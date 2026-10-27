#!/usr/bin/env python3
"""Collect host-side Android/OAI evidence for OAI UE diagnostics."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence


DEFAULT_CONTAINERS = "oai-gnb,oai-amf,oai-smf,oai-upf"
PATTERNS = [
    "RACH",
    "RA-",
    "RRCSetup",
    "RRCSetupComplete",
    "InitialUEMessage",
    "Initial UE",
    "Registration Request",
    "Registration Accept",
    "Registration Reject",
    "Authentication",
    "Security Mode",
    "PDU Session",
    "DNN",
    "NSSAI",
    "S-NSSAI",
    "SNSSAI",
    "Reject",
    "Cause",
    "IMS",
    "PFCP",
    "GTP",
    "NGAP",
    "AMF",
    "SMF",
    "UPF",
]


@dataclass
class CommandResult:
    command: list[str]
    returncode: int | None
    stdout_file: str | None = None
    stderr: str = ""
    timed_out: bool = False
    skipped: bool = False
    started_at: str = ""
    stopped_at: str = ""

    @property
    def ok(self) -> bool:
        return not self.skipped and not self.timed_out and self.returncode == 0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sanitize_filename(value: str) -> str:
    sanitized = re.sub(r"[^A-Za-z0-9_.-]+", "-", value.strip())
    sanitized = sanitized.strip(".-")
    return sanitized or "unknown"


def docker_log_filename(container: str) -> str:
    mapping = {
        "oai-gnb": "gnb.log",
        "oai-amf": "amf.log",
        "oai-smf": "smf.log",
        "oai-upf": "upf.log",
    }
    return mapping.get(container, f"docker-{sanitize_filename(container)}.log")


def run_command(
    command: Sequence[str],
    output_path: Path | None,
    timeout: int,
) -> CommandResult:
    started = utc_now()
    executable = command[0]
    if shutil.which(executable) is None:
        return CommandResult(
            command=list(command),
            returncode=None,
            stdout_file=str(output_path) if output_path else None,
            stderr=f"missing executable: {executable}",
            skipped=True,
            started_at=started,
            stopped_at=utc_now(),
        )

    try:
        completed = subprocess.run(
            list(command),
            capture_output=True,
            text=True,
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout or ""
        stderr = exc.stderr or ""
        if output_path is not None:
            output_path.write_text(stdout, encoding="utf-8", errors="replace")
        return CommandResult(
            command=list(command),
            returncode=None,
            stdout_file=str(output_path) if output_path else None,
            stderr=str(stderr),
            timed_out=True,
            started_at=started,
            stopped_at=utc_now(),
        )

    if output_path is not None:
        output_path.write_text(completed.stdout, encoding="utf-8", errors="replace")

    return CommandResult(
        command=list(command),
        returncode=completed.returncode,
        stdout_file=str(output_path) if output_path else None,
        stderr=completed.stderr.strip(),
        started_at=started,
        stopped_at=utc_now(),
    )


def parse_adb_devices(text: str) -> list[str]:
    devices: list[str] = []
    for line in text.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def scan_log_patterns(
    files: Iterable[Path],
    patterns: Sequence[str] = PATTERNS,
    per_pattern_limit: int = 30,
) -> dict[str, list[dict[str, object]]]:
    findings: dict[str, list[dict[str, object]]] = {pattern: [] for pattern in patterns}
    lowered = [(pattern, pattern.lower()) for pattern in patterns]

    for path in files:
        if not path.exists() or not path.is_file():
            continue
        try:
            with path.open("r", encoding="utf-8", errors="replace") as handle:
                for line_number, line in enumerate(handle, start=1):
                    line_lower = line.lower()
                    for pattern, pattern_lower in lowered:
                        if len(findings[pattern]) >= per_pattern_limit:
                            continue
                        if pattern_lower in line_lower:
                            findings[pattern].append(
                                {
                                    "file": path.name,
                                    "line": line_number,
                                    "text": line.rstrip(),
                                }
                            )
        except OSError:
            continue

    return {pattern: matches for pattern, matches in findings.items() if matches}


def adb_base(serial: str | None) -> list[str]:
    if serial:
        return ["adb", "-s", serial]
    return ["adb"]


def collect_adb(
    out_dir: Path,
    serial: str | None,
    bugreport: bool,
    commands: list[CommandResult],
    files: list[str],
    errors: list[str],
) -> dict[str, object]:
    status: dict[str, object] = {
        "enabled": True,
        "devices": [],
        "selected_serial": serial,
        "device_seen": False,
        "radio_logcat_collected": False,
        "bugreport_collected": False,
    }

    devices_file = out_dir / "adb-devices.txt"
    result = run_command(["adb", "devices"], devices_file, timeout=30)
    commands.append(result)
    if result.ok:
        files.append(str(devices_file))
        devices = parse_adb_devices(devices_file.read_text(encoding="utf-8", errors="replace"))
        status["devices"] = devices
        status["device_seen"] = serial in devices if serial else bool(devices)
    else:
        errors.append(format_command_error("adb devices", result))

    base = adb_base(serial)
    adb_jobs = [
        ("getprop.txt", [*base, "shell", "getprop"], 60),
        ("dumpsys-telephony-registry.txt", [*base, "shell", "dumpsys", "telephony.registry"], 60),
        ("dumpsys-phone.txt", [*base, "shell", "dumpsys", "phone"], 60),
        ("dumpsys-connectivity.txt", [*base, "shell", "dumpsys", "connectivity"], 60),
        ("dumpsys-subscription.txt", [*base, "shell", "dumpsys", "subscription"], 60),
        ("dumpsys-carrier-config.txt", [*base, "shell", "dumpsys", "carrier_config"], 60),
        ("logcat-radio.txt", [*base, "logcat", "-b", "radio", "-d"], 120),
    ]

    for filename, command, timeout in adb_jobs:
        path = out_dir / filename
        result = run_command(command, path, timeout=timeout)
        commands.append(result)
        if result.ok:
            files.append(str(path))
            if filename == "logcat-radio.txt":
                status["radio_logcat_collected"] = True
        else:
            errors.append(format_command_error(" ".join(command), result))

    if bugreport:
        bugreport_path = out_dir / "bugreport.zip"
        result = run_command([*base, "bugreport", str(bugreport_path)], None, timeout=900)
        commands.append(result)
        if result.ok and bugreport_path.exists():
            files.append(str(bugreport_path))
            status["bugreport_collected"] = True
        else:
            errors.append(format_command_error("adb bugreport", result))

    return status


def collect_docker(
    out_dir: Path,
    containers: Sequence[str],
    commands: list[CommandResult],
    files: list[str],
    errors: list[str],
) -> dict[str, object]:
    status: dict[str, object] = {
        "enabled": True,
        "collected": [],
        "missing_or_failed": [],
    }

    for container in containers:
        filename = docker_log_filename(container)
        path = out_dir / filename
        result = run_command(["docker", "logs", container], path, timeout=120)
        commands.append(result)
        if result.ok:
            files.append(str(path))
            status["collected"].append(container)
        else:
            status["missing_or_failed"].append(container)
            errors.append(format_command_error(f"docker logs {container}", result))

    return status


def collect_pcap(
    out_dir: Path,
    interface: str,
    duration: int,
    commands: list[CommandResult],
    files: list[str],
    errors: list[str],
) -> dict[str, object]:
    print("PCAP capture uses sudo tshark and may require sudo privileges.", file=sys.stdout)
    status: dict[str, object] = {
        "enabled": True,
        "collected": False,
        "file": None,
    }

    if shutil.which("tshark") is None:
        errors.append("pcap: missing executable: tshark")
        return status
    if shutil.which("sudo") is None:
        errors.append("pcap: missing executable: sudo")
        return status

    path = out_dir / "core.pcap"
    command = [
        "sudo",
        "-n",
        "tshark",
        "-i",
        interface,
        "-f",
        "not arp and not port 53",
        "-w",
        str(path),
        "-a",
        f"duration:{duration}",
    ]
    result = run_command(command, None, timeout=duration + 30)
    commands.append(result)
    if result.ok and path.exists():
        files.append(str(path))
        status["collected"] = True
        status["file"] = str(path)
    else:
        errors.append(format_command_error("sudo tshark", result))

    return status


def format_command_error(label: str, result: CommandResult) -> str:
    if result.skipped:
        return f"{label}: skipped ({result.stderr})"
    if result.timed_out:
        return f"{label}: timed out"
    return f"{label}: exit {result.returncode}; {result.stderr}".strip()


def write_manifest(
    path: Path,
    args: argparse.Namespace,
    start_time: str,
    stop_time: str,
    commands: list[CommandResult],
    files: list[str],
    errors: list[str],
    adb_status: dict[str, object],
    docker_status: dict[str, object],
    pcap_status: dict[str, object],
) -> None:
    manifest = {
        "start_time": start_time,
        "stop_time": stop_time,
        "phone_label": args.phone_label,
        "adb_serial": args.serial,
        "duration": args.duration,
        "commands_executed": [asdict(command) for command in commands],
        "files_generated": files,
        "errors": errors,
        "adb_status": adb_status,
        "docker_status": docker_status,
        "pcap_status": pcap_status,
    }
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")


def write_summary(
    path: Path,
    args: argparse.Namespace,
    start_time: str,
    stop_time: str,
    adb_status: dict[str, object],
    docker_status: dict[str, object],
    pcap_status: dict[str, object],
    findings: dict[str, list[dict[str, object]]],
    errors: Sequence[str],
) -> None:
    lines = [
        "OAI UE Diag Host Collection Summary",
        "",
        "Run metadata",
        f"- Start time: {start_time}",
        f"- Stop time: {stop_time}",
        f"- Phone label: {args.phone_label or 'not set'}",
        f"- ADB serial: {args.serial or 'not set'}",
        f"- Duration: {args.duration}s",
        "",
        "ADB status",
        f"- Enabled: {bool(adb_status.get('enabled'))}",
        f"- Devices: {', '.join(adb_status.get('devices', [])) or 'none'}",
        f"- Selected serial: {adb_status.get('selected_serial') or 'not set'}",
        f"- Device seen: {yes_no(bool(adb_status.get('device_seen')))}",
        f"- Radio logcat collected: {yes_no(bool(adb_status.get('radio_logcat_collected')))}",
        f"- Bugreport collected: {yes_no(bool(adb_status.get('bugreport_collected')))}",
        "",
        "Docker/OAI status",
        f"- Enabled: {bool(docker_status.get('enabled'))}",
        f"- Containers collected: {', '.join(docker_status.get('collected', [])) or 'none'}",
        f"- Containers missing/failed: {', '.join(docker_status.get('missing_or_failed', [])) or 'none'}",
        "",
        "PCAP status",
        f"- Enabled: {bool(pcap_status.get('enabled'))}",
        f"- Collected: {yes_no(bool(pcap_status.get('collected')))}",
        f"- File: {pcap_status.get('file') or 'none'}",
        "",
        "Errors",
    ]
    if errors:
        lines.extend(f"- {error}" for error in errors)
    else:
        lines.append("- none")

    lines.extend(["", "Grep-style findings"])
    if findings:
        for pattern, matches in findings.items():
            lines.append("")
            lines.append(f"Pattern: {pattern}")
            for match in matches:
                lines.append(f"{match['file']}:{match['line']}: {match['text']}")
    else:
        lines.append("- no configured patterns found in collected logs")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def yes_no(value: bool) -> str:
    return "yes" if value else "no"


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Collect Android ADB diagnostics and OAI Docker logs for UE attach diagnostics.",
    )
    parser.add_argument("--out", required=True, type=Path, help="Output directory")
    parser.add_argument("--phone-label", default=None, help="Human label for the phone under test")
    parser.add_argument("--duration", default=120, type=int, help="Collection duration in seconds")
    parser.add_argument("--serial", default=None, help="ADB serial to pass with adb -s")
    parser.add_argument("--containers", default=DEFAULT_CONTAINERS, help="Comma-separated Docker container names")
    parser.add_argument("--interface", default="demo-oai", help="Interface for optional PCAP capture")
    parser.add_argument("--pcap", action="store_true", help="Capture PCAP with sudo tshark")
    parser.add_argument("--bugreport", action="store_true", help="Collect adb bugreport.zip")
    parser.add_argument("--no-docker", action="store_true", help="Skip Docker log collection")
    parser.add_argument("--no-adb", action="store_true", help="Skip ADB diagnostics")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    out_dir: Path = args.out
    out_dir.mkdir(parents=True, exist_ok=True)

    start_time = utc_now()
    commands: list[CommandResult] = []
    files: list[str] = []
    errors: list[str] = []
    adb_status: dict[str, object] = {"enabled": False}
    docker_status: dict[str, object] = {"enabled": False, "collected": [], "missing_or_failed": []}
    pcap_status: dict[str, object] = {"enabled": False, "collected": False, "file": None}

    if args.no_adb:
        adb_status = {"enabled": False, "devices": [], "selected_serial": args.serial, "device_seen": False}
    else:
        adb_status = collect_adb(out_dir, args.serial, args.bugreport, commands, files, errors)

    if not args.no_docker:
        containers = [item.strip() for item in args.containers.split(",") if item.strip()]
        docker_status = collect_docker(out_dir, containers, commands, files, errors)

    if args.pcap:
        pcap_status = collect_pcap(out_dir, args.interface, max(args.duration, 1), commands, files, errors)

    log_files = [Path(file) for file in files if Path(file).suffix in {".log", ".txt"}]
    findings = scan_log_patterns(log_files)
    stop_time = utc_now()

    manifest_path = out_dir / "manifest.json"
    summary_path = out_dir / "summary.txt"
    write_manifest(
        manifest_path,
        args,
        start_time,
        stop_time,
        commands,
        [*files, str(manifest_path), str(summary_path)],
        errors,
        adb_status,
        docker_status,
        pcap_status,
    )
    write_summary(
        summary_path,
        args,
        start_time,
        stop_time,
        adb_status,
        docker_status,
        pcap_status,
        findings,
        errors,
    )

    print(f"Wrote manifest: {manifest_path}")
    print(f"Wrote summary: {summary_path}")
    if errors:
        print(f"Completed with {len(errors)} recorded error(s). See manifest.json and summary.txt.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
