import assert from 'node:assert/strict'
import test from 'node:test'

import {
  IndexedDbAttachmentOutbox,
  makeAttachmentCommand,
  normalizeRecoveredAttachment,
  sourceRevision
} from '../src/persistence/attachmentOutbox.js'

function fakeDatabaseProvider() {
  const records = new Map()
  const request = executor => {
    const result = {}
    queueMicrotask(() => {
      result.result = executor()
      result.onsuccess?.()
    })
    return result
  }
  return {
    open: async () => ({
      transaction: (_store, mode) => {
        const transaction = {
          objectStore: () => ({
            getAll: () => request(() =>
              [...records.values()].map(value => structuredClone(value))),
            put: value => request(() => records.set(value.key, structuredClone(value))),
            delete: key => request(() => records.delete(key))
          })
        }
        if (mode === 'readwrite') setTimeout(() => transaction.oncomplete?.(), 0)
        return transaction
      }
    })
  }
}

const file = { name: 'photo.png', size: 2048, lastModified: 123 }

test('creates account and conversation scoped commands without authorization', () => {
  const command = makeAttachmentCommand({
    account: 'alice', kind: 'room', conversationId: 7,
    clientMessageId: 'client-1', file, contentType: 'image'
  })
  assert.equal(command.sourceRevision, sourceRevision(file))
  assert.equal(command.uploadId, undefined)
  assert.equal(command.token, undefined)
})

test('recovery requires reselection when no persistent file handle exists', () => {
  const recovered = normalizeRecoveredAttachment(makeAttachmentCommand({
    account: 'alice', kind: 'direct', conversationId: 'bob',
    clientMessageId: 'client-2', file
  }))
  assert.equal(recovered.state, 'needs_source')
  assert.equal(recovered.errorCode, 'SOURCE_RESELECTION_REQUIRED')
})

test('round trips cloneable handles and removes completed commands', async () => {
  const repository = new IndexedDbAttachmentOutbox(fakeDatabaseProvider())
  const command = makeAttachmentCommand({
    account: 'alice', kind: 'room', conversationId: 7,
    clientMessageId: 'client-3', file, sourceHandle: { kind: 'file', name: file.name }
  })
  await repository.save(command)
  const recovered = await repository.list('alice')
  assert.equal(recovered.length, 1)
  assert.equal(recovered[0].state, 'pending')
  assert.equal(recovered[0].sourceHandle.name, file.name)
  await repository.remove('alice', 'client-3')
  assert.deepEqual(await repository.list('alice'), [])
})
