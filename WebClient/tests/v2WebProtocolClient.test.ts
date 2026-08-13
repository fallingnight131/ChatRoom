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
  ConversationEntryRecordSchema,
  MessageAcceptedSchema,
  MessageContentType,
  MessageHistoryPageSchema,
  MessageRecordSchema,
  MessageRecalledRecordSchema,
  ReadMessageHistorySchema,
  SubmitMessageSchema,
  SubmitReplyMessageSchema,
} from "../src/protocol/v2/generated/messaging_pb";
import { V2WebProtocolClient } from "../src/protocol/v2/webProtocolClient";
import {
  AttachmentReadySchema,
  AttachmentRegisteredSchema,
  AttachmentUploadAuthorizedSchema,
  AuthorizeAttachmentUploadSchema,
  CompleteAttachmentUploadSchema,
  RegisterAttachmentSchema,
} from "../src/protocol/v2/generated/attachment_pb";
import {
  DeviceDirectorySchema,
  DeviceRevokedSchema,
  ListDevicesSchema,
  RevokeDeviceSchema,
} from "../src/protocol/v2/generated/device_management_pb";

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

function publishedMessage(options: {
  requestId?: string;
  sessionId?: string;
  kind?: MessageKind;
  replyTargetSequence?: bigint;
} = {}): Uint8Array {
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, {
    protocolVersion: 2,
    kind: options.kind ?? MessageKind.EVENT,
    messageType: MessageType.MESSAGE_PUBLISHED,
    requestId: options.requestId ?? "",
    sessionId: options.sessionId ?? SESSION_ID,
    sentAtEpochMs: BigInt(NOW + 1),
    payload: toBinary(MessageRecordSchema, create(MessageRecordSchema, {
      conversationId: CONVERSATION_ID,
      messageId: MESSAGE_ID,
      conversationSequence: 2n,
      senderAccountId: ACCOUNT_ID,
      senderDeviceId: DEVICE_ID,
      clientMessageId: CLIENT_MESSAGE_ID,
      contentType: MessageContentType.TEXT_UTF8,
      content: new TextEncoder().encode("live"),
      acceptedAtEpochMs: BigInt(NOW),
      reply: options.replyTargetSequence === undefined ? undefined : {
        targetMessageId: "60000000-0000-4000-8000-000000000002",
        targetConversationSequence: options.replyTargetSequence,
        targetSenderAccountId: ACCOUNT_ID,
      },
    })),
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

  const replyEnvelope = decodeEnvelope(client.submitReply(
    CONVERSATION_ID, MESSAGE_ID, "client-reply-1", "reply V2"));
  assert.equal(replyEnvelope.messageType, MessageType.SUBMIT_REPLY_MESSAGE);
  assert.equal(replyEnvelope.clientMessageId, "client-reply-1");
  const reply = fromBinary(SubmitReplyMessageSchema, replyEnvelope.payload);
  assert.equal(reply.targetMessageId, MESSAGE_ID);
  assert.equal(reply.contentType, MessageContentType.TEXT_UTF8);
  assert.equal(new TextDecoder().decode(reply.content), "reply V2");
});

