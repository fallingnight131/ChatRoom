<template>
  <div class="modal-overlay" @click.self="closeDialog">
    <div ref="dialogRef" class="modal room-file-modal" role="dialog" aria-modal="true"
         aria-labelledby="room-file-title" tabindex="-1" :aria-busy="isDeleting"
         @keydown="onDialogKeydown">
      <div id="room-file-title" class="modal-title">文件管理</div>

      <div class="summary" aria-live="polite">当前文件空间: {{ formatSize(chatStore.roomFileUsage.used) }} / {{ formatSize(chatStore.roomFileUsage.max) }}</div>

      <div class="table-wrap">
        <table class="file-table">
          <caption class="visually-hidden">房间文件列表，可选择文件后批量删除</caption>
          <thead>
            <tr>
              <th scope="col"><input type="checkbox" :checked="allChecked" :disabled="isDeleting"
                     aria-label="选择全部房间文件" @change="toggleAll($event)" /></th>
              <th scope="col">文件名</th>
              <th scope="col">类型</th>
              <th scope="col">大小</th>
              <th scope="col">上传时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in chatStore.roomFiles" :key="f.fileId">
              <td><input type="checkbox" v-model="selected" :value="f.fileId" :disabled="isDeleting"
                         :aria-label="`选择文件 ${f.fileName}`" /></td>
              <td class="name-cell">{{ f.fileName }}</td>
              <td>{{ fileType(f.fileName) }}</td>
              <td>{{ formatSize(Number(f.fileSize || 0)) }}</td>
              <td>{{ f.createdAt || '-' }}</td>
            </tr>
            <tr v-if="chatStore.roomFiles.length === 0">
              <td colspan="5" class="empty">暂无文件</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="modal-actions">
        <span v-if="isDeleting" class="delete-status" role="status">删除中…</span>
        <button class="btn btn-secondary" type="button" :disabled="isDeleting" @click="refresh">刷新</button>
        <button class="btn btn-danger" type="button" :disabled="selected.length === 0 || isDeleting"
                @click="deleteSelected">删除所选</button>
        <button class="btn btn-primary" type="button" @click="closeDialog">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useChatStore } from '../stores/chat'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'

const chatStore = useChatStore()
const emit = defineEmits(['close'])
const selected = ref([])
const pendingDeleteOperationId = ref('')
const isDeleting = computed(() => pendingDeleteOperationId.value !== '')
const { dialogRef, closeDialog, onDialogKeydown } = useModalKeyboardBoundary({
  onClose: () => emit('close')
})

const allChecked = computed(() => {
  return chatStore.roomFiles.length > 0 && selected.value.length === chatStore.roomFiles.length
})

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function fileType(fileName) {
  const ext = (fileName || '').split('.').pop()?.toLowerCase() || ''
  const imageExt = ['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp']
  const videoExt = ['mp4', 'avi', 'mkv', 'mov', 'wmv', 'flv', 'webm']
  if (imageExt.includes(ext)) return '图片'
  if (videoExt.includes(ext)) return '视频'
  return '文件'
}

function toggleAll(e) {
  if (isDeleting.value) return
  if (e.target.checked) {
    selected.value = chatStore.roomFiles.map(f => f.fileId)
  } else {
    selected.value = []
  }
}

function refresh() {
  if (!isDeleting.value && chatStore.currentRoomId) {
    chatStore.requestRoomFiles(chatStore.currentRoomId)
  }
}

function deleteSelected() {
  if (isDeleting.value || selected.value.length === 0) return
  if (!confirm('确定彻底删除选中的文件消息吗？\n删除后消息会从聊天室中完全移除，无法恢复。')) return
  pendingDeleteOperationId.value = chatStore.deleteRoomFiles(
    chatStore.currentRoomId,
    [...selected.value],
  )
}

function matchesPendingDelete(data) {
  return pendingDeleteOperationId.value !== '' &&
    data?.clientOperationId === pendingDeleteOperationId.value
}

function onDeleted(data) {
  if (!matchesPendingDelete(data)) return
  pendingDeleteOperationId.value = ''
  selected.value = []
}

function onDeleteFailed(data) {
  if (!matchesPendingDelete(data)) return
  pendingDeleteOperationId.value = ''
}

onMounted(() => {
  chatStore.onEvent('roomFilesDeleted', onDeleted)
  chatStore.onEvent('roomFilesDeleteFailed', onDeleteFailed)
  refresh()
})

onUnmounted(() => {
  chatStore.offEvent('roomFilesDeleted', onDeleted)
  chatStore.offEvent('roomFilesDeleteFailed', onDeleteFailed)
})
</script>

<style scoped>
.room-file-modal {
  max-width: 860px;
  width: 92vw;
  color: var(--text-primary, #fff);
}
.summary {
  margin-bottom: 10px;
  color: var(--text-secondary);
}
.delete-status {
  margin-right: auto;
  color: var(--text-secondary);
  font-size: 13px;
}
.table-wrap {
  max-height: 52vh;
  overflow: auto;
  border: 1px solid var(--border-color);
  border-radius: 8px;
}
.file-table {
  width: 100%;
  border-collapse: collapse;
}
.file-table th,
.file-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--border-light);
  text-align: left;
  font-size: 13px;
  color: var(--text-primary, #fff);
}
.file-table th {
  position: sticky;
  top: 0;
  background: var(--bg-secondary);
  z-index: 1;
}
.name-cell {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty {
  text-align: center;
  color: var(--text-tertiary);
  padding: 18px 0;
}
</style>
