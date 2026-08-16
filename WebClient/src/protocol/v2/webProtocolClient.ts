import { create, fromBinary, toBinary } from "@bufbuild/protobuf";

import {
  AuthenticateSchema,
  AuthenticationRejectedSchema,
  ResumeSessionSchema,
  SessionEstablishedSchema,
  type AuthenticationRejected,
  type SessionEstablished,
} from "./generated/authentication_pb";
import {
  ConversationDirectoryPageSchema,
  ConversationParticipantPageSchema,
  ConversationRole,
  ListConversationParticipantsSchema,
  ListConversationsSchema,
  type ConversationDirectoryPage,
  type ConversationParticipantPage,
} from "./generated/conversation_pb";
import {
  ClientHelloSchema,
  ClientCapability,
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
  MessageReactionAppliedSchema,
  MessageReactionChangedRecordSchema,
  MessagePinAppliedSchema,
  MessagePinChangedRecordSchema,
  EditMessageSchema,
  ForwardMessageSchema,
  SearchConversationMessagesSchema,
  ConversationMessageSearchPageSchema,
  MessageEditAppliedSchema,
  MessageEditedRecordSchema,
  MessageReactionKind,
  MessageContentType,
  MessageHistoryPageSchema,
  MessageMentionSchema,
  MessageRecordSchema,
  ReadMessageHistorySchema,
  SubmitMessageSchema,
  SubmitReplyMessageSchema,
  SetMessageReactionSchema,
  SetMessagePinSchema,
  type MessageAccepted,
  type MessageHistoryPage,
  type MessageRecord,
  type MessageReactionApplied,
  type MessageReactionChangedRecord,
  type MessagePinApplied,
  type MessagePinChangedRecord,
  type MessageEditApplied,
  type MessageEditedRecord,
  type MessageMention,
  type ConversationMessageSearchPage,
} from "./generated/messaging_pb";
import {
  AttachmentReadySchema,
  AttachmentRegisteredSchema,
  AttachmentUploadAuthorizedSchema,
  AuthorizeAttachmentUploadSchema,
  CompleteAttachmentUploadSchema,
  RegisterAttachmentSchema,
  type AttachmentReady,
  type AttachmentRegistered,
  type AttachmentUploadAuthorized,
} from "./generated/attachment_pb";
import {
  DeviceDirectorySchema,
  DeviceRevokedSchema,
  ListDevicesSchema,
  RevokeDeviceSchema,
  type DeviceDirectory,
  type DeviceRevoked,
} from "./generated/device_management_pb";
import {
  AccountBlockAppliedSchema,
  AccountBlockDirectoryPageSchema,
  ListAccountBlocksSchema,
  SetAccountBlockSchema,
  type AccountBlockApplied,
  type AccountBlockDirectoryPage,
} from "./generated/contact_pb";

const PROTOCOL_VERSION = 2;
const MAX_IDENTIFIER_BYTES = 128;
const MAX_PAYLOAD_BYTES = 1024 * 1024;
const MAX_WIRE_BYTES = MAX_PAYLOAD_BYTES + 1024;
const MAX_PASSWORD_BYTES = 1024;
const MAX_TEXT_BYTES = 65_536;
const MAX_PAGE_SIZE = 100;
const MAX_CONTENT_REVISIONS = 100;
const MAX_MENTION_SPANS = 20;
const MAX_DISTINCT_MENTION_TARGETS = 10;
const MAX_DELETION_TARGETS = 1_000;
const MAX_PENDING_REQUESTS = 16;
const MAX_CANCELLED_REQUESTS = 32;
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

type ResponseCorrelation = { requestId: string; clientMessageId: string };

export type V2WebProtocolEvent = ResponseCorrelation & (
  | { type: "server-hello"; value: ServerHello }
  | { type: "session-established"; value: SessionEstablished }
  | { type: "authentication-rejected"; value: AuthenticationRejected }
  | { type: "protocol-error"; value: ProtocolError }
  | { type: "message-accepted"; value: MessageAccepted }
  | { type: "message-history-page"; value: MessageHistoryPage }
  | { type: "message-published"; value: MessageRecord }
  | { type: "message-reaction-applied"; value: MessageReactionApplied }
  | { type: "message-reaction-changed"; value: MessageReactionChangedRecord }
  | { type: "message-pin-applied"; value: MessagePinApplied }
  | { type: "message-pin-changed"; value: MessagePinChangedRecord }
  | { type: "message-edit-applied"; value: MessageEditApplied }
  | { type: "message-edited"; value: MessageEditedRecord }
  | { type: "conversation-directory-page"; value: ConversationDirectoryPage }
  | { type: "conversation-participant-page"; value: ConversationParticipantPage }
  | { type: "conversation-message-search-page"; value: ConversationMessageSearchPage }
  | { type: "attachment-registered"; value: AttachmentRegistered }
  | { type: "attachment-upload-authorized"; value: AttachmentUploadAuthorized }
  | { type: "attachment-ready"; value: AttachmentReady }
  | { type: "device-directory"; value: DeviceDirectory }
  | { type: "device-revoked"; value: DeviceRevoked }
  | { type: "account-block-applied"; value: AccountBlockApplied }
  | { type: "account-block-directory-page"; value: AccountBlockDirectoryPage }
  | { type: "cancelled-response"; value: undefined }
);

type PendingRequest = {
  expected: ReadonlySet<MessageType>;
  clientMessageId: string;
  cancelled: boolean;
  accountBlock?: Readonly<{
    targetAccountId: string;
    blocked: boolean;
    clientOperationId: string;
  }>;
  accountBlockDirectory?: Readonly<{ afterTargetAccountId: string; limit: number }>;
};
export type V2CorrelatedCommand = { requestId: string; bytes: Uint8Array };

export interface V2WebProtocolClientOptions {
  appVersion: string;
  clientDeviceId: string;
  createRequestId?: () => string;
  now?: () => number;
  enableMessageEdits?: boolean;
  enableMessageMentions?: boolean;
  enableMessageForwarding?: boolean;
  enableMessageSearch?: boolean;
  enableAccountBlocking?: boolean;
}

