#!/usr/bin/env python3
"""Generate Markdown reports from OAI UE Diag JSONL and host-side run logs."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable, Sequence


LOG_MILESTONES: list[tuple[str, list[str]]] = [
    ("gNB RACH/RRC", ["RACH", "RA-", "RRCSetup", "RRCSetupComplete"]),
    ("NGAP / Initial UE", ["NGAP", "InitialUEMessage", "Initial UE"]),
    ("AMF registration", ["Registration Request", "Registration Accept", "Registration Reject"]),
    ("Authentication/security", ["Authentication", "Security Mode", "Security Mode Complete", "5G_AKA", "MAC failure", "Sync failure"]),
    ("PDU session", ["PDU Session", "PDU Session Establishment", "PDU Session Reject", "PDU Session Accept"]),
    ("DNN/slice", ["DNN", "APN", "NSSAI", "S-NSSAI", "SNSSAI", "SST", "SD"]),
    ("Core/user plane", ["PFCP", "GTP", "N3", "UPF", "SMF"]),
    ("IMS", ["IMS", "P-CSCF", "PCSCF"]),
    ("Failure hints", ["Reject", "Cause", "failure", "failed", "error"]),
]
EXPECTED_FILES = [
    "gnb.log",
    "amf.log",
    "smf.log",
    "upf.log",
    "manifest.json",
    "summary.txt",
    "core.pcap",
    "adb-devices.txt",
    "logcat-radio.txt",
]


@dataclass
class JsonlParseResult:
    events: list[dict[str, Any]]
    warnings: list[str]


@dataclass
class LogMatch:
    category: str
    pattern: str
    file: str
    line: int
    text: str
    timestamp: str | None = None


@dataclass
class TimelineItem:
    time: str
    source: str
    event: str
    evidence: str
    sort_key: str | None = None


def parse_jsonl(path: Path) -> JsonlParseResult:
    warnings: list[str] = []
    events: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                value = json.loads(stripped)
            except json.JSONDecodeError as exc:
                warnings.append(f"{path}:{line_number}: malformed JSON: {exc.msg}")
                continue
            if isinstance(value, dict):
                events.append(value)
            else:
                warnings.append(f"{path}:{line_number}: JSON value is not an object")
    return JsonlParseResult(events=events, warnings=warnings)


def extract_oai_settings(events: Sequence[dict[str, Any]]) -> dict[str, Any]:
    settings_objects = [
        event.get("oaiSettings")
        for event in events
        if isinstance(event.get("oaiSettings"), dict) and is_non_empty_settings(event["oaiSettings"])
    ]
    first = settings_objects[0] if settings_objects else None
    distinct = {canonical_settings(settings) for settings in settings_objects}
    return {
        "settings": first,
        "count": len(settings_objects),
        "multiple_distinct": len(distinct) > 1,
    }


def is_non_empty_settings(settings: dict[str, Any]) -> bool:
    return any(str(settings.get(key) or "").strip() for key in OAI_SETTINGS_KEYS)


def canonical_settings(settings: dict[str, Any]) -> str:
    return json.dumps({key: settings.get(key) or "" for key in OAI_SETTINGS_KEYS}, sort_keys=True)


def extract_phone_observations(events: Sequence[dict[str, Any]]) -> dict[str, Any]:
    all_cells = [cell for event in events for cell in latest_cells(event)]
    nr_cells = [cell for cell in all_cells if str(cell.get("radio", "")).upper() == "NR"]
    registered_nr = [cell for cell in nr_cells if bool(cell.get("registered"))]
    ss_rsrp_values = [cell.get("ssRsrp") for cell in nr_cells if isinstance(cell.get("ssRsrp"), int)]
    display_overrides = sorted(
        {
            event.get("displayNetworkOverride")
            for event in events
            if event.get("displayNetworkOverride")
        }
    )
    manual_markers = [
        {
            "time": event.get("wallClockIso") or "",
            "label": event.get("label") or "",
        }
        for event in events
        if event.get("eventType") == "manual_marker"
    ]

    first_event = events[0] if events else {}
    last_event = events[-1] if events else {}
    latest = last_event.get("latestTelemetry") or first_event.get("latestTelemetry") or {}
    device = latest.get("device") or {}
    subscriptions = latest.get("subscriptions") or []

    return {
        "first_timestamp": first_event.get("wallClockIso"),
        "last_timestamp": last_event.get("wallClockIso"),
        "session_ids": sorted({event.get("sessionId") for event in events if event.get("sessionId")}),
        "device": device,
        "subscriptions": subscriptions,
        "event_counts": Counter(event.get("eventType") or "unknown" for event in events),
        "manual_markers": manual_markers,
        "first_nr_cell": nr_cells[0] if nr_cells else None,
        "first_registered_nr_cell": registered_nr[0] if registered_nr else None,
        "best_ss_rsrp": max(ss_rsrp_values) if ss_rsrp_values else None,
        "worst_ss_rsrp": min(ss_rsrp_values) if ss_rsrp_values else None,
        "service_in_service": any(event.get("serviceState") == "IN_SERVICE" for event in events),
        "data_connected": any(event.get("dataState") == "CONNECTED" for event in events),
        "display_overrides": display_overrides,
        "no_nr_cells": not nr_cells,
        "only_non_nr_cells": bool(all_cells) and not nr_cells,
        "cell_radios": sorted({str(cell.get("radio") or "UNKNOWN") for cell in all_cells}),
    }


def latest_cells(event: dict[str, Any]) -> list[dict[str, Any]]:
    latest = event.get("latestTelemetry") or {}
    cells = latest.get("cells") or []
    return [cell for cell in cells if isinstance(cell, dict)]


def discover_log_files(run_dir: Path) -> tuple[list[Path], list[str], list[str]]:
    found: list[Path] = []
    missing = [name for name in EXPECTED_FILES if not (run_dir / name).exists()]
    if run_dir.exists():
        for pattern in ["gnb.log", "amf.log", "smf.log", "upf.log", "docker-*.log", "logcat-radio.txt", "summary.txt"]:
            found.extend(sorted(run_dir.glob(pattern)))
    unique_found = sorted(set(found))
    return unique_found, missing, [] if run_dir.exists() else [f"run directory does not exist: {run_dir}"]


def scan_logs(files: Iterable[Path], per_category_limit: int = 40) -> list[LogMatch]:
    matches: list[LogMatch] = []
    counts: Counter[str] = Counter()
    compiled = [
        (category, [(pattern, re.compile(re.escape(pattern), re.IGNORECASE)) for pattern in patterns])
        for category, patterns in LOG_MILESTONES
    ]

    for path in files:
        try:
            with path.open("r", encoding="utf-8", errors="replace") as handle:
                for line_number, line in enumerate(handle, start=1):
                    text = line.rstrip()
                    for category, patterns in compiled:
                        if counts[category] >= per_category_limit:
                            continue
                        for pattern, regex in patterns:
                            if regex.search(text):
                                matches.append(
                                    LogMatch(
                                        category=category,
                                        pattern=pattern,
                                        file=path.name,
                                        line=line_number,
                                        text=text,
                                        timestamp=parse_log_timestamp(text),
                                    )
                                )
                                counts[category] += 1
                                break
        except OSError:
            continue

    return matches


def parse_log_timestamp(line: str) -> str | None:
    iso = re.search(r"\b(20\d\d-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:\.\d+)?Z?)\b", line)
    if iso:
        return iso.group(1).replace(" ", "T")
    android = re.search(r"\b(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\b", line)
    if android:
        return android.group(1)
    return None


def build_timeline(events: Sequence[dict[str, Any]], observations: dict[str, Any], log_matches: Sequence[LogMatch]) -> list[TimelineItem]:
    items: list[TimelineItem] = []
    for marker in observations["manual_markers"]:
        items.append(TimelineItem(marker["time"] or "unknown", "Android", "Manual marker", marker["label"], marker["time"] or None))
    if observations["first_nr_cell"]:
        cell = observations["first_nr_cell"]
        items.append(TimelineItem(first_cell_time(events, "NR") or "unknown", "Android", "First NR cell", format_cell(cell), first_cell_time(events, "NR")))
    if observations["first_registered_nr_cell"]:
        cell = observations["first_registered_nr_cell"]
        items.append(
            TimelineItem(
                first_cell_time(events, "NR", registered=True) or "unknown",
                "Android",
                "First registered NR cell",
                format_cell(cell),
                first_cell_time(events, "NR", registered=True),
            )
        )
    if observations["service_in_service"]:
        event = first_event_with(events, "serviceState", "IN_SERVICE")
        items.append(TimelineItem(event.get("wallClockIso") or "unknown", "Android", "Service IN_SERVICE", event.get("label") or "callback", event.get("wallClockIso")))
    if observations["data_connected"]:
        event = first_event_with(events, "dataState", "CONNECTED")
        items.append(TimelineItem(event.get("wallClockIso") or "unknown", "Android", "Data CONNECTED", event.get("label") or "callback", event.get("wallClockIso")))
    for match in log_matches:
        source = infer_source(match.file)
        items.append(
            TimelineItem(
                match.timestamp or "unparsed",
                source,
                match.category,
                f"{match.file}:{match.line} {truncate(match.text, 160)}",
                match.timestamp,
            )
        )
    return sorted(items, key=lambda item: (item.sort_key is None, item.sort_key or "", item.source, item.event))


def first_cell_time(events: Sequence[dict[str, Any]], radio: str, registered: bool | None = None) -> str | None:
    for event in events:
        for cell in latest_cells(event):
            if str(cell.get("radio", "")).upper() != radio.upper():
                continue
            if registered is not None and bool(cell.get("registered")) != registered:
                continue
            return event.get("wallClockIso")
    return None


def first_event_with(events: Sequence[dict[str, Any]], key: str, value: str) -> dict[str, Any]:
    return next((event for event in events if event.get(key) == value), {})


def infer_source(filename: str) -> str:
    lower = filename.lower()
    if "gnb" in lower:
        return "gNB"
    if "amf" in lower:
        return "AMF"
    if "smf" in lower:
        return "SMF"
    if "upf" in lower:
        return "UPF"
    if "logcat" in lower or "adb" in lower or "dumpsys" in lower:
        return "ADB"
    return "OAI"


def diagnosis_hints(observations: dict[str, Any], log_matches: Sequence[LogMatch]) -> list[str]:
    categories = {match.category for match in log_matches}
    texts = "\n".join(match.text for match in log_matches).lower()
    hints: list[str] = []
    has_oai_logs = any(infer_source(match.file) in {"gNB", "AMF", "SMF", "UPF", "OAI"} for match in log_matches)

    if observations["no_nr_cells"] and not has_oai_logs:
        hints.append("Phone-side recording did not expose NR CellInfo records, and no OAI logs were available. This cannot distinguish RF/cell visibility from Android/OEM redaction.")
    if observations["first_nr_cell"] and not observations["first_registered_nr_cell"]:
        hints.append("Android saw NR cell records but did not report a registered NR cell. Compare with gNB RACH/RRC evidence.")
    if "gNB RACH/RRC" in categories and "AMF registration" not in categories:
        hints.append("Failure region may be between RRC completion and NAS reaching AMF / NGAP path, but verify timestamps and full logs.")
    if "registration request" in texts and ("authentication" in texts or "mac failure" in texts or "sync failure" in texts):
        hints.append("AMF registration/authentication evidence is present. If failures are shown, verify SIM/subscriber/auth profile such as K/OPc/SQN/SUCI.")
    if "registration accept" in texts and "pdu session reject" in texts:
        hints.append("Registration likely succeeded, but data session failed. Check DNN/APN, S-NSSAI, PDU type, SMF/UPF, and IMS behavior.")
    if ("pdu session" in texts or "pfcp" in texts or "gtp" in texts) and observations["data_connected"]:
        hints.append("Phone-side and core-side evidence both suggest successful data path; validate with ping/iperf.")
    if not hints:
        hints.append("Evidence is insufficient for a specific diagnosis hint. Review phone milestones and OAI logs together.")
    return hints


def generate_report(android_jsonl: Path, run_dir: Path, out_path: Path) -> tuple[str, list[str]]:
    parsed = parse_jsonl(android_jsonl)
    observations = extract_phone_observations(parsed.events)
    oai_settings = extract_oai_settings(parsed.events)
    log_files, missing_files, run_warnings = discover_log_files(run_dir)
    log_matches = scan_logs(log_files)
    timeline = build_timeline(parsed.events, observations, log_matches)
    warnings = [*parsed.warnings, *run_warnings]
    markdown = render_markdown(android_jsonl, run_dir, observations, oai_settings, parsed.events, log_files, missing_files, log_matches, timeline, warnings)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(markdown, encoding="utf-8")
    return markdown, warnings


def render_markdown(
    android_jsonl: Path,
    run_dir: Path,
    observations: dict[str, Any],
    oai_settings: dict[str, Any],
    events: Sequence[dict[str, Any]],
    log_files: Sequence[Path],
    missing_files: Sequence[str],
    log_matches: Sequence[LogMatch],
    timeline: Sequence[TimelineItem],
    warnings: Sequence[str],
) -> str:
    lines: list[str] = ["# OAI UE Diag Report", ""]
    lines.extend(section_run_metadata(android_jsonl, run_dir, observations, warnings))
    lines.extend(section_device_session(observations))
    lines.extend(section_expected_settings(oai_settings))
    lines.extend(section_timeline(timeline))
    lines.extend(section_phone_observations(observations))
    lines.extend(section_oai_observations(log_matches, log_files))
    lines.extend(section_diagnosis(diagnosis_hints(observations, log_matches)))
    lines.extend(section_unknowns(missing_files, run_dir))
    lines.extend(section_next_tests(observations, log_matches))
    lines.extend(section_files(android_jsonl, run_dir, log_files, missing_files))
    return "\n".join(lines) + "\n"


def section_run_metadata(android_jsonl: Path, run_dir: Path, observations: dict[str, Any], warnings: Sequence[str]) -> list[str]:
    lines = [
        "## Title and Run Metadata",
        "",
        f"- Android JSONL: `{android_jsonl}`",
        f"- Run directory: `{run_dir}`",
        f"- First Android event: {observations['first_timestamp'] or 'missing'}",
        f"- Last Android event: {observations['last_timestamp'] or 'missing'}",
        f"- Session IDs: {', '.join(observations['session_ids']) or 'missing'}",
        "",
    ]
    if warnings:
        lines.append("Warnings:")
        lines.extend(f"- {warning}" for warning in warnings)
        lines.append("")
    return lines


def section_device_session(observations: dict[str, Any]) -> list[str]:
    device = observations["device"]
    lines = [
        "## Device / Session Summary",
        "",
        f"- Device: {device.get('manufacturer', 'missing')} {device.get('model', 'missing')}",
        f"- Android: {device.get('androidVersion', 'missing')} / SDK {device.get('sdk', 'missing')}",
        f"- Build fingerprint: {device.get('buildFingerprint', 'missing')}",
        "- Event counts:",
    ]
    for event_type, count in sorted(observations["event_counts"].items()):
        lines.append(f"  - {event_type}: {count}")
    lines.append("- Subscriptions:")
    subscriptions = observations["subscriptions"]
    if subscriptions:
        for subscription in subscriptions:
            lines.append(
                f"  - slot={subscription.get('simSlotIndex', 'missing')} carrier={subscription.get('carrierName') or subscription.get('displayName') or 'missing'} mcc={subscription.get('mcc') or 'missing'} mnc={subscription.get('mnc') or 'missing'}"
            )
    else:
        lines.append("  - missing")
    lines.append("")
    return lines


OAI_SETTINGS_KEYS = ["mcc", "mnc", "tac", "nrarfcn", "pci", "dnnApnLabel", "sst", "sd", "notes"]


def section_expected_settings(oai_settings: dict[str, Any]) -> list[str]:
    settings = oai_settings.get("settings")
    lines = ["## Expected OAI Settings", ""]
    if not settings:
        lines.extend(
            [
                "Expected OAI settings were not embedded in the JSONL schema analyzed by this report. Use the app settings and report notes as external context if needed.",
                "",
            ]
        )
        return lines

    lines.append("Embedded OAI expected settings were found in the Android JSONL and are shown for correlation only.")
    for key in OAI_SETTINGS_KEYS:
        label = {
            "dnnApnLabel": "DNN/APN label",
            "nrarfcn": "NRARFCN",
            "mcc": "MCC",
            "mnc": "MNC",
            "tac": "TAC",
            "pci": "PCI",
            "sst": "SST",
            "sd": "SD",
            "notes": "Notes",
        }[key]
        lines.append(f"- {label}: {settings.get(key) or 'not set'}")
    if oai_settings.get("multiple_distinct"):
        lines.append("- Multiple different OAI settings objects were observed; this report shows the first non-empty one.")
    elif oai_settings.get("count", 0) > 1:
        lines.append(f"- Same OAI settings object appeared in {oai_settings['count']} events.")
    lines.extend(
        [
            "- These values are diagnostic metadata only; they do not configure the phone, modem, PLMN, APN, slice, or IMS.",
            "",
        ]
    )
    return lines


def section_timeline(timeline: Sequence[TimelineItem]) -> list[str]:
    lines = ["## Timeline", "", "| Time | Source | Event | Evidence |", "| --- | --- | --- | --- |"]
    if timeline:
        for item in timeline[:120]:
            lines.append(f"| {escape_md(item.time)} | {escape_md(item.source)} | {escape_md(item.event)} | {escape_md(item.evidence)} |")
    else:
        lines.append("| missing | missing | No timeline evidence | No Android milestones or OAI log matches found |")
    lines.append("")
    return lines


def section_phone_observations(observations: dict[str, Any]) -> list[str]:
    lines = [
        "## Phone-Side Observations",
        "",
        f"- Manual markers: {len(observations['manual_markers'])}",
    ]
    for marker in observations["manual_markers"]:
        lines.append(f"  - {marker['time'] or 'unknown'}: {marker['label']}")
    lines.extend(
        [
            f"- First NR cell observed: {format_cell(observations['first_nr_cell']) if observations['first_nr_cell'] else 'no'}",
            f"- First registered NR cell observed: {format_cell(observations['first_registered_nr_cell']) if observations['first_registered_nr_cell'] else 'no'}",
            f"- Best SS-RSRP: {observations['best_ss_rsrp'] if observations['best_ss_rsrp'] is not None else 'missing'}",
            f"- Worst SS-RSRP: {observations['worst_ss_rsrp'] if observations['worst_ss_rsrp'] is not None else 'missing'}",
            f"- Service IN_SERVICE observed: {yes_no(observations['service_in_service'])}",
            f"- Data CONNECTED observed: {yes_no(observations['data_connected'])}",
            f"- Display network overrides: {', '.join(observations['display_overrides']) or 'missing'}",
            f"- Cell radios observed: {', '.join(observations['cell_radios']) or 'missing'}",
        ]
    )
    if observations["no_nr_cells"]:
        lines.append("- No NR CellRecord was present.")
    if observations["only_non_nr_cells"]:
        lines.append("- Only non-NR cell records were present.")
    lines.append("")
    return lines


def section_oai_observations(log_matches: Sequence[LogMatch], log_files: Sequence[Path]) -> list[str]:
    lines = ["## OAI-Side Observations", ""]
    if not log_files:
        lines.append("No OAI/ADB log files were available in the run directory.")
    elif not log_matches:
        lines.append("Log files were present, but no configured OAI milestone patterns were found.")
    else:
        by_category: dict[str, list[LogMatch]] = {}
        for match in log_matches:
            by_category.setdefault(match.category, []).append(match)
        for category, matches in by_category.items():
            lines.append(f"### {category}")
            for match in matches[:20]:
                timestamp = f" [{match.timestamp}]" if match.timestamp else ""
                lines.append(f"- `{match.file}:{match.line}`{timestamp} {truncate(match.text, 220)}")
            lines.append("")
    lines.append("")
    return lines


def section_diagnosis(hints: Sequence[str]) -> list[str]:
    return ["## Diagnosis Hints", "", *[f"- {hint}" for hint in hints], ""]


def section_unknowns(missing_files: Sequence[str], run_dir: Path) -> list[str]:
    lines = [
        "## Unknowns / Limitations",
        "",
        "- Android public APIs cannot directly expose NAS/RRC reject causes.",
        "- Android may redact or omit CellInfoNr depending on OEM, Android version, permissions, and radio state.",
        "- The report cannot prove slice/DNN/IMS status from phone JSONL alone.",
        "- Missing OAI logs limit conclusions.",
        "- PCAP presence is noted but this script does not deeply decode PCAP.",
    ]
    if not run_dir.exists():
        lines.append(f"- Run directory `{run_dir}` was missing.")
    if missing_files:
        lines.append(f"- Missing expected run files: {', '.join(missing_files)}")
    lines.append("")
    return lines


def section_next_tests(observations: dict[str, Any], log_matches: Sequence[LogMatch]) -> list[str]:
    lines = ["## Recommended Next Tests", ""]
    if observations["no_nr_cells"]:
        lines.append("- Confirm RF/frequency/SSB and Android permissions; compare with gNB RACH/RRC logs.")
    if observations["first_nr_cell"] and not observations["first_registered_nr_cell"]:
        lines.append("- Repeat attach with synchronized Android recording and gNB/AMF logs to localize registration progress.")
    if not log_matches:
        lines.append("- Collect OAI gNB/AMF/SMF/UPF logs with the Issue 004 host collector.")
    if observations["data_connected"]:
        lines.append("- Validate user plane with ping/iperf and inspect UPF counters/logs.")
    if len(lines) == 2:
        lines.append("- Review full logs around manual attach markers and compare wall-clock timestamps.")
    lines.append("")
    return lines


def section_files(android_jsonl: Path, run_dir: Path, log_files: Sequence[Path], missing_files: Sequence[str]) -> list[str]:
    lines = [
        "## Files Analyzed",
        "",
        f"- Android JSONL path: `{android_jsonl}`",
        f"- Run directory path: `{run_dir}`",
        "- Found run files:",
    ]
    if log_files:
        lines.extend(f"  - `{path.name}`" for path in log_files)
    else:
        lines.append("  - none")
    lines.append("- Missing expected files:")
    if missing_files:
        lines.extend(f"  - `{name}`" for name in missing_files)
    else:
        lines.append("  - none")
    lines.append("")
    return lines


def format_cell(cell: dict[str, Any] | None) -> str:
    if not cell:
        return "missing"
    parts = [
        f"radio={cell.get('radio', 'missing')}",
        f"registered={cell.get('registered', 'missing')}",
        f"mcc={cell.get('mcc', 'missing')}",
        f"mnc={cell.get('mnc', 'missing')}",
        f"pci={cell.get('pci', 'missing')}",
        f"tac={cell.get('tac', 'missing')}",
        f"nrarfcn={cell.get('nrarfcn', 'missing')}",
        f"rsrp={cell.get('ssRsrp', cell.get('rsrp', 'missing'))}",
    ]
    return ", ".join(parts)


def truncate(value: str, limit: int) -> str:
    return value if len(value) <= limit else value[: limit - 3] + "..."


def escape_md(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def yes_no(value: bool) -> str:
    return "yes" if value else "no"


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate an OAI UE Diag Markdown correlation report.")
    parser.add_argument("--android-jsonl", required=True, type=Path, help="Android OAI UE Diag JSONL export")
    parser.add_argument("--run-dir", required=True, type=Path, help="Host collector run directory")
    parser.add_argument("--out", default=Path("report.md"), type=Path, help="Markdown output path")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if not args.android_jsonl.exists():
        print(f"fatal: Android JSONL file not found: {args.android_jsonl}", file=sys.stderr)
        return 2
    markdown, warnings = generate_report(args.android_jsonl, args.run_dir, args.out)
    print(f"Wrote report: {args.out}")
    if warnings:
        print(f"Completed with {len(warnings)} warning(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
