package com.tickers.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tickers.app.update.UpdateState
import com.tickers.app.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Card at the top of the list while an update is available, downloading, or failed. */
@Composable
fun UpdateBanner(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // "Up to date" after a manual check: show briefly, then hide.
    if (state is UpdateState.UpToDate) {
        LaunchedEffect(state) { delay(3_000); updater.dismiss() }
    }

    val (title, detail) = when (val s = state) {
        UpdateState.Idle, UpdateState.Checking -> return
        UpdateState.UpToDate -> "You're on the latest version" to null
        is UpdateState.Available -> "Update available: ${s.release.tagName}" to "You have ${updater.currentVersion}"
        is UpdateState.Downloading -> "Downloading ${s.release.tagName}…" to null
        UpdateState.Installing -> "Installing…" to "Confirm in the system prompt"
        is UpdateState.NeedsPermission -> "Allow updates" to
            "Let Tickers install updates in the next screen, then come back and tap Install."
        is UpdateState.Failed -> s.message to null
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            (state as? UpdateState.Downloading)?.let {
                LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is UpdateState.Available -> {
                        Button(onClick = { scope.launch { updater.downloadAndInstall(s.release) } }) {
                            Text("Install")
                        }
                        TextButton(onClick = updater::dismiss) { Text("Later") }
                    }
                    is UpdateState.NeedsPermission -> {
                        Button(onClick = { context.startActivity(updater.permissionSettingsIntent()) }) {
                            Text("Open settings")
                        }
                        TextButton(onClick = { scope.launch { updater.downloadAndInstall(s.release) } }) {
                            Text("Install")
                        }
                    }
                    is UpdateState.Failed -> {
                        s.release?.let { r ->
                            Button(onClick = { scope.launch { updater.downloadAndInstall(r) } }) { Text("Retry") }
                        }
                        TextButton(onClick = updater::dismiss) { Text("Dismiss") }
                    }
                    else -> Unit
                }
            }
        }
    }
}
