import assert from "node:assert/strict";
import test from "node:test";

import { create } from "@bufbuild/protobuf";

import { V2WebChatApplication, type V2ConversationCacheMessage } from "../src/application/v2WebChatApplication";
import { AuthenticationRejectedSchema, SessionEstablishedSchema } from "../src/protocol/v2/generated/authentication_pb";
import {
  ConversationDirectoryPageSchema,
  ConversationKind,
  ConversationRole,
} from "../src/protocol/v2/generated/conversation_pb";
import { ProtocolErrorCode, ProtocolErrorSchema } from "../src/protocol/v2/generated/control_pb";
import {
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
} from "../src/protocol/v2/generated/messaging_pb";
import type { V2WebProtocolEvent } from "../src/protocol/v2/webProtocolClient";
import type {
  V2WebSocketTransportObserver,
  V2WebSocketTransportState,
} from "../src/protocol/v2/webSocketTransport";

const ACCOUNT_ID = "20000000-0000-4000-8000-000000000001";
const DEVICE_ID = "30000000-0000-4000-8000-000000000001";
const SESSION_ID = "40000000-0000-4000-8000-000000000001";
const CONVERSATION_ID = "50000000-0000-4000-8000-000000000001";
const SECOND_CONVERSATION_ID = "50000000-0000-4000-8000-000000000002";
const MESSAGE_ID = "60000000-0000-4000-8000-000000000001";
const SECOND_MESSAGE_ID = "60000000-0000-4000-8000-000000000002";
const CURSOR = "9007199254740993";
const NOW = 1_800_000_000_000;

class FakeTransport {
  state: V2WebSocketTransportState = "idle";
  observer: V2WebSocketTransportObserver | null = null;
  calls: unknown[][] = [];

  subscribe(observer: V2WebSocketTransportObserver): () => void {
    this.observer = observer;
    return () => { if (this.observer === observer) this.observer = null; };
  }

  start(): void { this.calls.push(["start"]); }
  stop(): void { this.calls.push(["stop"]); }
  authenticate(username: string, password: Uint8Array): void { this.calls.push(["authenticate", username, password]); }
  resumeSession(sessionId: string, token: Uint8Array): void { this.calls.push(["resume", sessionId, token]); }
  listConversations(limit: number, after?: unknown): void { this.calls.push(["directory", limit, after]); }
  readMessageHistory(conversationId: string, afterSequence: bigint, limit: number): void {
    this.calls.push(["history", conversationId, afterSequence, limit]);
  }
  submitText(conversationId: string, clientMessageId: string, text: string): void {
    this.calls.push(["submit", conversationId, clientMessageId, text]);
  }

  emit(event: V2WebProtocolEvent): void { this.observer?.onProtocolEvent?.(event); }
  transition(state: V2WebSocketTransportState): void {
    this.state = state;
    this.observer?.onStateChange?.(state);
  }
}

class FakeCache {
  readonly records = new Map<string, { messages: V2ConversationCacheMessage[]; cursorSequence: string }>();
  readonly saves: Array<{ accountId: string; conversationId: string; messages: V2ConversationCacheMessage[]; cursor: string }> = [];

  async loadV2(accountId: string, conversationId: string) {
    const value = this.records.get(`${accountId}:${conversationId}`);
    return value ? structuredClone(value) : null;
  }

  async saveV2(accountId: string, conversationId: string, messages: V2ConversationCacheMessage[], cursor: string) {
    this.saves.push({ accountId, conversationId, messages: structuredClone(messages), cursor });
    this.records.set(`${accountId}:${conversationId}`, { messages: structuredClone(messages), cursorSequence: cursor });
    return true;
  }
}

function correlated<T extends V2WebProtocolEvent>(event: Omit<T, "requestId" | "clientMessageId">, clientMessageId = ""): T {
  return { ...event, requestId: crypto.randomUUID(), clientMessageId } as T;
}

function establish(transport: FakeTransport): void {
  transport.emit(correlated({
    type: "session-established",
    value: create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: new Uint8Array(32),
      expiresAtEpochMs: BigInt(NOW + 60_000),
      displayName: "Alice",
    }),
  }));
}

