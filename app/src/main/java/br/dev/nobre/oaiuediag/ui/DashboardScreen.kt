package br.dev.nobre.oaiuediag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.dev.nobre.oaiuediag.data.CellSummary
import br.dev.nobre.oaiuediag.data.DiagnosisResult
import br.dev.nobre.oaiuediag.data.GenericCellSummary
import br.dev.nobre.oaiuediag.data.LteCellSummary
import br.dev.nobre.oaiuediag.data.ManualMarker
import br.dev.nobre.oaiuediag.data.NrCellSummary
import br.dev.nobre.oaiuediag.data.OaiSettings
import br.dev.nobre.oaiuediag.data.RecorderUiState
import br.dev.nobre.oaiuediag.data.SessionSummary
import br.dev.nobre.oaiuediag.data.TelemetrySnapshot
import br.dev.nobre.oaiuediag.ui.components.DiagCard
import br.dev.nobre.oaiuediag.ui.components.FieldRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    snapshot: TelemetrySnapshot,
    recorderState: RecorderUiState,
    oaiSettings: OaiSettings,
    diagnosis: DiagnosisResult,
    hasAllPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTesting: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onAddMarker: (String) -> Unit,
    onClearSession: () -> Unit,
    onShareLatest: () -> Unit,
    onSaveOaiSettings: (OaiSettings) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OAI UE Diag") },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Snapshot: ${snapshot.timestamp}",
                fontWeight = FontWeight.Medium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Refresh now")
                }
                OutlinedButton(
                    onClick = onOpenTesting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open Phone Info")
                }
            }

            if (!hasAllPermissions) {
                DiagCard(title = "Permissions") {
                    Text("Phone state and fine location permissions are required for complete diagnostics.")
                    Button(onClick = onRequestPermissions) {
                        Text("Grant permissions")
                    }
                }
            }

            RecorderCard(
                recorderState = recorderState,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording,
                onAddMarker = onAddMarker,
                onClearSession = onClearSession,
                onShareLatest = onShareLatest,
            )
            OaiSettingsCard(
                settings = oaiSettings,
                onSave = onSaveOaiSettings,
            )
            DiagnosisCard(diagnosis)
            LimitationsCard()
            DeviceCard(snapshot)
            SubscriptionCard(snapshot)
            NetworkCard(snapshot)
            CellListCard(snapshot.cells)

            if (snapshot.errors.isNotEmpty()) {
                DiagCard(title = "Read notes") {
                    snapshot.errors.forEach { error ->
                        Text(error)
                    }
                }
            }
        }
    }
}

