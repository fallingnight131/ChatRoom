import assert from "node:assert/strict";
import test from "node:test";

import { create } from "@bufbuild/protobuf";

import { V2WebChatApplication, type V2ConversationCacheMessage } from "../src/application/v2WebChatApplication";
import { AuthenticationRejectedSchema, SessionEstablishedSchema } from "../src/protocol/v2/generated/authentication_pb";
import {
  ConversationDirectoryPageSchema,
  ConversationParticipantPageSchema,
  ConversationKind,
  ConversationRole,
} from "../src/protocol/v2/generated/conversation_pb";
import { ProtocolErrorCode, ProtocolErrorSchema } from "../src/protocol/v2/generated/control_pb";
import { ClientPlatform } from "../src/protocol/v2/generated/control_pb";
import { DeviceDirectorySchema, DeviceRevokedSchema } from "../src/protocol/v2/generated/device_management_pb";
import {
  ConversationEntryRecordSchema,
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
  MessageRecordSchema,
  MessageRecalledRecordSchema,
  MessagesDeletedRecordSchema,
  MessageReactionKind,
  MessageReactionAppliedSchema,
  MessageReactionChangedRecordSchema,
  MessagePinAppliedSchema,
  MessagePinChangedRecordSchema,
  MessageEditAppliedSchema,
  MessageEditedRecordSchema,
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
  mentionCalls: Array<{ kind: "submit" | "reply" | "edit"; mentions: unknown[] }> = [];

  subscribe(observer: V2WebSocketTransportObserver): () => void {
    this.observer = observer;
    return () => { if (this.observer === observer) this.observer = null; };
  }

  start(): void { this.calls.push(["start"]); }
  stop(): void { this.calls.push(["stop"]); }
  authenticate(username: string, password: Uint8Array): void { this.calls.push(["authenticate", username, password]); }
  resumeSession(sessionId: string, token: Uint8Array): void { this.calls.push(["resume", sessionId, token]); }
  listConversations(limit: number, after?: unknown): void { this.calls.push(["directory", limit, after]); }
  listConversationParticipants(conversationId: string, limit: number, afterAccountId = ""): string {
    const requestId = `participants-${this.calls.length}`;
    this.calls.push(["participants", conversationId, limit, afterAccountId, requestId]);
    return requestId;
  }
  readMessageHistory(conversationId: string, afterSequence: bigint, limit: number): void {
    this.calls.push(["history", conversationId, afterSequence, limit]);
  }
  submitText(conversationId: string, clientMessageId: string, text: string,
    mentions: readonly import("../src/application/v2WebChatApplication").V2ConversationMention[] = []): void {
    this.calls.push(["submit", conversationId, clientMessageId, text]);
    this.mentionCalls.push({ kind: "submit", mentions: mentions.map((value) => ({ ...value })) });
  }
  submitReply(
    conversationId: string,
    targetMessageId: string,
    clientMessageId: string,
    text: string,
    mentions: readonly import("../src/application/v2WebChatApplication").V2ConversationMention[] = [],
  ): void {
    this.calls.push(["reply", conversationId, targetMessageId, clientMessageId, text]);
    this.mentionCalls.push({ kind: "reply", mentions: mentions.map((value) => ({ ...value })) });
  }
  setMessageReaction(
    conversationId: string,
    messageId: string,
    reaction: MessageReactionKind,
    active: boolean,
    clientOperationId: string,
  ): string {
    const requestId = `reaction-${this.calls.length}`;
    this.calls.push(["reaction", conversationId, messageId, reaction, active,
      clientOperationId, requestId]);
    return requestId;
  }
  setMessagePin(conversationId: string, messageId: string, pinned: boolean,
    clientOperationId: string): string {
    const requestId = `pin-${this.calls.length}`;
    this.calls.push(["pin", conversationId, messageId, pinned, clientOperationId, requestId]);
    return requestId;
  }
  editMessage(conversationId: string, messageId: string, expectedRevision: number,
    text: string, clientOperationId: string,
    mentions: readonly import("../src/application/v2WebChatApplication").V2ConversationMention[] = []): string {
    const requestId = `edit-${this.calls.length}`;
    this.calls.push(["edit", conversationId, messageId, expectedRevision, text,
      clientOperationId, requestId]);
    this.mentionCalls.push({ kind: "edit", mentions: mentions.map((value) => ({ ...value })) });
    return requestId;
  }
  listDevices(): string {
    const requestId = `device-list-${this.calls.length}`;
    this.calls.push(["devices", requestId]);
    return requestId;
  }
  revokeDevice(deviceId: string): string {
    const requestId = `device-revoke-${this.calls.length}`;
    this.calls.push(["revoke-device", deviceId, requestId]);
    return requestId;
  }

  emit(event: V2WebProtocolEvent): void { this.observer?.onProtocolEvent?.(event); }
  transition(state: V2WebSocketTransportState): void {
    this.state = state;
    this.observer?.onStateChange?.(state);
  }
}

