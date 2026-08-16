import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')

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
    '{{ attachmentMessages.expired }}',
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
