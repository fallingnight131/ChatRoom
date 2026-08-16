import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomList.vue', import.meta.url), 'utf8')

test('names and contains room search and creation dialogs', () => {
  for (const marker of [
    'aria-label="搜索房间" aria-haspopup="dialog"',
    'aria-label="创建房间" aria-haspopup="dialog"',
    'aria-labelledby="room-search-title"',
    'aria-labelledby="room-create-title"',
    'initialFocusSelector: \'#room-search-keyword\'',
    'initialFocusSelector: \'#new-room-name\'',
    'active: showSearch',
    'active: showCreate',
  ]) assert.ok(source.includes(marker), `missing room dialog marker: ${marker}`)
})

test('uses native forms and clears optional room password before transport', () => {
  for (const marker of [
    '@submit.prevent="doSearch"',
    '@submit.prevent="createRoom"',
    'for="new-room-name"',
    'for="new-room-password"',
    'autocomplete="off"',
    'if (searching.value) return',
    'if (!showSearch.value) return',
  ]) assert.ok(source.includes(marker), `missing room form marker: ${marker}`)
  const capture = source.indexOf('const password = newRoomPassword.value')
  const clear = source.indexOf("newRoomPassword.value = ''", capture)
  const send = source.indexOf('chatWs.createRoom(roomName, password)', clear)
  assert.ok(capture >= 0 && capture < clear && clear < send)
  assert.ok(source.match(/function closeCreate\(\)[\s\S]*?newRoomPassword\.value = ''/))
})
