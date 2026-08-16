import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomList.vue', import.meta.url), 'utf8')

test('uses native room rows and exposes the current conversation', () => {
  for (const marker of [
    '<button v-for="room in chatStore.rooms"',
    ':aria-current="room.roomId === chatStore.currentRoomId',
    '@contextmenu.prevent="openRoomMenuFromPointer($event, room)"',
    ':alt="roomAvatarLabel(room.roomName)"',
  ]) assert.ok(source.includes(marker), `missing room row marker: ${marker}`)
})

test('uses the shared keyboard menu for server-authoritative room actions', () => {
  for (const marker of [
    'role="menu"',
    ':aria-label="messages.menu"',
    'role="menuitem"',
    'useKeyboardContextMenu()',
    'openFromKeyboard: openRoomMenuFromKeyboard',
    'closeRoomMenu(true)',
    'canManageRoom(roomMenu.item)',
  ]) assert.ok(source.includes(marker), `missing room menu marker: ${marker}`)
})

test('renders room navigation and actions from the live locale catalog', () => {
  for (const marker of [
    'roomListMessages(userStore.locale)',
    '{{ messages.title }}',
    '{{ messages.searchRooms }}',
    'memberCountLabel(r.memberCount)',
    '{{ messages.settings }}',
    '{{ messages.files }}',
  ]) assert.ok(source.includes(marker), `missing room locale marker: ${marker}`)
})
