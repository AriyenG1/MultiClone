package dev.multiclone.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdaterTest {
    private fun release(): JSONObject = JSONObject().put("tag_name", "v0.1.5").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "MultiClone-v0.1.5.apk").put("size", 4000)
            .put("digest", "sha256:" + "a".repeat(64)).put("browser_download_url", "https://github.com/AriyenG1/MultiClone/releases/download/v0.1.5/MultiClone-v0.1.5.apk")))
    private fun rejected(json: JSONObject) {
        try { AppUpdater.parse(json.toString()); fail("Unsafe release must be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun versionsUseNumericOrderingAndStableAssetsAreBounded() {
        assertTrue(AppUpdater.newer("0.1.10", "0.1.9"))
        assertFalse(AppUpdater.newer("0.1.4", "0.1.4.0"))
        assertFalse(AppUpdater.newer("0.1.3", "0.1.4"))
        assertTrue(AppUpdater.newer("1.0", "0.99.99"))
        assertEquals("0.1.5", AppUpdater.parse(release().toString()).version)
        rejected(release().put("draft", true))
        rejected(release().put("prerelease", true))
        rejected(release().put("tag_name", "v0.1.5-beta"))
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/update.apk") })
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("digest", "sha256:invalid") })
        rejected(release().apply { getJSONArray("assets").getJSONObject(0).put("size", 300L * 1024 * 1024) })
        rejected(release().apply { getJSONArray("assets").put(getJSONArray("assets").getJSONObject(0)) })
    }
    @Test fun installerRejectsOtherPackagesAndSameVersion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
        val updater = AppUpdater(app)
        val fixture = File(app.cacheDir, "update-wrong-package.apk")
        try {
            instrumentation.context.assets.open("fixture.apk").use { input -> fixture.outputStream().use { input.copyTo(it) } }
            try { updater.validate(fixture); fail("Other package must be rejected") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("different app")) }
            try { updater.validate(File(app.applicationInfo.sourceDir)); fail("Same version must be rejected") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("not newer")) }
        } finally { fixture.delete() }
    }
    @Test fun publicGithubMetadataCanBeCheckedOnDemand() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultiCloneApplication
        val release = AppUpdater(app).latest()
        assertTrue(release.size > 0)
        assertTrue(release.url.startsWith("https://github.com/AriyenG1/MultiClone/releases/download/"))
    }
    @Test fun verifiedHigherVersionOpensAndroidUpdateConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
        val file = File(app.cacheDir, "updates/proof.apk")
        org.junit.Assume.assumeTrue("Optional host-prepared higher-version APK", file.isFile)
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am force-stop com.android.packageinstaller")
        device.executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
        app.startActivity(android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        device.waitForIdle()
        val updater = AppUpdater(app)
        updater.validate(file)
        val intent = updater.installIntent(file)
        assertTrue(intent.data.toString().startsWith("content://${app.packageName}.updates/"))
        app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val confirm = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)install|update"))), 15000)
        assertNotNull("Verified update must open Android confirmation", confirm)
        device.takeScreenshot(File(app.filesDir, "self-update-confirmation.png"))
        device.pressBack()
        val broken = File(app.cacheDir, "updates/tampered.apk")
        try {
            file.copyTo(broken, overwrite = true)
            java.io.RandomAccessFile(broken, "rw").use { output ->
                output.seek(output.length() / 2)
                val old = output.readByte()
                output.seek(output.filePointer - 1)
                output.writeByte(old.toInt() xor 1)
            }
            try { updater.validate(broken); fail("Tampered APK must be rejected") }
            catch (_: Exception) { }
        } finally { broken.delete(); file.delete() }
    }

}
