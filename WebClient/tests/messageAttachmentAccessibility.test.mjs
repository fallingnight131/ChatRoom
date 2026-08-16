import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/MessageList.vue', import.meta.url), 'utf8')

test('uses named native controls for every attachment preview state', () => {
  for (const marker of [
    'type="button" class="msg-expired-image"',
    ':aria-label="`查看已过期图片 ${msg.fileName || \'图片\'}`"',
    'type="button" class="msg-image-button"',
    ':aria-label="`预览图片 ${msg.fileName || \'聊天图片\'}`"',
    'type="button" class="msg-expired-video"',
    ':aria-label="`查看已过期视频 ${msg.fileName || \'视频\'}`"',
    'type="button" class="msg-video-card"',
    ':aria-label="`预览视频 ${msg.fileName || \'视频\'}`"',
    'type="button" class="msg-file"',
    "${msg.fileCleared ? '查看已过期文件' : '预览文件'}",
  ]) assert.ok(source.includes(marker), `missing attachment-action marker: ${marker}`)
})

test('does not recreate native button keyboard behavior on attachment cards', () => {
  assert.doesNotMatch(source, /role="button" tabindex="0" @keydown\.enter="openPreview\(msg\)"/)
  assert.doesNotMatch(source, /@keydown\.space\.prevent="openPreview\(msg\)"/)
  assert.match(source, /type="button" class="delivery-retry"/)
})
