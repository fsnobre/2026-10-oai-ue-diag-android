package br.dev.nobre.oaiuediag.diagnosis

import br.dev.nobre.oaiuediag.data.CellRecord
import br.dev.nobre.oaiuediag.data.DiagnosisResult
import br.dev.nobre.oaiuediag.data.OaiSettings
import br.dev.nobre.oaiuediag.data.TelemetryEvent

object OaiDiagnosisEngine {
    private const val EMPTY_SESSION_VERDICT =
        "No recorder session available yet. Start a recording during an attach attempt to generate diagnosis hints."
    private const val NO_NR_VERDICT =
        "No NR cell visible to Android. Check RF, band, SSB/frequency, phone scan policy, app permissions, or whether Android is only exposing LTE/NSA state."
    private const val EXPECTED_NOT_OBSERVED_VERDICT =
        "NR is visible, but the expected OAI cell was not observed."
    private const val EXPECTED_NOT_REGISTERED_VERDICT =
        "OAI cell appears visible, but Android did not report registration/camping. Suspect PLMN acceptance, cell selection, SIM policy, or modem/OEM policy."
    private const val REGISTERED_NO_SERVICE_VERDICT =
        "Phone appears to reach an NR cell, but Android did not report service. Compare with gNB/AMF logs."
    private const val SERVICE_NO_DATA_VERDICT =
        "Registration may have succeeded, but PDU/data session did not. Check APN/DNN, S-NSSAI/slice, SMF/UPF, and IMS behavior."
    private const val DATA_CONNECTED_VERDICT =
        "Phone-side Android telemetry indicates successful registration/data. Validate user plane with ping/iperf and OAI UPF logs."

    fun diagnose(
        events: List<TelemetryEvent>,
        settings: OaiSettings,
    ): DiagnosisResult {
        if (events.isEmpty()) {
            return DiagnosisResult(
                verdict = EMPTY_SESSION_VERDICT,
                details = listOf("Events analyzed: 0"),
            )
        }

        val nrCells = events.flatMap { event ->
            event.latestTelemetry.cells.filter { it.radio == "NR" }
        }
        val registeredNrSeen = nrCells.any { it.registered }
        val serviceInServiceSeen = events.any { it.serviceState == "IN_SERVICE" }
        val dataConnectedSeen = events.any { it.dataState == "CONNECTED" }
        val expectedNrSeen = if (settings.hasExpectedCellCriteria) {
            nrCells.any { it.matches(settings) }
        } else {
            nrCells.isNotEmpty()
        }

        val verdict = when {
            nrCells.isEmpty() -> NO_NR_VERDICT
            settings.hasExpectedCellCriteria && !expectedNrSeen -> EXPECTED_NOT_OBSERVED_VERDICT
            !registeredNrSeen -> EXPECTED_NOT_REGISTERED_VERDICT
            !serviceInServiceSeen -> REGISTERED_NO_SERVICE_VERDICT
            !dataConnectedSeen -> SERVICE_NO_DATA_VERDICT
            else -> DATA_CONNECTED_VERDICT
        }

        return DiagnosisResult(
            verdict = verdict,
            details = buildDetails(
                settings = settings,
                events = events,
                nrCells = nrCells,
                expectedNrSeen = expectedNrSeen,
                registeredNrSeen = registeredNrSeen,
                serviceInServiceSeen = serviceInServiceSeen,
                dataConnectedSeen = dataConnectedSeen,
            ),
        )
    }

    private fun CellRecord.matches(settings: OaiSettings): Boolean {
        return matchesString(mcc, settings.mcc) &&
            matchesString(mnc, settings.mnc) &&
            matchesInt(tac, settings.tac) &&
            matchesInt(nrarfcn, settings.nrarfcn) &&
            matchesInt(pci, settings.pci)
    }

    private fun matchesString(observed: String?, expected: String): Boolean {
        return expected.isBlank() || observed.equals(expected.trim(), ignoreCase = true)
    }

    private fun matchesInt(observed: Int?, expected: String): Boolean {
        return expected.isBlank() || observed?.toString() == expected.trim()
    }

    private fun buildDetails(
        settings: OaiSettings,
        events: List<TelemetryEvent>,
        nrCells: List<CellRecord>,
        expectedNrSeen: Boolean,
        registeredNrSeen: Boolean,
        serviceInServiceSeen: Boolean,
        dataConnectedSeen: Boolean,
    ): List<String> {
        return buildList {
            add("Events analyzed: ${events.size}")
            add("NR cell records observed: ${nrCells.size}")
            if (settings.hasExpectedCellCriteria) {
                add("Expected OAI cell observed: ${expectedNrSeen.yesNo()}")
                add("Expected criteria: ${settings.expectedCriteriaLabel()}")
            } else {
                add("Expected OAI cell criteria not configured; NR registration/service/data rules use any observed NR cell.")
            }
            add("Registered NR cell observed: ${registeredNrSeen.yesNo()}")
            add("Service IN_SERVICE callback observed: ${serviceInServiceSeen.yesNo()}")
            add("Data CONNECTED callback observed: ${dataConnectedSeen.yesNo()}")
            if (settings.dnnApnLabel.isNotBlank() || settings.sst.isNotBlank() || settings.sd.isNotBlank()) {
                add("DNN/APN and slice settings are recorded for correlation only, not verified directly from Android public APIs.")
            }
        }
    }

    private fun OaiSettings.expectedCriteriaLabel(): String {
        return listOfNotNull(
            mcc.takeIf { it.isNotBlank() }?.let { "MCC=$it" },
            mnc.takeIf { it.isNotBlank() }?.let { "MNC=$it" },
            tac.takeIf { it.isNotBlank() }?.let { "TAC=$it" },
            nrarfcn.takeIf { it.isNotBlank() }?.let { "NRARFCN=$it" },
            pci.takeIf { it.isNotBlank() }?.let { "PCI=$it" },
        ).joinToString(", ")
    }

    private fun Boolean.yesNo(): String = if (this) "Yes" else "No"
}
