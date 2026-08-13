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
    'role="listbox" aria-label="转发目标会话"',
    'await runtimeRef.value.application.forwardMessage(',
    'message.forwarded',
    '服务器会复制最新的消息内容，不会暴露来源会话',
  ]) assert.ok(view.includes(marker), `missing V2 forward UI marker: ${marker}`)
})

test('does not activate forwarding in the Web V2 runtime composition yet', () => {
  assert.equal(runtime.includes('enableMessageForwarding: true'), false)
})
