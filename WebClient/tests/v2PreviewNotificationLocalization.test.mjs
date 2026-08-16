import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders the gated notification preference and generic copy from the active locale', () => {
  for (const marker of [
    'v-if="snapshot.notificationsEnabled"', 'v2PreviewNotificationMessages(userStore.locale)',
    'notificationMessages.enable', 'notificationMessages.disable',
    'notificationMessages.description', 'notificationStateLabel',
    'notificationMessages.sessionOnly', 'notificationMessages.value',
  ]) assert.ok(source.includes(marker), `missing V2 notification locale marker: ${marker}`)
})

test('keeps permission on an explicit native action and navigation on stable identity', () => {
  for (const marker of [
    '@click="toggleNotifications"', 'enableFromUserGesture()',
    "subscribeRemoteMessages(candidate =>", 'candidate, {',
    "visibleConversationId: snapshot.value.activeConversationId || ''",
    'runtime.application.openConversation(conversationId)',
    'document.visibilityState', 'document.hasFocus()',
  ]) assert.ok(source.includes(marker), `missing V2 notification boundary marker: ${marker}`)
  assert.doesNotMatch(source, /present\([^)]*(message\.content|candidate\.content)/)
})
