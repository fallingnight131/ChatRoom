import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 optimistic reaction controls and feedback from the active locale', () => {
  for (const marker of [
    'v2PreviewReactionMessages(userStore.locale)', 'reactionMessages.groupLabel(message.sequence)',
    'reactionMessages.countLabel(reaction.label, reactionCount(message, reaction.kind))',
    'reactionMessages.retryLabel(message.sequence)', 'reactionMessages.retry',
    'reactionMessages.value.like', 'reactionMessages.value.angry',
    'reactionMessages.value.unavailable', 'reactionMessages.value.retryUnavailable',
  ]) assert.ok(source.includes(marker), `missing V2 reaction locale marker: ${marker}`)
})

test('preserves enum choices, actor identity, per-reaction pending state, and stable retry identity', () => {
  for (const marker of [
    'MessageReactionKind.LIKE', 'MessageReactionKind.ANGRY',
    'item.actorAccountIds.includes(snapshot.value.session?.accountId)',
    'command.messageId === message.id', 'command.reaction === reaction',
    "command.deliveryState === 'sending'", 'application.setReaction(message.id, reaction)',
    'application.retryReaction(clientOperationId)',
  ]) assert.ok(source.includes(marker), `missing V2 reaction boundary marker: ${marker}`)
})
