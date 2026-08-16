import {
  create,
  fromBinary,
  toBinary,
  type DescMessage,
  type MessageInitShape,
} from "@bufbuild/protobuf";

import {
  AuthenticateSchema,
  AuthenticationRejectedSchema,
  AuthenticationRejectionReason,
  ResumeSessionSchema,
  SessionEstablishedSchema,
} from "../../src/protocol/v2/generated/authentication_pb";
import {
  ClientHelloSchema,
  ClientPlatform,
  MessageType,
  ServerHelloSchema,
} from "../../src/protocol/v2/generated/control_pb";
import {
  ConversationDirectoryPageSchema,
  ConversationKind,
  ConversationParticipantPageSchema,
  ConversationRole,
  ListConversationParticipantsSchema,
  ListConversationsSchema,
} from "../../src/protocol/v2/generated/conversation_pb";
import {
  DeviceDirectorySchema,
  ListDevicesSchema,
} from "../../src/protocol/v2/generated/device_management_pb";
import {
  EnvelopeSchema,
  MessageKind,
  type Envelope,
} from "../../src/protocol/v2/generated/envelope_pb";
import {
  ConversationMessageSearchPageSchema,
  ForwardMessageSchema,
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
  MessageRecordSchema,
  ReadMessageHistorySchema,
  SearchConversationMessagesSchema,
  SubmitMessageSchema,
} from "../../src/protocol/v2/generated/messaging_pb";

const NOW = 1_800_000_000_000n;
const ACCOUNT_ID = "20000000-0000-4000-8000-000000000001";
export const PEER_ACCOUNT_ID = "20000000-0000-4000-8000-000000000002";
const PEER_DEVICE_ID = "30000000-0000-4000-8000-000000000002";
const SESSION_ID = "40000000-0000-4000-8000-000000000001";
export const FIXTURE_CONVERSATION_ID = "50000000-0000-4000-8000-000000000001";
export const KEYBOARD_CONVERSATION_ID = "50000000-0000-4000-8000-000000000002";
const INCOMING_MESSAGE_ID = "60000000-0000-4000-8000-000000000001";
const OUTGOING_MESSAGE_ID = "60000000-0000-4000-8000-000000000002";
const FORWARDED_MESSAGE_ID = "60000000-0000-4000-8000-000000000003";
const EXPECTED_USERNAME = "browser_v2_user";
const EXPECTED_PASSWORD = "non-secret-test-value";
const RESUME_TOKEN = Uint8Array.from({ length: 32 }, (_, index) => index + 1);

export type V2ProtocolFixtureMode = "accept" | "reject";

export interface V2ProtocolFixtureOptions {
  dropFirstForwardAcceptance?: boolean;
}

export interface V2ProtocolFixture {
  readonly receivedTypes: MessageType[];
  readonly clientHelloAppVersions: string[];
  readonly searchQueries: Array<{
    conversationId: string;
    literalQuery: string;
    beforeSequence: bigint;
    limit: number;
  }>;
  readonly participantRequests: Array<{
    conversationId: string;
    afterAccountId: string;
    limit: number;
  }>;
  readonly mentionSubmissions: Array<{
    text: string;
    targetAccountId: string;
    startUtf8Byte: number;
    lengthUtf8Bytes: number;
  }>;
  readonly forwardRequests: Array<{
    sourceConversationId: string;
    sourceMessageId: string;
    expectedSourceContentRevision: number;
    targetConversationId: string;
    clientMessageId: string;
  }>;
  respond(bytes: number[]): number[] | null;
}

