import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders the V2 preview entry, authentication, and directory from the active locale', () => {
  for (const marker of [
    'v2PreviewShellMessages(userStore.locale)', 'shellMessages.engineeringPreview',
    'shellMessages.loadingSecure', 'shellMessages.backV1Login', 'shellMessages.isolatedTest',
    'shellMessages.loginTitle', 'shellMessages.credentialsMemory', 'shellMessages.userId',
    'shellMessages.password', 'shellMessages.authenticating', 'shellMessages.connectingSecure',
    'shellMessages.conversationNavigation', 'shellMessages.loginDevices',
    'shellMessages.availableConversations', 'shellMessages.direct', 'shellMessages.group',
    'shellMessages.noConversations', 'shellMessages.loadMoreConversations',
    'shellMessages.messageRegion', 'shellMessages.selectConversation', 'shellMessages.cacheSync',
  ]) assert.ok(source.includes(marker), `missing V2 shell locale marker: ${marker}`)
})

test('localizes connection state without changing runtime or transient credential boundaries', () => {
  for (const marker of [
    'shellMessages.value.runtimeUnavailable', 'shellMessages.value.idle',
    'shellMessages.value.negotiating', 'shellMessages.value.authenticated',
    'shellMessages.value.reconnectWait', 'shellMessages.value.unknownState',
    'runtime.application.start()', "password.value = ''", 'passwordBytes.fill(0)',
  ]) assert.ok(source.includes(marker), `missing V2 shell boundary marker: ${marker}`)
})
