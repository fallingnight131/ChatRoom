import assert from 'node:assert/strict'
import test from 'node:test'

import { create, fromBinary, toBinary } from '@bufbuild/protobuf'
import {
  EnvelopeSchema,
  MessageKind
} from '../generated/typescript/chat/v2/envelope_pb.js'
import {
  ClientHelloSchema,
  ClientPlatform
} from '../generated/typescript/chat/v2/control_pb.js'
import { AuthenticateSchema } from '../generated/typescript/chat/v2/authentication_pb.js'
import {
  ListAccountBlocksSchema,
  SetAccountBlockSchema
} from '../generated/typescript/chat/v2/contact_pb.js'
import {
  MessageReactionKind,
  EditMessageSchema,
  ForwardMessageSchema,
  MessageMentionSchema,
  SetMessagePinSchema,
  SetMessageReactionSchema,
  SearchConversationMessagesSchema,
  SubmitMessageSchema,
  SubmitReplyMessageSchema
} from '../generated/typescript/chat/v2/messaging_pb.js'
import {
  ListConversationParticipantsSchema,
  ListConversationsSchema
} from '../generated/typescript/chat/v2/conversation_pb.js'
import { RegisterAttachmentSchema } from '../generated/typescript/chat/v2/attachment_pb.js'
import { RevokeDeviceSchema } from '../generated/typescript/chat/v2/device_management_pb.js'
import { WebPushHttpCredentialIssuedSchema } from '../generated/typescript/chat/v2/web_push_pb.js'

const GOLDEN_HEX = '08021001186422057265712d312a0973657373696f6e2d31' +
  '3208636c69656e742d313880d095ffbc314203616263'
const CLIENT_HELLO_GOLDEN_HEX = '0802100218012205302e312e302a086465766963652d31'
const AUTHENTICATE_GOLDEN_HEX = '0a05616c696365120d746573742d70617373776f7264'
const SUBMIT_MESSAGE_GOLDEN_HEX = '0a2430303030303030302d303030302d303030302d' +
  '303030302d30303030303030303030303110011a026869'
const MENTIONED_SUBMIT_MESSAGE_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '10011a0740e69d8e20686922280a2430303030303030302d303030302d303030302d303030302d' +
  '3030303030303030303030321804'
const SUBMIT_REPLY_MESSAGE_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '122430303030303030302d303030302d303030302d303030302d303030303030303030303032' +
  '180122026869'
const SET_MESSAGE_REACTION_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '122430303030303030302d303030302d303030302d303030302d303030303030303030303032' +
  '180120012a0a7265616374696f6e2d31'
const SET_MESSAGE_PIN_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '122430303030303030302d303030302d303030302d303030302d303030303030303030303032' +
  '1801220570696e2d31'
const EDIT_MESSAGE_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '122430303030303030302d303030302d303030302d303030302d303030303030303030303032' +
  '180320012a0268693206656469742d31'
const FORWARD_MESSAGE_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '122430303030303030302d303030302d303030302d303030302d303030303030303030303032' +
  '1803222430303030303030302d303030302d303030302d303030302d303030303030303030303033'
const SEARCH_MESSAGES_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '1206e8818ae5a4a918ac022019'
const SET_ACCOUNT_BLOCK_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '10011a2430303030303030302d303030302d303030302d303030302d303030303030303030303032'
const LIST_ACCOUNT_BLOCKS_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d3030303030303030303030311019'
const LIST_CONVERSATIONS_GOLDEN_HEX = '0880d095ffbc31122430303030303030302d303030302d' +
  '303030302d303030302d3030303030303030303030321819'
const LIST_PARTICIPANTS_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '122430303030303030302d303030302d303030302d303030302d3030303030303030303030321819'
const REGISTER_ATTACHMENT_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031' +
  '12086174746163682d311a05612e747874220a746578742f706c61696e28023220' +
  '0101010101010101010101010101010101010101010101010101010101010101'
