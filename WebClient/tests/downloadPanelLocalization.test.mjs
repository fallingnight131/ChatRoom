import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/DownloadPanel.vue', import.meta.url), 'utf8')

test('renders download management from the active locale catalog', () => {
  for (const marker of [
    'downloadPanelMessages(userStore.locale)',
    'messages.title',
    'messages.expand',
    'messages.collapse',
    'messages.taskSuffix',
    'messages.progressSuffix',
    'messages.pausePrefix',
    'messages.resumePrefix',
    'messages.cancelPrefix',
    'messages.value.paused',
    'messages.value.downloading',
  ]) assert.ok(source.includes(marker), `missing download-panel locale marker: ${marker}`)
})

test('preserves native progress and store-owned download commands', () => {
  for (const marker of [
    'role="progressbar"',
    ':aria-valuenow="percent(d)"',
    'chatStore.pauseDownload(fid)',
    'chatStore.resumeDownload(fid)',
    'chatStore.cancelDownload(fid)',
  ]) assert.ok(source.includes(marker), `missing download boundary marker: ${marker}`)
})
