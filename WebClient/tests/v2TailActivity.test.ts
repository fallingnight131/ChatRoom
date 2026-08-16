import assert from "node:assert/strict";
import test from "node:test";

import {
  classifyV2TailUpdate,
  countV2TailAdditions,
  type V2TailMessage,
  type V2TailSnapshot,
} from "../src/messaging/v2TailActivity";

function message(overrides: Partial<V2TailMessage> = {}): V2TailMessage {
  return {
    id: "server-1",
    clientMessageId: "client-1",
    sequence: "1",
    deliveryState: "accepted",
    ...overrides,
  };
}

test("counts only unknown accepted messages beyond the known server tail", () => {
  const previous = [message()];
  const next = [
    message(),
    message({ id: "older", clientMessageId: "", sequence: "1" }),
    message({ id: "server-2", clientMessageId: "client-2", sequence: "2" }),
    message({ id: "server-3", clientMessageId: "client-3", sequence: "3" }),
  ];
  assert.equal(countV2TailAdditions(previous, next), 2);
})

test("does not count an authoritative ACK for an existing optimistic client message", () => {
  const optimistic = message({ id: "", sequence: "0", deliveryState: "sending" });
  const accepted = message({ id: "server-9", sequence: "9", deliveryState: "accepted" });
  assert.equal(countV2TailAdditions([optimistic], [accepted]), 0);
})

test("counts a new optimistic send once and rejects malformed or duplicate identities", () => {
  const optimistic = message({ id: "", clientMessageId: "client-new", sequence: "0", deliveryState: "failed" });
  const malformed = message({ id: "bad", clientMessageId: "", sequence: "not-a-sequence" });
  assert.equal(countV2TailAdditions([], [optimistic, optimistic, malformed]), 1);
})

test("keeps context or history records at and below the current tail out of the count", () => {
  const previous = [message({ id: "tail", clientMessageId: "", sequence: "100" })];
  const repaired = [
    message({ id: "context-40", clientMessageId: "", sequence: "40" }),
    message({ id: "context-99", clientMessageId: "", sequence: "99" }),
    ...previous,
  ];
  assert.equal(countV2TailAdditions(previous, repaired), 0);
})

test("compares exact server sequences beyond the JavaScript safe-integer range", () => {
  const previous = [message({ id: "huge-tail", clientMessageId: "", sequence: "90071992547409920" })];
  const next = [
    ...previous,
    message({ id: "huge-next", clientMessageId: "", sequence: "90071992547409921" }),
  ];
  assert.equal(countV2TailAdditions(previous, next), 1);
})

function snapshot(overrides: Partial<V2TailSnapshot> = {}): V2TailSnapshot {
  return {
    activeConversationId: "conversation-1",
    messages: [],
    historyLoading: false,
    searchContextLoading: false,
    ...overrides,
  };
}

test("resets on conversation switch and suppresses initial history hydration", () => {
  assert.deepEqual(classifyV2TailUpdate(
    snapshot({ activeConversationId: "conversation-1", messages: [message()] }),
    snapshot({ activeConversationId: "conversation-2", messages: [message({ id: "other" })] }),
  ), { conversationChanged: true, additions: 0 });
  assert.deepEqual(classifyV2TailUpdate(
    snapshot({ historyLoading: true }),
    snapshot({ messages: [message()], historyLoading: false }),
  ), { conversationChanged: false, additions: 0 });
})

test("suppresses search repair but retains reconnect additions above an existing tail", () => {
  const previous = snapshot({ messages: [message()], searchContextLoading: true });
  const repaired = snapshot({ messages: [
    message({ id: "older", clientMessageId: "", sequence: "0" }),
    message(),
  ] });
  assert.deepEqual(classifyV2TailUpdate(previous, repaired), {
    conversationChanged: false, additions: 0,
  });

  const reconnecting = snapshot({ messages: [message()], historyLoading: true });
  const caughtUp = snapshot({ messages: [
    message(),
    message({ id: "server-2", clientMessageId: "client-2", sequence: "2" }),
  ] });
  assert.deepEqual(classifyV2TailUpdate(reconnecting, caughtUp), {
    conversationChanged: false, additions: 1,
  });
})