const REVOKE_DEVICE_GOLDEN_HEX =
  '0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031'
const WEB_PUSH_HTTP_CREDENTIAL_GOLDEN_HEX =
  '0a2b61616161616161616161616161616161616161616161616161616161616161616161616161616161616161122b626262626262626262626262626262626262626262626262626262626262626262626262626262626262621880a0f1c2b134'

function bytesFromHex(hex: string): Uint8Array {
  return Uint8Array.from(hex.match(/.{2}/g) ?? [], byte => Number.parseInt(byte, 16))
}

test('decodes the Java golden V2 envelope with generated TypeScript', () => {
  const envelope = fromBinary(EnvelopeSchema, bytesFromHex(GOLDEN_HEX))
  assert.equal(envelope.protocolVersion, 2)
  assert.equal(envelope.kind, MessageKind.COMMAND)
  assert.equal(envelope.messageType, 100)
  assert.equal(envelope.requestId, 'req-1')
  assert.equal(envelope.sessionId, 'session-1')
  assert.equal(envelope.clientMessageId, 'client-1')
  assert.equal(envelope.sentAtEpochMs, 1_700_000_000_000n)
  assert.equal(new TextDecoder().decode(envelope.payload), 'abc')
})

test('encodes the same deterministic bytes as the generated Java binding', () => {
  const envelope = create(EnvelopeSchema, {
    protocolVersion: 2,
    kind: MessageKind.COMMAND,
    messageType: 100,
    requestId: 'req-1',
    sessionId: 'session-1',
    clientMessageId: 'client-1',
    sentAtEpochMs: 1_700_000_000_000n,
    payload: new TextEncoder().encode('abc')
  })
  assert.equal(Buffer.from(toBinary(EnvelopeSchema, envelope)).toString('hex'), GOLDEN_HEX)
})

test('keeps the ClientHello payload compatible across generated bindings', () => {
  const decoded = fromBinary(ClientHelloSchema, bytesFromHex(CLIENT_HELLO_GOLDEN_HEX))
  assert.equal(decoded.minimumProtocolVersion, 2)
  assert.equal(decoded.maximumProtocolVersion, 2)
  assert.equal(decoded.platform, ClientPlatform.WEB)
  assert.equal(decoded.appVersion, '0.1.0')
  assert.equal(decoded.clientDeviceId, 'device-1')

  const encoded = create(ClientHelloSchema, {
    minimumProtocolVersion: 2,
    maximumProtocolVersion: 2,
    platform: ClientPlatform.WEB,
    appVersion: '0.1.0',
    clientDeviceId: 'device-1'
  })
  assert.equal(
    Buffer.from(toBinary(ClientHelloSchema, encoded)).toString('hex'),
    CLIENT_HELLO_GOLDEN_HEX
  )
})

test('keeps the bounded Authenticate payload compatible across generated bindings', () => {
  const decoded = fromBinary(AuthenticateSchema, bytesFromHex(AUTHENTICATE_GOLDEN_HEX))
  assert.equal(decoded.username, 'alice')
  assert.equal(new TextDecoder().decode(decoded.passwordUtf8), 'test-password')

  const encoded = create(AuthenticateSchema, {
    username: 'alice',
    passwordUtf8: new TextEncoder().encode('test-password')
  })
  assert.equal(
    Buffer.from(toBinary(AuthenticateSchema, encoded)).toString('hex'),
    AUTHENTICATE_GOLDEN_HEX
  )
})

test('keeps the bounded SubmitMessage payload compatible across generated bindings', () => {
  const decoded = fromBinary(SubmitMessageSchema, bytesFromHex(SUBMIT_MESSAGE_GOLDEN_HEX))
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.contentType, 1)
  assert.equal(new TextDecoder().decode(decoded.content), 'hi')

  const encoded = create(SubmitMessageSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    contentType: 1,
    content: new TextEncoder().encode('hi')
  })
  assert.equal(
    Buffer.from(toBinary(SubmitMessageSchema, encoded)).toString('hex'),
    SUBMIT_MESSAGE_GOLDEN_HEX
  )
})

