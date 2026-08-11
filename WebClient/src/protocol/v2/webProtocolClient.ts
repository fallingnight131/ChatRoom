import { create, fromBinary, toBinary } from "@bufbuild/protobuf";

import {
  AuthenticateSchema,
  AuthenticationRejectedSchema,
  SessionEstablishedSchema,
  type AuthenticationRejected,
  type SessionEstablished,
} from "./generated/authentication_pb";
import {
  ConversationDirectoryPageSchema,
  ListConversationsSchema,
  type ConversationDirectoryPage,
} from "./generated/conversation_pb";
import {
  ClientHelloSchema,
  ClientPlatform,
  MessageType,
  ProtocolErrorSchema,
  ServerHelloSchema,
  type ProtocolError,
  type ServerHello,
} from "./generated/control_pb";
import { EnvelopeSchema, MessageKind, type Envelope } from "./generated/envelope_pb";
import {
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
  ReadMessageHistorySchema,
  SubmitMessageSchema,
  type MessageAccepted,
  type MessageHistoryPage,
} from "./generated/messaging_pb";

const PROTOCOL_VERSION = 2;
const MAX_IDENTIFIER_BYTES = 128;
const MAX_PAYLOAD_BYTES = 1024 * 1024;
const MAX_WIRE_BYTES = MAX_PAYLOAD_BYTES + 1024;
const MAX_PASSWORD_BYTES = 1024;
const MAX_TEXT_BYTES = 65_536;
const MAX_PAGE_SIZE = 100;
const MAX_PENDING_REQUESTS = 16;
const MAX_SIGNED_SEQUENCE = (1n << 63n) - 1n;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const encoder = new TextEncoder();
const strictDecoder = new TextDecoder("utf-8", { fatal: true });

export type V2WebProtocolState =
  | "new"
  | "hello-sent"
  | "negotiated"
  | "authentication-sent"
  | "authenticated"
  | "closed";

export type V2WebProtocolEvent =
  | { type: "server-hello"; value: ServerHello }
  | { type: "session-established"; value: SessionEstablished }
  | { type: "authentication-rejected"; value: AuthenticationRejected }
  | { type: "protocol-error"; value: ProtocolError }
  | { type: "message-accepted"; value: MessageAccepted }
  | { type: "message-history-page"; value: MessageHistoryPage }
  | { type: "conversation-directory-page"; value: ConversationDirectoryPage };

type PendingRequest = { expected: ReadonlySet<MessageType> };

export interface V2WebProtocolClientOptions {
  appVersion: string;
  clientDeviceId: string;
  createRequestId?: () => string;
  now?: () => number;
}

export class V2WebProtocolClient {
  private readonly appVersion: string;
  private readonly clientDeviceId: string;
  private readonly createRequestId: () => string;
  private readonly now: () => number;
  private readonly pending = new Map<string, PendingRequest>();
  private currentState: V2WebProtocolState = "new";
  private currentSession: SessionEstablished | null = null;
  private retainedResumeToken: Uint8Array | null = null;
  private negotiatedMaximumFrameBytes = MAX_WIRE_BYTES;

  constructor(options: V2WebProtocolClientOptions) {
    requireUtf8("appVersion", options.appVersion, 1, 64);
    requireUtf8("clientDeviceId", options.clientDeviceId, 1, 128);
    this.appVersion = options.appVersion;
    this.clientDeviceId = options.clientDeviceId;
    this.createRequestId = options.createRequestId ?? (() => crypto.randomUUID());
    this.now = options.now ?? Date.now;
  }

  get state(): V2WebProtocolState {
    return this.currentState;
  }

  get session(): SessionEstablished | null {
    if (!this.currentSession) return null;
    return create(SessionEstablishedSchema, {
      ...this.currentSession,
      resumeToken: this.retainedResumeToken?.slice() ?? new Uint8Array(),
    });
  }

  createClientHello(): Uint8Array {
    this.requireState("new");
    const payload = toBinary(ClientHelloSchema, create(ClientHelloSchema, {
      minimumProtocolVersion: PROTOCOL_VERSION,
      maximumProtocolVersion: PROTOCOL_VERSION,
      platform: ClientPlatform.WEB,
      appVersion: this.appVersion,
      clientDeviceId: this.clientDeviceId,
    }));
    const bytes = this.command(MessageType.CLIENT_HELLO, payload, new Set([MessageType.SERVER_HELLO]));
    this.currentState = "hello-sent";
    return bytes;
  }

