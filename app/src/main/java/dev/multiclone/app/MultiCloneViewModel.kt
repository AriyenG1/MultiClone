package dev.multiclone.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import dev.multiclone.core.*
import dev.multiclone.storage.*
import dev.multiclone.platform.InstalledApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CloneOperation(val label: String, val progress: Float? = null, val sessionId: Int? = null)

class MultiCloneViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as MultiCloneApplication
    private val preferences = AppPreferences(app)
    val sources = app.dao.observeSources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val clones = app.dao.observeClones().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs = app.dao.observeJobs().map { rows -> rows.filter { it.state in setOf("RUNNING", "QUEUED", "PAUSED") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selectedId = preferences.selectedSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    var generatedCloneIds by mutableStateOf<Set<String>>(emptySet()); private set
    var installPromptOpen by mutableStateOf(false); private set
    private var autoOpenInstallPrompt = true
    fun openInstallPrompt() { installPromptOpen = true }
    fun dismissInstallPrompt() { installPromptOpen = false; autoOpenInstallPrompt = false }
    private val updater = AppUpdater(app)
    private var updateJob: Job? = null
    var checkingUpdate by mutableStateOf(false); private set
    var updateProgress by mutableStateOf<Int?>(null); private set
    var updateStatus by mutableStateOf(""); private set
    var updateDialog by mutableStateOf(false); private set
    var updateReady by mutableStateOf(false); private set
    var availableVersion by mutableStateOf(""); private set
    private var updateFile: File? = null
    fun dismissUpdateDialog() { updateDialog = false }
    fun showUpdateDialog() {
        if (updateFile?.isFile == true) updateDialog = true
        else { updateReady = false; updateStatus = "The cached update is no longer available. Check for updates again." }
    }
    fun cancelUpdateDownload() { updateJob?.cancel() }
    fun checkForAppUpdate() {
        if (checkingUpdate) return
        updateJob = viewModelScope.launch {
            checkingUpdate = true; updateReady = false; availableVersion = ""; updateProgress = null; updateFile = null; updateDialog = false
            updateStatus = "Checking GitHub for updates…"
            try {
                val release = withContext(Dispatchers.IO) { updater.latest() }
                if (!AppUpdater.newer(release.version, BuildConfig.VERSION_NAME)) {
                    updateStatus = "You're up to date (v${BuildConfig.VERSION_NAME})."
                } else {
                    availableVersion = release.version
                    updateStatus = "Downloading v${release.version}…"
                    updateFile = withContext(Dispatchers.IO) { updater.download(release) { percent ->
                        withContext(Dispatchers.Main) { updateProgress = percent }
                    } }
                    updateStatus = "v${release.version} verified and ready to install."
                    updateReady = true
                    updateDialog = true
                }
            } catch (e: CancellationException) {
                updateStatus = "Download cancelled. Your installed app is unchanged."
                throw e
            } catch (e: Exception) {
                updateStatus = "Update check/download failed: ${e.message ?: "Check your connection and retry."}"
            } finally { checkingUpdate = false; updateProgress = null }
        }
    }
    fun installAppUpdate(openInstaller: (Intent) -> Unit) {
        if (busy || checkingUpdate) return
        action {
            require(app.dao.observeJobs().first().none { it.state in setOf("RUNNING", "QUEUED") }) {
                "Pause or finish clone generation before updating MultiClone."
            }
            val file = updateFile ?: error("Check for updates again to download the APK.")
            val intent = updater.installIntent(file)
            withContext(Dispatchers.Main) { openInstaller(intent) }
        }
    }
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var messageIsError by mutableStateOf(false); private set
    var installedApps by mutableStateOf<List<InstalledApp>?>(null); private set
    var icon by mutableStateOf<File?>(null); private set
    var cloneOperations by mutableStateOf<Map<String, CloneOperation>>(emptyMap()); private set
    var installRequest by mutableStateOf<CloneRow?>(null); private set
    fun takeInstallRequest(): CloneRow? = installRequest.also { installRequest = null }
    private val updateGenerations = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun operation(id: String, value: CloneOperation?) {
        cloneOperations = if (value == null) cloneOperations - id else cloneOperations + (id to value)
    }
    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) { }
        override fun onBadgingChanged(sessionId: Int) { }
        override fun onActiveChanged(sessionId: Int, active: Boolean) { }
        override fun onProgressChanged(sessionId: Int, progress: Float) { }
        override fun onFinished(sessionId: Int, success: Boolean) {
            cloneOperations.filterValues { it.sessionId == sessionId }.keys.forEach { operation(it, null) }
            refreshInstalled()
        }
    }
    var installedPackages by mutableStateOf<Set<String>>(emptySet()); private set
    var installedVersions by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    var exportTarget: CloneRow? = null
    var pendingPassword: CharArray? = null

    private val installResults = app.getSharedPreferences(InstallFeedback.PREFERENCES, 0)
    private val installListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "revision") {
            prefs.getString("cloneId", null)?.let { operation(it, null) }
            message = prefs.getString("latest", "").orEmpty()
            messageIsError = prefs.getBoolean("failed", false)
            refreshInstalled()
        }
    }
    init {
        installResults.registerOnSharedPreferenceChangeListener(installListener)
        app.packageManager.packageInstaller.registerSessionCallback(sessionCallback, mainHandler)
        viewModelScope.launch {
            app.queue.generated.collect { id ->
                if (updateGenerations.remove(id)) return@collect
                generatedCloneIds = generatedCloneIds + id
                if (autoOpenInstallPrompt) installPromptOpen = true
            }
        }
        refreshInstalled()
    }
    fun source(id: String): ApkSource? = sources.value.find { it.id == id }?.let { JsonCodec.source(it.json) }
    fun select(id: String) { viewModelScope.launch { preferences.selectSource(id) } }
    fun dismissAppPicker() { installedApps = null }
    fun loadApps() = action { installedApps = app.importer.installedApps() }
    fun importInstalled(packageName: String, label: String) {
        installedApps = null
        action { saveSource(app.analyzer.analyze(app.importer.importInstalled(packageName), label)) }
    }
    fun importFiles(uris: List<Uri>) = action { saveSource(app.analyzer.analyze(app.importer.importFiles(uris))) }
    private suspend fun saveSource(source: ApkSource) {
        app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
        preferences.selectSource(source.id)
        message = if (source.buildable) "" else "This source is blocked. See the findings below."
    }
    fun importIcon(uri: Uri) = action { icon = app.importer.importIcon(uri); message = "Custom icon selected." }
    fun clearIcon() { icon = null }
    fun enqueue(source: ApkSource, label: String, count: Long) = action {
        app.queue.enqueue(source.id, label, count, icon)
        withContext(Dispatchers.Main) {
            generatedCloneIds = emptySet()
            autoOpenInstallPrompt = true
            installPromptOpen = false
        }
        withContext(Dispatchers.Main) { startQueue() }
        message = "Added to the local generation queue. Android will ask separately before installation."
    }
    private fun startQueue() { ContextCompat.startForegroundService(app, Intent(app, CloneService::class.java)) }
    fun resume() = action { app.dao.resumeJobs(); withContext(Dispatchers.Main) { startQueue() }; message = "Generation resumed." }
    fun pause() = action { app.dao.pauseJobs(); app.stopService(Intent(app, CloneService::class.java)); message = "Queue paused. Completed artifacts are retained." }
    fun cancelJob(id: String) = action { app.dao.cancelJob(id); message = "Batch cancelled. A clone already being processed may finish." }
    fun install(row: CloneRow) {
        if (busy || row.id in cloneOperations) return
        operation(row.id, CloneOperation("Verifying APKs…"))
        action {
            try {
                app.queue.withCloneMutation {
                    val current = app.dao.clone(row.id) ?: error("This clone was deleted.")
                    val artifacts = CloneArtifacts(JsonCodec.plan(current.plan), JsonCodec.artifacts(current.artifacts), current.versionCode)
                    var lastPercent = -1
                    val session = app.installer.install(artifacts) { copied, total ->
                        val percent = if (total > 0) (copied * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mainHandler.post {
                                if (cloneOperations[row.id]?.sessionId == null && row.id in cloneOperations)
                                    operation(row.id, CloneOperation("Preparing installation · $percent%", percent / 100f))
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (app.packageManager.packageInstaller.getSessionInfo(session) != null)
                            operation(row.id, CloneOperation("Waiting for Android · Confirm installation to continue", sessionId = session))
                        else operation(row.id, null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { operation(row.id, null) }
                throw e
            }
        }
    }
    fun launch(row: CloneRow) = action { withContext(Dispatchers.Main) { app.installer.launch(row.packageName) } }
    fun updateClone(row: CloneRow) {
        if (busy || cloneOperations.isNotEmpty() || installRequest != null) return
        messageIsError = false
        message = ""
        operation(row.id, CloneOperation("Checking original app for updates…"))
        viewModelScope.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    app.ready.await()
                    val current = app.dao.clone(row.id) ?: error("This clone was deleted.")
                    val installed = app.packageManager.getPackageInfo(current.packageName, 0).longVersionCode
                    require(app.dao.observeJobs().first().none { it.updateCloneId == row.id && it.state in setOf("QUEUED", "RUNNING", "PAUSED") }) {
                        "An update for this clone is already queued. Resume or cancel it in the queue."
                    }
                    val previous = JsonCodec.source(app.dao.source(current.sourceId)?.json ?: error("Original source metadata is missing."))
                    val originalVersion = try { app.packageManager.getPackageInfo(previous.packageName, 0).longVersionCode }
                    catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        error("Install the original app (${previous.packageName}) first, then tap Update again.")
                    }
                    if (originalVersion <= installed && current.versionCode <= installed) return@withContext null
                    // Reuse an already generated update after a cancelled confirmation or process restart.
                    if (current.versionCode > installed && current.versionCode >= originalVersion &&
                        JsonCodec.artifacts(current.artifacts).let { files -> files.isNotEmpty() && files.all { it.file.isFile && it.file.sha256() == it.sha256 } }) return@withContext current
                    val source = app.analyzer.analyze(app.importer.importInstalled(previous.packageName), previous.label)
                    require(source.versionCode >= installed) { "The original app is older than this clone. Update the original through its store first." }
                    app.dao.saveSource(SourceRow(source.id, source.label, source.packageName, JsonCodec.source(source)))
                    withContext(Dispatchers.Main) {
                        updateGenerations.add(row.id)
                        operation(row.id, CloneOperation("Building update · Your existing clone stays available"))
                    }
                    val jobId = app.queue.enqueue(source.id, current.label, 1, null, current.id)
                    withContext(Dispatchers.Main) { startQueue() }
                    val result = app.dao.observeJobs().first { rows -> rows.any { it.id == jobId && it.state in setOf("DONE", "PAUSED", "CANCELLED") } }.first { it.id == jobId }
                    require(result.state == "DONE") { "Clone update ${result.state.lowercase()}: ${result.message}" }
                    app.dao.clone(row.id) ?: error("This clone was deleted.")
                }
                if (ready == null) message = "${row.label} is up to date with the installed original. Check its store for newer versions."
                else installRequest = ready
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                updateGenerations.remove(row.id)
                message = e.message ?: "Clone update failed. Retry from the library."
                messageIsError = true
            } finally { operation(row.id, null) }
        }
    }
    fun refreshInstalled() {
        viewModelScope.launch(Dispatchers.IO) {
            app.ready.await()
            val installed = try { app.library.reconcile() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                withContext(Dispatchers.Main) { message = e.message ?: "The clone library could not be refreshed."; messageIsError = true }
                return@launch
            }
            val sessions = app.packageManager.packageInstaller.mySessions
            val rows = app.dao.clones()
            withContext(Dispatchers.Main) {
                installedPackages = installed.keys; installedVersions = installed
                cloneOperations.filterValues { it.sessionId != null && sessions.none { session -> session.sessionId == it.sessionId } }
                    .keys.forEach { operation(it, null) }
                sessions.forEach { session -> rows.find { it.packageName == session.appPackageName }?.let { row ->
                    if (row.id !in cloneOperations) operation(row.id, CloneOperation("Waiting for Android · Complete or cancel installation", sessionId = session.sessionId))
                } }
            }
        }
    }
    fun deleteClone(row: CloneRow, requestUninstall: (CloneRow) -> Unit) = action {
        if (app.library.deleteIfUninstalled(row.id)) {
            message = "${row.label} deleted from your clone library."
            refreshInstalled()
        } else withContext(Dispatchers.Main) {
            message = "Confirm removal in Android. The library entry is deleted after uninstall succeeds."
            requestUninstall(row)
        }
    }
    fun exportArtifacts(row: CloneRow, uri: Uri) = action {
        val files = JsonCodec.artifacts(row.artifacts)
        require(files.isNotEmpty() && files.all { it.file.isFile && it.file.sha256() == it.sha256 }) { "Artifacts are missing or changed. Generate an update first." }
        app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            if (files.size == 1) files.single().file.inputStream().use { it.copyTo(output) }
            else ZipOutputStream(output).use { zip -> files.forEachIndexed { index, file ->
                zip.putNextEntry(ZipEntry(if (file.splitName == null) "base.apk" else "split-$index.apk"))
                file.file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            } }
        } ?: error("Export destination could not be opened.")
        message = "Export complete. Signing-key backup is a separate action."
    }
    fun exportBackup(uri: Uri, password: CharArray) = action {
        try {
            require(jobs.value.none { it.state in setOf("RUNNING", "QUEUED") }) { "Pause generation before backing up signing keys." }
            val bytes = app.backups.export(password)
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Backup destination cannot be opened.")
            message = "Encrypted backup saved. Keep its passphrase separately. Clone login data is not included."
        } finally { password.fill('\u0000') }
    }
    fun restoreBackup(uri: Uri, password: CharArray) = action {
        try {
            require(jobs.value.none { it.state in setOf("RUNNING", "QUEUED") }) { "Pause generation before restoring keys." }
            val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 16 * 1024 * 1024 + 52) { "Backup is too large." }; output.write(buffer, 0, n) }
                output.toByteArray()
            } ?: error("Backup cannot be opened.")
            val count = app.backups.restore(bytes, password)
            message = "Restored $count signing identities. Import source APKs to generate updates."
            refreshInstalled()
        } finally { password.fill('\u0000') }
    }
    fun tell(message: String) { this.message = message; messageIsError = false }
    override fun onCleared() {
        installResults.unregisterOnSharedPreferenceChangeListener(installListener)
        app.packageManager.packageInstaller.unregisterSessionCallback(sessionCallback)
        pendingPassword?.fill('\u0000'); super.onCleared()
    }
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            messageIsError = false
            try { withContext(Dispatchers.IO) { app.ready.await(); block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "The action could not be completed."; messageIsError = true }
            finally { busy = false }
        }
    }
}
