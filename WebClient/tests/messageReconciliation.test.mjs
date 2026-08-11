import test from 'node:test'
import assert from 'node:assert/strict'

import {
  hasStableIdentity,
  mergeUniqueMessages,
  sameStableMessage
} from '../src/messaging/messageReconciliation.js'

test('matches committed messages by server id or client message id', () => {
  assert.equal(sameStableMessage({ id: 7 }, { id: 7 }), true)
  assert.equal(
    sameStableMessage(
      { clientMessageId: 'client-1' },
      { id: 8, clientMessageId: 'client-1' }
    ),
    true
  )
  assert.equal(sameStableMessage({ id: 7 }, { id: 8 }), false)
})

test('deduplicates history against live state and within the incoming page', () => {
  const live = [{ id: 2, clientMessageId: 'client-2' }]
  const history = [
    { id: 1, clientMessageId: 'client-1' },
    { id: 2, clientMessageId: 'client-2' },
    { id: 1, clientMessageId: 'client-1' }
  ]

  assert.deepEqual(mergeUniqueMessages(live, history, { prepend: true }), [
    { id: 1, clientMessageId: 'client-1' },
    { id: 2, clientMessageId: 'client-2' }
  ])
})

test('preserves legacy messages that have no stable identity', () => {
  const first = { content: 'legacy one' }
  const second = { content: 'legacy two' }
  assert.equal(hasStableIdentity(first), false)
  assert.deepEqual(mergeUniqueMessages([], [first, second]), [first, second])
})
