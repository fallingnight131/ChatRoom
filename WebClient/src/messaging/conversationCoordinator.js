import { syncSequenceOf } from './messageReconciliation.js'
import {
  applySendAcknowledgement,
  makeOptimisticMessage,
  pendingMessagesFor
} from './outbox.js'
import { conversationCache } from '../persistence/conversationCache.js'
import { chatWs } from '../services/websocket.js'

export class ConversationCoordinator {
  constructor({ cache, transport }) {
    this.cache = cache
    this.transport = transport
  }

  static room(roomId) {
    return { kind: 'room', id: roomId }
  }

  static direct(friendUsername) {
    return { kind: 'direct', id: friendUsername }
  }

  async hydrate(account, target) {
    if (!account || !this.#validTarget(target)) return null
    return this.cache.load(account, this.#cacheKind(target), target.id)
  }

  persist(account, target, messages, cursor) {
    if (!account || !this.#validTarget(target)) return Promise.resolve(false)
    return this.cache.save(account, this.#cacheKind(target), target.id,
      messages, Math.max(0, Number(cursor) || 0))
  }

  remove(account, target) {
    if (!account || !this.#validTarget(target)) return Promise.resolve(false)
    return this.cache.remove(account, this.#cacheKind(target), target.id)
  }

  prune(account, kind, allowedConversationIds) {
    if (!account || (kind !== 'room' && kind !== 'direct')) return Promise.resolve(false)
    return this.cache.prune(account, kind === 'direct' ? 'friend' : 'room',
      allowedConversationIds)
  }

  advanceCursor(currentCursor, ...items) {
    return items.reduce((maximum, item) => Math.max(maximum, syncSequenceOf(item)),
      Math.max(0, Number(currentCursor) || 0))
  }

  requestSync(target, messages, cursor) {
    if (!this.#validTarget(target)) return false
    const sequence = Math.max(0, Number(cursor) || 0)
    const incremental = Array.isArray(messages) && messages.length > 0 && sequence > 0
    if (target.kind === 'room') {
      this.transport.requestHistory(target.id, incremental ? 100 : 50,
        0, incremental ? sequence : undefined)
    } else {
      this.transport.requestFriendHistory(target.id, incremental ? 100 : 50,
        0, incremental ? sequence : undefined)
    }
    return true
  }

  stage(target, sender, senderName, content, contentType = 'text') {
    if (!this.#validTarget(target) || !sender || !content) return null
    const clientMessageId = target.kind === 'room'
      ? this.transport.sendChat(target.id, sender, content, contentType)
      : this.transport.sendFriendChat(target.id, content, contentType)
    const fields = {
      sender,
      senderName,
      content,
      contentType,
      ...(target.kind === 'room'
        ? { roomId: target.id }
        : { friendUsername: target.id })
    }
    return makeOptimisticMessage(fields, clientMessageId)
  }

  recoverPending(target, messages, username) {
    if (!this.#validTarget(target) || !username) return 0
    const pending = pendingMessagesFor(messages, username)
    for (const message of pending) this.#dispatch(target, username, message)
    return pending.length
  }

  retry(target, message, username) {
    if (!this.#validTarget(target) || !message?.clientMessageId || !username)
      return false
    message.deliveryState = 'sending'
    message.errorCode = ''
    this.#dispatch(target, username, message)
    return true
  }

  acknowledge(messages, result) {
    return applySendAcknowledgement(messages, result)
  }

  #dispatch(target, username, message) {
    if (target.kind === 'room') {
      this.transport.sendChat(target.id, username, message.content,
        message.contentType || 'text', message.clientMessageId)
    } else {
      this.transport.sendFriendChat(target.id, message.content,
        message.contentType || 'text', message.clientMessageId)
    }
  }

  #cacheKind(target) {
    return target.kind === 'direct' ? 'friend' : 'room'
  }

  #validTarget(target) {
    return Boolean(target?.id) && (target.kind === 'room' || target.kind === 'direct')
  }
}

export const conversationCoordinator = new ConversationCoordinator({
  cache: conversationCache,
  transport: chatWs
})
