import test from 'node:test'
import assert from 'node:assert/strict'

import {
  applySendAcknowledgement,
  makeOptimisticMessage,
  pendingMessagesFor
} from '../src/messaging/outbox.js'

test('creates a stable optimistic message and applies durable acceptance', () => {
  const message = makeOptimisticMessage(
    { roomId: 7, sender: 'alice', content: 'hello', contentType: 'text' },
    'client-7', 100)
  assert.equal(message.deliveryState, 'sending')
  applySendAcknowledgement([message], {
    success: true, clientMessageId: 'client-7', id: 9, sequence: 4, timestamp: 200
  })
  assert.deepEqual(message, {
    roomId: 7, sender: 'alice', content: 'hello', contentType: 'text',
    id: 9, clientMessageId: 'client-7', timestamp: 200,
    sequence: 4, deliveryState: 'accepted', errorCode: ''
  })
})

test('marks rejected messages retryable and selects only unresolved own sends', () => {
  const mine = makeOptimisticMessage({ sender: 'alice' }, 'mine', 100)
  const other = makeOptimisticMessage({ sender: 'bob' }, 'other', 100)
  applySendAcknowledgement([mine], {
    success: false, clientMessageId: 'mine', errorCode: 'PERSISTENCE_FAILED'
  })
  assert.equal(mine.deliveryState, 'failed')
  assert.equal(mine.errorCode, 'PERSISTENCE_FAILED')
  assert.deepEqual(pendingMessagesFor([mine, other], 'alice'), [])
  mine.deliveryState = 'sending'
  assert.deepEqual(pendingMessagesFor([mine, other], 'alice'), [mine])
})