function directory(transport: FakeTransport, hasMore = false): void {
  transport.emit(correlated({
    type: "conversation-directory-page",
    value: create(ConversationDirectoryPageSchema, {
      conversations: [{
        conversationId: CONVERSATION_ID,
        kind: ConversationKind.DIRECT,
        displayName: "Bob",
        role: ConversationRole.MEMBER,
        latestSequence: 9_007_199_254_741_000n,
        lastReadSequence: 9_007_199_254_740_999n,
        updatedAtEpochMs: BigInt(NOW),
      }],
      nextUpdatedAtEpochMs: BigInt(NOW),
      nextConversationId: CONVERSATION_ID,
      hasMore,
    }),
  }));
}

function cachedMessage(overrides: Partial<V2ConversationCacheMessage> = {}): V2ConversationCacheMessage {
  return {
    conversationId: CONVERSATION_ID,
    id: MESSAGE_ID,
    clientMessageId: "cached-client",
    senderAccountId: ACCOUNT_ID,
    senderDeviceId: DEVICE_ID,
    sequence: CURSOR,
    acceptedAtEpochMs: NOW - 1,
    content: "cached",
    contentType: "text",
    deliveryState: "accepted",
    errorCode: "",
    ...overrides,
  };
}

test("hydrates exact cursor, synchronizes forward, and paginates directory/history", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage()],
    cursorSequence: CURSOR,
  });
  const snapshots: number[] = [];
  const application = new V2WebChatApplication({
    transport,
    cache,
    onChange: (snapshot) => snapshots.push(snapshot.messages.length),
  });
  establish(transport);
  assert.deepEqual(transport.calls.at(-1), ["directory", 50, undefined]);
  directory(transport, true);
  assert.equal(application.snapshot.directory[0]?.latestSequence, "9007199254741000");
  assert.equal(application.loadMoreDirectory(), true);
  assert.deepEqual(transport.calls.at(-1), ["directory", 50, {
    updatedAtEpochMs: BigInt(NOW), conversationId: CONVERSATION_ID,
  }]);

  await application.openConversation(CONVERSATION_ID);
  assert.equal(application.snapshot.messages[0]?.content, "cached");
  assert.deepEqual(transport.calls.at(-1), ["history", CONVERSATION_ID, BigInt(CURSOR), 100]);

  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      messages: [{
        conversationId: CONVERSATION_ID,
        messageId: SECOND_MESSAGE_ID,
        conversationSequence: BigInt(CURSOR) + 1n,
        senderAccountId: ACCOUNT_ID,
        senderDeviceId: DEVICE_ID,
        clientMessageId: "server-client-2",
        contentType: MessageContentType.TEXT_UTF8,
        content: new TextEncoder().encode("new"),
        acceptedAtEpochMs: BigInt(NOW),
      }],
      nextSequence: BigInt(CURSOR) + 1n,
      latestSequence: BigInt(CURSOR) + 2n,
      hasMore: true,
    }),
  }));
  assert.equal(application.snapshot.messages.length, 2);
  assert.equal(application.snapshot.historyLoading, true);
  assert.deepEqual(transport.calls.at(-1), ["history", CONVERSATION_ID, BigInt(CURSOR) + 1n, 100]);
  assert.ok(cache.saves.some((save) => save.cursor === (BigInt(CURSOR) + 1n).toString()));
  assert.ok(snapshots.length > 0);
  application.dispose();
});

