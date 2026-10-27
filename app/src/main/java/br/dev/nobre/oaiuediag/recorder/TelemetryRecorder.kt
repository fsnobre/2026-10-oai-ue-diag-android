package br.dev.nobre.oaiuediag.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import br.dev.nobre.oaiuediag.data.CellRecord
import br.dev.nobre.oaiuediag.data.OaiSettings
import br.dev.nobre.oaiuediag.data.RecorderUiState
import br.dev.nobre.oaiuediag.data.SessionSummary
import br.dev.nobre.oaiuediag.data.TelemetryEvent
import br.dev.nobre.oaiuediag.telephony.TelephonyReader
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TelemetryRecorder(
    private val context: Context,
    private val reader: TelephonyReader,
    private val oaiSettingsProvider: () -> OaiSettings = { OaiSettings() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RecorderUiState())

    private var sampleJob: Job? = null
    private var callback: RecorderTelephonyCallback? = null
    private var sessionFile: File? = null

    val state: StateFlow<RecorderUiState> = _state

    fun start() {
        if (_state.value.isRecording) return

        val sessionId = UUID.randomUUID().toString()
        val file = recordingsDir().resolve("oai-ue-diag-$sessionId.jsonl")
        sessionFile = file
        _state.value = RecorderUiState(
            isRecording = true,
            sessionId = sessionId,
            latestFileName = file.name,
        )

        registerTelephonyCallback()
        sampleJob = scope.launch {
            appendEvent(
                eventType = "session_started",
                label = "Recording started",
            )
            while (isActive) {
                appendEvent(
                    eventType = "snapshot",
                    label = "1-second telemetry snapshot",
                )
                delay(1_000)
            }
        }
    }

    fun stop() {
        if (!_state.value.isRecording) return

        sampleJob?.cancel()
        sampleJob = null
        unregisterTelephonyCallback()
        scope.launch {
            appendEvent(
                eventType = "session_stopped",
                label = "Recording stopped",
            )
            _state.update { it.copy(isRecording = false) }
        }
    }

    fun recordMarker(label: String) {
        if (!_state.value.isRecording) return

        scope.launch {
            appendEvent(
                eventType = "manual_marker",
                label = label,
            )
        }
    }

    fun clearCurrentSession() {
        if (_state.value.isRecording) return

        sessionFile = null
        _state.value = RecorderUiState()
    }

    fun latestSessionFile(): File? = sessionFile?.takeIf { it.exists() }

    fun close() {
        sampleJob?.cancel()
        unregisterTelephonyCallback()
        scope.cancel()
    }

    private suspend fun appendEvent(
        eventType: String,
        label: String,
        callbackName: String? = null,
        serviceState: String? = null,
        dataState: String? = null,
        dataNetworkType: String? = null,
        displayNetworkOverride: String? = null,
    ) {
        val current = _state.value
        val sessionId = current.sessionId ?: return
        val file = sessionFile ?: return
        val event = TelemetryEvent(
            sessionId = sessionId,
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            wallClockIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            eventType = eventType,
            label = label,
            latestTelemetry = TelemetryEventSerializer.snapshotRecord(reader.readSnapshot()),
            callbackName = callbackName,
            serviceState = serviceState,
            dataState = dataState,
            dataNetworkType = dataNetworkType,
            displayNetworkOverride = displayNetworkOverride,
            oaiSettings = oaiSettingsProvider(),
        )

        mutex.withLock {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.appendText(TelemetryEventSerializer.toJsonLine(event) + "\n")
            }
            _state.update { state ->
                val events = state.events + event
                state.copy(
                    events = events,
                    summary = summarize(events),
                    latestFileName = file.name,
                )
            }
        }
    }

    private fun registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return

        callback = RecorderTelephonyCallback { event ->
            scope.launch {
                appendEvent(
                    eventType = "telephony_callback",
                    label = event.label,
                    callbackName = event.callbackName,
                    serviceState = event.serviceState,
                    dataState = event.dataState,
                    dataNetworkType = event.dataNetworkType,
                    displayNetworkOverride = event.displayNetworkOverride,
                )
            }
        }

        runCatching {
            telephonyManager.registerTelephonyCallback(context.mainExecutor, callback!!)
        }.onFailure {
            callback = null
        }
    }

    private fun unregisterTelephonyCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        callback?.let { activeCallback ->
            runCatching {
                telephonyManager.unregisterTelephonyCallback(activeCallback)
            }
        }
        callback = null
    }

    private fun recordingsDir(): File {
        return File(context.filesDir, "recordings").also { it.mkdirs() }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun summarize(events: List<TelemetryEvent>): SessionSummary {
        val nrCells = events.flatMap { event ->
            event.latestTelemetry.cells.filter { it.radio == "NR" }
        }
        val nrKeys = nrCells.map { cell ->
            cell.nci?.toString() ?: listOfNotNull(cell.mcc, cell.mnc, cell.pci, cell.tac, cell.nrarfcn)
                .joinToString(":")
                .ifBlank { cell.hashCode().toString() }
        }.toSet()
        val ssRsrpValues = nrCells.mapNotNull(CellRecord::ssRsrp)

        return SessionSummary(
            firstTimestampIso = events.firstOrNull()?.wallClockIso,
            lastTimestampIso = events.lastOrNull()?.wallClockIso,
            snapshotCount = events.count { it.eventType == "snapshot" },
            nrCellsObserved = nrKeys.size,
            bestSsRsrp = ssRsrpValues.maxOrNull(),
            worstSsRsrp = ssRsrpValues.minOrNull(),
            registeredNrSeen = nrCells.any { it.registered },
            serviceInServiceSeen = events.any { it.serviceState == "IN_SERVICE" },
            dataConnectedSeen = events.any { it.dataState == "CONNECTED" },
        )
    }
}

