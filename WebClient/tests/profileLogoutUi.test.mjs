import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ProfileDialog.vue', import.meta.url), 'utf8')

test('cleans the attachment session and user state through initialized stores before navigation', () => {
  for (const marker of [
    "import { useChatStore } from '../stores/chat'",
    'const chatStore = useChatStore()',
    'chatWs.logout()',
    'chatStore.endAttachmentSession()',
    'userStore.onLogout()',
    "router.push('/login')",
  ]) assert.ok(source.includes(marker), `missing profile logout marker: ${marker}`)

  assert.ok(source.indexOf('chatStore.endAttachmentSession()') < source.indexOf('userStore.onLogout()'))
  assert.ok(source.indexOf('userStore.onLogout()') < source.indexOf("router.push('/login')"))
})
