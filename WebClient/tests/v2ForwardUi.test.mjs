import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
const runtime = await readFile(new URL('../src/application/v2Runtime.ts', import.meta.url), 'utf8')

test('keeps an accessible forwarding picker behind the application capability gate', () => {
  for (const marker of [
    'v-if="snapshot.forwardingEnabled',
    'aria-haspopup="dialog"',
    'aria-labelledby="forward-dialog-title"',
    'aria-describedby="forward-dialog-description"',
    'ref="forwardDialogRef"',
    '@keydown="onForwardDialogKeydown"',
    "canClose: () => !forwardPending.value",
    "initialFocusSelector: '#forward-dialog-close'",
    'role="listbox" :aria-label="forwardMessages.targets"',
    'await runtimeRef.value.application.forwardMessage(',
    'message.forwarded',
    'forwardMessages.description',
  ]) assert.ok(view.includes(marker), `missing V2 forward UI marker: ${marker}`)
})

test('activates Web forwarding only through one exact default-off build flag', () => {
  for (const marker of [
    'VITE_CHAT_V2_MESSAGE_FORWARDING',
    'forwardingFlag === true || forwardingFlag === "true"',
    'enableMessageForwarding: forwardingEnabled',
  ]) assert.ok(runtime.includes(marker), `missing Web forwarding activation marker: ${marker}`)
  assert.equal(runtime.includes('enableMessageForwarding: true'), false)
})
