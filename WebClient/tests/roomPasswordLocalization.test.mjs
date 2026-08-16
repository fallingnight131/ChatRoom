import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const dialog = readFileSync(new URL('../src/components/RoomPasswordDialog.vue', import.meta.url), 'utf8')
const chatView = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')

test('renders the protected-room prompt from the active locale catalog', () => {
  for (const marker of [
    'roomPasswordMessages(userStore.locale)', 'messages.title', 'messages.description',
    'messages.label', 'messages.placeholder', 'messages.cancel', 'messages.join',
  ]) assert.ok(dialog.includes(marker), `missing room-password locale marker: ${marker}`)
})

test('keeps plaintext ephemeral and joins the original server room identity', () => {
  const capture = dialog.indexOf('const submittedPassword = password.value')
  const clear = dialog.indexOf("password.value = ''")
  const emit = dialog.indexOf("emit('submit', submittedPassword)")
  assert.ok(dialog.includes('if (!password.value) return'))
  assert.ok(capture >= 0 && capture < clear && clear < emit)
  assert.ok(chatView.includes('const roomId = passwordRoomData.value?.roomId'))
  assert.ok(chatView.includes('if (roomId != null) chatWs.joinRoom(roomId, password)'))
  assert.ok(chatView.includes('@close="closePasswordPrompt"'))
  assert.ok(chatView.includes('passwordRoomData.value = null'))
})
