import assert from "node:assert/strict";
import test from "node:test";

import {
  WEB_NOTIFICATION_PREFERENCE_KEY,
  WebNotificationPreferenceController,
} from "../src/platform/webNotificationPreference";
import type { WebNotificationPermission } from "../src/platform/webMessageNotification";

function memoryStorage(initial?: string) {
  const values = new Map<string, string>();
  if (initial !== undefined) values.set(WEB_NOTIFICATION_PREFERENCE_KEY, initial);
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
  };
}

test("restores only a granted persisted notification preference without prompting", () => {
  const storage = memoryStorage("true");
  let requests = 0;
  const controller = new WebNotificationPreferenceController({
    supported: () => true,
    permission: () => "granted",
    requestPermission: async () => { requests += 1; return "granted"; },
    storage,
  });

  assert.deepEqual(controller.snapshot, { enabled: true, persistence: "browser", state: "enabled" });
  assert.equal(requests, 0);
});

test("requests permission only from the explicit enable operation and persists disable", async () => {
  const storage = memoryStorage();
  let permission: WebNotificationPermission = "default";
  let requests = 0;
  const controller = new WebNotificationPreferenceController({
    supported: () => true,
    permission: () => permission,
    requestPermission: async () => { requests += 1; permission = "granted"; return permission; },
    storage,
  });

  assert.equal(requests, 0);
  assert.deepEqual(await controller.enableFromUserGesture(), {
    enabled: true, persistence: "browser", state: "enabled",
  });
  assert.equal(requests, 1);
  assert.equal(storage.values.get(WEB_NOTIFICATION_PREFERENCE_KEY), "true");
  assert.deepEqual(controller.disable(), { enabled: false, persistence: "browser", state: "disabled" });
  assert.equal(storage.values.get(WEB_NOTIFICATION_PREFERENCE_KEY), "false");
});

test("denied, revoked, unavailable, and storage-failure paths fail closed", async () => {
  const deniedStorage = memoryStorage("true");
  let permission: WebNotificationPermission = "denied";
  const denied = new WebNotificationPreferenceController({
    supported: () => true,
    permission: () => permission,
    requestPermission: async () => permission,
    storage: deniedStorage,
  });
  assert.equal(denied.snapshot.state, "denied");
  assert.equal(deniedStorage.values.get(WEB_NOTIFICATION_PREFERENCE_KEY), "false");
  assert.equal((await denied.enableFromUserGesture()).enabled, false);

  permission = "granted";
  const volatile = new WebNotificationPreferenceController({
    supported: () => true,
    permission: () => permission,
    requestPermission: async () => permission,
    storage: { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } },
  });
  assert.deepEqual(await volatile.enableFromUserGesture(), {
    enabled: true, persistence: "session", state: "enabled",
  });
  permission = "default";
  assert.equal(volatile.refreshPermission().enabled, false);

  const unavailable = new WebNotificationPreferenceController({
    supported: () => false,
    permission: () => "default",
    requestPermission: async () => "granted",
  });
  assert.equal(unavailable.snapshot.state, "unavailable");
  assert.equal((await unavailable.enableFromUserGesture()).enabled, false);
});
