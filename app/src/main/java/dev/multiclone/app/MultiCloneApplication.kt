package dev.multiclone.app

import android.app.Application
import dev.multiclone.engine.ApkAnalyzer
import dev.multiclone.engine.ApkTransformer
import dev.multiclone.platform.InstallCoordinator
import dev.multiclone.platform.SourceImporter
import dev.multiclone.signing.EmbeddedApkSigner
import dev.multiclone.signing.KeyVault
import dev.multiclone.storage.CloneDatabase
import kotlinx.coroutines.*

class MultiCloneApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { CloneDatabase.open(this) }
    val dao by lazy { database.clones() }
    val signer by lazy { EmbeddedApkSigner() }
    val vault by lazy { KeyVault(this) }
    val analyzer by lazy { ApkAnalyzer(signer) }
    val transformer by lazy { ApkTransformer() }
    val importer by lazy { SourceImporter(this) }
    val installer by lazy { InstallCoordinator(this, InstallResultReceiver::class.java) }
    val queue by lazy { CloneQueue(this) }
    val library by lazy { CloneLibrary(this) }
    val backups by lazy { BackupManager(this) }
    val ready by lazy { scope.async {
        dao.recoverJobs(); dao.recoverClones()
        java.io.File(filesDir, "artifacts").listFiles()?.filter { it.isDirectory }?.forEach { cloneDirectory ->
            cloneDirectory.listFiles()?.filter { it.isDirectory && it.name.startsWith(".pending-") }?.forEach { it.deleteRecursively() }
        }
    } }
    override fun onCreate() {
        super.onCreate()
        ready
    }
}
