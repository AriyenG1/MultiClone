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
    private fun text(value: String): UiObject2 {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return device.wait(Until.findObject(By.text(value)), 15000) ?: error("Missing UI: $value")
    }
    private fun shot(name: String) {
        device.takeScreenshot(File(File(app.filesDir, "ui-proof").apply { mkdirs() }, "$name.png"))
    }
    private suspend fun awaitState(check: () -> Boolean) {
        withTimeout(90000) { while (!withContext(Dispatchers.Main) { check() }) delay(100) }
    }
    private suspend fun withTablet(selectSource: Boolean = true, block: suspend (ApkSource, MultiCloneViewModel) -> Unit) {
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
            val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[MultiCloneViewModel::class.java] }
            if (selectSource) {
                withContext(Dispatchers.Main) { model.select(source.id) }
                text("UI design fixture")
            }
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
    @Test fun libraryUpdateRebuildsFromOriginalAndPreservesDataAfterCancelledInstall(): Unit = runBlocking {
        withTablet { source, model ->
            device.executeShellCommand("pm uninstall ${source.packageName}")
            suspend fun confirmInstall(id: String, accept: Boolean = true) {
                val button = device.wait(Until.findObject(By.pkg("com.android.packageinstaller")
                    .text(java.util.regex.Pattern.compile("(?i)install|update"))), 20000)
                assertNotNull("Android confirmation missing: ${model.message}", button)
                if (accept) button!!.click() else device.pressBack()
                withTimeout(30000) {
                    while (!app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).contains("status:$id")) delay(100)
                }
                val status = app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).getInt("status:$id", -99)
                if (accept) assertEquals(android.content.pm.PackageInstaller.STATUS_SUCCESS, status)
                else assertNotEquals(android.content.pm.PackageInstaller.STATUS_SUCCESS, status)
            }
            suspend fun installOriginal(file: File, version: Long) {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val plan = ClonePlan.create("Update original").copy(packageName = source.packageName)
                app.installer.install(CloneArtifacts(plan, listOf(SignedArtifact(file, null, file.sha256())), version))
                confirmInstall(plan.id)
            }
            installOriginal(source.parts.single().file, 1)
            app.queue.enqueue(source.id, "Single update proof", 1, null)
            app.queue.run { }
            val row = app.dao.clones().first { it.sourceId == source.id && it.label == "Single update proof" }
            withContext(Dispatchers.Main) { model.dismissInstallPrompt() }
            text("Library").click()
            text(row.label)
            assertFalse("Never-installed clones must not show Update", device.hasObject(By.text("Update")))
            assertFalse(device.hasObject(By.text("Mark tested working")))
            text("Install").click()
            awaitState { row.id in model.cloneOperations }
            confirmInstall(row.id)
            awaitState { row.id !in model.cloneOperations && !model.busy }
            app.installer.launch(row.packageName)
            text("Increment").click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Count: 1")), 10000))
            device.findObject(By.clazz("android.widget.EditText")).text = "Preserved account"
            text("Save mock login").click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Account: Preserved account")), 10000))
            val certificate = app.vault.getOrCreate(row.id).certificate.encoded
            val newer = File(app.filesDir, "single-update-v2.apk")
            instrumentation.context.assets.open("fixture-v2.apk").use { input -> newer.outputStream().use { input.copyTo(it) } }
            installOriginal(newer, 2)
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withContext(Dispatchers.Main) { model.dismissInstallPrompt(); model.refreshInstalled() }
            awaitState { row.packageName in model.installedPackages }
            text("Library").click()
            text("Your clone library")
            assertFalse(device.hasObject(By.text("Mark tested working")))
            assertFalse(device.hasObject(By.text("Install update")))
            app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).edit().remove("status:${row.id}").commit()
            // The single Library action must go straight from original-APK checking to Android.
            text("Update").click()
            awaitState { row.id in model.cloneOperations }
            confirmInstall(row.id, accept = false)
            awaitState { row.id !in model.cloneOperations && !model.busy }
            assertEquals(1L, app.packageManager.getPackageInfo(row.packageName, 0).longVersionCode)
            assertEquals(2L, app.dao.clone(row.id)!!.versionCode)
            text("Your clone library")
            shot("library-update-ready")
            val jobsBefore = app.dao.observeJobs().first().count { it.updateCloneId == row.id }
            app.getSharedPreferences(InstallFeedback.PREFERENCES, 0).edit().remove("status:${row.id}").commit()
            text("Update").click()
            confirmInstall(row.id)
            awaitState { row.id !in model.cloneOperations && !model.busy }
            assertEquals("Retry must reuse the verified update", jobsBefore, app.dao.observeJobs().first().count { it.updateCloneId == row.id })
            assertEquals(2L, app.packageManager.getPackageInfo(row.packageName, 0).longVersionCode)
            assertArrayEquals(certificate, app.vault.getOrCreate(row.id).certificate.encoded)
            assertEquals(row.packageName, app.dao.clone(row.id)!!.packageName)
            app.installer.launch(row.packageName)
            assertTrue(device.wait(Until.hasObject(By.textContains("Count: 1")), 10000))
            assertTrue(device.hasObject(By.textContains("Provider: 1")))
            assertTrue(device.hasObject(By.textContains("Account: Preserved account")))
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withContext(Dispatchers.Main) { model.updateClone(app.dao.clone(row.id)!!) }
            awaitState { row.id !in model.cloneOperations && model.cloneUpdateNotice?.text?.contains("up to date") == true }
            assertEquals(jobsBefore, app.dao.observeJobs().first().count { it.updateCloneId == row.id })
            text("Details") // The new floating notice, rather than a persistent status banner.
            shot("update-notice")
            delay(5500)
            withContext(Dispatchers.Main) { assertNull("Update feedback expires without dismissal", model.cloneUpdateNotice) }
        }
    }

    private fun tagged(tag: String): UiObject2 {
        fun clearCache() { if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache() }
        fun scroll(action: Int): Boolean {
            clearCache()
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isScrollable && node.packageName?.toString() == app.packageName) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            return find(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(action) == true
        }
        clearCache()
        device.findObject(By.res(tag))?.let { return it }
        repeat(6) {
            if (scroll(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) android.os.SystemClock.sleep(250)
        }
        repeat(12) {
            clearCache()
            device.findObject(By.res(tag))?.let { return it }
            scroll(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            android.os.SystemClock.sleep(300)
        }
        device.executeShellCommand("screencap -p /data/local/tmp/multiclone-missing.png")
        shot("missing-$tag")
        device.dumpWindowHierarchy(File(app.filesDir, "ui-proof/missing-$tag.xml"))
        error("Missing control: $tag")
    }

    @Test fun freshCreateHasNoImportHistoryAndSmallBatchesKeepIndividualNames(): Unit = runBlocking {
        withTablet(selectSource = false) { source, model ->
            assertEquals("", withContext(Dispatchers.Main) { model.selectedId })
            assertFalse(device.hasObject(By.text("UI design fixture")))
            assertFalse(device.hasObject(By.text("Use a previous import")))
            assertFalse(device.hasObject(By.text("MultiClone")))
            assertFalse(device.hasObject(By.text("On-device")))
            assertFalse(device.hasObject(By.text("Settings")))
            val settings = device.wait(Until.findObject(By.desc("Settings")), 5000)!!
            assertTrue("Settings belongs at the bottom of the rail", settings.visibleCenter.y > device.displayHeight * .75)
            shot("create-empty")
            settings.click()
            text("Signing keys & device")
            text("Create").click()
            withContext(Dispatchers.Main) { model.select(source.id) }
            assertEquals("", tagged("clone-count").text)
            assertEquals("", tagged("clone-label").text)
            tagged("clone-count").text = "5"
            assertFalse(device.hasObject(By.text("Choose custom icon")))
            tagged("clone-name-5")
            tagged("clone-count").text = "6"
            tagged("clone-label").text = "Large batch"
            assertFalse(device.hasObject(By.text("Clone 1 name")))
            tagged("clone-count").text = "2"
            tagged("clone-name-1").text = "Personal copy"
            tagged("clone-name-2").text = "Work copy"
            device.executeShellCommand("wm size 800x1280")
            delay(1200)
            assertEquals("Personal copy", tagged("clone-name-1").text)
            assertEquals("Work copy", tagged("clone-name-2").text)
            shot("create-individual-names")
            tagged("create-clones").click()
            awaitState { model.generatedCloneIds.size == 2 && !model.busy }
            val rows = app.dao.clones().filter { it.id in model.generatedCloneIds }
            assertEquals(setOf("Personal copy", "Work copy"), rows.map { it.label }.toSet())
            assertTrue(rows.all { JsonCodec.plan(it.plan).icon == null })
            withContext(Dispatchers.Main) { assertEquals("", model.selectedId); model.dismissInstallPrompt() }
            assertTrue(device.wait(Until.gone(By.text("Your clones are ready")), 5000))
            withContext(Dispatchers.Main) { model.select(source.id) }
            assertEquals("", tagged("clone-count").text)
            assertEquals("", tagged("clone-label").text)
        }
    }

}
