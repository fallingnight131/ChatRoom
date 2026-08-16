import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 timeline landmarks and immutable message state from the active locale', () => {
  for (const marker of [
    'v2PreviewTimelineMessages(userStore.locale)', ':aria-label="v2TimelineMessages.history"',
    'v2TimelineMessages.pinned', 'v2TimelineMessages.forwarded',
    'v2TimelineMessages.replyLabel(replyPreview(message))',
    'v2TimelineMessages.accountTitle(segment.targetAccountId)', 'v2TimelineMessages.edited',
    'v2TimelineMessages.messageStatus(message.sequence, deliveryLabel(message.deliveryState))',
    'v2TimelineMessages.value.accepted', 'v2TimelineMessages.value.sending',
  ]) assert.ok(source.includes(marker), `missing V2 timeline locale marker: ${marker}`)
})

test('localizes tail feedback and timestamps without changing live-tail ownership', () => {
  for (const marker of [
    'timelineMessages.value.newMessagesSuffix', 'timelineMessages.backToLatestSuffix',
    'timelineMessages.readingHistorySuffix', "new Intl.DateTimeFormat(userStore.locale",
    'classifyV2TailUpdate(previous, next)', 'addPendingNewMessages(',
    'messageListRef.value?.focus({ preventScroll: true })',
  ]) assert.ok(source.includes(marker), `missing V2 timeline boundary marker: ${marker}`)
})
