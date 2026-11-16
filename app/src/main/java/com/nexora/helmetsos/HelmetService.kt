package com.nexora.helmetsos

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
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
                post(token, "sendMessage", mapOf("chat_id" to chat, "text" to sb.toString()))
                if (loc != null) {
                    post(token, "sendLocation", mapOf(
                        "chat_id" to chat,
                        "latitude" to String.format(Locale.US, "%.6f", loc.latitude),
                        "longitude" to String.format(Locale.US, "%.6f", loc.longitude)
                    ))
                }
                setStatus("Alert sent to Telegram.")
            } catch (e: Exception) {
                lastSent = 0L
                setStatus("Sending failed: ${e.message}")
            }
        }.start()
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
