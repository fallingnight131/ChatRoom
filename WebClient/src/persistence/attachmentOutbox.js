import {
  ATTACHMENT_OUTBOX_STORE_NAME,
  conversationCache
} from './conversationCache.js'

const VALID_KINDS = new Set(['room', 'direct'])
const VALID_STATES = new Set(['pending', 'failed', 'needs_source'])

export function attachmentCommandKey(account, clientMessageId) {
  return `${String(account)}\u001f${String(clientMessageId)}`
}

export function sourceRevision(file) {
  return `${String(file?.name || '')}:${Math.max(0, Number(file?.size) || 0)}:${
    Math.max(0, Number(file?.lastModified) || 0)}`
}

export function makeAttachmentCommand({
  account,
  kind,
  conversationId,
  clientMessageId,
  file,
  sourceHandle = null,
  contentType = 'file'
}) {
  if (!account || !VALID_KINDS.has(kind) || !conversationId || !clientMessageId || !file)
    throw new Error('attachment command identity is incomplete')
  const now = Date.now()
  return {
    key: attachmentCommandKey(account, clientMessageId),
    account: String(account),
    kind,
    conversationId: String(conversationId),
    clientMessageId: String(clientMessageId),
    fileName: String(file.name || ''),
    fileSize: Math.max(0, Number(file.size) || 0),
    lastModified: Math.max(0, Number(file.lastModified) || 0),
    sourceRevision: sourceRevision(file),
    sourceHandle,
    contentType: ['image', 'video'].includes(contentType) ? contentType : 'file',
    state: 'pending',
    errorCode: '',
    createdAt: now,
    updatedAt: now
  }
}

export function normalizeRecoveredAttachment(command) {
  if (!command || typeof command !== 'object') return null
  const normalized = {
    key: attachmentCommandKey(command.account, command.clientMessageId),
    account: String(command.account || ''),
    kind: VALID_KINDS.has(command.kind) ? command.kind : '',
    conversationId: String(command.conversationId || ''),
    clientMessageId: String(command.clientMessageId || ''),
    fileName: String(command.fileName || ''),
    fileSize: Math.max(0, Number(command.fileSize) || 0),
    lastModified: Math.max(0, Number(command.lastModified) || 0),
    sourceRevision: String(command.sourceRevision || ''),
    sourceHandle: command.sourceHandle || null,
    contentType: ['image', 'video'].includes(command.contentType)
      ? command.contentType : 'file',
    state: VALID_STATES.has(command.state) ? command.state : 'failed',
    errorCode: String(command.errorCode || ''),
    createdAt: Number(command.createdAt) || Date.now(),
    updatedAt: Number(command.updatedAt) || Date.now()
  }
  if (!normalized.account || !normalized.kind || !normalized.conversationId
      || !normalized.clientMessageId || !normalized.fileName) return null
  if (!normalized.sourceHandle) {
    normalized.state = 'needs_source'
    normalized.errorCode = 'SOURCE_RESELECTION_REQUIRED'
  } else if (normalized.state === 'pending') {
    normalized.errorCode = ''
  }
  return normalized
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

export class IndexedDbAttachmentOutbox {
  constructor(databaseProvider = conversationCache) {
    this.databaseProvider = databaseProvider
    this.writeQueue = Promise.resolve()
  }

  save(command) {
    const normalized = normalizeRecoveredAttachment(command)
    if (!normalized) return Promise.reject(new Error('invalid attachment command'))
    // A newly selected File is usable for this page even when no persistent
    // handle exists; recovery will deliberately require reselection.
    if (command.state === 'pending') {
      normalized.state = 'pending'
      normalized.errorCode = ''
    }
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.databaseProvider.open()
      if (!database) return false
      const transaction = database.transaction(ATTACHMENT_OUTBOX_STORE_NAME, 'readwrite')
      transaction.objectStore(ATTACHMENT_OUTBOX_STORE_NAME).put(normalized)
      await transactionDone(transaction)
      return true
    })
    return this.writeQueue
  }

  async list(account) {
    if (!account) return []
    const database = await this.databaseProvider.open()
    if (!database) return []
    const transaction = database.transaction(ATTACHMENT_OUTBOX_STORE_NAME, 'readonly')
    const records = await requestResult(
      transaction.objectStore(ATTACHMENT_OUTBOX_STORE_NAME).getAll())
    return records
      .filter(record => record.account === String(account))
      .map(normalizeRecoveredAttachment)
      .filter(Boolean)
      .sort((left, right) => left.createdAt - right.createdAt)
  }

  remove(account, clientMessageId) {
    if (!account || !clientMessageId) return Promise.resolve(false)
    this.writeQueue = this.writeQueue.catch(() => {}).then(async () => {
      const database = await this.databaseProvider.open()
      if (!database) return false
      const transaction = database.transaction(ATTACHMENT_OUTBOX_STORE_NAME, 'readwrite')
      transaction.objectStore(ATTACHMENT_OUTBOX_STORE_NAME).delete(
        attachmentCommandKey(account, clientMessageId))
      await transactionDone(transaction)
      return true
    })
    return this.writeQueue
  }
}

export const attachmentOutbox = new IndexedDbAttachmentOutbox()
