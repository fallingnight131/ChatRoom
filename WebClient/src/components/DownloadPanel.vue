<template>
  <Teleport to="body">
    <section v-if="hasDownloads" class="dl-panel" aria-labelledby="download-panel-title">
      <div class="dl-header">
        <span id="download-panel-title">{{ messages.title }}</span>
        <button type="button" class="dl-toggle" aria-controls="download-list"
                :aria-expanded="!collapsed"
                :aria-label="collapsed ? messages.expand : messages.collapse"
                @click="collapsed = !collapsed">
          {{ collapsed ? '▲' : '▼' }}
        </button>
      </div>
      <div v-show="!collapsed" id="download-list" class="dl-list" role="list">
        <div v-for="(d, fid) in chatStore.downloads" :key="fid" class="dl-item"
             role="listitem" :aria-label="`${d.fileName}${messages.taskSuffix}`">
          <div class="dl-row">
            <span class="dl-name text-ellipsis">{{ d.fileName }}</span>
            <span class="dl-pct">{{ downloadStateLabel(d) }} · {{ percent(d) }}%</span>
          </div>
          <div class="progress-bar" role="progressbar"
               :aria-label="`${d.fileName}${messages.progressSuffix}`"
               aria-valuemin="0" aria-valuemax="100" :aria-valuenow="percent(d)">
            <div class="progress-fill" :style="{ width: percent(d) + '%' }"></div>
          </div>
          <div class="dl-row dl-actions">
            <span class="dl-size">{{ formatSize(d.received) }} / {{ formatSize(d.fileSize) }}</span>
            <span class="dl-btns">
              <button v-if="d.status === 'downloading'" type="button" class="dl-btn"
                      :aria-label="`${messages.pausePrefix}${d.fileName}`"
                      @click="chatStore.pauseDownload(fid)" :title="messages.pause">⏸</button>
              <button v-if="d.status === 'paused'" type="button" class="dl-btn"
                      :aria-label="`${messages.resumePrefix}${d.fileName}`"
                      @click="chatStore.resumeDownload(fid)" :title="messages.resume">▶</button>
              <button type="button" class="dl-btn" :aria-label="`${messages.cancelPrefix}${d.fileName}`"
                      @click="chatStore.cancelDownload(fid)" :title="messages.cancel">✖</button>
            </span>
          </div>
        </div>
      </div>
    </section>
  </Teleport>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { downloadPanelMessages } from '../localization/webLocale'

const chatStore = useChatStore()
const userStore = useUserStore()
const messages = computed(() => downloadPanelMessages(userStore.locale))
const collapsed = ref(false)

const hasDownloads = computed(() => Object.keys(chatStore.downloads).length > 0)

function percent(d) {
  if (!d.fileSize) return 0
  return Math.min(100, Math.floor((d.received / d.fileSize) * 100))
}

function downloadStateLabel(d) {
  return d.status === 'paused' ? messages.value.paused : messages.value.downloading
}

function formatSize(size) {
  if (!size) return '0 B'
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(1) + ' MB'
  return (size / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}
</script>

<style scoped>
.dl-panel {
  position: fixed;
  bottom: 16px;
  right: 16px;
  width: 320px;
  background: var(--bg-secondary, #2a2a2e);
  border: 1px solid var(--border-color, #444);
  border-radius: 10px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.35);
  z-index: 4000;
  overflow: hidden;
  font-size: 13px;
  color: var(--text-primary, #eee);
}
.dl-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  font-weight: 600;
  font-size: 14px;
  border-bottom: 1px solid var(--border-color, #444);
  background: var(--bg-tertiary, #333);
}
.dl-toggle {
  background: none;
  border: none;
  color: var(--text-secondary, #aaa);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 4px;
}
.dl-toggle:hover {
  background: var(--bg-hover, rgba(255,255,255,0.08));
}
.dl-list {
  max-height: 240px;
  overflow-y: auto;
}
.dl-item {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border-color, #3a3a3a);
}
.dl-item:last-child {
  border-bottom: none;
}
.dl-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.dl-name {
  flex: 1;
  min-width: 0;
  font-weight: 500;
}
.dl-pct {
  font-size: 12px;
  color: var(--accent, #4CAF50);
  font-weight: 600;
  white-space: nowrap;
}
.dl-item .progress-bar {
  margin: 6px 0;
  height: 4px;
  background: var(--bg-tertiary, #333);
  border-radius: 2px;
  overflow: hidden;
}
.dl-item .progress-fill {
  height: 100%;
  background: var(--accent, #4CAF50);
  border-radius: 2px;
  transition: width 0.3s;
}
.dl-actions {
  font-size: 11px;
}
.dl-size {
  color: var(--text-tertiary, #888);
}
.dl-btns {
  display: flex;
  gap: 4px;
}
.dl-btn {
  background: none;
  border: none;
  color: var(--text-secondary, #aaa);
  cursor: pointer;
  font-size: 13px;
  padding: 2px 4px;
  border-radius: 4px;
  line-height: 1;
}
.dl-btn:hover {
  background: var(--bg-hover, rgba(255,255,255,0.1));
  color: var(--text-primary, #eee);
}

@media (max-width: 480px) {
  .dl-panel {
    width: calc(100vw - 24px);
    right: 12px;
    bottom: 12px;
  }
}
</style>