  authenticate(username: string, passwordUtf8: Uint8Array): Uint8Array {
    this.requireState("negotiated");
    requireUtf8("username", username, 1, 128);
    if (passwordUtf8.byteLength < 1 || passwordUtf8.byteLength > MAX_PASSWORD_BYTES) {
      throw new Error("passwordUtf8 must contain 1..1024 bytes");
    }
    const transientPassword = passwordUtf8.slice();
    try {
      const payload = toBinary(AuthenticateSchema, create(AuthenticateSchema, {
        username,
        passwordUtf8: transientPassword,
      }));
      const bytes = this.command(
        MessageType.AUTHENTICATE,
        payload,
        new Set([MessageType.SESSION_ESTABLISHED, MessageType.AUTHENTICATION_REJECTED]),
      );
      this.currentState = "authentication-sent";
      return bytes;
    } finally {
      transientPassword.fill(0);
    }
  }

  listConversations(limit: number, after?: { updatedAtEpochMs: bigint; conversationId: string }): Uint8Array {
    this.requireState("authenticated");
    requirePageSize(limit);
    if (after) {
      if (after.updatedAtEpochMs <= 0n) throw new Error("directory cursor timestamp must be positive");
      requireUuid("conversationId", after.conversationId);
    }
    const payload = toBinary(ListConversationsSchema, create(ListConversationsSchema, {
      afterUpdatedAtEpochMs: after?.updatedAtEpochMs ?? 0n,
      afterConversationId: after?.conversationId ?? "",
      limit,
    }));
    return this.command(
      MessageType.LIST_CONVERSATIONS,
      payload,
      new Set([MessageType.CONVERSATION_DIRECTORY_PAGE]),
    );
  }

  readMessageHistory(conversationId: string, afterSequence: bigint, limit: number): Uint8Array {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    if (afterSequence < 0n) throw new Error("afterSequence must not be negative");
    requirePageSize(limit);
    const payload = toBinary(ReadMessageHistorySchema, create(ReadMessageHistorySchema, {
      conversationId,
      afterSequence,
      limit,
    }));
    return this.command(
      MessageType.READ_MESSAGE_HISTORY,
      payload,
      new Set([MessageType.MESSAGE_HISTORY_PAGE]),
    );
  }

  submitText(conversationId: string, clientMessageId: string, text: string): Uint8Array {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    requireIdentifier("clientMessageId", clientMessageId);
    const content = encoder.encode(text);
    if (content.byteLength < 1 || content.byteLength > MAX_TEXT_BYTES) {
      throw new Error("text must contain 1..65536 UTF-8 bytes");
    }
    const payload = toBinary(SubmitMessageSchema, create(SubmitMessageSchema, {
      conversationId,
      contentType: MessageContentType.TEXT_UTF8,
      content,
    }));
    return this.command(
      MessageType.SUBMIT_MESSAGE,
      payload,
      new Set([MessageType.MESSAGE_ACCEPTED]),
      clientMessageId,
    );
  }

  receive(bytes: Uint8Array): V2WebProtocolEvent {
    if (this.currentState === "closed") throw new Error("protocol client is closed");
    if (bytes.byteLength > Math.min(MAX_WIRE_BYTES, this.negotiatedMaximumFrameBytes)) {
      throw new Error("V2 frame exceeds the negotiated limit");
    }
    let envelope: Envelope;
    try {
      envelope = fromBinary(EnvelopeSchema, bytes);
    } catch {
      throw new Error("invalid V2 envelope");
    }
    this.validateInboundEnvelope(envelope);
    const pending = envelope.requestId ? this.pending.get(envelope.requestId) : undefined;
    if (envelope.messageType !== MessageType.PROTOCOL_ERROR) {
      if (!pending) throw new Error("response does not match a pending request");
      if (!pending.expected.has(envelope.messageType)) throw new Error("response message type does not match the request");
    } else if (envelope.requestId && !pending) {
      throw new Error("protocol error does not match a pending request");
    }

    const event = this.decodeEvent(envelope);
    if (event.type === "session-established" && envelope.sessionId !== event.value.sessionId) {
      throw new Error("established session does not match its envelope");
    }
    this.applyEvent(event);
    if (envelope.requestId) this.pending.delete(envelope.requestId);
    return event;
  }

  close(): void {
    this.retainedResumeToken?.fill(0);
    this.retainedResumeToken = null;
    this.currentSession = null;
    this.pending.clear();
    this.currentState = "closed";
  }