test('keeps structured mention UTF-8 spans compatible across generated bindings', () => {
  const decoded = fromBinary(
    SubmitMessageSchema,
    bytesFromHex(MENTIONED_SUBMIT_MESSAGE_GOLDEN_HEX)
  )
  assert.equal(new TextDecoder().decode(decoded.content), '@李 hi')
  assert.deepEqual(decoded.mentions, [{
    $typeName: 'chat.v2.MessageMention',
    targetAccountId: '00000000-0000-0000-0000-000000000002',
    startUtf8Byte: 0,
    lengthUtf8Bytes: 4
  }])

  const encoded = create(SubmitMessageSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    contentType: 1,
    content: new TextEncoder().encode('@李 hi'),
    mentions: [create(MessageMentionSchema, {
      targetAccountId: '00000000-0000-0000-0000-000000000002',
      startUtf8Byte: 0,
      lengthUtf8Bytes: 4
    })]
  })
  assert.equal(
    Buffer.from(toBinary(SubmitMessageSchema, encoded)).toString('hex'),
    MENTIONED_SUBMIT_MESSAGE_GOLDEN_HEX
  )
})

test('keeps the bounded SubmitReplyMessage payload compatible across generated bindings', () => {
  const decoded = fromBinary(
    SubmitReplyMessageSchema,
    bytesFromHex(SUBMIT_REPLY_MESSAGE_GOLDEN_HEX)
  )
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.targetMessageId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.contentType, 1)
  assert.equal(new TextDecoder().decode(decoded.content), 'hi')

  const encoded = create(SubmitReplyMessageSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    targetMessageId: '00000000-0000-0000-0000-000000000002',
    contentType: 1,
    content: new TextEncoder().encode('hi')
  })
  assert.equal(
    Buffer.from(toBinary(SubmitReplyMessageSchema, encoded)).toString('hex'),
    SUBMIT_REPLY_MESSAGE_GOLDEN_HEX
  )
})

test('keeps the server-authoritative ForwardMessage payload compatible across bindings', () => {
  const decoded = fromBinary(ForwardMessageSchema, bytesFromHex(FORWARD_MESSAGE_GOLDEN_HEX))
  assert.equal(decoded.sourceConversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.sourceMessageId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.expectedSourceContentRevision, 3)
  assert.equal(decoded.targetConversationId, '00000000-0000-0000-0000-000000000003')

  const encoded = create(ForwardMessageSchema, {
    sourceConversationId: '00000000-0000-0000-0000-000000000001',
    sourceMessageId: '00000000-0000-0000-0000-000000000002',
    expectedSourceContentRevision: 3,
    targetConversationId: '00000000-0000-0000-0000-000000000003'
  })
  assert.equal(
    Buffer.from(toBinary(ForwardMessageSchema, encoded)).toString('hex'),
    FORWARD_MESSAGE_GOLDEN_HEX
  )
})

test('keeps the bounded Unicode conversation search payload compatible across bindings', () => {
  const decoded = fromBinary(
    SearchConversationMessagesSchema,
    bytesFromHex(SEARCH_MESSAGES_GOLDEN_HEX)
  )
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.literalQuery, '聊天')
  assert.equal(decoded.beforeSequence, 300n)
  assert.equal(decoded.limit, 25)

  const encoded = create(SearchConversationMessagesSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    literalQuery: '聊天',
    beforeSequence: 300n,
    limit: 25
  })
  assert.equal(
    Buffer.from(toBinary(SearchConversationMessagesSchema, encoded)).toString('hex'),
    SEARCH_MESSAGES_GOLDEN_HEX
  )
})