class FakeCache {
  readonly records = new Map<string, { messages: V2ConversationCacheMessage[]; cursorSequence: string;
    reactionCommands?: import("../src/application/v2WebChatApplication").V2ConversationCacheReactionCommand[];
    pinCommands?: import("../src/application/v2WebChatApplication").V2ConversationCachePinCommand[];
    editCommands?: import("../src/application/v2WebChatApplication").V2ConversationCacheEditCommand[] }>();
  readonly saves: Array<{ accountId: string; conversationId: string; messages: V2ConversationCacheMessage[]; cursor: string }> = [];

  async loadV2(accountId: string, conversationId: string) {
    const value = this.records.get(`${accountId}:${conversationId}`);
    return value ? structuredClone(value) : null;
  }

  async saveV2(accountId: string, conversationId: string, messages: V2ConversationCacheMessage[], cursor: string,
    reactionCommands: import("../src/application/v2WebChatApplication").V2ConversationCacheReactionCommand[] = [],
    pinCommands: import("../src/application/v2WebChatApplication").V2ConversationCachePinCommand[] = [],
    editCommands: import("../src/application/v2WebChatApplication").V2ConversationCacheEditCommand[] = []) {
    this.saves.push({ accountId, conversationId, messages: structuredClone(messages), cursor });
    this.records.set(`${accountId}:${conversationId}`, { messages: structuredClone(messages), cursorSequence: cursor,
      reactionCommands: structuredClone(reactionCommands), pinCommands: structuredClone(pinCommands),
      editCommands: structuredClone(editCommands) });
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
    availability: "available",
    reply: null,
    reactions: [],
    contentRevision: overrides.contentRevision ?? 0,
    editedAtEpochMs: overrides.editedAtEpochMs ?? 0,
    forwarded: Boolean(overrides.forwarded),
    forwardSource: overrides.forwardSource ? { ...overrides.forwardSource } : null,
    ...overrides,
    mentions: overrides.mentions?.map((value) => ({ ...value })) ?? [],
    pinned: Boolean(overrides.pinned),
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

test("publishes immutable snapshots to detachable view observers", () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  const states: string[] = [];
  const unsubscribe = application.subscribe((snapshot) => {
    states.push(snapshot.connectionState);
    snapshot.directory.push({} as never);
  });
  assert.deepEqual(states, ["idle"]);
  transport.transition("connecting");
  unsubscribe();
  transport.transition("connected");
  assert.deepEqual(states, ["idle", "connecting"]);
  assert.equal(application.snapshot.directory.length, 0);
  application.dispose();
});

test("refreshes server-authoritative devices and reconciles revocation", () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  transport.transition("authenticated");
  establish(transport);
  const listCall = transport.calls.at(-1)!;
  assert.equal(listCall[0], "devices");
  const target = "30000000-0000-4000-8000-000000000002";
  transport.emit({
    requestId: listCall[1] as string, clientMessageId: "", type: "device-directory",
    value: create(DeviceDirectorySchema, { devices: [
      { deviceId: DEVICE_ID, platform: ClientPlatform.WEB, createdAtEpochMs: 1n,
        lastSeenAtEpochMs: 2n, current: true },
      { deviceId: target, platform: ClientPlatform.WINDOWS, createdAtEpochMs: 1n,
        lastSeenAtEpochMs: 3n, current: false },
    ] }),
  });
  assert.equal(application.snapshot.devices.length, 2);
  assert.equal(application.snapshot.devices[1]?.platform, "windows");
  assert.equal(application.revokeDevice(DEVICE_ID), false);
  assert.equal(application.revokeDevice(target), true);
  const revokeCall = transport.calls.at(-1)!;
  assert.deepEqual(revokeCall.slice(0, 2), ["revoke-device", target]);
  transport.emit({
    requestId: revokeCall[2] as string, clientMessageId: "", type: "device-revoked",
    value: create(DeviceRevokedSchema, { targetDeviceId: target,
      revokedAtEpochMs: BigInt(NOW), revokedSessions: 1, changed: true }),
  });
  assert.equal(application.snapshot.devices.some((device) => device.deviceId === target), false);
  assert.equal(transport.calls.at(-1)?.[0], "devices", "success rechecks authoritative state");
  application.dispose();
});

