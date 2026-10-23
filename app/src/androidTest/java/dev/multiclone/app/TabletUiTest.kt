package dev.multiclone.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import dev.multiclone.core.*
import dev.multiclone.storage.AppPreferences
import dev.multiclone.storage.SourceRow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TabletUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as MultiCloneApplication
    private fun text(value: String) = device.wait(Until.findObject(By.text(value)), 15000)
        ?: error("Missing UI: $value")
    private fun shot(name: String) {
        device.takeScreenshot(File(File(app.filesDir, "ui-proof").apply { mkdirs() }, "$name.png"))
    }
    private suspend fun awaitState(check: () -> Boolean) {
        withTimeout(90000) { while (!withContext(Dispatchers.Main) { check() }) delay(100) }
    }
    private suspend fun withTablet(block: suspend (ApkSource, MultiCloneViewModel) -> Unit) {
        app.ready.await()
        val prefs = AppPreferences(app)
        val previousSource = prefs.selectedSource.first()
        val previousSize = device.executeShellCommand("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(": ")
        try {
            // Clear stale confirmation windows/sessions left by interrupted emulator runs.
            device.executeShellCommand("am force-stop com.android.packageinstaller")
            app.packageManager.packageInstaller.mySessions.forEach { session ->
                runCatching { app.packageManager.packageInstaller.abandonSession(session.sessionId) }
            }
            device.executeShellCommand("wm size 1280x800")
            device.executeShellCommand("appops set dev.multiclone.app REQUEST_INSTALL_PACKAGES allow")
            device.executeShellCommand("pm grant dev.multiclone.app android.permission.POST_NOTIFICATIONS")
            android.os.SystemClock.sleep(1500)
            val input = File(app.filesDir, "ui-design-fixture.apk")
            instrumentation.context.assets.open("fixture.apk").use { source -> input.outputStream().use { source.copyTo(it) } }
            val analyzed = app.analyzer.analyze(listOf(input), "UI design fixture")
            val source = analyzed.copy(findings = analyzed.findings +
                Finding("UI_LONG_WARNING", Severity.WARNING, "Long compatibility warning for UI verification. ".repeat(60)))
            app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
            prefs.selectSource(source.id)
            val previousActivities = withContext(Dispatchers.Main) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toSet()
            }
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val activity = withTimeout(15000) {
                var fresh: MainActivity? = null
                while (fresh == null) {
                    fresh = withContext(Dispatchers.Main) {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull { it !in previousActivities && !it.isDestroyed }
                    }
                    if (fresh == null) delay(100)
                }
                fresh
            }
            device.waitForIdle()
            text("UI design fixture")
            val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[MultiCloneViewModel::class.java] }
            withContext(Dispatchers.Main) { assertTrue("Must test the current Activity's ViewModel", model.viewModelScope.isActive) }
            block(source, model)
        } finally {
            device.executeShellCommand("am force-stop com.android.packageinstaller")
            app.packageManager.packageInstaller.mySessions.forEach { session ->
                runCatching { app.packageManager.packageInstaller.abandonSession(session.sessionId) }
            }
            prefs.selectSource(previousSource)
            device.executeShellCommand("wm size ${previousSize ?: "reset"}")
        }
    }

    @Test fun warningDetailsStartCollapsedAndCanExpand(): Unit = runBlocking {
        withTablet { source, _ ->
            assertFalse(device.hasObject(By.text("Your clone library")))
            text("Show warnings (${source.findings.size})")
            assertFalse(device.hasObject(By.textStartsWith("WARNING: Long compatibility")))
            shot("tablet-landscape")
            text("Show warnings (${source.findings.size})").click()
            text("Hide warnings")
            val blocked = source.copy(findings = source.findings + Finding("UI_BLOCKED", Severity.BLOCKED, "Blocked test source"))
            app.dao.saveSource(SourceRow(blocked.id, blocked.label, blocked.packageName, JsonCodec.source(blocked)))
            try {
                app.queue.enqueue(blocked.id, "Must not generate", 1, null)
                fail("Blocked sources must never enter generation")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("blocking compatibility"))
            }
            app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
        }
    }

    @Test fun generationOpensInstallWindowAndLaterSuppressesFurtherPopups(): Unit = runBlocking {
        withTablet { source, model ->
            withContext(Dispatchers.Main) { model.enqueue(source, "UI generation proof", 1) }
            awaitState { model.generatedCloneIds.size == 1 && model.installPromptOpen && !model.busy }
            text("Your clones are ready")
            shot("install-window")
            withContext(Dispatchers.Main) { model.dismissInstallPrompt() }
            assertTrue(device.wait(Until.gone(By.text("Your clones are ready")), 5000))
            app.queue.enqueue(source.id, "Later UI proof", 1, null)
            withContext(Dispatchers.IO) { app.queue.run { } }
            awaitState { model.generatedCloneIds.size == 2 }
            withContext(Dispatchers.Main) { assertFalse(model.installPromptOpen); model.openInstallPrompt() }
            text("Your clones are ready")
            val id = withContext(Dispatchers.Main) { model.generatedCloneIds.first() }
            val row = app.dao.clone(id)!!
            withContext(Dispatchers.Main) {
                assertTrue("ViewModel was cleared before install", model.viewModelScope.isActive)
                assertFalse(model.busy)
                model.install(row)
            }
            awaitState { !model.busy }
            val confirmation = device.wait(Until.findObject(By.res("com.android.packageinstaller:id/ok_button")), 15000)
                ?: device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)install|update"))), 5000)
            if (confirmation == null) {
                shot("installer-failure")
                device.dumpWindowHierarchy(File(app.filesDir, "ui-proof/installer-failure.xml"))
            }
            assertNotNull("Install must reach Android confirmation: ${model.message}", confirmation)
            shot("android-install-confirmation")
            device.pressBack()
            withContext(Dispatchers.Main) { model.dismissInstallPrompt() }
        }
    }
}
