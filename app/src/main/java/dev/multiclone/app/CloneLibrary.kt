package dev.multiclone.app

import android.content.pm.PackageManager
import dev.multiclone.storage.CloneRow
import java.io.File

/** Owns clone removal and reconciliation; serialized with individual build publication. */
class CloneLibrary(private val app: MultiCloneApplication) {
    private fun installedVersion(packageName: String): Long? = try {
        app.packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) { null }

    /** False means the app is installed and Android must confirm its removal first. */
    suspend fun deleteIfUninstalled(id: String): Boolean = app.queue.withCloneMutation {
        val row = app.dao.clone(id) ?: return@withCloneMutation true
        if (installedVersion(row.packageName) != null) {
            if (!row.wasInstalled) app.dao.saveClone(row.copy(wasInstalled = true))
            return@withCloneMutation false
        }
        require(app.packageManager.packageInstaller.mySessions.none { it.appPackageName == row.packageName }) {
            "Finish or cancel this clone's pending Android installation before deleting it."
        }
        remove(row)
        true
    }

    suspend fun reconcile(): Map<String, Long> = app.queue.withCloneMutation {
        val installed = mutableMapOf<String, Long>()
        val installing = app.packageManager.packageInstaller.mySessions.mapNotNull { it.appPackageName }.toSet()
        for (row in app.dao.clones()) {
            val version = installedVersion(row.packageName)
            if (version != null) {
                installed[row.packageName] = version
                // Retire old manual testing labels without changing app data or signing identities.
                val legacyMessage = row.message.contains("untested", true) || row.message.startsWith("Marked working by you") ||
                    row.message == "Generated and signature verified. Install and test this app's behavior."
                if (!row.wasInstalled || row.state == "TESTED" || legacyMessage) app.dao.saveClone(row.copy(wasInstalled = true,
                    state = if (row.state == "TESTED") { if (version == row.versionCode) "INSTALLED" else "READY" } else row.state,
                    message = if (legacyMessage) "" else row.message))
            } else if ((row.wasInstalled || row.state in setOf("INSTALLED", "TESTED")) && row.packageName !in installing) {
                remove(row)
            } else if (row.message == "Generated and signature verified. Install and test this app's behavior.") {
                app.dao.saveClone(row.copy(message = "Generated and signature verified."))
            }
        }
        installed
    }

    private suspend fun remove(row: CloneRow) {
        require(row.id.matches(Regex("[a-f0-9]{32}"))) { "Invalid clone identifier." }
        val root = File(app.filesDir, "artifacts").canonicalFile
        val directory = File(root, row.id)
        require(directory.canonicalFile.parentFile == root) { "Invalid clone artifact directory." }
        app.dao.cancelUpdates(row.id)
        check(!directory.exists() || directory.deleteRecursively()) { "Could not delete generated APK files. Retry deletion." }
        app.vault.delete(row.id)
        app.dao.deleteClone(row.id)
        app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).edit().remove("status:${row.id}").apply()
    }
}
