package com.nexora.helmetsos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/**
 * Foreground service: keeps a Bluetooth LE link to the helmet and the phone's GPS warm.
 * When the helmet sends "CRASH", it posts the location to Telegram.
 * Location is only sent at that moment.
 */
class HelmetService : Service() {

    companion object {
        const val ACTION_START = "com.nexora.helmetsos.START"
        const val ACTION_TEST = "com.nexora.helmetsos.TEST"
        const val HELMET_NAME = "SmartHelmet"
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CHANNEL = "helmet_sos"
        private const val NOTIF_ID = 1

        @Volatile var status: String = "Stopped"
        @Volatile var listener: ((String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var lastFix: Location? = null
    private var lastSent = 0L
    @Volatile private var callNote = ""
    private lateinit var locationManager: LocationManager

    // ---------------- lifecycle ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!running) {
            running = true
            startLocation()
            startScan()
        }
        if (intent?.action == ACTION_TEST) sendAlert("test")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        unregisterCallWatcher()
        stopScan()
        closeGatt()
        stopLocation()
        status = "Stopped"
        listener?.invoke(status)
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Helmet SOS", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Helmet SOS is running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(s: String) {
        status = s
        handler.post {
            listener?.invoke(s)
            if (running) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(s))
            }
        }
    }

    // ---------------- location (kept warm, never sent unless a crash happens) ----------------

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val cur = lastFix
            if (cur == null || location.accuracy <= cur.accuracy + 20f || location.time - cur.time > 30_000L) {
                lastFix = location
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 5000L, 0f, locationListener, Looper.getMainLooper())
                }
            }
            lastFix = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            setStatus("Location permission missing")
        }
    }

    private fun stopLocation() {
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    // ---------------- Bluetooth LE ----------------

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord
            val byName = rec?.deviceName == HELMET_NAME
            val byUuid = rec?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if ((byName || byUuid) && gatt == null) {
                stopScan()
                setStatus("Helmet found. Connecting...")
                gatt = result.device.connectGatt(this@HelmetService, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus("Scan failed ($errorCode). Retrying...")
            retryScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running || scanning || gatt != null) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off. Turn it on.")
            retryScan()
            return
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            adapter.bluetoothLeScanner.startScan(null, settings, scanCallback)
            scanning = true
            setStatus("Looking for the helmet...")
        } catch (e: Exception) {
            setStatus("Cannot scan: ${e.message}")
            retryScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanning = false
    }

    private fun retryScan() {
        handler.postDelayed({ startScan() }, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                gatt = null
                if (running) {
                    setStatus("Helmet disconnected. Reconnecting...")
                    retryScan()
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                setStatus("Helmet service not found")
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
            setStatus("Helmet connected. Watching for a crash.")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleHelmetMessage(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleHelmetMessage(value)
        }
    }

    private fun handleHelmetMessage(value: ByteArray) {
        val text = String(value, Charsets.UTF_8).trim().uppercase(Locale.ROOT)
        if (text.contains("CRASH")) sendAlert("helmet")
    }

    // ---------------- Telegram ----------------

    private fun sendAlert(source: String) {
        val now = System.currentTimeMillis()
        if (source != "test" && now - lastSent < 30_000L) return
        lastSent = now

        val prefs = getSharedPreferences("helmet", MODE_PRIVATE)
        val token = prefs.getString("token", "").orEmpty()
        val chat = prefs.getString("chat", "").orEmpty()
        val phone = prefs.getString("phone", "").orEmpty()

        // Call the family first (works on the SIM, no internet needed); Telegram goes out in parallel.
        callNote = ""
        if (phone.isNotEmpty()) handler.post { startCalls(phone) }

        if (token.isEmpty() || chat.isEmpty()) {
            setStatus("Crash received, but Telegram is not set up.")
            return
        }

        val loc = lastFix
        setStatus(if (source == "test") "Sending test alert..." else "Crash received. Sending alert...")
        Thread {
            try {
                val sb = StringBuilder(
                    if (source == "test") "TEST: simulated crash alert from Helmet SOS."
                    else "CRASH DETECTED on the Smart Helmet."
                )
                if (loc != null) {
                    val ageMin = (System.currentTimeMillis() - loc.time) / 60_000L
                    sb.append("\nLocation: https://maps.google.com/?q=")
                        .append(String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude))
                        .append("\nAccuracy about ").append(loc.accuracy.toInt()).append(" m")
                    if (ageMin >= 2) sb.append(", last fix ").append(ageMin).append(" min ago")
                } else {
                    sb.append("\nLocation not available.")
                }
                val chats = chat.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                var ok = 0
                var lastErr: String? = null
                for (c in chats) {
                    try {
                        post(token, "sendMessage", mapOf("chat_id" to c, "text" to sb.toString()))
                        if (loc != null) {
                            post(token, "sendLocation", mapOf(
                                "chat_id" to c,
                                "latitude" to String.format(Locale.US, "%.6f", loc.latitude),
                                "longitude" to String.format(Locale.US, "%.6f", loc.longitude)
                            ))
                        }
                        ok++
                    } catch (e: Exception) {
                        lastErr = e.message
                    }
                }
                if (ok == 0) {
                    lastSent = 0L
                    setStatus("Sending failed: $lastErr")
                } else {
                    setStatus("Alert sent to $ok of ${chats.size} chats.$callNote")
                }
            } catch (e: Exception) {
                lastSent = 0L
                setStatus("Sending failed: ${e.message}")
            }
        }.start()
    }

    // ---------------- Phone calls (one number after another until someone answers) ----------------

    private var callQueue: List<String> = emptyList()
    private var callIdx = 0
    private var callActive = false
    private var callPhase = 0          // 0 idle, 1 dialing, 2 in call
    private var callGen = 0
    private var callStart = 0L
    private var offhookAt = 0L
    private var telephony: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    private fun startCalls(raw: String) {
        if (callActive) return
        val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return
        val canWatch = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            registerCallWatcher()
        if (!canWatch) {
            // Cannot tell whether a call was answered, so only call the first number.
            val err = placeCall(list[0])
            if (err != null) setStatus(err)
            return
        }
        callQueue = list
        callIdx = 0
        callActive = true
        dialNext()
    }

    private fun dialNext() {
        val total = callQueue.size * 2            // each number is tried up to twice
        if (callIdx >= total) {
            callActive = false
            callPhase = 0
            setStatus("Nobody answered the calls.")
            return
        }
        val number = callQueue[callIdx % callQueue.size]
        callIdx++
        callPhase = 1
        callStart = System.currentTimeMillis()
        val gen = ++callGen
        setStatus("Calling $number ($callIdx of $total)...")
        val err = placeCall(number)
        if (err != null) {
            setStatus(err)
            handler.post { if (gen == callGen) dialNext() }
            return
        }
        // If the call never starts, move on after 20 seconds.
        handler.postDelayed({ if (gen == callGen && callPhase == 1) dialNext() }, 20_000L)
    }

    private fun onCallState(state: Int) {
        if (!callActive) return
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (callPhase == 1) {
                callPhase = 2
                offhookAt = System.currentTimeMillis()
            }
        } else if (state == TelephonyManager.CALL_STATE_IDLE && callPhase == 2) {
            callPhase = 0
            val connectedMs = System.currentTimeMillis() - offhookAt
            val gen = callGen
            // Give Android a moment to write the call log entry.
            handler.postDelayed({ if (gen == callGen) afterCall(connectedMs) }, 2500L)
        }
    }

    private fun afterCall(connectedMs: Long) {
        if (wasAnswered(connectedMs)) {
            callActive = false
            setStatus("Call answered.")
        } else {
            dialNext()
        }
    }

    /** Outgoing call log duration is 0 when nobody picked up. Falls back to call length if the log is unreadable. */
    private fun wasAnswered(connectedMs: Long): Boolean {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            var duration = -1L
            try {
                val c = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.DURATION),
                    CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.TYPE + " = ?",
                    arrayOf((callStart - 5000L).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                    CallLog.Calls.DATE + " DESC"
                )
                if (c != null) {
                    if (c.moveToFirst()) duration = c.getLong(0)
                    c.close()
                }
            } catch (e: Exception) {
                duration = -1L
            }
            if (duration >= 0L) return duration > 0L
        }
        return connectedMs > 30000L
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerCallWatcher(): Boolean {
        if (telephony != null) return true
        return try {
            val t = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { onCallState(state) }
                }
                t.registerTelephonyCallback(mainExecutor, cb)
                modernCallback = cb
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) { onCallState(state) }
                }
                t.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = l
            }
            telephony = t
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallWatcher() {
        try {
            val t = telephony ?: return
            if (Build.VERSION.SDK_INT >= 31) {
                modernCallback?.let { t.unregisterTelephonyCallback(it) }
            } else {
                legacyListener?.let { t.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {}
        telephony = null
        modernCallback = null
        legacyListener = null
    }

    /** Places one call. Returns null on success, or a short error message. */
    @SuppressLint("MissingPermission")
    private fun placeCall(number: String): String? {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Call not placed: Phone permission missing."
        }
        val uri = Uri.fromParts("tel", number, null)
        return try {
            (getSystemService(TELECOM_SERVICE) as TelecomManager).placeCall(uri, Bundle())
            null
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (e2: Exception) {
                "Call failed: ${e2.message}"
            }
        }
    }

    private fun post(token: String, method: String, params: Map<String, String>) {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw RuntimeException("Telegram replied ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }
}
