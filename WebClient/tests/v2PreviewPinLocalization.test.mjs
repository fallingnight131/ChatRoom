import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 optimistic pin controls and retry feedback from the active locale', () => {
  for (const marker of [
    'v2PreviewPinMessages(userStore.locale)',
    'pinMessages.actionLabel(message.pinned ? pinMessages.unpin : pinMessages.pin, message.sequence)',
    'message.pinned ? pinMessages.unpin : pinMessages.pin',
    'pinMessages.retryLabel(message.sequence)', 'pinMessages.retry',
    'pinMessages.value.unavailable', 'pinMessages.value.retryUnavailable',
  ]) assert.ok(source.includes(marker), `missing V2 pin locale marker: ${marker}`)
})

test('preserves message identity, optimistic pending state, stable retry identity, and application calls', () => {
  for (const marker of [
    'command.messageId === message.id', "command.deliveryState === 'sending'",
    ':disabled="pinPending(message)"', 'application.setPin(message.id)',
    'failedPin(message).clientOperationId', 'application.retryPin(operationId)',
  ]) assert.ok(source.includes(marker), `missing V2 pin boundary marker: ${marker}`)
})
