import test from 'node:test'
import assert from 'node:assert/strict'

import {
  IndexedDbConversationCache,
  MAX_CACHED_MESSAGES,
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
    put: record => requestWith(() => records.set(record.key, structuredClone(record)))
  }
  database = {
    objectStoreNames: { contains: () => true },
    createObjectStore: () => objectStore,
    transaction: (_name, mode) => {
      const transaction = { objectStore: () => objectStore }
      if (mode === 'readwrite') queueMicrotask(() => queueMicrotask(() => transaction.oncomplete?.()))
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
})

test('degrades to an empty cache when IndexedDB is unavailable', async () => {
  const cache = new IndexedDbConversationCache(null)
  assert.equal(await cache.load('alice', 'room', 7), null)
  assert.equal(await cache.save('alice', 'room', 7, [], 0), false)
})
