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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
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
                    startActivity(model.app.installer.permissionIntent()); model.installationPermissionNeeded(row)
                } else model.install(row) },
                uninstall = { row -> uninstall.launch(model.app.installer.uninstallIntent(row.packageName)) },
                export = { row -> model.exportTarget = row; exportPicker.launch(row.label.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_") + if (JsonCodec.artifacts(row.artifacts).size == 1) ".apk" else ".apks") },
                backup = { password, restore -> model.pendingPassword?.fill('\u0000'); model.pendingPassword = password
                    if (restore) restorePicker.launch(arrayOf("*/*")) else backupPicker.launch("MultiClone-signing-backup.mclone") },
                installAppUpdate = {
                    if (!model.app.installer.canInstall()) {
                        startActivity(model.app.installer.permissionIntent())
                        model.tell("Allow installation from MultiClone, return here, then tap Install update again.")
                    } else model.installAppUpdate { startActivity(it) }
                },
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
    backup: (CharArray, Boolean) -> Unit, beforeQueue: () -> Unit, installAppUpdate: () -> Unit) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val clones by model.clones.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val selectedId = model.selectedId
    LaunchedEffect(model.installRequest, model.busy) {
        if (!model.busy) model.takeInstallRequest()?.let(install)
    }
    var tab by rememberSaveableInt()
    val screenStates = rememberSaveableStateHolder()
    var installDetailsOpen by rememberSaveable { mutableStateOf(false) }
    var updateDetails by rememberSaveable { mutableStateOf<String?>(null) }
    var updateDetailsError by rememberSaveable { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf<Boolean?>(null) }
    var deleteTarget by remember { mutableStateOf<CloneRow?>(null) }
    val selected = sources.find { it.id == selectedId }
    val source = remember(selected?.json) { selected?.let { JsonCodec.source(it.json) } }
    BackHandler(enabled = tab != 0) { tab = 0 }
    val pendingIds = model.generatedCloneIds
    val readyClones = clones.filter { it.id in pendingIds && it.artifacts != "[]" && it.state != "BUILDING" && it.state != "FAILED" }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().semantics { testTagsAsResourceId = true }) {
            // Window constraints, not device identity or orientation: also works in Samsung split screen / DeX.
            val useRail = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    if (useRail) NavigationRail(modifier = Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Spacer(Modifier.height(16.dp))
                        destinations.take(2).forEachIndexed { index, title ->
                            NavigationRailItem(selected = tab == index, onClick = { tab = index },
                                icon = { DestinationIcon(index) }, label = { Text(title) },
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(selected = tab == 2, onClick = { tab = 2 },
                            icon = { DestinationIcon(2) }, alwaysShowLabel = false,
                            modifier = Modifier.padding(bottom = 12.dp).semantics { contentDescription = "Settings" })
                    }
                    Column(Modifier.weight(1f)) {
                        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (model.message.isNotBlank()) StatusMessage(model.message, model.messageIsError, onDismiss = { model.tell("") })
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            screenStates.SaveableStateProvider(tab) {
                            when (tab) {
                                0 -> CreatePanel(model, source, importFiles, importIcon, beforeQueue,
                                    Modifier.widthIn(max = 840.dp).fillMaxSize(),
                                    readyCount = readyClones.size, showReady = { model.openInstallPrompt() })
                                1 -> DashboardPanel(clones, jobs, model, install, export, { deleteTarget = it },
                                    model::updateClone, Modifier.widthIn(max = 1040.dp).fillMaxSize())
                                else -> Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Signing keys & device", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                                    AppUpdateCard(model, installAppUpdate, jobs.any { it.state in setOf("RUNNING", "QUEUED") })
                                    Text("Back up your keys", style = MaterialTheme.typography.titleLarge)
                                    Text("Signing keys let existing clones receive updates. Back them up before uninstalling or resetting MultiClone. Backups include clone identities and source metadata, but no app sessions, source APKs or login data.")
                                    Button(onClick = { backupMode = false }, enabled = !model.busy) { Text("Export encrypted backup") }
                                    OutlinedButton(onClick = { backupMode = true }, enabled = !model.busy) { Text("Restore encrypted backup") }
                                    HorizontalDivider()
                                    Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}")
                                    Text("${model.app.filesDir.usableSpace / (1024 * 1024)} MB available in private storage")
                                    Text("Installation permission: ${if (model.app.installer.canInstall()) "allowed" else "not granted"}")
                                    Text("Samsung Auto Blocker or device policy may block sideloading. Complete any applicable system settings and confirmations yourself.")
                                    Text("Private app storage is separate. Browser cookies and external account brokers may still be shared. Apps requiring their original package or certificate may fail.")
                                    Text("Version ${BuildConfig.VERSION_NAME} · On-demand GitHub updates · No telemetry", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            }
                        }
                    }
                }
                if (!useRail) NavigationBar {
                    destinations.forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { DestinationIcon(index) }, label = if (index == 2) null else { { Text(title) } },
                            modifier = Modifier.semantics { contentDescription = title })
                    }
                }
            }
            model.cloneUpdateNotice?.let { notice ->
                CloneUpdateBanner(notice, model::dismissCloneUpdateNotice, { updateDetails = notice.text; updateDetailsError = notice.error },
                    Modifier.align(Alignment.BottomCenter).padding(start = if (useRail) 96.dp else 16.dp,
                        end = 16.dp, bottom = if (useRail) 16.dp else 96.dp).widthIn(max = 640.dp))
            }
        }
    }
    updateDetails?.let { details -> AlertDialog(onDismissRequest = { updateDetails = null },
        title = { Text(if (updateDetailsError) "Update needs attention" else "Clone update") },
        text = { Text(details, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { updateDetails = null }) { Text("Close") } }) }
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
                            else Button(onClick = { install(row) }, enabled = !model.busy && row.id !in model.cloneOperations,
                                modifier = Modifier.heightIn(min = 48.dp)) { Text("Install ${row.label}") }
                            model.cloneOperations[row.id]?.let { CloneProgress(it) }
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
    if (model.updateDialog) AlertDialog(onDismissRequest = model::dismissUpdateDialog,
        title = { Text("Update MultiClone to v${model.availableVersion}?") },
        text = { Text("The download and signing certificate have been verified. Android will ask you to confirm the update. Your clone library, signing keys and settings stay in the existing app storage. Pause generation before installing.") },
        confirmButton = { TextButton(onClick = installAppUpdate, enabled = !model.busy && jobs.none { it.state in setOf("RUNNING", "QUEUED") }) { Text("Install update") } },
        dismissButton = { TextButton(onClick = model::dismissUpdateDialog) { Text("Later") } })
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
private fun CreatePanel(model: MultiCloneViewModel, source: ApkSource?, importFiles: () -> Unit,
    importIcon: () -> Unit, beforeQueue: () -> Unit, modifier: Modifier, readyCount: Int, showReady: () -> Unit) {
    var name by rememberSaveable(model.formRevision, source?.id) { mutableStateOf("") }
    var count by rememberSaveable(model.formRevision, source?.id) { mutableStateOf("") }
    var cloneNames by rememberSaveable(model.formRevision, source?.id) { mutableStateOf(List(5) { "" }) }
    val quantity = count.toLongOrNull()
    val individual = quantity != null && quantity in 2L..5L
    val names = if (individual) cloneNames.take(quantity!!.toInt()) else emptyList()
    val nameError = if (individual) names.any { it.isBlank() || it.length > 80 } else name.isBlank() || name.length > 80
    val countError = quantity == null || quantity <= 0
    Column(modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Make room for another you.",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Create an independent copy of your app. Everything happens on your device.",
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
                OutlinedTextField(count, { if (it.all(Char::isDigit)) count = it }, label = { Text("Number of clones") },
                    supportingText = { Text(if (count.isBlank()) "Choose how many copies to create." else if (countError) "Enter a whole number greater than zero." else "Each copy has separate app storage.") },
                    isError = count.isNotBlank() && countError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("clone-count"), singleLine = true)
                if (individual) {
                    Text("Name each copy", style = MaterialTheme.typography.titleMedium)
                    names.forEachIndexed { index, value ->
                        OutlinedTextField(value, { text -> cloneNames = cloneNames.toMutableList().also { it[index] = text } },
                            label = { Text("Clone ${index + 1} name") },
                            supportingText = { Text("${value.length}/80") },
                            isError = value.length > 80, modifier = Modifier.fillMaxWidth().testTag("clone-name-${index + 1}"), singleLine = true)
                    }
                } else {
                    OutlinedTextField(name, { name = it }, label = { Text(if (quantity != null && quantity > 5) "Batch prefix" else "Clone name") },
                        supportingText = { Text(if (quantity != null && quantity > 5) "Copies are named $name 1, $name 2, …" else "${name.length}/80") },
                        isError = name.length > 80, modifier = Modifier.fillMaxWidth().testTag("clone-label"), singleLine = true)
                }
                if (quantity == 1L) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = importIcon, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (model.icon == null) "Choose custom icon" else "Change custom icon")
                    }
                    if (model.icon != null) TextButton(onClick = model::clearIcon, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset icon") }
                }
                if (quantity != null && quantity > 1) Text("Batch copies use the original app icon.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !model.busy && source.buildable && !nameError && !countError,
                    onClick = { beforeQueue(); model.enqueue(source, if (individual) names.first() else name, quantity!!, names) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("create-clones"), shape = RoundedCornerShape(16.dp)) {
                    Text("Create ${quantity ?: ""} ${if (quantity == 1L) "clone" else "clones"}")
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
                else "Ready to create", style = MaterialTheme.typography.bodyMedium)
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
                model.cloneOperations[row.id]?.let { CloneProgress(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val working = row.id in model.cloneOperations
                    if (!installed && row.artifacts != "[]") Button(onClick = { install(row) }, enabled = !model.busy && !working,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Install") }
                    if (installed) Button(onClick = { update(row) }, enabled = !model.busy && model.cloneOperations.isEmpty() && row.state != "BUILDING",
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Update") }
                    if (installed) OutlinedButton(onClick = { model.launch(row) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Launch") }
                    if (row.artifacts != "[]") TextButton(onClick = { export(row) }, enabled = !model.busy) { Text("Export") }
                    TextButton(onClick = { delete(row) }, enabled = !model.busy && !working && row.state != "BUILDING") { Text("Delete clone") }
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

@Composable
private fun CloneProgress(operation: CloneOperation) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(operation.label, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        if (operation.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { operation.progress }, modifier = Modifier.fillMaxWidth())
    }
}
