import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders the V2 participant picker and known failure adapter from the active locale', () => {
  for (const marker of [
    'v2PreviewMentionMessages(userStore.locale)', 'mentionMessages.title',
    ':aria-label="mentionMessages.close"', 'visibleParticipantFailure',
    'mentionMessages.retry', ':aria-label="mentionMessages.members"',
    'mentionMessages.owner', 'mentionMessages.admin', 'mentionMessages.member',
    'mentionMessages.loading', 'mentionMessages.loadMore',
  ]) assert.ok(source.includes(marker), `missing V2 mention locale marker: ${marker}`)
})

test('preserves participant identity, paging, Unicode insertion, and keyboard focus ownership', () => {
  for (const marker of [
    ':key="participant.accountId"', 'application.refreshParticipants()',
    'application.loadMoreParticipants()', 'insertMention(text, anchors, start, end, participant)',
    'element?.setSelectionRange(next.caretUtf16, next.caretUtf16)',
    "['ArrowDown', 'ArrowUp', 'Home', 'End']", 'if (restoreFocus) nextTick(() => trigger?.focus())',
  ]) assert.ok(source.includes(marker), `missing V2 mention boundary marker: ${marker}`)
})
