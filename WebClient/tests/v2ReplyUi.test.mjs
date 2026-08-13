import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('exposes keyboard-operable reply composition and unavailable target rendering', () => {
  for (const marker of [
    '@click="startReply(message)"',
    'type="button" aria-label="取消回复"',
    'application.sendReply(replyTarget.value.id, text)',
    "return '原消息暂不可用'",
    "target.availability === 'recalled' ? '原消息已撤回'",
  ]) assert.ok(source.includes(marker), `missing V2 reply UI marker: ${marker}`)
})
