import {
  ConversationKind,
  ConversationRole,
  type ConversationDirectoryRecord,
  type ConversationParticipantRecord,
} from "../protocol/v2/generated/conversation_pb";
import {
  MessageContentType,
  MessageReactionKind,
  type ConversationEntryRecord,
  type MessageReactionChangedRecord,
  type MessagePinChangedRecord,
  type MessageEditedRecord,
  type MessageRecord,
} from "../protocol/v2/generated/messaging_pb";
import type { V2WebProtocolEvent } from "../protocol/v2/webProtocolClient";
import { ClientPlatform, ProtocolErrorCode } from "../protocol/v2/generated/control_pb";
import type {
  V2WebSocketTransportObserver,
  V2WebSocketTransportState,
} from "../protocol/v2/webSocketTransport";

const HISTORY_PAGE_SIZE = 100;
const DIRECTORY_PAGE_SIZE = 50;
const PARTICIPANT_PAGE_SIZE = 100;
const MAX_RETAINED_PARTICIPANTS = 500;
const SEARCH_PAGE_SIZE = 50;
const MAX_RETAINED_SEARCH_HITS = 100;
const MAX_RETAINED_ACCEPTED_MESSAGES = 500;
const MAX_PENDING_MESSAGES = 100;
const MAX_PENDING_REACTIONS = 8;
const MAX_PENDING_PINS = 8;
const MAX_PENDING_EDITS = 8;
const MAX_MENTION_SPANS = 20;
const MAX_DISTINCT_MENTION_TARGETS = 10;
const decoder = new TextDecoder("utf-8", { fatal: true });
const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export interface V2ConversationCacheMessage {
  conversationId: string;
  id: string;
  clientMessageId: string;
  senderAccountId: string;
  senderDeviceId: string;
  sequence: string;
  acceptedAtEpochMs: number;
  content: string;
  mentions: V2ConversationMention[];
  contentType: "text";
  deliveryState: "sending" | "accepted" | "failed";
  errorCode: string;
  availability: "available" | "recalled";
  reply: null | {
    targetMessageId: string;
    targetConversationSequence: string;
    targetSenderAccountId: string;
  };
  reactions: Array<{
    reaction: MessageReactionKind;
    actorAccountIds: string[];
  }>;
  pinned: boolean;
  contentRevision: number;
  editedAtEpochMs: number;
  forwarded: boolean;
  forwardSource: null | {
    sourceConversationId: string;
    sourceMessageId: string;
    expectedSourceContentRevision: number;
  };
}

export interface V2ConversationMention {
  targetAccountId: string;
  startUtf8Byte: number;
  lengthUtf8Bytes: number;
}

export interface V2ConversationCacheReactionCommand {
  conversationId: string;
  messageId: string;
  reaction: MessageReactionKind;
  active: boolean;
  clientOperationId: string;
  deliveryState: "sending" | "failed";
  errorCode: string;
  requestId?: string;
}

export interface V2ConversationCachePinCommand {
  conversationId: string; messageId: string; pinned: boolean;
  clientOperationId: string; deliveryState: "sending" | "failed";
  errorCode: string; requestId?: string;
}

export interface V2ConversationCacheEditCommand {
  conversationId: string;
  messageId: string;
  expectedRevision: number;
  proposedContent: string;
  proposedMentions: V2ConversationMention[];
  clientOperationId: string;
  deliveryState: "sending" | "failed" | "conflict";
  errorCode: string;
  requestId?: string;
}

export interface V2ConversationCacheSnapshot {
  messages: V2ConversationCacheMessage[];
  cursorSequence: string;
  reactionCommands?: V2ConversationCacheReactionCommand[];
  pinCommands?: V2ConversationCachePinCommand[];
  editCommands?: V2ConversationCacheEditCommand[];
}

