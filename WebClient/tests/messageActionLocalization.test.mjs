import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')

test('renders V1 message actions from the active locale catalog', () => {
  for (const marker of [
    'messageActionMessages(userStore.locale)',
    ':aria-label="actionMessages.menu"',
    'actionMessages.copyText',
    'actionMessages.previewFile',
    'actionMessages.downloadFile',
    'actionMessages.forward',
    'actionMessages.recall',
    'actionMessages.deleteMessage',
    'actionMessages.clearAll',
    'actionMessages.deleteOlder',
    'actionMessages.deleteRecent',
  ]) assert.ok(source.includes(marker), `missing message-action locale marker: ${marker}`)
})

test('localizes forwarding and destructive-action feedback without changing commands', () => {
  for (const marker of [
    'actionMessages.value.selectForwardTarget',
    'actionMessages.value.forwardSubmittedPrefix',
    'actionMessages.value.forwardFailed',
    'confirm(actionMessages.value.confirmDelete)',
    'confirm(actionMessages.value.confirmClear)',
    'prompt(actionMessages.value.deleteOlderPrompt',
    'prompt(actionMessages.value.deleteRecentPrompt',
    'actionMessages.value.invalidDays',
    "chatWs.deleteMessages(chatStore.currentRoomId, 'selected', [msg.id])",
    "chatWs.deleteMessages(chatStore.currentRoomId, 'before', [], cutoff)",
    "chatWs.deleteMessages(chatStore.currentRoomId, 'after', [], cutoff)",
  ]) assert.ok(source.includes(marker), `missing localized action marker: ${marker}`)
})
