import assert from 'node:assert/strict'
import test from 'node:test'

import { AttachmentOutboxCoordinator } from '../src/messaging/attachmentOutboxCoordinator.js'

class MemoryRepository {
  constructor(records = []) { this.records = records.map(item => ({ ...item })) }
  async save(command) {
    const index = this.records.findIndex(item => item.key === command.key)
    if (index >= 0) this.records[index] = { ...command }
    else this.records.push({ ...command })
    return true
  }
  async list(account) {
    return this.records.filter(item => item.account === account)
      .map(item => ({ ...item }))
  }
  async remove(account, clientMessageId) {
    this.records = this.records.filter(item =>
      item.account !== account || item.clientMessageId !== clientMessageId)
    return true
  }
}

function input(overrides = {}) {
  return {
    account: 'alice', kind: 'room', conversationId: 7,
    clientMessageId: 'client-1',
    file: { name: 'a.png', size: 12, lastModified: 34 },
    contentType: 'image', ...overrides
  }
}

test('stages runtime source and prepares it without persisting bytes', async () => {
  const repository = new MemoryRepository()
  const coordinator = new AttachmentOutboxCoordinator(repository)
  const command = await coordinator.stage(input())
  const prepared = await coordinator.prepare(command)
  assert.equal(prepared.file.name, 'a.png')
  assert.equal(repository.records[0].file, undefined)
})

test('keeps a freshly selected runtime source usable when persistence fails', async () => {
  const repository = new MemoryRepository()
  repository.save = async () => { throw new Error('storage unavailable') }
  const coordinator = new AttachmentOutboxCoordinator(repository)
  const command = await coordinator.stage(input())
  const prepared = await coordinator.prepare(command)
  assert.equal(command.persistenceError, 'storage unavailable')
  assert.equal(prepared.file.name, 'a.png')
})

test('recovers granted handles and never prompts outside a user gesture', async () => {
  let requestCalls = 0
  const file = input().file
  const handle = {
    queryPermission: async () => 'granted',
    requestPermission: async () => { requestCalls += 1; return 'granted' },
    getFile: async () => file
  }
  const repository = new MemoryRepository()
  const first = new AttachmentOutboxCoordinator(repository)
  await first.stage(input({ sourceHandle: handle }))
  const recovered = await new AttachmentOutboxCoordinator(repository).recover(
    'alice', new Set(['room:7']))
  assert.equal(recovered[0].state, 'pending')
  assert.equal(requestCalls, 0)
})

test('requires reselection for denied handles and rejects changed sources', async () => {
  const repository = new MemoryRepository()
  const coordinator = new AttachmentOutboxCoordinator(repository)
  const command = await coordinator.stage(input({
    sourceHandle: { queryPermission: async () => 'prompt', getFile: async () => input().file }
  }))
  const recovered = await new AttachmentOutboxCoordinator(repository).recover(
    'alice', new Set(['room:7']))
  assert.equal(recovered[0].state, 'needs_source')
  await assert.rejects(
    () => coordinator.reselect(command, { ...input().file, size: 13 }),
    error => error.code === 'SOURCE_REVISION_MISMATCH')
})

test('removes commands whose conversation access was revoked', async () => {
  const repository = new MemoryRepository()
  const coordinator = new AttachmentOutboxCoordinator(repository)
  await coordinator.stage(input())
  assert.deepEqual(await coordinator.recover('alice', new Set()), [])
  assert.deepEqual(repository.records, [])
})
