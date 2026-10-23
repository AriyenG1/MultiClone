package dev.multiclone.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.multiclone.core.CloneArtifacts
import dev.multiclone.core.sha256

class InstallCoordinator(private val context: Context, private val callbackClass: Class<*>) {
    fun canInstall() = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    fun install(artifacts: CloneArtifacts): Int {
        require(canInstall()) { "Allow installation from MultiClone in Android settings, then tap Install again." }
        require(artifacts.files.isNotEmpty()) { "No generated APKs are available." }
        artifacts.files.forEach { require(it.file.isFile && it.file.sha256() == it.sha256) { "Generated APK is missing or changed. Rebuild it before installation." } }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(artifacts.plan.packageName)
            setAppLabel(artifacts.plan.label)
            setSize(artifacts.files.sumOf { it.file.length() })
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                artifacts.files.forEachIndexed { index, part ->
                    session.openWrite(if (part.splitName == null) "base.apk" else "split-$index.apk", 0, part.file.length()).use { sink ->
                        part.file.inputStream().use { it.copyTo(sink) }; session.fsync(sink)
                    }
                }
                val callback = Intent(context, callbackClass).setAction("dev.multiclone.INSTALL_RESULT")
                    .setData(Uri.parse("multiclone://session/$id")).putExtra("cloneId", artifacts.plan.id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, callback, flags).intentSender)
            }
        } catch (e: Exception) { installer.abandonSession(id); throw e }
        return id
    }
    fun uninstallIntent(packageName: String) = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
    fun launch(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: error("No launchable installed clone found.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