private data class CallbackEvent(
    val callbackName: String,
    val label: String,
    val serviceState: String? = null,
    val dataState: String? = null,
    val dataNetworkType: String? = null,
    val displayNetworkOverride: String? = null,
)

private class RecorderTelephonyCallback(
    private val onEvent: (CallbackEvent) -> Unit,
) : TelephonyCallback(),
    TelephonyCallback.ServiceStateListener,
    TelephonyCallback.SignalStrengthsListener,
    TelephonyCallback.CellInfoListener,
    TelephonyCallback.DisplayInfoListener,
    TelephonyCallback.DataConnectionStateListener {

    override fun onServiceStateChanged(serviceState: ServiceState) {
        val state = serviceStateName(serviceState.state)
        onEvent(
            CallbackEvent(
                callbackName = "service_state",
                label = "Service state changed: $state",
                serviceState = state,
            ),
        )
    }

    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
        onEvent(
            CallbackEvent(
                callbackName = "signal_strength",
                label = "Signal strength changed",
            ),
        )
    }

    override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) {
        onEvent(
            CallbackEvent(
                callbackName = "cell_info",
                label = "Cell info changed (${cellInfo.size} cells)",
            ),
        )
    }

    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
        val override = displayOverrideName(telephonyDisplayInfo.overrideNetworkType)
        onEvent(
            CallbackEvent(
                callbackName = "display_info",
                label = "Display info changed: $override",
                displayNetworkOverride = override,
            ),
        )
    }

    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
        val dataState = dataStateName(state)
        val network = networkTypeName(networkType)
        onEvent(
            CallbackEvent(
                callbackName = "data_connection_state",
                label = "Data connection state changed: $dataState on $network",
                dataState = dataState,
                dataNetworkType = network,
            ),
        )
    }

    private fun serviceStateName(state: Int): String {
        return when (state) {
            ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
            ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
            ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
            ServiceState.STATE_POWER_OFF -> "POWER_OFF"
            else -> "UNKNOWN_$state"
        }
    }

    private fun dataStateName(state: Int): String {
        return when (state) {
            TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
            TelephonyManager.DATA_CONNECTING -> "CONNECTING"
            TelephonyManager.DATA_CONNECTED -> "CONNECTED"
            TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
            TelephonyManager.DATA_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN_$state"
        }
    }

    private fun networkTypeName(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
            else -> "TYPE_$type"
        }
    }

    private fun displayOverrideName(type: Int): String {
        return when (type) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
            else -> "UNKNOWN_$type"
        }
    }
}
