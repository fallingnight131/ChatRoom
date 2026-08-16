import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 editing, conflict recovery, and local failures from the active locale', () => {
  for (const marker of [
    'v2PreviewEditMessages(userStore.locale)', 'editMessages.formLabel',
    ':aria-label="editMessages.bytes"', 'editMessages.save', 'editMessages.cancelTitle',
    'editMessages.saving', 'editMessages.conflict', 'editMessages.serverVersion',
    'editMessages.rebase', 'editMessages.discard', 'editMessages.saveFailed',
    'editMessages.retry', 'editMessages.editLabel(message.sequence)',
    'editMessages.value.unavailable', 'editMessages.value.rebaseUnavailable',
  ]) assert.ok(source.includes(marker), `missing V2 edit locale marker: ${marker}`)
})

test('preserves author and availability gates, UTF-8 budget, overlay, revision, mentions, and operation identity', () => {
  for (const marker of [
    'message.senderAccountId === snapshot.value.session?.accountId',
    "message.deliveryState === 'accepted' && message.availability === 'available'",
    'messageTextBudget(editDraft.value)', 'editBudget.value.withinBudget',
    'editCommand(message)?.proposedContent || message.content', 'message.contentRevision > 0',
    'serializeMentionAnchors(text, editMentionAnchors.value)',
    'application.editMessage(message.id, text, mentions)', 'application.retryEdit(operationId)',
    'application.rebaseEdit(operationId)', 'application.discardEdit(operationId)',
  ]) assert.ok(source.includes(marker), `missing V2 edit boundary marker: ${marker}`)
})
