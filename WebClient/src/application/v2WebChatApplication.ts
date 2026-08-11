import { ConversationKind, ConversationRole, type ConversationDirectoryRecord } from "../protocol/v2/generated/conversation_pb";
import { MessageContentType, type MessageRecord } from "../protocol/v2/generated/messaging_pb";
import type { V2WebProtocolEvent } from "../protocol/v2/webProtocolClient";
import type {
  V2WebSocketTransportObserver,
  V2WebSocketTransportState,
} from "../protocol/v2/webSocketTransport";

const HISTORY_PAGE_SIZE = 100;
const DIRECTORY_PAGE_SIZE = 50;
const MAX_RETAINED_ACCEPTED_MESSAGES = 500;
const MAX_PENDING_MESSAGES = 100;
const decoder = new TextDecoder("utf-8", { fatal: true });

export interface V2ConversationCacheMessage {
  conversationId: string;
  id: string;
  clientMessageId: string;
  senderAccountId: string;
  senderDeviceId: string;
  sequence: string;
  acceptedAtEpochMs: number;
  content: string;
  contentType: "text";
  deliveryState: "sending" | "accepted" | "failed";
  errorCode: string;
}

export interface V2ConversationCacheSnapshot {
  messages: V2ConversationCacheMessage[];
  cursorSequence: string;
}

export interface V2ConversationCache {
  loadV2(accountId: string, conversationId: string): Promise<V2ConversationCacheSnapshot | null>;
  saveV2(
    accountId: string,
    conversationId: string,
    messages: V2ConversationCacheMessage[],
    cursorSequence: string,
  ): Promise<boolean>;
}

export interface V2ChatTransport {
  readonly state: V2WebSocketTransportState;
  subscribe(observer: V2WebSocketTransportObserver): () => void;
  start(): void;
  stop(): void;
  authenticate(username: string, passwordUtf8: Uint8Array): void;
  resumeSession(sessionId: string, resumeToken: Uint8Array): void;
  listConversations(limit: number, after?: { updatedAtEpochMs: bigint; conversationId: string }): void;
  readMessageHistory(conversationId: string, afterSequence: bigint, limit: number): void;
  submitText(conversationId: string, clientMessageId: string, text: string): void;
}

export interface V2DirectoryItem {
  conversationId: string;
  kind: "direct" | "group";
  displayName: string;
  role: "owner" | "admin" | "member";
  latestSequence: string;
  lastReadSequence: string;
  updatedAtEpochMs: number;
}

export interface V2WebChatSnapshot {
  connectionState: V2WebSocketTransportState;
  session: null | {
    accountId: string;
    deviceId: string;
    sessionId: string;
    displayName: string;
    expiresAtEpochMs: number;
  };
  directory: V2DirectoryItem[];
  directoryHasMore: boolean;
  activeConversationId: string | null;
  messages: V2ConversationCacheMessage[];
  historyLoading: boolean;
  lastFailure: string;
}

export interface V2WebChatApplicationOptions {
  transport: V2ChatTransport;
  cache: V2ConversationCache;
  createClientMessageId?: () => string;
  now?: () => number;
  onChange?: (snapshot: V2WebChatSnapshot) => void;
}

type ConversationState = {
  messages: V2ConversationCacheMessage[];
  cursorSequence: string;
  loading: boolean;
};

export class V2WebChatApplication {
  private readonly transport: V2ChatTransport;
  private readonly cache: V2ConversationCache;
  private readonly createClientMessageId: () => string;
  private readonly now: () => number;
  private readonly onChange?: (snapshot: V2WebChatSnapshot) => void;
  private readonly conversations = new Map<string, ConversationState>();
  private readonly unsubscribeTransport: () => void;
  private sessionValue: V2WebChatSnapshot["session"] = null;
  private directoryValue: V2DirectoryItem[] = [];
  private directoryCursor: { updatedAtEpochMs: bigint; conversationId: string } | null = null;
  private directoryHasMoreValue = false;
  private activeConversationIdValue: string | null = null;
  private connectionStateValue: V2WebSocketTransportState;
  private lastFailureValue = "";
  private selectionGeneration = 0;
  private sessionGeneration = 0;
  private readonly replayedAtGeneration = new Map<string, number>();
  private readonly replayQueues = new Map<string, string[]>();
  private readonly replayInFlight = new Map<string, string>();
  private disposed = false;

