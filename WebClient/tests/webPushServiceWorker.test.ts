import assert from "node:assert/strict";
import test from "node:test";
import { installWebPushServiceWorker } from "../src/platform/webPushServiceWorker";

const payload = JSON.stringify({
  version: 1,
  notificationId: "10000000-0000-4000-8000-000000000001",
  conversationId: "20000000-0000-4000-8000-000000000001",
  messageId: "30000000-0000-4000-8000-000000000001",
  mentioned: false,
});

test("shows only validated generic push and focuses one same-origin window", async () => {
  const listeners = new Map<string, (event: any) => void>();
  const shown: any[] = []; const navigated: string[] = []; let focused = 0;
  const scope = {
    registration: { async showNotification(title: string, options: unknown) {
      shown.push({ title, options });
    } },
    clients: {
      async matchAll() { return [{ url: "https://chat.example/#/login",
        async navigate(url: string) { navigated.push(url); }, async focus() { focused++; } }]; },
      async openWindow() { throw new Error("must reuse window"); },
    },
    addEventListener(type: string, listener: (event: any) => void) { listeners.set(type, listener); },
  };
  installWebPushServiceWorker(scope, {
    messageTitle: "New message", mentionTitle: "Mention", body: "Open ChatRoom",
  }, "https://chat.example");
  let pushPromise: Promise<unknown> | undefined;
  listeners.get("push")!({ data: { text: () => payload }, waitUntil(p: Promise<unknown>) { pushPromise = p; } });
  await pushPromise;
  assert.equal(shown.length, 1);
  assert.equal(shown[0].title, "New message");
  let clickPromise: Promise<unknown> | undefined; let closed = 0;
  listeners.get("notificationclick")!({ notification: {
    data: shown[0].options.data, close() { closed++; },
  }, waitUntil(p: Promise<unknown>) { clickPromise = p; } });
  await clickPromise;
  assert.equal(closed, 1); assert.equal(focused, 1); assert.equal(navigated.length, 1);
  assert.match(navigated[0], /^https:\/\/chat\.example\/#\/preview\/v2\?/);
});

test("drops malformed push and unsafe click without opening a window", () => {
  const listeners = new Map<string, (event: any) => void>(); let waits = 0; let closed = 0;
  installWebPushServiceWorker({
    registration: { async showNotification() { throw new Error("must not show"); } },
    clients: { async matchAll() { return []; }, async openWindow() { throw new Error("must not open"); } },
    addEventListener(type: string, listener: (event: any) => void) { listeners.set(type, listener); },
  }, { messageTitle: "", mentionTitle: "", body: "" }, "https://chat.example");
  listeners.get("push")!({ data: { text: () => "{}" }, waitUntil() { waits++; } });
  listeners.get("notificationclick")!({ notification: { data: {
    navigationUrl: "https://evil.example/#/preview/v2?x=1",
    notificationId: "10000000-0000-4000-8000-000000000001",
  }, close() { closed++; } }, waitUntil() { waits++; } });
  assert.equal(waits, 0); assert.equal(closed, 1);
});

test("resolves durable localized copy inside the push lifetime", async () => {
  const listeners = new Map<string, (event: any) => void>(); const shown: string[] = [];
  installWebPushServiceWorker({
    registration: { async showNotification(title: string) { shown.push(title); } },
    clients: { async matchAll() { return []; }, async openWindow() {} },
    addEventListener(type: string, listener: (event: any) => void) { listeners.set(type, listener); },
  }, async () => ({ messageTitle: "本地化消息", mentionTitle: "本地化提及", body: "打开应用" }),
  "https://chat.example");
  let pending: Promise<unknown> | undefined;
  listeners.get("push")!({ data: { text: () => payload }, waitUntil(value: Promise<unknown>) { pending = value; } });
  await pending;
  assert.deepEqual(shown, ["本地化消息"]);
});