test("contains device denial and permits explicit retry without clearing conversations", () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  transport.transition("authenticated");
  establish(transport);
  const firstRequest = transport.calls.at(-1)?.[1] as string;
  transport.emit({
    requestId: firstRequest, clientMessageId: "", type: "protocol-error",
    value: create(ProtocolErrorSchema, {
      code: ProtocolErrorCode.NOT_AUTHORIZED, safeMessage: "opaque", retryable: false,
    }),
  });
  assert.equal(application.snapshot.deviceFailure, "无法加载登录设备");
  assert.equal(application.snapshot.lastFailure, "");
  assert.equal(application.refreshDevices(), true);
  assert.notEqual(transport.calls.at(-1)?.[1], firstRequest);
  application.dispose();
});

test("abandons ambiguous device requests on disconnect and refreshes after resume", () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  transport.transition("authenticated");
  establish(transport);
  assert.equal(application.snapshot.devicesLoading, true);
  transport.transition("reconnect-wait");
  assert.equal(application.snapshot.devicesLoading, false);
  assert.equal(application.snapshot.revokingDeviceId, null);
  transport.transition("authenticated");
  establish(transport);
  assert.equal(transport.calls.at(-1)?.[0], "devices");
  assert.equal(application.snapshot.devicesLoading, true);
  application.dispose();
});

test("stops route-owned transport state without deleting durable cache", () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  assert.ok(application.snapshot.session);
  application.stop();
  assert.deepEqual(transport.calls.at(-1), ["stop"]);
  assert.equal(application.snapshot.session, null);
  assert.deepEqual(application.snapshot.directory, []);
  application.start();
  assert.deepEqual(transport.calls.at(-1), ["start"]);
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

test("persists and retries an optimistic reply with one stable target and client id", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage()],
    cursorSequence: CURSOR,
  });
  const application = new V2WebChatApplication({
    transport,
    cache,
    createClientMessageId: () => "client-reply-1",
    now: () => NOW,
  });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);

  const optimistic = application.sendReply(MESSAGE_ID, "quoted answer");
  assert.equal(optimistic.reply?.targetMessageId, MESSAGE_ID);
  assert.deepEqual(transport.calls.at(-1), [
    "reply", CONVERSATION_ID, MESSAGE_ID, "client-reply-1", "quoted answer",
  ]);
  assert.equal(cache.saves.at(-1)?.messages.at(-1)?.reply?.targetConversationSequence, CURSOR);

  transport.emit(correlated({
    type: "protocol-error",
    value: create(ProtocolErrorSchema, {
      code: ProtocolErrorCode.RATE_LIMITED,
      safeMessage: "retry later",
      retryable: true,
    }),
  }, "client-reply-1"));
  assert.equal(application.retryMessage("client-reply-1"), true);
  assert.deepEqual(transport.calls.at(-1), [
    "reply", CONVERSATION_ID, MESSAGE_ID, "client-reply-1", "quoted answer",
  ]);
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
        clientMessageId: "client-reply-1",
        contentType: MessageContentType.TEXT_UTF8,
        content: new TextEncoder().encode("quoted answer"),
        acceptedAtEpochMs: BigInt(NOW + 1),
        reply: {
          targetMessageId: MESSAGE_ID,
          targetConversationSequence: BigInt(CURSOR),
          targetSenderAccountId: ACCOUNT_ID,
        },
      }],
      nextSequence: BigInt(CURSOR) + 1n,
      latestSequence: BigInt(CURSOR) + 1n,
    }),
  }));
  const merged = application.snapshot.messages.find(
    message => message.clientMessageId === "client-reply-1");
  assert.equal(merged?.id, SECOND_MESSAGE_ID);
  assert.equal(merged?.reply?.targetConversationSequence, CURSOR);
  application.dispose();
});