  constructor(options: V2WebChatApplicationOptions) {
    this.transport = options.transport;
    this.cache = options.cache;
    this.createClientMessageId = options.createClientMessageId ?? (() => crypto.randomUUID());
    this.now = options.now ?? Date.now;
    this.onChange = options.onChange;
    this.connectionStateValue = this.transport.state;
    this.unsubscribeTransport = this.transport.subscribe({
      onStateChange: (state) => this.handleTransportState(state),
      onProtocolEvent: (event) => this.handleProtocolEvent(event),
      onFailure: (reason) => this.handleFailure(reason),
    });
  }

  get snapshot(): V2WebChatSnapshot {
    const active = this.activeConversationIdValue
      ? this.conversations.get(this.activeConversationIdValue)
      : undefined;
    return {
      connectionState: this.connectionStateValue,
      session: this.sessionValue ? { ...this.sessionValue } : null,
      directory: this.directoryValue.map((item) => ({ ...item })),
      directoryHasMore: this.directoryHasMoreValue,
      activeConversationId: this.activeConversationIdValue,
      messages: active?.messages.map((message) => ({ ...message })) ?? [],
      historyLoading: active?.loading ?? false,
      lastFailure: this.lastFailureValue,
    };
  }

  start(): void {
    this.requireActive();
    this.transport.start();
  }

  authenticate(username: string, passwordUtf8: Uint8Array): void {
    this.requireActive();
    this.transport.authenticate(username, passwordUtf8);
  }

  resumeSession(sessionId: string, resumeToken: Uint8Array): void {
    this.requireActive();
    this.transport.resumeSession(sessionId, resumeToken);
  }

  loadMoreDirectory(): boolean {
    this.requireActive();
    if (!this.sessionValue || !this.directoryHasMoreValue || !this.directoryCursor) return false;
    this.transport.listConversations(DIRECTORY_PAGE_SIZE, this.directoryCursor);
    return true;
  }

  async openConversation(conversationId: string): Promise<void> {
    this.requireActive();
    if (!this.sessionValue || !this.directoryValue.some((item) => item.conversationId === conversationId)) {
      throw new Error("conversation is not present in the authenticated directory");
    }
    const generation = ++this.selectionGeneration;
    this.activeConversationIdValue = conversationId;
    let state = this.conversations.get(conversationId);
    if (!state) {
      state = { messages: [], cursorSequence: "0", loading: true };
      this.conversations.set(conversationId, state);
    } else {
      state.loading = true;
    }
    this.emit();
    try {
      const cached = await this.cache.loadV2(this.sessionValue.accountId, conversationId);
      if (this.disposed || generation !== this.selectionGeneration) return;
      if (cached) {
        state.messages = boundMessages(cached.messages.map(normalizeCachedMessage));
        state.cursorSequence = normalizeSequence(cached.cursorSequence);
      }
    } catch {
      if (generation === this.selectionGeneration) this.lastFailureValue = "V2 cache load failed";
    }
    if (this.disposed || generation !== this.selectionGeneration) return;
    this.transport.readMessageHistory(conversationId, BigInt(state.cursorSequence), HISTORY_PAGE_SIZE);
    this.emit();
  }

  sendText(text: string): V2ConversationCacheMessage {
    this.requireActive();
    if (!this.sessionValue || !this.activeConversationIdValue) throw new Error("no active V2 conversation");
    if (!text || new TextEncoder().encode(text).byteLength > 65_536) {
      throw new Error("text must contain 1..65536 UTF-8 bytes");
    }
    const state = this.requireConversation(this.activeConversationIdValue);
    if (state.messages.filter((message) => message.deliveryState !== "accepted").length >= MAX_PENDING_MESSAGES) {
      throw new Error("V2 pending message limit reached");
    }
    const message: V2ConversationCacheMessage = {
      conversationId: this.activeConversationIdValue,
      id: "",
      clientMessageId: this.createClientMessageId(),
      senderAccountId: this.sessionValue.accountId,
      senderDeviceId: this.sessionValue.deviceId,
      sequence: "0",
      acceptedAtEpochMs: this.now(),
      content: text,
      contentType: "text",
      deliveryState: "sending",
      errorCode: "",
    };
    state.messages = boundMessages([...state.messages, message]);
    try {
      this.transport.submitText(message.conversationId, message.clientMessageId, text);
    } catch {
      message.deliveryState = "failed";
      message.errorCode = "TRANSPORT_UNAVAILABLE";
    }
    this.persist(message.conversationId);
    this.emit();
    return { ...message };
  }