  private command(
    messageType: MessageType,
    payload: Uint8Array,
    expected: ReadonlySet<MessageType>,
    clientMessageId = "",
  ): Uint8Array {
    if (payload.byteLength > MAX_PAYLOAD_BYTES) throw new Error("V2 payload exceeds the limit");
    const requestId = this.createRequestId();
    requireUuid("requestId", requestId);
    if (this.pending.has(requestId)) throw new Error("requestId is already pending");
    if (this.pending.size >= MAX_PENDING_REQUESTS) throw new Error("too many pending V2 requests");
    const sentAt = this.now();
    if (!Number.isSafeInteger(sentAt) || sentAt <= 0) throw new Error("clock must return a positive safe integer");
    const envelope = create(EnvelopeSchema, {
      protocolVersion: PROTOCOL_VERSION,
      kind: MessageKind.COMMAND,
      messageType,
      requestId,
      sessionId: this.currentSession?.sessionId ?? "",
      clientMessageId,
      sentAtEpochMs: BigInt(sentAt),
      payload,
    });
    const bytes = toBinary(EnvelopeSchema, envelope);
    if (bytes.byteLength > Math.min(MAX_WIRE_BYTES, this.negotiatedMaximumFrameBytes)) {
      throw new Error("V2 frame exceeds the negotiated limit");
    }
    this.pending.set(requestId, { expected });
    return bytes;
  }

  private validateInboundEnvelope(envelope: Envelope): void {
    if (envelope.protocolVersion !== PROTOCOL_VERSION) throw new Error("unsupported protocol version");
    if (envelope.kind !== MessageKind.RESPONSE && envelope.kind !== MessageKind.ERROR) {
      throw new Error("unexpected inbound message kind");
    }
    if (envelope.messageType === MessageType.PROTOCOL_ERROR
        || envelope.messageType === MessageType.AUTHENTICATION_REJECTED) {
      if (envelope.kind !== MessageKind.ERROR) throw new Error("error payload requires error message kind");
    } else if (envelope.kind !== MessageKind.RESPONSE) {
      throw new Error("successful payload requires response message kind");
    }
    if (envelope.payload.byteLength > MAX_PAYLOAD_BYTES) throw new Error("V2 payload exceeds the limit");
    if (envelope.sentAtEpochMs <= 0n) throw new Error("server timestamp must be positive");
    if (envelope.requestId) requireIdentifier("requestId", envelope.requestId);
    if (envelope.sessionId) requireIdentifier("sessionId", envelope.sessionId);
    if (envelope.clientMessageId) requireIdentifier("clientMessageId", envelope.clientMessageId);
    if (this.currentState === "authenticated" && envelope.sessionId !== this.currentSession?.sessionId) {
      throw new Error("response session does not match the authenticated session");
    }
  }

  private decodeEvent(envelope: Envelope): V2WebProtocolEvent {
    try {
      switch (envelope.messageType) {
        case MessageType.SERVER_HELLO:
          return { type: "server-hello", value: fromBinary(ServerHelloSchema, envelope.payload) };
        case MessageType.SESSION_ESTABLISHED:
          return { type: "session-established", value: fromBinary(SessionEstablishedSchema, envelope.payload) };
        case MessageType.AUTHENTICATION_REJECTED:
          return { type: "authentication-rejected", value: fromBinary(AuthenticationRejectedSchema, envelope.payload) };
        case MessageType.PROTOCOL_ERROR:
          return { type: "protocol-error", value: fromBinary(ProtocolErrorSchema, envelope.payload) };
        case MessageType.MESSAGE_ACCEPTED:
          return { type: "message-accepted", value: fromBinary(MessageAcceptedSchema, envelope.payload) };
        case MessageType.MESSAGE_HISTORY_PAGE:
          return { type: "message-history-page", value: fromBinary(MessageHistoryPageSchema, envelope.payload) };
        case MessageType.CONVERSATION_DIRECTORY_PAGE:
          return { type: "conversation-directory-page", value: fromBinary(ConversationDirectoryPageSchema, envelope.payload) };
        default:
          throw new Error("unsupported inbound message type");
      }
    } catch (error) {
      if (error instanceof Error && error.message === "unsupported inbound message type") throw error;
      throw new Error("invalid V2 response payload");
    }
  }

  private applyEvent(event: V2WebProtocolEvent): void {
    switch (event.type) {
      case "server-hello":
        this.requireState("hello-sent");
        if (event.value.selectedProtocolVersion !== PROTOCOL_VERSION
            || event.value.serverTimeEpochMs <= 0n
            || event.value.maximumFrameBytes < 1
            || event.value.maximumFrameBytes > MAX_WIRE_BYTES) {
          throw new Error("invalid server hello");
        }
        requireIdentifier("connectionId", event.value.connectionId);
        this.negotiatedMaximumFrameBytes = event.value.maximumFrameBytes;
        this.currentState = "negotiated";
        break;
      case "session-established":
        this.requireState("authentication-sent");
        requireUuid("accountId", event.value.accountId);
        requireUuid("deviceId", event.value.deviceId);
        requireUuid("sessionId", event.value.sessionId);
        if (event.value.resumeToken.byteLength !== 32 || event.value.expiresAtEpochMs <= 0n) {
          throw new Error("invalid established session");
        }
        this.retainedResumeToken?.fill(0);
        this.retainedResumeToken = event.value.resumeToken.slice();
        this.currentSession = create(SessionEstablishedSchema, {
          ...event.value,
          resumeToken: new Uint8Array(),
        });
        this.currentState = "authenticated";
        break;
      case "authentication-rejected":
        this.requireState("authentication-sent");
        if (event.value.retryAfterMs < 0n) throw new Error("invalid authentication rejection");
        this.currentState = "closed";
        break;
      case "protocol-error":
        if (event.value.safeMessage.length > 512) throw new Error("invalid protocol error");
        break;
      case "message-accepted":
        requireUuid("conversationId", event.value.conversationId);
        requireUuid("messageId", event.value.messageId);
        if (event.value.conversationSequence <= 0n || event.value.acceptedAtEpochMs <= 0n) {
          throw new Error("invalid message acceptance");
        }
        break;
      case "message-history-page":
        validateHistoryPage(event.value);
        break;
      case "conversation-directory-page":
        validateDirectoryPage(event.value);
        break;
    }
  }

