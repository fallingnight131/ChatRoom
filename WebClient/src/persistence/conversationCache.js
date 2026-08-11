const DATABASE_NAME = 'chat-room-client'
const DATABASE_VERSION = 1
const STORE_NAME = 'conversations'
export const MAX_CACHED_MESSAGES = 500

export function conversationCacheKey(account, kind, conversationId) {
  return `${String(account)}\u001f${String(kind)}\u001f${String(conversationId)}`
}

export function makeConversationRecord(account, kind, conversationId, messages, cursor) {
  const boundedMessages = Array.isArray(messages)
    ? messages.slice(-MAX_CACHED_MESSAGES)
    : []
  return {
    key: conversationCacheKey(account, kind, conversationId),
    account: String(account),
    kind: String(kind),
    conversationId: String(conversationId),
    // Pinia exposes reactive proxies; JSON protocol messages are deliberately
    // converted back to plain structured-clone-safe data before IndexedDB.
    messages: JSON.parse(JSON.stringify(boundedMessages)),
    cursor: Math.max(0, Number(cursor) || 0),
    updatedAt: Date.now()
  }
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('IndexedDB request failed'))
  })
}

function transactionDone(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error || new Error('IndexedDB transaction failed'))
    transaction.onabort = () => reject(transaction.error || new Error('IndexedDB transaction aborted'))
  })
}

export class IndexedDbConversationCache {
  constructor(indexedDb = globalThis.indexedDB) {
    this.indexedDb = indexedDb
    this.databasePromise = null
    this.writeQueue = Promise.resolve()
  }

  async open() {
    if (!this.indexedDb) return null
    if (!this.databasePromise) {
      this.databasePromise = new Promise((resolve, reject) => {
        const request = this.indexedDb.open(DATABASE_NAME, DATABASE_VERSION)
        request.onupgradeneeded = () => {
          const database = request.result
          if (!database.objectStoreNames.contains(STORE_NAME)) {
            database.createObjectStore(STORE_NAME, { keyPath: 'key' })
          }
        }
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error || new Error('Unable to open IndexedDB'))
      }).catch(error => {
        this.databasePromise = null
        throw error
      })
    }
    return this.databasePromise
  }

  async load(account, kind, conversationId) {
    if (!account) return null
    const database = await this.open()
    if (!database) return null
    const transaction = database.transaction(STORE_NAME, 'readonly')
    const record = await requestResult(
      transaction.objectStore(STORE_NAME).get(conversationCacheKey(account, kind, conversationId))
    )
    return record || null
  }

  save(account, kind, conversationId, messages, cursor) {
    if (!account) return Promise.resolve(false)
    const record = makeConversationRecord(account, kind, conversationId, messages, cursor)
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.open()
      if (!database) return false
      const transaction = database.transaction(STORE_NAME, 'readwrite')
      transaction.objectStore(STORE_NAME).put(record)
      await transactionDone(transaction)
      return true
    })
    return this.writeQueue
  }

  remove(account, kind, conversationId) {
    if (!account) return Promise.resolve(false)
    const key = conversationCacheKey(account, kind, conversationId)
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.open()
      if (!database) return false
      const transaction = database.transaction(STORE_NAME, 'readwrite')
      transaction.objectStore(STORE_NAME).delete(key)
      await transactionDone(transaction)
      return true
    })
    return this.writeQueue
  }

  prune(account, kind, allowedConversationIds) {
    if (!account) return Promise.resolve(false)
    const allowed = new Set([...allowedConversationIds].map(String))
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.open()
      if (!database) return false
      const transaction = database.transaction(STORE_NAME, 'readwrite')
      const store = transaction.objectStore(STORE_NAME)
      const done = transactionDone(transaction)
      const records = await requestResult(store.getAll())
      for (const record of records) {
        if (record.account === String(account) && record.kind === String(kind) &&
            !allowed.has(record.conversationId)) {
          store.delete(record.key)
        }
      }
      await done
      return true
    })
    return this.writeQueue
  }
}

export const conversationCache = new IndexedDbConversationCache()
