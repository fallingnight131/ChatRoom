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
  ConversationRole,
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
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
  MessageRecordSchema,
  ReadMessageHistorySchema,
  SubmitMessageSchema,
} from "../../src/protocol/v2/generated/messaging_pb";

const NOW = 1_800_000_000_000n;
const ACCOUNT_ID = "20000000-0000-4000-8000-000000000001";
const PEER_ACCOUNT_ID = "20000000-0000-4000-8000-000000000002";
const PEER_DEVICE_ID = "30000000-0000-4000-8000-000000000002";
const SESSION_ID = "40000000-0000-4000-8000-000000000001";
export const FIXTURE_CONVERSATION_ID = "50000000-0000-4000-8000-000000000001";
const KEYBOARD_CONVERSATION_ID = "50000000-0000-4000-8000-000000000002";
const INCOMING_MESSAGE_ID = "60000000-0000-4000-8000-000000000001";
const OUTGOING_MESSAGE_ID = "60000000-0000-4000-8000-000000000002";
const EXPECTED_USERNAME = "browser_v2_user";
const EXPECTED_PASSWORD = "non-secret-test-value";
const RESUME_TOKEN = Uint8Array.from({ length: 32 }, (_, index) => index + 1);

export type V2ProtocolFixtureMode = "accept" | "reject";

export interface V2ProtocolFixture {
  readonly receivedTypes: MessageType[];
  respond(bytes: number[]): number[];
}

export function createV2ProtocolFixture(mode: V2ProtocolFixtureMode): V2ProtocolFixture {
  const receivedTypes: MessageType[] = [];
  let clientDeviceId = "";
  let resumed = false;

  return {
    receivedTypes,
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
                latestSequence: 0n,
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
        case MessageType.READ_MESSAGE_HISTORY: {
          const history = fromBinary(ReadMessageHistorySchema, request.payload);
          requireSession(request);
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
          const message = create(MessageRecordSchema, {
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
          return response(request, MessageType.MESSAGE_HISTORY_PAGE,
            MessageHistoryPageSchema, {
              conversationId: FIXTURE_CONVERSATION_ID,
              messages: [message],
              nextSequence: 1n,
              latestSequence: 1n,
              hasMore: false,
            }, { sessionId: SESSION_ID });
        }
        case MessageType.SUBMIT_MESSAGE: {
          const submission = fromBinary(SubmitMessageSchema, request.payload);
          requireSession(request);
          const text = new TextDecoder().decode(submission.content);
          if (submission.conversationId !== FIXTURE_CONVERSATION_ID
              || submission.contentType !== MessageContentType.TEXT_UTF8
              || !["Fixture outgoing message", "Offline queued message"].includes(text)
              || !request.clientMessageId) {
            throw new Error("unexpected V2 fixture message submission");
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
