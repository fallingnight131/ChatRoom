import assert from "node:assert/strict";
import test from "node:test";

import {
  decodeWebPushPayload,
  presentWebPushPayload,
  WEB_PUSH_MAX_PAYLOAD_BYTES,
} from "../src/platform/webPushPayload";

const payload = {
  version: 1,
  notificationId: "10000000-0000-4000-8000-000000000001",
  conversationId: "20000000-0000-4000-8000-000000000001",
  messageId: "30000000-0000-4000-8000-000000000001",
  mentioned: true,
};

test("accepts only the exact bounded identity-only Web Push payload", () => {
  assert.deepEqual(decodeWebPushPayload(JSON.stringify(payload)), payload);
  for (const invalid of [
    { ...payload, version: 2 },
    { ...payload, messageId: "not-a-uuid" },
    { ...payload, body: "secret message text" },
    { ...payload, mentioned: "true" },
  ]) assert.equal(decodeWebPushPayload(JSON.stringify(invalid)), null);
  assert.equal(decodeWebPushPayload("x".repeat(WEB_PUSH_MAX_PAYLOAD_BYTES + 1)), null);
  assert.equal(decodeWebPushPayload("not-json"), null);
});

test("builds generic same-origin presentation and stable opaque navigation", () => {
  const decoded = decodeWebPushPayload(JSON.stringify(payload));
  assert.ok(decoded);
  const presentation = presentWebPushPayload(decoded, {
    messageTitle: "New message",
    mentionTitle: "You were mentioned",
    body: "Open ChatRoom to view it.",
  }, "https://chat.example");
  assert.equal(presentation.title, "You were mentioned");
  assert.equal(presentation.options.body, "Open ChatRoom to view it.");
  assert.equal(presentation.options.tag, `chat-v2-push-${payload.notificationId}`);
  const target = new URL(presentation.options.data.navigationUrl);
  assert.equal(target.origin, "https://chat.example");
  assert.equal(target.hash.startsWith("#/preview/v2"), true);
  const query = new URLSearchParams(target.hash.split("?", 2)[1]);
  assert.equal(query.get("conversationId"), payload.conversationId);
  assert.throws(() => presentWebPushPayload(decoded, {
    messageTitle: "", mentionTitle: "", body: "",
  }, "http://chat.example"), /exact HTTPS origin/);
});
