import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')

test('exposes the friend and room switcher as related tabs and panels', () => {
  for (const marker of [
    'role="tablist" :aria-label="shellMessages.conversationTypes"',
    'id="friends-tab"',
    'role="tab"',
    ':aria-selected="activeTab === \'friends\'"',
    ':tabindex="activeTab === \'friends\' ? 0 : -1"',
    'aria-controls="friends-panel"',
    'id="friends-panel" role="tabpanel" aria-labelledby="friends-tab"',
    'id="rooms-panel" role="tabpanel"',
    '@keydown="onTabKeydown"',
    "['ArrowLeft', 'ArrowRight', 'Home', 'End']",
    'selectNavigationTab(nextTab)',
  ]) assert.ok(source.includes(marker), `missing chat tab marker: ${marker}`)
})

test('names mobile conversation, member, settings, and close controls', () => {
  for (const marker of [
    ':aria-label="shellMessages.openConversations"',
    ':aria-label="shellMessages.openMembers"',
    ':aria-label="shellMessages.openRoomSettings" aria-haspopup="dialog"',
    ':aria-label="shellMessages.closeMembers"',
    'class="btn-icon theme-btn" type="button"',
  ]) assert.ok(source.includes(marker), `missing chat control marker: ${marker}`)
})
