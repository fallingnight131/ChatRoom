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

const GOLDEN_HEX = '08021001186422057265712d312a0973657373696f6e2d31' +
  '3208636c69656e742d313880d095ffbc314203616263'
const CLIENT_HELLO_GOLDEN_HEX = '0802100218012205302e312e302a086465766963652d31'
const AUTHENTICATE_GOLDEN_HEX = '0a05616c696365120d746573742d70617373776f7264'

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
