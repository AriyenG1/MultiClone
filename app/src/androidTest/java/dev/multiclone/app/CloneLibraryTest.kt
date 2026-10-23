package dev.multiclone.app

import android.content.Intent
import android.content.pm.PackageInstaller
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.multiclone.core.*
import dev.multiclone.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class CloneLibraryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun deleteRemovesRecoveredRecordKeysAllArtifactVersionsAndPendingUpdates() = runBlocking {
        app.ready.await()
        val plan = ClonePlan.create("Delete fixture")
        val keep = ClonePlan.create("Keep fixture")
        val directory = File(app.filesDir, "artifacts/${plan.id}")
        listOf("old/base.apk", "new/base.apk", ".pending-interrupted/unsigned.apk").forEach {
            File(directory, it).apply { parentFile!!.mkdirs(); writeText("local test artifact") }
        }
        app.vault.getOrCreate(plan.id); app.vault.getOrCreate(keep.id)
        val row = CloneRow(plan.id, "deletion-source", plan.label, plan.packageName, JsonCodec.plan(plan), state = "RECOVERED")
        app.dao.saveClone(row)
        app.dao.saveClone(CloneRow(keep.id, row.sourceId, keep.label, keep.packageName, JsonCodec.plan(keep), state = "RECOVERED"))
        val update = JobRow(UUID.randomUUID().toString(), row.sourceId, "Pending update", 1, state = "PAUSED", updateCloneId = row.id)
        val done = update.copy(id = UUID.randomUUID().toString(), label = "Old DONE batch", state = "DONE", updateCloneId = null)
        val active = update.copy(id = UUID.randomUUID().toString(), label = "Keep paused batch", updateCloneId = null)
        app.dao.saveJob(update); app.dao.saveJob(done); app.dao.saveJob(active)
        val store = ViewModelStore()
        lateinit var model: MultiCloneViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MultiCloneViewModel::class.java] }
        try {
            val visibleJobs = withTimeout(15000) { model.jobs.first { rows -> rows.any { it.id == active.id } } }
            assertFalse("Completed history must not appear in the library", visibleJobs.any { it.id == done.id })
            assertTrue(app.library.deleteIfUninstalled(plan.id))
            assertNull(app.dao.clone(plan.id))
            assertFalse(directory.exists())
            assertFalse(File(app.noBackupFilesDir, "clone-keys/${plan.id}.key").exists())
            assertEquals("CANCELLED", app.dao.job(update.id)?.state)
            app.library.reconcile()
            assertNotNull("A never-installed restored identity must remain until explicitly deleted", app.dao.clone(keep.id))
            assertTrue(File(app.noBackupFilesDir, "clone-keys/${keep.id}.key").exists())
            assertEquals("PAUSED", app.dao.job(active.id)?.state)
            assertTrue("Deletion is idempotent", app.library.deleteIfUninstalled(plan.id))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.library.deleteIfUninstalled(keep.id)
            app.dao.cancelJob(active.id)
        }
    }

    @Test fun cancelledUninstallKeepsCloneAndExternalUninstallRemovesLibraryFilesAndKey() = runBlocking {
        app.ready.await()
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        // Emulator-only setup; install and cancellation still use the supported Android UI.
        device.executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
        val input = File(app.filesDir, "library-fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { source -> input.outputStream().use { source.copyTo(it) } }
        val source = app.analyzer.analyze(listOf(input), "Library lifecycle fixture")
        app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
        app.queue.enqueue(source.id, "Library lifecycle clone", 1, null)
        app.queue.run { }
        val row = app.dao.clones().single { it.sourceId == source.id }
        try {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            app.installer.install(CloneArtifacts(JsonCodec.plan(row.plan), JsonCodec.artifacts(row.artifacts), row.versionCode))
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline && app.dao.clone(row.id)?.wasInstalled != true) {
                (device.findObject(By.res("com.android.packageinstaller:id/ok_button"))
                    ?: device.findObject(By.text(Pattern.compile("(?i)install|update"))))?.click()
                delay(150)
            }
            assertTrue("Installer must record that this clone was installed", app.dao.clone(row.id)?.wasInstalled == true)
            assertFalse("Installed clone requires Android confirmation", app.library.deleteIfUninstalled(row.id))
            app.startActivity(app.installer.uninstallIntent(row.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val cancel = device.wait(Until.findObject(By.res("android:id/button2")), 10000)
                ?: device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel"))), 5000)
            assertNotNull("Expected Android's uninstall cancellation", cancel)
            cancel!!.click()
            assertTrue(app.library.reconcile().containsKey(row.packageName))
            assertNotNull(app.dao.clone(row.id))
            assertTrue(File(app.noBackupFilesDir, "clone-keys/${row.id}.key").exists())

            // Represents uninstalling outside MultiClone while the library is closed.
            device.pressHome()
            assertTrue(device.executeShellCommand("pm uninstall ${row.packageName}").contains("Success"))
            app.library.reconcile()
            assertNull(app.dao.clone(row.id))
            assertFalse(File(app.filesDir, "artifacts/${row.id}").exists())
            assertFalse(File(app.noBackupFilesDir, "clone-keys/${row.id}.key").exists())
        } finally {
            device.executeShellCommand("pm uninstall ${row.packageName}")
            app.library.deleteIfUninstalled(row.id)
        }
    }

    @Test fun pendingInstallIsPreservedUntilSessionEnds() = runBlocking {
        app.ready.await()
        val plan = ClonePlan.create("Pending fixture")
        app.dao.saveClone(CloneRow(plan.id, "pending-source", plan.label, plan.packageName, JsonCodec.plan(plan), state = "READY", wasInstalled = true))
        app.vault.getOrCreate(plan.id)
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(plan.packageName) }
        val session = installer.createSession(params)
        try {
            app.library.reconcile()
            assertNotNull(app.dao.clone(plan.id))
            try { app.library.deleteIfUninstalled(plan.id); fail("Pending install must prevent deletion") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("pending Android installation")) }
            assertNotNull(app.dao.clone(plan.id))
        } finally { installer.abandonSession(session) }
        app.library.reconcile()
        assertNull(app.dao.clone(plan.id))
    }

    @Test fun versionOneDatabaseMigratesWithoutLosingRecords() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val schema = JSONObject(instrumentation.context.assets.open("database-v1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val path = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val id = "a".repeat(32)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
            }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(schema.getString("identityHash")))
            db.execSQL("INSERT INTO clones VALUES (?, 'source', 'Existing clone', 'dev.multiclone.migration', '{}', '[]', 1, 'INSTALLED', '', 1)", arrayOf(id))
            db.execSQL("INSERT INTO sources VALUES ('source','Source','dev.fixture','{}')")
            db.execSQL("INSERT INTO jobs VALUES ('job','source','Completed job',1,1,'DONE',NULL,NULL,'',1)")
            db.version = 1
        }
        val database = Room.databaseBuilder(app, CloneDatabase::class.java, name).addMigrations(CloneDatabase.MIGRATION_1_2, CloneDatabase.MIGRATION_2_3).build()
        try {
            assertTrue(database.clones().clone(id)!!.wasInstalled)
            assertEquals("Existing clone", database.clones().clone(id)?.label)
            assertNotNull(database.clones().source("source"))
            assertEquals("DONE", database.clones().job("job")?.state)
            assertEquals("[]", database.clones().job("job")?.cloneNames)
        } finally { database.close(); app.deleteDatabase(name) }
    }
}
