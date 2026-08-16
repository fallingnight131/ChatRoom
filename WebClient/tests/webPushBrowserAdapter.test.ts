import assert from "node:assert/strict";
import test from "node:test";
import { createWebPushBrowserAdapter } from "../src/platform/webPushBrowserAdapter";
import type { BrowserPushSubscription } from "../src/platform/webPushSubscriptionController";

test("registers one bundled worker and creates a user-visible subscription with a key copy", async () => {
  const calls: unknown[] = []; let subscription: BrowserPushSubscription | null = null;
  const created = { toJSON: () => ({}), async unsubscribe() { return true; } };
  const registration = { pushManager: {
    async getSubscription() { calls.push("get"); return subscription; },
    async subscribe(options: { userVisibleOnly: true; applicationServerKey: Uint8Array }) {
      calls.push(options); subscription = created; return created;
    },
  } };
  const adapter = createWebPushBrowserAdapter({
    serviceWorker: {
      async register(url, options) { calls.push({ url, options }); return registration; },
      async getRegistration() { calls.push("lookup"); return undefined; },
    },
    notification: { permission: "granted", async requestPermission() { return "granted"; } },
    pushManagerSupported: true, secureContext: true,
    workerUrl: "/assets/web-push-worker.js", scope: "/",
  });
  assert.equal(adapter.supported(), true);
  await adapter.registerWorker();
  assert.equal(await adapter.currentSubscription(), null);
  const key = new Uint8Array([4, 1, 2]);
  assert.equal(await adapter.subscribe(key), created);
  const options = calls[2] as { userVisibleOnly: true; applicationServerKey: Uint8Array };
  assert.equal(options.userVisibleOnly, true);
  assert.notEqual(options.applicationServerKey, key);
  assert.deepEqual(options.applicationServerKey, key);
  assert.deepEqual(calls[0], { url: "/assets/web-push-worker.js",
    options: { scope: "/" } });
});

test("fails closed without secure Service Worker and PushManager capabilities", () => {
  const adapter = createWebPushBrowserAdapter({
    pushManagerSupported: false, secureContext: false, workerUrl: "/worker.js",
  });
  assert.equal(adapter.supported(), false);
  assert.equal(adapter.permission(), "denied");
  assert.throws(() => createWebPushBrowserAdapter({
    pushManagerSupported: true, workerUrl: "https://evil.example/worker.js?x=1",
  }), /invalid worker URL/);
});
