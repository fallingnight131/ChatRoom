<template>
  <div class="input-area">
    <!-- 工具栏 -->
    <div class="input-toolbar">
      <button class="btn-icon" @click="showEmoji = !showEmoji" title="表情">😊</button>
      <button class="btn-icon" @click="triggerFileInput" title="发送文件">📎</button>
      <input ref="fileInput" type="file" style="display:none" @change="onFileSelected" />

      <!-- 上传进度 -->
      <div v-if="Object.keys(chatStore.uploads).length > 0" class="upload-status">
        <div v-for="(u, uid) in chatStore.uploads" :key="uid" class="upload-item">
          <span class="text-ellipsis" style="max-width:120px">{{ u.fileName }}</span>
          <div class="progress-bar" style="width:80px">
            <div class="progress-fill" :style="{ width: uploadPercent(u) + '%' }"></div>
          </div>
          <span class="upload-pct">{{ uploadPercent(u) }}%</span>
          <button v-if="u.status === 'uploading'" class="btn-icon" @click="chatStore.pauseUpload(uid)" title="暂停">⏸</button>
          <button v-if="u.status === 'paused'" class="btn-icon" @click="chatStore.resumeUpload(uid)" title="继续">▶</button>
          <button class="btn-icon" @click="chatStore.cancelUpload(uid)" title="取消">✖</button>
        </div>
      </div>
    </div>

    <!-- 表情选择器 -->
    <div v-if="showEmoji" class="emoji-overlay" @click="showEmoji = false"></div>
    <EmojiPicker v-if="showEmoji" @select="onEmojiSelect" @close="showEmoji = false" />

    <!-- 文本输入 -->
    <div class="input-row">
      <textarea ref="textareaRef" class="input chat-input" v-model="text"
                placeholder="输入消息..."
                @keydown.enter.exact="sendMessage"
                @keydown.enter.shift.exact.prevent="text += '\n'"
                rows="1"></textarea>
      <button class="btn btn-primary send-btn" @click="sendMessage" :disabled="!text.trim()">
        发送
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MAX_SMALL_FILE } from '../services/websocket'
import EmojiPicker from './EmojiPicker.vue'

const userStore = useUserStore()
const chatStore = useChatStore()

const text = ref('')
const showEmoji = ref(false)
const fileInput = ref(null)
const textareaRef = ref(null)

function sendMessage(e) {
  if (e) e.preventDefault()
  const msg = text.value.trim()
  if (!msg || !chatStore.currentRoomId) return
  chatWs.sendChat(chatStore.currentRoomId, userStore.username, msg, 'text')
  text.value = ''
}

function onEmojiSelect(emoji) {
  chatWs.sendChat(chatStore.currentRoomId, userStore.username, emoji, 'emoji')
  showEmoji.value = false
}

function triggerFileInput() {
  fileInput.value?.click()
}

async function onFileSelected(e) {
  const file = e.target.files[0]
  if (!file) return
  e.target.value = '' // 重置

  if (file.size <= MAX_SMALL_FILE) {
    // 小文件直传
    await chatStore.uploadSmallFile(chatStore.currentRoomId, file)
  } else {
    // 大文件分块上传
    await chatStore.startChunkedUpload(chatStore.currentRoomId, file)
  }
}

function uploadPercent(u) {
  if (!u.fileSize) return 0
  return Math.floor((u.sent / u.fileSize) * 100)
}
</script>

<style scoped>
.input-area {
  border-top: 1px solid var(--border-color);
  background: var(--bg-secondary);
  padding: 8px 16px 12px;
  position: relative;
}
.input-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.chat-input {
  flex: 1;
  resize: none;
  min-height: 36px;
  max-height: 120px;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  line-height: 1.4;
}
.send-btn {
  height: 36px;
  padding: 0 20px;
  flex-shrink: 0;
}

/* 上传状态 */
.upload-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: 8px;
}
.upload-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-primary);
  padding: 2px 8px;
  border-radius: 6px;
}
.upload-pct {
  font-size: 11px;
  color: var(--text-tertiary);
  min-width: 30px;
  text-align: right;
}

/* ========== 移动端适配 ========== */
@media (max-width: 768px) {
  .input-area {
    padding: 6px 10px 10px;
    padding-bottom: max(10px, env(safe-area-inset-bottom));
  }
  .input-row {
    gap: 6px;
  }
  .chat-input {
    font-size: 16px; /* 防止iOS缩放 */
    border-radius: 20px;
    padding: 8px 14px;
    min-height: 40px;
  }
  .send-btn {
    height: 40px;
    padding: 0 16px;
    border-radius: 20px;
    font-size: 15px;
  }
  .input-toolbar {
    gap: 2px;
  }
  .upload-status {
    margin-left: 4px;
  }
  .upload-item {
    font-size: 11px;
  }
}

/* 表情选择器遮罩 */
.emoji-overlay {
  position: fixed;
  inset: 0;
  z-index: 99;
}
</style>
