package br.dev.nobre.oaiuediag.data

import kotlinx.serialization.Serializable

@Serializable
data class TelemetryEvent(
    val sessionId: String,
    val elapsedRealtimeNanos: Long,
    val wallClockIso: String,
    val eventType: String,
    val label: String,
    val latestTelemetry: TelemetrySnapshotRecord,
    val callbackName: String? = null,
    val serviceState: String? = null,
    val dataState: String? = null,
    val dataNetworkType: String? = null,
    val displayNetworkOverride: String? = null,
    val oaiSettings: OaiSettings? = null,
)

@Serializable
data class TelemetrySnapshotRecord(
    val timestampIso: String,
    val device: DeviceRecord,
    val subscriptions: List<SubscriptionRecord>,
    val network: NetworkRecord,
    val cells: List<CellRecord>,
    val errors: List<String> = emptyList(),
)

@Serializable
data class DeviceRecord(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val buildFingerprint: String,
)

@Serializable
data class SubscriptionRecord(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val carrierName: String? = null,
    val displayName: String? = null,
    val mcc: String? = null,
    val mnc: String? = null,
)

@Serializable
data class NetworkRecord(
    val dataNetworkType: String,
    val voiceNetworkType: String,
    val isRoaming: Boolean,
    val simState: String,
)

@Serializable
data class CellRecord(
    val radio: String,
    val registered: Boolean,
    val mcc: String? = null,
    val mnc: String? = null,
    val nci: Long? = null,
    val ci: Int? = null,
    val pci: Int? = null,
    val tac: Int? = null,
    val nrarfcn: Int? = null,
    val bands: List<Int> = emptyList(),
    val earfcn: Int? = null,
    val ssRsrp: Int? = null,
    val ssRsrq: Int? = null,
    val ssSinr: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val rawDescription: String? = null,
)

data class RecorderUiState(
    val isRecording: Boolean = false,
    val sessionId: String? = null,
    val events: List<TelemetryEvent> = emptyList(),
    val summary: SessionSummary = SessionSummary(),
    val latestFileName: String? = null,
)

data class SessionSummary(
    val firstTimestampIso: String? = null,
    val lastTimestampIso: String? = null,
    val snapshotCount: Int = 0,
    val nrCellsObserved: Int = 0,
    val bestSsRsrp: Int? = null,
    val worstSsRsrp: Int? = null,
    val registeredNrSeen: Boolean = false,
    val serviceInServiceSeen: Boolean = false,
    val dataConnectedSeen: Boolean = false,
)

enum class ManualMarker(val eventType: String, val label: String) {
    AirplaneModeOff("manual_marker", "Airplane mode OFF"),
    ManualPlmnSelected("manual_marker", "Manual PLMN selected"),
    NrOnlySet("manual_marker", "NR only set in hidden menu"),
    ApnChanged("manual_marker", "APN changed"),
    AttachAttemptStarted("manual_marker", "Attach attempt started"),
    AttachAttemptFailed("manual_marker", "Attach attempt failed"),
    AttachAttemptSucceeded("manual_marker", "Attach attempt succeeded"),
}
