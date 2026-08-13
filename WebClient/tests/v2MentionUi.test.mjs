import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
const runtime = await readFile(new URL('../src/application/v2Runtime.ts', import.meta.url), 'utf8')

test('exposes keyboard-native participant selection and identity-preserving mention rendering', () => {
  for (const marker of [
    'aria-haspopup="dialog"',
    'role="listbox" aria-label="会话成员"',
    'role="option"',
    'aria-label="关闭成员选择器"',
    'serializeMentionAnchors(text, draftMentionAnchors.value)',
    'application.sendText(text, mentions)',
    'application.sendReply(replyTarget.value.id, text, mentions)',
    'application.editMessage(message.id, text, mentions)',
    ':title="`账号 ${segment.targetAccountId}`"',
    'snapshot.participantFailure',
  ]) assert.ok(view.includes(marker), `missing V2 mention UI marker: ${marker}`)
})

test('activates MESSAGE_MENTIONS only in the completed Web V2 composition', () => {
  assert.match(runtime, /enableMessageMentions: true/)
})
