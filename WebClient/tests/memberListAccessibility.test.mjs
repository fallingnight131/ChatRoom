import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/UserList.vue', import.meta.url), 'utf8')

test('groups online and offline members into named lists', () => {
  for (const marker of [
    'aria-labelledby="online-members-title"',
    'id="online-members-title"',
    'aria-labelledby="offline-members-title"',
    'class="member-list" role="list"',
    'role="status" aria-live="polite"',
  ]) assert.ok(source.includes(marker), `missing member group marker: ${marker}`)
})

test('uses named native member buttons without relying on status dots', () => {
  for (const marker of [
    'class="user-item" type="button" :aria-label="memberLabel(user, true)"',
    'class="user-item offline" type="button" :aria-label="memberLabel(user, false)"',
    ':alt="avatarLabel(user.displayName || user.username)"',
    'class="online-dot online" aria-hidden="true"',
    'online ? messages.value.online : messages.value.offline',
    'user.isAdmin ? `${separator}${messages.value.admin}` : \'\'',
  ]) assert.ok(source.includes(marker), `missing member action marker: ${marker}`)
})

test('renders member groups, roles, and action names from the live locale catalog', () => {
  for (const marker of [
    'memberListMessages(userStore.locale)',
    '{{ onlineCountLabel }}',
    '{{ messages.onlineMembers }}',
    '{{ offlineCountLabel }}',
    '{{ messages.admin }}',
  ]) assert.ok(source.includes(marker), `missing member locale marker: ${marker}`)
})