  private requireState(expected: V2WebProtocolState): void {
    if (this.currentState !== expected) throw new Error(`expected ${expected} state, found ${this.currentState}`);
  }
}

function validateHistoryPage(page: MessageHistoryPage): void {
  requireUuid("conversationId", page.conversationId);
  if (page.messages.length > MAX_PAGE_SIZE
      || page.nextSequence > MAX_SIGNED_SEQUENCE
      || page.latestSequence > MAX_SIGNED_SEQUENCE) {
    throw new Error("history page exceeds the limit");
  }
  let previous = 0n;
  for (const message of page.messages) {
    requireUuid("messageId", message.messageId);
    requireUuid("senderAccountId", message.senderAccountId);
    requireUuid("senderDeviceId", message.senderDeviceId);
    requireIdentifier("clientMessageId", message.clientMessageId);
    if (message.conversationId !== page.conversationId
        || message.conversationSequence <= previous
        || message.acceptedAtEpochMs <= 0n
        || message.contentType !== MessageContentType.TEXT_UTF8
        || message.content.byteLength < 1
        || message.content.byteLength > MAX_TEXT_BYTES) {
      throw new Error("invalid history message");
    }
    try {
      strictDecoder.decode(message.content);
    } catch {
      throw new Error("history text is not valid UTF-8");
    }
    previous = message.conversationSequence;
  }
  if (page.messages.length > 0 && page.nextSequence !== previous) {
    throw new Error("history cursor does not identify the last message");
  }
}

function validateDirectoryPage(page: ConversationDirectoryPage): void {
  if (page.conversations.length > MAX_PAGE_SIZE || (page.hasMore && page.conversations.length === 0)) {
    throw new Error("invalid directory page bounds");
  }
  let previous: (typeof page.conversations)[number] | undefined;
  for (const item of page.conversations) {
    requireUuid("conversationId", item.conversationId);
    const displayNameBytes = encoder.encode(item.displayName).byteLength;
    if ((item.kind !== 1 && item.kind !== 2)
        || (item.role !== 1 && item.role !== 2 && item.role !== 3)
        || item.displayName.trim().length === 0
        || [...item.displayName].length > 100 || displayNameBytes > 400
        || item.lastReadSequence > item.latestSequence || item.updatedAtEpochMs <= 0n) {
      throw new Error("invalid directory record");
    }
    if (previous && !(previous.updatedAtEpochMs > item.updatedAtEpochMs
      || (previous.updatedAtEpochMs === item.updatedAtEpochMs && previous.conversationId > item.conversationId))) {
      throw new Error("conversation directory is out of order");
    }
    previous = item;
  }
  if (!previous) {
    if (page.nextUpdatedAtEpochMs !== 0n || page.nextConversationId !== "") {
      throw new Error("empty directory page has a cursor");
    }
  } else if (page.nextUpdatedAtEpochMs !== previous.updatedAtEpochMs
    || page.nextConversationId !== previous.conversationId) {
    throw new Error("directory cursor does not identify the last record");
  }
}

function requirePageSize(value: number): void {
  if (!Number.isInteger(value) || value < 1 || value > MAX_PAGE_SIZE) {
    throw new Error("limit must be an integer in 1..100");
  }
}

function requireUuid(field: string, value: string): void {
  if (!UUID_PATTERN.test(value)) throw new Error(`${field} must be a canonical UUID`);
}

function requireIdentifier(field: string, value: string): void {
  requireUtf8(field, value, 1, MAX_IDENTIFIER_BYTES);
}

function requireUtf8(field: string, value: string, minimum: number, maximum: number): void {
  const bytes = encoder.encode(value).byteLength;
  if (value.trim().length === 0 || bytes < minimum || bytes > maximum) {
    throw new Error(`${field} must contain ${minimum}..${maximum} UTF-8 bytes`);
  }
}