test("merges contiguous live events and repairs sequence gaps through history", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage()],
    cursorSequence: CURSOR,
  });
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);
  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      nextSequence: BigInt(CURSOR),
      latestSequence: BigInt(CURSOR),
      hasMore: false,
    }),
  }));

  transport.emit({
    type: "message-published",
    requestId: "",
    clientMessageId: "",
    value: create(MessageRecordSchema, {
      conversationId: CONVERSATION_ID,
      messageId: SECOND_MESSAGE_ID,
      conversationSequence: BigInt(CURSOR) + 1n,
      senderAccountId: ACCOUNT_ID,
      senderDeviceId: DEVICE_ID,
      clientMessageId: "live-1",
      contentType: MessageContentType.TEXT_UTF8,
      content: new TextEncoder().encode("live one"),
      acceptedAtEpochMs: BigInt(NOW),
    }),
  });
  assert.equal(application.snapshot.messages.at(-1)?.content, "live one");
  assert.equal(cache.saves.at(-1)?.cursor, (BigInt(CURSOR) + 1n).toString());

  transport.emit({
    type: "message-published",
    requestId: "",
    clientMessageId: "",
    value: create(MessageRecordSchema, {
      conversationId: CONVERSATION_ID,
      messageId: "60000000-0000-4000-8000-000000000003",
      conversationSequence: BigInt(CURSOR) + 3n,
      senderAccountId: ACCOUNT_ID,
      senderDeviceId: DEVICE_ID,
      clientMessageId: "live-3",
      contentType: MessageContentType.TEXT_UTF8,
      content: new TextEncoder().encode("live three"),
      acceptedAtEpochMs: BigInt(NOW + 2),
    }),
  });
  assert.deepEqual(transport.calls.at(-1), [
    "history", CONVERSATION_ID, BigInt(CURSOR) + 1n, 100,
  ]);
  assert.equal(cache.saves.at(-1)?.cursor, (BigInt(CURSOR) + 1n).toString());
  application.dispose();
});

test("applies ordered recall and deletion entries while advancing the mixed cursor", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);
  const first = create(MessageRecordSchema, {
    conversationId: CONVERSATION_ID,
    messageId: MESSAGE_ID,
    conversationSequence: 1n,
    senderAccountId: ACCOUNT_ID,
    senderDeviceId: DEVICE_ID,
    clientMessageId: "mixed-1",
    contentType: MessageContentType.TEXT_UTF8,
    content: new TextEncoder().encode("first"),
    acceptedAtEpochMs: BigInt(NOW),
  });
  const second = create(MessageRecordSchema, {
    ...first,
    messageId: SECOND_MESSAGE_ID,
    conversationSequence: 2n,
    clientMessageId: "mixed-2",
    content: new TextEncoder().encode("second"),
  });

  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      messages: [first, second],
      entries: [
        create(ConversationEntryRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 1n,
          detail: { case: "message", value: first },
        }),
        create(ConversationEntryRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 2n,
          detail: { case: "message", value: second },
        }),
        create(ConversationEntryRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 3n,
          detail: { case: "recall", value: create(MessageRecalledRecordSchema, {
            conversationId: CONVERSATION_ID,
            conversationSequence: 3n,
            messageId: SECOND_MESSAGE_ID,
            actorAccountId: ACCOUNT_ID,
            source: "V1_IMPORT",
          }) },
        }),
        create(ConversationEntryRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 4n,
          detail: { case: "deletion", value: create(MessagesDeletedRecordSchema, {
            conversationId: CONVERSATION_ID,
            conversationSequence: 4n,
            actorAccountId: ACCOUNT_ID,
            source: "V1_IMPORT",
            mode: "selected",
            clientOperationId: "delete-1",
            messageIds: [MESSAGE_ID],
            operatorNameSnapshot: "Operator",
            occurredAtEpochMs: BigInt(NOW + 1),
          }) },
        }),
      ],
      nextSequence: 4n,
      latestSequence: 4n,
    }),
  }));

  assert.equal(application.snapshot.messages.length, 1);
  assert.equal(application.snapshot.messages[0]?.id, SECOND_MESSAGE_ID);
  assert.equal(application.snapshot.messages[0]?.content, "此消息已被撤回");
  assert.equal(application.snapshot.messages[0]?.availability, "recalled");
  assert.equal(cache.saves.at(-1)?.cursor, "4");
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

