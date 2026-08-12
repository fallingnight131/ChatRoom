# ADR-0115: Web Browser Engine Compatibility Gate

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

Node unit tests and a successful Vite build do not prove that the Web UI starts,
persists data, connects safely, or remains usable in browsers. The support
matrix had no pinned browser automation and therefore could not make a public
compatibility claim. The project needs a reproducible first browser gate without
mistaking Playwright's patched engines for branded Chrome, Edge, Firefox, or
Safari release evidence.

## Decision

- Pin `@playwright/test` exactly at 1.62.0 in the Web lockfile. That release
  binds Chromium 151.0.7922.34 and Playwright Firefox 153.0 for this iteration.
- Run both engines against the real production Vite build and preview server.
  Use one worker in CI, bounded retries, trace/screenshot only on failure, and
  retain failure reports for 14 days.
- Gate the login product surface, absence of page errors, IndexedDB creation,
  WebSocket/fetch/Blob/AbortController/BigInt/`crypto.randomUUID` capabilities,
  purge of hostile legacy server overrides, exact safe WebSocket target, and a
  narrow responsive viewport.
- Add correct username/current/new-password autocomplete semantics as part of
  the browser-facing login contract.
- Treat the automated engines as engineering evidence, not a branded-browser
  support declaration. Public Web targets are current and previous stable
  desktop Google Chrome, Microsoft Edge, and Mozilla Firefox; those branded
  versions need a release-time matrix before public support begins.
- Do not claim Safari/WebKit or mobile-browser support. Responsive browser
  behavior is useful but does not create an iOS/Android native product or an
  untested browser promise.
- Review the bound engine versions whenever Playwright is upgraded. Browser
  binaries are downloaded by the official CLI and are not committed or cached
  as project artifacts.

## Consequences

Local macOS development now has real Chromium/Firefox feedback, and Linux CI is
configured to install the same pinned engines and run six product checks. The
matrix catches engine differences (including reconnect timing) that Node tests
cannot reveal.

This remains a shallow release smoke suite: authenticated messaging, IndexedDB
restart/offline flows, attachments/media codecs, accessibility tree depth,
branded current/previous versions, Windows browser behavior, and production
HTTPS deployment still need broader coverage. The M4 public Web release gate
therefore remains open.

## Migration and Rollback

No protocol or durable data migration. Rollback removes Playwright configuration,
tests, dependency, CI steps, and autocomplete annotations. Browser test output
directories remain ignored and contain no production data.

## Verification

- the locked Playwright registry identifies Chromium 151.0.7922.34 and Firefox
  153.0;
- all three scenarios pass in both engines on the maintainer's macOS development
  host;
- CI is configured to install only Chromium and Firefox with system dependencies,
  run serially, and upload failure evidence without publishing a client artifact;
- Node tests and the production build remain independent gates.
