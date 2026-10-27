package br.dev.nobre.oaiuediag.diagnosis

import br.dev.nobre.oaiuediag.data.CellRecord
import br.dev.nobre.oaiuediag.data.DeviceRecord
import br.dev.nobre.oaiuediag.data.NetworkRecord
import br.dev.nobre.oaiuediag.data.OaiSettings
import br.dev.nobre.oaiuediag.data.TelemetryEvent
import br.dev.nobre.oaiuediag.data.TelemetrySnapshotRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class OaiDiagnosisEngineTest {
    @Test
    fun reportsIdleWhenNoRecorderEventsExist() {
        val result = OaiDiagnosisEngine.diagnose(
            events = emptyList(),
            settings = OaiSettings(),
        )

        assertEquals(
            "No recorder session available yet. Start a recording during an attach attempt to generate diagnosis hints.",
            result.verdict,
        )
    }

    @Test
    fun reportsNoNrWhenOnlyLteIsObserved() {
        val result = OaiDiagnosisEngine.diagnose(
            events = listOf(event(cells = listOf(CellRecord(radio = "LTE", registered = true)))),
            settings = OaiSettings(),
        )

        assertEquals(
            "No NR cell visible to Android. Check RF, band, SSB/frequency, phone scan policy, app permissions, or whether Android is only exposing LTE/NSA state.",
            result.verdict,
        )
    }

    @Test
    fun reportsExpectedCellNotObservedWhenNrDoesNotMatchSettings() {
        val result = OaiDiagnosisEngine.diagnose(
            events = listOf(
                event(
                    cells = listOf(
                        CellRecord(
                            radio = "NR",
                            registered = false,
                            mcc = "999",
                            mnc = "99",
                            tac = 1,
                            nrarfcn = 100,
                            pci = 2,
                        ),
                    ),
                ),
            ),
            settings = OaiSettings(mcc = "001", mnc = "01", tac = "7", nrarfcn = "641280", pci = "10"),
        )

        assertEquals("NR is visible, but the expected OAI cell was not observed.", result.verdict)
    }

    @Test
    fun reportsExpectedCellVisibleButNotRegistered() {
        val result = OaiDiagnosisEngine.diagnose(
            events = listOf(
                event(
                    cells = listOf(
                        CellRecord(
                            radio = "NR",
                            registered = false,
                            mcc = "001",
                            mnc = "01",
                            tac = 7,
                            nrarfcn = 641280,
                            pci = 10,
                        ),
                    ),
                ),
            ),
            settings = OaiSettings(mcc = "001", mnc = "01", tac = "7", nrarfcn = "641280", pci = "10"),
        )

        assertEquals(
            "OAI cell appears visible, but Android did not report registration/camping. Suspect PLMN acceptance, cell selection, SIM policy, or modem/OEM policy.",
            result.verdict,
        )
    }

    @Test
    fun reportsRegisteredNrWithoutService() {
        val result = OaiDiagnosisEngine.diagnose(
            events = listOf(event(cells = listOf(CellRecord(radio = "NR", registered = true)))),
            settings = OaiSettings(),
        )

        assertEquals(
            "Phone appears to reach an NR cell, but Android did not report service. Compare with gNB/AMF logs.",
            result.verdict,
        )
    }

    @Test
    fun reportsServiceWithoutData() {
        val result = OaiDiagnosisEngine.diagnose(
            events = listOf(
                event(
                    serviceState = "IN_SERVICE",
                    cells = listOf(CellRecord(radio = "NR", registered = true)),
                ),
            ),
            settings = OaiSettings(),
        )

        assertEquals(
            "Registration may have succeeded, but PDU/data session did not. Check APN/DNN, S-NSSAI/slice, SMF/UPF, and IMS behavior.",
            result.verdict,
        )
    }

    @Test
    fun reportsDataConnected() {
        val result = OaiDiagnosisEngine.diagnose(
            events = listOf(
                event(
                    serviceState = "IN_SERVICE",
                    dataState = "CONNECTED",
                    cells = listOf(CellRecord(radio = "NR", registered = true)),
                ),
            ),
            settings = OaiSettings(),
        )

        assertEquals(
            "Phone-side Android telemetry indicates successful registration/data. Validate user plane with ping/iperf and OAI UPF logs.",
            result.verdict,
        )
    }

    private fun event(
        cells: List<CellRecord>,
        serviceState: String? = null,
        dataState: String? = null,
    ): TelemetryEvent {
        return TelemetryEvent(
            sessionId = "session",
            elapsedRealtimeNanos = 1L,
            wallClockIso = "2026-05-13T00:00:00Z",
            eventType = "snapshot",
            label = "snapshot",
            serviceState = serviceState,
            dataState = dataState,
            latestTelemetry = TelemetrySnapshotRecord(
                timestampIso = "2026-05-13T00:00:00Z",
                device = DeviceRecord(
                    manufacturer = "Test",
                    model = "Phone",
                    androidVersion = "16",
                    sdk = 36,
                    buildFingerprint = "test",
                ),
                subscriptions = emptyList(),
                network = NetworkRecord(
                    dataNetworkType = "NR",
                    voiceNetworkType = "LTE",
                    isRoaming = false,
                    simState = "Ready",
                ),
                cells = cells,
            ),
        )
    }
}
