import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const chat = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')
const profile = readFileSync(new URL('../src/components/ProfileDialog.vue', import.meta.url), 'utf8')

test('renders authenticated shell chrome from the live locale catalog', () => {
  for (const marker of [
    'const shellMessages = computed(() => chatShellMessages(userStore.locale))',
    '{{ shellMessages.offlineBanner }}',
    '{{ shellMessages.friends }}',
    '{{ shellMessages.rooms }}',
    '{{ shellMessages.directMessage }} -',
    '{{ emptyStateMessage }}',
    '{{ shellMessages.memberList }}',
    '{{ shellMessages.connectionLost }}',
    '{{ shellMessages.signInAgain }}',
  ]) assert.ok(chat.includes(marker), `missing authenticated shell locale marker: ${marker}`)
})

test('keeps the authenticated locale selector in the shared user preference boundary', () => {
  assert.ok(profile.includes('userStore.setLocale($event.target.value)'))
  assert.ok(profile.includes('profileMessages(userStore.locale)'))
})

test('renders profile editing and local feedback from stable catalog keys', () => {
  for (const marker of [
    '{{ messages.title }}',
    '{{ messages.displayName }}',
    '{{ messages.userIdHint }}',
    '{{ messages.changePassword }}',
    '{{ messages.lowBandwidthDescription }}',
    '{{ messages.signOut }}',
    "uidErrorKey.value = 'invalidUserId'",
    "uidSuccessKey.value = 'userIdChanged'",
  ]) assert.ok(profile.includes(marker), `missing profile locale marker: ${marker}`)
})
