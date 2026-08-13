const DATABASE_NAME = 'chat-room-client'
const DATABASE_VERSION = 3
const STORE_NAME = 'conversations'
export const ATTACHMENT_OUTBOX_STORE_NAME = 'attachmentCommands'
const V2_DATABASE_NAME = 'chat-room-client-v2'
const V2_DATABASE_VERSION = 1
export const V2_CONVERSATION_STORE_NAME = 'v2Conversations'
export const MAX_CACHED_MESSAGES = 500
export const MAX_V2_PENDING_MESSAGES = 100
export const MAX_DRAFT_LENGTH = 10000
const MAX_SIGNED_SEQUENCE = (1n << 63n) - 1n
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
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

export function normalizeV2Sequence(value) {
  try {
    const sequence = BigInt(value)
    return sequence >= 0n && sequence <= MAX_SIGNED_SEQUENCE
      ? sequence.toString()
      : '0'
  } catch {
    return '0'
  }
}

export function sanitizeV2Message(message) {
  if (!message || typeof message !== 'object') return null
  const content = typeof message.content === 'string' ? message.content : ''
  const replySequence = normalizeV2Sequence(message.reply?.targetConversationSequence)
  const reply = message.reply
    && typeof message.reply.targetMessageId === 'string'
    && CANONICAL_UUID.test(message.reply.targetMessageId)
    && typeof message.reply.targetSenderAccountId === 'string'
    && CANONICAL_UUID.test(message.reply.targetSenderAccountId)
    && replySequence !== '0'
    ? {
        targetMessageId: message.reply.targetMessageId,
        targetConversationSequence: replySequence,
        targetSenderAccountId: message.reply.targetSenderAccountId
      }
    : null
  return {
    conversationId: String(message.conversationId || ''),
    id: String(message.id || ''),
    clientMessageId: String(message.clientMessageId || ''),
    senderAccountId: String(message.senderAccountId || ''),
    senderDeviceId: String(message.senderDeviceId || ''),
    sequence: normalizeV2Sequence(message.sequence),
    acceptedAtEpochMs: Math.max(0, Number(message.acceptedAtEpochMs) || 0),
    content,
    contentType: 'text',
    deliveryState: ['sending', 'accepted', 'failed'].includes(message.deliveryState)
      ? message.deliveryState
      : 'accepted',
    errorCode: typeof message.errorCode === 'string' ? message.errorCode : '',
    availability: message.availability === 'recalled' ? 'recalled' : 'available',
    reply,
    reactions: Array.isArray(message.reactions)
      ? message.reactions.map(sanitizeV2Reaction).filter(Boolean).slice(0, 6)
      : [],
    pinned: Boolean(message.pinned)
  }
}

function sanitizeV2Reaction(value) {
  const reaction = Number(value?.reaction)
  if (!Number.isInteger(reaction) || reaction < 1 || reaction > 6) return null
  const actorAccountIds = Array.isArray(value.actorAccountIds)
    ? [...new Set(value.actorAccountIds.filter(id =>
      typeof id === 'string' && CANONICAL_UUID.test(id)))].sort()
    : []
  return actorAccountIds.length ? { reaction, actorAccountIds } : null
}

function sanitizeV2ReactionCommand(value) {
  const reaction = Number(value?.reaction)
  if (!value || typeof value !== 'object'
      || !CANONICAL_UUID.test(String(value.conversationId || ''))
      || !CANONICAL_UUID.test(String(value.messageId || ''))
      || !Number.isInteger(reaction) || reaction < 1 || reaction > 6
      || typeof value.clientOperationId !== 'string'
      || value.clientOperationId.length < 1 || value.clientOperationId.length > 128) return null
  return {
    conversationId: value.conversationId,
    messageId: value.messageId,
    reaction,
    active: Boolean(value.active),
    clientOperationId: value.clientOperationId,
    deliveryState: value.deliveryState === 'failed' ? 'failed' : 'sending',
    errorCode: typeof value.errorCode === 'string' ? value.errorCode : ''
  }
}

function sanitizeV2PinCommand(value) {
  if (!value || typeof value !== 'object'
      || !CANONICAL_UUID.test(String(value.conversationId || ''))
      || !CANONICAL_UUID.test(String(value.messageId || ''))
      || typeof value.clientOperationId !== 'string'
      || value.clientOperationId.length < 1 || value.clientOperationId.length > 128) return null
  return { conversationId: value.conversationId, messageId: value.messageId,
    pinned: Boolean(value.pinned), clientOperationId: value.clientOperationId,
    deliveryState: value.deliveryState === 'failed' ? 'failed' : 'sending',
    errorCode: typeof value.errorCode === 'string' ? value.errorCode : '' }
}

export function v2ConversationCacheKey(accountId, conversationId) {
  return `${String(accountId)}\u001f${String(conversationId)}`
}

