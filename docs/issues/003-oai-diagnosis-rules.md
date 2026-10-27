# Issue 003 — Add OAI expected-cell settings and diagnosis rules

## Goal

Add OAI-specific diagnosis hints based on recorded Android telemetry.

The app should let the user enter expected OAI cell/testbed values and compare them against observed Android telephony state.

## Settings

Add a Settings screen or dialog for expected OAI values:

- MCC
- MNC
- TAC
- NRARFCN
- PCI
- DNN/APN label
- SST
- SD
- Notes field

These settings are only for comparison and reporting. The app must not attempt to configure modem, APN, PLMN, slice, or IMS behavior.

Persist settings locally.

## Diagnosis screen

Analyze the current/latest recorded session and produce non-definitive hints.

Rules:

1. If no `CellInfoNr` appears:
   - “No NR cell visible to Android. Check RF, band, SSB/frequency, phone scan policy, or app permissions.”

2. If NR cells appear but none match expected MCC/MNC/TAC/NRARFCN:
   - “NR is visible, but the expected OAI cell was not observed.”

3. If expected NR cell appears but no registered NR cell appears:
   - “OAI cell appears visible, but Android did not report registration/camping. Suspect PLMN acceptance, cell selection, SIM policy, or modem/OEM policy.”

4. If registered NR cell appears but service never becomes IN_SERVICE:
   - “Phone appears to reach an NR cell, but Android did not report service. Compare with gNB/AMF logs.”

5. If service becomes IN_SERVICE but data never connects:
   - “Registration may have succeeded, but PDU/data session did not. Check APN/DNN, S-NSSAI/slice, SMF/UPF, and IMS behavior.”

6. If data connects:
   - “Phone-side Android telemetry indicates successful registration/data. Validate user plane with ping/iperf and OAI UPF logs.”

## Limitations text

The Diagnosis screen must clearly state:

- The app cannot directly see NAS Registration Reject causes.
- The app cannot directly see RRC reject/release causes.
- The app cannot directly see AMF/SMF reject causes.
- The app cannot guarantee slice/DNN/IMS status from Android public APIs.
- Final diagnosis requires correlation with OAI gNB/AMF/SMF/UPF logs.

## Acceptance criteria

- `./gradlew assembleDebug` succeeds.
- User can enter and persist expected OAI cell values.
- Diagnosis screen produces clear hints.
- The app never presents hints as definitive when OAI logs are required.
- README explains how to use diagnosis with OAI logs.