@Composable
private fun OaiSettingsCard(
    settings: OaiSettings,
    onSave: (OaiSettings) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var draft by remember(settings) { mutableStateOf(settings) }

    DiagCard(title = "OAI diagnosis settings") {
        FieldRow("MCC/MNC", listOf(settings.mcc, settings.mnc).filter { it.isNotBlank() }.joinToString("/").ifBlank { "Not set" })
        FieldRow("TAC", settings.tac.ifBlank { "Not set" })
        FieldRow("NRARFCN", settings.nrarfcn.ifBlank { "Not set" })
        FieldRow("PCI", settings.pci.ifBlank { "Not set" })
        FieldRow("DNN/APN", settings.dnnApnLabel.ifBlank { "Not set" })
        FieldRow("SST/SD", listOf(settings.sst, settings.sd).filter { it.isNotBlank() }.joinToString("/").ifBlank { "Not set" })
        if (settings.notes.isNotBlank()) {
            FieldRow("Notes", settings.notes)
        }
        Text("Values are used only for comparison and report hints.")
        OutlinedButton(
            onClick = {
                draft = settings
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Edit OAI settings")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("OAI diagnosis settings") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsField("MCC", draft.mcc) { draft = draft.copy(mcc = it) }
                    SettingsField("MNC", draft.mnc) { draft = draft.copy(mnc = it) }
                    SettingsField("TAC", draft.tac) { draft = draft.copy(tac = it) }
                    SettingsField("NRARFCN", draft.nrarfcn) { draft = draft.copy(nrarfcn = it) }
                    SettingsField("PCI", draft.pci) { draft = draft.copy(pci = it) }
                    SettingsField("DNN/APN label", draft.dnnApnLabel) { draft = draft.copy(dnnApnLabel = it) }
                    SettingsField("SST", draft.sst) { draft = draft.copy(sst = it) }
                    SettingsField("SD", draft.sd) { draft = draft.copy(sd = it) }
                    SettingsField("Notes", draft.notes, minLines = 3) { draft = draft.copy(notes = it) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(draft)
                        showDialog = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DiagnosisCard(diagnosis: DiagnosisResult) {
    DiagCard(title = "Diagnosis") {
        FieldRow("Verdict", diagnosis.verdict)
        diagnosis.details.forEach { detail ->
            Text(detail)
        }
        Text("This is a non-definitive phone-side hint. Final analysis requires correlation with OAI gNB/AMF/SMF/UPF logs.")
    }
}

@Composable
private fun LimitationsCard() {
    DiagCard(title = "Diagnosis limitations") {
        Text("The app cannot directly see NAS Registration Reject causes.")
        Text("The app cannot directly see RRC reject/release causes.")
        Text("The app cannot directly see AMF/SMF reject causes.")
        Text("The app cannot guarantee slice/DNN/IMS status from Android public APIs.")
        Text("Final diagnosis requires correlation with OAI gNB/AMF/SMF/UPF logs.")
    }
}

@Composable
private fun RecorderCard(
    recorderState: RecorderUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onAddMarker: (String) -> Unit,
    onClearSession: () -> Unit,
    onShareLatest: () -> Unit,
) {
    var showCustomNote by remember { mutableStateOf(false) }
    var customNote by remember { mutableStateOf("") }

    DiagCard(title = "Recorder") {
        FieldRow("Status", if (recorderState.isRecording) "Recording" else "Stopped")
        recorderState.sessionId?.let { FieldRow("Session ID", it) }
        recorderState.latestFileName?.let { FieldRow("Latest file", it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onStartRecording,
                enabled = !recorderState.isRecording,
                modifier = Modifier.weight(1f),
            ) {
                Text("Start Recording")
            }
            Button(
                onClick = onStopRecording,
                enabled = recorderState.isRecording,
                modifier = Modifier.weight(1f),
            ) {
                Text("Stop Recording")
            }
        }

        OutlinedButton(
            onClick = onShareLatest,
            enabled = recorderState.latestFileName != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export / Share JSONL")
        }
        OutlinedButton(
            onClick = onClearSession,
            enabled = !recorderState.isRecording && recorderState.events.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Clear current session")
        }

        Text("Markers", fontWeight = FontWeight.SemiBold)
        ManualMarker.entries.forEach { marker ->
            OutlinedButton(
                onClick = { onAddMarker(marker.label) },
                enabled = recorderState.isRecording,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(marker.label)
            }
        }
        OutlinedButton(
            onClick = { showCustomNote = true },
            enabled = recorderState.isRecording,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Custom note")
        }
    }

    SessionSummaryCard(recorderState.summary)

    if (showCustomNote) {
        AlertDialog(
            onDismissRequest = { showCustomNote = false },
            title = { Text("Custom note") },
            text = {
                OutlinedTextField(
                    value = customNote,
                    onValueChange = { customNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val note = customNote.trim()
                        if (note.isNotEmpty()) {
                            onAddMarker("Custom note: $note")
                        }
                        customNote = ""
                        showCustomNote = false
                    },
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCustomNote = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SessionSummaryCard(summary: SessionSummary) {
    DiagCard(title = "Session summary") {
        FieldRow("First timestamp", summary.firstTimestampIso ?: "Unavailable")
        FieldRow("Last timestamp", summary.lastTimestampIso ?: "Unavailable")
        FieldRow("Snapshots", summary.snapshotCount.toString())
        FieldRow("NR cells observed", summary.nrCellsObserved.toString())
        FieldRow("Best SS-RSRP", summary.bestSsRsrp.dbm())
        FieldRow("Worst SS-RSRP", summary.worstSsRsrp.dbm())
        FieldRow("Registered NR cell seen", if (summary.registeredNrSeen) "Yes" else "No")
        FieldRow("Service IN_SERVICE seen", if (summary.serviceInServiceSeen) "Yes" else "No")
        FieldRow("Data CONNECTED seen", if (summary.dataConnectedSeen) "Yes" else "No")
    }
}

@Composable
private fun DeviceCard(snapshot: TelemetrySnapshot) {
    DiagCard(title = "Device") {
        FieldRow("Manufacturer", snapshot.device.manufacturer)
        FieldRow("Model", snapshot.device.model)
        FieldRow("Android", snapshot.device.androidVersion)
        FieldRow("SDK", snapshot.device.sdk.toString())
        FieldRow("Build fingerprint", snapshot.device.buildFingerprint)
    }
}

@Composable
private fun SubscriptionCard(snapshot: TelemetrySnapshot) {
    DiagCard(title = "SIM / subscriptions") {
        if (snapshot.subscriptions.isEmpty()) {
            Text("No active subscriptions reported.")
        } else {
            snapshot.subscriptions.forEachIndexed { index, subscription ->
                if (index > 0) {
                    Text("")
                }
                FieldRow("Subscription ID", subscription.subscriptionId.toString())
                FieldRow("SIM slot", subscription.simSlotIndex.toString())
                FieldRow("Carrier", subscription.carrierName ?: "Unavailable")
                FieldRow("Display name", subscription.displayName ?: "Unavailable")
                FieldRow("MCC/MNC", listOfNotNull(subscription.mcc, subscription.mnc).joinToString("/").ifBlank { "Unavailable" })
            }
        }
    }
}

@Composable
private fun NetworkCard(snapshot: TelemetrySnapshot) {
    DiagCard(title = "Current network") {
        FieldRow("Data network", snapshot.network.dataNetworkType)
        FieldRow("Voice network", snapshot.network.voiceNetworkType)
        FieldRow("Roaming", if (snapshot.network.isRoaming) "Yes" else "No")
        FieldRow("SIM state", snapshot.network.simState)
    }
}

@Composable
private fun CellListCard(cells: List<CellSummary>) {
    DiagCard(title = "Current cell list") {
        if (cells.isEmpty()) {
            Text("No cell information reported.")
        } else {
            cells.forEachIndexed { index, cell ->
                Text(
                    text = "${index + 1}. ${cell.radio} ${if (cell.registered) "(registered)" else ""}",
                    fontWeight = FontWeight.SemiBold,
                )
                CellDetails(cell)
            }
        }
    }
}

@Composable
private fun CellDetails(cell: CellSummary) {
    when (cell) {
        is NrCellSummary -> {
            FieldRow("MCC/MNC", listOfNotNull(cell.mcc, cell.mnc).joinToString("/").ifBlank { "Unavailable" })
            FieldRow("NCI", cell.nci.display())
            FieldRow("PCI", cell.pci.display())
            FieldRow("TAC", cell.tac.display())
            FieldRow("NRARFCN", cell.nrarfcn.display())
            FieldRow("Bands", cell.bands.joinToString { "n$it" }.ifBlank { "Unavailable" })
            FieldRow("SS-RSRP", cell.ssRsrp.dbm())
            FieldRow("SS-RSRQ", cell.ssRsrq.db())
            FieldRow("SS-SINR", cell.ssSinr.db())
        }
        is LteCellSummary -> {
            FieldRow("MCC/MNC", listOfNotNull(cell.mcc, cell.mnc).joinToString("/").ifBlank { "Unavailable" })
            FieldRow("CI", cell.ci.display())
            FieldRow("PCI", cell.pci.display())
            FieldRow("TAC", cell.tac.display())
            FieldRow("EARFCN", cell.earfcn.display())
            FieldRow("RSRP", cell.rsrp.dbm())
            FieldRow("RSRQ", cell.rsrq.db())
        }
        is GenericCellSummary -> {
            FieldRow("Registered", if (cell.registered) "Yes" else "No")
            FieldRow("Raw", cell.description)
        }
    }
}

private fun Int?.display(): String = this?.toString() ?: "Unavailable"

private fun Long?.display(): String = this?.toString() ?: "Unavailable"

private fun Int?.dbm(): String = this?.let { "$it dBm" } ?: "Unavailable"

private fun Int?.db(): String = this?.let { "$it dB" } ?: "Unavailable"