test("reconciles optimistic acceptance without skipping the contiguous history cursor", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage()],
    cursorSequence: CURSOR,
  });
  let nextClient = 0;
  const application = new V2WebChatApplication({
    transport,
    cache,
    createClientMessageId: () => `client-${++nextClient}`,
    now: () => NOW,
  });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);
  const optimistic = application.sendText("hello");
  assert.equal(optimistic.deliveryState, "sending");
  assert.deepEqual(transport.calls.at(-1), ["submit", CONVERSATION_ID, "client-1", "hello"]);

  transport.emit(correlated({
    type: "message-accepted",
    value: create(MessageAcceptedSchema, {
      conversationId: CONVERSATION_ID,
      messageId: SECOND_MESSAGE_ID,
      conversationSequence: BigInt(CURSOR) + 20n,
      acceptedAtEpochMs: BigInt(NOW + 1),
      duplicate: false,
    }),
  }, "client-1"));
  const accepted = application.snapshot.messages.find((message) => message.clientMessageId === "client-1");
  assert.equal(accepted?.deliveryState, "accepted");
  assert.equal(accepted?.sequence, (BigInt(CURSOR) + 20n).toString());
  assert.equal(cache.saves.at(-1)?.cursor, CURSOR, "ACK is not a contiguous sync cursor");

  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      messages: [{
        conversationId: CONVERSATION_ID,
        messageId: SECOND_MESSAGE_ID,
        conversationSequence: BigInt(CURSOR) + 20n,
        senderAccountId: ACCOUNT_ID,
        senderDeviceId: DEVICE_ID,
        clientMessageId: "client-1",
        contentType: MessageContentType.TEXT_UTF8,
        content: new TextEncoder().encode("hello"),
        acceptedAtEpochMs: BigInt(NOW + 1),
      }],
      nextSequence: BigInt(CURSOR) + 20n,
      latestSequence: BigInt(CURSOR) + 20n,
      hasMore: false,
    }),
  }));
  assert.equal(application.snapshot.messages.filter((message) => message.clientMessageId === "client-1").length, 1);
  assert.equal(cache.saves.at(-1)?.cursor, (BigInt(CURSOR) + 20n).toString());

  application.sendText("retry me");
  transport.emit(correlated({
    type: "protocol-error",
    value: create(ProtocolErrorSchema, {
      code: ProtocolErrorCode.RATE_LIMITED,
      safeMessage: "retry later",
      retryable: true,
    }),
  }, "client-2"));
  assert.equal(application.snapshot.messages.find((message) => message.clientMessageId === "client-2")?.deliveryState, "failed");
  assert.equal(application.retryMessage("client-2"), true);
  assert.deepEqual(transport.calls.at(-1), ["submit", CONVERSATION_ID, "client-2", "retry me"]);
  application.dispose();
});

test("ignores stale cache completion after a rapid conversation switch and clears state on disposal", async () => {
  const transport = new FakeTransport();
  let resolveFirst: ((value: { messages: V2ConversationCacheMessage[]; cursorSequence: string }) => void) | undefined;
  const cache = {
    loadV2: (_accountId: string, conversationId: string) => conversationId === CONVERSATION_ID
      ? new Promise<{ messages: V2ConversationCacheMessage[]; cursorSequence: string }>((resolve) => { resolveFirst = resolve; })
      : Promise.resolve({ messages: [cachedMessage({ conversationId: SECOND_CONVERSATION_ID, content: "second" })], cursorSequence: "2" }),
    saveV2: async () => true,
  };
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  transport.emit(correlated({
    type: "conversation-directory-page",
    value: create(ConversationDirectoryPageSchema, {
      conversations: [{
        conversationId: SECOND_CONVERSATION_ID,
        kind: ConversationKind.GROUP,
        displayName: "Team",
        role: ConversationRole.MEMBER,
        latestSequence: 2n,
        updatedAtEpochMs: BigInt(NOW - 1),
      }],
      nextUpdatedAtEpochMs: BigInt(NOW - 1),
      nextConversationId: SECOND_CONVERSATION_ID,
    }),
  }));
  const first = application.openConversation(CONVERSATION_ID);
  await application.openConversation(SECOND_CONVERSATION_ID);
  resolveFirst?.({ messages: [cachedMessage({ content: "stale" })], cursorSequence: "1" });
  await first;
  assert.equal(application.snapshot.activeConversationId, SECOND_CONVERSATION_ID);
  assert.equal(application.snapshot.messages[0]?.content, "second");
  application.dispose();
  assert.equal(transport.observer, null);
  assert.deepEqual(transport.calls.at(-1), ["stop"]);
});

test("surfaces generic authentication rejection without retaining session secrets", () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  transport.emit(correlated({
    type: "authentication-rejected",
    value: create(AuthenticationRejectedSchema, { retryAfterMs: 1000n }),
  }));
  assert.equal(application.snapshot.lastFailure, "Authentication rejected");
  assert.equal(application.snapshot.session, null);
  application.dispose();
});
