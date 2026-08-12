# Web Response Policy

`response-policy.json` is the provider-neutral production response contract for
the versioned Web artifact. The hosting adapter must translate every declared
security header and cache class without broadening it:

- `site/index.html` uses `versionEntrypoint` (`no-store`);
- content-hashed `site/assets/*` use `hashedAssets` (one-year immutable);
- any other static file uses `other` (`no-cache`);
- every static response carries all `securityHeaders`;
- HTTPS responses expose the release version and source revision using the
  declared identity header names.

The CSP deliberately keeps `style-src 'unsafe-inline'` because the current Vue
client and media UI use inline style attributes for positioning and progress.
Scripts remain self-only with no inline/eval exception. Removing the style
exception is desirable, but must follow UI refactoring plus browser tests rather
than silently breaking layout.

HSTS intentionally omits `includeSubDomains` and `preload`; the project has not
established ownership and HTTPS readiness for every subdomain. Add either only
through a security ADR and domain-wide verification.

`tools/web_artifact_manifest.py` rejects missing or weakened fields and binds
the exact policy bytes into schema-2 artifact metadata. This proves artifact
intent, not that a hosting provider served the headers. Deployment observation,
health checks, browser coverage, and rollback rehearsal remain separate gates.
`tools/web_release_probe.py` closes the observation part only after an HTTPS
adapter exists: it requests identity encoding, verifies every declared byte and
exact header, and emits closed, write-once release evidence bound to the artifact
manifest bytes. The same tool can independently re-probe a retained observation.
`tools/web_rollback_evidence.py` binds prior-A, current-B, and restored-A
observations by digest, origin, exact release identity, and strict time order.
Neither tool publishes a release or turns isolated fixture output into production
evidence.