  retryMessage(clientMessageId: string): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const message = state.messages.find((candidate) => candidate.clientMessageId === clientMessageId);
    if (!message || message.deliveryState !== "failed") return false;
    message.deliveryState = "sending";
    message.errorCode = "";
    try {
      this.transport.submitText(message.conversationId, message.clientMessageId, message.content);
    } catch {
      message.deliveryState = "failed";
      message.errorCode = "TRANSPORT_UNAVAILABLE";
    }
    this.persist(message.conversationId);
    this.emit();
    return message.deliveryState === "sending";
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.selectionGeneration += 1;
    this.unsubscribeTransport();
    this.transport.stop();
    this.sessionValue = null;
    this.conversations.clear();
  }

  private handleTransportState(state: V2WebSocketTransportState): void {
    if (this.disposed) return;
    this.connectionStateValue = state;
    if (state === "reconnect-wait" || state === "stopped") {
      this.replayQueues.clear();
      this.replayInFlight.clear();
    }
    this.emit();
  }

  private handleFailure(reason: string): void {
    if (this.disposed) return;
    this.lastFailureValue = reason;
    this.emit();
  }

  private handleProtocolEvent(event: V2WebProtocolEvent): void {
    if (this.disposed) return;
    switch (event.type) {
      case "session-established":
        {
        const sameSession = this.sessionValue?.accountId === event.value.accountId
          && this.sessionValue.sessionId === event.value.sessionId;
        this.sessionGeneration += 1;
        this.replayQueues.clear();
        this.replayInFlight.clear();
        this.sessionValue = {
          accountId: event.value.accountId,
          deviceId: event.value.deviceId,
          sessionId: event.value.sessionId,
          displayName: event.value.displayName,
          expiresAtEpochMs: Number(event.value.expiresAtEpochMs),
        };
        this.directoryValue = [];
        this.directoryCursor = null;
        this.directoryHasMoreValue = false;
        if (!sameSession) {
          this.activeConversationIdValue = null;
          this.conversations.clear();
          this.replayedAtGeneration.clear();
        } else if (this.activeConversationIdValue) {
          const active = this.conversations.get(this.activeConversationIdValue);
          if (active) {
            active.loading = true;
            this.transport.readMessageHistory(
              this.activeConversationIdValue,
              BigInt(active.cursorSequence),
              HISTORY_PAGE_SIZE,
            );
          }
        }
        this.transport.listConversations(DIRECTORY_PAGE_SIZE);
        break;
        }
      case "conversation-directory-page":
        this.applyDirectoryPage(event.value.conversations, event.value.hasMore,
          event.value.nextUpdatedAtEpochMs, event.value.nextConversationId);
        break;
      case "message-history-page":
        this.applyHistoryPage(event.value.conversationId, event.value.messages,
          event.value.nextSequence, event.value.hasMore);
        break;
      case "message-accepted":
        this.applyMessageAccepted(event);
        break;
      case "protocol-error":
        this.applyProtocolError(event);
        break;
      case "authentication-rejected":
        this.lastFailureValue = "Authentication rejected";
        this.sessionValue = null;
        this.directoryValue = [];
        this.directoryCursor = null;
        this.directoryHasMoreValue = false;
        this.activeConversationIdValue = null;
        this.conversations.clear();
        break;
      default:
        break;
    }
    this.emit();
  }

  private applyDirectoryPage(
    records: ConversationDirectoryRecord[],
    hasMore: boolean,
    nextUpdatedAtEpochMs: bigint,
    nextConversationId: string,
  ): void {
    const merged = new Map(this.directoryValue.map((item) => [item.conversationId, item]));
    for (const record of records) merged.set(record.conversationId, mapDirectoryItem(record));
    this.directoryValue = [...merged.values()].sort((left, right) =>
      right.updatedAtEpochMs - left.updatedAtEpochMs
        || right.conversationId.localeCompare(left.conversationId));
    this.directoryHasMoreValue = hasMore;
    this.directoryCursor = records.length > 0
      ? { updatedAtEpochMs: nextUpdatedAtEpochMs, conversationId: nextConversationId }
      : null;
  }

  private applyHistoryPage(
    conversationId: string,
    records: MessageRecord[],
    nextSequence: bigint,
    hasMore: boolean,
  ): void {
    const state = this.conversations.get(conversationId);
    if (!state || !this.sessionValue) return;
    state.messages = mergeMessages(state.messages, records.map(mapMessageRecord));
    state.cursorSequence = maxSequence(state.cursorSequence, nextSequence.toString());
    state.loading = hasMore;
    this.persist(conversationId);
    if (hasMore) {
      this.transport.readMessageHistory(conversationId, BigInt(state.cursorSequence), HISTORY_PAGE_SIZE);
    } else {
      this.replayPendingAfterSync(conversationId, state);
    }
  }

  private applyMessageAccepted(event: Extract<V2WebProtocolEvent, { type: "message-accepted" }>): void {
    const state = this.conversations.get(event.value.conversationId);
    if (!state) return;
    const message = state.messages.find((candidate) => candidate.clientMessageId === event.clientMessageId);
    if (!message) return;
    message.id = event.value.messageId;
    message.sequence = event.value.conversationSequence.toString();
    message.acceptedAtEpochMs = Number(event.value.acceptedAtEpochMs);
    message.deliveryState = "accepted";
    message.errorCode = "";
    state.messages = boundMessages(state.messages);
    this.persist(event.value.conversationId);
    if (this.replayInFlight.get(event.value.conversationId) === event.clientMessageId) {
      this.replayInFlight.delete(event.value.conversationId);
      this.dispatchNextReplay(event.value.conversationId, state);
    }
  }

  private applyProtocolError(event: Extract<V2WebProtocolEvent, { type: "protocol-error" }>): void {
    if (event.clientMessageId) {
      for (const [conversationId, state] of this.conversations) {
        const message = state.messages.find((candidate) => candidate.clientMessageId === event.clientMessageId);
        if (!message) continue;
        message.deliveryState = "failed";
        message.errorCode = `PROTOCOL_${event.value.code}`;
        if (this.replayInFlight.get(conversationId) === event.clientMessageId) {
          this.replayInFlight.delete(conversationId);
          this.failQueuedReplays(conversationId, state, "REPLAY_PAUSED");
        }
        this.persist(conversationId);
        return;
      }
    }
    this.lastFailureValue = event.value.safeMessage || "V2 protocol error";
  }

  private persist(conversationId: string): void {
    if (!this.sessionValue) return;
    const state = this.conversations.get(conversationId);
    if (!state) return;
    void this.cache.saveV2(
      this.sessionValue.accountId,
      conversationId,
      state.messages.map((message) => ({ ...message })),
      state.cursorSequence,
    ).catch(() => {
      this.lastFailureValue = "V2 cache write failed";
      this.emit();
    });
  }

  private replayPendingAfterSync(conversationId: string, state: ConversationState): void {
    if (this.replayedAtGeneration.get(conversationId) === this.sessionGeneration) return;
    this.replayedAtGeneration.set(conversationId, this.sessionGeneration);
    this.replayQueues.set(conversationId, state.messages
      .filter((message) => message.deliveryState === "sending")
      .map((message) => message.clientMessageId));
    this.dispatchNextReplay(conversationId, state);
  }

  private dispatchNextReplay(conversationId: string, state: ConversationState): void {
    if (this.replayInFlight.has(conversationId)) return;
    const queue = this.replayQueues.get(conversationId);
    if (!queue) return;
    let message: V2ConversationCacheMessage | undefined;
    while (queue.length > 0 && !message) {
      const clientMessageId = queue.shift();
      message = state.messages.find((candidate) =>
        candidate.clientMessageId === clientMessageId && candidate.deliveryState === "sending");
    }
    if (!message) {
      this.replayQueues.delete(conversationId);
      return;
    }
    try {
      this.transport.submitText(conversationId, message.clientMessageId, message.content);
      this.replayInFlight.set(conversationId, message.clientMessageId);
    } catch {
      message.deliveryState = "failed";
      message.errorCode = "TRANSPORT_UNAVAILABLE";
      this.failQueuedReplays(conversationId, state, "TRANSPORT_UNAVAILABLE");
      this.persist(conversationId);
    }
  }

  private failQueuedReplays(
    conversationId: string,
    state: ConversationState,
    errorCode: string,
  ): void {
    const queue = this.replayQueues.get(conversationId) ?? [];
    this.replayQueues.delete(conversationId);
    const queued = new Set(queue);
    for (const message of state.messages) {
      if (queued.has(message.clientMessageId) && message.deliveryState === "sending") {
        message.deliveryState = "failed";
        message.errorCode = errorCode;
      }
    }
  }

  private requireConversation(conversationId: string): ConversationState {
    const state = this.conversations.get(conversationId);
    if (!state) throw new Error("conversation has not been opened");
    return state;
  }

  private emit(): void {
    try { this.onChange?.(this.snapshot); } catch { /* views do not own application state */ }
  }

  private requireActive(): void {
    if (this.disposed) throw new Error("V2 Web chat application is disposed");
  }
}