export function createV2ProtocolFixture(
  mode: V2ProtocolFixtureMode,
  options: V2ProtocolFixtureOptions = {},
): V2ProtocolFixture {
  const receivedTypes: MessageType[] = [];
  const clientHelloAppVersions: string[] = [];
  const searchQueries: V2ProtocolFixture["searchQueries"] = [];
  const participantRequests: V2ProtocolFixture["participantRequests"] = [];
  const mentionSubmissions: V2ProtocolFixture["mentionSubmissions"] = [];
  const forwardRequests: V2ProtocolFixture["forwardRequests"] = [];
  let clientDeviceId = "";
  let resumed = false;
  let forwardedClientMessageId = "";
  let dropForwardAcceptance = options.dropFirstForwardAcceptance === true;

  return {
    receivedTypes,
    clientHelloAppVersions,
    searchQueries,
    participantRequests,
    mentionSubmissions,
    forwardRequests,
    respond(raw) {
      const request = fromBinary(EnvelopeSchema, Uint8Array.from(raw));
      if (request.protocolVersion !== 2 || request.kind !== MessageKind.COMMAND
          || !request.requestId || request.sentAtEpochMs <= 0n) {
        throw new Error("invalid V2 fixture command envelope");
      }
      receivedTypes.push(request.messageType);

      switch (request.messageType) {
        case MessageType.CLIENT_HELLO: {
          const hello = fromBinary(ClientHelloSchema, request.payload);
          if (hello.minimumProtocolVersion !== 2 || hello.maximumProtocolVersion !== 2
              || hello.platform !== ClientPlatform.WEB || !hello.clientDeviceId
              || !hello.appVersion || hello.capabilities.length < 2) {
            throw new Error("invalid V2 fixture client hello");
          }
          clientDeviceId = hello.clientDeviceId;
          clientHelloAppVersions.push(hello.appVersion);
          return response(request, MessageType.SERVER_HELLO, ServerHelloSchema, {
            selectedProtocolVersion: 2,
            connectionId: "browser-fixture-connection",
            serverTimeEpochMs: NOW,
            maximumFrameBytes: 1024 * 1024 + 1024,
            enabledCapabilities: hello.capabilities,
          });
        }
        case MessageType.AUTHENTICATE: {
          const auth = fromBinary(AuthenticateSchema, request.payload);
          const password = new TextDecoder("utf-8", { fatal: true }).decode(auth.passwordUtf8);
          if (mode === "reject") {
            return response(request, MessageType.AUTHENTICATION_REJECTED,
              AuthenticationRejectedSchema, {
                reason: AuthenticationRejectionReason.REJECTED,
                retryAfterMs: 0n,
              }, { kind: MessageKind.ERROR });
          }
          if (auth.username !== EXPECTED_USERNAME || password !== EXPECTED_PASSWORD
              || !clientDeviceId) {
            throw new Error("unexpected V2 fixture credentials");
          }
          return establishedSession(request, clientDeviceId);
        }
        case MessageType.RESUME_SESSION: {
          const resume = fromBinary(ResumeSessionSchema, request.payload);
          if (resume.sessionId !== SESSION_ID || !equalBytes(resume.resumeToken, RESUME_TOKEN)
              || !clientDeviceId) {
            throw new Error("unexpected V2 fixture resume proof");
          }
          resumed = true;
          return establishedSession(request, clientDeviceId);
        }
        case MessageType.LIST_CONVERSATIONS:
          fromBinary(ListConversationsSchema, request.payload);
          requireSession(request);
          return response(request, MessageType.CONVERSATION_DIRECTORY_PAGE,
            ConversationDirectoryPageSchema, {
              conversations: [{
                conversationId: FIXTURE_CONVERSATION_ID,
                kind: ConversationKind.DIRECT,
                displayName: "Browser Fixture Conversation",
                role: ConversationRole.MEMBER,
                latestSequence: resumed ? 2n : 1n,
                lastReadSequence: 0n,
                updatedAtEpochMs: NOW,
              }, {
                conversationId: KEYBOARD_CONVERSATION_ID,
                kind: ConversationKind.DIRECT,
                displayName: "Keyboard Target Conversation",
                role: ConversationRole.MEMBER,
                latestSequence: forwardedClientMessageId ? 1n : 0n,
                lastReadSequence: 0n,
                updatedAtEpochMs: NOW - 1_000n,
              }],
              nextUpdatedAtEpochMs: NOW - 1_000n,
              nextConversationId: KEYBOARD_CONVERSATION_ID,
              hasMore: false,
            }, { sessionId: SESSION_ID });
        case MessageType.LIST_DEVICES:
          fromBinary(ListDevicesSchema, request.payload);
          requireSession(request);
          return response(request, MessageType.DEVICE_DIRECTORY,
            DeviceDirectorySchema, { devices: [{
              deviceId: clientDeviceId,
              platform: ClientPlatform.WEB,
              createdAtEpochMs: NOW - 60_000n,
              lastSeenAtEpochMs: NOW,
              current: true,
            }] }, { sessionId: SESSION_ID });
        case MessageType.LIST_CONVERSATION_PARTICIPANTS: {
          const participants = fromBinary(ListConversationParticipantsSchema, request.payload);
          requireSession(request);
          if (participants.conversationId !== FIXTURE_CONVERSATION_ID
              || participants.afterAccountId !== "" || participants.limit !== 100) {
            throw new Error("unexpected V2 fixture participant request");
          }
          participantRequests.push({
            conversationId: participants.conversationId,
            afterAccountId: participants.afterAccountId,
            limit: participants.limit,
          });
          return response(request, MessageType.CONVERSATION_PARTICIPANT_PAGE,
            ConversationParticipantPageSchema, {
              conversationId: FIXTURE_CONVERSATION_ID,
              participants: [
                { accountId: ACCOUNT_ID, displayName: "Browser V2 User", role: ConversationRole.MEMBER },
                { accountId: PEER_ACCOUNT_ID, displayName: "李雷", role: ConversationRole.MEMBER },
              ],
              nextAccountId: PEER_ACCOUNT_ID,
              hasMore: false,
            }, { sessionId: SESSION_ID });
        }
        case MessageType.READ_MESSAGE_HISTORY: {
          const history = fromBinary(ReadMessageHistorySchema, request.payload);
          requireSession(request);
          if (history.conversationId === KEYBOARD_CONVERSATION_ID) {
            if (history.afterSequence !== 0n || !forwardedClientMessageId) {
              throw new Error("unexpected V2 fixture forwarded history cursor");
            }
            return response(request, MessageType.MESSAGE_HISTORY_PAGE,
              MessageHistoryPageSchema, {
                conversationId: KEYBOARD_CONVERSATION_ID,
                messages: [create(MessageRecordSchema, {
                  conversationId: KEYBOARD_CONVERSATION_ID,
                  messageId: FORWARDED_MESSAGE_ID,
                  conversationSequence: 1n,
                  senderAccountId: ACCOUNT_ID,
                  senderDeviceId: clientDeviceId,
                  clientMessageId: forwardedClientMessageId,
                  contentType: MessageContentType.TEXT_UTF8,
                  content: new TextEncoder().encode("Fixture incoming message"),
                  acceptedAtEpochMs: NOW + 2_000n,
                  forwarded: true,
                })],
                nextSequence: 1n,
                latestSequence: 1n,
                hasMore: false,
              }, { sessionId: SESSION_ID });
          }
          if (history.conversationId !== FIXTURE_CONVERSATION_ID
              || (history.afterSequence !== 0n && history.afterSequence !== 1n)) {
            throw new Error("unexpected V2 fixture history cursor");
          }
          if (history.afterSequence === 1n) {
            if (!resumed) throw new Error("history repair requires a resumed fixture session");
            const repaired = create(MessageRecordSchema, {
              conversationId: FIXTURE_CONVERSATION_ID,
              messageId: OUTGOING_MESSAGE_ID,
              conversationSequence: 2n,
              senderAccountId: PEER_ACCOUNT_ID,
              senderDeviceId: PEER_DEVICE_ID,
              clientMessageId: "browser-fixture-repaired",
              contentType: MessageContentType.TEXT_UTF8,
              content: new TextEncoder().encode("Fixture repaired message"),
              acceptedAtEpochMs: NOW + 1_000n,
            });
            return response(request, MessageType.MESSAGE_HISTORY_PAGE,
              MessageHistoryPageSchema, {
                conversationId: FIXTURE_CONVERSATION_ID,
                messages: [repaired],
                nextSequence: 2n,
                latestSequence: 2n,
                hasMore: false,
              }, { sessionId: SESSION_ID });
          }
          return response(request, MessageType.MESSAGE_HISTORY_PAGE,
            MessageHistoryPageSchema, {
              conversationId: FIXTURE_CONVERSATION_ID,
              messages: [incomingMessage()],
              nextSequence: 1n,
              latestSequence: 1n,
              hasMore: false,
            }, { sessionId: SESSION_ID });
        }
        case MessageType.SEARCH_CONVERSATION_MESSAGES: {
          const search = fromBinary(SearchConversationMessagesSchema, request.payload);
          requireSession(request);
          if (search.conversationId !== FIXTURE_CONVERSATION_ID
              || search.literalQuery !== "Fixture" || search.beforeSequence !== 0n
              || search.limit !== 50) {
            throw new Error("unexpected V2 fixture message search");
          }
          searchQueries.push({
            conversationId: search.conversationId,
            literalQuery: search.literalQuery,
            beforeSequence: search.beforeSequence,
            limit: search.limit,
          });
          return response(request, MessageType.CONVERSATION_MESSAGE_SEARCH_PAGE,
            ConversationMessageSearchPageSchema, {
              conversationId: FIXTURE_CONVERSATION_ID,
              hits: [incomingMessage()],
              nextBeforeSequence: 1n,
              hasMore: false,
            }, { sessionId: SESSION_ID });
        }
        case MessageType.FORWARD_MESSAGE: {
          const forward = fromBinary(ForwardMessageSchema, request.payload);
          requireSession(request);
          if (forward.sourceConversationId !== FIXTURE_CONVERSATION_ID
              || forward.sourceMessageId !== INCOMING_MESSAGE_ID
              || forward.expectedSourceContentRevision !== 0
              || forward.targetConversationId !== KEYBOARD_CONVERSATION_ID
              || !request.clientMessageId) {
            throw new Error("unexpected V2 fixture message forward");
          }
          forwardedClientMessageId = request.clientMessageId;
          forwardRequests.push({
            sourceConversationId: forward.sourceConversationId,
            sourceMessageId: forward.sourceMessageId,
            expectedSourceContentRevision: forward.expectedSourceContentRevision,
            targetConversationId: forward.targetConversationId,
            clientMessageId: request.clientMessageId,
          });
          if (dropForwardAcceptance) {
            dropForwardAcceptance = false;
            return null;
          }
          return response(request, MessageType.MESSAGE_ACCEPTED,
            MessageAcceptedSchema, {
              conversationId: KEYBOARD_CONVERSATION_ID,
              messageId: FORWARDED_MESSAGE_ID,
              conversationSequence: 1n,
              acceptedAtEpochMs: NOW + 2_000n,
              duplicate: false,
            }, { sessionId: SESSION_ID });
        }
        case MessageType.SUBMIT_MESSAGE: {
          const submission = fromBinary(SubmitMessageSchema, request.payload);
          requireSession(request);
          const text = new TextDecoder().decode(submission.content);
          const mentioned = text === "@李雷 Fixture mentioned message";
          if (submission.conversationId !== FIXTURE_CONVERSATION_ID
              || submission.contentType !== MessageContentType.TEXT_UTF8
              || (!mentioned && !["Fixture outgoing message", "Offline queued message"].includes(text))
              || !request.clientMessageId) {
            throw new Error("unexpected V2 fixture message submission");
          }
          if (mentioned) {
            if (submission.mentions.length !== 1
                || submission.mentions[0]?.targetAccountId !== PEER_ACCOUNT_ID
                || submission.mentions[0]?.startUtf8Byte !== 0
                || submission.mentions[0]?.lengthUtf8Bytes !== 7) {
              throw new Error("unexpected V2 fixture mention span");
            }
            mentionSubmissions.push({
              text,
              targetAccountId: submission.mentions[0].targetAccountId,
              startUtf8Byte: submission.mentions[0].startUtf8Byte,
              lengthUtf8Bytes: submission.mentions[0].lengthUtf8Bytes,
            });
          } else if (submission.mentions.length !== 0) {
            throw new Error("unexpected mentions on ordinary V2 fixture submission");
          }
          return response(request, MessageType.MESSAGE_ACCEPTED,
            MessageAcceptedSchema, {
              conversationId: FIXTURE_CONVERSATION_ID,
              messageId: OUTGOING_MESSAGE_ID,
              conversationSequence: resumed ? 3n : 2n,
              acceptedAtEpochMs: NOW + 1_000n,
              duplicate: false,
            }, { sessionId: SESSION_ID });
        }
        default:
          throw new Error(`unsupported V2 fixture command ${request.messageType}`);
      }
    },
  };
}

