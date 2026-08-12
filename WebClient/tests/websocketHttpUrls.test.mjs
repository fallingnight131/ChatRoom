import assert from 'node:assert/strict'
import test from 'node:test'

globalThis.location = {
  protocol: 'https:',
  origin: 'https://chat.example'
}

const {
  getHttpDownloadUrl,
  getHttpUploadUrl,
  setHttpConfig
} = await import('../src/services/websocket.js')

test.beforeEach(() => setHttpConfig('legacy-backend.example', 9529, 'secret token'))

test('uses the page origin for V1 upload and download URLs', () => {
  assert.equal(
    getHttpUploadUrl('/api/upload/42?part=1'),
    'https://chat.example/api/upload/42?part=1&token=secret+token'
  )
  assert.equal(
    getHttpDownloadUrl(42, false, 'inline'),
    'https://chat.example/api/download/42?token=secret+token&friend=0&disposition=inline'
  )
})

test('rejects upload paths that escape the same-origin API boundary', () => {
  for (const path of ['//evil.example/api/upload/42', 'https://evil.example/api/upload/42', '/other/42']) {
    assert.equal(getHttpUploadUrl(path), '', path)
  }
})
