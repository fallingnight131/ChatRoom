import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const messages = await readFile(
  new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')
const input = await readFile(
  new URL('../src/components/InputArea.vue', import.meta.url), 'utf8')
const styles = await readFile(
  new URL('../src/assets/style.css', import.meta.url), 'utf8')

test('exposes the message timeline as an announced busy-aware log', () => {
  assert.match(messages, /role="log"/)
  assert.match(messages, /aria-live="polite"/)
  assert.match(messages, /:aria-busy="loadingMore"/)
  assert.match(messages, /:aria-label="messageAriaLabel\(msg\)"/)
})

test('supports keyboard access to files, profiles, retry, and message actions', () => {
  assert.match(messages, /<button type="button" class="msg-avatar"/)
  assert.match(messages, /@keydown\.space\.prevent="openPreview\(msg\)"/)
  assert.match(messages, /onBubbleKeydown/)
  assert.match(messages, /role="menu" aria-label="消息操作"/)
  assert.match(messages, /role="menuitem" tabindex="0"/)
})

test('labels composer controls and honors focus and reduced-motion preferences', () => {
  assert.match(input, /role="toolbar" aria-label="消息工具"/)
  assert.match(input, /role="progressbar"/)
  assert.match(input, /aria-label="消息内容"/)
  assert.match(styles, /:focus-visible/)
  assert.match(styles, /prefers-reduced-motion: reduce/)
})
