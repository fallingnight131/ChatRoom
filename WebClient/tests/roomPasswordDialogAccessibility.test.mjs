import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/RoomPasswordDialog.vue', import.meta.url), 'utf8')

test('uses a labeled modal form with direct password focus and native submission', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="room-password-title" aria-describedby="room-password-description"',
    '@submit.prevent="submit"',
    'label for="room-password"',
    'autocomplete="off" required',
    'type="submit" :disabled="!password"',
    "initialFocusSelector: '#room-password'",
  ]) assert.ok(source.includes(marker), `missing room password marker: ${marker}`)
})

test('rejects empty submission and clears component plaintext before emitting', () => {
  assert.ok(source.includes('if (!password.value) return'))
  const capture = source.indexOf('const submittedPassword = password.value')
  const clear = source.indexOf("password.value = ''")
  const emit = source.indexOf("emit('submit', submittedPassword)")
  assert.ok(capture >= 0 && capture < clear && clear < emit)
})
