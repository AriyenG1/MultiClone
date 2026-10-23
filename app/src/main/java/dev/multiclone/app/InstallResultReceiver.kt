package dev.multiclone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.launch

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val app = context.applicationContext as MultiCloneApplication
        val id = intent.getStringExtra("cloneId") ?: return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            // Installation is initiated from the visible activity. Keep Android's confirmation intact.
            try {
                requireNotNull(confirmation) { "Android did not supply an installation confirmation." }
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                intent.data?.lastPathSegment?.toIntOrNull()?.let { session ->
                    runCatching { app.packageManager.packageInstaller.abandonSession(session) }
                }
                saveResult(app, id, false, "Android's installation confirmation could not open. Return to MultiClone and retry. ${e.message.orEmpty()}")
            }
            return
        }
        app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).edit().putInt("status:$id", status).apply()
        saveResult(app, id, status == PackageInstaller.STATUS_SUCCESS, InstallFeedback.message(intent))
    }

    private fun saveResult(app: MultiCloneApplication, id: String, success: Boolean, message: String) {
        app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).edit()
            .putBoolean("failed", !success).putString("latest", message).putString("cloneId", id)
            .putLong("revision", System.nanoTime()).apply()
        val pending = goAsync()
        app.scope.launch {
            try {
                app.ready.await()
                app.queue.withCloneMutation {
                    app.dao.clone(id)?.let { app.dao.saveClone(it.copy(state = if (success) "INSTALLED" else "READY", message = message,
                        wasInstalled = it.wasInstalled || success)) }
                }
            }
            finally { pending.finish() }
        }
    }
}
