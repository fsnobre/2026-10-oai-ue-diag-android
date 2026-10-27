import json
import tempfile
import unittest
from pathlib import Path

from tools.oai_ue_diag_report import (
    extract_phone_observations,
    extract_oai_settings,
    generate_report,
    parse_jsonl,
    scan_logs,
)


class ReportGeneratorTest(unittest.TestCase):
    def test_jsonl_parser_reads_valid_events_and_warns_on_malformed_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "events.jsonl"
            path.write_text(
                "\n".join(
                    [
                        json.dumps(sample_event()),
                        "",
                        "{not-json",
                        json.dumps(sample_event(event_type="manual_marker", label="Attach attempt started")),
                    ]
                ),
                encoding="utf-8",
            )

            parsed = parse_jsonl(path)

        self.assertEqual(2, len(parsed.events))
        self.assertEqual(1, len(parsed.warnings))
        self.assertIn("malformed JSON", parsed.warnings[0])

    def test_phone_milestone_extraction(self):
        events = [
            sample_event(
                display_override="NR_NSA",
                cells=[
                    {
                        "radio": "NR",
                        "registered": False,
                        "mcc": "001",
                        "mnc": "01",
                        "pci": 10,
                        "tac": 7,
                        "nrarfcn": 641280,
                        "ssRsrp": -95,
                    }
                ],
            ),
            sample_event(
                service_state="IN_SERVICE",
                data_state="CONNECTED",
                cells=[
                    {
                        "radio": "NR",
                        "registered": True,
                        "pci": 10,
                        "tac": 7,
                        "nrarfcn": 641280,
                        "ssRsrp": -82,
                    }
                ],
            ),
        ]

        observations = extract_phone_observations(events)

        self.assertIsNotNone(observations["first_nr_cell"])
        self.assertIsNotNone(observations["first_registered_nr_cell"])
        self.assertTrue(observations["service_in_service"])
        self.assertTrue(observations["data_connected"])
        self.assertEqual(["NR_NSA"], observations["display_overrides"])
        self.assertEqual(-82, observations["best_ss_rsrp"])
        self.assertEqual(-95, observations["worst_ss_rsrp"])

    def test_log_scanner_detects_amf_registration_and_smf_pdu_session(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            (run_dir / "amf.log").write_text("2026-05-13T12:00:00Z Registration Request from UE\n", encoding="utf-8")
            (run_dir / "smf.log").write_text("2026-05-13T12:00:01Z PDU Session Establishment Accept\n", encoding="utf-8")

            matches = scan_logs([run_dir / "amf.log", run_dir / "smf.log"])

        categories = {match.category for match in matches}
        self.assertIn("AMF registration", categories)
        self.assertIn("PDU session", categories)

    def test_report_generator_creates_markdown_when_oai_logs_are_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            jsonl = tmp_path / "events.jsonl"
            run_dir = tmp_path / "missing-run"
            out = tmp_path / "report.md"
            jsonl.write_text(json.dumps(sample_event(cells=[{"radio": "LTE", "registered": True}])) + "\n", encoding="utf-8")

            markdown, warnings = generate_report(jsonl, run_dir, out)

            self.assertTrue(out.exists())
            self.assertIn("No OAI/ADB log files were available", markdown)
            self.assertIn("Missing OAI logs limit conclusions", markdown)
        self.assertTrue(warnings)

    def test_report_displays_embedded_oai_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            jsonl = tmp_path / "events.jsonl"
            run_dir = tmp_path / "run"
            run_dir.mkdir()
            out = tmp_path / "report.md"
            jsonl.write_text(
                json.dumps(
                    sample_event(
                        oai_settings={
                            "mcc": "001",
                            "mnc": "01",
                            "tac": "7",
                            "nrarfcn": "641280",
                            "pci": "10",
                            "dnnApnLabel": "oai",
                            "sst": "1",
                            "sd": "000001",
                            "notes": "lab",
                        }
                    )
                )
                + "\n",
                encoding="utf-8",
            )

            markdown, _ = generate_report(jsonl, run_dir, out)

        self.assertIn("Embedded OAI expected settings were found", markdown)
        self.assertIn("- MCC: 001", markdown)
        self.assertIn("- DNN/APN label: oai", markdown)
        self.assertIn("diagnostic metadata only", markdown)

    def test_extract_oai_settings_handles_absent_older_jsonl(self):
        settings = extract_oai_settings([sample_event()])

        self.assertIsNone(settings["settings"])
        self.assertFalse(settings["multiple_distinct"])

    def test_report_does_not_invent_conclusions_when_evidence_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            jsonl = tmp_path / "events.jsonl"
            run_dir = tmp_path / "run"
            run_dir.mkdir()
            out = tmp_path / "report.md"
            jsonl.write_text(json.dumps(sample_event(cells=[])) + "\n", encoding="utf-8")

            markdown, _ = generate_report(jsonl, run_dir, out)

        self.assertIn("cannot distinguish RF/cell visibility from Android/OEM redaction", markdown)
        self.assertNotIn("successful data path", markdown)


def sample_event(
    event_type="snapshot",
    label="snapshot",
    service_state=None,
    data_state=None,
    display_override=None,
    cells=None,
    oai_settings=None,
):
    event = {
        "sessionId": "session-1",
        "elapsedRealtimeNanos": 1,
        "wallClockIso": "2026-05-13T12:00:00Z",
        "eventType": event_type,
        "label": label,
        "serviceState": service_state,
        "dataState": data_state,
        "displayNetworkOverride": display_override,
        "latestTelemetry": {
            "timestampIso": "2026-05-13T12:00:00Z",
            "device": {
                "manufacturer": "Test",
                "model": "Phone",
                "androidVersion": "16",
                "sdk": 36,
                "buildFingerprint": "test/fingerprint",
            },
            "subscriptions": [
                {
                    "subscriptionId": 1,
                    "simSlotIndex": 0,
                    "carrierName": "TestCarrier",
                    "mcc": "001",
                    "mnc": "01",
                }
            ],
            "network": {
                "dataNetworkType": "NR",
                "voiceNetworkType": "LTE",
                "isRoaming": False,
                "simState": "Ready",
            },
            "cells": cells if cells is not None else [],
            "errors": [],
        },
    }
    if oai_settings is not None:
        event["oaiSettings"] = oai_settings
    return event


if __name__ == "__main__":
    unittest.main()