test("pages bounded participants and ignores responses abandoned by conversation switches", async () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  transport.transition("authenticated");
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
        latestSequence: 1n,
        updatedAtEpochMs: BigInt(NOW - 1),
      }],
      nextUpdatedAtEpochMs: BigInt(NOW - 1),
      nextConversationId: SECOND_CONVERSATION_ID,
    }),
  }));
  await application.openConversation(CONVERSATION_ID);

  assert.equal(application.refreshParticipants(), true);
  const first = transport.calls.at(-1)!;
  assert.deepEqual(first.slice(0, 4), ["participants", CONVERSATION_ID, 100, ""]);
  transport.emit({
    type: "conversation-participant-page",
    requestId: first[4] as string,
    clientMessageId: "",
    value: create(ConversationParticipantPageSchema, {
      conversationId: CONVERSATION_ID,
      participants: [{ accountId: ACCOUNT_ID, displayName: "Alice", role: ConversationRole.OWNER }],
      nextAccountId: ACCOUNT_ID,
      hasMore: true,
    }),
  });
  assert.equal(application.snapshot.participants[0]?.displayName, "Alice");
  assert.equal(application.snapshot.participantsHasMore, true);
  assert.equal(application.loadMoreParticipants(), true);
  assert.deepEqual(transport.calls.at(-1)?.slice(0, 4),
    ["participants", CONVERSATION_ID, 100, ACCOUNT_ID]);

  const staleRequest = transport.calls.at(-1)![4] as string;
  await application.openConversation(SECOND_CONVERSATION_ID);
  transport.emit({
    type: "conversation-participant-page",
    requestId: staleRequest,
    clientMessageId: "",
    value: create(ConversationParticipantPageSchema, {
      conversationId: CONVERSATION_ID,
      participants: [{ accountId: ACCOUNT_ID, displayName: "stale", role: ConversationRole.MEMBER }],
      nextAccountId: ACCOUNT_ID,
    }),
  });
  assert.deepEqual(application.snapshot.participants, []);
  assert.equal(application.snapshot.participantsLoading, false);
  application.dispose();
});

test("contains participant denial and abandons ambiguous requests on disconnect", async () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({ transport, cache: new FakeCache() });
  transport.transition("authenticated"); establish(transport); directory(transport);
  await application.openConversation(CONVERSATION_ID);
  assert.equal(application.refreshParticipants(), true);
  const deniedRequest = transport.calls.at(-1)![4] as string;
  transport.emit({
    type: "protocol-error", requestId: deniedRequest, clientMessageId: "",
    value: create(ProtocolErrorSchema, { code: ProtocolErrorCode.NOT_AUTHORIZED }),
  });
  assert.equal(application.snapshot.participantFailure, "无法加载会话成员");
  assert.equal(application.snapshot.participantsLoading, false);

  assert.equal(application.refreshParticipants(), true);
  transport.transition("reconnect-wait");
  assert.equal(application.snapshot.participantsLoading, false);
  application.dispose();
});

test("keeps structured mention spans in optimistic submission and rejects stale spans", async () => {
  const transport = new FakeTransport();
  const application = new V2WebChatApplication({
    transport,
    cache: new FakeCache(),
    createClientMessageId: () => "mention-client-1",
    now: () => NOW,
  });
  transport.transition("authenticated"); establish(transport); directory(transport);
  await application.openConversation(CONVERSATION_ID);
  const mention = { targetAccountId: ACCOUNT_ID, startUtf8Byte: 0, lengthUtf8Bytes: 4 };
  const optimistic = application.sendText("@李 hi", [mention]);
  assert.deepEqual(optimistic.mentions, [mention]);
  assert.deepEqual(transport.mentionCalls.at(-1), { kind: "submit", mentions: [mention] });
  assert.throws(() => application.sendText("@李 hi", [{
    ...mention, lengthUtf8Bytes: 2,
  }]), /mentions do not match/);
  application.dispose();
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

test("preserves active cached state across same-session resume and clears it on rejection", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage()],
    cursorSequence: CURSOR,
  });
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);
  const historyCallsBeforeResume = transport.calls.filter((call) => call[0] === "history").length;
  establish(transport);
  assert.equal(application.snapshot.activeConversationId, CONVERSATION_ID);
  assert.equal(application.snapshot.messages[0]?.content, "cached");
  assert.equal(transport.calls.filter((call) => call[0] === "history").length, historyCallsBeforeResume + 1);
  assert.ok(transport.calls.some((call) => call[0] === "history" && call[2] === BigInt(CURSOR)));

  transport.emit(correlated({
    type: "authentication-rejected",
    value: create(AuthenticationRejectedSchema, { retryAfterMs: 1000n }),
  }));
  assert.equal(application.snapshot.session, null);
  assert.equal(application.snapshot.activeConversationId, null);
  assert.deepEqual(application.snapshot.messages, []);
  application.dispose();
});

