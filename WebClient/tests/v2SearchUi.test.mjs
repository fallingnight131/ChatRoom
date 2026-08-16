import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('keeps the accessible conversation search surface behind application capability state', () => {
  for (const marker of [
    'v-if="snapshot.searchEnabled"',
    'aria-controls="v2-message-search"',
    'aria-labelledby="v2-message-search-title"',
    'type="search"',
    'aria-live="polite"',
    'aria-label="消息搜索结果"',
    'application.searchMessages',
    'application.loadMoreSearchResults',
    'application.revealSearchHit',
  ]) assert.ok(source.includes(marker), `missing Web search UI marker: ${marker}`)
})

test('makes revealed search hits keyboard-focusable without advancing sync state in the view', () => {
  assert.match(source, /:id="message\.id \? `v2-message-\$\{message\.id\}` : undefined" tabindex="-1"/)
  assert.match(source, /scrollIntoView\(\{ block: 'center', behavior: 'smooth' \}\)/)
  assert.ok(!source.includes('cursorSequence = hit.sequence'))
})
