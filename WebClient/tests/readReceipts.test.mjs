import assert from 'node:assert/strict'
import test from 'node:test'

import {
  advanceReadWatermark,
  applyPeerReadWatermark
} from '../src/messaging/readReceipts.js'

test('advances private read watermarks monotonically', () => {
  assert.equal(advanceReadWatermark(12, 9), 12)
  assert.equal(advanceReadWatermark(12, 18), 18)
  assert.equal(advanceReadWatermark(-1, 'bad'), 0)
})

test('marks only authoritative own messages through the peer watermark', () => {
  const messages = [
    { id: 10, sender: 'alice', deliveryState: 'accepted' },
    { id: 11, sender: 'bob', deliveryState: 'accepted' },
    { id: 12, sender: 'alice', deliveryState: 'accepted' },
    { id: 0, sender: 'alice', deliveryState: 'sending' }
  ]
  assert.equal(applyPeerReadWatermark(messages, 11, 'alice'), true)
  assert.deepEqual(messages.map(message => message.deliveryState),
    ['read', 'accepted', 'accepted', 'sending'])
  assert.equal(applyPeerReadWatermark(messages, 11, 'alice'), false)
})