test("replays sending messages only after history and deduplicates an ACK-lost server copy", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [
      cachedMessage({ id: "", clientMessageId: "pending-send", sequence: "0", content: "send", deliveryState: "sending" }),
      cachedMessage({ id: "", clientMessageId: "pending-next", sequence: "0", content: "next", deliveryState: "sending" }),
      cachedMessage({ id: "", clientMessageId: "pending-after", sequence: "0", content: "after", deliveryState: "sending" }),
      cachedMessage({ id: "", clientMessageId: "pending-failed", sequence: "0", content: "failed", deliveryState: "failed" }),
    ],
    cursorSequence: CURSOR,
  });
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);
  assert.equal(transport.calls.filter((call) => call[0] === "submit").length, 0);

  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      nextSequence: BigInt(CURSOR),
      latestSequence: BigInt(CURSOR),
      hasMore: false,
    }),
  }));
  assert.deepEqual(transport.calls.filter((call) => call[0] === "submit"), [
    ["submit", CONVERSATION_ID, "pending-send", "send"],
  ], "recovery dispatches at most one command before its ACK");
  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      nextSequence: BigInt(CURSOR),
      latestSequence: BigInt(CURSOR),
      hasMore: false,
    }),
  }));
  assert.equal(transport.calls.filter((call) => call[0] === "submit").length, 1);

  establish(transport);
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
        clientMessageId: "pending-send",
        contentType: MessageContentType.TEXT_UTF8,
        content: new TextEncoder().encode("send"),
        acceptedAtEpochMs: BigInt(NOW),
      }],
      nextSequence: BigInt(CURSOR) + 1n,
      latestSequence: BigInt(CURSOR) + 1n,
      hasMore: false,
    }),
  }));
  assert.deepEqual(transport.calls.filter((call) => call[0] === "submit"), [
    ["submit", CONVERSATION_ID, "pending-send", "send"],
    ["submit", CONVERSATION_ID, "pending-next", "next"],
  ], "history acceptance suppresses the lost-ACK replay and releases the next command");
  assert.equal(application.snapshot.messages.find((message) => message.clientMessageId === "pending-send")?.deliveryState, "accepted");
  assert.equal(application.snapshot.messages.find((message) => message.clientMessageId === "pending-failed")?.deliveryState, "failed");
  transport.emit(correlated({
    type: "protocol-error",
    value: create(ProtocolErrorSchema, {
      code: ProtocolErrorCode.RATE_LIMITED,
      safeMessage: "retry later",
      retryable: true,
    }),
  }, "pending-next"));
  assert.equal(transport.calls.filter((call) => call[0] === "submit").length, 2,
    "a replay error stops the automatic queue");
  assert.equal(application.snapshot.messages.find((message) => message.clientMessageId === "pending-after")?.deliveryState, "failed");
  assert.equal(application.snapshot.messages.find((message) => message.clientMessageId === "pending-after")?.errorCode, "REPLAY_PAUSED");
  application.dispose();
});

test("refuses new optimistic sends when the bounded unresolved outbox is full", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: Array.from({ length: 100 }, (_, index) => cachedMessage({
      id: "",
      clientMessageId: `failed-${index}`,
      sequence: "0",
      deliveryState: "failed",
    })),
    cursorSequence: CURSOR,
  });
  const application = new V2WebChatApplication({ transport, cache });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);
  assert.throws(() => application.sendText("overflow"), /pending message limit/);
  assert.equal(transport.calls.filter((call) => call[0] === "submit").length, 0);
  application.dispose();
});

