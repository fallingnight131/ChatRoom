import {
  attachmentOutbox,
  makeAttachmentCommand,
  sourceRevision
} from '../persistence/attachmentOutbox.js'

function targetKey(kind, conversationId) {
  return `${kind}:${String(conversationId)}`
}

export class AttachmentOutboxCoordinator {
  constructor(repository = attachmentOutbox) {
    this.repository = repository
    this.runtimeSources = new Map()
  }

  async stage(input) {
    const command = makeAttachmentCommand(input)
    this.runtimeSources.set(command.key, input.file)
    await this.repository.save(command)
    return command
  }

  async recover(account, allowedTargets) {
    const allowed = allowedTargets instanceof Set ? allowedTargets : new Set()
    const commands = await this.repository.list(account)
    const retained = []
    for (const command of commands) {
      if (!allowed.has(targetKey(command.kind, command.conversationId))) {
        await this.repository.remove(command.account, command.clientMessageId)
        this.runtimeSources.delete(command.key)
        continue
      }
      const source = await this.resolveSource(command)
      if (source) {
        command.state = 'pending'
        command.errorCode = ''
        this.runtimeSources.set(command.key, source)
      } else {
        command.state = 'needs_source'
        command.errorCode = 'SOURCE_RESELECTION_REQUIRED'
      }
      command.updatedAt = Date.now()
      await this.repository.save(command)
      retained.push(command)
    }
    return retained
  }

  async resolveSource(command) {
    const inMemory = this.runtimeSources.get(command.key)
    if (inMemory && sourceRevision(inMemory) === command.sourceRevision)
      return inMemory
    const handle = command.sourceHandle
    if (!handle || typeof handle.getFile !== 'function') return null
    try {
      if (typeof handle.queryPermission === 'function') {
        const permission = await handle.queryPermission({ mode: 'read' })
        if (permission !== 'granted') return null
      }
      const file = await handle.getFile()
      return sourceRevision(file) === command.sourceRevision ? file : null
    } catch {
      return null
    }
  }

  async reselect(command, file, sourceHandle = null) {
    if (!command || sourceRevision(file) !== command.sourceRevision) {
      const error = new Error('selected file does not match the original source revision')
      error.code = 'SOURCE_REVISION_MISMATCH'
      throw error
    }
    const updated = {
      ...command,
      sourceHandle,
      state: 'pending',
      errorCode: '',
      updatedAt: Date.now()
    }
    this.runtimeSources.set(updated.key, file)
    await this.repository.save(updated)
    return updated
  }

  async prepare(command) {
    const file = await this.resolveSource(command)
    if (file) return { command, file }
    const updated = {
      ...command,
      state: 'needs_source',
      errorCode: 'SOURCE_RESELECTION_REQUIRED',
      updatedAt: Date.now()
    }
    await this.repository.save(updated)
    return { command: updated, file: null }
  }

  async fail(command, errorCode = 'UPLOAD_FAILED') {
    const updated = {
      ...command,
      state: 'failed',
      errorCode: String(errorCode || 'UPLOAD_FAILED'),
      updatedAt: Date.now()
    }
    await this.repository.save(updated)
    return updated
  }

  async cancel(command) {
    if (!command) return false
    this.runtimeSources.delete(command.key)
    return this.repository.remove(command.account, command.clientMessageId)
  }

  async complete(command) {
    return this.cancel(command)
  }

  static targetKey(kind, conversationId) {
    return targetKey(kind, conversationId)
  }
}

export const attachmentOutboxCoordinator = new AttachmentOutboxCoordinator()
