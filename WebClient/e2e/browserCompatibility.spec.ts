import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";

const brandedTarget = process.env.CHATROOM_BRANDED_BROWSER_TARGET;

test("loads the production login surface with required browser capabilities", async ({ page }) => {
  const pageErrors: Error[] = [];
  page.on("pageerror", error => pageErrors.push(error));

  await page.goto("/", { waitUntil: "networkidle" });

  await expect(page).toHaveTitle("聊天室 - Web");
  await expect(page.getByRole("heading", { name: "ChatRoom" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  await expect(page.locator('input[placeholder="输入唯一用户ID"]')).toBeEditable();
  await expect(page.locator('input[type="password"]')).toHaveAttribute("autocomplete", "current-password");

  const capabilities = await page.evaluate(async () => {
    const databaseName = `chat-room-browser-gate-${crypto.randomUUID()}`;
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open(databaseName, 1);
      request.onerror = () => reject(request.error);
      request.onupgradeneeded = () => request.result.createObjectStore("health");
      request.onsuccess = () => {
        request.result.close();
        const deletion = indexedDB.deleteDatabase(databaseName);
        deletion.onerror = () => reject(deletion.error);
        deletion.onsuccess = () => resolve();
      };
    });
    return {
      abortController: typeof AbortController === "function",
      bigInt: typeof BigInt === "function",
      blob: typeof Blob === "function",
      cryptoRandomUuid: typeof crypto.randomUUID === "function",
      fetch: typeof fetch === "function",
      indexedDb: typeof indexedDB === "object",
      webSocket: typeof WebSocket === "function",
    };
  });
  expect(capabilities).toEqual({
    abortController: true,
    bigInt: true,
    blob: true,
    cryptoRandomUuid: true,
    fetch: true,
    indexedDb: true,
    webSocket: true,
  });
  expect(pageErrors).toEqual([]);
});

test("purges hostile legacy server overrides before a login connection", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("serverHost", "evil.example");
    localStorage.setItem("serverPort", "443");
    localStorage.setItem("wsPath", "/steal");
  });
  const socketUrls: string[] = [];
  page.on("websocket", socket => socketUrls.push(socket.url()));

  await page.goto("/");
  await page.locator('input[placeholder="输入唯一用户ID"]').fill("browser_gate_user");
  await page.locator('input[type="password"]').fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();

  await expect.poll(() => socketUrls.length).toBeGreaterThan(0);
  expect(new Set(socketUrls)).toEqual(new Set(["ws://127.0.0.1:9528/"]));
  const persisted = await page.evaluate(() => ({
    host: localStorage.getItem("serverHost"),
    port: localStorage.getItem("serverPort"),
    path: localStorage.getItem("wsPath"),
  }));
  expect(persisted).toEqual({ host: null, port: null, path: null });
});

test("keeps the login path usable at a narrow responsive viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  const card = page.locator(".login-card");
  await expect(card).toBeVisible();
  const bounds = await card.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  await expect(page.locator('input[placeholder="输入唯一用户ID"]')).toBeEditable();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
});

test("records one exact branded-browser candidate smoke", async ({ browser }) => {
  test.skip(!brandedTarget, "Only the protected branded-browser matrix emits support evidence");
  const required = (name: string): string => {
    const value = process.env[name];
    if (!value) throw new Error(`Missing branded-browser evidence input: ${name}`);
    return value;
  };
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors: Error[] = [];
  page.on("pageerror", error => pageErrors.push(error));

  await page.goto("/", { waitUntil: "networkidle" });
  await expect(page).toHaveTitle("聊天室 - Web");
  await expect(page.getByRole("heading", { name: "ChatRoom" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  await expect(page.locator('input[placeholder="输入唯一用户ID"]')).toBeEditable();

  const runtime = await page.evaluate(async () => {
    const databaseName = `chat-room-branded-gate-${crypto.randomUUID()}`;
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open(databaseName, 1);
      request.onerror = () => reject(request.error);
      request.onupgradeneeded = () => request.result.createObjectStore("health");
      request.onsuccess = () => {
        request.result.close();
        const deletion = indexedDB.deleteDatabase(databaseName);
        deletion.onerror = () => reject(deletion.error);
        deletion.onsuccess = () => resolve();
      };
    });
    return {
      capabilities: [AbortController, BigInt, Blob, fetch, WebSocket]
        .every(value => typeof value === "function")
        && typeof crypto.randomUUID === "function",
      userAgent: navigator.userAgent,
    };
  });
  expect(runtime.capabilities).toBe(true);

  await page.addInitScript(() => {
    localStorage.setItem("serverHost", "evil.example");
    localStorage.setItem("serverPort", "443");
    localStorage.setItem("wsPath", "/steal");
  });
  const socketUrls: string[] = [];
  page.on("websocket", socket => socketUrls.push(socket.url()));
  await page.reload();
  await page.locator('input[placeholder="输入唯一用户ID"]').fill("branded_gate_user");
  await page.locator('input[type="password"]').fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect.poll(() => socketUrls.length).toBeGreaterThan(0);
  expect(new Set(socketUrls)).toEqual(new Set(["ws://127.0.0.1:9528/"]));
  await expect.poll(() => page.evaluate(() => ({
    host: localStorage.getItem("serverHost"),
    port: localStorage.getItem("serverPort"),
    path: localStorage.getItem("wsPath"),
  }))).toEqual({ host: null, port: null, path: null });

  const responsive = await context.newPage();
  responsive.on("pageerror", error => pageErrors.push(error));
  await responsive.setViewportSize({ width: 390, height: 844 });
  await responsive.goto("/");
  const bounds = await responsive.locator(".login-card").boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  expect(pageErrors).toEqual([]);

  const architecture = process.arch === "x64" ? "x86_64" : process.arch;
  const evidence = {
    schemaVersion: 1,
    evidenceType: "web-browser-host-acceptance",
    status: "candidate-smoke-observed",
    product: "chat-room-web-client",
    targetId: brandedTarget,
    browserFamily: required("CHATROOM_BRANDED_BROWSER_FAMILY"),
    browserProduct: required("CHATROOM_BRANDED_BROWSER_PRODUCT"),
    supportPosition: required("CHATROOM_BRANDED_BROWSER_POSITION"),
    browserVersion: browser.version(),
    browserExecutableSha256: required("CHATROOM_BRANDED_BROWSER_EXECUTABLE_SHA256"),
    platform: `${process.platform}-${required("CHATROOM_BRANDED_BROWSER_HOST")}`,
    architecture,
    userAgent: runtime.userAgent,
    releaseId: required("CHATROOM_WEB_RELEASE_ID"),
    version: required("CHATROOM_WEB_VERSION"),
    sourceRevision: required("CHATROOM_WEB_SOURCE_REVISION"),
    artifactManifestSha256: required("CHATROOM_WEB_MANIFEST_SHA256"),
    checks: {
      productionLoginSurface: true,
      requiredWebCapabilities: true,
      indexedDb: true,
      serverEndpointIsolation: true,
      responsiveLogin: true,
      noPageErrors: true,
    },
    observedAt: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
  };
  writeFileSync(required("CHATROOM_BRANDED_BROWSER_EVIDENCE"), `${JSON.stringify(evidence, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
  await context.close();
});