export function sanitizeV2ConversationRecord(record) {
  if (!record || typeof record !== 'object') return null
  const sanitizedMessages = Array.isArray(record.messages)
    ? record.messages.map(sanitizeV2Message).filter(Boolean)
    : []
  const acceptedMessages = sanitizedMessages
    .filter(message => message.deliveryState === 'accepted')
    .slice(-MAX_CACHED_MESSAGES)
  const unresolvedMessages = sanitizedMessages
    .filter(message => message.deliveryState !== 'accepted')
    .slice(-MAX_V2_PENDING_MESSAGES)
  return {
    key: v2ConversationCacheKey(record.accountId, record.conversationId),
    accountId: String(record.accountId || ''),
    conversationId: String(record.conversationId || ''),
    messages: [...acceptedMessages, ...unresolvedMessages],
    reactionCommands: Array.isArray(record.reactionCommands)
      ? record.reactionCommands.map(sanitizeV2ReactionCommand).filter(Boolean)
        .slice(-MAX_V2_PENDING_MESSAGES)
      : [],
    pinCommands: Array.isArray(record.pinCommands)
      ? record.pinCommands.map(sanitizeV2PinCommand).filter(Boolean)
        .slice(-MAX_V2_PENDING_MESSAGES)
      : [],
    cursorSequence: normalizeV2Sequence(record.cursorSequence),
    draft: typeof record.draft === 'string'
      ? record.draft.slice(0, MAX_DRAFT_LENGTH)
      : '',
    updatedAt: Number(record.updatedAt) || Date.now()
  }
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

function openDatabase(indexedDb, name, version, upgrade) {
  return new Promise((resolve, reject) => {
    const request = indexedDb.open(name, version)
    let settled = false
    const fail = error => {
      if (settled) return
      settled = true
      reject(error)
    }
    request.onupgradeneeded = event => upgrade(request, event)
    request.onblocked = () => fail(new Error(
      'IndexedDB upgrade blocked by another browser tab'))
    request.onerror = () => fail(request.error || new Error('Unable to open IndexedDB'))
    request.onsuccess = () => {
      const database = request.result
      if (settled) {
        database.close()
        return
      }
      settled = true
      database.onversionchange = () => database.close()
      resolve(database)
    }
  })
}

export class IndexedDbConversationCache {
  constructor(indexedDb = globalThis.indexedDB) {
    this.indexedDb = indexedDb
    this.databasePromise = null
    this.v2DatabasePromise = null
    this.writeQueue = Promise.resolve()
  }

  async open() {
    if (!this.indexedDb) return null
    if (!this.databasePromise) {
      this.databasePromise = openDatabase(
        this.indexedDb, DATABASE_NAME, DATABASE_VERSION, (request, event) => {
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
          if (!database.objectStoreNames.contains(ATTACHMENT_OUTBOX_STORE_NAME)) {
            database.createObjectStore(
              ATTACHMENT_OUTBOX_STORE_NAME, { keyPath: 'key' })
          }
        }).catch(error => {
          this.databasePromise = null
          throw error
        })
    }
    return this.databasePromise
  }

  async openV2() {
    if (!this.indexedDb) return null
    if (!this.v2DatabasePromise) {
      this.v2DatabasePromise = openDatabase(
        this.indexedDb, V2_DATABASE_NAME, V2_DATABASE_VERSION, request => {
          const database = request.result
          if (!database.objectStoreNames.contains(V2_CONVERSATION_STORE_NAME)) {
            database.createObjectStore(V2_CONVERSATION_STORE_NAME, { keyPath: 'key' })
          }
        }).catch(error => {
          this.v2DatabasePromise = null
          throw error
        })
    }
    return this.v2DatabasePromise
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

  async loadV2(accountId, conversationId) {
    if (!accountId || !conversationId) return null
    const database = await this.openV2()
    if (!database) return null
    const transaction = database.transaction(V2_CONVERSATION_STORE_NAME, 'readonly')
    const record = await requestResult(transaction.objectStore(V2_CONVERSATION_STORE_NAME)
      .get(v2ConversationCacheKey(accountId, conversationId)))
    return sanitizeV2ConversationRecord(record)
  }

  saveV2(accountId, conversationId, messages, cursorSequence, reactionCommands = [], pinCommands = []) {
    if (!accountId || !conversationId) return Promise.resolve(false)
    const record = sanitizeV2ConversationRecord({
      accountId, conversationId, messages, cursorSequence, reactionCommands, pinCommands,
      updatedAt: Date.now()
    })
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.openV2()
      if (!database) return false
      const transaction = database.transaction(V2_CONVERSATION_STORE_NAME, 'readwrite')
      const store = transaction.objectStore(V2_CONVERSATION_STORE_NAME)
      const done = transactionDone(transaction)
      const existing = await requestResult(store.get(record.key))
      store.put({ ...record, draft: existing?.draft || '' })
      await done
      return true
    })
    return this.writeQueue
  }

  removeV2(accountId, conversationId) {
    if (!accountId || !conversationId) return Promise.resolve(false)
    const key = v2ConversationCacheKey(accountId, conversationId)
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.openV2()
      if (!database) return false
      const transaction = database.transaction(V2_CONVERSATION_STORE_NAME, 'readwrite')
      transaction.objectStore(V2_CONVERSATION_STORE_NAME).delete(key)
      await transactionDone(transaction)
      return true
    })
    return this.writeQueue
  }
}

export const conversationCache = new IndexedDbConversationCache()
