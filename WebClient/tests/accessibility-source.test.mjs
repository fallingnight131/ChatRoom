import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const messages = await readFile(
  new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')
const input = await readFile(
  new URL('../src/components/InputArea.vue', import.meta.url), 'utf8')
const styles = await readFile(
  new URL('../src/assets/style.css', import.meta.url), 'utf8')
const login = await readFile(
  new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
const chatView = await readFile(
  new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')

test('exposes the message timeline as an announced busy-aware log', () => {
  assert.match(messages, /role="log"/)
  assert.match(messages, /aria-live="polite"/)
  assert.match(messages, /:aria-busy="loadingMore"/)
  assert.match(messages, /:aria-label="messageAriaLabel\(msg\)"/)
})

test('announces offline and reconnecting state without relying on icons', () => {
  assert.match(chatView, /role="status" aria-live="polite"/)
  assert.match(chatView, /可继续查看已缓存消息/)
  assert.match(chatView, /网络已断开，将在恢复后自动连接/)
  assert.match(chatView, /chatWs\.on\('offline', onNetworkOffline\)/)
  assert.match(chatView, /chatWs\.on\('online', onNetworkOnline\)/)
})

test('supports keyboard access to files, profiles, retry, and message actions', () => {
  assert.match(messages, /<button type="button" class="msg-avatar"/)
  assert.match(messages, /@keydown\.space\.prevent="openPreview\(msg\)"/)
  assert.match(messages, /onBubbleKeydown/)
  assert.match(messages, /role="menu" aria-label="消息操作"/)
  assert.match(messages, /role="menuitem" tabindex="0"/)
  assert.match(messages, /已发送/)
})

test('labels composer controls and honors focus and reduced-motion preferences', () => {
  assert.match(input, /role="toolbar" aria-label="消息工具"/)
  assert.match(input, /role="progressbar"/)
  assert.match(input, /aria-label="待恢复的文件发送"/)
  assert.match(input, /`重新选择 \$\{command\.fileName\}`/)
  assert.match(input, /`取消发送 \$\{command\.fileName\}`/)
  assert.match(input, /aria-label="消息内容"/)
  assert.match(input, /aria-label="消息字节数"/)
  assert.match(input, /:disabled="!canSendText"/)
  assert.match(styles, /:focus-visible/)
  assert.match(styles, /prefers-reduced-motion: reduce/)
})

test('exposes login as a labeled keyboard-operable form with announced errors', () => {
  assert.match(login, /<form class="login-card" aria-labelledby="login-title"/)
  assert.match(login, /<label for="login-username">/)
  assert.match(login, /id="login-username"/)
  assert.match(login, /<label for="login-password">/)
  assert.match(login, /type="submit" class="btn btn-primary login-btn"/)
  assert.match(login, /id="login-error" class="error-msg" role="alert" aria-live="assertive"/)
  assert.match(login, /<button type="button" @click="isRegister = !isRegister"/)
  assert.match(login, /:aria-label="userStore\.darkMode/)
})
