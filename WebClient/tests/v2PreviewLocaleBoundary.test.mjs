import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('keeps fixed Chinese UI and compatibility mappings out of the V2 view boundary', () => {
  assert.doesNotMatch(source, /[一-鿿]/)
  for (const marker of [
    'localizeV2PreviewSearchFailure(', 'localizeV2PreviewParticipantFailure(',
    'localizeV2PreviewDeviceFailure(', 'shellMessages.value.authStartFailed',
    'shellMessages.value.openConversationFailed', 'shellMessages.value.loadConversationsFailed',
  ]) assert.ok(source.includes(marker), `missing V2 locale boundary marker: ${marker}`)
})
