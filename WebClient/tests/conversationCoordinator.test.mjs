import assert from 'node:assert/strict'
import test from 'node:test'

import { ConversationCoordinator } from '../src/messaging/conversationCoordinator.js'

function fixture() {
  const calls = []
  const cache = {
    load: async (...args) => ({ args, messages: [{ id: 1 }], cursor: 4 }),
    save: async (...args) => { calls.push(['save', ...args]); return true },
    remove: async (...args) => { calls.push(['remove', ...args]); return true },
    prune: async (...args) => { calls.push(['prune', ...args]); return true }
  }
  let nextId = 0
  const transport = {
    requestHistory: (...args) => calls.push(['roomHistory', ...args]),
    requestFriendHistory: (...args) => calls.push(['directHistory', ...args]),
    sendChat: (...args) => { calls.push(['roomSend', ...args]); return `room-${++nextId}` },
    sendFriendChat: (...args) => { calls.push(['directSend', ...args]); return `direct-${++nextId}` }
  }
  return { coordinator: new ConversationCoordinator({ cache, transport }), calls }
}

test('hydrates and persists room/direct targets through compatible cache kinds', async () => {
  const { coordinator, calls } = fixture()
  const room = ConversationCoordinator.room(7)
  const direct = ConversationCoordinator.direct('bob')
  const loaded = await coordinator.hydrate('alice', direct)
  assert.deepEqual(loaded.args, ['alice', 'friend', 'bob'])
  await coordinator.persist('alice', room, [{ id: 1 }], 9)
  await coordinator.remove('alice', direct)
  await coordinator.prune('alice', 'direct', ['bob'])
  assert.deepEqual(calls[0], ['save', 'alice', 'room', 7, [{ id: 1 }], 9])
  assert.deepEqual(calls[1], ['remove', 'alice', 'friend', 'bob'])
  assert.deepEqual(calls[2], ['prune', 'alice', 'friend', ['bob']])
})

test('requests snapshots or incremental pages from the correct transport', () => {
  const { coordinator, calls } = fixture()
  coordinator.requestSync(ConversationCoordinator.room(7), [], 0)
  coordinator.requestSync(ConversationCoordinator.direct('bob'), [{ id: 1 }], 12)
  assert.deepEqual(calls[0], ['roomHistory', 7, 50, 0, undefined])
  assert.deepEqual(calls[1], ['directHistory', 'bob', 100, 0, 12])
})

test('stages, recovers, retries, and acknowledges stable optimistic commands', () => {
  const { coordinator, calls } = fixture()
  const room = ConversationCoordinator.room(7)
  const message = coordinator.stage(room, 'alice', 'Alice', 'hello', 'text')
  assert.equal(message.clientMessageId, 'room-1')
  assert.equal(message.deliveryState, 'sending')
  assert.equal(coordinator.recoverPending(room, [message], 'alice'), 1)
  message.deliveryState = 'failed'
  message.errorCode = 'SEND_REJECTED'
  assert.equal(coordinator.retry(room, message, 'alice'), true)
  assert.equal(calls.filter(call => call[0] === 'roomSend').length, 3)
  const accepted = coordinator.acknowledge([message], {
    success: true,
    clientMessageId: message.clientMessageId,
    id: 91,
    sequence: 13,
    timestamp: 1700000000000
  })
  assert.equal(accepted.id, 91)
  assert.equal(accepted.deliveryState, 'accepted')
})

test('cursor advancement is monotonic across messages and mutations', () => {
  const { coordinator } = fixture()
  assert.equal(coordinator.advanceCursor(8,
    { sequence: 4 }, { mutationSequence: 11 }, { syncSequence: 10 }), 11)
  assert.equal(coordinator.advanceCursor(12, { sequence: 2 }), 12)
})
