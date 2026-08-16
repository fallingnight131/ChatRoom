import assert from "node:assert/strict";
import test from "node:test";
import {
  applyDocumentLocale,
  chatShellMessages,
  composerMessages,
  emojiPickerMessages,
  friendListMessages,
  loginMessages,
  memberListMessages,
  messageTimelineMessages,
  messageAttachmentMessages,
  messageActionMessages,
  filePreviewMessages,
  forwardDialogMessages,
  downloadPanelMessages,
  userInfoMessages,
  roomPasswordMessages,
  roomFileManagerMessages,
  roomSettingsMessages,
  v2PreviewShellMessages,
  v2PreviewSearchMessages,
  v2PreviewTimelineMessages,
  persistWebLocale,
  profileMessages,
  roomListMessages,
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
  assert.deepEqual(
    Object.keys(roomListMessages("en-US")).sort(),
    Object.keys(roomListMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(memberListMessages("en-US")).sort(),
    Object.keys(memberListMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(composerMessages("en-US")).sort(),
    Object.keys(composerMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(emojiPickerMessages("en-US")).sort(),
    Object.keys(emojiPickerMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(messageTimelineMessages("en-US")).sort(),
    Object.keys(messageTimelineMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(messageAttachmentMessages("en-US")).sort(),
    Object.keys(messageAttachmentMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(messageActionMessages("en-US")).sort(),
    Object.keys(messageActionMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(filePreviewMessages("en-US")).sort(),
    Object.keys(filePreviewMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(forwardDialogMessages("en-US")).sort(),
    Object.keys(forwardDialogMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(downloadPanelMessages("en-US")).sort(),
    Object.keys(downloadPanelMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(userInfoMessages("en-US")).sort(),
    Object.keys(userInfoMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(roomPasswordMessages("en-US")).sort(),
    Object.keys(roomPasswordMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(roomFileManagerMessages("en-US")).sort(),
    Object.keys(roomFileManagerMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(roomSettingsMessages("en-US")).sort(),
    Object.keys(roomSettingsMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(v2PreviewShellMessages("en-US")).sort(),
    Object.keys(v2PreviewShellMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(v2PreviewSearchMessages("en-US")).sort(),
    Object.keys(v2PreviewSearchMessages("zh-CN")).sort(),
  );
  assert.deepEqual(
    Object.keys(v2PreviewTimelineMessages("en-US")).sort(),
    Object.keys(v2PreviewTimelineMessages("zh-CN")).sort(),
  );
});

test("applies the selected locale to the document language boundary", () => {
  const root = { lang: "" };
  applyDocumentLocale("en-US", root);
  assert.equal(root.lang, "en-US");
});
