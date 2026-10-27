# Issue 005 — Add report generator for phone JSONL + OAI logs

## Goal

Create a report generator that correlates the Android app JSONL export with host-side OAI logs collected by Issue 004.

## File

Create:

```text
tools/oai_ue_diag_report.py
```

## Input

Arguments:

--android-jsonl FILE
--run-dir DIR
--out FILE default report.md

The run dir may contain:

gnb.log
amf.log
smf.log
upf.log
manifest.json
summary.txt
core.pcap

## Output

Generate a Markdown report with:

1. Device/session summary
2. Expected OAI settings if present in JSONL
3. Timeline table
4. Phone-side observations
5. OAI-side observations
6. Diagnosis hints
7. Unknowns / limitations
8. Next tests

## Correlation

Try to correlate by wall-clock timestamps when available.

Highlight key milestones:

- First NR cell observed
- First registered NR cell observed
- Service state IN_SERVICE
- Data connected
- First gNB RACH/RRC event
- First AMF Registration Request
- Authentication success/failure
- Registration Accept/Reject
- PDU Session Accept/Reject
- SMF DNN/NSSAI references
- UPF session/user-plane references

## Acceptance criteria
- Script runs with --help.
- Script accepts a JSONL session export and a run directory.
- Markdown report is generated.
- Missing files are handled gracefully.
- README explains workflow.
