import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const storeSource = await readFile(new URL('../src/stores/chat.js', import.meta.url), 'utf8')

test('routes Web room and friend files through raw HTTP upload sessions', () => {
  assert.match(storeSource, /async uploadSmallFile\(roomId, file\) \{\s*return this\.startChunkedUpload/)
  assert.match(storeSource, /async uploadFriendSmallFile\(friendUsername, file\) \{\s*return this\.startFriendChunkedUpload/)
  assert.match(storeSource, /method: 'PUT'/)
  assert.match(storeSource, /body: u\.file/)
  assert.match(storeSource, /chatWs\.endUpload\(uploadId/)
})
