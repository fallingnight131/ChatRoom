# ADR-0136: Compiled Windows Update Product Trust

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The updater primitives accept injected keys and URLs, but a product UI must not
enable itself from an arbitrary writable settings file. Conversely, committing
an update private key or silently using a test key would destroy the release
trust model.

## Decision

- Add one compiled product-configuration boundary. Ordinary builds contain no
  update configuration and remain disabled with no error.
- Enable configuration only when qmake receives
  `CHAT_UPDATE_ENABLED=1`, channel, exact HTTPS manifest URL, primary key ID, and
  lowercase 32-byte Ed25519 public key. An optional complete secondary key pair
  supports old/new key rotation; at most two keys are accepted.
- Accept only `stable` or `beta`; require the manifest's direct parent path
  segment to match the channel and its exact filename to be `manifest.json`.
  Derive the same-origin signature URL as `manifest.json.sig`. Reject credentials,
  query, fragment, noncanonical encoding, traversal, duplicate IDs, or malformed
  key material.
- Treat Ed25519 public keys as reviewed release inputs, not secrets. Keep offline
  manifest private keys and Authenticode private keys entirely outside source,
  ordinary CI workspaces, arguments, logs, and artifacts.
- Do not yet instantiate discovery or UI from this configuration. A later slice
  may expose update checking only when `enabled` is true.

## Consequences

A release candidate has an explicit, testable trust identity while developer
and unsigned verification builds remain default-off. Changing endpoint,
channel, or key ring requires rebuilding and signing the Windows client rather
than editing per-user settings.

The public key and URL can appear in build metadata. This is intentional; their
integrity comes from the signed client artifact, while secrecy belongs only to
the corresponding private keys.

## Migration and Rollback

No existing build is enabled. Rollback removes the configuration boundary and
qmake inputs; all updater network paths remain inactive. Key rotation must ship
a signed client trusting old and new IDs before manifests move exclusively to
the new key.

## Verification

- portable tests prove default-off behavior, a compiled enabled fixture, exact
  URL/signature derivation, dual-key rotation, and rejection of unsafe channels,
  schemes, paths, queries, IDs, key bytes, and duplicates;
- qmake rejects partial enabled configuration and an incomplete secondary key;
- signed release provisioning and custody remain external M4 gates.
