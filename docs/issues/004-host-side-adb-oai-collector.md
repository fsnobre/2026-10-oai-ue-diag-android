# Issue 004 — Add host-side ADB/OAI collector script

## Goal

Create a host-side Python companion tool that collects Android ADB diagnostics and OAI Docker logs during a UE attach attempt.

This script complements the Android app. It runs on the Linux workstation/laptop.

## File

Create:

```text
tools/oai_ue_diag_collect.py
```

## CLI

Arguments:

--out DIR required
--phone-label LABEL optional
--duration SEC default 120
--serial SERIAL optional ADB serial
--containers default oai-gnb,oai-amf,oai-smf,oai-upf
--interface default demo-oai
--pcap boolean
--bugreport boolean
--no-docker boolean
--no-adb boolean

## ADB collection

Collect and save:

```shell
adb devices
adb shell getprop
adb shell dumpsys telephony.registry
adb shell dumpsys phone
adb shell dumpsys connectivity
adb shell dumpsys subscription
adb shell dumpsys carrier_config
adb logcat -b radio -d
```

If `--bugreport` is enabled:
```shell
adb bugreport OUT/bugreport.zip
```

If `--serial` is provided, use `adb -s SERIAL`.

## OAI collection

Collect docker logs for configured containers:
```bash
docker logs oai-gnb
docker logs oai-amf
docker logs oai-smf
docker logs oai-upf
```

Do not fail the entire script if one container is missing; record the error.

## PCAP

If `--pcap` is enabled, run for the requested duration:
```shell
sudo tshark -i INTERFACE -f "not arp and not port 53" -w OUT/core.pcap
```

The script must explain that pcap capture may require sudo.

### Manifest

Create manifest.json with:

Start time
Stop time
Phone label
ADB serial
Commands executed
Files generated
Errors

### Grep summary

Create summary.txt with grep-style hints for:

RACH
RRCSetup
InitialUEMessage
Registration Request
Authentication
Security Mode
Registration Accept
PDU Session
DNN
NSSAI
S-NSSAI
Reject
Cause
IMS

### Acceptance criteria
Script runs with python3 tools/oai_ue_diag_collect.py --out ./runs/test --duration 30
Output directory is created.
Manifest is created.
Missing containers do not crash the script.
README documents usage.
