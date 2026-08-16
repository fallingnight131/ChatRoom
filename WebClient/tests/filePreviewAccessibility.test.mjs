import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/FilePreview.vue', import.meta.url), 'utf8')

test('contains conditional file preview focus in a named modal', () => {
  for (const marker of [
    'ref="dialogRef" class="preview-overlay" role="dialog"',
    'aria-modal="true" aria-labelledby="file-preview-title" tabindex="-1"',
    '@keydown="onDialogKeydown" @click.self="closeDialog"',
    'id="file-preview-title"',
    'useModalKeyboardBoundary({',
    'active: previewActive',
    "initialFocusSelector: '[data-preview-close]'",
    "onClose: () => emit('close')",
  ]) assert.ok(source.includes(marker), `missing preview-modal marker: ${marker}`)
  assert.doesNotMatch(source, /document\.addEventListener\('keydown'/)
})

test('names preview actions and each embedded content surface', () => {
  for (const marker of [
    ':aria-label="`下载 ${fileName}`"',
    'data-preview-close',
    'aria-label="关闭文件预览"',
    'class="preview-spinner" role="status" aria-live="polite"',
    ':alt="`${fileName} 预览`"',
    ':aria-label="`${fileName} 视频播放器`"',
    ':aria-label="`${fileName} 音频播放器`"',
    ':title="`${fileName} PDF 预览`"',
    ':aria-label="`${fileName} 文本预览`"',
  ]) assert.ok(source.includes(marker), `missing preview-content marker: ${marker}`)
})

test('provides bounded native image zoom controls alongside pointer zoom', () => {
  for (const marker of [
    'aria-label="缩小图片"',
    ':disabled="scale <= MIN_IMAGE_SCALE"',
    ':aria-label="`重置图片缩放，当前 ${imageScalePercent}%`"',
    'aria-label="放大图片"',
    ':disabled="scale >= MAX_IMAGE_SCALE"',
    'const MIN_IMAGE_SCALE = 0.1',
    'const MAX_IMAGE_SCALE = 10',
    'function adjustZoom(delta)',
    'adjustZoom(e.deltaY > 0 ? -IMAGE_SCALE_STEP : IMAGE_SCALE_STEP)',
  ]) assert.ok(source.includes(marker), `missing image-zoom marker: ${marker}`)
})
