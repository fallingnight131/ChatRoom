import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const v1 = fs.readFileSync(new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')
const v2 = fs.readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('guards V1 copy availability and announces its browser result', () => {
  for (const marker of [
    'v-if="canCopyText(contextMenu.msg)"',
    '!msg.recalled',
    "msg.contentType !== 'system'",
    'await copyMessageText(msg.content)',
    'messages.value.copyFailed'
  ]) assert.ok(v1.includes(marker), `missing V1 copy UI marker: ${marker}`)
})

test('exposes V2 copy only for accepted available messages with live feedback', () => {
  for (const marker of [
    ':aria-label="`复制消息 ${message.sequence} 正文`"',
    "message.deliveryState === 'accepted' && message.availability === 'available'",
    '@click="copyMessage(message)"',
    'await copyMessageText(message.content)',
    'aria-live="polite" aria-atomic="true"'
  ]) assert.ok(v2.includes(marker), `missing V2 copy UI marker: ${marker}`)
})