test("encodes and validates bounded device management commands", () => {
  const client = newClient();
  authenticate(client);
  const target = "30000000-0000-4000-8000-000000000002";

  const listRequest = decodeEnvelope(client.listDevices().bytes);
  assert.equal(listRequest.messageType, MessageType.LIST_DEVICES);
  assert.deepEqual(fromBinary(ListDevicesSchema, listRequest.payload), create(ListDevicesSchema, {}));
  const listed = client.receive(response(listRequest, MessageType.DEVICE_DIRECTORY,
    toBinary(DeviceDirectorySchema, create(DeviceDirectorySchema, { devices: [
      { deviceId: DEVICE_ID, platform: ClientPlatform.WEB, createdAtEpochMs: 1n,
        lastSeenAtEpochMs: 2n, current: true },
      { deviceId: target, platform: ClientPlatform.WINDOWS, createdAtEpochMs: 1n,
        lastSeenAtEpochMs: 3n, current: false },
    ] })), { sessionId: SESSION_ID }));
  assert.equal(listed.type, "device-directory");

  assert.throws(() => client.revokeDevice(DEVICE_ID), /current device/);
  const revokeRequest = decodeEnvelope(client.revokeDevice(target).bytes);
  assert.equal(fromBinary(RevokeDeviceSchema, revokeRequest.payload).targetDeviceId, target);
  const revoked = client.receive(response(revokeRequest, MessageType.DEVICE_REVOKED,
    toBinary(DeviceRevokedSchema, create(DeviceRevokedSchema, {
      targetDeviceId: target, revokedAtEpochMs: BigInt(NOW), revokedSessions: 2, changed: true,
    })), { sessionId: SESSION_ID }));
  assert.equal(revoked.type, "device-revoked");
});

