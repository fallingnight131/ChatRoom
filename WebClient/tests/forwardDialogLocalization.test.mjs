import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ForwardDialog.vue', import.meta.url), 'utf8')

test('renders the forwarding picker from the active locale catalog', () => {
  for (const marker of [
    'forwardDialogMessages(userStore.locale)',
    'messages.title',
    'messages.targetType',
    'messages.friends',
    'messages.rooms',
    'messages.search',
    'messages.searchFriends',
    'messages.searchRooms',
    'messages.selectAll',
    'messages.selectedPrefix',
    'messages.online',
    'messages.offline',
    'messages.noFriends',
    'messages.noRooms',
    'messages.cancel',
    'messages.submitting',
    'messages.confirm',
  ]) assert.ok(source.includes(marker), `missing forwarding locale marker: ${marker}`)
})

test('preserves identity-safe target selection and pending-state boundaries', () => {
  for (const marker of [
    "targets.push({ type: 'room', roomId })",
    "targets.push({ type: 'friend', username })",
    "if (props.submitting || selectedCount.value === 0) return",
    'canClose: () => !props.submitting',
  ]) assert.ok(source.includes(marker), `missing forwarding boundary marker: ${marker}`)
})
