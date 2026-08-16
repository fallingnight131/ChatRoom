export const V2_BROWSER_PERFORMANCE_SCHEMA = 1;

export const V2_BROWSER_PERFORMANCE_METRICS = [
  "previewReadyMs",
  "authenticationMs",
  "conversationOpenMs",
  "sendAcceptanceMs",
] as const;

export type V2BrowserPerformanceMetric =
  typeof V2_BROWSER_PERFORMANCE_METRICS[number];

export interface V2BrowserPerformanceEvidence {
  schemaVersion: 1;
  generatedAt: string;
  sourceRevision: string;
  cleanTree: true;
  appVersion: string;
  endpoint: "wss://fixture.invalid/v2/web";
  evidenceClass: "deterministic-browser-client-only";
  browser: { name: "chromium"; version: string };
  host: {
    platform: string;
    release: string;
    architecture: string;
    cpuModel: string;
    logicalCpuCount: number;
    totalMemoryBytes: number;
    nodeVersion: string;
  };
  scenario: {
    iterations: number;
    isolatedContextPerIteration: true;
    protocolFixture: "generated-protobuf-in-process";
    realNetwork: false;
  };
  samples: Record<V2BrowserPerformanceMetric, number[]>;
  usedJsHeapBytes: number[];
  percentiles: Record<V2BrowserPerformanceMetric, {
    p50: number;
    p95: number;
    p99: number;
  }>;
}

export function nearestRank(values: readonly number[], percentile: number): number {
  if (values.length === 0 || percentile <= 0 || percentile > 1) {
    throw new Error("invalid percentile input");
  }
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.ceil(percentile * sorted.length) - 1]!;
}

export function summarize(values: readonly number[]) {
  return {
    p50: nearestRank(values, 0.50),
    p95: nearestRank(values, 0.95),
    p99: nearestRank(values, 0.99),
  };
}

export function validateV2BrowserPerformanceEvidence(
  value: unknown,
): asserts value is V2BrowserPerformanceEvidence {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("performance evidence must be an object");
  }
  const evidence = value as Partial<V2BrowserPerformanceEvidence>;
  if (evidence.schemaVersion !== V2_BROWSER_PERFORMANCE_SCHEMA
      || evidence.cleanTree !== true
      || evidence.evidenceClass !== "deterministic-browser-client-only"
      || evidence.endpoint !== "wss://fixture.invalid/v2/web"
      || typeof evidence.generatedAt !== "string"
      || !Number.isFinite(Date.parse(evidence.generatedAt))
      || typeof evidence.sourceRevision !== "string"
      || !/^[0-9a-f]{40}$/.test(evidence.sourceRevision)
      || typeof evidence.appVersion !== "string"
      || evidence.appVersion !== evidence.sourceRevision.slice(0, 12)) {
    throw new Error("performance evidence identity is invalid");
  }
  if (evidence.browser?.name !== "chromium" || !evidence.browser.version) {
    throw new Error("performance evidence browser is invalid");
  }
  const iterations = evidence.scenario?.iterations;
  if (!Number.isInteger(iterations) || iterations! < 20 || iterations! > 50
      || evidence.scenario?.isolatedContextPerIteration !== true
      || evidence.scenario?.protocolFixture !== "generated-protobuf-in-process"
      || evidence.scenario?.realNetwork !== false) {
    throw new Error("performance evidence scenario is invalid");
  }
  const host = evidence.host;
  if (!host || !host.platform || !host.release || !host.architecture || !host.cpuModel
      || !Number.isInteger(host.logicalCpuCount) || host.logicalCpuCount <= 0
      || !Number.isSafeInteger(host.totalMemoryBytes) || host.totalMemoryBytes <= 0
      || !host.nodeVersion) {
    throw new Error("performance evidence host is invalid");
  }
  for (const metric of V2_BROWSER_PERFORMANCE_METRICS) {
    const samples = evidence.samples?.[metric];
    if (!Array.isArray(samples) || samples.length !== iterations
        || samples.some(sample => !Number.isFinite(sample) || sample <= 0)) {
      throw new Error(`performance evidence ${metric} samples are invalid`);
    }
    const expected = summarize(samples);
    const actual = evidence.percentiles?.[metric];
    if (!actual || actual.p50 !== expected.p50 || actual.p95 !== expected.p95
        || actual.p99 !== expected.p99) {
      throw new Error(`performance evidence ${metric} percentiles are invalid`);
    }
  }
  if (!Array.isArray(evidence.usedJsHeapBytes)
      || evidence.usedJsHeapBytes.some(sample => !Number.isSafeInteger(sample) || sample <= 0)
      || (evidence.usedJsHeapBytes.length !== 0
        && evidence.usedJsHeapBytes.length !== iterations)) {
    throw new Error("performance evidence heap samples are invalid");
  }
}
