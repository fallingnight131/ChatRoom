import assert from "node:assert/strict";
import test from "node:test";

import { create, fromBinary, toBinary } from "@bufbuild/protobuf";

import {
  AuthenticateSchema,
  ResumeSessionSchema,
  SessionEstablishedSchema,
} from "../src/protocol/v2/generated/authentication_pb";
import {
  ConversationDirectoryPageSchema,
  ConversationKind,
  ConversationRole,
  ListConversationsSchema,
} from "../src/protocol/v2/generated/conversation_pb";
import {
  ClientHelloSchema,
  ClientPlatform,
  MessageType,
  ServerHelloSchema,
} from "../src/protocol/v2/generated/control_pb";
import { EnvelopeSchema, MessageKind, type Envelope } from "../src/protocol/v2/generated/envelope_pb";
import {
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
  ReadMessageHistorySchema,
  SubmitMessageSchema,
} from "../src/protocol/v2/generated/messaging_pb";
import { V2WebProtocolClient } from "../src/protocol/v2/webProtocolClient";

const UNKNOWN_REQUEST_ID = "10000000-0000-4000-8000-999999999999";
const ACCOUNT_ID = "20000000-0000-4000-8000-000000000001";
const DEVICE_ID = "30000000-0000-4000-8000-000000000001";
const SESSION_ID = "40000000-0000-4000-8000-000000000001";
const CONVERSATION_ID = "50000000-0000-4000-8000-000000000001";
const SECOND_CONVERSATION_ID = "50000000-0000-4000-8000-000000000002";
const MESSAGE_ID = "60000000-0000-4000-8000-000000000001";
const CLIENT_MESSAGE_ID = "client-message-1";
const NOW = 1_800_000_000_000;

function newClient(): V2WebProtocolClient {
  let next = 0;
  return new V2WebProtocolClient({
    appVersion: "2.0.0-test",
    clientDeviceId: "web-test-device",
    createRequestId: () => `10000000-0000-4000-8000-${String(++next).padStart(12, "0")}`,
    now: () => NOW,
  });
}

function decodeEnvelope(bytes: Uint8Array): Envelope {
  return fromBinary(EnvelopeSchema, bytes);
}

function response(
  request: Envelope,
  messageType: MessageType,
  payload: Uint8Array,
  options: { sessionId?: string; kind?: MessageKind; requestId?: string; clientMessageId?: string } = {},
): Uint8Array {
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, {
    protocolVersion: 2,
    kind: options.kind ?? MessageKind.RESPONSE,
    messageType,
    requestId: options.requestId ?? request.requestId,
    sessionId: options.sessionId ?? "",
    clientMessageId: options.clientMessageId ?? request.clientMessageId,
    sentAtEpochMs: BigInt(NOW + 1),
    payload,
  }));
}

function negotiate(client: V2WebProtocolClient): Envelope {
  const helloEnvelope = decodeEnvelope(client.createClientHello());
  const hello = fromBinary(ClientHelloSchema, helloEnvelope.payload);
  assert.equal(hello.minimumProtocolVersion, 2);
  assert.equal(hello.maximumProtocolVersion, 2);
  assert.equal(hello.platform, ClientPlatform.WEB);
  assert.equal(hello.appVersion, "2.0.0-test");
  assert.equal(hello.clientDeviceId, "web-test-device");
  const event = client.receive(response(
    helloEnvelope,
    MessageType.SERVER_HELLO,
    toBinary(ServerHelloSchema, create(ServerHelloSchema, {
      selectedProtocolVersion: 2,
      connectionId: "gateway-connection-1",
      serverTimeEpochMs: BigInt(NOW),
      maximumFrameBytes: 1024 * 1024 + 1024,
    })),
  ));
  assert.equal(event.type, "server-hello");
  assert.equal(client.state, "negotiated");
  return helloEnvelope;
}

