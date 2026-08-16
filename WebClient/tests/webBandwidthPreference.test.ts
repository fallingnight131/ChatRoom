import assert from "node:assert/strict";
import test from "node:test";

import {
  LOW_BANDWIDTH_STORAGE_KEY,
  persistBandwidthPreference,
  resolveBandwidthPreference,
  shouldAutoRequestAvatar,
} from "../src/preferences/webBandwidthPreference";

test("uses an explicit user choice before the browser data-saver hint", () => {
  const enabled = { getItem: () => "true", setItem() {} };
  const disabled = { getItem: () => "false", setItem() {} };
  assert.deepEqual(resolveBandwidthPreference(enabled, { saveData: false }), {
    enabled: true, source: "user",
  });
  assert.deepEqual(resolveBandwidthPreference(disabled, { saveData: true }), {
    enabled: false, source: "user",
  });
})

test("defaults to browser data saver without treating malformed storage as a choice", () => {
  const malformed = { getItem: () => "enabled", setItem() {} };
  assert.deepEqual(resolveBandwidthPreference(malformed, { saveData: true }), {
    enabled: true, source: "browser",
  });
  assert.deepEqual(resolveBandwidthPreference(null, null), {
    enabled: false, source: "default",
  });
})

test("contains denied storage and reports whether the choice persisted", () => {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
  };
  assert.equal(persistBandwidthPreference(storage, true), true);
  assert.equal(values.get(LOW_BANDWIDTH_STORAGE_KEY), "true");
  assert.equal(persistBandwidthPreference({
    getItem: () => null,
    setItem: () => { throw new Error("denied"); },
  }, false), false);
})

test("permits one automatic avatar request only outside low-bandwidth mode", () => {
  assert.equal(shouldAutoRequestAvatar("alice", false, false), true);
  assert.equal(shouldAutoRequestAvatar("alice", true, false), false);
  assert.equal(shouldAutoRequestAvatar("alice", false, true), false);
  assert.equal(shouldAutoRequestAvatar("", false, false), false);
})
