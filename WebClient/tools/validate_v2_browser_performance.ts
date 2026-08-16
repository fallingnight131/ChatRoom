import { readFileSync } from "node:fs";

import { validateV2BrowserPerformanceEvidence } from
  "../e2e/fixtures/v2PerformanceEvidence";

const path = process.argv[2];
if (!path || process.argv.length !== 3) {
  throw new Error("usage: npm run validate:v2-browser-performance -- <evidence.json>");
}

const value: unknown = JSON.parse(readFileSync(path, "utf8"));
validateV2BrowserPerformanceEvidence(value);
process.stdout.write(`Web V2 browser performance evidence passed: ${path}\n`);
