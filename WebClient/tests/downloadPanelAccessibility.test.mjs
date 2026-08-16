import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/DownloadPanel.vue', import.meta.url), 'utf8')

test('exposes download management as a named collapsible region', () => {
  for (const marker of [
    '<section v-if="hasDownloads" class="dl-panel" aria-labelledby="download-panel-title">',
    'id="download-panel-title"',
    'type="button" class="dl-toggle" aria-controls="download-list"',
    ':aria-expanded="!collapsed"',
    ":aria-label=\"collapsed ? '展开下载管理' : '收起下载管理'\"",
    'v-show="!collapsed" id="download-list" class="dl-list" role="list"',
    'role="listitem" :aria-label="`${d.fileName} 下载任务`"',
  ]) assert.ok(source.includes(marker), `missing download-region marker: ${marker}`)
})

test('names file progress and native download actions', () => {
  for (const marker of [
    'class="progress-bar" role="progressbar"',
    ':aria-label="`${d.fileName} 下载进度`"',
    ':aria-valuenow="percent(d)"',
    "d.status === 'paused' ? '已暂停' : '下载中'",
    ':aria-label="`暂停下载 ${d.fileName}`"',
    ':aria-label="`继续下载 ${d.fileName}`"',
    ':aria-label="`取消下载 ${d.fileName}`"',
  ]) assert.ok(source.includes(marker), `missing download-action marker: ${marker}`)
})