function mapDirectoryItem(record: ConversationDirectoryRecord): V2DirectoryItem {
  return {
    conversationId: record.conversationId,
    kind: record.kind === ConversationKind.DIRECT ? "direct" : "group",
    displayName: record.displayName,
    role: record.role === ConversationRole.OWNER
      ? "owner"
      : record.role === ConversationRole.ADMIN ? "admin" : "member",
    latestSequence: record.latestSequence.toString(),
    lastReadSequence: record.lastReadSequence.toString(),
    updatedAtEpochMs: Number(record.updatedAtEpochMs),
  };
}

function mapMessageRecord(record: MessageRecord): V2ConversationCacheMessage {
  if (record.contentType !== MessageContentType.TEXT_UTF8) throw new Error("unsupported V2 message content");
  return {
    conversationId: record.conversationId,
    id: record.messageId,
    clientMessageId: record.clientMessageId,
    senderAccountId: record.senderAccountId,
    senderDeviceId: record.senderDeviceId,
    sequence: record.conversationSequence.toString(),
    acceptedAtEpochMs: Number(record.acceptedAtEpochMs),
    content: decoder.decode(record.content),
    contentType: "text",
    deliveryState: "accepted",
    errorCode: "",
  };
}

function normalizeCachedMessage(message: V2ConversationCacheMessage): V2ConversationCacheMessage {
  return {
    ...message,
    sequence: normalizeSequence(message.sequence),
    deliveryState: message.deliveryState === "sending" || message.deliveryState === "failed"
      ? message.deliveryState
      : "accepted",
  };
}

