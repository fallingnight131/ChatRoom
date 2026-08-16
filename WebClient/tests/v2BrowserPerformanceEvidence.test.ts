import assert from "node:assert/strict";
import test from "node:test";

import {
  summarize,
  validateV2BrowserPerformanceEvidence,
  type V2BrowserPerformanceEvidence,
} from "../e2e/fixtures/v2PerformanceEvidence";

function evidence(): V2BrowserPerformanceEvidence {
  const values = Array.from({ length: 20 }, (_, index) => index + 1);
  return {
    schemaVersion: 1,
    generatedAt: "2026-08-16T00:00:00.000Z",
    sourceRevision: "a".repeat(40),
    cleanTree: true,
    appVersion: "a".repeat(12),
    endpoint: "wss://fixture.invalid/v2/web",
    evidenceClass: "deterministic-browser-client-only",
    browser: { name: "chromium", version: "151.0.0.0" },
    host: {
      platform: "darwin", release: "test", architecture: "arm64",
      cpuModel: "fixture", logicalCpuCount: 8, totalMemoryBytes: 16_000_000_000,
      nodeVersion: "v22.0.0",
    },
    scenario: {
      iterations: 20,
      isolatedContextPerIteration: true,
      protocolFixture: "generated-protobuf-in-process",
      realNetwork: false,
    },
    samples: {
      previewReadyMs: values,
      authenticationMs: values,
      conversationOpenMs: values,
      sendAcceptanceMs: values,
    },
    usedJsHeapBytes: values.map(value => value * 1_000_000),
    percentiles: {
      previewReadyMs: summarize(values),
      authenticationMs: summarize(values),
      conversationOpenMs: summarize(values),
      sendAcceptanceMs: summarize(values),
    },
  };
}

test("validates exact deterministic Web V2 browser performance evidence", () => {
  assert.doesNotThrow(() => validateV2BrowserPerformanceEvidence(evidence()));
});

test("rejects dirty, undersampled, or forged percentile evidence", () => {
  const dirty = evidence() as unknown as Record<string, unknown>;
  dirty.cleanTree = false;
  assert.throws(() => validateV2BrowserPerformanceEvidence(dirty), /identity/);

  const undersampled = evidence();
  undersampled.samples.previewReadyMs.pop();
  assert.throws(() => validateV2BrowserPerformanceEvidence(undersampled), /samples/);

  const forged = evidence();
  forged.percentiles.sendAcceptanceMs.p99 = 0;
  assert.throws(() => validateV2BrowserPerformanceEvidence(forged), /percentiles/);
});
