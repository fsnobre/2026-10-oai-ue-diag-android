package br.dev.nobre.oaiuediag.data

import java.time.Instant

data class TelemetrySnapshot(
    val timestamp: Instant,
    val device: DeviceSummary,
    val subscriptions: List<SubscriptionSummary>,
    val network: NetworkSummary,
    val cells: List<CellSummary>,
    val errors: List<String> = emptyList(),
)

data class DeviceSummary(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val buildFingerprint: String,
)

data class SubscriptionSummary(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val carrierName: String?,
    val displayName: String?,
    val mcc: String?,
    val mnc: String?,
)

data class NetworkSummary(
    val dataNetworkType: String,
    val voiceNetworkType: String,
    val isRoaming: Boolean,
    val simState: String,
)
