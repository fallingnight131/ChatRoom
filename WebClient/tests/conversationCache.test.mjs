import test from 'node:test'
import assert from 'node:assert/strict'

import {
  IndexedDbConversationCache,
  MAX_CACHED_MESSAGES,
  MAX_DRAFT_LENGTH,
  MAX_V2_PENDING_MESSAGES,
  conversationCacheKey,
  makeConversationRecord,
  normalizeV2Sequence,
  sanitizeCachedMessage,
  sanitizeConversationRecord,
  sanitizeV2ConversationRecord
} from '../src/persistence/conversationCache.js'

function requestWith(executor) {
  const request = {}
  queueMicrotask(() => {
    try {
      request.result = executor()
      request.onsuccess?.()
    } catch (error) {
      request.error = error
      request.onerror?.()
    }
  })
  return request
}

function fakeIndexedDb() {
  const records = new Map()
  let database
  const objectStore = {
    get: key => requestWith(() => records.get(key)),
    getAll: () => requestWith(() => [...records.values()].map(value => structuredClone(value))),
    put: record => requestWith(() => records.set(record.key, structuredClone(record))),
    delete: key => requestWith(() => records.delete(key))
  }
  database = {
    objectStoreNames: { contains: () => true },
    createObjectStore: () => objectStore,
    transaction: (_name, mode) => {
      const transaction = { objectStore: () => objectStore }
      if (mode === 'readwrite') setTimeout(() => transaction.oncomplete?.(), 0)
      return transaction
    }
  }
  return {
    open: () => {
      const request = {}
      queueMicrotask(() => {
        request.result = database
        request.onsuccess?.()
      })
      return request
    }
  }
}

test('partitions conversation records by account, kind, and id', () => {
  assert.notEqual(
    conversationCacheKey('alice', 'room', 7),
    conversationCacheKey('bob', 'room', 7)
  )
  assert.notEqual(
    conversationCacheKey('alice', 'room', 7),
    conversationCacheKey('alice', 'friend', 7)
  )
})

test('bounds cached history and normalizes the cursor', () => {
  const messages = Array.from({ length: MAX_CACHED_MESSAGES + 20 }, (_, id) => ({ id }))
  const record = makeConversationRecord('alice', 'room', 7, messages, -4)
  assert.equal(record.messages.length, MAX_CACHED_MESSAGES)
  assert.equal(record.messages[0].id, 20)
  assert.equal(record.cursor, 0)
})

test('persists attachment metadata without media bytes or temporary authorization', () => {
  const message = sanitizeCachedMessage({
    id: 8,
    contentType: 'image',
    fileId: 12,
    fileName: 'photo.png',
    fileSize: 2048,
    thumbnail: 'base64-thumbnail',
    imageData: 'base64-image',
    fileData: 'base64-file',
    blob: { bytes: 'payload' },
    cosUrl: 'https://temporary.example/file?token=secret',
    token: 'secret'
  })
  assert.deepEqual(message, {
    id: 8,
    contentType: 'image',
    fileId: 12,
    fileName: 'photo.png',
    fileSize: 2048
  })
})

test('sanitizes legacy records and bounds their messages and drafts', () => {
  const record = sanitizeConversationRecord({
    key: conversationCacheKey('alice', 'room', 7),
    account: 'alice',
    kind: 'room',
    conversationId: 7,
    messages: Array.from({ length: MAX_CACHED_MESSAGES + 1 }, (_, id) => ({
      id,
      thumbnail: 'bytes'
    })),
    cursor: 9,
    draft: 'x'.repeat(MAX_DRAFT_LENGTH + 1),
    token: 'must-not-survive'
  })
  assert.equal(record.messages.length, MAX_CACHED_MESSAGES)
  assert.equal(record.messages[0].id, 1)
  assert.equal('thumbnail' in record.messages[0], false)
  assert.equal(record.draft.length, MAX_DRAFT_LENGTH)
  assert.equal('token' in record, false)
})

test('round trips an IndexedDB conversation snapshot', async () => {
  const cache = new IndexedDbConversationCache(fakeIndexedDb())
  assert.equal(await cache.load('alice', 'room', 7), null)
  await cache.save('alice', 'room', 7, [{ id: 1, content: 'hello' }], 9)
  const loaded = await cache.load('alice', 'room', 7)
  assert.deepEqual(loaded.messages, [{ id: 1, content: 'hello' }])
  assert.equal(loaded.cursor, 9)
  await cache.remove('alice', 'room', 7)
  assert.equal(await cache.load('alice', 'room', 7), null)
})

test('degrades to an empty cache when IndexedDB is unavailable', async () => {
  const cache = new IndexedDbConversationCache(null)
  assert.equal(await cache.load('alice', 'room', 7), null)
  assert.equal(await cache.save('alice', 'room', 7, [], 0), false)
})

test('prunes inaccessible conversations without crossing account or kind boundaries', async () => {
  const cache = new IndexedDbConversationCache(fakeIndexedDb())
  await cache.save('alice', 'room', 1, [{ id: 1 }], 1)
  await cache.save('alice', 'room', 2, [{ id: 2 }], 2)
  await cache.save('alice', 'friend', 'bob', [{ id: 3 }], 3)
  await cache.save('carol', 'room', 2, [{ id: 4 }], 4)
  await cache.prune('alice', 'room', [1])
  assert.equal(await cache.load('alice', 'room', 2), null)
  assert.ok(await cache.load('alice', 'friend', 'bob'))
  assert.ok(await cache.load('carol', 'room', 2))
})

