package dev.multiclone.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.multiclone.core.*
import dev.multiclone.storage.CloneRow

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MultiCloneViewModel::class.java] }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.importFiles(it) }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(model::importIcon) }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        model.exportTarget?.let { row -> if (uri != null) model.exportArtifacts(row, uri) }; model.exportTarget = null
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = model.pendingPassword; model.pendingPassword = null
        if (uri != null && password != null) model.exportBackup(uri, password) else password?.fill('\u0000')
    }
    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = model.pendingPassword; model.pendingPassword = null
        if (uri != null && password != null) model.restoreBackup(uri, password) else password?.fill('\u0000')
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val uninstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.tell(""); model.refreshInstalled() }
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            model.refreshInstalled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MultiCloneTheme { MultiCloneScreen(model,
                importFiles = { picker.launch(arrayOf("*/*")) }, importIcon = { iconPicker.launch("image/*") },
                install = { row -> if (!model.app.installer.canInstall()) {
                    startActivity(model.app.installer.permissionIntent()); model.tell("Allow installation from MultiClone, then tap Install again.")
                } else model.install(row) },
                uninstall = { row -> uninstall.launch(model.app.installer.uninstallIntent(row.packageName)) },
                export = { row -> model.exportTarget = row; exportPicker.launch(row.label.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + if (JsonCodec.artifacts(row.artifacts).size == 1) ".apk" else ".apks") },
                backup = { password, restore -> model.pendingPassword?.fill('\u0000'); model.pendingPassword = password
                    if (restore) restorePicker.launch(arrayOf("*/*")) else backupPicker.launch("MultiClone-signing-backup.mclone") },
                beforeQueue = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ) }
        }
    }
    override fun onResume() { super.onResume(); model.refreshInstalled() }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStop() { unregisterReceiver(packageChanges); super.onStop() }
}

