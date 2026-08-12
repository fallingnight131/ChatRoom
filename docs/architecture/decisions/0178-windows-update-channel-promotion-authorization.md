# ADR-0178: Windows Update-Channel Promotion Authorization

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0176, ADR-0177

## Context

A protected update-signing run proves a candidate is authentic but must not by
itself authorize publication. A channel mutation also needs to know exactly
which manifest is expected to be live, otherwise a stale approval can overwrite
an intervening release. Treating a hand-entered digest as independently
verifiable would leave that current-state identity impossible to reconstruct.

## Decision

- Add a credential-free, write-once schema-1 authorization for the fixed
  `windows-update-production` environment.
- Independently revalidate the entire update-channel candidate, require its
  Ed25519 manifest to be currently valid, and reject candidates older than 24
  hours or from the future.
- Require a canonical, currently valid manifest snapshot representing the
  exact expected channel state. Derive its sequence and SHA-256 from bytes; do
  not accept those values as free-form authorization inputs.
- Require the candidate sequence to be strictly greater than the expected
  current sequence. Initial channel bootstrap remains a separate operation.
- Bind channel, target version/revision/sequence/key ID/installer URL, candidate
  manifest digest, update manifest/signature digests, reviewed public-key file
  digest, Authenticode publisher digest, and exact expected-current manifest
  sequence/digest.
- Limit approval to an exact UTC second and 60–900 seconds. Mark it
  `update-channel-promotion-approved-not-executed`.
- Include no provider credential, network client, upload, channel pointer, or
  mutation command. A future executor must revalidate the authorization and
  perform compare-and-swap against the exact current manifest digest before any
  write.

## Consequences

Signing, operational approval, and endpoint mutation remain separate controls.
Approval tampering is detectable by reconstruction from the immutable candidate
and current snapshot. The snapshot is an approved compare-and-swap input, not
proof that it was observed live; the executor must establish that equality at
the endpoint. Requiring a previous manifest deliberately excludes unsafe
implicit bootstrap.

## Migration and Rollback

Creating or deleting an unused authorization changes no channel. Expired
authorizations cannot be refreshed in place; fetch the current canonical
manifest again, revalidate the candidate, and issue a new record. An intervening
channel update makes the old expected-current digest unusable.

## Verification

- `python3 Tests/windows_update_release_authorization_test.py`
- reject non-advancing sequence, stale/expired candidate or authorization,
  duplicate/unknown fields, candidate changes, current-snapshot changes, and
  write-once overwrite;
- require a future production executor and live endpoint observation before
  claiming stable/beta publication.
