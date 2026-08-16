import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ProfileDialog.vue', import.meta.url), 'utf8')

test('exposes a labeled modal with bounded keyboard focus and trigger restoration', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="profile-dialog-title"',
    'tabindex="-1" @keydown="onDialogKeydown"',
    'useModalKeyboardBoundary({',
    "onClose: () => emit('close')",
  ]) assert.ok(source.includes(marker), `missing profile dialog marker: ${marker}`)
})

test('makes avatar replacement a native keyboard action with an accessible file input', () => {
  for (const marker of [
    'type="button" class="profile-avatar-wrap" aria-label="更换头像"',
    'alt="当前头像"',
    'class="visually-hidden" tabindex="-1"',
    'aria-label="选择新头像"',
  ]) assert.ok(source.includes(marker), `missing avatar accessibility marker: ${marker}`)
})
