import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const view = fs.readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders account block management from the active locale catalog', () => {
  for (const fragment of [
    'accountBlockMessages.manage', 'accountBlockMessages.title',
    'accountBlockMessages.description', 'accountBlockMessages.confirmBlock',
    'accountBlockMessages.confirmUnblock', 'accountBlockMessages.sessionEvidence',
    'accountBlockMessages.retry',
  ]) assert.ok(view.includes(fragment), `missing ${fragment}`)
})