test("encodes and correlates the bounded V2 attachment workflow", () => {
  const client = newClient();
  authenticate(client);
  const attachmentId = "70000000-0000-4000-8000-000000000001";
  const clientAttachmentId = "client-attachment-1";
  const hash = new Uint8Array(32).fill(7);

  const registerCommand = client.registerAttachment(
    CONVERSATION_ID,
    clientAttachmentId,
    "photo.bin",
    "application/octet-stream",
    4n,
    hash,
  );
  const registerRequest = decodeEnvelope(registerCommand.bytes);
  assert.equal(registerCommand.requestId, registerRequest.requestId);
  assert.equal(registerRequest.messageType, MessageType.REGISTER_ATTACHMENT);
  assert.equal(registerRequest.clientMessageId, clientAttachmentId);
  const register = fromBinary(RegisterAttachmentSchema, registerRequest.payload);
  assert.equal(register.conversationId, CONVERSATION_ID);
  assert.deepEqual(register.contentSha256, hash);
  const registered = client.receive(response(
    registerRequest,
    MessageType.ATTACHMENT_REGISTERED,
    toBinary(AttachmentRegisteredSchema, create(AttachmentRegisteredSchema, {
      attachmentId,
      conversationId: CONVERSATION_ID,
      clientAttachmentId,
      duplicate: false,
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(registered.type, "attachment-registered");

  const authorizeRequest = decodeEnvelope(client.authorizeAttachmentUpload(attachmentId).bytes);
  assert.equal(fromBinary(AuthorizeAttachmentUploadSchema, authorizeRequest.payload).attachmentId, attachmentId);
  const authorized = client.receive(response(
    authorizeRequest,
    MessageType.ATTACHMENT_UPLOAD_AUTHORIZED,
    toBinary(AttachmentUploadAuthorizedSchema, create(AttachmentUploadAuthorizedSchema, {
      attachmentId,
      uploadUri: "https://objects.example.test/key?signature=secret",
      requiredHeaders: [{ name: "if-none-match", value: "*" }],
      expiresAtEpochMs: BigInt(NOW + 60_000),
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(authorized.type, "attachment-upload-authorized");

  const completeRequest = decodeEnvelope(client.completeAttachmentUpload(attachmentId).bytes);
  assert.equal(fromBinary(CompleteAttachmentUploadSchema, completeRequest.payload).attachmentId, attachmentId);
  const ready = client.receive(response(
    completeRequest,
    MessageType.ATTACHMENT_READY,
    toBinary(AttachmentReadySchema, create(AttachmentReadySchema, {
      attachmentId,
      conversationId: CONVERSATION_ID,
      readyAtEpochMs: BigInt(NOW + 1),
    })),
    { sessionId: SESSION_ID },
  ));
  assert.equal(ready.type, "attachment-ready");
});

test("rejects unsafe or malformed attachment grants", () => {
  const client = newClient();
  authenticate(client);
  const attachmentId = "70000000-0000-4000-8000-000000000001";
  const request = decodeEnvelope(client.authorizeAttachmentUpload(attachmentId).bytes);

  assert.throws(() => client.receive(response(
    request,
    MessageType.ATTACHMENT_UPLOAD_AUTHORIZED,
    toBinary(AttachmentUploadAuthorizedSchema, create(AttachmentUploadAuthorizedSchema, {
      attachmentId,
      uploadUri: "http://objects.example.test/key",
      requiredHeaders: [{ name: "Host", value: "objects.example.test" }],
      expiresAtEpochMs: BigInt(NOW + 60_000),
    })),
    { sessionId: SESSION_ID },
  )), /invalid attachment upload authorization|invalid V2 response payload/);
});

test("tombstones cancelled attachment requests without consuming active capacity", () => {
  const client = newClient();
  authenticate(client);
  const attachmentId = "70000000-0000-4000-8000-000000000001";
  const first = client.authorizeAttachmentUpload(attachmentId);
  client.cancelPendingRequest(first.requestId);
  const ignored = client.receive(response(
    decodeEnvelope(first.bytes),
    MessageType.ATTACHMENT_UPLOAD_AUTHORIZED,
    new Uint8Array(),
    { sessionId: SESSION_ID },
  ));
  assert.equal(ignored.type, "cancelled-response");

  for (let index = 0; index < 40; index += 1) {
    const command = client.authorizeAttachmentUpload(attachmentId);
    client.cancelPendingRequest(command.requestId);
  }
  assert.doesNotThrow(() => client.authorizeAttachmentUpload(attachmentId));
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
      entries: [
        create(ConversationEntryRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 1n,
          detail: { case: "message", value: create(MessageRecordSchema, {
            conversationId: CONVERSATION_ID,
            messageId: MESSAGE_ID,
            conversationSequence: 1n,
            senderAccountId: ACCOUNT_ID,
            senderDeviceId: DEVICE_ID,
            clientMessageId: CLIENT_MESSAGE_ID,
            contentType: MessageContentType.TEXT_UTF8,
            content: new TextEncoder().encode("hello"),
            acceptedAtEpochMs: BigInt(NOW),
          }) },
        }),
        create(ConversationEntryRecordSchema, {
          conversationId: CONVERSATION_ID,
          conversationSequence: 2n,
          detail: { case: "recall", value: create(MessageRecalledRecordSchema, {
            conversationId: CONVERSATION_ID,
            conversationSequence: 2n,
            messageId: MESSAGE_ID,
            actorAccountId: ACCOUNT_ID,
            source: "V1_IMPORT",
          }) },
        }),
      ],
      nextSequence: 2n,
      latestSequence: 2n,
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

test("accepts only uncorrelated authenticated live-message events", () => {
  assert.throws(() => newClient().receive(publishedMessage()), /requires an authenticated session/);
  const client = newClient();
  authenticate(client);
  const event = client.receive(publishedMessage());
  assert.equal(event.type, "message-published");
  assert.equal(event.requestId, "");
  assert.equal(event.clientMessageId, "");
  assert.equal(event.type === "message-published" && new TextDecoder().decode(event.value.content), "live");
  const reply = client.receive(publishedMessage({ replyTargetSequence: 1n }));
  assert.equal(reply.type === "message-published"
    && reply.value.reply?.targetConversationSequence, 1n);
  assert.throws(() => client.receive(publishedMessage({ replyTargetSequence: 2n })),
    /reply target sequence/);
  assert.throws(() => client.receive(publishedMessage({ requestId: UNKNOWN_REQUEST_ID })), /must not carry request/);
  assert.throws(() => client.receive(publishedMessage({ sessionId: DEVICE_ID })), /session does not match/);
  assert.throws(() => client.receive(publishedMessage({ kind: MessageKind.RESPONSE })), /pending request/);
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
