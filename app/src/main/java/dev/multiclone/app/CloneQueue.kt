package dev.multiclone.app

import dev.multiclone.core.*
import dev.multiclone.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class CloneQueue(private val app: MultiCloneApplication) {
    private val _generated = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    val generated = _generated.asSharedFlow()
    private val runMutex = Mutex()
    private val cloneMutation = Mutex()
    suspend fun <T> withCloneMutation(block: suspend () -> T): T = cloneMutation.withLock { block() }
    suspend fun enqueue(sourceId: String, name: String, count: Long, icon: File?, updateCloneId: String? = null): String {
        require(count > 0) { "Enter a positive clone count." }
        require(name.isNotBlank() && name.length <= 80) { "Use a name between 1 and 80 characters." }
        val source = JsonCodec.source(app.dao.source(sourceId)?.json ?: error("Import a source APK first."))
        require(source.buildable) { "This source has blocking compatibility findings." }
        require(source.parts.all { it.file.isFile }) { "Source APKs are missing. Import the source again." }
        if (updateCloneId != null) {
            require(count == 1L)
            val clone = app.dao.clone(updateCloneId) ?: error("Clone no longer exists.")
            val previous = JsonCodec.source(app.dao.source(clone.sourceId)?.json ?: error("Original source metadata is missing."))
            require(source.packageName == previous.packageName && source.base.signerDigests == previous.base.signerDigests) {
                "The update must have the same original package and signing certificates. Certificate rotation is not supported yet."
            }
            require(source.versionCode >= clone.versionCode) { "Downgrades are unsupported. Select the same or a newer source version." }
        }
        val id = UUID.randomUUID().toString()
        app.dao.saveJob(JobRow(id, sourceId, name.trim(), count, iconPath = icon?.absolutePath, updateCloneId = updateCloneId))
        return id
    }

    suspend fun run(progress: (String) -> Unit) = runMutex.withLock {
        while (currentCoroutineContext().isActive) {
            val job = app.dao.nextJob() ?: return@withLock
            app.dao.saveJob(job.copy(state = "RUNNING"))
            val source = JsonCodec.source(app.dao.source(job.sourceId)?.json ?: error("Source metadata is missing."))
            var completed = job.completed
            try {
                while (completed < job.total && currentCoroutineContext().isActive) {
                    if (app.dao.job(job.id)?.state != "RUNNING") break
                    withCloneMutation {
                        if (app.dao.job(job.id)?.state != "RUNNING") return@withCloneMutation
                        val update = job.updateCloneId?.let { app.dao.clone(it) ?: error("Clone was removed.") }
                        require(update == null || source.versionCode >= update.versionCode) { "A newer clone artifact already exists. Downgrade refused." }
                        val oldPlan = update?.let { JsonCodec.plan(it.plan) }
                        val label = if (job.total == 1L) job.label else "${job.label} ${completed + 1}"
                        val plan = (oldPlan?.copy(label = label, icon = job.iconPath?.let(::File) ?: oldPlan.icon)
                            ?: ClonePlan.create(label, job.iconPath?.let(::File))).let {
                            it.copy(identityMap = app.transformer.identityMap(source, it.packageName) + it.identityMap)
                        }
                        require(plan.icon == null || plan.icon!!.isFile) { "Custom icon is missing. Select it again." }
                        if (update == null) {
                            try { app.packageManager.getPackageInfo(plan.packageName, 0); error("Generated package already exists.") }
                            catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
                        } else {
                            val installed = try { app.packageManager.getPackageInfo(plan.packageName, 0) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
                            require(installed == null || source.versionCode >= installed.longVersionCode) { "The installed clone has a newer version. Downgrade refused." }
                        }
                        val row = update?.copy(label = label, plan = JsonCodec.plan(plan))
                            ?: CloneRow(plan.id, source.id, label, plan.packageName, JsonCodec.plan(plan))
                        // Keep the old usable clone record until an update artifact is verified.
                        if (update == null) app.dao.saveClone(row)
                        val root = File(app.filesDir, "artifacts/${plan.id}").apply { mkdirs() }
                        val staging = File(root, ".pending-${UUID.randomUUID()}").apply { mkdirs() }
                        try {
                            val generated = runInterruptible(Dispatchers.IO) {
                                val needed = source.parts.sumOf { it.file.length() } * 4 + 64L * 1024 * 1024
                                require(staging.usableSpace > needed) { "Not enough storage for this clone. Free space and resume." }
                                val unsigned = app.transformer.build(source, plan, staging, progress)
                                val identity = app.vault.getOrCreate(plan.id)
                                unsigned.mapIndexed { index, (file, split) ->
                                    progress("Signing ${completed + 1}/${job.total}: ${split ?: "base"}")
                                    val signed = File(staging, if (split == null) "base.apk" else "split-$index.apk")
                                    app.signer.sign(file, signed, identity, 28)
                                    file.delete()
                                    SignedArtifact(signed, split, signed.sha256())
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            val ready = File(root, "v${source.versionCode}-${UUID.randomUUID()}")
                            require(staging.renameTo(ready)) { "Could not publish completed artifacts." }
                            val files = generated.map { it.copy(file = File(ready, it.file.name)) }
                            app.dao.saveClone(row.copy(sourceId = source.id, artifacts = JsonCodec.artifacts(files), versionCode = source.versionCode,
                                state = "READY", message = "Generated and signature verified. Install and test this app's behavior."))
                            completed++
                            app.dao.job(job.id)?.let { current ->
                                app.dao.saveJob(current.copy(completed = completed, state = if (completed == job.total) "DONE" else current.state, message = ""))
                            }
                            // UI notification must never suspend generation or change queue completion.
                            _generated.tryEmit(plan.id)
                            progress("Generated $completed/${job.total}")
                        } catch (e: Exception) {
                            if (update == null) withContext(NonCancellable) { app.dao.saveClone(row.copy(state = "FAILED", message = e.message ?: "Build interrupted.")) }
                            throw e
                        } finally { staging.deleteRecursively() }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { app.dao.job(job.id)?.let { app.dao.saveJob(it.copy(state = "PAUSED", completed = completed, message = "Interrupted. Resume when ready.")) } }
                throw e
            } catch (e: Exception) {
                app.dao.job(job.id)?.let { app.dao.saveJob(it.copy(state = "PAUSED", completed = completed, message = e.message ?: "Processing failed.")) }
                progress("Paused: ${e.message}")
            }
        }
    }
}
