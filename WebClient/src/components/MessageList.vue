<template>
  <div class="message-list" ref="listRef" @scroll="onScroll">
    <div v-if="loadingMore" class="loading-more">加载中...</div>

    <div v-for="(msg, idx) in chatStore.messages" :key="msg.id || idx" class="message-wrapper">
      <!-- 系统消息 -->
      <div v-if="msg.contentType === 'system'" class="system-message">
        {{ msg.content }}
      </div>

      <!-- 已撤回 -->
      <div v-else-if="msg.recalled" class="system-message recalled">
        {{ msg.senderName || msg.sender }} 撤回了一条消息
      </div>

      <!-- 普通消息 -->
      <div v-else class="message-row" :class="{ mine: isMine(msg) }">
        <!-- 头像 -->
        <div class="msg-avatar" @click="openUser(msg)">
          <img v-if="getAvatarSrc(msg.sender)" :src="getAvatarSrc(msg.sender)" class="avatar" />
          <div v-else class="avatar avatar-placeholder"
               :style="{ background: hashColor(msg.sender) }">
            {{ (msg.senderName || msg.sender || '?').charAt(0) }}
          </div>
        </div>

        <div class="msg-body">
          <!-- 发送者名 -->
          <div class="msg-sender" v-if="!isMine(msg)">{{ msg.senderName || msg.sender }}</div>

          <!-- 消息气泡 -->
          <div class="msg-bubble" :class="{ 'bubble-mine': isMine(msg) }"
               @contextmenu.prevent="onContextMenu($event, msg)">
            <!-- 文本 -->
            <template v-if="msg.contentType === 'text'">
              <span class="msg-text">{{ msg.content }}</span>
            </template>

            <!-- 表情 -->
            <template v-else-if="msg.contentType === 'emoji'">
              <span class="msg-emoji">{{ msg.content }}</span>
            </template>

            <!-- 图片 -->
            <template v-else-if="msg.contentType === 'image'">
              <img v-if="msg.imageData" :src="'data:image/png;base64,' + msg.imageData"
                   class="msg-image" @click="previewImage(msg)" />
              <div v-else class="msg-file" @click="downloadFile(msg)">
                📷 {{ msg.fileName || '图片' }}
                <span class="file-size">{{ formatSize(msg.fileSize) }}</span>
              </div>
            </template>

            <!-- 文件 -->
            <template v-else-if="msg.contentType === 'file'">
              <div class="msg-file" @click="downloadFile(msg)">
                <div class="file-icon">📎</div>
                <div class="file-info">
                  <div class="file-name text-ellipsis">{{ msg.fileName }}</div>
                  <div class="file-size">{{ formatSize(msg.fileSize) }}</div>
                </div>
                <!-- 缩略图（视频） -->
                <img v-if="msg.thumbnail" :src="'data:image/jpeg;base64,' + msg.thumbnail"
                     class="file-thumbnail" />
              </div>
            </template>
          </div>

          <!-- 时间 -->
          <div class="msg-time" :class="{ 'time-mine': isMine(msg) }">
            {{ formatTime(msg.timestamp) }}
          </div>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div v-if="contextMenu.show" class="context-menu"
         :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }">
      <div class="context-menu-item" v-if="canRecall(contextMenu.msg)"
           @click="recallMsg(contextMenu.msg)">撤回</div>
      <div class="context-menu-item" v-if="chatStore.isAdmin && contextMenu.msg"
           @click="deleteMsg(contextMenu.msg)">删除</div>
    </div>

    <!-- 图片预览 -->
    <div v-if="previewSrc" class="image-preview" @click="previewSrc = ''">
      <img :src="previewSrc" />
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, inject, onMounted, onUnmounted } from 'vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType, MAX_SMALL_FILE } from '../services/websocket'

const userStore = useUserStore()
const chatStore = useChatStore()
const openUserInfo = inject('openUserInfo')
const hashColor = inject('hashColor')

const listRef = ref(null)
const loadingMore = ref(false)
const previewSrc = ref('')
const contextMenu = ref({ show: false, x: 0, y: 0, msg: null })

function isMine(msg) {
  return msg.sender === userStore.username
}

function getAvatarSrc(username) {
  const data = userStore.getAvatar(username)
  if (data) return 'data:image/png;base64,' + data
  // 请求头像
  if (username && !userStore.avatarCache[username]) {
    userStore.avatarCache[username] = '' // 标记已请求
    chatWs.getAvatar(username)
  }
  return ''
}