test("persists optimistic reactions and converges ACK, history, and live changes", async () => {
  const transport = new FakeTransport();
  const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage({ sequence: "1" })],
    cursorSequence: "1",
  });
  const application = new V2WebChatApplication({
    transport,
    cache,
    createClientMessageId: () => "70000000-0000-4000-8000-000000000001",
  });
  establish(transport);
  directory(transport);
  await application.openConversation(CONVERSATION_ID);

  assert.equal(application.setReaction(MESSAGE_ID, MessageReactionKind.LOVE), true);
  const optimistic = application.snapshot.messages[0]!.reactions[0]!;
  assert.equal(optimistic.reaction, MessageReactionKind.LOVE);
  assert.deepEqual(optimistic.actorAccountIds, [ACCOUNT_ID]);
  assert.equal(application.snapshot.reactionCommands.length, 1);
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)
    ?.reactionCommands?.[0]?.clientOperationId,
  "70000000-0000-4000-8000-000000000001");

  transport.emit(correlated({
    type: "message-reaction-applied",
    value: create(MessageReactionAppliedSchema, {
      conversationId: CONVERSATION_ID,
      messageId: MESSAGE_ID,
      reaction: MessageReactionKind.LOVE,
      active: true,
      actorAccountId: ACCOUNT_ID,
      clientOperationId: "70000000-0000-4000-8000-000000000001",
      changed: true,
      conversationSequence: 2n,
      occurredAtEpochMs: BigInt(NOW),
    }),
  }));
  assert.equal(application.snapshot.reactionCommands.length, 0);
  assert.equal(application.snapshot.messages[0]!.reactions[0]!.actorAccountIds.length, 1);

  transport.emit(correlated({
    type: "message-history-page",
    value: create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      entries: [create(ConversationEntryRecordSchema, {
        conversationId: CONVERSATION_ID,
        conversationSequence: 2n,
        detail: { case: "reaction", value: create(MessageReactionChangedRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 2n,
          messageId: MESSAGE_ID,
          reaction: MessageReactionKind.LOVE,
          active: true,
          actorAccountId: ACCOUNT_ID,
          clientOperationId: "70000000-0000-4000-8000-000000000001",
          occurredAtEpochMs: BigInt(NOW),
        }) },
      })],
      nextSequence: 2n,
      latestSequence: 2n,
    }),
  }));
  assert.equal(application.snapshot.messages[0]!.reactions[0]!.actorAccountIds.length, 1,
    "history replay remains idempotent");

  transport.emit(correlated({
    type: "message-reaction-changed",
    value: create(MessageReactionChangedRecordSchema, {
      conversationId: CONVERSATION_ID,
      conversationSequence: 3n,
      messageId: MESSAGE_ID,
      reaction: MessageReactionKind.LOVE,
      active: false,
      actorAccountId: ACCOUNT_ID,
      clientOperationId: "70000000-0000-4000-8000-000000000002",
      occurredAtEpochMs: BigInt(NOW + 1),
    }),
  }));
  assert.deepEqual(application.snapshot.messages[0]!.reactions, []);
  application.dispose();
});

test("persists optimistic pins and advances cursor only from ordered events", async () => {
  const transport = new FakeTransport(); const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage({ sequence: "1" })], cursorSequence: "1" });
  const operationId = "70000000-0000-4000-8000-000000000003";
  const application = new V2WebChatApplication({ transport, cache,
    createClientMessageId: () => operationId });
  establish(transport); directory(transport); await application.openConversation(CONVERSATION_ID);
  assert.equal(application.setPin(MESSAGE_ID), true);
  assert.equal(application.snapshot.messages[0]!.pinned, true);
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)?.pinCommands?.length, 1);
  transport.emit(correlated({ type: "message-pin-applied", value: create(MessagePinAppliedSchema, {
    conversationId: CONVERSATION_ID, messageId: MESSAGE_ID, pinned: true,
    actorAccountId: ACCOUNT_ID, clientOperationId: operationId, changed: true,
    conversationSequence: 2n, occurredAtEpochMs: BigInt(NOW),
  }) }));
  assert.equal(application.snapshot.pinCommands.length, 0);
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)?.cursorSequence, "1",
    "ACK never advances the contiguous cursor");
  transport.emit(correlated({ type: "message-pin-changed", value: create(MessagePinChangedRecordSchema, {
    conversationId: CONVERSATION_ID, conversationSequence: 2n, messageId: MESSAGE_ID,
    pinned: true, actorAccountId: ACCOUNT_ID, clientOperationId: operationId,
    occurredAtEpochMs: BigInt(NOW),
  }) }));
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)?.cursorSequence, "2");
  assert.equal(application.snapshot.messages[0]!.pinned, true);
  application.dispose();
});

