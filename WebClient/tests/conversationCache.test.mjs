import test from 'node:test'
import assert from 'node:assert/strict'

import {
  IndexedDbConversationCache,
  MAX_CACHED_MESSAGES,
  MAX_DRAFT_LENGTH,
  conversationCacheKey,
  makeConversationRecord
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