test('persists bounded drafts without losing cached messages', async () => {
  const cache = new IndexedDbConversationCache(fakeIndexedDb())
  await cache.save('alice', 'room', 7, [{ id: 1 }], 9)
  await cache.saveDraft('alice', 'room', 7, 'x'.repeat(MAX_DRAFT_LENGTH + 20))
  assert.equal((await cache.loadDraft('alice', 'room', 7)).length, MAX_DRAFT_LENGTH)
  await cache.save('alice', 'room', 7, [{ id: 1 }, { id: 2 }], 10)
  const loaded = await cache.load('alice', 'room', 7)
  assert.equal(loaded.messages.length, 2)
  assert.equal(loaded.draft.length, MAX_DRAFT_LENGTH)
  await cache.saveDraft('alice', 'room', 7, '')
  assert.equal(await cache.loadDraft('alice', 'room', 7), '')
})

test('preserves exact V2 sequences without persisting secrets or media bytes', () => {
  const record = sanitizeV2ConversationRecord({
    accountId: 'account-1',
    conversationId: 'conversation-1',
    cursorSequence: '9007199254740993',
    token: 'secret',
    messages: [
      ...Array.from({ length: MAX_CACHED_MESSAGES }, (_, index) => ({
        id: `old-${index}`, sequence: String(index), content: 'old'
      })),
      {
      conversationId: 'conversation-1',
      id: 'message-1',
      clientMessageId: 'client-1',
      senderAccountId: 'account-1',
      senderDeviceId: 'device-1',
      sequence: 9007199254740995n,
      acceptedAtEpochMs: 1700000000000,
      content: 'hello',
      contentType: 'text',
      deliveryState: 'accepted',
      resumeToken: 'secret',
      bytes: new Uint8Array([1])
      }
    ]
  })
  assert.equal(record.messages.length, MAX_CACHED_MESSAGES)
  assert.equal(record.messages[0].id, 'old-1')
  assert.equal(record.cursorSequence, '9007199254740993')
  assert.equal(record.messages.at(-1).sequence, '9007199254740995')
  assert.equal('token' in record, false)
  assert.equal('resumeToken' in record.messages.at(-1), false)
  assert.equal('bytes' in record.messages.at(-1), false)
  assert.equal(normalizeV2Sequence((1n << 63n)), '0')
  assert.equal(normalizeV2Sequence('-1'), '0')
})

test('round trips V2 snapshots in an isolated exact-sequence store', async () => {
  const cache = new IndexedDbConversationCache(fakeIndexedDb())
  await cache.saveV2('account-1', 'conversation-1', [{
    id: 'message-1', sequence: '9007199254740993', content: 'hello'
  }], '9007199254740993')
  const loaded = await cache.loadV2('account-1', 'conversation-1')
  assert.equal(loaded.cursorSequence, '9007199254740993')
  assert.equal(loaded.messages[0].sequence, '9007199254740993')
  await cache.removeV2('account-1', 'conversation-1')
  assert.equal(await cache.loadV2('account-1', 'conversation-1'), null)
})

test('creates the isolated V2 database without upgrading the rollback-compatible V1 database', async () => {
  const created = []
  let requestedName = ''
  let requestedVersion = 0
  const database = {
    objectStoreNames: { contains: () => false },
    createObjectStore: (name, options) => created.push([name, options])
  }
  const indexedDb = {
    open: (name, version) => {
      requestedName = name
      requestedVersion = version
      const request = { result: database, transaction: {} }
      queueMicrotask(() => {
        request.onupgradeneeded?.({ oldVersion: 0 })
        request.onsuccess?.()
      })
      return request
    }
  }
  const cache = new IndexedDbConversationCache(indexedDb)
  await cache.openV2()
  assert.equal(requestedName, 'chat-room-client-v2')
  assert.equal(requestedVersion, 1)
  assert.deepEqual(created, [['v2Conversations', { keyPath: 'key' }]])
})

test('bounds V2 accepted history and unresolved outbox independently', () => {
  const record = sanitizeV2ConversationRecord({
    accountId: 'account-1',
    conversationId: 'conversation-1',
    messages: [
      ...Array.from({ length: MAX_CACHED_MESSAGES + 1 }, (_, index) => ({
        id: `accepted-${index}`, sequence: String(index + 1), content: 'accepted'
      })),
      ...Array.from({ length: MAX_V2_PENDING_MESSAGES + 1 }, (_, index) => ({
        clientMessageId: `pending-${index}`,
        sequence: '0',
        content: 'pending',
        deliveryState: index % 2 === 0 ? 'sending' : 'failed'
      }))
    ]
  })
  assert.equal(record.messages.length, MAX_CACHED_MESSAGES + MAX_V2_PENDING_MESSAGES)
  assert.equal(record.messages[0].id, 'accepted-1')
  assert.equal(record.messages[MAX_CACHED_MESSAGES].clientMessageId, 'pending-1')
})
