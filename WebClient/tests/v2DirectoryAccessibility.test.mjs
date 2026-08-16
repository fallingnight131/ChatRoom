import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('exposes the V2 directory as a named navigation list', () => {
  for (const marker of [
    '<nav class="conversation-panel" :aria-label="shellMessages.conversationNavigation">',
    '<ul class="conversation-list" :aria-label="shellMessages.availableConversations">',
    '<li v-for="conversation in snapshot.directory"',
    'type="button"',
    ":aria-current=\"conversation.conversationId === snapshot.activeConversationId ? 'page' : undefined\"",
    '</nav>',
  ]) assert.ok(source.includes(marker), `missing V2 directory marker: ${marker}`)
})

test('does not rely on the connection status dot for meaning', () => {
  assert.match(source, /role="status" aria-live="polite"/)
  assert.match(source, /:class="\['status-dot', connectionTone\]" aria-hidden="true"/)
  assert.match(source, /\{\{ connectionLabel \}\}/)
})
