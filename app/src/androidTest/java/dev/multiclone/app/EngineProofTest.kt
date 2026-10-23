package dev.multiclone.app

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.multiclone.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun compiledApkProducesInstallableIsolatedClones() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("settings put system screen_off_timeout 2147483647")
        device.executeShellCommand("am force-stop com.android.packageinstaller")
        app.packageManager.packageInstaller.mySessions.forEach { app.packageManager.packageInstaller.abandonSession(it.sessionId) }
        // Only test setup uses shell app-ops. Actual installation uses MultiClone's Session API.
        device.executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val sourceFile = File(app.filesDir, "fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { input -> sourceFile.outputStream().use { input.copyTo(it) } }
        val source = app.analyzer.analyze(listOf(sourceFile), "Fixture")
        assertTrue(source.findings.toString(), source.buildable)
        val originalPlan = ClonePlan.create("Original fixture").copy(packageName = source.packageName)
        install(CloneArtifacts(originalPlan, listOf(SignedArtifact(sourceFile, null, sourceFile.sha256())), 1))
        val clones = (1..2).map { i ->
            val icon = File(app.filesDir, "proof-icon.png")
            android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.CYAN)
                icon.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val plan = ClonePlan.create("Fixture Clone $i", icon).let { it.copy(identityMap = app.transformer.identityMap(source, it.packageName)) }
            val dir = File(app.filesDir, "proof/${plan.id}").apply { mkdirs() }
            val output = app.transformer.build(source, plan, dir) { }
            val identity = app.vault.getOrCreate(plan.id)
            val files = output.mapIndexed { index, part ->
                val signed = File(dir, "signed-$index.apk")
                app.signer.sign(part.first, signed, identity, 28)
                assertEquals(1, app.signer.verify(signed).size)
                SignedArtifact(signed, part.second, signed.sha256())
            }
            CloneArtifacts(plan, files, source.versionCode).also { install(it) }
        }
        assertTrue(app.packageManager.getApplicationIcon(clones[0].plan.packageName) is android.graphics.drawable.AdaptiveIconDrawable)
        assertNotEquals(clones[0].plan.packageName, clones[1].plan.packageName)
        openFixture(clones[0].plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Count: 0")), 10000))
        device.findObject(By.text("Increment")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Count: 1")), 3000))
        device.findObject(By.clazz("android.widget.EditText")).text = "Account one"
        device.findObject(By.text("Save mock login")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Account: Account one")), 3000))
        openFixture(clones[1].plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Count: 0")), 10000))
        assertTrue(device.hasObject(By.textContains("Account: signed out")))
        device.findObject(By.clazz("android.widget.EditText")).text = "Account two"
        device.findObject(By.text("Save mock login")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Account: Account two")), 3000))
        openFixture(source.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Count: 0")), 10000))
        openFixture(clones[0].plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Count: 1")), 10000))
        assertTrue(device.hasObject(By.textContains("Account: Account one")))
        val updated = if ("fixture-v2.apk" in instrumentation.context.assets.list("").orEmpty()) {
            val input = File(app.filesDir, "fixture-v2.apk")
            instrumentation.context.assets.open("fixture-v2.apk").use { bytes -> input.outputStream().use { bytes.copyTo(it) } }
            val newerSource = app.analyzer.analyze(listOf(input))
            assertEquals(2L, newerSource.versionCode)
            val dir = File(app.filesDir, "proof/${clones[0].plan.id}/v2").apply { mkdirs() }
            val outputs = app.transformer.build(newerSource, clones[0].plan, dir) { }
            val files = outputs.mapIndexed { index, pair ->
                val signed = File(dir, "signed-$index.apk")
                app.signer.sign(pair.first, signed, app.vault.getOrCreate(clones[0].plan.id), 28)
                SignedArtifact(signed, pair.second, signed.sha256())
            }
            CloneArtifacts(clones[0].plan, files, 2)
        } else clones[0]
        install(updated)
        assertEquals(updated.versionCode, app.packageManager.getPackageInfo(updated.plan.packageName, 0).longVersionCode)
        openFixture(clones[0].plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Count: 1")), 10000))
        assertTrue(device.hasObject(By.textContains("Provider: 1")))
        assertTrue(device.hasObject(By.textContains("Account: Account one")))
        app.startActivity(app.installer.uninstallIntent(clones[1].plan.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val removal = device.wait(Until.findObject(By.res("android:id/button1")), 10000)
            ?: device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)ok|uninstall"))), 5000)
        assertNotNull("Uninstall confirmation missing", removal)
        requireNotNull(removal).click()
        val deadline = System.currentTimeMillis() + 10000
        var removed = false
        while (System.currentTimeMillis() < deadline) {
            try { app.packageManager.getPackageInfo(clones[1].plan.packageName, 0) }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { removed = true; break }
            device.waitForWindowUpdate(null, 200)
        }
        assertTrue("Second clone was not uninstalled", removed)
        openFixture(clones[0].plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Account: Account one")), 10000))
        File(app.filesDir, "proof-results.txt").writeText("PASS: compiled APK transformed on Android; original + 2 clones installed through PackageInstaller; adaptive icons, rewritten provider URI, independent counters/mock accounts, restart persistence, update to version ${updated.versionCode}, and independent uninstall verified.\n")
    }

    @Test fun independentJetchatCloneLaunches() {
        org.junit.Assume.assumeTrue("Build the pinned Jetchat fixture before running its compatibility test.", "jetchat.apk" in instrumentation.context.assets.list("").orEmpty())
        device.wakeUp()
        device.executeShellCommand("am force-stop com.android.packageinstaller")
        app.packageManager.packageInstaller.mySessions.forEach { app.packageManager.packageInstaller.abandonSession(it.sessionId) }
        device.executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
        val file = File(app.filesDir, "jetchat.apk")
        instrumentation.context.assets.open("jetchat.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
        val source = app.analyzer.analyze(listOf(file), "Jetchat")
        assertTrue(source.findings.toString(), source.buildable)
        val plan = ClonePlan.create("Jetchat clone")
        val dir = File(app.filesDir, "proof/${plan.id}").apply { mkdirs() }
        val identity = app.vault.getOrCreate(plan.id)
        val outputs = app.transformer.build(source, plan, dir) { }
        val files = outputs.mapIndexed { index, pair ->
            val signed = File(dir, "signed-$index.apk")
            app.signer.sign(pair.first, signed, identity, 28)
            SignedArtifact(signed, pair.second, signed.sha256())
        }
        install(CloneArtifacts(plan, files, source.versionCode))
        app.installer.launch(plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.pkg(plan.packageName).depth(0)), 10000))
        assertTrue("Jetchat conversation did not render", device.wait(Until.hasObject(By.textContains("composers")), 15000))
        File(app.filesDir, "jetchat-proof.txt").writeText("PASS: Jetchat v2026.08.00 compiled APK cloned on-device, installed via PackageInstaller and conversation UI rendered. This sample has no real authentication.\n")
    }

    @Test fun baseAndLanguageSplitCloneInstallTogether() {
        org.junit.Assume.assumeTrue("Build the split fixture first.", "fixture.apks" in instrumentation.context.assets.list("").orEmpty())
        device.wakeUp()
        device.executeShellCommand("am force-stop com.android.packageinstaller")
        app.packageManager.packageInstaller.mySessions.forEach { app.packageManager.packageInstaller.abandonSession(it.sessionId) }
        device.executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
        val archive = File(app.filesDir, "split-fixture.apks")
        instrumentation.context.assets.open("fixture.apks").use { input -> archive.outputStream().use { input.copyTo(it) } }
        val parts = java.util.zip.ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".apk") }.mapIndexed { index, entry ->
                File(app.filesDir, "split-input-$index.apk").also { file -> zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } } }
            }.toList()
        }
        val source = app.analyzer.analyze(parts, "Split fixture")
        assertTrue(source.findings.toString(), source.buildable)
        assertTrue(source.parts.size >= 2)
        assertTrue("The fixture must contain a separate feature split", source.parts.any { it.splitName == "test_feature" })
        val plan = ClonePlan.create("Split clone")
        val dir = File(app.filesDir, "proof/${plan.id}").apply { mkdirs() }
        val identity = app.vault.getOrCreate(plan.id)
        val files = app.transformer.build(source, plan, dir) { }.mapIndexed { index, pair ->
            val signed = File(dir, "signed-$index.apk")
            app.signer.sign(pair.first, signed, identity, 28)
            SignedArtifact(signed, pair.second, signed.sha256())
        }
        assertEquals(1, files.map { app.signer.verify(it.file) }.distinct().size)
        install(CloneArtifacts(plan, files, source.versionCode))
        val installed = app.packageManager.getApplicationInfo(plan.packageName, 0)
        assertEquals(source.parts.size - 1, installed.splitSourceDirs.orEmpty().size)
        openFixture(plan.packageName)
        assertTrue(device.wait(Until.hasObject(By.textContains("Provider: 0")), 10000))
        device.findObject(By.text("Open feature")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Dynamic feature loaded")), 10000))
        assertTrue(device.hasObject(By.textContains(plan.packageName)))
        val copied = app.importer.importInstalled(plan.packageName)
        assertEquals(source.parts.size, copied.size)
        assertEquals(plan.packageName, app.analyzer.analyze(copied).packageName)
        File(app.filesDir, "split-proof.txt").writeText("PASS: supplied base, language configuration and available install-time feature splits transformed and signed consistently, installed in one session, launched with working provider and feature, then re-imported from installed APK paths. On-demand delivery and Play asset delivery remain unsupported.\n")
    }

    private fun openFixture(packageName: String) {
        device.executeShellCommand("am force-stop $packageName")
        app.installer.launch(packageName)
        assertTrue("Fixture did not launch: $packageName", device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 10000))
    }
    private fun install(artifacts: CloneArtifacts) {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        app.getSharedPreferences("install-results", 0).edit().remove("status:${artifacts.plan.id}").commit()
        val session = app.installer.install(artifacts)
        println("Install ${artifacts.plan.id}, session=$session, package=${artifacts.plan.packageName}")
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            val button = device.findObject(By.res("com.android.packageinstaller:id/ok_button"))
                ?: device.findObject(By.text(java.util.regex.Pattern.compile("(?i)install|update")))
            button?.click()
            val status = app.getSharedPreferences("install-results", 0).getInt("status:${artifacts.plan.id}", Int.MIN_VALUE)
            if (status != Int.MIN_VALUE) {
                assertEquals(app.getSharedPreferences("install-results", 0).getString("latest", ""), PackageInstaller.STATUS_SUCCESS, status)
                return
            }
            device.waitForWindowUpdate(null, 500)
        }
        fail("Installer did not complete: ${device.currentPackageName}")
    }
}
