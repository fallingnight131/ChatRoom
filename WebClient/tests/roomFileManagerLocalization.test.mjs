import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomFileManagerDialog.vue', import.meta.url), 'utf8')

test('renders room file management from the active locale catalog', () => {
  for (const marker of [
    'roomFileManagerMessages(userStore.locale)', 'messages.title', 'messages.usagePrefix',
    'messages.caption', 'messages.selectAll', 'messages.fileName', 'messages.type', 'messages.size',
    'messages.uploadedAt', 'messages.selectFilePrefix', 'messages.noFiles', 'messages.deleting',
    'messages.refresh', 'messages.deleteSelected', 'messages.close', 'messages.value.image',
    'messages.value.video', 'messages.value.file', 'messages.value.deleteConfirm',
  ]) assert.ok(source.includes(marker), `missing room-file locale marker: ${marker}`)
})

test('preserves correlated destructive commands and pending-state behavior', () => {
  for (const marker of [
    'if (isDeleting.value || selected.value.length === 0) return',
    'pendingDeleteOperationId.value = chatStore.deleteRoomFiles(',
    'chatStore.currentRoomId,', '[...selected.value]',
    'data?.clientOperationId === pendingDeleteOperationId.value',
    "chatStore.onEvent('roomFilesDeleted', onDeleted)",
    "chatStore.onEvent('roomFilesDeleteFailed', onDeleteFailed)",
  ]) assert.ok(source.includes(marker), `missing room-file deletion boundary marker: ${marker}`)
})
