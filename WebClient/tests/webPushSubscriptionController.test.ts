import assert from "node:assert/strict";
import test from "node:test";
import {
  WebPushSubscriptionController,
  type BrowserPushSubscription,
  type WebPushBrowserPort,
} from "../src/platform/webPushSubscriptionController";

const installation = "10000000-0000-4000-8000-000000000001";
const key = new Uint8Array(65); key[0] = 0x04;

function fixture() {
  const calls: string[] = [];
  let current: BrowserPushSubscription | null = null;
  const subscription: BrowserPushSubscription = {
    toJSON: () => ({ endpoint: "https://push.example/opaque", expirationTime: null,
      keys: { p256dh: "key", auth: "auth" } }),
    async unsubscribe() { calls.push("unsubscribe"); current = null; return true; },
  };
  const browser: WebPushBrowserPort = {
    supported: () => true, permission: () => "granted",
    async requestPermission() { calls.push("permission"); return "granted"; },
    async registerWorker() { calls.push("register"); },
    async currentSubscription() { calls.push("current"); return current; },
    async subscribe(received) { calls.push("subscribe"); assert.notEqual(received, key);
      assert.deepEqual(received, key); current = subscription; return subscription; },
  };
  return { calls, browser, subscription, get current() { return current; },
    set current(value) { current = value; } };
}

test("enables only by user gesture and uploads a newly created browser subscription", async () => {
  const value = fixture();
  const controller = new WebPushSubscriptionController(true, installation, key, value.browser, {
    async replace(id, json) { value.calls.push("replace"); assert.equal(id, installation);
      assert.equal(json.endpoint, "https://push.example/opaque"); },
    async delete() { throw new Error("not used"); },
  });
  assert.deepEqual(controller.snapshot, { enabled: false, pending: false, state: "disabled" });
  assert.deepEqual(await controller.enableFromUserGesture(),
    { enabled: true, pending: false, state: "enabled" });
  assert.deepEqual(value.calls, ["permission", "register", "current", "subscribe", "replace"]);
});

test("rolls back a newly created subscription when server replacement fails", async () => {
  const value = fixture();
  const controller = new WebPushSubscriptionController(true, installation, key, value.browser, {
    async replace() { value.calls.push("replace"); throw new Error("unavailable"); },
    async delete() {},
  });
  assert.deepEqual(await controller.enableFromUserGesture(),
    { enabled: false, pending: false, state: "server-failed" });
  assert.deepEqual(value.calls,
    ["permission", "register", "current", "subscribe", "replace", "unsubscribe"]);
  assert.equal(value.current, null);
});

test("deletes server state before browser unsubscribe and preserves local state on denial", async () => {
  const value = fixture(); value.current = value.subscription;
  const controller = new WebPushSubscriptionController(true, installation, key, value.browser, {
    async replace() {}, async delete() { value.calls.push("delete"); },
  });
  assert.deepEqual(await controller.disable(),
    { enabled: false, pending: false, state: "disabled" });
  assert.deepEqual(value.calls, ["current", "delete", "unsubscribe"]);

  const denied = fixture(); denied.current = denied.subscription;
  const deniedController = new WebPushSubscriptionController(
    true, installation, key, denied.browser, {
      async replace() {}, async delete() { denied.calls.push("delete"); throw new Error("denied"); },
    });
  assert.deepEqual(await deniedController.disable(),
    { enabled: true, pending: false, state: "server-failed" });
  assert.deepEqual(denied.calls, ["current", "delete"]);
});

test("exact default-off never touches permission, worker, PushManager, or server", async () => {
  const value = fixture();
  const controller = new WebPushSubscriptionController(false, "", new Uint8Array(), value.browser, {
    async replace() { throw new Error("must not call"); }, async delete() { throw new Error("must not call"); },
  });
  assert.deepEqual(await controller.enableFromUserGesture(),
    { enabled: false, pending: false, state: "unsupported" });
  assert.deepEqual(value.calls, []);
});
