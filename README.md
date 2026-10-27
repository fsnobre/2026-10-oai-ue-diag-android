# OAI UE Diag

Android diagnostics app for 5G/OAI lab work. Milestone 1 is a normal non-root Android app that collects timestamped device, subscription, network, and cell information using public Android Telephony APIs.

The app does not force NR-only mode, perform manual PLMN selection, toggle mobile data, or modify APNs.

## Build

```bash
./gradlew assembleDebug
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Milestone 1 Validation Checklist

- Run `./gradlew assembleDebug`
- Run `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Open the app
- Grant permissions
- Press `Refresh now`
- Test the `Open Phone Info` button

## Runtime Permissions

Grant phone state and fine location permissions when prompted. Android requires these permissions for subscription and cell information, and some fields may still be redacted by the device, carrier policy, Android version, or radio state.

## Lab Recording Workflow

1. Start OAI gNB/Core logs on the host.
2. Open OAI UE Diag and press `Start Recording`.
3. Toggle airplane mode off and add the `Airplane mode OFF` marker.
4. Use Android settings or the testing screen for manual PLMN/NR-only/APN lab steps, adding matching markers in the app.
5. Add `Attach attempt started`, `Attach attempt failed`, or `Attach attempt succeeded` markers as the test runs.
6. Press `Stop Recording`.
7. Press `Export / Share JSONL` and save the session file for later correlation with OAI logs.

## OAI Diagnosis Settings

Use the OAI diagnosis settings card to enter expected lab values such as MCC, MNC, TAC, NRARFCN, PCI, DNN/APN, SST, and SD. The app only compares these values against recorded Android telemetry and produces non-definitive hints.

The app does not configure PLMN, APN, NR mode, slice, IMS, or modem behavior. Final diagnosis requires correlation with OAI gNB/AMF/SMF/UPF logs.

JSONL exports include the current OAI expected settings in each recorder event so the Markdown report generator can show the lab metadata used for correlation. These values are diagnostic metadata only; they do not configure the phone, modem, network, APN, PLMN, slice, or IMS.

Do not store SIM authentication secrets in OAI settings. Never enter values such as K, OP, OPc, SQN, Ki, ADM, or other subscriber credentials.

## Host-Side ADB/OAI Collector

Issue 004 adds a Linux workstation companion script for collecting host-side evidence during a UE attach attempt. It can collect ADB dumps, radio logcat, optional bugreports, OAI Docker logs, and optional PCAP.

Basic ADB-only run:

```bash
python3 tools/oai_ue_diag_collect.py \
  --out ./runs/poco-adb-test \
  --phone-label poco-x4-gt \
  --serial <ADB_SERIAL> \
  --duration 60 \
  --no-docker
```

Full lab run:

```bash
python3 tools/oai_ue_diag_collect.py \
  --out ./runs/poco-oai-test-001 \
  --phone-label poco-x4-gt \
  --serial <ADB_SERIAL> \
  --duration 180 \
  --pcap
```

No live OAI available / dry run:

```bash
python3 tools/oai_ue_diag_collect.py \
  --out ./runs/offline-test \
  --duration 10
```

The script does not require live OAI to execute. Missing OAI containers, missing ADB devices, missing `tshark`, or missing sudo privileges are recorded in `manifest.json` and `summary.txt` instead of stopping the whole run. PCAP capture may require sudo. Bugreports may contain sensitive device and network identifiers and should not be committed to public repositories. Generated run directories should usually remain under `runs/`, which is ignored by git.

## Markdown Report Generator

Issue 005 adds a Python report generator that correlates an Android JSONL export with a host-side run directory from the collector.

Workflow:

1. Start Android recording in OAI UE Diag.
2. Run the host collector:

```bash
python3 tools/oai_ue_diag_collect.py --out ./runs/poco-oai-test-001 --phone-label poco-x4-gt --serial <ADB_SERIAL> --duration 180 --pcap
```

3. Attempt attach.
4. Export Android JSONL from the app.
5. Generate the report:

```bash
python3 tools/oai_ue_diag_report.py --android-jsonl ./path/to/export.jsonl --run-dir ./runs/poco-oai-test-001 --out ./runs/poco-oai-test-001/report.md
```

6. Review `report.md`.

The report generator can also be tested with a commercial-network Android JSONL and an incomplete or missing OAI run directory. In that case, the report identifies missing OAI evidence and avoids definitive conclusions.
