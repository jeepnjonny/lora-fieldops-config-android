package io.github.jeepnjonny.lorafieldops.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import io.github.jeepnjonny.lorafieldops.platform.plainTextClip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportSheet(vm: ConfigViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Import / Export", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Export") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Import") })
            }

            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                ExportTab(vm)
            } else {
                ImportTab(vm) { scope.launch { sheetState.hide(); onDismiss() } }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExportTab(vm: ConfigViewModel) {
    val clipboard = LocalClipboard.current
    var json by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        json = vm.exportRaw() ?: "(export failed)"
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) {
            Text("Fetching config from device…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Current tracker_conf.json",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch { clipboard.setClipEntry(plainTextClip("tracker_conf.json", json)) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                Button(
                    onClick = {
                        vm.platform.shareText(json, subject = "tracker_conf.json")
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loading = true
                        json = vm.exportRaw() ?: "(export failed)"
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Re-fetch from device") }
        }
    }
}

@Composable
private fun ImportTab(vm: ConfigViewModel, onSuccess: () -> Unit) {
    var pasteText by remember { mutableStateOf("") }
    val state by vm.importState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ImportState.Done) onSuccess()
    }
    val sending = state is ImportState.Sending

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Paste a complete tracker_conf.json. The device will parse, write, and reboot.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = pasteText,
            onValueChange = { pasteText = it },
            label = { Text("Paste JSON here") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
            minLines = 8,
        )
        (state as? ImportState.Failed)?.let {
            Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { vm.importJson(pasteText) },
            enabled = pasteText.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Sending…")
            } else {
                Text("Send to device")
            }
        }
    }
}
