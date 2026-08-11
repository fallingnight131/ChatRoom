import test from 'node:test'
import assert from 'node:assert/strict'

import {
  applyDeletionEvents,
  hasStableIdentity,
  mergeUniqueMessages,
  reconcileRoomSyncPage,
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

test('applies selected and predicate deletion events idempotently', () => {
  const messages = [
    { id: 1, timestamp: 100 },
    { id: 2, timestamp: 200 },
    { id: 3, timestamp: 300 }
  ]
  const selected = { eventType: 'messagesDeleted', mode: 'selected', messageIds: [2], syncSequence: 4 }
  assert.deepEqual(applyDeletionEvents(messages, [selected, selected]), [
    { id: 1, timestamp: 100 },
    { id: 3, timestamp: 300 }
  ])
  assert.deepEqual(applyDeletionEvents(messages, [{ mode: 'before', timestamp: 200 }]), [
    { id: 2, timestamp: 200 },
    { id: 3, timestamp: 300 }
  ])
  assert.deepEqual(applyDeletionEvents(messages, [{ mode: 'after', timestamp: 200 }]), [
    { id: 1, timestamp: 100 },
    { id: 2, timestamp: 200 }
  ])
  assert.deepEqual(applyDeletionEvents(messages, [{ mode: 'all' }]), [])
})

test('reconciles mixed sync pages in cursor order', () => {
  const existing = [{ id: 1, sequence: 1, timestamp: 100 }]
  const messages = [{ id: 3, sequence: 3, syncSequence: 3, timestamp: 300 }]
  const events = [{ mode: 'all', sequence: 2, syncSequence: 2 }]
  assert.deepEqual(reconcileRoomSyncPage(existing, messages, events), messages)
})

test('deduplicates history against live state and within the incoming page', () => {
  const live = [{ id: 2, clientMessageId: 'client-2', recalled: false }]
  const history = [
    { id: 1, clientMessageId: 'client-1' },
    { id: 2, clientMessageId: 'client-2', recalled: true },
    { id: 1, clientMessageId: 'client-1' }
  ]

  assert.deepEqual(mergeUniqueMessages(live, history, { prepend: true }), [
    { id: 1, clientMessageId: 'client-1' },
    { id: 2, clientMessageId: 'client-2', recalled: true }
  ])
})

test('reconciles authoritative server fields without dropping local-only state', () => {
  const live = [{ id: 7, content: 'hello', recalled: false, downloadState: 'cached' }]
  const replay = [{ id: 7, content: '此消息已被撤回', recalled: true, mutationSequence: 3 }]

  assert.deepEqual(mergeUniqueMessages(live, replay), [{
    id: 7,
    content: '此消息已被撤回',
    recalled: true,
    downloadState: 'cached',
    mutationSequence: 3
  }])
})

test('preserves legacy messages that have no stable identity', () => {
  const first = { content: 'legacy one' }
  const second = { content: 'legacy two' }
  assert.equal(hasStableIdentity(first), false)
  assert.deepEqual(mergeUniqueMessages([], [first, second]), [first, second])
})
