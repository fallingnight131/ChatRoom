import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/FriendList.vue', import.meta.url), 'utf8')
const menuSource = readFileSync(new URL('../src/ui/useKeyboardContextMenu.ts', import.meta.url), 'utf8')

test('uses native friend rows and exposes the current conversation', () => {
  for (const marker of [
    '<button v-for="fr in chatStore.friends"',
    'type="button"',
    ':aria-current="chatStore.isFriendChat',
    '@contextmenu.prevent="openFriendMenuFromPointer($event, fr)"',
    ':alt="avatarLabel(fr.displayName || fr.username)"',
  ]) assert.ok(source.includes(marker), `missing friend row marker: ${marker}`)
})

test('opens and navigates a keyboard menu with focus restoration', () => {
  for (const marker of [
    'role="menu"',
    'role="menuitem"',
    'useKeyboardContextMenu()',
    'openFromPointer: openFriendMenuFromPointer',
    'openFromKeyboard: openFriendMenuFromKeyboard',
  ]) assert.ok(source.includes(marker), `missing friend menu marker: ${marker}`)
  for (const marker of [
    'event.key !== "ContextMenu"',
    'event.shiftKey && event.key === "F10"',
    '["ArrowDown", "ArrowUp", "Home", "End"]',
    'if (event.key === "Escape")',
    'if (restoreFocus) nextTick(() => restoreTarget?.focus())',
  ]) assert.ok(menuSource.includes(marker), `missing shared menu marker: ${marker}`)
})

test('renders friend navigation and actions from the live locale catalog', () => {
  for (const marker of [
    'friendListMessages(userStore.locale)',
    '{{ messages.title }}',
    'fr.isOnline ? messages.online : messages.offline',
    ':aria-label="messages.menu"',
    '{{ messages.viewInfo }}',
    'messages.value.removeConfirmPrefix',
  ]) assert.ok(source.includes(marker), `missing friend locale marker: ${marker}`)
})
