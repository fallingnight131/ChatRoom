const DATABASE_NAME = 'chat-room-client'
const DATABASE_VERSION = 2
const STORE_NAME = 'conversations'
export const MAX_CACHED_MESSAGES = 500
export const MAX_DRAFT_LENGTH = 10000
export const NON_PERSISTED_MEDIA_FIELDS = Object.freeze([
  'imageData',
  'fileData',
  'chunkData',
  'thumbnail',
  'file',
  'blob',
  'bytes',
  'dataUrl',
  'cosUrl',
  'downloadUrl',
  'uploadUrl',
  'httpUploadPath',
  'uploadId',
  'authorization',
  'token'
])

export function conversationCacheKey(account, kind, conversationId) {
  return `${String(account)}\u001f${String(kind)}\u001f${String(conversationId)}`
}

export function sanitizeCachedMessage(message) {
  const serialized = JSON.stringify(message)
  if (serialized === undefined) return null
  const plain = JSON.parse(serialized)
  if (!plain || typeof plain !== 'object' || Array.isArray(plain)) return plain
  for (const field of NON_PERSISTED_MEDIA_FIELDS) delete plain[field]
  return plain
}

export function sanitizeConversationRecord(record) {
  if (!record || typeof record !== 'object') return record
  return {
    key: String(record.key || ''),
    account: String(record.account || ''),
    kind: String(record.kind || ''),
    conversationId: String(record.conversationId || ''),
    messages: Array.isArray(record.messages)
      ? record.messages.slice(-MAX_CACHED_MESSAGES).map(sanitizeCachedMessage)
      : [],
    cursor: Math.max(0, Number(record.cursor) || 0),
    draft: typeof record.draft === 'string'
      ? record.draft.slice(0, MAX_DRAFT_LENGTH)
      : '',
    updatedAt: Number(record.updatedAt) || Date.now()
  }
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
    // converted back to plain structured-clone-safe metadata before IndexedDB.
    // Media bytes and temporary authorization remain page-memory-only.
    messages: boundedMessages.map(sanitizeCachedMessage),
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
        request.onupgradeneeded = event => {
          const database = request.result
          if (!database.objectStoreNames.contains(STORE_NAME)) {
            database.createObjectStore(STORE_NAME, { keyPath: 'key' })
          } else if (event.oldVersion < 2) {
            const store = request.transaction.objectStore(STORE_NAME)
            const cursorRequest = store.openCursor()
            cursorRequest.onsuccess = () => {
              const cursor = cursorRequest.result
              if (!cursor) return
              cursor.update(sanitizeConversationRecord(cursor.value))
              cursor.continue()
            }
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
    return record ? sanitizeConversationRecord(record) : null
  }

  save(account, kind, conversationId, messages, cursor) {
    if (!account) return Promise.resolve(false)
    const record = makeConversationRecord(account, kind, conversationId, messages, cursor)
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.open()
      if (!database) return false
      const transaction = database.transaction(STORE_NAME, 'readwrite')
      const store = transaction.objectStore(STORE_NAME)
      const done = transactionDone(transaction)
      const existing = await requestResult(store.get(record.key))
      store.put({ ...record, draft: existing?.draft || '' })
      await done
      return true
    })
    return this.writeQueue
  }

  async loadDraft(account, kind, conversationId) {
    const record = await this.load(account, kind, conversationId)
    return typeof record?.draft === 'string' ? record.draft : ''
  }

  saveDraft(account, kind, conversationId, draft) {
    if (!account) return Promise.resolve(false)
    const key = conversationCacheKey(account, kind, conversationId)
    const normalizedDraft = String(draft || '').slice(0, MAX_DRAFT_LENGTH)
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.open()
      if (!database) return false
      const transaction = database.transaction(STORE_NAME, 'readwrite')
      const store = transaction.objectStore(STORE_NAME)
      const done = transactionDone(transaction)
      const existing = sanitizeConversationRecord(await requestResult(store.get(key))) ||
        makeConversationRecord(account, kind, conversationId, [], 0)
      store.put({ ...existing, draft: normalizedDraft, updatedAt: Date.now() })
      await done
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
