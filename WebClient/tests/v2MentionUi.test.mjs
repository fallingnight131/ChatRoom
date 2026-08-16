import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const view = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
const runtime = await readFile(new URL('../src/application/v2Runtime.ts', import.meta.url), 'utf8')

test('exposes keyboard-native participant selection and identity-preserving mention rendering', () => {
  for (const marker of [
    'aria-haspopup="dialog"',
    'aria-controls="v2-mention-picker"',
    ':aria-expanded="mentionPickerMode === \'draft\'"',
    'id="v2-mention-picker" ref="mentionPickerRef"',
    'role="listbox" :aria-label="mentionMessages.members"',
    'role="option"',
    'aria-selected="false"',
    ':aria-label="mentionMessages.close"',
    'serializeMentionAnchors(text, draftMentionAnchors.value)',
    'application.sendText(text, mentions)',
    'application.sendReply(replyTarget.value.id, text, mentions)',
    'application.editMessage(message.id, text, mentions)',
    ':title="v2TimelineMessages.accountTitle(segment.targetAccountId)"',
    'visibleParticipantFailure',
  ]) assert.ok(view.includes(marker), `missing V2 mention UI marker: ${marker}`)
})

test('moves focus through the non-modal participant picker and restores its trigger', () => {
  for (const marker of [
    "@click=\"openMentionPicker('draft', $event)\"",
    "@click=\"openMentionPicker('edit', $event)\"",
    "querySelector('[role=\"option\"]')",
    "querySelector('[data-mention-close]')",
    "['ArrowDown', 'ArrowUp', 'Home', 'End']",
    'current <= 0 ? options.length - 1 : current - 1',
    'options[next]?.focus()',
    'if (restoreFocus) nextTick(() => trigger?.focus())',
    'closeMentionPicker(false)',
  ]) assert.ok(view.includes(marker), `missing V2 mention focus marker: ${marker}`)
})

test('activates MESSAGE_MENTIONS only in the completed Web V2 composition', () => {
  assert.match(runtime, /enableMessageMentions: true/)
})
