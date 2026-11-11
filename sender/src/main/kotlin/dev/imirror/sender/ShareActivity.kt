package dev.imirror.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Invisible share target: parse, hand off to [SendService], finish. */
class ShareActivity : Activity() {
    private var shareable: Shareable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = parse(intent)
        if (parsed == null) {
            Toast.makeText(this, "Can't send this to the TV", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        shareable = parsed

        // Ask once; the service works without the permission, the notification is just hidden.
        val prefs = getSharedPreferences("tv", MODE_PRIVATE)
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)
        if (needsNotificationPermission) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        } else {
            send(parsed)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        shareable?.let(::send)
    }

    private fun send(item: Shareable) {
        Toast.makeText(this, "Sending to TV…", Toast.LENGTH_SHORT).show()
        startForegroundService(SendService.intentFor(this, item))
        finish()
    }

    private fun parse(intent: Intent): Shareable? {
        val uri: Uri? = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        }
        // The provider knows the real type; intent.type can be a wildcard like image/*.
        val mime = uri?.let { contentResolver.getType(it) } ?: intent.type
        return Shareable.from(mime, uri)
    }
}
