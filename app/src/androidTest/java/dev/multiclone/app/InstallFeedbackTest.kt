package dev.multiclone.app

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiclone.storage.CloneRow
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InstallFeedbackTest {
    @Test fun verificationFailureWithAbortedStatusReplacesPendingBannerAndPersistsDetails() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
        app.ready.await()
        val id = UUID.randomUUID().toString().replace("-", "")
        app.dao.saveClone(CloneRow(id, "diagnostic-test", "Diagnostic fixture", "dev.multiclone.test.c${id.replace("-", "")}", "{}", state = "READY"))
        val store = ViewModelStore()
        lateinit var model: MultiCloneViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MultiCloneViewModel::class.java]
            model.tell("Installation requested. Complete Android's confirmation.")
        }
        try {
            val result = Intent(app, InstallResultReceiver::class.java).putExtra("cloneId", id)
                // Samsung's screenshot reports 3 (ABORTED), with a more specific verifier error.
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl123.tmp")
                .putExtra(InstallFeedback.LEGACY_STATUS, -22)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 123)
                .putExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME, "example.verifier")
            app.sendBroadcast(result)
            withTimeout(15000) {
                while (app.dao.clone(id)?.message?.contains("package verifier rejected") != true) delay(50)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(model.message.contains("package verifier rejected"))
                assertTrue(model.messageIsError)
            }
            val saved = app.dao.clone(id)!!
            assertEquals("READY", saved.state)
            assertTrue(saved.message.contains("Installer status: 3"))
            assertTrue(saved.message.contains("Package manager: -22"))
            assertTrue(saved.message.contains("example.verifier"))
            assertTrue(saved.message.contains("Install not allowed for"))

            // An identical result must notify the UI again after a new attempt.
            instrumentation.runOnMainSync { model.tell("Installation requested. Complete Android's confirmation.") }
            val prefs = app.getSharedPreferences(InstallFeedback.PREFERENCES, 0)
            val revision = prefs.getLong("revision", 0)
            app.sendBroadcast(result)
            withTimeout(15000) { while (prefs.getLong("revision", 0) == revision) delay(50) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(model.messageIsError); assertTrue(model.message.contains("package verifier rejected")) }

            val success = Intent(result).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            app.sendBroadcast(success)
            withTimeout(15000) { while (prefs.getBoolean("failed", true)) delay(50) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertFalse(model.messageIsError); assertEquals("Successful installation must not show a banner", "", model.message) }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            // Wait for the receiver's asynchronous Room write before removing the fixture.
            delay(200)
            app.dao.deleteClone(id)
        }
    }

    @Test fun broadStatusesDoNotInventAVerifierRejection() {
        fun feedback(status: Int) = InstallFeedback.message(Intent().putExtra(PackageInstaller.EXTRA_STATUS, status))
        assertTrue(feedback(PackageInstaller.STATUS_FAILURE_ABORTED).contains("cancelled or aborted"))
        assertTrue(feedback(PackageInstaller.STATUS_FAILURE_BLOCKED).contains("Android blocked"))
        assertTrue(feedback(PackageInstaller.STATUS_FAILURE_INVALID).contains("APK set as invalid"))
        assertFalse(feedback(PackageInstaller.STATUS_FAILURE_ABORTED).contains("package verifier rejected"))
        assertTrue(InstallFeedback.message(Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
            .putExtra(InstallFeedback.LEGACY_STATUS, -22)).contains("package verifier rejected"))
    }
}
