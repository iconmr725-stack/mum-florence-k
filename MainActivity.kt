package com.mumflorence.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One screen: tap "Set up Mum Florence", allow each thing she asks for, done. */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var keyBox: EditText
    private lateinit var ccBox: EditText
    private lateinit var dbBox: EditText
    private var wizard = false
    private val asked = HashSet<String>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        buildUi()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun tv(t: String, size: Float) = TextView(this).apply { text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(tv("Mum Florence", 30f))
        status = tv("", 16f)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Set up Mum Florence"
            setOnClickListener { wizard = true; asked.clear(); nextStep() }
        })
        root.addView(tv("Optional settings", 18f))
        keyBox = EditText(this).apply {
            hint = "Anthropic API key (for answering any question online)"
            setText(store.get("api_key"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ccBox = EditText(this).apply { hint = "Your country code, e.g. +234"; setText(store.get("country_code")) }
        dbBox = EditText(this).apply { hint = "Your database web address (optional)"; setText(store.get("db_url")) }
        root.addView(keyBox); root.addView(ccBox); root.addView(dbBox)
        root.addView(Button(this).apply { text = "Save settings"; setOnClickListener { saveSettings(); toast("Saved") } })
        root.addView(tv(
            "Say \"Mum Florence\" any time. Try: \"call John\", \"text Mary saying I am coming\", " +
                "\"open WhatsApp\", \"read my notifications\", \"answer the call\", \"what is the time\". " +
                "Add \"in text\" to get the answer as text.\n\n" +
                "If a switch is greyed out (Android 13+): Settings > Apps > Mum Florence > three dots > Allow restricted settings.",
            14f
        ))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun saveSettings() {
        store.put("api_key", keyBox.text.toString().trim())
        store.put("country_code", ccBox.text.toString().trim())
        store.put("db_url", dbBox.text.toString().trim())
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ checks
    private fun runtimePerms(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun missingPerms() = runtimePerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun listenerOk() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
    private fun a11yOk() = Settings.Secure.getString(contentResolver, "enabled_accessibility_services")?.contains(packageName) == true

    private fun refreshStatus() {
        fun m(ok: Boolean) = if (ok) "✓ " else "✗ "
        status.text = m(FlorenceService.instance != null) + "Mum Florence is " + (if (FlorenceService.instance != null) "listening" else "not running") + "\n" +
            m(missingPerms().isEmpty()) + "Microphone, phone, SMS and contacts\n" +
            m(overlayOk()) + "Open apps while the phone is asleep\n" +
            m(batteryOk()) + "Stay awake in the background\n" +
            m(listenerOk()) + "Read and reply to notifications\n" +
            m(a11yOk()) + "Read other apps' screens"
    }

    // ------------------------------------------------------------------ the wizard
    private fun step(name: String, ok: Boolean, message: String, launch: () -> Unit): Boolean {
        if (ok || !asked.add(name)) return false
        toast(message)
        launch()
        return true
    }

    private fun nextStep() {
        saveSettings()
        refreshStatus()
        val miss = missingPerms()
        if (miss.isNotEmpty() && asked.add("perms")) { requestPermissions(miss.toTypedArray(), 1); return }
        if (step("overlay", overlayOk(), "Switch ON for Mum Florence, then press Back") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }) return
        if (step("battery", batteryOk(), "Tap Allow so I can keep listening") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }) return
        if (step("listener", listenerOk(), "Switch ON Mum Florence, then press Back") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) return
        if (step("a11y", a11yOk(), "Open Mum Florence in the list and switch it ON, then press Back") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) return
        wizard = false
        startFlorence()
        refreshStatus()
    }

    private fun startFlorence() {
        if (!micOk()) return
        try { startForegroundService(Intent(this, FlorenceService::class.java)) } catch (e: Exception) { toast("Could not start: ${e.message}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (wizard) nextStep() else refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (wizard) nextStep() else if (micOk() && FlorenceService.instance == null) {
            startFlorence()
            status.postDelayed({ refreshStatus() }, 1500)
        }
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }
}
