# ADR-0201: Require Exact Windows Client Support-Host Evidence

- Status: Accepted
- Date: 2026-08-13
- Owners: Windows release engineering and quality
- Related milestone: M4
- Extends: ADR-0009, ADR-0110, ADR-0190

## Context

Windows Server CI proves native compilation and the protected signing runner
proves one signed install/uninstall cycle, but neither identifies a supported
Windows client release nor proves a real previous signed version can launch,
upgrade, retain user data, reject downgrade, and uninstall. A broad “Windows
10/11” claim is not independently auditable without exact OS targets.

## Decision

- Pin the initial x86_64 client matrix to Windows 10 22H2 build 19045 and
  Windows 11 23H2/24H2 builds 22631/26100.
- Require ProductType 1 client OS evidence; Windows Server must fail.
- Bind one fresh per-host record to complete current and previous signed
  candidate manifests, versions, revisions, channel, Qt version, publisher,
  caption/version/build, and exact target ID.
- Require every clean-host, previous install/launch, signed upgrade, AppData
  preservation, current launch, running-client rejection, downgrade rejection,
  uninstall, cleanup, and registration result to be explicitly true.
- Independently revalidate both complete candidates and their manifest hashes.
  Public support waits for records produced on every named clean client host.

## Consequences

Windows Server 2025 remains useful build evidence but cannot satisfy product
compatibility. The matrix excludes Windows on Arm, Windows 10 before 22H2, and
future Windows 11 builds until reviewed.

## Verification

- `python3 Tests/windows_support_host_evidence_test.py`
- `packaging/windows/support-matrix-policy.json`
