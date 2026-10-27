package br.dev.nobre.oaiuediag.recorder

import br.dev.nobre.oaiuediag.data.CellRecord
import br.dev.nobre.oaiuediag.data.DeviceRecord
import br.dev.nobre.oaiuediag.data.NetworkRecord
import br.dev.nobre.oaiuediag.data.OaiSettings
import br.dev.nobre.oaiuediag.data.SubscriptionRecord
import br.dev.nobre.oaiuediag.data.TelemetryEvent
import br.dev.nobre.oaiuediag.data.TelemetrySnapshotRecord
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEventSerializerTest {
    @Test
    fun serializesStableJsonLine() {
        val event = TelemetryEvent(
            sessionId = "session-1",
            elapsedRealtimeNanos = 123456789L,
            wallClockIso = "2026-05-13T12:00:00Z",
            eventType = "manual_marker",
            label = "Attach attempt started",
            oaiSettings = OaiSettings(
                mcc = "001",
                mnc = "01",
                tac = "7",
                nrarfcn = "641280",
                pci = "10",
                dnnApnLabel = "oai",
                sst = "1",
                sd = "000001",
                notes = "lab",
            ),
            latestTelemetry = TelemetrySnapshotRecord(
                timestampIso = "2026-05-13T12:00:00Z",
                device = DeviceRecord(
                    manufacturer = "Google",
                    model = "Pixel",
                    androidVersion = "16",
                    sdk = 36,
                    buildFingerprint = "test/fingerprint",
                ),
                subscriptions = listOf(
                    SubscriptionRecord(
                        subscriptionId = 1,
                        simSlotIndex = 0,
                        carrierName = "Test",
                        mcc = "001",
                        mnc = "01",
                    ),
                ),
                network = NetworkRecord(
                    dataNetworkType = "NR",
                    voiceNetworkType = "LTE",
                    isRoaming = false,
                    simState = "Ready",
                ),
                cells = listOf(
                    CellRecord(
                        radio = "NR",
                        registered = true,
                        mcc = "001",
                        mnc = "01",
                        nci = 42L,
                        pci = 10,
                        tac = 7,
                        nrarfcn = 641280,
                        bands = listOf(78),
                        ssRsrp = -82,
                        ssRsrq = -11,
                        ssSinr = 18,
                    ),
                ),
            ),
        )

        val jsonLine = TelemetryEventSerializer.toJsonLine(event)
        val decoded = TelemetryEventSerializer.json.decodeFromString<TelemetryEvent>(jsonLine)

        assertTrue(jsonLine.contains("\"sessionId\":\"session-1\""))
        assertTrue(jsonLine.contains("\"oaiSettings\""))
        assertEquals("001", decoded.oaiSettings?.mcc)
        assertEquals("oai", decoded.oaiSettings?.dnnApnLabel)
        assertEquals("manual_marker", decoded.eventType)
        assertEquals("Attach attempt started", decoded.label)
        assertEquals("NR", decoded.latestTelemetry.cells.single().radio)
        assertEquals(-82, decoded.latestTelemetry.cells.single().ssRsrp)
    }

    @Test
    fun deserializesOlderJsonWithoutOaiSettings() {
        val jsonLine = """
            {
              "sessionId":"session-1",
              "elapsedRealtimeNanos":123,
              "wallClockIso":"2026-05-13T12:00:00Z",
              "eventType":"snapshot",
              "label":"snapshot",
              "latestTelemetry":{
                "timestampIso":"2026-05-13T12:00:00Z",
                "device":{"manufacturer":"Google","model":"Pixel","androidVersion":"16","sdk":36,"buildFingerprint":"test"},
                "subscriptions":[],
                "network":{"dataNetworkType":"NR","voiceNetworkType":"LTE","isRoaming":false,"simState":"Ready"},
                "cells":[]
              }
            }
        """.trimIndent()

        val decoded = TelemetryEventSerializer.json.decodeFromString<TelemetryEvent>(jsonLine)

        assertEquals("snapshot", decoded.eventType)
        assertEquals(null, decoded.oaiSettings)
    }
}
