import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('exposes keyboard-operable reply composition and unavailable target rendering', () => {
  for (const marker of [
    '@click="startReply(message)"',
    'type="button" :aria-label="v2ComposerMessages.cancelReply"',
    '@keydown.esc="cancelReplyFromKeyboard"',
    'if (!replyTarget.value) return',
    ':title="v2ComposerMessages.cancelReplyTitle"',
    'application.sendReply(replyTarget.value.id, text, mentions)',
    ':aria-label="basicActionMessages.replyLabel(message.sequence)"',
    'return v2TimelineMessages.value.originalUnavailable',
    'target.availability === \'recalled\' ? v2TimelineMessages.value.originalRecalled',
  ]) assert.ok(source.includes(marker), `missing V2 reply UI marker: ${marker}`)
})
