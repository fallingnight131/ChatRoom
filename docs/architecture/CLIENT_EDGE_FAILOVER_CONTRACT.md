# Client Edge Failover Contract

This contract keeps the supported Web and Windows V2 preview transports aligned
without sharing platform networking code. ADR-0382 and ADR-0383 own the current
implementations. V1 remains the default product path until its cutover gate is
accepted.

## Trusted Configuration

| Rule | Web | Windows |
| --- | --- | --- |
| Source | immutable Vite build metadata | compiled CMake product metadata |
| Primary | exactly one `wss://authority/v2/web` | exactly one `wss://authority/v2/windows` |
| Fallback bound | at most three | at most one |
| Duplicate/unsafe entry | disable V2 before network access | fail CMake or disable binary configuration |
| User/runtime override | forbidden | forbidden |

The different bounds are deliberate. Web assets may serve several regions;
the Windows preview currently targets the two-edge topology proven by
ADR-0381. Increasing either bound requires reconnect-capacity evidence and a
reviewed configuration/diagnostic update.

## State-Machine Rules

1. Start at the reviewed primary entry.
2. A synchronous connection-start failure or an established socket close moves
   to the next entry in a circular list.
3. Every attempt retains the existing full-jitter exponential backoff and phase
   timeouts. Endpoint rotation never permits a tight retry loop.
4. Every new socket starts a fresh protocol negotiation. An authenticated
   client may present only its current memory-held resume proof.
5. Authentication rejection clears the resume proof. Explicit stop/logout
   clears it and prevents another attempt.
6. Web browser `offline` is a local network signal, not edge-health evidence: it
   closes/pauses without rotating or consuming retry budget. Windows currently
   receives socket disconnects from Qt and rotates; a later Windows network-cost
   observer must preserve this distinction if introduced.
7. Connection success keeps the selected entry. No background probe silently
   moves an active session back to primary.

## Required Evidence

- Web tests cover constructor failure, close, wraparound, offline pause,
  endpoint validation, jittered retry, and memory-only resume.
- Native Windows tests cover compiled pair validation, final-binary diagnostic,
  disconnect rotation, fresh negotiation, and memory-only resume.
- The dual-edge Java/HAProxy gate proves the destination edge and gateway can
  repair durable ordered history; client unit tests alone are not that proof.
- Production release still requires real authorities, certificates, CSP/Origin
  policy, health/discovery behavior, reconnect-storm capacity, and signed Web or
  Windows candidate evidence.

## Rollback

Web redeploys the previous immutable assets or removes fallback metadata.
Windows ships the prior signed candidate or omits the fallback CMake value.
Neither rollback changes protocol or durable client data.
