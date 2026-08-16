import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const userInfo = readFileSync(new URL('../src/components/UserInfoDialog.vue', import.meta.url), 'utf8')
const avatarPreview = readFileSync(new URL('../src/components/AvatarPreviewDialog.vue', import.meta.url), 'utf8')

test('renders user information and administrator actions from the active catalog', () => {
  for (const marker of [
    'userInfoMessages(userStore.locale)', 'messages.title', 'messages.previewAvatarPrefix',
    'messages.name', 'messages.userId', 'messages.status', 'messages.online', 'messages.offline',
    'messages.role', 'messages.admin', 'messages.member', 'messages.adminActions',
    'messages.setAdmin', 'messages.unsetAdmin', 'messages.kick', 'messages.close',
    'messages.value.kickConfirmPrefix', 'messages.value.kickConfirmSuffix',
  ]) assert.ok(userInfo.includes(marker), `missing user-info locale marker: ${marker}`)
})

test('preserves server-authoritative administrator command identity', () => {
  for (const marker of [
    'chatWs.setAdmin(chatStore.currentRoomId, props.user.username, true)',
    'chatWs.setAdmin(chatStore.currentRoomId, props.user.username, false)',
    'chatWs.kickUser(chatStore.currentRoomId, props.user.username)',
  ]) assert.ok(userInfo.includes(marker), `missing administrator command marker: ${marker}`)
})

test('localizes the nested avatar preview with a catalog fallback description', () => {
  for (const marker of [
    'userInfoMessages(userStore.locale)', 'messages.avatarPreview', 'messages.closePreview',
    'props.alt || messages.value.largeAvatar', ':alt="imageAlt"',
  ]) assert.ok(avatarPreview.includes(marker), `missing avatar-preview locale marker: ${marker}`)
})