test('keeps the server-bound account block payload compatible across bindings', () => {
  const decoded = fromBinary(SetAccountBlockSchema, bytesFromHex(SET_ACCOUNT_BLOCK_GOLDEN_HEX))
  assert.equal(decoded.targetAccountId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.blocked, true)
  assert.equal(decoded.clientOperationId, '00000000-0000-0000-0000-000000000002')

  const encoded = create(SetAccountBlockSchema, {
    targetAccountId: '00000000-0000-0000-0000-000000000001',
    blocked: true,
    clientOperationId: '00000000-0000-0000-0000-000000000002'
  })
  assert.equal(
    Buffer.from(toBinary(SetAccountBlockSchema, encoded)).toString('hex'),
    SET_ACCOUNT_BLOCK_GOLDEN_HEX
  )
})

test('keeps the bounded server-bound account block directory request compatible', () => {
  const decoded = fromBinary(
    ListAccountBlocksSchema,
    bytesFromHex(LIST_ACCOUNT_BLOCKS_GOLDEN_HEX)
  )
  assert.equal(decoded.afterTargetAccountId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.limit, 25)

  const encoded = create(ListAccountBlocksSchema, {
    afterTargetAccountId: '00000000-0000-0000-0000-000000000001',
    limit: 25
  })
  assert.equal(
    Buffer.from(toBinary(ListAccountBlocksSchema, encoded)).toString('hex'),
    LIST_ACCOUNT_BLOCKS_GOLDEN_HEX
  )
})

test('keeps the session-bound Web Push HTTP credential compatible across bindings', () => {
  const decoded = fromBinary(
    WebPushHttpCredentialIssuedSchema,
    bytesFromHex(WEB_PUSH_HTTP_CREDENTIAL_GOLDEN_HEX)
  )
  assert.equal(new TextDecoder().decode(decoded.bearerTokenAscii), 'a'.repeat(43))
  assert.equal(new TextDecoder().decode(decoded.csrfTokenAscii), 'b'.repeat(43))
  assert.equal(decoded.expiresAtEpochMs, 1_800_000_000_000n)

  const encoded = create(WebPushHttpCredentialIssuedSchema, {
    bearerTokenAscii: new TextEncoder().encode('a'.repeat(43)),
    csrfTokenAscii: new TextEncoder().encode('b'.repeat(43)),
    expiresAtEpochMs: 1_800_000_000_000n
  })
  assert.equal(
    Buffer.from(toBinary(WebPushHttpCredentialIssuedSchema, encoded)).toString('hex'),
    WEB_PUSH_HTTP_CREDENTIAL_GOLDEN_HEX
  )
})

test('keeps the bounded SetMessageReaction payload compatible across bindings', () => {
  const decoded = fromBinary(
    SetMessageReactionSchema,
    bytesFromHex(SET_MESSAGE_REACTION_GOLDEN_HEX)
  )
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.messageId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.reaction, MessageReactionKind.LIKE)
  assert.equal(decoded.active, true)
  assert.equal(decoded.clientOperationId, 'reaction-1')

  const encoded = create(SetMessageReactionSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    messageId: '00000000-0000-0000-0000-000000000002',
    reaction: MessageReactionKind.LIKE,
    active: true,
    clientOperationId: 'reaction-1'
  })
  assert.equal(
    Buffer.from(toBinary(SetMessageReactionSchema, encoded)).toString('hex'),
    SET_MESSAGE_REACTION_GOLDEN_HEX
  )
})

test('keeps the bounded SetMessagePin payload compatible across bindings', () => {
  const decoded = fromBinary(SetMessagePinSchema, bytesFromHex(SET_MESSAGE_PIN_GOLDEN_HEX))
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.messageId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.pinned, true)
  assert.equal(decoded.clientOperationId, 'pin-1')

  const encoded = create(SetMessagePinSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    messageId: '00000000-0000-0000-0000-000000000002',
    pinned: true,
    clientOperationId: 'pin-1'
  })
  assert.equal(
    Buffer.from(toBinary(SetMessagePinSchema, encoded)).toString('hex'),
    SET_MESSAGE_PIN_GOLDEN_HEX
  )
})

