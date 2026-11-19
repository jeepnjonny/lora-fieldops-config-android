package com.kj7nye.lorafieldops.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val LOG_LEVELS = listOf("off", "error", "warn", "info", "debug")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(vm: ConfigViewModel) {
    val logLines by vm.logLines.collectAsStateWithLifecycle()
    val currentLevel by vm.currentLogLevel.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Enter log mode on the device when this screen is shown
    LaunchedEffect(Unit) { vm.setLogLevel(currentLevel) }

    // Follow new lines only while the user is at the bottom. Scrolling up to read
    // pauses it; scrolling back down (or "Jump to latest") resumes it.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }.collect { bottom -> if (listState.isScrollInProgress) follow = bottom }
    }

    // Keyed on the list itself: once the buffer is full (1000 lines) its size stops changing.
    LaunchedEffect(logLines) {
        if (follow && logLines.isNotEmpty()) listState.scrollToItem(logLines.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Toolbar: log level selector + clear button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = currentLevel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Log level") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    LOG_LEVELS.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level) },
                            onClick = {
                                expanded = false
                                vm.setLogLevel(level)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { vm.clearLog() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear log")
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Scrollable monospace log output
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
            if (!follow && logLines.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        follow = true
                        scope.launch { listState.scrollToItem(logLines.size - 1) }
                    },
                    icon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                    text = { Text("Jump to latest") },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}
