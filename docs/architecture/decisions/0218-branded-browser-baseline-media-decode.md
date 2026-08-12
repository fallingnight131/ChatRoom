# ADR-0218: Require Real Baseline Media Decode in Branded Browser Evidence

- Status: Superseded by ADR-0219
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0217 evidence schema 4

## Context

The Web client previews audio and video, but prior browser gates proved neither
codec decoding nor media metadata. `canPlayType()` is only a capability hint and
cannot establish that a browser actually decodes the content.

## Decision

- Advance branded-browser host evidence to schema 5.
- Commit two tiny synthetic, sub-second Base64 fixtures: 16x16 VP9 WebM video
  and mono Opus-in-Ogg audio. They contain no user or third-party content and
  include reproducible FFmpeg generation commands.
- Construct Blob URLs in each browser, require real `loadedmetadata` within five
  seconds, positive durations, and exact 16x16 video dimensions. Revoke every
  Blob URL on success or failure.
- Add mandatory `baselineMediaDecoded` to all six host records. Keep MP4/H.264,
  MP3/AAC, large-file streaming, DPlayer controls, and device-specific hardware
  acceleration outside this baseline and do not claim them from this evidence.

## Consequences

The supported browser matrix gains one conservative open-codec media baseline.
Product codec policy can expand only with explicit fixtures and support data;
this avoids accidental platform-codec promises.

## Verification

- `npm run test:browser` from `WebClient/`
- `python3 Tests/web_browser_host_evidence_test.py`
- fixture generation commands in `WebClient/e2e/fixtures/README.md`
