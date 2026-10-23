package dev.multiclone.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppUpdateCard(model: MultiCloneViewModel, install: () -> Unit, generating: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleLarge)
            Text("Installed: v${BuildConfig.VERSION_NAME}")
            Text("Checks AriyenG1/MultiClone on GitHub only when you ask. A newer stable release is downloaded and verified before Android confirms installation.", style = MaterialTheme.typography.bodyMedium)
            if (model.updateStatus.isNotBlank()) Text(model.updateStatus, style = MaterialTheme.typography.bodyMedium)
            if (model.checkingUpdate) {
                val progress = model.updateProgress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$progress% downloaded")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::checkForAppUpdate, enabled = !model.checkingUpdate && !model.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check for updates") }
                if (model.checkingUpdate) TextButton(onClick = model::cancelUpdateDownload, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                if (!model.checkingUpdate && model.updateReady) OutlinedButton(onClick = model::showUpdateDialog, enabled = !model.busy && !generating, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review update") }
            }
            if (generating) Text("Pause or finish generation before installing an update.", style = MaterialTheme.typography.bodySmall)
            Text("Updates keep app data and settings in place. Signing-key backups are available below.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
