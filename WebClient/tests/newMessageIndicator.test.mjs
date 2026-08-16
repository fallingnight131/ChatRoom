import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MAX_PENDING_NEW_MESSAGES,
  addPendingNewMessages,
  pendingNewMessageLabel
} from '../src/messaging/newMessageIndicator.js'

test('accumulates only positive appended messages and caps the badge', () => {
  assert.equal(addPendingNewMessages(2, 3), 5)
  assert.equal(addPendingNewMessages(98, 5), MAX_PENDING_NEW_MESSAGES)
  assert.equal(addPendingNewMessages(4, -1), 4)
  assert.equal(addPendingNewMessages(Number.NaN, 2), 2)
})

test('labels bounded pending counts without exposing an empty indicator', () => {
  assert.equal(pendingNewMessageLabel(0), '')
  assert.equal(pendingNewMessageLabel(3), '3 条新消息')
  assert.equal(pendingNewMessageLabel(99), '99+ 条新消息')
})
