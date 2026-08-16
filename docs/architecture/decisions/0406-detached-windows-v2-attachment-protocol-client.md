# ADR-0406: Detached Windows V2 attachment protocol client

- Status: Accepted
- Date: 2026-08-16
- Owners: project maintainers
- Related milestone: M6

## Context

Types 120 through 125 already define the inactive V2 attachment registration,
upload authorization, and completion workflow. Java has an inactive authenticated
handler and Web has an inactive coordinator, but Windows has no corresponding
strict client boundary. Real-provider create-only, checksum, CORS, expiry,
lifecycle, and cleanup evidence remains incomplete, so exposing a file picker or
routing these types through the Windows product would be unsafe.

## Decision

- Add a pure C++ Windows protocol client for the existing generated attachment
  schema. It validates bounded metadata and creates no new wire fields or types.
- Bind every command to one authenticated session and one canonical request ID.
  Retain at most eight pending commands and eight registered attachment
  lifecycles in memory.
- Require exact conversation, client-attachment, and stable-attachment identity
  correlation across register, authorize, and complete responses. Type confusion,
  malformed UTF-8, unsafe basenames, noncanonical MIME types, invalid HTTPS
  authorities, forbidden/duplicate headers, and uncorrelated responses fail
  closed.
- Keep the signed URI and required headers only in the returned transient event.
  The protocol client stores neither grant, file bytes, local path, hash input,
  nor credentials. Disconnect clears every pending and tracked identity.
- Keep the client absent from root product CMake, the authenticated WSS transport,
  capability negotiation, SQLite, application services, and Widgets. The
  protocol-only CMake test is the sole composition in this slice.

## Consequences

Windows gains a reviewed protocol seam that a later restartable attachment
coordinator can consume without coupling generated Protobuf objects to UI or
storage. It is not an upload implementation and creates no supported user path.
Provider acceptance, durable source-reselection intent, bounded hashing/upload,
transport routing, capability activation, accessible UI, and Windows Release
evidence remain separate gates.

## Verification

The C++ protocol test covers unbound rejection, basename and MIME validation,
exact type-120 metadata, correlated 121/123/125 responses, transient HTTPS
headers, missing-host rejection, completion cleanup, explicit abandonment, and
disconnect cleanup. The Windows product-composition policy rejects any reference
to this client from the ordinary root product composition.

## Rollback

Remove the detached source and protocol-only test. Permanent message types and
the Java/Web inactive boundaries remain unchanged; no product data or client
database needs migration.
