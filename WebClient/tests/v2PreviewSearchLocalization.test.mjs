import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders the gated V2 search surface from the active locale', () => {
  for (const marker of [
    'v2PreviewSearchMessages(userStore.locale)', 'searchMessages.openSearch',
    'searchMessages.closeSearch', 'searchMessages.searchConversation',
    ':placeholder="searchMessages.exactText"', 'searchMessages.searching',
    'searchMessages.loadingContext', 'searchMessages.resultCount(snapshot.searchResults.length)',
    ':aria-label="searchMessages.resultsLabel"', 'searchMessages.loadMore',
    'visibleSearchFailure',
  ]) assert.ok(source.includes(marker), `missing V2 search locale marker: ${marker}`)
})

test('keeps search capability, bounds, application calls, and focus restoration unchanged', () => {
  for (const marker of [
    'v-if="snapshot.searchEnabled"', 'maxlength="128"',
    'application.searchMessages(searchDraft.value)', 'application.loadMoreSearchResults()',
    'application.revealSearchHit(hit.id)', "scrollIntoView({ block: 'center', behavior: 'smooth' })",
    'focus({ preventScroll: true })',
  ]) assert.ok(source.includes(marker), `missing V2 search boundary marker: ${marker}`)
})
