import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ForwardDialog.vue', import.meta.url), 'utf8')

test('uses the shared modal boundary and exposes named forwarding controls', () => {
  for (const marker of [
    'role="dialog" aria-modal="true"',
    'aria-labelledby="forward-dialog-title"',
    'tabindex="-1" @keydown="onDialogKeydown"',
    ':aria-pressed="activeTab === \'friends\'"',
    ':aria-pressed="activeTab === \'rooms\'"',
    'for="forward-target-search"',
    'id="forward-target-search"',
    'useModalKeyboardBoundary({',
  ]) assert.ok(source.includes(marker), `missing forwarding dialog marker: ${marker}`)
})

test('prevents dismissal and duplicate confirmation while forwarding is pending', () => {
  for (const marker of [
    'canClose: () => !props.submitting',
    ':disabled="submitting" @click="closeDialog"',
    'if (props.submitting || selectedCount.value === 0) return',
  ]) assert.ok(source.includes(marker), `missing forwarding pending-state marker: ${marker}`)
})
