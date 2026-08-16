import assert from "node:assert/strict";
import test from "node:test";

import {
  V2_DEVICE_ID_STORAGE_KEY,
  createConfiguredV2Runtime,
} from "../src/application/v2Runtime";

const DEVICE_ID = "70000000-0000-4000-8000-000000000001";
const OTHER_DEVICE_ID = "70000000-0000-4000-8000-000000000002";
const ENABLED_ENVIRONMENT = {
  VITE_CHAT_V2_PREVIEW: "true",
  VITE_CHAT_V2_WSS_URL: "wss://chat.example/v2/web",
  VITE_CHAT_APP_VERSION: "2.0.0-preview",
};

class MemoryStorage {
  readonly values = new Map<string, string>();
  reads = 0;
  writes = 0;

  getItem(key: string): string | null {
    this.reads += 1;
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.writes += 1;
    this.values.set(key, value);
  }
}

test("keeps V2 disabled by default without touching browser storage", () => {
  const storage = new MemoryStorage();
  let generated = 0;
  for (const environment of [
    {},
    { VITE_CHAT_V2_PREVIEW: "" },
    { VITE_CHAT_V2_PREVIEW: "false" },
    { VITE_CHAT_V2_PREVIEW: false },
  ]) {
    const runtime = createConfiguredV2Runtime(environment, {
      storage,
      createUuid: () => { generated += 1; return DEVICE_ID; },
    });
    assert.equal(runtime.enabled, false);
  }
  assert.equal(storage.reads, 0);
  assert.equal(storage.writes, 0);
  assert.equal(generated, 0);
});

test("fails closed for incomplete or unsafe preview configuration", () => {
  const cases = [
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_PREVIEW: "TRUE" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_URL: "" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_APP_VERSION: "" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_URL: "ws://chat.example/v2/web" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_URL: "wss://chat.example/socket" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_FALLBACK_URLS: true },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_FALLBACK_URLS: "not-json" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_FALLBACK_URLS: "{}" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_FALLBACK_URLS: '["ws://edge-b.example/v2/web"]' },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_WSS_FALLBACK_URLS: '["wss://chat.example/v2/web"]' },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_APP_VERSION: "x".repeat(65) },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_MESSAGE_FORWARDING: "TRUE" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_MESSAGE_SEARCH: "TRUE" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_ACCOUNT_BLOCKING: "TRUE" },
    { ...ENABLED_ENVIRONMENT, VITE_CHAT_V2_NOTIFICATIONS: "TRUE" },
  ];
  for (const environment of cases) {
    const runtime = createConfiguredV2Runtime(environment, { storage: null, createUuid: () => DEVICE_ID });
    assert.equal(runtime.enabled, false);
  }
});

test("accepts a bounded build-time V2 fallback endpoint list", () => {
  const runtime = createConfiguredV2Runtime({
    ...ENABLED_ENVIRONMENT,
    VITE_CHAT_V2_WSS_FALLBACK_URLS: '["wss://edge-b.example/v2/web"]',
  }, { storage: null, createUuid: () => DEVICE_ID });
  assert.equal(runtime.enabled, true);
  runtime.dispose();
});

test("creates an inert enabled runtime and persists a stable non-secret device identifier", () => {
  const storage = new MemoryStorage();
  const runtime = createConfiguredV2Runtime(ENABLED_ENVIRONMENT, {
    storage,
    createUuid: () => DEVICE_ID,
  });
  assert.equal(runtime.enabled, true);
  assert.equal(runtime.enabled && runtime.deviceIdentity, "persistent");
  assert.equal(storage.values.get(V2_DEVICE_ID_STORAGE_KEY), DEVICE_ID);
  assert.equal(runtime.enabled && runtime.application.snapshot.connectionState, "idle");
  assert.equal(runtime.enabled && runtime.application.snapshot.forwardingEnabled, false);
  assert.equal(runtime.enabled && runtime.application.snapshot.searchEnabled, false);
  assert.equal(runtime.enabled && runtime.application.snapshot.notificationsEnabled, false);
  assert.equal(runtime.enabled && runtime.application.snapshot.accountBlockingEnabled, false);
  runtime.dispose();
});

test("activates forwarding only for the exact independent build flag", () => {
  const runtime = createConfiguredV2Runtime({
    ...ENABLED_ENVIRONMENT,
    VITE_CHAT_V2_MESSAGE_FORWARDING: "true",
  }, { storage: null, createUuid: () => DEVICE_ID });
  assert.equal(runtime.enabled, true);
  assert.equal(runtime.enabled && runtime.application.snapshot.forwardingEnabled, true);
  runtime.dispose();
});

test("activates search only for the exact independent build flag", () => {
  const runtime = createConfiguredV2Runtime({
    ...ENABLED_ENVIRONMENT,
    VITE_CHAT_V2_MESSAGE_SEARCH: "true",
  }, { storage: null, createUuid: () => DEVICE_ID });
  assert.equal(runtime.enabled, true);
  assert.equal(runtime.enabled && runtime.application.snapshot.searchEnabled, true);
  runtime.dispose();
});

test("activates notifications only for the exact independent build flag", () => {
  const runtime = createConfiguredV2Runtime({
    ...ENABLED_ENVIRONMENT,
    VITE_CHAT_V2_NOTIFICATIONS: "true",
  }, { storage: null, createUuid: () => DEVICE_ID });
  assert.equal(runtime.enabled, true);
  assert.equal(runtime.enabled && runtime.application.snapshot.notificationsEnabled, true);
  runtime.dispose();
});

test("activates account blocking only for the exact independent build flag", () => {
  const runtime = createConfiguredV2Runtime({
    ...ENABLED_ENVIRONMENT,
    VITE_CHAT_V2_ACCOUNT_BLOCKING: "true",
  }, { storage: null, createUuid: () => DEVICE_ID });
  assert.equal(runtime.enabled, true);
  assert.equal(runtime.enabled && runtime.application.snapshot.accountBlockingEnabled, true);
  runtime.dispose();
});

test("reuses a valid device identifier without generating a replacement", () => {
  const storage = new MemoryStorage();
  storage.values.set(V2_DEVICE_ID_STORAGE_KEY, DEVICE_ID);
  let generated = 0;
  const runtime = createConfiguredV2Runtime(ENABLED_ENVIRONMENT, {
    storage,
    createUuid: () => { generated += 1; return OTHER_DEVICE_ID; },
  });
  assert.equal(runtime.enabled, true);
  assert.equal(generated, 0);
  assert.equal(storage.writes, 0);
  runtime.dispose();
});

test("falls back to page-ephemeral identity when browser storage is denied", () => {
  const runtime = createConfiguredV2Runtime(ENABLED_ENVIRONMENT, {
    storage: {
      getItem: () => { throw new Error("denied"); },
      setItem: () => { throw new Error("denied"); },
    },
    createUuid: () => DEVICE_ID,
  });
  assert.equal(runtime.enabled, true);
  assert.equal(runtime.enabled && runtime.deviceIdentity, "ephemeral");
  runtime.dispose();
});

test("contains a bad UUID generator as an invalid preview configuration", () => {
  const runtime = createConfiguredV2Runtime(ENABLED_ENVIRONMENT, {
    storage: null,
    createUuid: () => "not-a-uuid",
  });
  assert.equal(runtime.enabled, false);
  assert.match(runtime.enabled ? "" : runtime.reason, /configuration is invalid/);
});
