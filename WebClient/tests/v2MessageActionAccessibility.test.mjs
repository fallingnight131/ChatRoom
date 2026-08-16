import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('binds repeated V2 message actions to an explicit message context', () => {
  for (const marker of [
    ':aria-label="reactionMessages.retryLabel(message.sequence)"',
    ':aria-label="pinMessages.actionLabel(message.pinned ? pinMessages.unpin : pinMessages.pin, message.sequence)"',
    ':aria-label="pinMessages.retryLabel(message.sequence)"',
    ':aria-label="v2TimelineMessages.messageStatus(message.sequence, deliveryLabel(message.deliveryState))"',
    ':aria-label="basicActionMessages.copyLabel(message.sequence)"',
    ':aria-label="basicActionMessages.replyLabel(message.sequence)"',
    ':aria-label="`编辑消息 ${message.sequence}`"',
    ':aria-label="forwardMessages.forwardLabel(message.sequence)"',
    ':aria-label="basicActionMessages.retryLabel"',
  ]) assert.ok(source.includes(marker), `missing V2 message-action marker: ${marker}`)
})
