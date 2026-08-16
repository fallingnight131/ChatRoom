import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const input = readFileSync(new URL('../src/components/InputArea.vue', import.meta.url), 'utf8')
const picker = readFileSync(new URL('../src/components/EmojiPicker.vue', import.meta.url), 'utf8')

test('names native composer and file-upload controls', () => {
  for (const marker of [
    'ref="emojiButton" type="button"',
    'aria-controls="emoji-picker" aria-haspopup="dialog"',
    ':aria-label="`${u.fileName}${messages.uploadProgressSuffix}`"',
    ':aria-label="`${messages.pauseUploadPrefix}${u.fileName}`"',
    ':aria-label="`${messages.resumeUploadPrefix}${u.fileName}`"',
    ':aria-label="`${messages.cancelUploadPrefix}${u.fileName}`"',
    'type="button" class="btn btn-primary send-btn"',
  ]) assert.ok(input.includes(marker), `missing composer marker: ${marker}`)
})

test('renders composer controls, recovery, and local limits from the live catalog', () => {
  for (const marker of [
    'composerMessages(userStore.locale)',
    ':placeholder="messages.messagePlaceholder"',
    '{{ messages.send }}',
    'messages.value.needsSource',
    'messages.value.friendFileTooLarge',
    'messages.value.roomFileTooLargePrefix',
  ]) assert.ok(input.includes(marker), `missing composer locale marker: ${marker}`)
})

test('provides one-entry keyboard navigation through the emoji grid', () => {
  for (const marker of [
    'id="emoji-picker" class="emoji-picker" role="dialog"',
    'class="emoji-grid" role="grid"',
    'class="emoji-row" role="row"',
    'type="button" role="gridcell" class="emoji-item"',
    ':tabindex="emojiIndex(rowIndex, columnIndex) === activeIndex ? 0 : -1"',
    '@keydown="onEmojiKeydown($event, emojiIndex(rowIndex, columnIndex))"',
    'ArrowRight',
    'ArrowLeft',
    'ArrowDown',
    'ArrowUp',
    'onMounted(() => focusEmoji(0))',
  ]) assert.ok(picker.includes(marker), `missing emoji-grid marker: ${marker}`)
})

test('renders the emoji dialog, grid, and actions from the live locale catalog', () => {
  for (const marker of [
    'emojiPickerMessages(userStore.locale)',
    ':aria-label="messages.dialog"',
    ':aria-label="messages.grid"',
    ':aria-label="`${messages.sendPrefix}${emoji}`"',
  ]) assert.ok(picker.includes(marker), `missing emoji locale marker: ${marker}`)
})

test('restores a useful focus target when the picker closes', () => {
  assert.match(input, /@close="closeEmojiPicker\(true\)"/)
  assert.match(input, /nextTick\(\(\) => emojiButton\.value\?\.focus\(\)\)/)
  assert.match(input, /nextTick\(\(\) => textareaRef\.value\?\.focus\(\)\)/)
  assert.match(picker, /@keydown\.esc\.stop\.prevent="emit\('close'\)"/)
})
