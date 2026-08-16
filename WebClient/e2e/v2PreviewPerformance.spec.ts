import { expect, test, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { arch, cpus, platform, release, totalmem } from "node:os";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { performance } from "node:perf_hooks";

import { createV2ProtocolFixture } from "./fixtures/v2ProtocolFixture";
import {
  summarize,
  validateV2BrowserPerformanceEvidence,
  type V2BrowserPerformanceEvidence,
} from "./fixtures/v2PerformanceEvidence";

const enabled = process.env.CHATROOM_V2_BROWSER_PERFORMANCE === "true";
const endpoint = "wss://fixture.invalid/v2/web" as const;
const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));

test.describe("Web V2 deterministic client performance", () => {
  test.skip(!enabled, "requires an explicit clean-tree V2 performance run");
  test.describe.configure({ timeout: 180_000 });

  test("records cold preview, authentication, open, send, and heap samples", async ({ browser }, testInfo) => {
    test.skip(testInfo.project.name !== "chromium", "the baseline browser is Chromium only");
    const output = process.env.CHATROOM_V2_PERFORMANCE_OUTPUT;
    if (!output) throw new Error("CHATROOM_V2_PERFORMANCE_OUTPUT is required");
    const revision = git("rev-parse", "HEAD");
    if (!/^[0-9a-f]{40}$/.test(revision)) throw new Error("Git revision is invalid");
    if (git("status", "--porcelain=v1", "--untracked-files=all")) {
      throw new Error("Web V2 browser performance requires a clean worktree");
    }
    const appVersion = revision.slice(0, 12);
    const iterations = boundedIterations(process.env.CHATROOM_V2_PERFORMANCE_ITERATIONS);
    const samples = {
      previewReadyMs: [] as number[],
      authenticationMs: [] as number[],
      conversationOpenMs: [] as number[],
      sendAcceptanceMs: [] as number[],
    };
    const usedJsHeapBytes: number[] = [];

    for (let iteration = 0; iteration < iterations; iteration += 1) {
      const context = await browser.newContext();
      const page = await context.newPage();
      const fixture = createV2ProtocolFixture("accept");
      await installFixture(page, fixture.respond);
      try {
        let started = performance.now();
        await page.goto("/#/preview/v2");
        await expect(page.getByText("可登录", { exact: true })).toBeVisible();
        samples.previewReadyMs.push(elapsed(started));
        expect(fixture.clientHelloAppVersions).toEqual([appVersion]);

        await page.getByLabel("用户 ID").fill("browser_v2_user");
        await page.getByLabel("密码").fill("non-secret-test-value");
        started = performance.now();
        await page.getByRole("button", { name: "登录" }).click();
        await expect(page.getByRole("navigation", { name: "V2 会话导航" })).toBeVisible();
        samples.authenticationMs.push(elapsed(started));

        started = performance.now();
        await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
        await expect(page.getByText("Fixture incoming message")).toBeVisible();
        samples.conversationOpenMs.push(elapsed(started));

        await page.getByLabel("消息内容").fill("Fixture outgoing message");
        started = performance.now();
        await page.getByRole("button", { name: "发送", exact: true }).click();
        await expect(page.getByLabel("消息 2：已接收")).toBeVisible();
        samples.sendAcceptanceMs.push(elapsed(started));

        const heap = await readUsedHeap(page);
        if (heap !== null) usedJsHeapBytes.push(heap);
      } finally {
        await context.close();
      }
    }

    const cpu = cpus();
    const evidence: V2BrowserPerformanceEvidence = {
      schemaVersion: 1,
      generatedAt: new Date().toISOString(),
      sourceRevision: revision,
      cleanTree: true,
      appVersion,
      endpoint,
      evidenceClass: "deterministic-browser-client-only",
      browser: { name: "chromium", version: browser.version() },
      host: {
        platform: platform(),
        release: release(),
        architecture: arch(),
        cpuModel: cpu[0]?.model.trim() || "unknown",
        logicalCpuCount: cpu.length,
        totalMemoryBytes: totalmem(),
        nodeVersion: process.version,
      },
      scenario: {
        iterations,
        isolatedContextPerIteration: true,
        protocolFixture: "generated-protobuf-in-process",
        realNetwork: false,
      },
      samples,
      usedJsHeapBytes,
      percentiles: {
        previewReadyMs: summarize(samples.previewReadyMs),
        authenticationMs: summarize(samples.authenticationMs),
        conversationOpenMs: summarize(samples.conversationOpenMs),
        sendAcceptanceMs: summarize(samples.sendAcceptanceMs),
      },
    };
    validateV2BrowserPerformanceEvidence(evidence);
    writeFileSync(resolve(output), `${JSON.stringify(evidence, null, 2)}\n`, {
      encoding: "utf8",
      flag: "wx",
    });
  });
});

async function installFixture(
  page: Page,
  respond: (bytes: number[]) => number[] | null,
): Promise<void> {
  await page.routeWebSocket(endpoint, socket => {
    socket.onMessage(message => {
      if (typeof message === "string") throw new Error("performance fixture received text");
      const response = respond(Array.from(message));
      if (response !== null) socket.send(Buffer.from(response));
    });
  });
}

function git(...args: string[]): string {
  return execFileSync("git", args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

function boundedIterations(raw: string | undefined): number {
  const value = raw === undefined ? 20 : Number(raw);
  if (!Number.isInteger(value) || value < 20 || value > 50) {
    throw new Error("CHATROOM_V2_PERFORMANCE_ITERATIONS must be an integer from 20 to 50");
  }
  return value;
}

function elapsed(started: number): number {
  return Math.max(0.001, Number((performance.now() - started).toFixed(3)));
}

async function readUsedHeap(page: Page): Promise<number | null> {
  return page.evaluate(() => {
    const memory = (globalThis.performance as unknown as {
      memory?: { usedJSHeapSize?: number };
    }).memory;
    const value = memory?.usedJSHeapSize;
    return Number.isSafeInteger(value) && value! > 0 ? value! : null;
  });
}