function mergeMessages(
  existing: V2ConversationCacheMessage[],
  incoming: V2ConversationCacheMessage[],
): V2ConversationCacheMessage[] {
  const merged = existing.map((message) => ({ ...message }));
  for (const candidate of incoming) {
    const index = merged.findIndex((message) =>
      (message.id && message.id === candidate.id)
      || (message.clientMessageId && message.clientMessageId === candidate.clientMessageId));
    if (index >= 0) merged[index] = { ...merged[index], ...candidate };
    else merged.push(candidate);
  }
  return boundMessages(merged);
}

function boundMessages(messages: V2ConversationCacheMessage[]): V2ConversationCacheMessage[] {
  const accepted = messages
    .filter((message) => message.deliveryState === "accepted")
    .sort((left, right) => compareSequence(left.sequence, right.sequence));
  const unresolved = messages
    .filter((message) => message.deliveryState !== "accepted")
    .slice(-MAX_PENDING_MESSAGES);
  return [...accepted.slice(-MAX_RETAINED_ACCEPTED_MESSAGES), ...unresolved];
}

function normalizeSequence(value: string): string {
  try {
    const sequence = BigInt(value);
    return sequence >= 0n ? sequence.toString() : "0";
  } catch {
    return "0";
  }
}

function maxSequence(left: string, right: string): string {
  return BigInt(left) >= BigInt(right) ? left : right;
}

function compareSequence(left: string, right: string): number {
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}