test('keeps the revision-safe EditMessage payload compatible across bindings', () => {
  const decoded = fromBinary(EditMessageSchema, bytesFromHex(EDIT_MESSAGE_GOLDEN_HEX))
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.messageId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.expectedRevision, 3)
  assert.equal(decoded.contentType, 1)
  assert.equal(new TextDecoder().decode(decoded.content), 'hi')
  assert.equal(decoded.clientOperationId, 'edit-1')
  const encoded = create(EditMessageSchema, {
    conversationId: decoded.conversationId,
    messageId: decoded.messageId,
    expectedRevision: 3,
    contentType: 1,
    content: new TextEncoder().encode('hi'),
    clientOperationId: 'edit-1'
  })
  assert.equal(Buffer.from(toBinary(EditMessageSchema, encoded)).toString('hex'),
    EDIT_MESSAGE_GOLDEN_HEX)
})

test('keeps the composite conversation cursor compatible across bindings', () => {
  const decoded = fromBinary(
    ListConversationsSchema,
    bytesFromHex(LIST_CONVERSATIONS_GOLDEN_HEX)
  )
  assert.equal(decoded.afterUpdatedAtEpochMs, 1_700_000_000_000n)
  assert.equal(decoded.afterConversationId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.limit, 25)

  const encoded = create(ListConversationsSchema, {
    afterUpdatedAtEpochMs: 1_700_000_000_000n,
    afterConversationId: '00000000-0000-0000-0000-000000000002',
    limit: 25
  })
  assert.equal(
    Buffer.from(toBinary(ListConversationsSchema, encoded)).toString('hex'),
    LIST_CONVERSATIONS_GOLDEN_HEX
  )
})

test('keeps the participant account cursor compatible across bindings', () => {
  const decoded = fromBinary(
    ListConversationParticipantsSchema,
    bytesFromHex(LIST_PARTICIPANTS_GOLDEN_HEX)
  )
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.afterAccountId, '00000000-0000-0000-0000-000000000002')
  assert.equal(decoded.limit, 25)
  assert.equal(Buffer.from(toBinary(ListConversationParticipantsSchema, decoded)).toString('hex'),
    LIST_PARTICIPANTS_GOLDEN_HEX)
})

test('keeps bounded attachment registration compatible across bindings', () => {
  const decoded = fromBinary(
    RegisterAttachmentSchema,
    bytesFromHex(REGISTER_ATTACHMENT_GOLDEN_HEX)
  )
  assert.equal(decoded.conversationId, '00000000-0000-0000-0000-000000000001')
  assert.equal(decoded.clientAttachmentId, 'attach-1')
  assert.equal(decoded.fileName, 'a.txt')
  assert.equal(decoded.mediaType, 'text/plain')
  assert.equal(decoded.byteSize, 2n)
  assert.equal(decoded.contentSha256.byteLength, 32)

  const encoded = create(RegisterAttachmentSchema, {
    conversationId: '00000000-0000-0000-0000-000000000001',
    clientAttachmentId: 'attach-1',
    fileName: 'a.txt',
    mediaType: 'text/plain',
    byteSize: 2n,
    contentSha256: Uint8Array.from({ length: 32 }, () => 1)
  })
  assert.equal(
    Buffer.from(toBinary(RegisterAttachmentSchema, encoded)).toString('hex'),
    REGISTER_ATTACHMENT_GOLDEN_HEX
  )
})

test('keeps canonical device revocation compatible across bindings', () => {
  const decoded = fromBinary(RevokeDeviceSchema, bytesFromHex(REVOKE_DEVICE_GOLDEN_HEX))
  assert.equal(decoded.targetDeviceId, '00000000-0000-0000-0000-000000000001')

  const encoded = create(RevokeDeviceSchema, {
    targetDeviceId: '00000000-0000-0000-0000-000000000001'
  })
  assert.equal(
    Buffer.from(toBinary(RevokeDeviceSchema, encoded)).toString('hex'),
    REVOKE_DEVICE_GOLDEN_HEX
  )
})
