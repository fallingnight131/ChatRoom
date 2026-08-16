import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders the capability-gated V2 forwarding action and dialog from the active locale', () => {
  for (const marker of [
    'v2PreviewForwardMessages(userStore.locale)', 'forwardMessages.forwardLabel(message.sequence)',
    'forwardMessages.forward', 'forwardMessages.title', 'forwardMessages.description',
    ':aria-label="forwardMessages.close"', ':aria-label="forwardMessages.targets"',
    'forwardMessages.direct', 'forwardMessages.group', 'forwardMessages.forwarding',
    'forwardMessages.value.cacheUnavailable', 'forwardMessages.value.retryInTarget',
  ]) assert.ok(source.includes(marker), `missing V2 forwarding locale marker: ${marker}`)
})

test('preserves capability, identity, durability, pending, and focus boundaries', () => {
  for (const marker of [
    'v-if="snapshot.forwardingEnabled', 'forwardSource.value.id, conversation.conversationId',
    "result.errorCode === 'CACHE_UNAVAILABLE'", 'if (!forwardSource.value || forwardPending.value) return',
    'if (forwardPending.value) return', 'canClose: () => !forwardPending.value',
    "initialFocusSelector: '#forward-dialog-close'",
  ]) assert.ok(source.includes(marker), `missing V2 forwarding boundary marker: ${marker}`)
})
