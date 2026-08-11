import assert from 'node:assert/strict'
import test from 'node:test'

import { create, fromBinary, toBinary } from '@bufbuild/protobuf'
import {
  EnvelopeSchema,
  MessageKind
} from '../generated/typescript/chat/v2/envelope_pb.js'

const GOLDEN_HEX = '08021001186422057265712d312a0973657373696f6e2d31' +
  '3208636c69656e742d313880d095ffbc314203616263'

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