function authenticate(client: V2WebProtocolClient): Envelope {
  negotiate(client);
  const callerPassword = new TextEncoder().encode("correct horse battery staple");
  const passwordBefore = callerPassword.slice();
  const authEnvelope = decodeEnvelope(client.authenticate("alice", callerPassword));
  assert.deepEqual(callerPassword, passwordBefore, "the caller remains responsible for its own buffer");
  const auth = fromBinary(AuthenticateSchema, authEnvelope.payload);
  assert.equal(auth.username, "alice");
  assert.deepEqual(auth.passwordUtf8, passwordBefore);

  const token = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
  const event = client.receive(response(
    authEnvelope,
    MessageType.SESSION_ESTABLISHED,
    toBinary(SessionEstablishedSchema, create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: token,
      expiresAtEpochMs: BigInt(NOW + 60_000),
      displayName: "Alice",
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(event.type, "session-established");
  assert.equal(client.state, "authenticated");
  const firstCopy = client.session!.resumeToken;
  firstCopy.fill(0);
  assert.deepEqual(client.session!.resumeToken, token, "session access returns a defensive token copy");
  return authEnvelope;
}

test("negotiates and authenticates without retaining caller password bytes", () => {
  const client = newClient();
  authenticate(client);
  assert.equal(client.session?.sessionId, SESSION_ID);
  client.close();
  assert.equal(client.state, "closed");
  assert.equal(client.session, null);
});

test("encodes an explicit resume proof and accepts only the rotated session response", () => {
  const client = newClient();
  negotiate(client);
  const callerToken = Uint8Array.from({ length: 32 }, (_, index) => 32 - index);
  const before = callerToken.slice();
  const resumeEnvelope = decodeEnvelope(client.resumeSession(SESSION_ID, callerToken));
  assert.deepEqual(callerToken, before, "the caller retains ownership of its proof buffer");
  assert.equal(resumeEnvelope.messageType, MessageType.RESUME_SESSION);
  const resume = fromBinary(ResumeSessionSchema, resumeEnvelope.payload);
  assert.equal(resume.sessionId, SESSION_ID);
  assert.deepEqual(resume.resumeToken, before);

  const rotated = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
  client.receive(response(
    resumeEnvelope,
    MessageType.SESSION_ESTABLISHED,
    toBinary(SessionEstablishedSchema, create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: rotated,
      expiresAtEpochMs: BigInt(NOW + 60_000),
      displayName: "Alice",
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(client.state, "authenticated");
  assert.deepEqual(client.session?.resumeToken, rotated);
});

test("encodes authenticated directory, history, and idempotent text commands", () => {
  const client = newClient();
  authenticate(client);

  const directoryEnvelope = decodeEnvelope(client.listConversations(25, {
    updatedAtEpochMs: BigInt(NOW),
    conversationId: CONVERSATION_ID,
  }));
  assert.equal(directoryEnvelope.sessionId, SESSION_ID);
  const directory = fromBinary(ListConversationsSchema, directoryEnvelope.payload);
  assert.equal(directory.limit, 25);
  assert.equal(directory.afterUpdatedAtEpochMs, BigInt(NOW));
  assert.equal(directory.afterConversationId, CONVERSATION_ID);

  const historyEnvelope = decodeEnvelope(client.readMessageHistory(CONVERSATION_ID, 8n, 50));
  const history = fromBinary(ReadMessageHistorySchema, historyEnvelope.payload);
  assert.deepEqual(
    { conversationId: history.conversationId, afterSequence: history.afterSequence, limit: history.limit },
    { conversationId: CONVERSATION_ID, afterSequence: 8n, limit: 50 },
  );

  const submitEnvelope = decodeEnvelope(client.submitText(CONVERSATION_ID, CLIENT_MESSAGE_ID, "hello V2"));
  assert.equal(submitEnvelope.clientMessageId, CLIENT_MESSAGE_ID);
  const submit = fromBinary(SubmitMessageSchema, submitEnvelope.payload);
  assert.equal(submit.contentType, MessageContentType.TEXT_UTF8);
  assert.equal(new TextDecoder().decode(submit.content), "hello V2");
});

test("validates correlated directory, history, and accepted responses", () => {
  const client = newClient();
  authenticate(client);

  const directoryRequest = decodeEnvelope(client.listConversations(2));
  const directoryEvent = client.receive(response(
    directoryRequest,
    MessageType.CONVERSATION_DIRECTORY_PAGE,
    toBinary(ConversationDirectoryPageSchema, create(ConversationDirectoryPageSchema, {
      conversations: [
        {
          conversationId: SECOND_CONVERSATION_ID,
          kind: ConversationKind.GROUP,
          displayName: "Architecture",
          role: ConversationRole.MEMBER,
          latestSequence: 3n,
          lastReadSequence: 2n,
          updatedAtEpochMs: BigInt(NOW),
        },
        {
          conversationId: CONVERSATION_ID,
          kind: ConversationKind.DIRECT,
          displayName: "Bob",
          role: ConversationRole.MEMBER,
          latestSequence: 1n,
          lastReadSequence: 1n,
          updatedAtEpochMs: BigInt(NOW),
        },
      ],
      nextUpdatedAtEpochMs: BigInt(NOW),
      nextConversationId: CONVERSATION_ID,
      hasMore: false,
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(directoryEvent.type, "conversation-directory-page");

  const historyRequest = decodeEnvelope(client.readMessageHistory(CONVERSATION_ID, 0n, 10));
  const historyEvent = client.receive(response(
    historyRequest,
    MessageType.MESSAGE_HISTORY_PAGE,
    toBinary(MessageHistoryPageSchema, create(MessageHistoryPageSchema, {
      conversationId: CONVERSATION_ID,
      messages: [{
        conversationId: CONVERSATION_ID,
        messageId: MESSAGE_ID,
        conversationSequence: 1n,
        senderAccountId: ACCOUNT_ID,
        senderDeviceId: DEVICE_ID,
        clientMessageId: CLIENT_MESSAGE_ID,
        contentType: MessageContentType.TEXT_UTF8,
        content: new TextEncoder().encode("hello"),
        acceptedAtEpochMs: BigInt(NOW),
      }],
      nextSequence: 1n,
      latestSequence: 1n,
      hasMore: false,
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(historyEvent.type, "message-history-page");

  const submitRequest = decodeEnvelope(client.submitText(CONVERSATION_ID, CLIENT_MESSAGE_ID, "hello"));
  const acceptedEvent = client.receive(response(
    submitRequest,
    MessageType.MESSAGE_ACCEPTED,
    toBinary(MessageAcceptedSchema, create(MessageAcceptedSchema, {
      conversationId: CONVERSATION_ID,
      messageId: MESSAGE_ID,
      conversationSequence: 2n,
      acceptedAtEpochMs: BigInt(NOW),
      duplicate: false,
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(acceptedEvent.type, "message-accepted");
  assert.equal(acceptedEvent.requestId, submitRequest.requestId);
  assert.equal(acceptedEvent.clientMessageId, CLIENT_MESSAGE_ID);
});

test("rejects invalid transitions, unknown requests, wrong sessions, and response type confusion", () => {
  const client = newClient();
  assert.throws(() => client.listConversations(10), /expected authenticated/);
  const hello = decodeEnvelope(client.createClientHello());
  const serverHello = toBinary(ServerHelloSchema, create(ServerHelloSchema, {
    selectedProtocolVersion: 2,
    connectionId: "gateway-connection-1",
    serverTimeEpochMs: BigInt(NOW),
    maximumFrameBytes: 1024 * 1024 + 1024,
  }));
  assert.throws(
    () => client.receive(response(hello, MessageType.SERVER_HELLO, serverHello, { requestId: UNKNOWN_REQUEST_ID })),
    /pending request/,
  );
  client.receive(response(hello, MessageType.SERVER_HELLO, serverHello));

  const auth = decodeEnvelope(client.authenticate("alice", new TextEncoder().encode("password")));
  const established = toBinary(SessionEstablishedSchema, create(SessionEstablishedSchema, {
    accountId: ACCOUNT_ID,
    deviceId: DEVICE_ID,
    sessionId: SESSION_ID,
    resumeToken: new Uint8Array(32),
    expiresAtEpochMs: BigInt(NOW + 60_000),
    displayName: "Alice",
  }));
  client.receive(response(auth, MessageType.SESSION_ESTABLISHED, established, { sessionId: SESSION_ID }));

  const request = decodeEnvelope(client.listConversations(10));
  assert.throws(
    () => client.receive(response(request, MessageType.MESSAGE_HISTORY_PAGE, new Uint8Array(), { sessionId: SESSION_ID })),
    /message type does not match/,
  );
  assert.throws(
    () => client.receive(response(request, MessageType.CONVERSATION_DIRECTORY_PAGE, new Uint8Array(), {
      sessionId: SESSION_ID,
      kind: MessageKind.ERROR,
    })),
    /successful payload requires response/,
  );
  assert.throws(
    () => client.receive(response(request, MessageType.CONVERSATION_DIRECTORY_PAGE, new Uint8Array(), { sessionId: DEVICE_ID })),
    /session does not match/,
  );
  client.receive(response(
    request,
    MessageType.CONVERSATION_DIRECTORY_PAGE,
    toBinary(ConversationDirectoryPageSchema, create(ConversationDirectoryPageSchema, {})),
    { sessionId: SESSION_ID },
  ));
  const submit = decodeEnvelope(client.submitText(CONVERSATION_ID, CLIENT_MESSAGE_ID, "correlated"));
  assert.throws(
    () => client.receive(response(submit, MessageType.MESSAGE_ACCEPTED, new Uint8Array(), {
      sessionId: SESSION_ID,
      clientMessageId: "wrong-client-message",
    })),
    /clientMessageId does not match/,
  );
  for (let index = 0; index < 15; index += 1) client.listConversations(1);
  assert.throws(() => client.listConversations(1), /too many pending/);
});

test("rejects malformed and semantically invalid server data", () => {
  const client = newClient();
  assert.throws(() => client.receive(Uint8Array.of(255, 255)), /invalid V2 envelope/);
  const hello = decodeEnvelope(client.createClientHello());
  const invalidHello = toBinary(ServerHelloSchema, create(ServerHelloSchema, {
    selectedProtocolVersion: 1,
    connectionId: "gateway-connection-1",
    serverTimeEpochMs: BigInt(NOW),
    maximumFrameBytes: 1024,
  }));
  assert.throws(
    () => client.receive(response(hello, MessageType.SERVER_HELLO, invalidHello)),
    /invalid server hello/,
  );
});
