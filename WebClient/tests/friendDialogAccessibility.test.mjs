import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const friendSource = readFileSync(new URL('../src/components/FriendList.vue', import.meta.url), 'utf8')
const boundarySource = readFileSync(new URL('../src/ui/useModalKeyboardBoundary.ts', import.meta.url), 'utf8')

test('supports focus cycles for conditional dialogs without changing independent consumers', () => {
  for (const marker of [
    'active?: Readonly<Ref<boolean>>',
    'if (active) {',
    'const stop = watch(active, (isActive)',
    'if (isActive) activateFocusBoundary()',
    'else deactivateFocusBoundary()',
    'onMounted(activateFocusBoundary)',
  ]) assert.ok(boundarySource.includes(marker), `missing conditional boundary marker: ${marker}`)
})

test('names and contains friend search and pending-request dialogs', () => {
  for (const marker of [
    'aria-label="搜索好友" aria-haspopup="dialog"',
    'aria-labelledby="add-friend-title"',
    'aria-labelledby="pending-friend-title"',
    'initialFocusSelector: \'#friend-search-keyword\'',
    'active: showAddFriend',
    'active: showPendingDialog',
    '@submit.prevent="doSearch"',
    'if (searching.value) return',
    'if (!showAddFriend.value) return',
  ]) assert.ok(friendSource.includes(marker), `missing friend dialog marker: ${marker}`)
})
