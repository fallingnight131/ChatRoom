import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomSettingsDialog.vue', import.meta.url), 'utf8')

test('uses the shared modal boundary and labels room setting controls', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="room-settings-title"',
    ':aria-busy="hasPendingOperation"',
    '@keydown="onDialogKeydown"',
    'for="room-settings-password"',
    'for="room-developer-key"',
    'useModalKeyboardBoundary({',
  ]) assert.ok(source.includes(marker), `missing room settings marker: ${marker}`)
})

test('keeps operator secrets out of autofill and clears component state', () => {
  assert.ok(source.match(/id="room-settings-password"[\s\S]*?autocomplete="off"/))
  assert.ok(source.match(/id="room-developer-key"[\s\S]*?autocomplete="off"/))
  const capture = source.indexOf('pendingDeveloperKey.value = developerKey.value')
  const clear = source.indexOf("developerKey.value = ''", capture)
  const send = source.indexOf('pendingDeveloperKey.value,', clear)
  assert.ok(capture >= 0 && capture < clear && clear < send)
  assert.ok(source.includes("pendingDeveloperKey.value = ''"))
})

test('hides operator settings from non-admins and rejects duplicate async writes', () => {
  assert.ok(source.includes('<div v-if="chatStore.isAdmin" class="setting-section">'))
  for (const guard of [
    'if (pendingAvatarUpload.value) return',
    'if (pendingRename.value) return',
    'if (pendingPasswordSave.value) return',
    'if (pendingSaveLimits.value) return',
  ]) assert.ok(source.includes(guard), `missing pending guard: ${guard}`)
})
