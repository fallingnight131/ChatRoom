import assert from "node:assert/strict";
import test from "node:test";
import {
  applyDocumentLocale,
  chatShellMessages,
  friendListMessages,
  loginMessages,
  persistWebLocale,
  profileMessages,
  resolveWebLocale,
} from "../src/localization/webLocale";

class MemoryStorage {
  readonly values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
}

test("defaults invalid or unavailable Web locale state to Chinese", () => {
  assert.equal(resolveWebLocale(null), "zh-CN");
  const storage = new MemoryStorage();
  storage.values.set("chat.web.locale", "fr-FR");
  assert.equal(resolveWebLocale(storage), "zh-CN");
  assert.equal(resolveWebLocale({
    getItem() { throw new Error("denied"); },
    setItem() { throw new Error("denied"); },
  }), "zh-CN");
});

test("persists only supported locale identifiers and keeps catalogs aligned", () => {
  const storage = new MemoryStorage();
  assert.equal(persistWebLocale(storage, "en-US"), true);
  assert.equal(resolveWebLocale(storage), "en-US");
  assert.equal(persistWebLocale(storage, "de-DE"), false);
  assert.equal(resolveWebLocale(storage), "en-US");
  assert.deepEqual(
    Object.keys(loginMessages("en-US")).sort(),
    Object.keys(loginMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(chatShellMessages("en-US")).sort(),
    Object.keys(chatShellMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(profileMessages("en-US")).sort(),
    Object.keys(profileMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(friendListMessages("en-US")).sort(),
    Object.keys(friendListMessages("zh-CN")).sort(),
  );
});

test("applies the selected locale to the document language boundary", () => {
  const root = { lang: "" };
  applyDocumentLocale("en-US", root);
  assert.equal(root.lang, "en-US");
});
