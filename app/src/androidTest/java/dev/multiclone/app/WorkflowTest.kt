package dev.multiclone.app

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.multiclone.core.*
import dev.multiclone.storage.SourceRow
import dev.multiclone.signing.BackupCipher
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkflowTest {
    @Test fun individualBatchNamesSurviveRecoveryAndBatchIconsAreIgnored() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
        app.ready.await()
        val input = File(app.filesDir, "named-batch-fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { source -> input.outputStream().use { source.copyTo(it) } }
        val source = app.analyzer.analyze(listOf(input), "Named batch fixture")
        app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
        val names = listOf("Personal", "Work", "Travel", "Study", "Ariyen’s copy")
        val id = app.queue.enqueue(source.id, "Named copies", 5, File(app.filesDir, "missing-batch-icon.png"), cloneNames = names)
        assertNull("Batches must use the original icon", app.dao.job(id)!!.iconPath)
        app.dao.saveJob(app.dao.job(id)!!.copy(state = "RUNNING"))
        app.dao.recoverJobs()
        assertEquals("PAUSED", app.dao.job(id)!!.state)
        assertEquals(names.size, org.json.JSONArray(app.dao.job(id)!!.cloneNames).length())
        app.dao.resumeJobs()
        app.queue.run { }
        assertEquals("DONE", app.dao.job(id)!!.state)
        val copies = app.dao.clones().filter { it.sourceId == source.id }
        assertEquals(names.toSet(), copies.map { it.label }.toSet())
        assertTrue(copies.all { JsonCodec.plan(it.plan).icon == null })
        try {
            app.queue.enqueue(source.id, "Too many custom names", 6, null, cloneNames = names + "Sixth")
            fail("More than five individually named copies must be refused")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun batchQueueRecoveryAndSigningBackup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MultiCloneApplication
        app.ready.await()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val input = File(app.filesDir, "workflow-fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { source -> input.outputStream().use { source.copyTo(it) } }
        val source = app.analyzer.analyze(listOf(input), "Workflow fixture")
        app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
        val batch = app.queue.enqueue(source.id, "Batch proof", 7, null)
        ContextCompat.startForegroundService(app, Intent(app, CloneService::class.java))
        withTimeout(120000) { while (app.dao.job(batch)?.state !in setOf("DONE", "PAUSED")) delay(200) }
        assertEquals(app.dao.job(batch)?.message, "DONE", app.dao.job(batch)?.state)
        assertEquals(7L, app.dao.job(batch)?.completed)
        val clones = app.dao.clones().filter { it.sourceId == source.id }
        assertEquals(7, clones.size)
        assertEquals(7, clones.map { it.packageName }.distinct().size)
        val signerSets = clones.map { app.signer.verify(JsonCodec.artifacts(it.artifacts).single().file) }
        assertEquals(7, signerSets.distinct().size)
        // A later independent batch uses the same imported source, without a predefined clone list.
        val later = app.queue.enqueue(source.id, "Later proof", 1, null)
        app.queue.run { }
        assertEquals("DONE", app.dao.job(later)?.state)
        val huge = app.queue.enqueue(source.id, "Lazy queue proof", Long.MAX_VALUE, null)
        assertEquals(Long.MAX_VALUE, app.dao.job(huge)?.total)
        app.dao.cancelJob(huge)
        assertEquals("CANCELLED", app.dao.job(huge)?.state)
        val paused = app.queue.enqueue(source.id, "Recovery proof", 1, null)
        app.dao.saveJob(app.dao.job(paused)!!.copy(state = "RUNNING"))
        app.dao.recoverJobs()
        assertEquals("PAUSED", app.dao.job(paused)?.state)
        app.dao.resumeJobs(); app.queue.run { }
        assertEquals("DONE", app.dao.job(paused)?.state)

        val first = clones.first()
        val identity = app.vault.getOrCreate(first.id)
        val password = "local recovery test passphrase".toCharArray()
        val backup = app.backups.export(password)
        assertTrue(backup.size > 100)
        val key = File(app.noBackupFilesDir, "clone-keys/${first.id}.key")
        assertTrue(key.delete())
        assertTrue(app.backups.restore(backup, password) >= 8)
        val restored = app.vault.getOrCreate(first.id)
        assertArrayEquals(identity.certificate.encoded, restored.certificate.encoded)
        val signedAgain = File(app.filesDir, "restored-key-proof.apk")
        app.signer.sign(JsonCodec.artifacts(first.artifacts).single().file, signedAgain, restored, 28)
        assertEquals(signerSets.first(), app.signer.verify(signedAgain))
        val update = app.queue.enqueue(source.id, "Updated proof", 1, null, first.id)
        app.queue.run { }
        assertEquals("DONE", app.dao.job(update)?.state)
        val updated = app.dao.clone(first.id)!!
        assertEquals(first.packageName, updated.packageName)
        assertEquals(app.signer.verify(JsonCodec.artifacts(first.artifacts).single().file), app.signer.verify(JsonCodec.artifacts(updated.artifacts).single().file))
        File(app.filesDir, "workflow-proof.txt").writeText("PASS: foreground batch of 7, later batch, lazy Long.MAX_VALUE request cancellation, interrupted-job recovery, encrypted key restoration and stable-identity update.\n")
    }
}
