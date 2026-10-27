# Issue 002 — Add attach-attempt recorder and JSONL export

## Goal

Add a timestamped attach-attempt recorder to OAI UE Diag.

The app should record phone-side Android telephony state during an OAI attach attempt, allow manual markers, and export a JSONL log file for correlation with OAI gNB/AMF/SMF logs.

## Requirements

### Recorder UI

Add a Recorder screen or section with:

- Start Recording
- Stop Recording
- Export / Share latest session
- Clear current session
- Session summary card

### Sampling

While recording:

- Collect a `TelemetrySnapshot` every 1 second.
- Use `elapsedRealtimeNanos` for monotonic timestamp.
- Use wall-clock ISO-8601 timestamp for human correlation.
- Keep samples in memory during the session.
- Append events to a JSONL file in app-specific storage.

### Telephony callbacks

Where supported, register `TelephonyCallback` to capture changes for:

- Service state
- Signal strength
- Cell info
- Display info if available
- Data connection state if available

The app must still work on devices/API levels where some callbacks are not available.

### Manual markers

Add buttons to insert manual marker events:

- Airplane mode OFF
- Manual PLMN selected
- NR only set in hidden menu
- APN changed
- Attach attempt started
- Attach attempt failed
- Attach attempt succeeded
- Custom note

### Event format

Each JSONL event must include:

- Session ID
- Monotonic timestamp
- Wall-clock timestamp
- Event type
- Human-readable label
- Latest telemetry snapshot

### Session summary

Show:

- Session start time
- Session end time
- Number of samples
- Number of NR cells observed
- Best SS-RSRP
- Worst SS-RSRP
- Whether a registered NR cell was observed
- Whether Android reported `IN_SERVICE`
- Whether data state became connected, if available

### Export

Use Android share sheet to share the JSONL file.

## Acceptance criteria

- `./gradlew assembleDebug` succeeds.
- Start/stop recording works.
- JSONL file is created.
- Manual markers appear in JSONL.
- Export/share works.
- Recorder does not crash if permissions are missing.
- Session summary updates after recording.
