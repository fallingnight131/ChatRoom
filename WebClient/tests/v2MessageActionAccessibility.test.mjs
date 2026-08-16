import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('binds repeated V2 message actions to an explicit message context', () => {
  for (const marker of [
    ':aria-label="`重试消息 ${message.sequence} 的回应`"',
    ":aria-label=\"`${message.pinned ? '取消置顶' : '置顶'}消息 ${message.sequence}`\"",
    ':aria-label="`重试消息 ${message.sequence} 的置顶操作`"',
    ':aria-label="v2TimelineMessages.messageStatus(message.sequence, deliveryLabel(message.deliveryState))"',
    ':aria-label="basicActionMessages.copyLabel(message.sequence)"',
    ':aria-label="basicActionMessages.replyLabel(message.sequence)"',
    ':aria-label="`编辑消息 ${message.sequence}`"',
    ':aria-label="forwardMessages.forwardLabel(message.sequence)"',
    ':aria-label="basicActionMessages.retryLabel"',
  ]) assert.ok(source.includes(marker), `missing V2 message-action marker: ${marker}`)
})
