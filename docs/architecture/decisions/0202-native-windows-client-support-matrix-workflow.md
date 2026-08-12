# ADR-0202: Run the Windows Client Support Matrix on Dedicated Client Hosts

- Status: Accepted
- Date: 2026-08-13
- Owners: Windows release engineering and quality
- Related milestone: M4
- Extends: ADR-0201

## Context

ADR-0201 defines independently verifiable evidence, but evidence is useful only
when a controlled executor actually exercises two real signed releases on the
named client operating systems. GitHub-hosted Windows Server runners cannot
stand in for those client hosts, and the protected signing runner should not
also own compatibility approval.

## Decision

- Add a manual, reviewed `windows-client-support-matrix` environment that runs
  only on dedicated self-hosted x86_64 Windows 10 22H2 and Windows 11
  23H2/24H2 clean-host labels.
- Download exact current and previous artifacts from reviewed protected-signing
  run IDs. Revalidate both complete candidates before executing either Setup.
- Give the workflow read-only repository/artifact permissions and no signing,
  update-channel, or publication credentials. Pass dispatch values to scripts
  through validated environment variables rather than source interpolation.
- On each client host require ProductType 1 and exact build; timestamped signer
  parity; previous install and live launch; current upgrade and exact installed
  bytes; AppData preservation; current live launch; running-client and downgrade
  rejection; clean uninstall; retained AppData; and program/registry cleanup.
- Independently verify and retain one per-target ADR-0201 record. All three jobs
  must succeed before a later aggregate release-support decision is possible.

## Consequences

Repository changes now define the executable gate, but no native result is
claimed until the dedicated hosts are provisioned and a reviewed run succeeds.
Persistent self-hosted workers must be restored to their clean snapshot between
runs; the script also rejects pre-existing product install/data state.

## Verification

- `python3 Tests/windows_support_matrix_workflow_test.py`
- `python3 Tests/windows_support_host_evidence_test.py`
- native execution of `.github/workflows/m4-windows-support-matrix.yml`