@Composable
private fun MultiCloneScreen(model: MultiCloneViewModel, importFiles: () -> Unit, importIcon: () -> Unit,
    install: (CloneRow) -> Unit, uninstall: (CloneRow) -> Unit, export: (CloneRow) -> Unit,
    backup: (CharArray, Boolean) -> Unit, beforeQueue: () -> Unit) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val clones by model.clones.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val selectedId by model.selectedId.collectAsStateWithLifecycle()
    var tab by rememberSaveableInt()
    val screenStates = rememberSaveableStateHolder()
    var installDetailsOpen by rememberSaveable { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf<Boolean?>(null) }
    var deleteTarget by remember { mutableStateOf<CloneRow?>(null) }
    val selected = sources.find { it.id == selectedId } ?: sources.firstOrNull()
    val source = remember(selected?.json) { selected?.let { JsonCodec.source(it.json) } }
    BackHandler(enabled = tab != 0) { tab = 0 }
    val pendingIds = model.generatedCloneIds
    val readyClones = clones.filter { it.id in pendingIds && it.artifacts != "[]" && it.state != "BUILDING" && it.state != "FAILED" }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Window constraints, not device identity or orientation: also works in Samsung split screen / DeX.
            val useRail = maxWidth >= 600.dp
            val compactHeight = maxHeight < 480.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    if (useRail) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        if (!compactHeight) {
                            Spacer(Modifier.height(24.dp))
                            AppMark()
                            Spacer(Modifier.height(32.dp))
                        } else Spacer(Modifier.height(8.dp))
                        destinations.forEachIndexed { index, title ->
                            NavigationRailItem(selected = tab == index, onClick = { tab = index },
                                icon = { DestinationIcon(index) }, label = { Text(title) },
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("MultiClone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                                Text("On-device", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (model.message.isNotBlank()) StatusMessage(model.message, model.messageIsError, onDismiss = { model.tell("") })
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            screenStates.SaveableStateProvider(tab) {
                            when (tab) {
                                0 -> CreatePanel(model, source, sources.map { it.id to it.label }, importFiles, importIcon, beforeQueue,
                                    Modifier.widthIn(max = 840.dp).fillMaxSize(),
                                    readyCount = readyClones.size, showReady = { model.openInstallPrompt() })
                                1 -> DashboardPanel(clones, jobs, model, install, export, { deleteTarget = it },
                                    { row -> model.prepareUpdate(row); tab = 0 }, Modifier.widthIn(max = 1040.dp).fillMaxSize())
                                else -> Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Signing keys & device", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                                    Text("Back up your keys", style = MaterialTheme.typography.titleLarge)
                                    Text("Signing keys let existing clones receive updates. Back them up before uninstalling or resetting MultiClone. Backups include clone identities and source metadata, but no app sessions, source APKs or login data.")
                                    Button(onClick = { backupMode = false }, enabled = !model.busy) { Text("Export encrypted backup") }
                                    OutlinedButton(onClick = { backupMode = true }, enabled = !model.busy) { Text("Restore encrypted backup") }
                                    HorizontalDivider()
                                    Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}")
                                    Text("${model.app.filesDir.usableSpace / (1024 * 1024)} MB available in private storage")
                                    Text("Installation permission: ${if (model.app.installer.canInstall()) "allowed" else "not granted"}")
                                    Text("Samsung Auto Blocker or device policy may block sideloading. Complete any applicable system settings and confirmations yourself.")
                                    Text("Private app storage is separate. Browser cookies and external account brokers may still be shared. Apps requiring their original package or certificate may fail. Riot Mobile compatibility is untested.")
                                    Text("MultiClone ${BuildConfig.VERSION_NAME} · No network permission · No telemetry", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            }
                        }
                    }
                }
                if (!useRail) NavigationBar {
                    destinations.forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { DestinationIcon(index) }, label = { Text(title) })
                    }
                }
            }
        }
    }
    if (model.installPromptOpen && readyClones.isNotEmpty()) {
        AlertDialog(onDismissRequest = model::dismissInstallPrompt,
            title = { Text("Your clones are ready") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Install a copy now, or keep it in your library for later. Android will ask you to confirm each installation.")
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(readyClones, key = { it.id }) { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.label, style = MaterialTheme.typography.titleMedium)
                            val current = model.installedVersions[row.packageName]
                            Text(if (current == row.versionCode) "Installed · version $current" else "Ready · version ${row.versionCode}",
                                style = MaterialTheme.typography.bodyMedium)
                            if (current == row.versionCode) OutlinedButton(onClick = { model.launch(row) }, enabled = !model.busy) { Text("Open ${row.label}") }
                            else Button(onClick = { install(row) }, enabled = !model.busy) { Text("Install ${row.label}") }
                        }
                    }
                }
                if (model.message.isNotBlank()) {
                    Text(model.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (model.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { installDetailsOpen = true }) { Text("Installation details") }
                }
            } },
            confirmButton = { TextButton(onClick = model::dismissInstallPrompt) { Text("Done") } },
            dismissButton = { TextButton(onClick = model::dismissInstallPrompt) { Text("Later") } })
    }
    if (installDetailsOpen) AlertDialog(onDismissRequest = { installDetailsOpen = false },
        title = { Text("Installation details") },
        text = { Text(model.message, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { installDetailsOpen = false }) { Text("Close") } })
    model.installedApps?.let { apps ->
        var search by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = model::dismissAppPicker, title = { Text("Select an installed app") },
            text = { Column {
                OutlinedTextField(search, { search = it }, label = { Text("Search apps") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 440.dp)) { items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                    TextButton(onClick = { model.importInstalled(app.packageName, app.label) }) { Column(Modifier.fillMaxWidth()) {
                        Text(app.label); Text(app.packageName + if (app.splitCount > 0) " · ${app.splitCount} splits" else "", style = MaterialTheme.typography.bodySmall)
                    } }
                } }
            } }, confirmButton = { TextButton(onClick = model::dismissAppPicker) { Text("Close") } })
    }
    backupMode?.let { restore ->
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { backupMode = null }, title = { Text(if (restore) "Restore signing keys" else "Protect your signing backup") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (restore) "Choose an encrypted MultiClone backup after entering its passphrase." else "Use at least 12 characters. The passphrase cannot be recovered.")
                OutlinedTextField(password, { password = it }, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (!restore) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Repeat passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            } }, confirmButton = { TextButton(enabled = password.length >= 12 && (restore || password == confirmation), onClick = {
                backup(password.toCharArray(), restore); password = ""; confirmation = ""; backupMode = null
            }) { Text("Choose file") } }, dismissButton = { TextButton(onClick = { password = ""; confirmation = ""; backupMode = null }) { Text("Cancel") } })
    }
    deleteTarget?.let { row -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete ${row.label}?") },
        text = { Text(if (row.packageName in model.installedPackages)
            "Android will ask to uninstall this clone and remove its app data. After uninstall succeeds, its library entry, generated APKs and local signing key are deleted. Cancelling keeps the clone."
            else "Remove this library entry, generated APKs and local signing key. To make another copy later, generate a new clone.") },
        confirmButton = { TextButton(onClick = { model.deleteClone(row, uninstall); deleteTarget = null }) { Text("Delete clone") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatePanel(model: MultiCloneViewModel, source: ApkSource?, sources: List<Pair<String, String>>, importFiles: () -> Unit,
    importIcon: () -> Unit, beforeQueue: () -> Unit, modifier: Modifier, readyCount: Int, showReady: () -> Unit) {
    var name by rememberSaveable(source?.id, model.updateTarget?.id) { mutableStateOf(model.updateTarget?.label ?: source?.label?.substringAfterLast('.')?.take(60).orEmpty()) }
    var count by rememberSaveable { mutableStateOf("1") }
    var chooseSource by remember { mutableStateOf(false) }
    val quantity = if (model.updateTarget != null) 1L else count.toLongOrNull()
    val nameError = name.isBlank() || name.length > 80
    val countError = quantity == null || quantity <= 0
    Column(modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (model.updateTarget == null) "Make room for another you." else "Update your clone",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(if (model.updateTarget == null) "Create an independent copy of your app. Everything happens on your device."
                else "Choose the same or a newer original app to update ${model.updateTarget!!.label}.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (readyCount > 0) FilledTonalButton(onClick = showReady, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$readyCount ${if (readyCount == 1) "copy" else "copies"} ready · Review & install")
        }
        SectionCard("1", "Choose your app", "Start with an installed app or an APK file.") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = model::loadApps, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Installed apps") }
                OutlinedButton(onClick = importFiles, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import APKs") }
            }
            if (sources.isNotEmpty()) TextButton(onClick = { chooseSource = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use a previous import") }
            if (model.updateTarget != null) TextButton(onClick = model::cancelUpdate) { Text("Cancel update") }
            if (source != null) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Text(source.label.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.label, style = MaterialTheme.typography.titleLarge)
                        Text(source.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Version ${source.versionCode} · ${source.parts.size} APK file(s)", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else Text("For split apps, import the base APK and all required splits together.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (source != null) {
            CompatibilitySummary(source)
            SectionCard("2", "Make it yours", "Name your copy and choose how many to create.") {
                OutlinedTextField(name, { name = it }, label = { Text("Clone name / batch prefix") },
                    supportingText = { Text(if (nameError) "Enter a name using 1–80 characters." else "${name.length}/80 · Batches add a number to each name") },
                    isError = nameError, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (model.updateTarget == null) OutlinedTextField(count, { if (it.all(Char::isDigit)) count = it }, label = { Text("Number of clones") },
                    supportingText = { Text(if (countError) "Enter a whole number greater than zero (up to ${Long.MAX_VALUE})." else "Each copy has separate app storage.") },
                    isError = countError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = importIcon, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (model.icon == null) "Choose custom icon" else "Change custom icon")
                    }
                    if (model.icon != null) TextButton(onClick = model::clearIcon, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset icon") }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !model.busy && source.buildable && !nameError && !countError,
                    onClick = { beforeQueue(); model.enqueue(source, name, quantity!!) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (model.updateTarget == null) "Create ${quantity ?: ""} ${if (quantity == 1L) "clone" else "clones"}" else "Generate update")
                }
                Text("Each copy gets a unique package and signing key. When a copy is ready, an install window opens here. Large batches may pause at Android execution or storage limits.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text("Your original app stays as it is. Clones start fresh, with their own private app data.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val activeJobs by model.jobs.collectAsStateWithLifecycle()
        activeJobs.forEach { job ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${job.label} · ${job.completed}/${job.total}", style = MaterialTheme.typography.titleMedium)
                    Text(if (job.state == "PAUSED") "Paused · ${job.message}" else "${job.state.lowercase().replaceFirstChar(Char::uppercase)} · Generating on your device")
                    if (job.state == "RUNNING") LinearProgressIndicator(Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { if (job.state == "PAUSED") model.resume() else model.pause() }, enabled = !model.busy) { Text(if (job.state == "PAUSED") "Resume queue" else "Pause queue") }
                        TextButton(onClick = { model.cancelJob(job.id) }, enabled = !model.busy) { Text("Cancel batch") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (chooseSource) AlertDialog(onDismissRequest = { chooseSource = false }, title = { Text("Imported sources") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) { items(sources, key = { it.first }) { (id, label) ->
            TextButton(onClick = { model.select(id); chooseSource = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
        } }
    }, confirmButton = { TextButton(onClick = { chooseSource = false }) { Text("Close") } })
}

@Composable
private fun SectionCard(number: String, title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(number, style = MaterialTheme.typography.labelLarge) }
                }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
internal fun CompatibilitySummary(source: ApkSource) {
    var expanded by rememberSaveable(source.id) { mutableStateOf(false) }
    val blocked = source.findings.count { it.severity == Severity.BLOCKED }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (blocked > 0) "Cannot create this clone" else "Compatibility review", style = MaterialTheme.typography.titleMedium,
                color = if (blocked > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(if (blocked > 0) "$blocked blocking ${if (blocked == 1) "issue" else "issues"} · Generation unavailable"
                else "Generation available · App behavior is untested", style = MaterialTheme.typography.bodyMedium)
            Text("Some apps require their original package or signing key and may not work as a clone.", style = MaterialTheme.typography.bodySmall)
            if (source.findings.isNotEmpty()) TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
                Text(if (expanded) "Hide warnings" else "Show warnings (${source.findings.size})")
            }
            if (expanded) source.findings.forEach { finding ->
                HorizontalDivider()
                Text("${finding.severity}: ${finding.message}", style = MaterialTheme.typography.bodyMedium,
                    color = if (finding.severity == Severity.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardPanel(clones: List<CloneRow>, jobs: List<dev.multiclone.storage.JobRow>, model: MultiCloneViewModel,
    install: (CloneRow) -> Unit, export: (CloneRow) -> Unit, delete: (CloneRow) -> Unit,
    update: (CloneRow) -> Unit, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Your clone library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("${clones.size} ${if (clones.size == 1) "clone" else "clones"} · ${model.installedPackages.size} installed", style = MaterialTheme.typography.bodyMedium)
        }
        if (jobs.any { it.state in setOf("RUNNING", "QUEUED", "PAUSED") }) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::pause) { Text("Pause queue") }
                OutlinedButton(onClick = model::resume) { Text("Resume queue") }
            }
        }
        items(jobs, key = { "job:${it.id}" }) { job ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("${job.label} · ${job.completed}/${job.total} · ${when (job.state) { "RUNNING" -> "Generating"; "PAUSED" -> "Paused"; else -> "Queued" }}", style = MaterialTheme.typography.titleSmall)
                if (job.message.isNotBlank()) Text(job.message, style = MaterialTheme.typography.bodySmall)
                if (job.state in setOf("QUEUED", "RUNNING", "PAUSED")) TextButton(onClick = { model.cancelJob(job.id) }) { Text("Cancel batch") }
            } }
        }
        if (clones.isEmpty()) item { Text("Generated clones will appear here. You can install each copy when ready.", Modifier.padding(vertical = 24.dp)) }
        items(clones, key = { "clone:${it.id}" }) { row ->
            val installed = row.packageName in model.installedPackages
            Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.label, style = MaterialTheme.typography.titleLarge)
                Text(row.packageName, style = MaterialTheme.typography.bodySmall)
                val status = when {
                    installed -> "Installed v${model.installedVersions[row.packageName]}"
                    row.state == "BUILDING" -> "Generating"
                    row.state == "FAILED" -> "Generation failed"
                    row.artifacts != "[]" -> "Ready to install"
                    else -> "Rebuild to install"
                }
                Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (row.message.isNotBlank()) Text(row.message, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.artifacts != "[]") Button(onClick = { install(row) }, enabled = !model.busy) { Text(if (installed) "Install update" else "Install") }
                    if (installed) OutlinedButton(onClick = { model.launch(row) }) { Text("Launch") }
                    if (installed && row.state != "TESTED" && model.installedVersions[row.packageName] == row.versionCode)
                        TextButton(onClick = { model.markTested(row) }, enabled = !model.busy) { Text("Mark tested working") }
                    OutlinedButton(onClick = { update(row) }, enabled = !model.busy && row.state != "BUILDING") { Text("Update") }
                    if (row.artifacts != "[]") TextButton(onClick = { export(row) }, enabled = !model.busy) { Text("Export") }
                    TextButton(onClick = { delete(row) }, enabled = !model.busy && row.state != "BUILDING") { Text("Delete clone") }
                    if (row.message.isNotBlank()) TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MultiClone installation details", "${row.label}\n${row.packageName}\n${row.message}"))
                    }) { Text("Copy details") }
                }
            } }
        }
    }
}

@Composable
private fun rememberSaveableInt() = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