function openUser(msg) {
  const user = chatStore.users.find(u => u.username === msg.sender)
  if (user) {
    openUserInfo(user)
  } else {
    openUserInfo({
      username: msg.sender,
      displayName: msg.senderName || msg.sender,
      isAdmin: false,
      isOnline: false
    })
  }
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const pad = n => String(n).padStart(2, '0')
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (d.toDateString() === now.toDateString()) return time
  return `${pad(d.getMonth()+1)}-${pad(d.getDate())} ${time}`
}

function formatSize(size) {
  if (!size) return ''
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  if (size < 1024 * 1024 * 1024) return (size / (1024*1024)).toFixed(1) + ' MB'
  return (size / (1024*1024*1024)).toFixed(2) + ' GB'
}

function downloadFile(msg) {
  if (msg.fileId) {
    if (msg.fileSize && msg.fileSize > MAX_SMALL_FILE) {
      chatStore.startChunkedDownload(msg.fileId, msg.fileName, msg.fileSize)
    } else {
      chatWs.downloadFile(msg.fileId)
    }
  }
}

function previewImage(msg) {
  if (msg.imageData) {
    previewSrc.value = 'data:image/png;base64,' + msg.imageData
  }
}

function canRecall(msg) {
  if (!msg) return false
  if (msg.sender !== userStore.username) return false
  const elapsed = Date.now() - (msg.timestamp || 0)
  return elapsed < 120000 // 2分钟
}

function recallMsg(msg) {
  if (msg) {
    chatWs.recallMessage(msg.id, chatStore.currentRoomId)
  }
  contextMenu.value.show = false
}

function deleteMsg(msg) {
  if (msg && confirm('确定删除此消息？')) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'selected', [msg.id])
  }
  contextMenu.value.show = false
}

function onContextMenu(e, msg) {
  contextMenu.value = { show: true, x: e.clientX, y: e.clientY, msg }
}

function onScroll() {
  if (listRef.value && listRef.value.scrollTop === 0 && chatStore.messages.length > 0) {
    // 加载更多
    const firstMsg = chatStore.messages[0]
    if (firstMsg && firstMsg.timestamp) {
      loadingMore.value = true
      chatWs.requestHistory(chatStore.currentRoomId, 50, firstMsg.timestamp)
      setTimeout(() => { loadingMore.value = false }, 2000)
    }
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
    }
  })
}

// 新消息自动滚动到底部
watch(() => chatStore.messages.length, () => {
  scrollToBottom()
})

// 切换房间也滚动
watch(() => chatStore.currentRoomId, () => {
  scrollToBottom()
})

function closeMenu() {
  contextMenu.value.show = false
}

onMounted(() => {
  document.addEventListener('click', closeMenu)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
})
</script>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.loading-more {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 12px;
  padding: 8px;
}

/* 系统消息 */
.system-message {
  text-align: center;
  color: var(--text-system);
  font-size: 12px;
  padding: 6px 0;
}
.recalled {
  font-style: italic;
}

/* 消息行 */
.message-row {
  display: flex;
  gap: 10px;
  max-width: 75%;
  align-items: flex-start;
}
.message-row.mine {
  flex-direction: row-reverse;
  align-self: flex-end;
}

.msg-avatar {
  cursor: pointer;
  flex-shrink: 0;
}

.msg-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.msg-sender {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 2px;
  margin-left: 4px;
}

.msg-bubble {
  background: var(--bg-bubble-other);
  border-radius: 12px 12px 12px 4px;
  padding: 10px 14px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
  word-break: break-word;
  max-width: 100%;
}
.msg-bubble.bubble-mine {
  background: var(--bg-bubble-mine);
  border-radius: 12px 12px 4px 12px;
}

.msg-text {
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.msg-emoji {
  font-size: 32px;
  line-height: 1.2;
}

.msg-image {
  max-width: 300px;
  max-height: 300px;
  border-radius: 8px;
  cursor: pointer;
  display: block;
}

/* 文件消息 */
.msg-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  background: var(--bg-primary);
  border-radius: 8px;
  cursor: pointer;
  min-width: 200px;
}
.msg-file:hover {
  opacity: 0.8;
}
.file-icon {
  font-size: 28px;
  flex-shrink: 0;
}
.file-info {
  flex: 1;
  min-width: 0;
}
.file-name {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 500;
}
.file-size {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
}
.file-thumbnail {
  width: 48px;
  height: 48px;
  border-radius: 4px;
  object-fit: cover;
  flex-shrink: 0;
}

.msg-time {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
  margin-left: 4px;
}
.msg-time.time-mine {
  text-align: right;
  margin-right: 4px;
}

/* 图片预览 */
.image-preview {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
  cursor: zoom-out;
}
.image-preview img {
  max-width: 90%;
  max-height: 90%;
  border-radius: 8px;
}

.message-wrapper {
  display: flex;
  flex-direction: column;
}
</style>
