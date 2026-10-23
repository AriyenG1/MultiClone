package dev.multiclone.app

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Interpret the detailed verifier result before the broad installer status category. */
object InstallFeedback {
    const val PREFERENCES = "install-results"
    // Optional diagnostic extra emitted by AOSP; read as data without calling hidden APIs.
    const val LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"

    fun message(intent: Intent): String {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) return "Installed successfully. Runtime compatibility is untested."
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(4000)
        val verificationFailure = detail.contains("INSTALL_FAILED_VERIFICATION_FAILURE") ||
            intent.hasExtra(LEGACY_STATUS) && intent.getIntExtra(LEGACY_STATUS, 0) == -22
        val explanation = when {
            verificationFailure -> "Android's package verifier rejected this installation. The result does not identify the reason or establish runtime compatibility. Check any system security notice, Play Protect history, Samsung Auto Blocker and device policy. Try cloning the supplied test app to compare."
            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked this installation. Review the system notice and applicable device policy or security settings."
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled or aborted by Android. This code alone does not identify who stopped it."
            status == PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the APK set as invalid. It may be malformed, incorrectly signed or inconsistent. Retain these details for diagnosis."
            status == PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android found an installation conflict, such as a permission or signing-certificate conflict. An update must retain its original clone identity and signing key."
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Android could not complete installation because of storage. Check available device space."
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android reported that this APK set is incompatible with the device. Review SDK, native-code and split requirements."
            else -> "Android could not complete installation. Review the system details below."
        }
        return buildString {
            append(explanation)
            append("\nInstaller status: ").append(status)
            if (intent.hasExtra(LEGACY_STATUS)) append(" · Package manager: ").append(intent.getIntExtra(LEGACY_STATUS, 0))
            if (intent.hasExtra(PackageInstaller.EXTRA_SESSION_ID)) append(" · Session: ").append(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
            intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)?.takeIf { it.isNotBlank() }?.let {
                append("\nRelated package reported by Android: ").append(it.take(300))
            }
            append("\nSystem message: ").append(detail.ifBlank { "No explanation supplied." })
            append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
            append(" · MultiClone ").append(BuildConfig.VERSION_NAME)
        }
    }
}
