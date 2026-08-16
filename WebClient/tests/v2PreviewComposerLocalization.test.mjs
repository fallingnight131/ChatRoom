import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 reply and send composition from the active locale', () => {
  for (const marker of [
    'composerCatalogMessages(userStore.locale)', 'v2PreviewComposerMessages(userStore.locale)',
    'v2ComposerMessages.replyingTo(replyTarget.sequence)', 'v2ComposerMessages.cancelReply',
    'v2ComposerMessages.cancelReplyTitle', 'composerMessages.messageContent',
    ':placeholder="composerMessages.messagePlaceholder"', ':aria-label="composerMessages.messageBytes"',
    'v2ComposerMessages.mentionMember', 'v2ComposerMessages.sendTitle', 'composerMessages.send',
  ]) assert.ok(source.includes(marker), `missing V2 composer locale marker: ${marker}`)
})

test('formats the shared UTF-8 budget without changing send, reply, mention, or keyboard behavior', () => {
  for (const marker of [
    'messageTextBudget(draft.value)', 'draftBudget.value.withinBudget',
    'draftBudget.value.overage', 'draftBudget.value.maximum',
    '@keydown.enter.exact.prevent="sendMessage"', '@keydown.esc="cancelReplyFromKeyboard"',
    'application.sendText(text, mentions)', 'application.sendReply(replyTarget.value.id, text, mentions)',
    'serializeMentionAnchors(text, draftMentionAnchors.value)',
  ]) assert.ok(source.includes(marker), `missing V2 composer boundary marker: ${marker}`)
})
