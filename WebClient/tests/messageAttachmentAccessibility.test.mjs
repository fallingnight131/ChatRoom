import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')
const store = readFileSync(new URL('../src/stores/chat.js', import.meta.url), 'utf8')

test('uses named native controls for every attachment preview state', () => {
  for (const marker of [
    'type="button" class="msg-expired-image"',
    'attachmentMessages.expiredImagePrefix',
    'type="button" class="msg-image-button"',
    'attachmentMessages.previewImagePrefix',
    'type="button" class="msg-expired-video"',
    'attachmentMessages.expiredVideoPrefix',
    'type="button" class="msg-video-card"',
    'attachmentMessages.previewVideoPrefix',
    'type="button" class="msg-file"',
    'msg.fileCleared ? attachmentMessages.expiredFilePrefix : attachmentMessages.previewFilePrefix',
  ]) assert.ok(source.includes(marker), `missing attachment-action marker: ${marker}`)
})

test('renders attachment defaults, expiry state, and local denials from the live catalog', () => {
  for (const marker of [
    'messageAttachmentMessages(userStore.locale)',
    '{{ attachmentUnavailableText(msg) }}',
    'safeReason.trim() ? safeReason : attachmentMessages.value.expired',
    'attachmentMessages.imageThumbnail',
    'attachmentMessages.thumbnailSuffix',
    'attachmentMessages.value.cannotPreview',
    'attachmentMessages.value.cannotDownload',
    'attachmentMessages.value.cannotForward',
  ]) assert.ok(source.includes(marker), `missing attachment locale marker: ${marker}`)
})

test('does not recreate native button keyboard behavior on attachment cards', () => {
  assert.doesNotMatch(source, /role="button" tabindex="0" @keydown\.enter="openPreview\(msg\)"/)
  assert.doesNotMatch(source, /@keydown\.space\.prevent="openPreview\(msg\)"/)
  assert.match(source, /type="button" class="delivery-retry"/)
})

test('persists attachment availability state without locale-specific fallback copy', () => {
  assert.match(store, /const wasCleared = message\.fileCleared === true/)
  assert.match(store, /message\.fileCleared = true/)
  assert.match(store, /if \(!wasCleared\) message\.clearReason = ''/)
  assert.doesNotMatch(store, /clearReason = '文件已过期或被清除'/)
  assert.match(store, /error\.code = 'ATTACHMENT_UNAVAILABLE'/)
  assert.match(source, /err\?\.code === 'ATTACHMENT_UNAVAILABLE'/)
  assert.match(source, /attachmentMessages\.value\.cannotForward/)
})
