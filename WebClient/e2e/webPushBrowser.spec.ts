import { expect, test } from "@playwright/test";
import { readdirSync, statSync } from "node:fs";

const enabled = process.env.CHATROOM_WEB_PUSH_WORKER_BROWSER_GATE === "true";

test("registers and rolls back the exact Web Push worker in a secure context", async ({ page }) => {
  test.skip(!enabled, "requires the explicit Web Push worker browser gate");
  const assetRoot = new URL("../dist/assets/", import.meta.url);
  const assets = readdirSync(assetRoot)
    .filter(name => /^webPushServiceWorkerEntry-[A-Za-z0-9_-]+\.js$/.test(name)
      && statSync(new URL(name, assetRoot)).size > 1_024);
  expect(assets).toHaveLength(1);
  const workerPath = `/assets/${assets[0]}`;

  await page.goto("/");
  expect(await page.evaluate(() => ({
    secure: isSecureContext,
    serviceWorkerAvailable: Boolean(navigator.serviceWorker),
  }))).toEqual({ secure: true, serviceWorkerAvailable: true });
  expect(await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).length)).toBe(0);

  const activeScript = await page.evaluate(async path => {
    const registration = await navigator.serviceWorker.register(path, { scope: "/" });
    const ready = await navigator.serviceWorker.ready;
    const worker = ready.active ?? registration.active ?? registration.waiting ?? registration.installing;
    if (!worker) throw new Error("Web Push worker did not activate");
    return worker.scriptURL;
  }, workerPath);
  expect(new URL(activeScript).pathname).toBe(workerPath);

  expect(await page.evaluate(async () => {
    const registrations = await navigator.serviceWorker.getRegistrations();
    const removed = await Promise.all(registrations.map(value => value.unregister()));
    await caches.delete("chatroom-web-push-locale-v1");
    return { count: registrations.length, removed };
  })).toEqual({ count: 1, removed: [true] });
  await expect.poll(() => page.evaluate(async () =>
    (await navigator.serviceWorker.getRegistrations()).length)).toBe(0);
});
