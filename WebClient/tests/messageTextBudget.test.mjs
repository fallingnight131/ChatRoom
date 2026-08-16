import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  MAX_MESSAGE_TEXT_BYTES,
  messageTextBudget,
  messageTextBudgetLabel
} from '../src/messaging/messageTextBudget.js'

test('measures the shared message limit in UTF-8 bytes', () => {
  const exact = messageTextBudget('é'.repeat(32_768))
  assert.equal(exact.bytes, MAX_MESSAGE_TEXT_BYTES)
  assert.equal(exact.withinBudget, true)
  assert.match(messageTextBudgetLabel('é'.repeat(32_768)), /^65536 \/ 65536/)

  const oversized = messageTextBudget('é'.repeat(32_769))
  assert.equal(oversized.bytes, MAX_MESSAGE_TEXT_BYTES + 2)
  assert.equal(oversized.withinBudget, false)
  assert.equal(oversized.overage, 2)
  assert.match(messageTextBudgetLabel('é'.repeat(32_769)), /超过上限 2 字节/)
})

test('uses the shared UTF-8 budget in V1 and V2 Web composers', async () => {
  const input = await readFile(
    new URL('../src/components/InputArea.vue', import.meta.url), 'utf8')
  const preview = await readFile(
    new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
  assert.match(input, /:disabled="!canSendText"/)
  assert.match(input, /!textBudget\.value\.withinBudget/)
  assert.match(preview, /!draftBudget\.value\.withinBudget/)
  assert.match(preview, /!editBudget\.value\.withinBudget/)
  assert.doesNotMatch(preview, /maxlength="65536"/)
})
