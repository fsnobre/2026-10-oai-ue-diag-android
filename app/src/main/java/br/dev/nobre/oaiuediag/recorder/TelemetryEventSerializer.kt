package br.dev.nobre.oaiuediag.recorder

import br.dev.nobre.oaiuediag.data.CellRecord
import br.dev.nobre.oaiuediag.data.CellSummary
import br.dev.nobre.oaiuediag.data.DeviceRecord
import br.dev.nobre.oaiuediag.data.GenericCellSummary
import br.dev.nobre.oaiuediag.data.LteCellSummary
import br.dev.nobre.oaiuediag.data.NetworkRecord
import br.dev.nobre.oaiuediag.data.NrCellSummary
import br.dev.nobre.oaiuediag.data.SubscriptionRecord
import br.dev.nobre.oaiuediag.data.TelemetryEvent
import br.dev.nobre.oaiuediag.data.TelemetrySnapshot
import br.dev.nobre.oaiuediag.data.TelemetrySnapshotRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TelemetryEventSerializer {
    val json = Json {
        explicitNulls = false
        prettyPrint = false
    }

    fun toJsonLine(event: TelemetryEvent): String {
        return json.encodeToString(event)
    }

    fun snapshotRecord(snapshot: TelemetrySnapshot): TelemetrySnapshotRecord {
        return TelemetrySnapshotRecord(
            timestampIso = snapshot.timestamp.toString(),
            device = DeviceRecord(
                manufacturer = snapshot.device.manufacturer,
                model = snapshot.device.model,
                androidVersion = snapshot.device.androidVersion,
                sdk = snapshot.device.sdk,
                buildFingerprint = snapshot.device.buildFingerprint,
            ),
            subscriptions = snapshot.subscriptions.map { subscription ->
                SubscriptionRecord(
                    subscriptionId = subscription.subscriptionId,
                    simSlotIndex = subscription.simSlotIndex,
                    carrierName = subscription.carrierName,
                    displayName = subscription.displayName,
                    mcc = subscription.mcc,
                    mnc = subscription.mnc,
                )
            },
            network = NetworkRecord(
                dataNetworkType = snapshot.network.dataNetworkType,
                voiceNetworkType = snapshot.network.voiceNetworkType,
                isRoaming = snapshot.network.isRoaming,
                simState = snapshot.network.simState,
            ),
            cells = snapshot.cells.map(::cellRecord),
            errors = snapshot.errors,
        )
    }

    private fun cellRecord(cell: CellSummary): CellRecord {
        return when (cell) {
            is NrCellSummary -> CellRecord(
                radio = cell.radio,
                registered = cell.registered,
                mcc = cell.mcc,
                mnc = cell.mnc,
                nci = cell.nci,
                pci = cell.pci,
                tac = cell.tac,
                nrarfcn = cell.nrarfcn,
                bands = cell.bands,
                ssRsrp = cell.ssRsrp,
                ssRsrq = cell.ssRsrq,
                ssSinr = cell.ssSinr,
            )
            is LteCellSummary -> CellRecord(
                radio = cell.radio,
                registered = cell.registered,
                mcc = cell.mcc,
                mnc = cell.mnc,
                ci = cell.ci,
                pci = cell.pci,
                tac = cell.tac,
                earfcn = cell.earfcn,
                rsrp = cell.rsrp,
                rsrq = cell.rsrq,
            )
            is GenericCellSummary -> CellRecord(
                radio = cell.radio,
                registered = cell.registered,
                rawDescription = cell.description,
            )
        }
    }
}
