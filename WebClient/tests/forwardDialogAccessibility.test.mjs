import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ForwardDialog.vue', import.meta.url), 'utf8')

test('uses the shared modal boundary and exposes named forwarding controls', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="forward-dialog-title"',
    'tabindex="-1" @keydown="onDialogKeydown"',
    'role="tablist" aria-label="转发目标类型"',
    'id="forward-friends-tab" aria-controls="forward-friends-panel"',
    'id="forward-rooms-tab" aria-controls="forward-rooms-panel"',
    ':aria-selected="activeTab === \'friends\'"',
    ':aria-selected="activeTab === \'rooms\'"',
    'role="tabpanel" aria-labelledby="forward-friends-tab"',
    'role="tabpanel"',
    'aria-labelledby="forward-rooms-tab"',
    'for="forward-target-search"',
    'id="forward-target-search"',
    'useModalKeyboardBoundary({',
  ]) assert.ok(source.includes(marker), `missing forwarding dialog marker: ${marker}`)
})

test('uses roving native tabs with arrow and boundary-key navigation', () => {
  for (const marker of [
    'ref="friendTab" type="button"',
    'ref="roomTab" type="button"',
    ':tabindex="activeTab === \'friends\' ? 0 : -1"',
    ':tabindex="activeTab === \'rooms\' ? 0 : -1"',
    "['ArrowLeft', 'ArrowRight', 'Home', 'End']",
    "selectTab(tab === 'friends' ? 'rooms' : 'friends', true)",
  ]) assert.ok(source.includes(marker), `missing forwarding tab marker: ${marker}`)
})

test('prevents dismissal and duplicate confirmation while forwarding is pending', () => {
  for (const marker of [
    'canClose: () => !props.submitting',
    'type="button" class="btn btn-secondary" :disabled="submitting"',
    '@click="closeDialog">取消</button>',
    'if (props.submitting || selectedCount.value === 0) return',
  ]) assert.ok(source.includes(marker), `missing forwarding pending-state marker: ${marker}`)
})
