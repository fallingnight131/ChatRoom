import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(
  new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
const runtime = await readFile(
  new URL('../src/application/v2Runtime.ts', import.meta.url), 'utf8')

test('exposes author-only accessible message editing and explicit conflict recovery', () => {
  assert.match(view, /message\.senderAccountId === snapshot\.value\.session\?\.accountId/)
  assert.match(view, /<label :for="`edit-\$\{message\.id\}`">{{ editMessages\.formLabel }}<\/label>/)
  assert.match(view, /@keydown\.esc="cancelEditFromKeyboard"/)
  assert.match(view, /:title="editMessages\.cancelTitle"/)
  assert.match(view, /if \(!editingMessageId\.value\) return/)
  assert.match(view, /role="status">{{ editMessages\.saving }}/)
  assert.match(view, /role="alert">{{ editMessages\.conflict }}/)
  assert.match(view, /{{ editMessages\.rebase }}/)
  assert.match(view, /{{ editMessages\.discard }}<\/button>/)
  assert.match(view, /message\.contentRevision > 0/)
  assert.match(view, /application\.editMessage\(message\.id, text, mentions\)/)
  assert.match(view, /application\.rebaseEdit\(operationId\)/)
})

test('activates MESSAGE_EDITS only in the completed Web V2 runtime composition', () => {
  assert.match(runtime, /enableMessageEdits: true/)
})
