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
    'onClose: () => {',
    "emit('close')",
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

test('associates profile fields with labels and announced feedback', () => {
  for (const marker of [
    '<label for="profile-display-name">',
    'id="profile-display-name"',
    '<label for="profile-user-id">',
    'id="profile-user-id"',
    'aria-describedby="profile-user-id-hint profile-user-id-feedback"',
    'id="profile-user-id-feedback" aria-live="polite"',
    'class="uid-error" role="alert"',
    'class="uid-success" role="status"',
  ]) assert.ok(source.includes(marker), `missing profile-field marker: ${marker}`)
})

test('uses a native password disclosure and clears component secrets on exit', () => {
  for (const marker of [
    'type="button" class="section-header" aria-controls="profile-password-panel"',
    '<div id="profile-password-panel">',
    ':aria-expanded="showPasswordChange" @click="togglePasswordChange"',
    '<form v-if="showPasswordChange" id="profile-password-form"',
    '@submit.prevent="changePassword"',
    'autocomplete="current-password" required',
    'autocomplete="new-password" required',
    'type="submit" class="btn btn-primary"',
    'if (!showPasswordChange.value) clearPasswordFields()',
    'clearPasswordFields()\n    emit(\'close\')',
  ]) assert.ok(source.includes(marker), `missing password-form marker: ${marker}`)
})
