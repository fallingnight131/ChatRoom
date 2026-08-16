import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('follows only the V2 live tail and exposes bounded pending-message feedback', () => {
  for (const marker of [
    'classifyV2TailUpdate(previous, next)',
    '@scroll="onMessageListScroll"',
    'v-if="pendingNewMessages"',
    ':aria-label="`${pendingNewMessagesLabel}${timelineMessages.backToLatestSuffix}`"',
    'messageListRef.value?.focus({ preventScroll: true })',
  ]) assert.ok(source.includes(marker), `missing V2 tail UI marker: ${marker}`)
})
