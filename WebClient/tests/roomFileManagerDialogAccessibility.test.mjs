import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const dialogSource = readFileSync(new URL('../src/components/RoomFileManagerDialog.vue', import.meta.url), 'utf8')
const storeSource = readFileSync(new URL('../src/stores/chat.js', import.meta.url), 'utf8')

test('exposes a labeled file-management modal and selectable table', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="room-file-title"',
    ':aria-busy="isDeleting"',
    '<caption class="visually-hidden">',
    ':aria-label="messages.selectAll"',
    ':aria-label="`${messages.selectFilePrefix}${f.fileName}`"',
    'useModalKeyboardBoundary({',
  ]) assert.ok(dialogSource.includes(marker), `missing room file marker: ${marker}`)
})

test('correlates one deletion response and unlocks on success or failure', () => {
  for (const marker of [
    'if (isDeleting.value || selected.value.length === 0) return',
    'pendingDeleteOperationId.value = chatStore.deleteRoomFiles(',
    'data?.clientOperationId === pendingDeleteOperationId.value',
    "chatStore.onEvent('roomFilesDeleted', onDeleted)",
    "chatStore.onEvent('roomFilesDeleteFailed', onDeleteFailed)",
  ]) assert.ok(dialogSource.includes(marker), `missing deletion marker: ${marker}`)
  assert.ok(storeSource.includes("this._emit('roomFilesDeleteFailed', d)"))
  assert.ok(storeSource.includes('return chatWs.deleteRoomFiles(roomId, fileIds)'))
})

test('keeps close available while destructive work is pending', () => {
  assert.ok(dialogSource.includes('<button class="btn btn-primary" type="button" @click="closeDialog">{{ messages.close }}</button>'))
  assert.ok(!dialogSource.includes(':disabled="isDeleting" @click="closeDialog"'))
})
