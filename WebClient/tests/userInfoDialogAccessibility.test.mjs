import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const userInfoSource = readFileSync(new URL('../src/components/UserInfoDialog.vue', import.meta.url), 'utf8')
const previewSource = readFileSync(new URL('../src/components/AvatarPreviewDialog.vue', import.meta.url), 'utf8')

test('contains the user information dialog and exposes avatar preview as a native action', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="user-info-title"',
    'tabindex="-1" @keydown="onDialogKeydown"',
    'type="button" class="avatar-preview-trigger"',
    ':aria-label="`预览 ${userDisplayName} 的头像`"',
    'useModalKeyboardBoundary({',
    'canClose: () => !showAvatarPreview.value',
  ]) assert.ok(userInfoSource.includes(marker), `missing user info marker: ${marker}`)
})

test('contains the nested avatar preview and restores focus through the shared boundary', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="avatar-preview-title"',
    '@keydown.stop="onDialogKeydown"',
    ':alt="alt"',
    'useModalKeyboardBoundary({',
    "onClose: () => emit('close')",
  ]) assert.ok(previewSource.includes(marker), `missing avatar preview marker: ${marker}`)
})
