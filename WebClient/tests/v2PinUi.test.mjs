import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('exposes keyboard-native optimistic pin controls and retry feedback', async () => {
  const source = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
  assert.match(source, /:aria-pressed="message\.pinned"/)
  assert.match(source, /:disabled="pinPending\(message\)"/)
  assert.match(source, /application\.setPin\(message\.id\)/)
  assert.match(source, /application\.retryPin\(operationId\)/)
  assert.match(source, /role="status">{{ v2TimelineMessages\.pinned }}/)
})
