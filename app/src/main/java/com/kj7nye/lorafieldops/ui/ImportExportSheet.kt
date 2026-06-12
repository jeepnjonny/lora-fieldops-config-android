package com.kj7nye.lorafieldops.ui

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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

            TabRow(selectedTabIndex = tab) {
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
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
                    onClick = { clipboard.setText(AnnotatedString(json)) },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_SUBJECT, "tracker_conf.json")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share config"))
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
    var statusText by remember { mutableStateOf("") }
    val snack by vm.snackMessage.collectAsState()

    LaunchedEffect(snack) {
        snack?.let { msg ->
            statusText = msg
            if (msg.contains("successful")) onSuccess()
            vm.clearSnack()
        }
    }

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
        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusText.contains("failed") || statusText.contains("timed"))
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
            )
        }
        Button(
            onClick = {
                if (pasteText.isNotBlank()) {
                    statusText = "Sending…"
                    vm.importJson(pasteText)
                }
            },
            enabled = pasteText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send to device") }
    }
}
