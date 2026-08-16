import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomList.vue', import.meta.url), 'utf8')

test('uses native room rows and exposes the current conversation', () => {
  for (const marker of [
    '<button v-for="room in chatStore.rooms"',
    ':aria-current="room.roomId === chatStore.currentRoomId',
    '@contextmenu.prevent="openRoomMenuFromPointer($event, room)"',
    ':alt="`${room.roomName} 的房间头像`"',
  ]) assert.ok(source.includes(marker), `missing room row marker: ${marker}`)
})

test('uses the shared keyboard menu for server-authoritative room actions', () => {
  for (const marker of [
    'role="menu"',
    'aria-label="房间操作"',
    'role="menuitem"',
    'useKeyboardContextMenu()',
    'openFromKeyboard: openRoomMenuFromKeyboard',
    'closeRoomMenu(true)',
    'canManageRoom(roomMenu.item)',
  ]) assert.ok(source.includes(marker), `missing room menu marker: ${marker}`)
})
