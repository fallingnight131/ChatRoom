import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 copy, reply entry, and failed-send retry from the active locale', () => {
  for (const marker of [
    'v2PreviewBasicActionMessages(userStore.locale)',
    'basicActionMessages.copyLabel(message.sequence)', 'basicActionMessages.copy',
    'basicActionMessages.replyLabel(message.sequence)', 'basicActionMessages.reply',
    ':aria-label="basicActionMessages.retryLabel"', 'basicActionMessages.retry',
    'basicActionMessages.value.copied(message.sequence)', 'basicActionMessages.value.copyFailed',
  ]) assert.ok(source.includes(marker), `missing V2 basic-action locale marker: ${marker}`)
})

test('preserves availability guards, clipboard helper, reply focus, and stable retry identity', () => {
  for (const marker of [
    "message.deliveryState === 'accepted' && message.availability === 'available'",
    'await copyMessageText(message.content)', "document.getElementById('v2-message')?.focus()",
    'application.retryMessage(clientMessageId)',
  ]) assert.ok(source.includes(marker), `missing V2 basic-action boundary marker: ${marker}`)
})
