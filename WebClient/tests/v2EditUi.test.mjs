import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(
  new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
const runtime = await readFile(
  new URL('../src/application/v2Runtime.ts', import.meta.url), 'utf8')

test('exposes author-only accessible message editing and explicit conflict recovery', () => {
  assert.match(view, /message\.senderAccountId === snapshot\.value\.session\?\.accountId/)
  assert.match(view, /<label :for="`edit-\$\{message\.id\}`">编辑消息<\/label>/)
  assert.match(view, /@keydown\.esc="cancelEditFromKeyboard"/)
  assert.match(view, /title="取消编辑（Esc）"/)
  assert.match(view, /if \(!editingMessageId\.value\) return/)
  assert.match(view, /role="status">正在保存编辑/)
  assert.match(view, /role="alert">其他设备已修改此消息/)
  assert.match(view, />\s*基于新版本重试\s*</)
  assert.match(view, />放弃草稿<\/button>/)
  assert.match(view, /message\.contentRevision > 0/)
  assert.match(view, /application\.editMessage\(message\.id, text, mentions\)/)
  assert.match(view, /application\.rebaseEdit\(operationId\)/)
})

test('activates MESSAGE_EDITS only in the completed Web V2 runtime composition', () => {
  assert.match(runtime, /enableMessageEdits: true/)
})