test("persists an edit overlay, preserves conflicts for explicit rebase, and advances only on events", async () => {
  const transport = new FakeTransport(); const cache = new FakeCache();
  cache.records.set(`${ACCOUNT_ID}:${CONVERSATION_ID}`, {
    messages: [cachedMessage({ sequence: "1", content: "original" })], cursorSequence: "1" });
  const operationIds = [
    "70000000-0000-4000-8000-000000000010",
    "70000000-0000-4000-8000-000000000011",
  ];
  const application = new V2WebChatApplication({ transport, cache,
    createClientMessageId: () => operationIds.shift()! });
  establish(transport); directory(transport); await application.openConversation(CONVERSATION_ID);
  transport.emit(correlated({ type: "message-history-page", value: create(MessageHistoryPageSchema, {
    conversationId: CONVERSATION_ID, nextSequence: 1n, latestSequence: 1n, hasMore: false,
  }) }));

  assert.equal(application.editMessage(MESSAGE_ID, "my proposal"), true);
  assert.equal(application.snapshot.messages[0]!.content, "original",
    "optimistic content remains an overlay, not durable truth");
  assert.equal(application.snapshot.editCommands[0]!.proposedContent, "my proposal");
  assert.equal(application.snapshot.editCommands[0]!.expectedRevision, 0);
  const firstRequestId = application.snapshot.editCommands[0]!.requestId!;
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)?.editCommands?.length, 1);

  transport.emit({ type: "protocol-error", requestId: firstRequestId, clientMessageId: "",
    value: create(ProtocolErrorSchema, {
      code: ProtocolErrorCode.MESSAGE_REVISION_CONFLICT,
      safeMessage: "message revision conflict",
    }) });
  assert.equal(application.snapshot.editCommands[0]!.deliveryState, "conflict");
  assert.equal(application.snapshot.editCommands[0]!.proposedContent, "my proposal");
  assert.deepEqual(transport.calls.at(-1)?.slice(0, 3), ["history", CONVERSATION_ID, 1n]);

  const otherOperationId = "70000000-0000-4000-8000-000000000099";
  transport.emit(correlated({ type: "message-history-page", value: create(MessageHistoryPageSchema, {
    conversationId: CONVERSATION_ID,
    entries: [create(ConversationEntryRecordSchema, {
      conversationId: CONVERSATION_ID, conversationSequence: 2n,
      detail: { case: "edit", value: create(MessageEditedRecordSchema, {
        conversationId: CONVERSATION_ID, conversationSequence: 2n, messageId: MESSAGE_ID,
        contentRevision: 1, contentType: MessageContentType.TEXT_UTF8,
        content: new TextEncoder().encode("@李 other device"), actorAccountId: ACCOUNT_ID,
        mentions: [{ targetAccountId: "20000000-0000-4000-8000-000000000002",
          startUtf8Byte: 0, lengthUtf8Bytes: 4 }],
        clientOperationId: otherOperationId, occurredAtEpochMs: BigInt(NOW),
      }) },
    })],
    nextSequence: 2n, latestSequence: 2n, hasMore: false,
  }) }));
  assert.equal(application.snapshot.messages[0]!.content, "@李 other device");
  assert.equal(application.snapshot.messages[0]!.mentions[0]?.targetAccountId,
    "20000000-0000-4000-8000-000000000002");
  assert.equal(application.snapshot.messages[0]!.contentRevision, 1);
  assert.equal(application.snapshot.editCommands[0]!.deliveryState, "conflict");

  const staleOperationId = application.snapshot.editCommands[0]!.clientOperationId;
  assert.equal(application.rebaseEdit(staleOperationId), true);
  assert.equal(application.snapshot.editCommands[0]!.expectedRevision, 1);
  assert.notEqual(application.snapshot.editCommands[0]!.clientOperationId, staleOperationId);
  const rebased = application.snapshot.editCommands[0]!;
  const rebasedCall = [...transport.calls].reverse().find((call) => call[0] === "edit")!;
  assert.deepEqual(rebasedCall.slice(1, 6), [CONVERSATION_ID, MESSAGE_ID, 1,
    "my proposal", rebased.clientOperationId]);

  transport.emit({ type: "message-edit-applied", requestId: rebased.requestId!, clientMessageId: "",
    value: create(MessageEditAppliedSchema, {
      conversationId: CONVERSATION_ID, messageId: MESSAGE_ID, contentRevision: 2,
      contentType: MessageContentType.TEXT_UTF8, content: new TextEncoder().encode("my proposal"),
      actorAccountId: ACCOUNT_ID, clientOperationId: rebased.clientOperationId,
      changed: true, conversationSequence: 3n, occurredAtEpochMs: BigInt(NOW + 1),
    }) });
  assert.equal(application.snapshot.editCommands.length, 0);
  assert.equal(application.snapshot.messages[0]!.content, "my proposal");
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)?.cursorSequence, "2",
    "edit ACK does not advance the mixed cursor");

  transport.emit(correlated({ type: "message-edited", value: create(MessageEditedRecordSchema, {
    conversationId: CONVERSATION_ID, conversationSequence: 3n, messageId: MESSAGE_ID,
    contentRevision: 2, contentType: MessageContentType.TEXT_UTF8,
    content: new TextEncoder().encode("my proposal"), actorAccountId: ACCOUNT_ID,
    clientOperationId: rebased.clientOperationId, occurredAtEpochMs: BigInt(NOW + 1),
  }) }));
  assert.equal(cache.records.get(`${ACCOUNT_ID}:${CONVERSATION_ID}`)?.cursorSequence, "3");
  assert.equal(application.snapshot.messages[0]!.editedAtEpochMs, NOW + 1);
  application.dispose();
});
