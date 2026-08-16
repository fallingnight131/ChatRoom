import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')

test('renders timeline landmarks and delivery states from the live locale catalog', () => {
  for (const marker of [
    'messageTimelineMessages(userStore.locale)',
    ':aria-label="messages.timeline"',
    '{{ messages.loading }}',
    '{{ messages.sending }}',
    ':aria-label="messages.failedRetryLabel"',
    '{{ messages.read }}',
    '{{ messages.sent }}',
  ]) assert.ok(source.includes(marker), `missing timeline locale marker: ${marker}`)
})

test('builds message summaries and pending-history feedback from catalog punctuation', () => {
  for (const marker of [
    'messages.value.systemPrefix',
    'messages.value.recalledSuffix',
    'messages.value.contentSeparator',
    'messages.value.separator',
    'messages.value.newMessagesSuffix',
    'messages.readingHistorySuffix',
    'messages.value.copySucceeded',
  ]) assert.ok(source.includes(marker), `missing timeline summary marker: ${marker}`)
})