export interface V2ConversationCache {
  loadV2(accountId: string, conversationId: string): Promise<V2ConversationCacheSnapshot | null>;
  saveV2(
    accountId: string,
    conversationId: string,
    messages: V2ConversationCacheMessage[],
    cursorSequence: string,
    reactionCommands?: V2ConversationCacheReactionCommand[],
    pinCommands?: V2ConversationCachePinCommand[],
    editCommands?: V2ConversationCacheEditCommand[],
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
  listConversationParticipants(
    conversationId: string, limit: number, afterAccountId?: string): string;
  readMessageHistory(conversationId: string, afterSequence: bigint, limit: number): void;
  searchConversationMessages?(
    conversationId: string, literalQuery: string, beforeSequence: bigint, limit: number): string;
  submitText(conversationId: string, clientMessageId: string, text: string,
    mentions?: readonly V2ConversationMention[]): void;
  submitReply(
    conversationId: string,
    targetMessageId: string,
    clientMessageId: string,
    text: string,
    mentions?: readonly V2ConversationMention[],
  ): void;
  forwardMessage(
    sourceConversationId: string,
    sourceMessageId: string,
    expectedSourceContentRevision: number,
    targetConversationId: string,
    clientMessageId: string,
  ): void;
  setMessageReaction(
    conversationId: string,
    messageId: string,
    reaction: MessageReactionKind,
    active: boolean,
    clientOperationId: string,
  ): string;
  setMessagePin(conversationId: string, messageId: string, pinned: boolean,
    clientOperationId: string): string;
  editMessage(conversationId: string, messageId: string, expectedRevision: number,
    text: string, clientOperationId: string,
    mentions?: readonly V2ConversationMention[]): string;
  listDevices(): string;
  revokeDevice(targetDeviceId: string): string;
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

export interface V2ManagedDevice {
  deviceId: string;
  platform: "web" | "windows";
  createdAtEpochMs: number;
  lastSeenAtEpochMs: number;
  current: boolean;
}

export interface V2ConversationParticipant {
  accountId: string;
  displayName: string;
  role: "owner" | "admin" | "member";
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
  reactionCommands: V2ConversationCacheReactionCommand[];
  pinCommands: V2ConversationCachePinCommand[];
  editCommands: V2ConversationCacheEditCommand[];
  participants: V2ConversationParticipant[];
  participantsLoading: boolean;
  participantsHasMore: boolean;
  participantFailure: string;
  historyLoading: boolean;
  devices: V2ManagedDevice[];
  devicesLoading: boolean;
  revokingDeviceId: string | null;
  deviceFailure: string;
  lastFailure: string;
  forwardingEnabled: boolean;
  searchEnabled: boolean;
  searchQuery: string;
  searchResults: V2ConversationCacheMessage[];
  searchLoading: boolean;
  searchHasMore: boolean;
  searchFailure: string;
}

export interface V2WebChatApplicationOptions {
  transport: V2ChatTransport;
  cache: V2ConversationCache;
  createClientMessageId?: () => string;
  now?: () => number;
  onChange?: (snapshot: V2WebChatSnapshot) => void;
  enableMessageForwarding?: boolean;
  enableMessageSearch?: boolean;
}

type ConversationState = {
  messages: V2ConversationCacheMessage[];
  reactionCommands: V2ConversationCacheReactionCommand[];
  pinCommands: V2ConversationCachePinCommand[];
  editCommands: V2ConversationCacheEditCommand[];
  cursorSequence: string;
  loading: boolean;
};

type ParticipantState = {
  values: V2ConversationParticipant[];
  nextAccountId: string;
  hasMore: boolean;
  loading: boolean;
  failure: string;
};

type SearchState = {
  query: string;
  results: V2ConversationCacheMessage[];
  nextBeforeSequence: string;
  hasMore: boolean;
  loading: boolean;
  failure: string;
};

export class V2WebChatApplication {
  private readonly transport: V2ChatTransport;
  private readonly cache: V2ConversationCache;
  private readonly createClientMessageId: () => string;
  private readonly now: () => number;
  private readonly onChange?: (snapshot: V2WebChatSnapshot) => void;
  private readonly enableMessageForwarding: boolean;
  private readonly enableMessageSearch: boolean;
  private readonly observers = new Set<(snapshot: V2WebChatSnapshot) => void>();
  private readonly conversations = new Map<string, ConversationState>();
  private readonly participants = new Map<string, ParticipantState>();
  private readonly unsubscribeTransport: () => void;
  private sessionValue: V2WebChatSnapshot["session"] = null;
  private directoryValue: V2DirectoryItem[] = [];
  private directoryCursor: { updatedAtEpochMs: bigint; conversationId: string } | null = null;
  private directoryHasMoreValue = false;
  private activeConversationIdValue: string | null = null;
  private connectionStateValue: V2WebSocketTransportState;
  private lastFailureValue = "";
  private devicesValue: V2ManagedDevice[] = [];
  private devicesLoadingValue = false;
  private revokingDeviceIdValue: string | null = null;
  private deviceFailureValue = "";
  private deviceListRequestId: string | null = null;
  private deviceRevokeRequestId: string | null = null;
  private participantRequest: { requestId: string; conversationId: string } | null = null;
  private searchState: SearchState = emptySearchState();
  private searchRequest: { requestId: string; conversationId: string; append: boolean } | null = null;
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
    this.enableMessageForwarding = options.enableMessageForwarding === true;
    this.enableMessageSearch = options.enableMessageSearch === true;
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
    const participantState = this.activeConversationIdValue
      ? this.participants.get(this.activeConversationIdValue)
      : undefined;
    return {
      connectionState: this.connectionStateValue,
      session: this.sessionValue ? { ...this.sessionValue } : null,
      directory: this.directoryValue.map((item) => ({ ...item })),
      directoryHasMore: this.directoryHasMoreValue,
      activeConversationId: this.activeConversationIdValue,
      messages: active?.messages.map(cloneMessage) ?? [],
      reactionCommands: active?.reactionCommands.map(cloneReactionCommand) ?? [],
      pinCommands: active?.pinCommands.map((value) => ({ ...value })) ?? [],
      editCommands: active?.editCommands.map(cloneEditCommand) ?? [],
      participants: participantState?.values.map((value) => ({ ...value })) ?? [],
      participantsLoading: participantState?.loading ?? false,
      participantsHasMore: participantState?.hasMore ?? false,
      participantFailure: participantState?.failure ?? "",
      historyLoading: active?.loading ?? false,
      devices: this.devicesValue.map((device) => ({ ...device })),
      devicesLoading: this.devicesLoadingValue,
      revokingDeviceId: this.revokingDeviceIdValue,
      deviceFailure: this.deviceFailureValue,
      lastFailure: this.lastFailureValue,
      forwardingEnabled: this.enableMessageForwarding,
      searchEnabled: this.enableMessageSearch,
      searchQuery: this.searchState.query,
      searchResults: this.searchState.results.map(cloneMessage),
      searchLoading: this.searchState.loading,
      searchHasMore: this.searchState.hasMore,
      searchFailure: this.searchState.failure,
    };
  }

  start(): void {
    this.requireActive();
    this.transport.start();
  }

  authenticate(username: string, passwordUtf8: Uint8Array): void {
    this.requireActive();
    this.lastFailureValue = "";
    this.emit();
    this.transport.authenticate(username, passwordUtf8);
  }

  subscribe(observer: (snapshot: V2WebChatSnapshot) => void): () => void {
    this.requireActive();
    this.observers.add(observer);
    try { observer(this.snapshot); } catch { /* views do not own application state */ }
    return () => this.observers.delete(observer);
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

  refreshDevices(): boolean {
    this.requireActive();
    if (!this.sessionValue || this.connectionStateValue !== "authenticated"
        || this.devicesLoadingValue || this.revokingDeviceIdValue) return false;
    this.deviceFailureValue = "";
    this.devicesLoadingValue = true;
    try {
      this.deviceListRequestId = this.transport.listDevices();
    } catch {
      this.devicesLoadingValue = false;
      this.deviceFailureValue = "无法加载登录设备";
      this.emit();
      return false;
    }
    this.emit();
    return true;
  }

  revokeDevice(deviceId: string): boolean {
    this.requireActive();
    if (!this.sessionValue || this.connectionStateValue !== "authenticated"
        || this.devicesLoadingValue || this.revokingDeviceIdValue
        || deviceId === this.sessionValue.deviceId
        || !this.devicesValue.some((device) => device.deviceId === deviceId && !device.current)) {
      return false;
    }
    this.deviceFailureValue = "";
    this.revokingDeviceIdValue = deviceId;
    try {
      this.deviceRevokeRequestId = this.transport.revokeDevice(deviceId);
    } catch {
      this.revokingDeviceIdValue = null;
      this.deviceFailureValue = "无法撤销该设备";
      this.emit();
      return false;
    }
    this.emit();
    return true;
  }

  async openConversation(conversationId: string): Promise<void> {
    this.requireActive();
    if (!this.sessionValue || !this.directoryValue.some((item) => item.conversationId === conversationId)) {
      throw new Error("conversation is not present in the authenticated directory");
    }
    const generation = ++this.selectionGeneration;
    this.abandonParticipantRequest();
    this.clearSearch();
    this.activeConversationIdValue = conversationId;
    let state = this.conversations.get(conversationId);
    if (!state) {
      state = { messages: [], reactionCommands: [], pinCommands: [], editCommands: [],
        cursorSequence: "0", loading: true };
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
        state.reactionCommands = (cached.reactionCommands ?? [])
          .map(normalizeReactionCommand).filter((value): value is V2ConversationCacheReactionCommand => Boolean(value));
        state.pinCommands = (cached.pinCommands ?? []).map(normalizePinCommand)
          .filter((value): value is V2ConversationCachePinCommand => Boolean(value));
        state.editCommands = [];
        const cachedEdits = (cached.editCommands ?? []).map(normalizeEditCommand)
          .filter((value): value is V2ConversationCacheEditCommand => Boolean(value))
          .slice(-MAX_PENDING_EDITS);
        for (const command of cachedEdits) {
          if (!state.editCommands.some((value) => value.messageId === command.messageId
              || value.clientOperationId === command.clientOperationId)) {
            state.editCommands.push(command);
          }
        }
        state.cursorSequence = normalizeSequence(cached.cursorSequence);
      }
    } catch {
      if (generation === this.selectionGeneration) this.lastFailureValue = "V2 cache load failed";
    }
    if (this.disposed || generation !== this.selectionGeneration) return;
    this.transport.readMessageHistory(conversationId, BigInt(state.cursorSequence), HISTORY_PAGE_SIZE);
    this.emit();
  }

  searchMessages(literalQuery: string): boolean {
    this.requireActive();
    const query = literalQuery.trim();
    if (!this.enableMessageSearch || !this.activeConversationIdValue
        || this.connectionStateValue !== "authenticated"
        || !this.transport.searchConversationMessages
        || query.length === 0 || new TextEncoder().encode(query).byteLength > 128) {
      return false;
    }
    this.searchRequest = null;
    this.searchState = {
      query, results: [], nextBeforeSequence: "0", hasMore: false,
      loading: true, failure: "",
    };
    return this.requestSearchPage(false);
  }

  loadMoreSearchResults(): boolean {
    this.requireActive();
    if (!this.searchState.hasMore || this.searchState.loading
        || this.searchState.results.length >= MAX_RETAINED_SEARCH_HITS) return false;
    this.searchState.loading = true;
    this.searchState.failure = "";
    return this.requestSearchPage(true);
  }

  clearSearch(): void {
    this.searchRequest = null;
    this.searchState = emptySearchState();
    this.emit();
  }

  refreshParticipants(): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue || this.connectionStateValue !== "authenticated") {
      return false;
    }
    const state: ParticipantState = {
      values: [], nextAccountId: "", hasMore: false, loading: true, failure: "",
    };
    this.participants.set(this.activeConversationIdValue, state);
    return this.requestParticipantPage(this.activeConversationIdValue, state, "");
  }

  loadMoreParticipants(): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue || this.connectionStateValue !== "authenticated") {
      return false;
    }
    const state = this.participants.get(this.activeConversationIdValue);
    if (!state || state.loading || !state.hasMore || !state.nextAccountId
        || state.values.length >= MAX_RETAINED_PARTICIPANTS) return false;
    state.loading = true;
    state.failure = "";
    return this.requestParticipantPage(
      this.activeConversationIdValue, state, state.nextAccountId);
  }

  sendText(
    text: string,
    mentions: readonly V2ConversationMention[] = [],
  ): V2ConversationCacheMessage {
    return this.submitOptimisticText(text, null, mentions);
  }

  sendReply(
    targetMessageId: string,
    text: string,
    mentions: readonly V2ConversationMention[] = [],
  ): V2ConversationCacheMessage {
    this.requireActive();
    if (!this.activeConversationIdValue) throw new Error("no active V2 conversation");
    const state = this.requireConversation(this.activeConversationIdValue);
    const target = state.messages.find((message) => message.id === targetMessageId);
    if (!target || target.deliveryState !== "accepted" || target.availability !== "available"
        || BigInt(target.sequence) <= 0n) {
      throw new Error("reply target is unavailable");
    }
    return this.submitOptimisticText(text, {
      targetMessageId: target.id,
      targetConversationSequence: target.sequence,
      targetSenderAccountId: target.senderAccountId,
    }, mentions);
  }

  async forwardMessage(
    sourceMessageId: string,
    targetConversationId: string,
  ): Promise<V2ConversationCacheMessage> {
    this.requireActive();
    if (!this.enableMessageForwarding) throw new Error("message forwarding is not enabled");
    const session = this.sessionValue;
    const sourceConversationId = this.activeConversationIdValue;
    if (!session || !sourceConversationId) throw new Error("no active V2 conversation");
    if (!this.directoryValue.some((item) => item.conversationId === targetConversationId)) {
      throw new Error("target conversation is not present in the authenticated directory");
    }
    const sourceState = this.requireConversation(sourceConversationId);
    const source = sourceState.messages.find((message) => message.id === sourceMessageId);
    if (!source || source.deliveryState !== "accepted" || source.availability !== "available") {
      throw new Error("forward source is unavailable");
    }
    const generation = this.sessionGeneration;
    let targetState = this.conversations.get(targetConversationId);
    if (!targetState) {
      const cached = await this.cache.loadV2(session.accountId, targetConversationId);
      if (this.disposed || this.sessionValue?.accountId !== session.accountId
          || this.sessionGeneration !== generation) {
        throw new Error("session changed while preparing forward");
      }
      targetState = hydrateConversationState(cached);
      this.conversations.set(targetConversationId, targetState);
    }
    if (targetState.messages.filter((message) => message.deliveryState !== "accepted").length
        >= MAX_PENDING_MESSAGES) throw new Error("V2 pending message limit reached");
    const message: V2ConversationCacheMessage = {
      conversationId: targetConversationId,
      id: "",
      clientMessageId: this.createClientMessageId(),
      senderAccountId: session.accountId,
      senderDeviceId: session.deviceId,
      sequence: "0",
      acceptedAtEpochMs: this.now(),
      content: source.content,
      mentions: [],
      contentType: "text",
      deliveryState: "sending",
      errorCode: "",
      availability: "available",
      reply: null,
      reactions: [],
      pinned: false,
      contentRevision: 0,
      editedAtEpochMs: 0,
      forwarded: true,
      forwardSource: {
        sourceConversationId,
        sourceMessageId: source.id,
        expectedSourceContentRevision: source.contentRevision,
      },
    };
    targetState.messages = boundMessages([...targetState.messages, message]);
    let persisted = false;
    try {
      persisted = await this.saveConversation(targetConversationId, targetState, session.accountId);
    } catch { /* handled below */ }
    if (!persisted) {
      message.deliveryState = "failed";
      message.errorCode = "CACHE_UNAVAILABLE";
      this.emit();
      return cloneMessage(message);
    }
    try {
      this.dispatchSubmission(message);
    } catch {
      message.deliveryState = "failed";
      message.errorCode = "TRANSPORT_UNAVAILABLE";
      await this.saveConversation(targetConversationId, targetState, session.accountId)
        .catch(() => false);
    }
    this.emit();
    return cloneMessage(message);
  }

  private submitOptimisticText(
    text: string,
    reply: V2ConversationCacheMessage["reply"],
    mentions: readonly V2ConversationMention[],
  ): V2ConversationCacheMessage {
    this.requireActive();
    if (!this.sessionValue || !this.activeConversationIdValue) throw new Error("no active V2 conversation");
    if (!text || new TextEncoder().encode(text).byteLength > 65_536) {
      throw new Error("text must contain 1..65536 UTF-8 bytes");
    }
    const state = this.requireConversation(this.activeConversationIdValue);
    const validMentions = requireComposedMentions(text, mentions);
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
      mentions: validMentions,
      contentType: "text",
      deliveryState: "sending",
      errorCode: "",
      availability: "available",
      reply: reply ? { ...reply } : null,
      reactions: [],
      pinned: false,
      contentRevision: 0,
      editedAtEpochMs: 0,
      forwarded: false,
      forwardSource: null,
    };
    state.messages = boundMessages([...state.messages, message]);
    try {
      this.dispatchSubmission(message);
    } catch {
      message.deliveryState = "failed";
      message.errorCode = "TRANSPORT_UNAVAILABLE";
    }
    this.persist(message.conversationId);
    this.emit();
    return cloneMessage(message);
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
      this.dispatchSubmission(message);
    } catch {
      message.deliveryState = "failed";
      message.errorCode = "TRANSPORT_UNAVAILABLE";
    }
    this.persist(message.conversationId);
    this.emit();
    return message.deliveryState === "sending";
  }

  setReaction(messageId: string, reaction: MessageReactionKind): boolean {
    this.requireActive();
    if (!this.sessionValue || !this.activeConversationIdValue) return false;
    requireReactionKind(reaction);
    const state = this.requireConversation(this.activeConversationIdValue);
    const message = state.messages.find((candidate) => candidate.id === messageId);
    if (!message || message.deliveryState !== "accepted" || message.availability !== "available") return false;
    if (state.reactionCommands.some((command) => command.messageId === messageId
        && command.reaction === reaction && command.deliveryState === "sending")) return false;
    if (state.reactionCommands.length >= MAX_PENDING_REACTIONS) {
      throw new Error("V2 pending reaction limit reached");
    }
    const active = !messageReactionActive(message, reaction, this.sessionValue.accountId);
    const command: V2ConversationCacheReactionCommand = {
      conversationId: this.activeConversationIdValue,
      messageId,
      reaction,
      active,
      clientOperationId: this.createClientMessageId(),
      deliveryState: "sending",
      errorCode: "",
    };
    state.reactionCommands.push(command);
    applyReactionState(message, reaction, this.sessionValue.accountId, active);
    this.persist(command.conversationId);
    try {
      command.requestId = this.transport.setMessageReaction(
        command.conversationId, command.messageId, command.reaction,
        command.active, command.clientOperationId);
    } catch {
      command.deliveryState = "failed";
      command.errorCode = "TRANSPORT_UNAVAILABLE";
    }
    this.persist(command.conversationId);
    this.emit();
    return true;
  }

  retryReaction(clientOperationId: string): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const command = state.reactionCommands.find((value) =>
      value.clientOperationId === clientOperationId && value.deliveryState === "failed");
    if (!command) return false;
    command.deliveryState = "sending";
    command.errorCode = "";
    try {
      command.requestId = this.transport.setMessageReaction(
        command.conversationId, command.messageId, command.reaction,
        command.active, command.clientOperationId);
    } catch {
      command.deliveryState = "failed";
      command.errorCode = "TRANSPORT_UNAVAILABLE";
    }
    this.persist(command.conversationId);
    this.emit();
    return command.deliveryState === "sending";
  }

  setPin(messageId: string): boolean {
    this.requireActive();
    if (!this.sessionValue || !this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const message = state.messages.find((value) => value.id === messageId);
    if (!message || message.deliveryState !== "accepted" || message.availability !== "available"
        || state.pinCommands.some((value) => value.messageId === messageId
          && value.deliveryState === "sending")) return false;
    if (state.pinCommands.length >= MAX_PENDING_PINS) throw new Error("V2 pending pin limit reached");
    const command: V2ConversationCachePinCommand = {
      conversationId: this.activeConversationIdValue, messageId, pinned: !message.pinned,
      clientOperationId: this.createClientMessageId(), deliveryState: "sending", errorCode: "",
    };
    state.pinCommands.push(command); message.pinned = command.pinned; this.persist(command.conversationId);
    try { command.requestId = this.transport.setMessagePin(command.conversationId,
      messageId, command.pinned, command.clientOperationId); }
    catch { command.deliveryState = "failed"; command.errorCode = "TRANSPORT_UNAVAILABLE"; }
    this.persist(command.conversationId); this.emit(); return true;
  }

  retryPin(clientOperationId: string): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const command = state.pinCommands.find((value) => value.clientOperationId === clientOperationId
      && value.deliveryState === "failed");
    if (!command) return false;
    command.deliveryState = "sending"; command.errorCode = "";
    try { command.requestId = this.transport.setMessagePin(command.conversationId,
      command.messageId, command.pinned, command.clientOperationId); }
    catch { command.deliveryState = "failed"; command.errorCode = "TRANSPORT_UNAVAILABLE"; }
    this.persist(command.conversationId); this.emit(); return command.deliveryState === "sending";
  }

  editMessage(
    messageId: string,
    text: string,
    mentions: readonly V2ConversationMention[] = [],
  ): boolean {
    this.requireActive();
    if (!this.sessionValue || !this.activeConversationIdValue) return false;
    requireEditText(text);
    const validMentions = requireComposedMentions(text, mentions);
    const state = this.requireConversation(this.activeConversationIdValue);
    const message = state.messages.find((value) => value.id === messageId);
    if (!message || message.senderAccountId !== this.sessionValue.accountId
        || message.deliveryState !== "accepted" || message.availability !== "available"
        || text === message.content
        || state.editCommands.some((value) => value.messageId === messageId)) return false;
    if (state.editCommands.length >= MAX_PENDING_EDITS) {
      throw new Error("V2 pending edit limit reached");
    }
    const command: V2ConversationCacheEditCommand = {
      conversationId: this.activeConversationIdValue,
      messageId,
      expectedRevision: message.contentRevision,
      proposedContent: text,
      proposedMentions: validMentions,
      clientOperationId: this.createClientMessageId(),
      deliveryState: "sending",
      errorCode: "",
    };
    state.editCommands.push(command);
    this.persist(command.conversationId);
    this.dispatchEdit(command);
    this.persist(command.conversationId);
    this.emit();
    return true;
  }

  retryEdit(clientOperationId: string): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const command = state.editCommands.find((value) =>
      value.clientOperationId === clientOperationId && value.deliveryState === "failed");
    if (!command) return false;
    command.deliveryState = "sending";
    command.errorCode = "";
    this.dispatchEdit(command);
    this.persist(command.conversationId);
    this.emit();
    return command.deliveryState === "sending";
  }

  rebaseEdit(clientOperationId: string): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const command = state.editCommands.find((value) =>
      value.clientOperationId === clientOperationId && value.deliveryState === "conflict");
    const message = command
      ? state.messages.find((value) => value.id === command.messageId)
      : undefined;
    if (!command || !message || message.availability !== "available"
        || message.contentRevision <= command.expectedRevision
        || message.content === command.proposedContent) return false;
    command.expectedRevision = message.contentRevision;
    command.clientOperationId = this.createClientMessageId();
    command.deliveryState = "sending";
    command.errorCode = "";
    command.requestId = undefined;
    this.dispatchEdit(command);
    this.persist(command.conversationId);
    this.emit();
    return command.deliveryState === "sending";
  }

  discardEdit(clientOperationId: string): boolean {
    this.requireActive();
    if (!this.activeConversationIdValue) return false;
    const state = this.requireConversation(this.activeConversationIdValue);
    const before = state.editCommands.length;
    state.editCommands = state.editCommands.filter((value) =>
      value.clientOperationId !== clientOperationId);
    if (state.editCommands.length === before) return false;
    this.persist(this.activeConversationIdValue);
    this.emit();
    return true;
  }

  stop(): void {
    this.requireActive();
    this.selectionGeneration += 1;
    this.sessionGeneration += 1;
    this.sessionValue = null;
    this.directoryValue = [];
    this.directoryCursor = null;
    this.directoryHasMoreValue = false;
    this.activeConversationIdValue = null;
    this.conversations.clear();
    this.participants.clear();
    this.participantRequest = null;
    this.searchRequest = null;
    this.searchState = emptySearchState();
    this.clearDeviceState();
    this.replayedAtGeneration.clear();
    this.replayQueues.clear();
    this.replayInFlight.clear();
    this.transport.stop();
    this.emit();
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.selectionGeneration += 1;
    this.unsubscribeTransport();
    this.observers.clear();
    this.transport.stop();
    this.sessionValue = null;
    this.conversations.clear();
    this.participants.clear();
    this.participantRequest = null;
    this.searchRequest = null;
    this.searchState = emptySearchState();
    this.clearDeviceState();
  }

  private handleTransportState(state: V2WebSocketTransportState): void {
    if (this.disposed) return;
    this.connectionStateValue = state;
    if (state === "reconnect-wait" || state === "stopped") {
      this.replayQueues.clear();
      this.replayInFlight.clear();
    }
    if (state !== "authenticated") {
      this.resetDeviceRequests();
      this.abandonParticipantRequest();
      this.abandonSearchRequest();
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
        this.lastFailureValue = "";
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
        this.searchRequest = null;
        this.searchState = emptySearchState();
        if (!sameSession) {
          this.activeConversationIdValue = null;
          this.conversations.clear();
          this.participants.clear();
          this.participantRequest = null;
          this.replayedAtGeneration.clear();
          this.clearDeviceState();
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
        this.resetDeviceRequests();
        this.transport.listConversations(DIRECTORY_PAGE_SIZE);
        this.refreshDevices();
        break;
        }
      case "conversation-directory-page":
        this.applyDirectoryPage(event.value.conversations, event.value.hasMore,
          event.value.nextUpdatedAtEpochMs, event.value.nextConversationId);
        break;
      case "conversation-participant-page":
        this.applyParticipantPage(event);
        break;
      case "conversation-message-search-page":
        this.applySearchPage(event);
        break;
      case "message-history-page":
        this.applyHistoryPage(event.value.conversationId, event.value.messages,
          event.value.entries,
          event.value.nextSequence, event.value.hasMore);
        break;
      case "message-published":
        this.applyPublishedMessage(event.value);
        break;
      case "message-reaction-changed":
        this.applyReactionChanged(event.value);
        break;
      case "message-reaction-applied":
        this.applyReactionApplied(event);
        break;
      case "message-pin-changed": this.applyPinChanged(event.value); break;
      case "message-pin-applied": this.applyPinApplied(event); break;
      case "message-edited": this.applyEditedRecord(event.value); break;
      case "message-edit-applied": this.applyEditApplied(event); break;
      case "message-accepted":
        this.applyMessageAccepted(event);
        break;
      case "device-directory":
        if (event.requestId !== this.deviceListRequestId) break;
        this.deviceListRequestId = null;
        this.devicesLoadingValue = false;
        this.devicesValue = event.value.devices.map((device) => ({
          deviceId: device.deviceId,
          platform: device.platform === ClientPlatform.WINDOWS ? "windows" : "web",
          createdAtEpochMs: Number(device.createdAtEpochMs),
          lastSeenAtEpochMs: Number(device.lastSeenAtEpochMs),
          current: device.current,
        }));
        this.deviceFailureValue = "";
        break;
      case "device-revoked":
        if (event.requestId !== this.deviceRevokeRequestId
            || event.value.targetDeviceId !== this.revokingDeviceIdValue) break;
        this.deviceRevokeRequestId = null;
        this.revokingDeviceIdValue = null;
        this.devicesValue = this.devicesValue.filter(
          (device) => device.deviceId !== event.value.targetDeviceId);
        this.refreshDevices();
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
        this.participants.clear();
        this.participantRequest = null;
        this.clearDeviceState();
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

  private applyParticipantPage(
    event: Extract<V2WebProtocolEvent, { type: "conversation-participant-page" }>,
  ): void {
    const pending = this.participantRequest;
    if (!pending || event.requestId !== pending.requestId
        || event.value.conversationId !== pending.conversationId
        || this.activeConversationIdValue !== pending.conversationId) return;
    this.participantRequest = null;
    const state = this.participants.get(pending.conversationId);
    if (!state) return;
    const merged = new Map(state.values.map((value) => [value.accountId, value]));
    for (const participant of event.value.participants) {
      merged.set(participant.accountId, mapParticipant(participant));
    }
    state.values = [...merged.values()]
      .sort((left, right) => left.accountId.localeCompare(right.accountId))
      .slice(0, MAX_RETAINED_PARTICIPANTS);
    state.nextAccountId = event.value.nextAccountId;
    state.hasMore = event.value.hasMore
      && state.values.length < MAX_RETAINED_PARTICIPANTS;
    state.loading = false;
    state.failure = "";
  }

  private applyHistoryPage(
    conversationId: string,
    records: MessageRecord[],
    entries: ConversationEntryRecord[],
    nextSequence: bigint,
    hasMore: boolean,
  ): void {
    const state = this.conversations.get(conversationId);
    if (!state || !this.sessionValue) return;
    if (entries.length === 0) {
      state.messages = mergeMessages(state.messages, records.map(mapMessageRecord));
    } else {
      for (const entry of entries) {
        if (entry.detail.case === "message") {
          state.messages = mergeMessages(state.messages, [mapMessageRecord(entry.detail.value)]);
        } else if (entry.detail.case === "recall") {
          const recall = entry.detail.value;
          const recalled = state.messages.find((message) => message.id === recall.messageId);
          if (recalled) {
            recalled.content = "此消息已被撤回";
            recalled.mentions = [];
            recalled.availability = "recalled";
            recalled.pinned = false;
            state.pinCommands = state.pinCommands.filter((value) => value.messageId !== recall.messageId);
            state.editCommands = state.editCommands.filter((value) => value.messageId !== recall.messageId);
          }
        } else if (entry.detail.case === "deletion") {
          const deleted = new Set(entry.detail.value.messageIds);
          state.messages = state.messages.filter((message) => !deleted.has(message.id));
          state.pinCommands = state.pinCommands.filter((value) => !deleted.has(value.messageId));
          state.editCommands = state.editCommands.filter((value) => !deleted.has(value.messageId));
        } else if (entry.detail.case === "reaction") {
          this.applyReactionToState(state, entry.detail.value);
        } else if (entry.detail.case === "pin") {
          this.applyPinToState(state, entry.detail.value);
        } else if (entry.detail.case === "edit") {
          this.applyEditToState(state, entry.detail.value);
        }
      }
    }
    state.cursorSequence = maxSequence(state.cursorSequence, nextSequence.toString());
    state.loading = hasMore;
    this.persist(conversationId);
    if (hasMore) {
      this.transport.readMessageHistory(conversationId, BigInt(state.cursorSequence), HISTORY_PAGE_SIZE);
    } else {
      this.replayPendingReactions(conversationId, state);
      this.replayPendingPins(conversationId, state);
      this.replayPendingEdits(conversationId, state);
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
    message.forwardSource = null;
    state.messages = boundMessages(state.messages);
    this.persist(event.value.conversationId);
    if (this.replayInFlight.get(event.value.conversationId) === event.clientMessageId) {
      this.replayInFlight.delete(event.value.conversationId);
      this.dispatchNextReplay(event.value.conversationId, state);
    }
  }

  private applyPublishedMessage(record: MessageRecord): void {
    const directory = this.directoryValue.find((item) => item.conversationId === record.conversationId);
    if (directory) {
      directory.latestSequence = maxSequence(directory.latestSequence, record.conversationSequence.toString());
      directory.updatedAtEpochMs = Math.max(directory.updatedAtEpochMs, Number(record.acceptedAtEpochMs));
      this.directoryValue.sort((left, right) =>
        right.updatedAtEpochMs - left.updatedAtEpochMs
          || right.conversationId.localeCompare(left.conversationId));
    }
    const state = this.conversations.get(record.conversationId);
    if (!state) return;
    state.messages = mergeMessages(state.messages, [mapMessageRecord(record)]);
    const publishedSequence = record.conversationSequence;
    const cursor = BigInt(state.cursorSequence);
    if (publishedSequence === cursor + 1n) {
      state.cursorSequence = publishedSequence.toString();
    } else if (publishedSequence > cursor + 1n && !state.loading) {
      state.loading = true;
      this.transport.readMessageHistory(record.conversationId, cursor, HISTORY_PAGE_SIZE);
    }
    this.persist(record.conversationId);
  }

  private applyReactionApplied(
    event: Extract<V2WebProtocolEvent, { type: "message-reaction-applied" }>,
  ): void {
    const state = this.conversations.get(event.value.conversationId);
    if (!state) return;
    const command = state.reactionCommands.find((value) =>
      value.clientOperationId === event.value.clientOperationId);
    if (!command) return;
    const message = state.messages.find((value) => value.id === event.value.messageId);
    if (message) applyReactionState(
      message, event.value.reaction, event.value.actorAccountId, event.value.active);
    state.reactionCommands = state.reactionCommands.filter((value) =>
      value.clientOperationId !== event.value.clientOperationId);
    this.persist(event.value.conversationId);
  }

  private applyReactionChanged(record: MessageReactionChangedRecord): void {
    const state = this.conversations.get(record.conversationId);
    if (!state) return;
    this.applyReactionToState(state, record);
    const cursor = BigInt(state.cursorSequence);
    if (record.conversationSequence === cursor + 1n) {
      state.cursorSequence = record.conversationSequence.toString();
    } else if (record.conversationSequence > cursor + 1n && !state.loading) {
      state.loading = true;
      this.transport.readMessageHistory(record.conversationId, cursor, HISTORY_PAGE_SIZE);
    }
    this.persist(record.conversationId);
  }

  private applyReactionToState(
    state: ConversationState,
    record: MessageReactionChangedRecord,
  ): void {
    const message = state.messages.find((value) => value.id === record.messageId);
    if (message) applyReactionState(message, record.reaction, record.actorAccountId, record.active);
    state.reactionCommands = state.reactionCommands.filter((value) =>
      value.clientOperationId !== record.clientOperationId);
  }

  private applyPinApplied(event: Extract<V2WebProtocolEvent, { type: "message-pin-applied" }>): void {
    const state = this.conversations.get(event.value.conversationId); if (!state) return;
    const command = state.pinCommands.find((value) =>
      value.clientOperationId === event.value.clientOperationId); if (!command) return;
    const message = state.messages.find((value) => value.id === event.value.messageId);
    if (message) message.pinned = event.value.pinned;
    state.pinCommands = state.pinCommands.filter((value) =>
      value.clientOperationId !== event.value.clientOperationId);
    this.persist(event.value.conversationId);
  }

  private applyPinChanged(record: MessagePinChangedRecord): void {
    const state = this.conversations.get(record.conversationId); if (!state) return;
    this.applyPinToState(state, record);
    const cursor = BigInt(state.cursorSequence);
    if (record.conversationSequence === cursor + 1n) state.cursorSequence = record.conversationSequence.toString();
    else if (record.conversationSequence > cursor + 1n && !state.loading) {
      state.loading = true; this.transport.readMessageHistory(record.conversationId, cursor, HISTORY_PAGE_SIZE);
    }
    this.persist(record.conversationId);
  }

  private applyPinToState(state: ConversationState, record: MessagePinChangedRecord): void {
    const message = state.messages.find((value) => value.id === record.messageId);
    if (message) message.pinned = record.pinned;
    state.pinCommands = state.pinCommands.filter((value) =>
      value.clientOperationId !== record.clientOperationId);
  }

  private applyEditApplied(event: Extract<V2WebProtocolEvent, { type: "message-edit-applied" }>): void {
    const state = this.conversations.get(event.value.conversationId);
    if (!state) return;
    const command = state.editCommands.find((value) =>
      value.clientOperationId === event.value.clientOperationId);
    if (!command) return;
    const message = state.messages.find((value) => value.id === event.value.messageId);
    if (message && event.value.contentRevision >= message.contentRevision) {
      message.content = decoder.decode(event.value.content);
      message.mentions = mapMentions(event.value.mentions);
      message.contentRevision = event.value.contentRevision;
      message.editedAtEpochMs = Number(event.value.occurredAtEpochMs);
    }
    state.editCommands = state.editCommands.filter((value) =>
      value.clientOperationId !== event.value.clientOperationId);
    this.persist(event.value.conversationId);
  }

  private applyEditedRecord(record: MessageEditedRecord): void {
    const state = this.conversations.get(record.conversationId);
    if (!state) return;
    this.applyEditToState(state, record);
    const cursor = BigInt(state.cursorSequence);
    if (record.conversationSequence === cursor + 1n) {
      state.cursorSequence = record.conversationSequence.toString();
    } else if (record.conversationSequence > cursor + 1n && !state.loading) {
      state.loading = true;
      this.transport.readMessageHistory(record.conversationId, cursor, HISTORY_PAGE_SIZE);
    }
    this.persist(record.conversationId);
  }

  private applyEditToState(state: ConversationState, record: MessageEditedRecord): void {
    const message = state.messages.find((value) => value.id === record.messageId);
    if (message && record.contentRevision >= message.contentRevision) {
      message.content = decoder.decode(record.content);
      message.mentions = mapMentions(record.mentions);
      message.contentRevision = record.contentRevision;
      message.editedAtEpochMs = Number(record.occurredAtEpochMs);
    }
    state.editCommands = state.editCommands.filter((value) =>
      value.clientOperationId !== record.clientOperationId);
  }

  private applyProtocolError(event: Extract<V2WebProtocolEvent, { type: "protocol-error" }>): void {
    if (event.requestId === this.searchRequest?.requestId) {
      this.searchRequest = null;
      this.searchState.loading = false;
      this.searchState.failure = "无法搜索当前会话";
      return;
    }
    if (event.requestId === this.participantRequest?.requestId) {
      const pending = this.participantRequest;
      this.participantRequest = null;
      const state = this.participants.get(pending.conversationId);
      if (state) {
        state.loading = false;
        state.failure = "无法加载会话成员";
      }
      return;
    }
    if (event.requestId === this.deviceListRequestId) {
      this.deviceListRequestId = null;
      this.devicesLoadingValue = false;
      this.deviceFailureValue = "无法加载登录设备";
      return;
    }
    if (event.requestId === this.deviceRevokeRequestId) {
      this.deviceRevokeRequestId = null;
      this.revokingDeviceIdValue = null;
      this.deviceFailureValue = "无法撤销该设备";
      return;
    }
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
    if (event.requestId) {
      for (const [conversationId, state] of this.conversations) {
        const command = state.reactionCommands.find((value) =>
          value.deliveryState === "sending" && value.requestId === event.requestId);
        if (!command) continue;
        command.deliveryState = "failed";
        command.errorCode = `PROTOCOL_${event.value.code}`;
        this.persist(conversationId);
        return;
      }
      for (const [conversationId, state] of this.conversations) {
        const command = state.pinCommands.find((value) =>
          value.deliveryState === "sending" && value.requestId === event.requestId);
        if (!command) continue;
        command.deliveryState = "failed"; command.errorCode = `PROTOCOL_${event.value.code}`;
        this.persist(conversationId); return;
      }
      for (const [conversationId, state] of this.conversations) {
        const command = state.editCommands.find((value) =>
          value.deliveryState === "sending" && value.requestId === event.requestId);
        if (!command) continue;
        command.deliveryState = event.value.code === ProtocolErrorCode.MESSAGE_REVISION_CONFLICT
          ? "conflict" : "failed";
        command.errorCode = `PROTOCOL_${event.value.code}`;
        if (command.deliveryState === "conflict" && !state.loading) {
          state.loading = true;
          this.transport.readMessageHistory(conversationId, BigInt(state.cursorSequence), HISTORY_PAGE_SIZE);
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
    void this.saveConversation(conversationId, state, this.sessionValue.accountId).catch(() => {
      this.lastFailureValue = "V2 cache write failed";
      this.emit();
    });
  }

  private saveConversation(
    conversationId: string,
    state: ConversationState,
    accountId: string,
  ): Promise<boolean> {
    return this.cache.saveV2(
      accountId,
      conversationId,
      state.messages.map(cloneMessage),
      state.cursorSequence,
      state.reactionCommands.map(cloneReactionCommand),
      state.pinCommands.map((value) => ({ ...value })),
      state.editCommands.map(cloneEditCommand),
    );
  }

  private replayPendingAfterSync(conversationId: string, state: ConversationState): void {
    if (this.replayedAtGeneration.get(conversationId) === this.sessionGeneration) return;
    this.replayedAtGeneration.set(conversationId, this.sessionGeneration);
    this.replayQueues.set(conversationId, state.messages
      .filter((message) => message.deliveryState === "sending")
      .map((message) => message.clientMessageId));
    this.dispatchNextReplay(conversationId, state);
  }

  private replayPendingReactions(conversationId: string, state: ConversationState): void {
    for (const command of state.reactionCommands.filter((value) =>
      value.deliveryState === "sending")) {
      try {
        command.requestId = this.transport.setMessageReaction(
          conversationId, command.messageId, command.reaction,
          command.active, command.clientOperationId);
      } catch {
        command.deliveryState = "failed";
        command.errorCode = "TRANSPORT_UNAVAILABLE";
      }
    }
    this.persist(conversationId);
  }

  private replayPendingPins(conversationId: string, state: ConversationState): void {
    for (const command of state.pinCommands.filter((value) => value.deliveryState === "sending")) {
      try { command.requestId = this.transport.setMessagePin(conversationId, command.messageId,
        command.pinned, command.clientOperationId); }
      catch { command.deliveryState = "failed"; command.errorCode = "TRANSPORT_UNAVAILABLE"; }
    }
    this.persist(conversationId);
  }

  private replayPendingEdits(conversationId: string, state: ConversationState): void {
    for (const command of state.editCommands.filter((value) => value.deliveryState === "sending")) {
      this.dispatchEdit(command);
    }
    this.persist(conversationId);
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
      this.dispatchSubmission(message);
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

  private dispatchSubmission(message: V2ConversationCacheMessage): void {
    if (message.forwardSource) {
      this.transport.forwardMessage(
        message.forwardSource.sourceConversationId,
        message.forwardSource.sourceMessageId,
        message.forwardSource.expectedSourceContentRevision,
        message.conversationId,
        message.clientMessageId,
      );
      return;
    }
    if (message.reply) {
      this.transport.submitReply(
        message.conversationId,
        message.reply.targetMessageId,
        message.clientMessageId,
        message.content,
        message.mentions,
      );
      return;
    }
    this.transport.submitText(
      message.conversationId, message.clientMessageId, message.content, message.mentions);
  }

  private dispatchEdit(command: V2ConversationCacheEditCommand): void {
    try {
      command.requestId = this.transport.editMessage(
        command.conversationId, command.messageId, command.expectedRevision,
        command.proposedContent, command.clientOperationId, command.proposedMentions);
    } catch {
      command.deliveryState = "failed";
      command.errorCode = "TRANSPORT_UNAVAILABLE";
    }
  }

  private clearDeviceState(): void {
    this.devicesValue = [];
    this.deviceFailureValue = "";
    this.resetDeviceRequests();
  }

  private requestParticipantPage(
    conversationId: string,
    state: ParticipantState,
    afterAccountId: string,
  ): boolean {
    this.abandonParticipantRequest();
    state.loading = true;
    state.failure = "";
    try {
      const requestId = this.transport.listConversationParticipants(
        conversationId, PARTICIPANT_PAGE_SIZE, afterAccountId);
      this.participantRequest = { requestId, conversationId };
      this.emit();
      return true;
    } catch {
      state.loading = false;
      state.failure = "无法加载会话成员";
      this.emit();
      return false;
    }
  }

  private requestSearchPage(append: boolean): boolean {
    const conversationId = this.activeConversationIdValue;
    const search = this.transport.searchConversationMessages;
    if (!conversationId || !search) {
      this.searchState.loading = false;
      this.searchState.failure = "搜索暂不可用";
      this.emit();
      return false;
    }
    try {
      const requestId = search.call(
        this.transport,
        conversationId,
        this.searchState.query,
        append ? BigInt(this.searchState.nextBeforeSequence) : 0n,
        SEARCH_PAGE_SIZE,
      );
      this.searchRequest = { requestId, conversationId, append };
      this.emit();
      return true;
    } catch {
      this.searchState.loading = false;
      this.searchState.failure = "无法搜索当前会话";
      this.emit();
      return false;
    }
  }

  private applySearchPage(
    event: Extract<V2WebProtocolEvent, { type: "conversation-message-search-page" }>,
  ): void {
    const pending = this.searchRequest;
    if (!pending || event.requestId !== pending.requestId
        || event.value.conversationId !== pending.conversationId
        || this.activeConversationIdValue !== pending.conversationId) return;
    this.searchRequest = null;
    const incoming = event.value.hits.map(mapMessageRecord);
    const merged = pending.append
      ? mergeSearchHits(this.searchState.results, incoming)
      : incoming;
    this.searchState.results = merged.slice(0, MAX_RETAINED_SEARCH_HITS);
    this.searchState.nextBeforeSequence = event.value.nextBeforeSequence.toString();
    this.searchState.hasMore = event.value.hasMore
      && this.searchState.results.length < MAX_RETAINED_SEARCH_HITS;
    this.searchState.loading = false;
    this.searchState.failure = "";
  }

  private abandonSearchRequest(): void {
    if (!this.searchRequest) return;
    this.searchRequest = null;
    this.searchState.loading = false;
  }

  private abandonParticipantRequest(): void {
    if (!this.participantRequest) return;
    const state = this.participants.get(this.participantRequest.conversationId);
    if (state) state.loading = false;
    this.participantRequest = null;
  }

  private resetDeviceRequests(): void {
    this.devicesLoadingValue = false;
    this.revokingDeviceIdValue = null;
    this.deviceListRequestId = null;
    this.deviceRevokeRequestId = null;
  }

  private emit(): void {
    try { this.onChange?.(this.snapshot); } catch { /* views do not own application state */ }
    for (const observer of this.observers) {
      try { observer(this.snapshot); } catch { /* views do not own application state */ }
    }
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

function emptySearchState(): SearchState {
  return {
    query: "",
    results: [],
    nextBeforeSequence: "0",
    hasMore: false,
    loading: false,
    failure: "",
  };
}

function mergeSearchHits(
  current: V2ConversationCacheMessage[],
  incoming: V2ConversationCacheMessage[],
): V2ConversationCacheMessage[] {
  const byId = new Map(current.map((message) => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((left, right) => {
    const leftSequence = BigInt(left.sequence);
    const rightSequence = BigInt(right.sequence);
    return leftSequence === rightSequence ? right.id.localeCompare(left.id)
      : leftSequence > rightSequence ? -1 : 1;
  });
}

function mapParticipant(record: ConversationParticipantRecord): V2ConversationParticipant {
  return {
    accountId: record.accountId,
    displayName: record.displayName,
    role: record.role === ConversationRole.OWNER
      ? "owner" : record.role === ConversationRole.ADMIN ? "admin" : "member",
  };
}

function hydrateConversationState(
  cached: V2ConversationCacheSnapshot | null,
): ConversationState {
  const state: ConversationState = {
    messages: [], reactionCommands: [], pinCommands: [], editCommands: [],
    cursorSequence: "0", loading: false,
  };
  if (!cached) return state;
  state.messages = boundMessages(cached.messages.map(normalizeCachedMessage));
  state.reactionCommands = (cached.reactionCommands ?? [])
    .map(normalizeReactionCommand)
    .filter((value): value is V2ConversationCacheReactionCommand => Boolean(value));
  state.pinCommands = (cached.pinCommands ?? [])
    .map(normalizePinCommand)
    .filter((value): value is V2ConversationCachePinCommand => Boolean(value));
  for (const command of (cached.editCommands ?? [])
    .map(normalizeEditCommand)
    .filter((value): value is V2ConversationCacheEditCommand => Boolean(value))
    .slice(-MAX_PENDING_EDITS)) {
    if (!state.editCommands.some((value) => value.messageId === command.messageId
        || value.clientOperationId === command.clientOperationId)) {
      state.editCommands.push(command);
    }
  }
  state.cursorSequence = normalizeSequence(cached.cursorSequence);
  return state;
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
    mentions: mapMentions(record.mentions),
    contentType: "text",
    deliveryState: "accepted",
    errorCode: "",
    availability: "available",
    reply: record.reply ? {
      targetMessageId: record.reply.targetMessageId,
      targetConversationSequence: record.reply.targetConversationSequence.toString(),
      targetSenderAccountId: record.reply.targetSenderAccountId,
    } : null,
    reactions: [],
    pinned: false,
    contentRevision: record.contentRevision,
    editedAtEpochMs: Number(record.editedAtEpochMs),
    forwarded: record.forwarded,
    forwardSource: null,
  };
}

function normalizeCachedMessage(message: V2ConversationCacheMessage): V2ConversationCacheMessage {
  const candidateRevision = normalizeContentRevision(message.contentRevision);
  const candidateEditedAt = Math.max(0, Number(message.editedAtEpochMs) || 0);
  const validEditMetadata = (candidateRevision === 0) === (candidateEditedAt === 0);
  return {
    ...message,
    sequence: normalizeSequence(message.sequence),
    deliveryState: message.deliveryState === "sending" || message.deliveryState === "failed"
      ? message.deliveryState
      : "accepted",
    availability: message.availability === "recalled" ? "recalled" : "available",
    mentions: message.availability === "recalled"
      ? []
      : normalizeMentions(message.content, message.mentions),
    reply: normalizeReply(message.reply),
    reactions: normalizeReactions(message.reactions),
    pinned: Boolean(message.pinned),
    contentRevision: validEditMetadata ? candidateRevision : 0,
    editedAtEpochMs: validEditMetadata ? candidateEditedAt : 0,
    forwarded: Boolean(message.forwarded),
    forwardSource: normalizeForwardSource(message),
  };
}

function normalizeReply(
  reply: V2ConversationCacheMessage["reply"],
): V2ConversationCacheMessage["reply"] {
  if (!reply || !canonicalUuid.test(reply.targetMessageId)
      || !canonicalUuid.test(reply.targetSenderAccountId)) return null;
  const sequence = normalizeSequence(reply.targetConversationSequence);
  return sequence === "0" ? null : { ...reply, targetConversationSequence: sequence };
}

function cloneMessage(message: V2ConversationCacheMessage): V2ConversationCacheMessage {
  return {
    ...message,
    reply: message.reply ? { ...message.reply } : null,
    reactions: message.reactions.map((value) => ({
      reaction: value.reaction,
      actorAccountIds: [...value.actorAccountIds],
    })),
    mentions: message.mentions.map((value) => ({ ...value })),
    pinned: message.pinned,
    contentRevision: message.contentRevision,
    editedAtEpochMs: message.editedAtEpochMs,
    forwarded: message.forwarded,
    forwardSource: message.forwardSource ? { ...message.forwardSource } : null,
  };
}

function normalizeForwardSource(
  message: V2ConversationCacheMessage,
): V2ConversationCacheMessage["forwardSource"] {
  const source = message.forwardSource;
  if (message.deliveryState === "accepted" || !source
      || !canonicalUuid.test(source.sourceConversationId)
      || !canonicalUuid.test(source.sourceMessageId)
      || !Number.isInteger(source.expectedSourceContentRevision)
      || source.expectedSourceContentRevision < 0
      || source.expectedSourceContentRevision > 100) return null;
  return { ...source };
}

function normalizePinCommand(value: V2ConversationCachePinCommand): V2ConversationCachePinCommand | null {
  if (!canonicalUuid.test(value?.conversationId) || !canonicalUuid.test(value?.messageId)
      || !value.clientOperationId) return null;
  return { ...value, pinned: Boolean(value.pinned),
    deliveryState: value.deliveryState === "failed" ? "failed" : "sending",
    errorCode: typeof value.errorCode === "string" ? value.errorCode : "" };
}

function normalizeEditCommand(value: V2ConversationCacheEditCommand): V2ConversationCacheEditCommand | null {
  if (!canonicalUuid.test(value?.conversationId) || !canonicalUuid.test(value?.messageId)
      || !value.clientOperationId || value.clientOperationId.length > 128
      || !Number.isInteger(value.expectedRevision) || value.expectedRevision < 0
      || value.expectedRevision > 100) return null;
  try { requireEditText(value.proposedContent); } catch { return null; }
  return {
    conversationId: value.conversationId,
    messageId: value.messageId,
    expectedRevision: value.expectedRevision,
    proposedContent: value.proposedContent,
    proposedMentions: normalizeMentions(value.proposedContent, value.proposedMentions),
    clientOperationId: value.clientOperationId,
    deliveryState: value.deliveryState === "failed" || value.deliveryState === "conflict"
      ? value.deliveryState : "sending",
    errorCode: typeof value.errorCode === "string" ? value.errorCode : "",
  };
}

function cloneEditCommand(command: V2ConversationCacheEditCommand): V2ConversationCacheEditCommand {
  return { ...command, proposedMentions: command.proposedMentions.map((value) => ({ ...value })) };
}

function mapMentions(values: readonly {
  targetAccountId: string; startUtf8Byte: number; lengthUtf8Bytes: number;
}[]): V2ConversationMention[] {
  return values.map((value) => ({
    targetAccountId: value.targetAccountId,
    startUtf8Byte: value.startUtf8Byte,
    lengthUtf8Bytes: value.lengthUtf8Bytes,
  }));
}

function normalizeMentions(
  content: string,
  values: readonly V2ConversationMention[] | undefined,
): V2ConversationMention[] {
  if (!Array.isArray(values) || values.length === 0) return [];
  if (values.length > MAX_MENTION_SPANS) return [];
  const bytes = new TextEncoder().encode(content);
  const targets = new Set<string>();
  const result: V2ConversationMention[] = [];
  let previousEnd = 0;
  for (const value of values) {
    const start = Number(value?.startUtf8Byte);
    const length = Number(value?.lengthUtf8Bytes);
    const end = start + length;
    if (!canonicalUuid.test(value?.targetAccountId ?? "")
        || !Number.isInteger(start) || !Number.isInteger(length)
        || start < previousEnd || length < 1 || !Number.isSafeInteger(end)
        || end > bytes.byteLength || !isUtf8Boundary(bytes, start)
        || !isUtf8Boundary(bytes, end) || bytes[start] !== 0x40) return [];
    targets.add(value.targetAccountId);
    if (targets.size > MAX_DISTINCT_MENTION_TARGETS) return [];
    result.push({ targetAccountId: value.targetAccountId,
      startUtf8Byte: start, lengthUtf8Bytes: length });
    previousEnd = end;
  }
  return result;
}

function requireComposedMentions(
  content: string,
  values: readonly V2ConversationMention[],
): V2ConversationMention[] {
  const normalized = normalizeMentions(content, values);
  if (normalized.length !== values.length) {
    throw new Error("message mentions do not match the composed text");
  }
  return normalized;
}

function isUtf8Boundary(content: Uint8Array, index: number): boolean {
  return index === 0 || index === content.byteLength || (content[index]! & 0xc0) !== 0x80;
}

function normalizeContentRevision(value: number): number {
  return Number.isInteger(value) && value >= 0 && value <= 100 ? value : 0;
}

function requireEditText(value: string): void {
  if (typeof value !== "string" || !value
      || new TextEncoder().encode(value).byteLength > 65_536) {
    throw new Error("text must contain 1..65536 UTF-8 bytes");
  }
}

function cloneReactionCommand(
  command: V2ConversationCacheReactionCommand,
): V2ConversationCacheReactionCommand {
  return { ...command };
}

function normalizeReactionCommand(value: V2ConversationCacheReactionCommand):
    V2ConversationCacheReactionCommand | null {
  try { requireReactionKind(value.reaction); } catch { return null; }
  if (!canonicalUuid.test(value.conversationId) || !canonicalUuid.test(value.messageId)
      || !value.clientOperationId) return null;
  return {
    ...value,
    deliveryState: value.deliveryState === "failed" ? "failed" : "sending",
    errorCode: typeof value.errorCode === "string" ? value.errorCode : "",
  };
}

function normalizeReactions(value: V2ConversationCacheMessage["reactions"] | undefined):
    V2ConversationCacheMessage["reactions"] {
  if (!Array.isArray(value)) return [];
  const result: V2ConversationCacheMessage["reactions"] = [];
  for (const item of value) {
    try { requireReactionKind(item.reaction); } catch { continue; }
    const actors = [...new Set(item.actorAccountIds.filter((id) => canonicalUuid.test(id)))].sort();
    if (actors.length > 0) result.push({ reaction: item.reaction, actorAccountIds: actors });
  }
  return result.sort((left, right) => left.reaction - right.reaction);
}

function applyReactionState(
  message: V2ConversationCacheMessage,
  reaction: MessageReactionKind,
  actorAccountId: string,
  active: boolean,
): void {
  requireReactionKind(reaction);
  let aggregate = message.reactions.find((value) => value.reaction === reaction);
  if (!aggregate && active) {
    aggregate = { reaction, actorAccountIds: [] };
    message.reactions.push(aggregate);
  }
  if (!aggregate) return;
  const actors = new Set(aggregate.actorAccountIds);
  if (active) actors.add(actorAccountId); else actors.delete(actorAccountId);
  aggregate.actorAccountIds = [...actors].sort();
  message.reactions = message.reactions.filter((value) => value.actorAccountIds.length > 0)
    .sort((left, right) => left.reaction - right.reaction);
}

function messageReactionActive(
  message: V2ConversationCacheMessage,
  reaction: MessageReactionKind,
  actorAccountId: string,
): boolean {
  return message.reactions.some((value) => value.reaction === reaction
    && value.actorAccountIds.includes(actorAccountId));
}

function requireReactionKind(value: MessageReactionKind): void {
  if (![MessageReactionKind.LIKE, MessageReactionKind.LOVE, MessageReactionKind.LAUGH,
    MessageReactionKind.SURPRISED, MessageReactionKind.SAD,
    MessageReactionKind.ANGRY].includes(value)) throw new Error("unsupported reaction");
}

function mergeMessages(
  existing: V2ConversationCacheMessage[],
  incoming: V2ConversationCacheMessage[],
): V2ConversationCacheMessage[] {
  const merged = existing.map(cloneMessage);
  for (const candidate of incoming) {
    const index = merged.findIndex((message) =>
      (message.id && message.id === candidate.id)
      || (message.clientMessageId && message.clientMessageId === candidate.clientMessageId));
    if (index >= 0) merged[index] = {
      ...merged[index],
      ...candidate,
      reactions: candidate.reactions.length > 0
        ? candidate.reactions
        : merged[index]!.reactions,
    };
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
