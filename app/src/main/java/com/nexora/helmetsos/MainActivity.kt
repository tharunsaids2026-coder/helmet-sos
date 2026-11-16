package com.nexora.helmetsos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var tokenField: EditText
    private lateinit var chatField: EditText
    private var pendingAction: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("helmet", MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun label(t: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        col.addView(label("Helmet SOS", 26f, true))
        col.addView(label("Runs in the background. When the helmet reports a crash, your phone's location is sent to Telegram."))

        statusView = label("Status: " + HelmetService.status, 16f, true)
        col.addView(statusView)

        col.addView(label("Telegram bot token", 14f, true))
        tokenField = EditText(this).apply {
            setText(prefs.getString("token", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        col.addView(tokenField)

        col.addView(label("Family chat ID", 14f, true))
        chatField = EditText(this).apply {
            setText(prefs.getString("chat", ""))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        col.addView(chatField)

        col.addView(button("Start protection") { run(HelmetService.ACTION_START) })
        col.addView(button("Stop") {
            stopService(Intent(this, HelmetService::class.java))
            statusView.text = "Status: Stopped"
        })
        col.addView(button("Send test alert (simulated crash)") { run(HelmetService.ACTION_TEST) })
        col.addView(button("Allow background running (battery)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        col.addView(label("Keep Bluetooth, Location and mobile data on. In the battery list, set Helmet SOS to Unrestricted or Not optimised."))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        statusView.text = "Status: " + HelmetService.status
        HelmetService.listener = { s -> runOnUiThread { statusView.text = "Status: $s" } }
    }

    override fun onPause() {
        HelmetService.listener = null
        super.onPause()
    }

    private fun needed(): List<String> {
        val l = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        return l
    }

    private fun missing() = needed().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun run(action: String) {
        getSharedPreferences("helmet", MODE_PRIVATE).edit()
            .putString("token", tokenField.text.toString().trim())
            .putString("chat", chatField.text.toString().trim())
            .apply()
        val m = missing()
        if (m.isNotEmpty()) {
            pendingAction = action
            requestPermissions(m.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, HelmetService::class.java).setAction(action))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val essentialMissing = missing().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        val action = pendingAction
        pendingAction = null
        if (essentialMissing.isEmpty() && action != null) run(action)
        else Toast.makeText(this, "Location and Bluetooth permissions are required.", Toast.LENGTH_LONG).show()
    }
}
