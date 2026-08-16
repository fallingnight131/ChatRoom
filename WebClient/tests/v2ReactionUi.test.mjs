import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const source = await readFile(
  new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('exposes bounded keyboard-native reaction controls and retry feedback', () => {
  assert.match(source, /role="group" :aria-label="reactionMessages\.groupLabel/)
  assert.match(source, /:aria-pressed="reactionActive/)
  assert.match(source, /:aria-label="reactionMessages\.countLabel\(reaction\.label, reactionCount/)
  assert.match(source, /v-for="reaction in reactionChoices"/)
  assert.match(source, /MessageReactionKind\.ANGRY/)
  assert.match(source, /{{ reactionMessages\.retry }}/)
})
