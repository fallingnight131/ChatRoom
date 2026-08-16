import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomSettingsDialog.vue', import.meta.url), 'utf8')

test('renders the complete room settings surface from the active locale catalog', () => {
  for (const marker of [
    'roomSettingsMessages(userStore.locale)', 'messages.title', 'messages.roomName', 'messages.roomId',
    'messages.administrator', 'messages.maxFileSize', 'messages.totalFileSpace', 'messages.maxFileCount',
    'messages.maxMembers', 'messages.roomAvatar', 'messages.selectImage', 'messages.renameRoom',
    'messages.roomPassword', 'messages.passwordSet', 'messages.manageMembers', 'messages.messageManagement',
    'messages.danger', 'messages.limitsTitle', 'messages.developerKey', 'messages.saveLimits',
    'messages.leaveRoom', 'messages.close',
  ]) assert.ok(source.includes(marker), `missing room-settings locale marker: ${marker}`)
})

test('localizes feedback and destructive confirmation without changing server command identities', () => {
  for (const marker of [
    'messages.value.avatarTooLarge', 'messages.value.avatarSaved', 'messages.value.nameSaved',
    'messages.value.passwordSaved', 'messages.value.passwordRemoved', 'messages.value.cleanupPrefix',
    'messages.value.limitsSaved', 'messages.value.limitsPositive', 'messages.value.totalTooSmall',
    'messages.value.developerKeyRequired', 'messages.value.kickPrefix', 'messages.value.clearConfirm',
    'messages.value.deletePrefix', 'messages.value.leavePrefix',
    'chatWs.uploadRoomAvatar(chatStore.currentRoomId, base64)',
    'chatWs.renameRoom(chatStore.currentRoomId, newName.value.trim())',
    'chatWs.setRoomPassword(chatStore.currentRoomId, roomPassword.value)',
    'chatWs.getRoomPassword(chatStore.currentRoomId)',
    'chatWs.setRoomSettings(',
    'chatWs.setAdmin(chatStore.currentRoomId, selectedUser.value, !isSelectedAdmin.value)',
    'chatWs.kickUser(chatStore.currentRoomId, selectedUser.value)',
    "chatWs.deleteMessages(chatStore.currentRoomId, 'all')",
    'chatWs.deleteRoom(chatStore.currentRoomId, chatStore.currentRoomName)',
    'chatWs.leaveRoom(chatStore.currentRoomId)',
  ]) assert.ok(source.includes(marker), `missing room-settings boundary marker: ${marker}`)
})
