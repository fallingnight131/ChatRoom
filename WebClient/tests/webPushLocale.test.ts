import assert from "node:assert/strict";
import test from "node:test";
import {
  loadWebPushGenericCopy,
  persistWebPushLocale,
  WEB_PUSH_LOCALE_CACHE,
} from "../src/platform/webPushLocale";

function storageFixture() {
  const records = new Map<string, Response>();
  const storage = { async open(name: string) {
    assert.equal(name, WEB_PUSH_LOCALE_CACHE);
    return {
      async put(key: RequestInfo | URL, response: Response) {
        records.set(String(key), response.clone());
      },
      async match(key: RequestInfo | URL) { return records.get(String(key))?.clone(); },
    };
  } } as unknown as CacheStorage;
  return { records, storage };
}

test("persists only a supported locale and loads a defensive generic copy", async () => {
  const fixture = storageFixture();
  assert.equal(await persistWebPushLocale("en-US", "https://chat.example", fixture.storage), true);
  assert.equal(await persistWebPushLocale("fr-FR", "https://chat.example", fixture.storage), false);
  const loaded = await loadWebPushGenericCopy("https://chat.example", fixture.storage);
  assert.equal(loaded.messageTitle, "New ChatRoom message");
  loaded.messageTitle = "changed";
  assert.equal((await loadWebPushGenericCopy("https://chat.example", fixture.storage)).messageTitle,
    "New ChatRoom message");
});

test("fails closed on unsafe persistence and falls back to generic Chinese copy", async () => {
  const fixture = storageFixture();
  assert.equal(await persistWebPushLocale("zh-CN", "http://chat.example", fixture.storage), false);
  fixture.records.set("https://chat.example/.well-known/chatroom-web-push-locale",
    new Response("malformed"));
  assert.equal((await loadWebPushGenericCopy("https://chat.example", fixture.storage)).messageTitle,
    "ChatRoom 新消息");
});
