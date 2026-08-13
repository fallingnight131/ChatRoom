import assert from "node:assert/strict";
import test from "node:test";

import {
  anchorsFromMentionSpans,
  insertMention,
  reconcileMentionEdit,
  segmentMentionText,
  serializeMentionAnchors,
} from "../src/application/v2MentionComposer";

const ALICE = "20000000-0000-4000-8000-000000000001";

test("inserts Unicode mentions and serializes exact UTF-8 byte spans", () => {
  const inserted = insertMention("你好 ", [], 3, 3, {
    accountId: ALICE, displayName: "李雷",
  });
  assert.equal(inserted.text, "你好 @李雷 ");
  assert.deepEqual(serializeMentionAnchors(inserted.text, inserted.anchors), [{
    targetAccountId: ALICE,
    startUtf8Byte: 7,
    lengthUtf8Bytes: 7,
  }]);
});

test("shifts anchors for edits outside a token and drops overlapping edits", () => {
  const initial = insertMention("hello", [], 0, 0, {
    accountId: ALICE, displayName: "Alice",
  });
  const shifted = reconcileMentionEdit(initial.text, `前${initial.text}`, initial.anchors);
  assert.equal(shifted[0]?.startUtf16, 1);
  const changed = `前${initial.text}`.replace("Alice", "Alicia");
  assert.deepEqual(reconcileMentionEdit(`前${initial.text}`, changed, shifted), []);
});

test("round trips stored spans and renders mention segments without parsing identity", () => {
  const text = "hi @李!";
  const mentions = [{ targetAccountId: ALICE, startUtf8Byte: 3, lengthUtf8Bytes: 4 }];
  assert.deepEqual(serializeMentionAnchors(
    text, anchorsFromMentionSpans(text, mentions)), mentions);
  assert.deepEqual(segmentMentionText(text, mentions), [
    { kind: "text", text: "hi " },
    { kind: "mention", text: "@李", targetAccountId: ALICE },
    { kind: "text", text: "!" },
  ]);
});
