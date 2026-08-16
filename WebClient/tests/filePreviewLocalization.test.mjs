import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/FilePreview.vue', import.meta.url), 'utf8')

test('renders file preview controls and content names from the active catalog', () => {
  for (const marker of [
    'filePreviewMessages(userStore.locale)',
    'messages.zoomOut',
    'messages.resetZoomPrefix',
    'messages.zoomIn',
    'messages.downloadTitle',
    'messages.closeTitle',
    'messages.close',
    'messages.loading',
    'messages.imagePreviewSuffix',
    'messages.videoPlayerSuffix',
    'messages.audioPlayerSuffix',
    'messages.pdfPreviewSuffix',
    'messages.textPreviewSuffix',
    'messages.unsupportedHint',
    'messages.downloadFile',
  ]) assert.ok(source.includes(marker), `missing file-preview locale marker: ${marker}`)
})

test('localizes preview failure state and third-party player language without changing transport', () => {
  for (const marker of [
    'messages.value.cannotDownload',
    'messages.value.cannotPreview',
    'messages.value.unknownFile',
    "userStore.locale === 'zh-CN' ? 'zh-cn' : 'en'",
    "getHttpDownloadUrl(msg.fileId, Number(msg.fileId) < 0, 'inline')",
    'chatWs.downloadChunk(msg.fileId, 0, FILE_CHUNK_SIZE)',
    'URL.revokeObjectURL(blobUrl.value)',
  ]) assert.ok(source.includes(marker), `missing localized preview boundary marker: ${marker}`)
})