export type V2MessageMention = Readonly<{
  targetAccountId: string;
  startUtf8Byte: number;
  lengthUtf8Bytes: number;
}>;

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
  private readonly requestedCapabilities: readonly ClientCapability[];

  constructor(options: V2WebProtocolClientOptions) {
    requireUtf8("appVersion", options.appVersion, 1, 64);
    requireUtf8("clientDeviceId", options.clientDeviceId, 1, 128);
    this.appVersion = options.appVersion;
    this.clientDeviceId = options.clientDeviceId;
    this.createRequestId = options.createRequestId ?? (() => crypto.randomUUID());
    this.now = options.now ?? Date.now;
    this.requestedCapabilities = [
      ClientCapability.MESSAGE_REACTIONS,
      ClientCapability.MESSAGE_PINS,
      ...(options.enableMessageEdits ? [ClientCapability.MESSAGE_EDITS] : []),
      ...(options.enableMessageMentions ? [ClientCapability.MESSAGE_MENTIONS] : []),
      ...(options.enableMessageForwarding ? [ClientCapability.MESSAGE_FORWARDING] : []),
      ...(options.enableMessageSearch ? [ClientCapability.MESSAGE_SEARCH] : []),
      ...(options.enableAccountBlocking ? [ClientCapability.ACCOUNT_BLOCKING] : []),
    ];
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
      capabilities: [...this.requestedCapabilities],
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

  resumeSession(sessionId: string, resumeToken: Uint8Array): Uint8Array {
    this.requireState("negotiated");
    requireUuid("sessionId", sessionId);
    if (resumeToken.byteLength !== 32) throw new Error("resumeToken must contain exactly 32 bytes");
    const transientToken = resumeToken.slice();
    try {
      const payload = toBinary(ResumeSessionSchema, create(ResumeSessionSchema, {
        sessionId,
        resumeToken: transientToken,
      }));
      const bytes = this.command(
        MessageType.RESUME_SESSION,
        payload,
        new Set([MessageType.SESSION_ESTABLISHED, MessageType.AUTHENTICATION_REJECTED]),
      );
      this.currentState = "authentication-sent";
      return bytes;
    } finally {
      transientToken.fill(0);
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

  listConversationParticipants(
    conversationId: string,
    limit: number,
    afterAccountId = "",
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    if (!this.mentionsEnabled()) {
      throw new Error("message mentions were not enabled for this client");
    }
    requireUuid("conversationId", conversationId);
    requirePageSize(limit);
    if (afterAccountId) requireUuid("afterAccountId", afterAccountId);
    return correlated(this.command(
      MessageType.LIST_CONVERSATION_PARTICIPANTS,
      toBinary(ListConversationParticipantsSchema, create(ListConversationParticipantsSchema, {
        conversationId, afterAccountId, limit,
      })),
      new Set([MessageType.CONVERSATION_PARTICIPANT_PAGE]),
    ));
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

  readMessageContext(
    conversationId: string,
    afterSequence: bigint,
    limit: number,
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    if (afterSequence < 0n || afterSequence > MAX_SIGNED_SEQUENCE) {
      throw new Error("afterSequence must be in the signed server range");
    }
    requirePageSize(limit);
    return correlated(this.command(
      MessageType.READ_MESSAGE_HISTORY,
      toBinary(ReadMessageHistorySchema, create(ReadMessageHistorySchema, {
        conversationId, afterSequence, limit,
      })),
      new Set([MessageType.MESSAGE_HISTORY_PAGE]),
    ));
  }

  searchConversationMessages(
    conversationId: string,
    literalQuery: string,
    beforeSequence: bigint,
    limit: number,
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    if (!this.searchEnabled()) throw new Error("message search was not enabled for this client");
    requireUuid("conversationId", conversationId);
    if (literalQuery !== literalQuery.trim()) {
      throw new Error("literalQuery must already be stripped");
    }
    requireUtf8("literalQuery", literalQuery, 1, 128);
    if (beforeSequence < 0n || beforeSequence > MAX_SIGNED_SEQUENCE) {
      throw new Error("beforeSequence must be in the signed server range");
    }
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
      throw new Error("search limit must be an integer in 1..50");
    }
    return correlated(this.command(
      MessageType.SEARCH_CONVERSATION_MESSAGES,
      toBinary(SearchConversationMessagesSchema, create(SearchConversationMessagesSchema, {
        conversationId, literalQuery, beforeSequence, limit,
      })),
      new Set([MessageType.CONVERSATION_MESSAGE_SEARCH_PAGE]),
    ));
  }

  setAccountBlock(
    targetAccountId: string,
    blocked: boolean,
    clientOperationId: string,
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    if (!this.accountBlockingEnabled()) {
      throw new Error("account blocking was not enabled for this client");
    }
    requireUuid("targetAccountId", targetAccountId);
    requireUuid("clientOperationId", clientOperationId);
    if (targetAccountId === this.currentSession?.accountId) {
      throw new Error("account block target must differ from the authenticated account");
    }
    return correlated(this.command(
      MessageType.SET_ACCOUNT_BLOCK,
      toBinary(SetAccountBlockSchema, create(SetAccountBlockSchema, {
        targetAccountId, blocked, clientOperationId,
      })),
      new Set([MessageType.ACCOUNT_BLOCK_APPLIED]),
      clientOperationId,
      { targetAccountId, blocked, clientOperationId },
    ));
  }

  listAccountBlocks(afterTargetAccountId = "", limit = 100): V2CorrelatedCommand {
    this.requireState("authenticated");
    if (!this.accountBlockingEnabled()) {
      throw new Error("account blocking was not enabled for this client");
    }
    if (afterTargetAccountId) requireUuid("afterTargetAccountId", afterTargetAccountId);
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new Error("account block directory limit must be an integer in 1..100");
    }
    return correlated(this.command(
      MessageType.LIST_ACCOUNT_BLOCKS,
      toBinary(ListAccountBlocksSchema, create(ListAccountBlocksSchema, {
        afterTargetAccountId, limit,
      })),
      new Set([MessageType.ACCOUNT_BLOCK_DIRECTORY_PAGE]),
      "",
      undefined,
      { afterTargetAccountId, limit },
    ));
  }

  submitText(conversationId: string, clientMessageId: string, text: string,
    mentions: readonly V2MessageMention[] = []): Uint8Array {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    requireIdentifier("clientMessageId", clientMessageId);
    const content = encoder.encode(text);
    if (content.byteLength < 1 || content.byteLength > MAX_TEXT_BYTES) {
      throw new Error("text must contain 1..65536 UTF-8 bytes");
    }
    const validMentions = this.requireOutboundMentions(content, mentions);
    const payload = toBinary(SubmitMessageSchema, create(SubmitMessageSchema, {
      conversationId,
      contentType: MessageContentType.TEXT_UTF8,
      content,
      mentions: validMentions,
    }));
    return this.command(
      MessageType.SUBMIT_MESSAGE,
      payload,
      new Set([MessageType.MESSAGE_ACCEPTED]),
      clientMessageId,
    );
  }

  submitReply(
    conversationId: string,
    targetMessageId: string,
    clientMessageId: string,
    text: string,
    mentions: readonly V2MessageMention[] = [],
  ): Uint8Array {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    requireUuid("targetMessageId", targetMessageId);
    requireIdentifier("clientMessageId", clientMessageId);
    const content = encoder.encode(text);
    if (content.byteLength < 1 || content.byteLength > MAX_TEXT_BYTES) {
      throw new Error("text must contain 1..65536 UTF-8 bytes");
    }
    const validMentions = this.requireOutboundMentions(content, mentions);
    const payload = toBinary(SubmitReplyMessageSchema, create(SubmitReplyMessageSchema, {
      conversationId,
      targetMessageId,
      contentType: MessageContentType.TEXT_UTF8,
      content,
      mentions: validMentions,
    }));
    return this.command(
      MessageType.SUBMIT_REPLY_MESSAGE,
      payload,
      new Set([MessageType.MESSAGE_ACCEPTED]),
      clientMessageId,
    );
  }

  forwardMessage(
    sourceConversationId: string,
    sourceMessageId: string,
    expectedSourceContentRevision: number,
    targetConversationId: string,
    clientMessageId: string,
  ): Uint8Array {
    this.requireState("authenticated");
    if (!this.forwardingEnabled()) {
      throw new Error("message forwarding was not enabled for this client");
    }
    requireUuid("sourceConversationId", sourceConversationId);
    requireUuid("sourceMessageId", sourceMessageId);
    requireUuid("targetConversationId", targetConversationId);
    requireIdentifier("clientMessageId", clientMessageId);
    if (!Number.isInteger(expectedSourceContentRevision)
        || expectedSourceContentRevision < 0
        || expectedSourceContentRevision > MAX_CONTENT_REVISIONS) {
      throw new Error("expectedSourceContentRevision must be an integer in 0..100");
    }
    const payload = toBinary(ForwardMessageSchema, create(ForwardMessageSchema, {
      sourceConversationId,
      sourceMessageId,
      expectedSourceContentRevision,
      targetConversationId,
    }));
    return this.command(
      MessageType.FORWARD_MESSAGE,
      payload,
      new Set([MessageType.MESSAGE_ACCEPTED]),
      clientMessageId,
    );
  }

  setMessageReaction(
    conversationId: string,
    messageId: string,
    reaction: MessageReactionKind,
    active: boolean,
    clientOperationId: string,
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    requireUuid("messageId", messageId);
    requireIdentifier("clientOperationId", clientOperationId);
    requireReaction(reaction);
    const payload = toBinary(SetMessageReactionSchema, create(SetMessageReactionSchema, {
      conversationId,
      messageId,
      reaction,
      active,
      clientOperationId,
    }));
    return correlated(this.command(
      MessageType.SET_MESSAGE_REACTION,
      payload,
      new Set([MessageType.MESSAGE_REACTION_APPLIED]),
    ));
  }

  setMessagePin(
    conversationId: string,
    messageId: string,
    pinned: boolean,
    clientOperationId: string,
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    requireUuid("messageId", messageId);
    requireIdentifier("clientOperationId", clientOperationId);
    const payload = toBinary(SetMessagePinSchema, create(SetMessagePinSchema, {
      conversationId, messageId, pinned, clientOperationId,
    }));
    return correlated(this.command(
      MessageType.SET_MESSAGE_PIN, payload, new Set([MessageType.MESSAGE_PIN_APPLIED])));
  }

  editMessage(
    conversationId: string,
    messageId: string,
    expectedRevision: number,
    text: string,
    clientOperationId: string,
    mentions: readonly V2MessageMention[] = [],
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    if (!this.requestedCapabilities.includes(ClientCapability.MESSAGE_EDITS)) {
      throw new Error("message edits were not enabled for this client");
    }
    requireUuid("conversationId", conversationId);
    requireUuid("messageId", messageId);
    requireIdentifier("clientOperationId", clientOperationId);
    if (!Number.isInteger(expectedRevision) || expectedRevision < 0
        || expectedRevision > MAX_CONTENT_REVISIONS) {
      throw new Error("expectedRevision must be an integer in 0..100");
    }
    const content = encoder.encode(text);
    if (content.byteLength < 1 || content.byteLength > MAX_TEXT_BYTES) {
      throw new Error("text must contain 1..65536 UTF-8 bytes");
    }
    const validMentions = this.requireOutboundMentions(content, mentions);
    const payload = toBinary(EditMessageSchema, create(EditMessageSchema, {
      conversationId, messageId, expectedRevision,
      contentType: MessageContentType.TEXT_UTF8, content, clientOperationId,
      mentions: validMentions,
    }));
    return correlated(this.command(
      MessageType.EDIT_MESSAGE, payload, new Set([MessageType.MESSAGE_EDIT_APPLIED])));
  }

  private requireOutboundMentions(
    content: Uint8Array,
    mentions: readonly V2MessageMention[],
  ): MessageMention[] {
    if (mentions.length > 0
        && !this.requestedCapabilities.includes(ClientCapability.MESSAGE_MENTIONS)) {
      throw new Error("message mentions were not enabled for this client");
    }
    const values = mentions.map(mention => create(MessageMentionSchema, {
      targetAccountId: mention.targetAccountId,
      startUtf8Byte: mention.startUtf8Byte,
      lengthUtf8Bytes: mention.lengthUtf8Bytes,
    }));
    validateMentions(content, values);
    return values;
  }

  registerAttachment(
    conversationId: string,
    clientAttachmentId: string,
    fileName: string,
    mediaType: string,
    byteSize: bigint,
    contentSha256: Uint8Array,
  ): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("conversationId", conversationId);
    requireIdentifier("clientAttachmentId", clientAttachmentId);
    requireUtf8("fileName", fileName, 1, 255);
    if (fileName === "." || fileName === ".." || fileName.includes("/") || fileName.includes("\\")) {
      throw new Error("fileName must be a basename");
    }
    requireUtf8("mediaType", mediaType, 1, 127);
    if (!/^[a-z0-9][a-z0-9!#$&^_.+-]*\/[a-z0-9][a-z0-9!#$&^_.+-]*$/.test(mediaType)) {
      throw new Error("mediaType must be canonical");
    }
    if (byteSize < 1n || byteSize > 10n * 1024n * 1024n * 1024n) {
      throw new Error("byteSize is outside the V2 attachment bound");
    }
    if (contentSha256.byteLength !== 32) throw new Error("contentSha256 must contain 32 bytes");
    const payload = toBinary(RegisterAttachmentSchema, create(RegisterAttachmentSchema, {
      conversationId,
      clientAttachmentId,
      fileName,
      mediaType,
      byteSize,
      contentSha256: contentSha256.slice(),
    }));
    return correlated(this.command(
      MessageType.REGISTER_ATTACHMENT,
      payload,
      new Set([MessageType.ATTACHMENT_REGISTERED]),
      clientAttachmentId,
    ));
  }

  authorizeAttachmentUpload(attachmentId: string): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("attachmentId", attachmentId);
    return correlated(this.command(
      MessageType.AUTHORIZE_ATTACHMENT_UPLOAD,
      toBinary(AuthorizeAttachmentUploadSchema, create(AuthorizeAttachmentUploadSchema, { attachmentId })),
      new Set([MessageType.ATTACHMENT_UPLOAD_AUTHORIZED]),
    ));
  }

  completeAttachmentUpload(attachmentId: string): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("attachmentId", attachmentId);
    return correlated(this.command(
      MessageType.COMPLETE_ATTACHMENT_UPLOAD,
      toBinary(CompleteAttachmentUploadSchema, create(CompleteAttachmentUploadSchema, { attachmentId })),
      new Set([MessageType.ATTACHMENT_READY]),
    ));
  }

  listDevices(): V2CorrelatedCommand {
    this.requireState("authenticated");
    return correlated(this.command(
      MessageType.LIST_DEVICES,
      toBinary(ListDevicesSchema, create(ListDevicesSchema, {})),
      new Set([MessageType.DEVICE_DIRECTORY]),
    ));
  }

  revokeDevice(targetDeviceId: string): V2CorrelatedCommand {
    this.requireState("authenticated");
    requireUuid("targetDeviceId", targetDeviceId);
    if (targetDeviceId === this.currentSession?.deviceId) {
      throw new Error("the current device cannot be revoked");
    }
    return correlated(this.command(
      MessageType.REVOKE_DEVICE,
      toBinary(RevokeDeviceSchema, create(RevokeDeviceSchema, { targetDeviceId })),
      new Set([MessageType.DEVICE_REVOKED]),
    ));
  }

  cancelPendingRequest(requestId: string): void {
    const pending = this.pending.get(requestId);
    if (!pending) return;
    pending.cancelled = true;
    const cancelled = [...this.pending.entries()].filter(([, value]) => value.cancelled);
    if (cancelled.length > MAX_CANCELLED_REQUESTS) this.pending.delete(cancelled[0]![0]);
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
    const serverEvent = envelope.kind === MessageKind.EVENT;
    const pending = envelope.requestId ? this.pending.get(envelope.requestId) : undefined;
    if (serverEvent) {
      if (envelope.requestId || envelope.clientMessageId) {
        throw new Error("server event must not carry request correlation");
      }
    } else if (envelope.messageType !== MessageType.PROTOCOL_ERROR) {
      if (!pending) throw new Error("response does not match a pending request");
      if (!pending.expected.has(envelope.messageType)) throw new Error("response message type does not match the request");
    } else if (envelope.requestId && !pending) {
      throw new Error("protocol error does not match a pending request");
    }
    if (pending && envelope.clientMessageId !== pending.clientMessageId) {
      throw new Error("response clientMessageId does not match the request");
    }
    if (pending?.cancelled) {
      this.pending.delete(envelope.requestId);
      return {
        requestId: envelope.requestId,
        clientMessageId: envelope.clientMessageId,
        type: "cancelled-response",
        value: undefined,
      };
    }

    const event = this.decodeEvent(envelope);
    if (event.type === "account-block-applied") {
      const expected = pending?.accountBlock;
      if (!expected || event.value.actorAccountId !== this.currentSession?.accountId
          || event.value.targetAccountId !== expected.targetAccountId
          || event.value.blocked !== expected.blocked
          || event.value.clientOperationId !== expected.clientOperationId) {
        throw new Error("account block result does not match the authenticated request");
      }
    }
    if (event.type === "account-block-directory-page") {
      const expected = pending?.accountBlockDirectory;
      if (!expected) throw new Error("account block directory does not match a pending request");
      validateAccountBlockDirectoryPage(
        event.value, expected.afterTargetAccountId, expected.limit);
    }
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
    accountBlock?: PendingRequest["accountBlock"],
    accountBlockDirectory?: PendingRequest["accountBlockDirectory"],
  ): Uint8Array {
    if (payload.byteLength > MAX_PAYLOAD_BYTES) throw new Error("V2 payload exceeds the limit");
    const requestId = this.createRequestId();
    requireUuid("requestId", requestId);
    if (this.pending.has(requestId)) throw new Error("requestId is already pending");
    const activePending = [...this.pending.values()].filter((value) => !value.cancelled).length;
    if (activePending >= MAX_PENDING_REQUESTS) throw new Error("too many pending V2 requests");
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
    this.pending.set(requestId, {
      expected, clientMessageId, cancelled: false, accountBlock, accountBlockDirectory,
    });
    return bytes;
  }

  private validateInboundEnvelope(envelope: Envelope): void {
    if (envelope.protocolVersion !== PROTOCOL_VERSION) throw new Error("unsupported protocol version");
    const publishedEvent = envelope.kind === MessageKind.EVENT
      && (envelope.messageType === MessageType.MESSAGE_PUBLISHED
        || envelope.messageType === MessageType.MESSAGE_REACTION_CHANGED
        || envelope.messageType === MessageType.MESSAGE_PIN_CHANGED
        || envelope.messageType === MessageType.MESSAGE_EDITED);
    if (!publishedEvent && envelope.kind !== MessageKind.RESPONSE && envelope.kind !== MessageKind.ERROR) {
      throw new Error("unexpected inbound message kind");
    }
    if (publishedEvent) {
      if (this.currentState !== "authenticated") throw new Error("server event requires an authenticated session");
    } else if (envelope.messageType === MessageType.PROTOCOL_ERROR
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
    const correlation = {
      requestId: envelope.requestId,
      clientMessageId: envelope.clientMessageId,
    };
    try {
      switch (envelope.messageType) {
        case MessageType.SERVER_HELLO:
          return { ...correlation, type: "server-hello", value: fromBinary(ServerHelloSchema, envelope.payload) };
        case MessageType.SESSION_ESTABLISHED:
          return { ...correlation, type: "session-established", value: fromBinary(SessionEstablishedSchema, envelope.payload) };
        case MessageType.AUTHENTICATION_REJECTED:
          return { ...correlation, type: "authentication-rejected", value: fromBinary(AuthenticationRejectedSchema, envelope.payload) };
        case MessageType.PROTOCOL_ERROR:
          return { ...correlation, type: "protocol-error", value: fromBinary(ProtocolErrorSchema, envelope.payload) };
        case MessageType.MESSAGE_ACCEPTED:
          return { ...correlation, type: "message-accepted", value: fromBinary(MessageAcceptedSchema, envelope.payload) };
        case MessageType.MESSAGE_HISTORY_PAGE:
          return { ...correlation, type: "message-history-page", value: fromBinary(MessageHistoryPageSchema, envelope.payload) };
        case MessageType.MESSAGE_PUBLISHED:
          return { ...correlation, type: "message-published", value: fromBinary(MessageRecordSchema, envelope.payload) };
        case MessageType.MESSAGE_REACTION_APPLIED:
          return { ...correlation, type: "message-reaction-applied", value: fromBinary(MessageReactionAppliedSchema, envelope.payload) };
        case MessageType.MESSAGE_REACTION_CHANGED:
          return { ...correlation, type: "message-reaction-changed", value: fromBinary(MessageReactionChangedRecordSchema, envelope.payload) };
        case MessageType.MESSAGE_PIN_APPLIED:
          return { ...correlation, type: "message-pin-applied", value: fromBinary(MessagePinAppliedSchema, envelope.payload) };
        case MessageType.MESSAGE_PIN_CHANGED:
          return { ...correlation, type: "message-pin-changed", value: fromBinary(MessagePinChangedRecordSchema, envelope.payload) };
        case MessageType.MESSAGE_EDIT_APPLIED:
          return { ...correlation, type: "message-edit-applied", value: fromBinary(MessageEditAppliedSchema, envelope.payload) };
        case MessageType.MESSAGE_EDITED:
          return { ...correlation, type: "message-edited", value: fromBinary(MessageEditedRecordSchema, envelope.payload) };
        case MessageType.CONVERSATION_DIRECTORY_PAGE:
          return { ...correlation, type: "conversation-directory-page", value: fromBinary(ConversationDirectoryPageSchema, envelope.payload) };
        case MessageType.CONVERSATION_PARTICIPANT_PAGE:
          return { ...correlation, type: "conversation-participant-page", value: fromBinary(ConversationParticipantPageSchema, envelope.payload) };
        case MessageType.CONVERSATION_MESSAGE_SEARCH_PAGE:
          return { ...correlation, type: "conversation-message-search-page", value: fromBinary(ConversationMessageSearchPageSchema, envelope.payload) };
        case MessageType.ATTACHMENT_REGISTERED:
          return { ...correlation, type: "attachment-registered", value: fromBinary(AttachmentRegisteredSchema, envelope.payload) };
        case MessageType.ATTACHMENT_UPLOAD_AUTHORIZED:
          return { ...correlation, type: "attachment-upload-authorized", value: fromBinary(AttachmentUploadAuthorizedSchema, envelope.payload) };
        case MessageType.ATTACHMENT_READY:
          return { ...correlation, type: "attachment-ready", value: fromBinary(AttachmentReadySchema, envelope.payload) };
        case MessageType.DEVICE_DIRECTORY:
          return { ...correlation, type: "device-directory", value: fromBinary(DeviceDirectorySchema, envelope.payload) };
        case MessageType.DEVICE_REVOKED:
          return { ...correlation, type: "device-revoked", value: fromBinary(DeviceRevokedSchema, envelope.payload) };
        case MessageType.ACCOUNT_BLOCK_APPLIED:
          return { ...correlation, type: "account-block-applied", value: fromBinary(AccountBlockAppliedSchema, envelope.payload) };
        case MessageType.ACCOUNT_BLOCK_DIRECTORY_PAGE:
          return { ...correlation, type: "account-block-directory-page", value: fromBinary(AccountBlockDirectoryPageSchema, envelope.payload) };
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
        if (event.value.enabledCapabilities.length !== this.requestedCapabilities.length
            || event.value.enabledCapabilities.some((capability, index) =>
              capability !== this.requestedCapabilities[index])) {
          throw new Error("required V2 capability was not enabled");
        }
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
        validateHistoryPage(event.value, this.mentionsEnabled(), this.forwardingEnabled());
        break;
      case "message-published":
        validateMessageRecord(event.value, this.mentionsEnabled(), this.forwardingEnabled());
        break;
      case "message-reaction-applied":
        validateReactionApplied(event.value);
        break;
      case "message-reaction-changed":
        validateReactionChanged(event.value);
        break;
      case "message-pin-applied":
        validatePinApplied(event.value);
        break;
      case "message-pin-changed":
        validatePinChanged(event.value);
        break;
      case "message-edit-applied":
        validateEditApplied(event.value, this.mentionsEnabled());
        break;
      case "message-edited":
        validateEditedRecord(event.value, this.mentionsEnabled());
        break;
      case "conversation-directory-page":
        validateDirectoryPage(event.value);
        break;
      case "conversation-participant-page":
        if (!this.mentionsEnabled()) {
          throw new Error("conversation participants require negotiated message mentions");
        }
        validateParticipantPage(event.value);
        break;
      case "conversation-message-search-page":
        if (!this.searchEnabled()) {
          throw new Error("conversation search requires negotiated message search");
        }
        validateMessageSearchPage(
          event.value, this.mentionsEnabled(), this.forwardingEnabled());
        break;
      case "attachment-registered":
        requireUuid("attachmentId", event.value.attachmentId);
        requireUuid("conversationId", event.value.conversationId);
        requireIdentifier("clientAttachmentId", event.value.clientAttachmentId);
        break;
      case "attachment-upload-authorized":
        validateUploadAuthorization(event.value);
        break;
      case "attachment-ready":
        requireUuid("attachmentId", event.value.attachmentId);
        requireUuid("conversationId", event.value.conversationId);
        if (event.value.readyAtEpochMs <= 0n) throw new Error("invalid attachment ready response");
        break;
      case "device-directory": {
        if (event.value.devices.length < 1 || event.value.devices.length > 100
            || event.value.devices.filter((device) => device.current).length !== 1) {
          throw new Error("invalid device directory");
        }
        const ids = new Set<string>();
        for (const device of event.value.devices) {
          requireUuid("deviceId", device.deviceId);
          if (ids.has(device.deviceId) || (device.platform !== ClientPlatform.WEB
              && device.platform !== ClientPlatform.WINDOWS)
              || device.createdAtEpochMs <= 0n
              || device.lastSeenAtEpochMs < device.createdAtEpochMs) {
            throw new Error("invalid device directory");
          }
          ids.add(device.deviceId);
        }
        break;
      }
      case "device-revoked":
        requireUuid("targetDeviceId", event.value.targetDeviceId);
        if (event.value.targetDeviceId === this.currentSession?.deviceId
            || event.value.revokedAtEpochMs <= 0n) {
          throw new Error("invalid device revocation");
        }
        break;
      case "account-block-applied":
        if (!this.accountBlockingEnabled()) {
          throw new Error("account block result requires negotiated account blocking");
        }
        requireUuid("actorAccountId", event.value.actorAccountId);
        requireUuid("targetAccountId", event.value.targetAccountId);
        requireUuid("clientOperationId", event.value.clientOperationId);
        if (event.value.actorAccountId === event.value.targetAccountId) {
          throw new Error("invalid account block result");
        }
        break;
      case "account-block-directory-page":
        if (!this.accountBlockingEnabled()) {
          throw new Error("account block directory requires negotiated account blocking");
        }
        break;
      case "cancelled-response":
        break;
    }
  }

  private requireState(expected: V2WebProtocolState): void {
    if (this.currentState !== expected) throw new Error(`expected ${expected} state, found ${this.currentState}`);
  }

  private mentionsEnabled(): boolean {
    return this.requestedCapabilities.includes(ClientCapability.MESSAGE_MENTIONS);
  }

  private forwardingEnabled(): boolean {
    return this.requestedCapabilities.includes(ClientCapability.MESSAGE_FORWARDING);
  }

  private searchEnabled(): boolean {
    return this.requestedCapabilities.includes(ClientCapability.MESSAGE_SEARCH);
  }

  private accountBlockingEnabled(): boolean {
    return this.requestedCapabilities.includes(ClientCapability.ACCOUNT_BLOCKING);
  }
}

function validateUploadAuthorization(value: AttachmentUploadAuthorized): void {
  requireUuid("attachmentId", value.attachmentId);
  let uri: URL;
  try { uri = new URL(value.uploadUri); } catch { throw new Error("invalid attachment upload authorization"); }
  if (uri.protocol !== "https:" || uri.username || uri.password || uri.hash
      || value.expiresAtEpochMs <= 0n || value.requiredHeaders.length < 1
      || value.requiredHeaders.length > 32) {
    throw new Error("invalid attachment upload authorization");
  }
  const names = new Set<string>();
  for (const header of value.requiredHeaders) {
    requireUtf8("upload header name", header.name, 1, 128);
    requireUtf8("upload header value", header.value, 1, 4096);
    if (header.name !== header.name.toLowerCase() || names.has(header.name)
        || header.name === "host" || header.name === "content-length") {
      throw new Error("invalid attachment upload authorization");
    }
    names.add(header.name);
  }
}

function correlated(bytes: Uint8Array): V2CorrelatedCommand {
  const requestId = fromBinary(EnvelopeSchema, bytes).requestId;
  return { requestId, bytes };
}

function validateHistoryPage(
  page: MessageHistoryPage,
  allowMentions: boolean,
  allowForwarding: boolean,
): void {
  requireUuid("conversationId", page.conversationId);
  if (page.messages.length > MAX_PAGE_SIZE
      || page.entries.length > MAX_PAGE_SIZE
      || page.nextSequence > MAX_SIGNED_SEQUENCE
      || page.latestSequence > MAX_SIGNED_SEQUENCE) {
    throw new Error("history page exceeds the limit");
  }
  let previous = 0n;
  for (const message of page.messages) {
    validateMessageRecord(message, allowMentions, allowForwarding);
    if (message.conversationId !== page.conversationId
        || message.conversationSequence <= previous) {
      throw new Error("invalid history message");
    }
    previous = message.conversationSequence;
  }
  let previousEntry = 0n;
  for (const entry of page.entries) {
    requireUuid("entry.conversationId", entry.conversationId);
    if (entry.conversationId !== page.conversationId
        || entry.conversationSequence <= previousEntry
        || entry.conversationSequence > MAX_SIGNED_SEQUENCE) {
      throw new Error("invalid history entry identity");
    }
    if (entry.detail.case === "message") {
      validateMessageRecord(entry.detail.value, allowMentions, allowForwarding);
      if (entry.detail.value.conversationId !== entry.conversationId
          || entry.detail.value.conversationSequence !== entry.conversationSequence) {
        throw new Error("invalid message entry detail");
      }
    } else if (entry.detail.case === "recall") {
      const recall = entry.detail.value;
      requireUuid("recall.conversationId", recall.conversationId);
      requireUuid("recall.messageId", recall.messageId);
      requireUuid("recall.actorAccountId", recall.actorAccountId);
      requireIdentifier("recall.source", recall.source);
      if (recall.conversationId !== entry.conversationId
          || recall.conversationSequence !== entry.conversationSequence
          || (recall.source !== "V2" && recall.source !== "V1_IMPORT")
          || recall.occurredAtEpochMs < 0n) throw new Error("invalid recall entry detail");
    } else if (entry.detail.case === "deletion") {
      const deletion = entry.detail.value;
      requireUuid("deletion.conversationId", deletion.conversationId);
      requireUuid("deletion.actorAccountId", deletion.actorAccountId);
      requireIdentifier("deletion.source", deletion.source);
      requireIdentifier("deletion.mode", deletion.mode);
      requireIdentifier("deletion.clientOperationId", deletion.clientOperationId);
      deletion.messageIds.forEach((id) => requireUuid("deletion.messageId", id));
      if (deletion.conversationId !== entry.conversationId
          || deletion.conversationSequence !== entry.conversationSequence
          || deletion.cutoffEpochMs < 0n
          || deletion.occurredAtEpochMs <= 0n
          || (deletion.source !== "V2" && deletion.source !== "V1_IMPORT")
          || !["selected", "all", "before", "after"].includes(deletion.mode)
          || deletion.messageIds.length > MAX_DELETION_TARGETS
          || [...deletion.operatorNameSnapshot].length > 100) {
        throw new Error("invalid deletion entry detail");
      }
    } else if (entry.detail.case === "reaction") {
      validateReactionChanged(entry.detail.value);
      if (entry.detail.value.conversationId !== entry.conversationId
          || entry.detail.value.conversationSequence !== entry.conversationSequence) {
        throw new Error("invalid reaction entry detail");
      }
    } else if (entry.detail.case === "pin") {
      validatePinChanged(entry.detail.value);
      if (entry.detail.value.conversationId !== entry.conversationId
          || entry.detail.value.conversationSequence !== entry.conversationSequence) {
        throw new Error("invalid pin entry detail");
      }
    } else if (entry.detail.case === "edit") {
      validateEditedRecord(entry.detail.value, allowMentions);
      if (entry.detail.value.conversationId !== entry.conversationId
          || entry.detail.value.conversationSequence !== entry.conversationSequence) {
        throw new Error("invalid edit entry detail");
      }
    } else {
      throw new Error("history entry detail is required");
    }
    previousEntry = entry.conversationSequence;
  }
  const lastSequence = page.entries.length > 0 ? previousEntry : previous;
  if (page.nextSequence < lastSequence || page.nextSequence > page.latestSequence) {
    throw new Error("history cursor is outside the visible and latest sequence bounds");
  }
}

function validateMessageSearchPage(
  page: ConversationMessageSearchPage,
  allowMentions: boolean,
  allowForwarding: boolean,
): void {
  requireUuid("conversationId", page.conversationId);
  if (page.hits.length > 50 || (page.hasMore && page.hits.length === 0)
      || page.nextBeforeSequence > MAX_SIGNED_SEQUENCE) {
    throw new Error("invalid message search page bounds");
  }
  let previous = 0n;
  for (const hit of page.hits) {
    validateMessageRecord(hit, allowMentions, allowForwarding);
    if (hit.conversationId !== page.conversationId
        || (previous !== 0n && hit.conversationSequence >= previous)) {
      throw new Error("message search hits must descend within one conversation");
    }
    previous = hit.conversationSequence;
  }
  if (page.nextBeforeSequence !== previous) {
    throw new Error("message search cursor must identify the last hit");
  }
}

function validateAccountBlockDirectoryPage(
  page: AccountBlockDirectoryPage,
  requestedAfterTargetAccountId: string,
  requestedLimit: number,
): void {
  if (page.blocks.length > requestedLimit
      || (page.hasMore && page.blocks.length === 0)) {
    throw new Error("invalid account block directory bounds");
  }
  let previous = "";
  const targets = new Set<string>();
  for (const block of page.blocks) {
    requireUuid("accountBlock.targetAccountId", block.targetAccountId);
    requireUtf8("accountBlock.targetDisplayName", block.targetDisplayName, 1, 400);
    if (strictDecoder.decode(encoder.encode(block.targetDisplayName))
          !== block.targetDisplayName
        || block.targetDisplayName.trim().length === 0
        || [...block.targetDisplayName].length > 100
        || block.blockedAtEpochMs <= 0n
        || targets.has(block.targetAccountId)
        || (requestedAfterTargetAccountId
          && block.targetAccountId <= requestedAfterTargetAccountId)
        || (previous && block.targetAccountId <= previous)) {
      throw new Error("invalid account block directory row");
    }
    targets.add(block.targetAccountId);
    previous = block.targetAccountId;
  }
  if (page.hasMore) {
    requireUuid("nextAfterTargetAccountId", page.nextAfterTargetAccountId);
    if (page.nextAfterTargetAccountId !== previous) {
      throw new Error("account block directory cursor must identify the last row");
    }
  } else if (page.nextAfterTargetAccountId !== "") {
    throw new Error("terminal account block directory must not carry a cursor");
  }
}

function validateEditApplied(value: MessageEditApplied, allowMentions: boolean): void {
  validateEditIdentity(value.conversationId, value.messageId, value.actorAccountId,
    value.clientOperationId);
  validateEditContent(value.contentType, value.content);
  requireInboundMentions(value.content, value.mentions, allowMentions);
  if (value.contentRevision > MAX_CONTENT_REVISIONS
      || (value.changed && value.contentRevision === 0)
      || value.occurredAtEpochMs <= 0n
      || value.conversationSequence > MAX_SIGNED_SEQUENCE
      || value.changed !== (value.conversationSequence > 0n)) {
    throw new Error("invalid edit application");
  }
}

function validateEditedRecord(value: MessageEditedRecord, allowMentions: boolean): void {
  validateEditIdentity(value.conversationId, value.messageId, value.actorAccountId,
    value.clientOperationId);
  validateEditContent(value.contentType, value.content);
  requireInboundMentions(value.content, value.mentions, allowMentions);
  if (value.contentRevision < 1 || value.contentRevision > MAX_CONTENT_REVISIONS
      || value.conversationSequence <= 0n
      || value.conversationSequence > MAX_SIGNED_SEQUENCE
      || value.occurredAtEpochMs <= 0n) {
    throw new Error("invalid message edit");
  }
}

function validateEditIdentity(
  conversationId: string,
  messageId: string,
  actorAccountId: string,
  clientOperationId: string,
): void {
  requireUuid("edit.conversationId", conversationId);
  requireUuid("edit.messageId", messageId);
  requireUuid("edit.actorAccountId", actorAccountId);
  requireIdentifier("edit.clientOperationId", clientOperationId);
}

function validateEditContent(contentType: number, content: Uint8Array): void {
  if (contentType !== MessageContentType.TEXT_UTF8
      || content.byteLength < 1 || content.byteLength > MAX_TEXT_BYTES) {
    throw new Error("invalid edited content");
  }
  try { strictDecoder.decode(content); } catch { throw new Error("invalid edited content"); }
}

function validatePinApplied(value: MessagePinApplied): void {
  requireUuid("pin.conversationId", value.conversationId);
  requireUuid("pin.messageId", value.messageId);
  requireUuid("pin.actorAccountId", value.actorAccountId);
  requireIdentifier("pin.clientOperationId", value.clientOperationId);
  if (value.occurredAtEpochMs <= 0n || value.conversationSequence > MAX_SIGNED_SEQUENCE
      || value.changed !== (value.conversationSequence > 0n)) {
    throw new Error("invalid pin application");
  }
}

function validatePinChanged(value: MessagePinChangedRecord): void {
  requireUuid("pin.conversationId", value.conversationId);
  requireUuid("pin.messageId", value.messageId);
  requireUuid("pin.actorAccountId", value.actorAccountId);
  requireIdentifier("pin.clientOperationId", value.clientOperationId);
  if (value.conversationSequence <= 0n
      || value.conversationSequence > MAX_SIGNED_SEQUENCE
      || value.occurredAtEpochMs <= 0n) throw new Error("invalid pin change");
}

function validateReactionApplied(value: MessageReactionApplied): void {
  requireUuid("reaction.conversationId", value.conversationId);
  requireUuid("reaction.messageId", value.messageId);
  requireUuid("reaction.actorAccountId", value.actorAccountId);
  requireIdentifier("reaction.clientOperationId", value.clientOperationId);
  requireReaction(value.reaction);
  if (value.occurredAtEpochMs <= 0n
      || value.conversationSequence > MAX_SIGNED_SEQUENCE
      || value.changed !== (value.conversationSequence > 0n)) {
    throw new Error("invalid reaction application");
  }
}

function validateReactionChanged(value: MessageReactionChangedRecord): void {
  requireUuid("reaction.conversationId", value.conversationId);
  requireUuid("reaction.messageId", value.messageId);
  requireUuid("reaction.actorAccountId", value.actorAccountId);
  requireIdentifier("reaction.clientOperationId", value.clientOperationId);
  requireReaction(value.reaction);
  if (value.conversationSequence <= 0n
      || value.conversationSequence > MAX_SIGNED_SEQUENCE
      || value.occurredAtEpochMs <= 0n) {
    throw new Error("invalid reaction change");
  }
}

function requireReaction(value: MessageReactionKind): void {
  if (![MessageReactionKind.LIKE, MessageReactionKind.LOVE,
    MessageReactionKind.LAUGH, MessageReactionKind.SURPRISED,
    MessageReactionKind.SAD, MessageReactionKind.ANGRY].includes(value)) {
    throw new Error("unsupported message reaction");
  }
}

function validateMessageRecord(
  message: MessageRecord,
  allowMentions: boolean,
  allowForwarding: boolean,
): void {
  requireUuid("conversationId", message.conversationId);
  requireUuid("messageId", message.messageId);
  requireUuid("senderAccountId", message.senderAccountId);
  requireUuid("senderDeviceId", message.senderDeviceId);
  requireIdentifier("clientMessageId", message.clientMessageId);
  if (message.conversationSequence <= 0n
      || message.conversationSequence > MAX_SIGNED_SEQUENCE
      || message.acceptedAtEpochMs <= 0n
      || message.contentType !== MessageContentType.TEXT_UTF8
      || message.content.byteLength < 1
      || message.content.byteLength > MAX_TEXT_BYTES
      || message.contentRevision > MAX_CONTENT_REVISIONS
      || (message.contentRevision === 0) !== (message.editedAtEpochMs === 0n)) {
    throw new Error("invalid message record");
  }
  try { strictDecoder.decode(message.content); }
  catch { throw new Error("message text is not valid UTF-8"); }
  requireInboundMentions(message.content, message.mentions, allowMentions);
  if (message.forwarded && !allowForwarding) {
    throw new Error("received forwarded message without negotiated capability");
  }
  if (message.reply) {
    requireUuid("reply.targetMessageId", message.reply.targetMessageId);
    requireUuid("reply.targetSenderAccountId", message.reply.targetSenderAccountId);
    if (message.reply.targetConversationSequence <= 0n
        || message.reply.targetConversationSequence >= message.conversationSequence) {
      throw new Error("reply target sequence must precede the reply message");
    }
  }
}

function requireInboundMentions(
  content: Uint8Array,
  mentions: readonly MessageMention[],
  allowMentions: boolean,
): void {
  if (mentions.length > 0 && !allowMentions) {
    throw new Error("received message mentions without negotiated capability");
  }
  validateMentions(content, mentions);
}

function validateMentions(content: Uint8Array, mentions: readonly MessageMention[]): void {
  if (mentions.length > MAX_MENTION_SPANS) throw new Error("message has too many mention spans");
  const targets = new Set<string>();
  let previousEnd = 0;
  for (const mention of mentions) {
    requireUuid("mention.targetAccountId", mention.targetAccountId);
    if (!Number.isInteger(mention.startUtf8Byte)
        || !Number.isInteger(mention.lengthUtf8Bytes)
        || mention.startUtf8Byte < previousEnd
        || mention.lengthUtf8Bytes < 1) {
      throw new Error("invalid message mention span");
    }
    const end = mention.startUtf8Byte + mention.lengthUtf8Bytes;
    if (!Number.isSafeInteger(end)
        || end > content.byteLength
        || !isUtf8Boundary(content, mention.startUtf8Byte)
        || !isUtf8Boundary(content, end)
        || content[mention.startUtf8Byte] !== 0x40) {
      throw new Error("invalid message mention span");
    }
    targets.add(mention.targetAccountId);
    if (targets.size > MAX_DISTINCT_MENTION_TARGETS) {
      throw new Error("message has too many distinct mention targets");
    }
    previousEnd = end;
  }
}

function isUtf8Boundary(content: Uint8Array, index: number): boolean {
  return index === 0 || index === content.byteLength || (content[index]! & 0xc0) !== 0x80;
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

function validateParticipantPage(page: ConversationParticipantPage): void {
  requireUuid("conversationId", page.conversationId);
  if (page.participants.length > MAX_PAGE_SIZE
      || (page.hasMore && page.participants.length === 0)) {
    throw new Error("invalid participant page bounds");
  }
  let previousAccountId = "";
  for (const participant of page.participants) {
    requireUuid("accountId", participant.accountId);
    requireUtf8("displayName", participant.displayName, 1, 400);
    if ([...participant.displayName].length > 100
        || (participant.role !== ConversationRole.OWNER
          && participant.role !== ConversationRole.ADMIN
          && participant.role !== ConversationRole.MEMBER)
        || (previousAccountId && previousAccountId >= participant.accountId)) {
      throw new Error("invalid participant record");
    }
    previousAccountId = participant.accountId;
  }
  if (!previousAccountId) {
    if (page.nextAccountId) throw new Error("empty participant page has a cursor");
  } else if (page.nextAccountId !== previousAccountId) {
    throw new Error("participant cursor does not identify the last record");
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
