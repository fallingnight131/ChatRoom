import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')

test('exposes a non-dismissible forced-offline alert dialog', () => {
  for (const marker of [
    'role="alertdialog"',
    'aria-modal="true" aria-labelledby="force-offline-title"',
    'aria-describedby="force-offline-description"',
    'initialFocusSelector: \'#force-offline-login\'',
    'active: forceOfflineActive',
    'canClose: () => false',
    '{{ shellMessages.signInAgain }}</button>',
  ]) assert.ok(source.includes(marker), `missing force-offline marker: ${marker}`)
})

test('cleans attachment and in-memory identity before returning to login', () => {
  const handler = source.slice(source.indexOf('function onForceOfflineConfirm()'))
  const attachment = handler.indexOf('chatStore.endAttachmentSession()')
  const logout = handler.indexOf('userStore.onLogout()')
  const navigate = handler.indexOf("router.push('/login')")
  assert.ok(attachment >= 0 && attachment < logout && logout < navigate)
})