function incomingMessage() {
  return create(MessageRecordSchema, {
    conversationId: FIXTURE_CONVERSATION_ID,
    messageId: INCOMING_MESSAGE_ID,
    conversationSequence: 1n,
    senderAccountId: PEER_ACCOUNT_ID,
    senderDeviceId: PEER_DEVICE_ID,
    clientMessageId: "browser-fixture-incoming",
    contentType: MessageContentType.TEXT_UTF8,
    content: new TextEncoder().encode("Fixture incoming message"),
    acceptedAtEpochMs: NOW - 1_000n,
  });
}

function establishedSession(request: Envelope, clientDeviceId: string): number[] {
  return response(request, MessageType.SESSION_ESTABLISHED, SessionEstablishedSchema, {
    accountId: ACCOUNT_ID,
    deviceId: clientDeviceId,
    sessionId: SESSION_ID,
    resumeToken: RESUME_TOKEN,
    expiresAtEpochMs: NOW + 60_000n,
    displayName: "Browser V2 User",
  }, { sessionId: SESSION_ID });
}

function equalBytes(left: Uint8Array, right: Uint8Array): boolean {
  return left.byteLength === right.byteLength
    && left.every((value, index) => value === right[index]);
}

function requireSession(request: Envelope): void {
  if (request.sessionId !== SESSION_ID) throw new Error("invalid V2 fixture session");
}

function response<Desc extends DescMessage>(
  request: Envelope,
  messageType: MessageType,
  schema: Desc,
  value: MessageInitShape<Desc>,
  options: { kind?: MessageKind; sessionId?: string } = {},
): number[] {
  const payload = toBinary(schema, create(schema, value));
  const envelope = toBinary(EnvelopeSchema, create(EnvelopeSchema, {
    protocolVersion: 2,
    kind: options.kind ?? MessageKind.RESPONSE,
    messageType,
    requestId: request.requestId,
    sessionId: options.sessionId ?? "",
    clientMessageId: request.clientMessageId,
    sentAtEpochMs: NOW + 1n,
    payload,
  }));
  return Array.from(envelope);
}
