import { expect, test } from "@playwright/test";

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
