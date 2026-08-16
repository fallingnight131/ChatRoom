import assert from "node:assert/strict";
import test from "node:test";

import {
  WebMessageNotificationPolicy,
  WebMessageNotificationPresenter,
  type WebMessageNotificationCandidate,
  type WebNotificationHandle,
} from "../src/platform/webMessageNotification";

const ACCOUNT_ID = "10000000-0000-4000-8000-000000000001";
const SENDER_ID = "10000000-0000-4000-8000-000000000002";
const CONVERSATION_ID = "20000000-0000-4000-8000-000000000001";
const OTHER_CONVERSATION_ID = "20000000-0000-4000-8000-000000000002";

function candidate(messageId: string, overrides: Partial<WebMessageNotificationCandidate> = {}) {
  return {
    messageId, conversationId: CONVERSATION_ID, senderAccountId: SENDER_ID,
    authenticatedAccountId: ACCOUNT_ID, authenticatedAccountMentioned: false,
    ...overrides,
  };
}

test("bounds remote live-message notification identity and suppresses visible duplicates", () => {
  const policy = new WebMessageNotificationPolicy(2);
  const firstId = "30000000-0000-4000-8000-000000000001";
  const secondId = "30000000-0000-4000-8000-000000000002";
  const thirdId = "30000000-0000-4000-8000-000000000003";

  assert.deepEqual(policy.evaluate(candidate(firstId), {
    applicationActive: false, visibleConversationId: "",
  }), { show: true, kind: "message", conversationId: CONVERSATION_ID, messageId: firstId });
  assert.deepEqual(policy.evaluate(candidate(firstId), {
    applicationActive: false, visibleConversationId: "",
  }), { show: false });
  assert.deepEqual(policy.evaluate(candidate(secondId), {
    applicationActive: true, visibleConversationId: CONVERSATION_ID,
  }), { show: false });
  assert.equal(policy.rememberedMessageCount, 2);
  assert.deepEqual(policy.evaluate(candidate(thirdId, {
    conversationId: OTHER_CONVERSATION_ID, authenticatedAccountMentioned: true,
  }), { applicationActive: true, visibleConversationId: CONVERSATION_ID }), {
    show: true, kind: "mention", conversationId: OTHER_CONVERSATION_ID, messageId: thirdId,
  });
  assert.equal(policy.rememberedMessageCount, 2);
  assert.equal(policy.evaluate(candidate(firstId), {
    applicationActive: false, visibleConversationId: "",
  }).show, true);
  policy.clear();
  assert.equal(policy.rememberedMessageCount, 0);
});

test("rejects malformed and self-authored candidates without consuming memory", () => {
  const policy = new WebMessageNotificationPolicy();
  assert.equal(policy.evaluate(candidate("not-a-uuid"), {
    applicationActive: false, visibleConversationId: "",
  }).show, false);
  assert.equal(policy.evaluate(candidate("30000000-0000-4000-8000-000000000001", {
    senderAccountId: ACCOUNT_ID,
  }), { applicationActive: false, visibleConversationId: "" }).show, false);
  assert.equal(policy.rememberedMessageCount, 0);
  assert.throws(() => new WebMessageNotificationPolicy(0), /memory bound/);
});

test("isolates platform failure and consumes one stable activation target", () => {
  let permission: "denied" | "granted" = "denied";
  let handle: WebNotificationHandle | null = null;
  const created: Array<{ title: string; body: string; tag: string }> = [];
  const activated: string[] = [];
  const presenter = new WebMessageNotificationPresenter({
    permission: () => permission,
    create: (title, options) => {
      created.push({ title, ...options });
      handle = { onclick: null, close() {} };
      return handle;
    },
    activateConversation: conversationId => activated.push(conversationId),
  });
  const copy = { messageTitle: "New message", mentionTitle: "You were mentioned", body: "Open ChatRoom" };
  assert.equal(presenter.present(candidate("30000000-0000-4000-8000-000000000001"), {
    applicationActive: false, visibleConversationId: "",
  }, copy), false);

  permission = "granted";
  const mentionId = "30000000-0000-4000-8000-000000000002";
  assert.equal(presenter.present(candidate(mentionId, { authenticatedAccountMentioned: true }), {
    applicationActive: false, visibleConversationId: "",
  }, copy), true);
  assert.deepEqual(created, [{
    title: "You were mentioned", body: "Open ChatRoom", tag: `chat-v2-message-${mentionId}`,
  }]);
  const click = handle!.onclick;
  assert.ok(click);
  click!();
  click!();
  assert.deepEqual(activated, [CONVERSATION_ID]);

  const failing = new WebMessageNotificationPresenter({
    permission: () => "granted",
    create: () => { throw new Error("platform unavailable"); },
    activateConversation: () => { throw new Error("must not activate"); },
  });
  assert.equal(failing.present(candidate("30000000-0000-4000-8000-000000000003"), {
    applicationActive: false, visibleConversationId: "",
  }, copy), false);
});
