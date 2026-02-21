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
               @contextmenu.prevent="onContextMenu($event, msg)"
               @touchstart="onTouchStart($event, msg)"
               @touchend="onTouchEnd"
               @touchmove="onTouchMove">
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
                   class="msg-image" @click="previewMedia(msg)" />
              <div v-else class="msg-file" @click="previewMedia(msg)">
                📷 {{ msg.fileName || '图片' }}
                <span class="file-size">{{ formatSize(msg.fileSize) }}</span>
              </div>
            </template>

            <!-- 视频 -->
            <template v-else-if="msg.contentType === 'file' && isVideoFile(msg.fileName)">
              <div class="msg-video-card" @click="previewMedia(msg)">
                <img v-if="msg.thumbnail" :src="'data:image/jpeg;base64,' + msg.thumbnail"
                     class="video-thumbnail" />
                <div v-else class="video-placeholder">
                  <span>🎬</span>
                </div>
                <div class="video-play-btn">▶</div>
                <div class="video-info">
                  <span class="file-name text-ellipsis">{{ msg.fileName }}</span>
                  <span class="file-size">{{ formatSize(msg.fileSize) }}</span>
                </div>
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

    <!-- 媒体预览 -->
    <div v-if="previewSrc || previewVideoSrc" class="media-preview" @click="closePreview">
      <!-- 图片预览 -->
      <img v-if="previewSrc" :src="previewSrc" @click.stop />
      <!-- 视频预览 -->
      <video v-if="previewVideoSrc" :src="previewVideoSrc" controls autoplay
             @click.stop class="preview-video"></video>
      <!-- 关闭按钮 -->
      <button class="preview-close" @click="closePreview">✕</button>
      <!-- 下载按钮 -->
      <button class="preview-download" @click.stop="downloadPreviewFile" v-if="previewMsg">⬇ 下载</button>
      <!-- 加载提示 -->
      <div v-if="previewLoading" class="preview-loading">加载中...</div>
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
const previewVideoSrc = ref('')
const previewMsg = ref(null)
const previewLoading = ref(false)
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

const VIDEO_EXTS = /\.(mp4|webm|ogg|mov|avi|mkv|m4v)$/i
const IMAGE_EXTS = /\.(jpg|jpeg|png|gif|bmp|webp|svg)$/i

function isVideoFile(fileName) {
  return fileName && VIDEO_EXTS.test(fileName)
}

function isImageFile(fileName) {
  return fileName && IMAGE_EXTS.test(fileName)
}

function previewMedia(msg) {
  previewMsg.value = msg
  // 图片已有 base64 数据
  if (msg.imageData) {
    previewSrc.value = 'data:image/png;base64,' + msg.imageData
    return
  }
  // 需要下载后预览
  if (!msg.fileId) return
  previewLoading.value = true
  // 监听下载完成
  const handler = (rsp) => {
    const d = rsp.data
    if (!d.success || !d.fileData) {
      previewLoading.value = false
      // 下载失败，回退到普通下载
      downloadFile(msg)
      closePreview()
      return
    }
    previewLoading.value = false
    const binary = atob(d.fileData)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    const mimeType = isVideoFile(d.fileName || msg.fileName)
      ? 'video/mp4'
      : 'image/png'
    const blob = new Blob([bytes], { type: mimeType })
    const url = URL.createObjectURL(blob)
    if (isVideoFile(d.fileName || msg.fileName)) {
      previewVideoSrc.value = url
    } else {
      previewSrc.value = url
    }
    chatWs.off(MsgType.FILE_DOWNLOAD_RSP, handler)
  }
  chatWs.on(MsgType.FILE_DOWNLOAD_RSP, handler)
  chatWs.downloadFile(msg.fileId)
}

function closePreview() {
  if (previewSrc.value && previewSrc.value.startsWith('blob:')) {
    URL.revokeObjectURL(previewSrc.value)
  }
  if (previewVideoSrc.value) {
    URL.revokeObjectURL(previewVideoSrc.value)
  }
  previewSrc.value = ''
  previewVideoSrc.value = ''
  previewMsg.value = null
  previewLoading.value = false
}

function downloadPreviewFile() {
  if (previewMsg.value) {
    downloadFile(previewMsg.value)
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

// 移动端长按触发上下文菜单
let longPressTimer = null
let longPressTriggered = false

function onTouchStart(e, msg) {
  longPressTriggered = false
  longPressTimer = setTimeout(() => {
    longPressTriggered = true
    const touch = e.touches[0]
    // 保证菜单不超出屏幕
    const x = Math.min(touch.clientX, window.innerWidth - 140)
    const y = Math.min(touch.clientY, window.innerHeight - 100)
    contextMenu.value = { show: true, x, y, msg }
  }, 500)
}

function onTouchEnd() {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

function onTouchMove() {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
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

/* 图片预览 → 媒体预览 */
.media-preview {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
  cursor: zoom-out;
}
.media-preview img {
  max-width: 90%;
  max-height: 90%;
  border-radius: 8px;
  object-fit: contain;
  cursor: default;
}
.preview-video {
  max-width: 90%;
  max-height: 85%;
  border-radius: 8px;
  outline: none;
  cursor: default;
}
.preview-close {
  position: absolute;
  top: 16px;
  right: 16px;
  background: rgba(255,255,255,0.15);
  border: none;
  color: #fff;
  font-size: 24px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
  z-index: 3001;
}
.preview-close:hover {
  background: rgba(255,255,255,0.3);
}
.preview-download {
  position: absolute;
  bottom: 24px;
  right: 24px;
  background: rgba(255,255,255,0.15);
  border: none;
  color: #fff;
  font-size: 14px;
  padding: 8px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  z-index: 3001;
}
.preview-download:hover {
  background: rgba(255,255,255,0.3);
}
.preview-loading {
  position: absolute;
  color: #fff;
  font-size: 16px;
  background: rgba(0,0,0,0.6);
  padding: 12px 24px;
  border-radius: 8px;
}

/* 视频卡片 */
.msg-video-card {
  position: relative;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  max-width: 280px;
  background: #000;
}
.msg-video-card:hover .video-play-btn {
  transform: translate(-50%, -50%) scale(1.1);
}
.video-thumbnail {
  width: 100%;
  max-height: 200px;
  object-fit: cover;
  display: block;
}
.video-placeholder {
  width: 280px;
  height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
  font-size: 48px;
}
.video-play-btn {
  position: absolute;
  top: 45%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 56px;
  height: 56px;
  background: rgba(0,0,0,0.55);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 22px;
  padding-left: 4px;
  transition: transform 0.2s;
}
.video-info {
  padding: 6px 10px;
  background: rgba(0,0,0,0.6);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.video-info .file-name {
  color: #fff;
  font-size: 12px;
}
.video-info .file-size {
  color: rgba(255,255,255,0.7);
  font-size: 11px;
  white-space: nowrap;
}

.message-wrapper {
  display: flex;
  flex-direction: column;
}

/* ========== 移动端适配 ========== */
@media (max-width: 768px) {
  .message-list {
    padding: 10px 12px;
  }
  .message-row {
    max-width: 88%;
    gap: 8px;
  }
  .msg-bubble {
    padding: 8px 12px;
  }
  .msg-text {
    font-size: 15px;
  }
  .msg-image {
    max-width: 220px;
    max-height: 220px;
  }
  .msg-file {
    min-width: 160px;
  }
  .file-icon {
    font-size: 24px;
  }
  .media-preview img {
    max-width: 95%;
    max-height: 85%;
  }
  .preview-video {
    max-width: 95%;
    max-height: 80%;
  }
  .msg-video-card {
    max-width: 220px;
  }
  .video-placeholder {
    width: 220px;
    height: 130px;
  }
  .preview-close {
    top: max(12px, env(safe-area-inset-top));
    right: max(12px, env(safe-area-inset-right));
  }
  .preview-download {
    bottom: max(20px, env(safe-area-inset-bottom));
  }
  /* 长按替代右键 */
  .msg-bubble {
    -webkit-touch-callout: none;
    -webkit-user-select: none;
    user-select: none;
  }
}

@media (max-width: 480px) {
  .message-row {
    max-width: 92%;
  }
  .msg-image {
    max-width: 180px;
    max-height: 180px;
  }
  .msg-video-card {
    max-width: 180px;
  }
  .video-placeholder {
    width: 180px;
    height: 110px;
  }
}
</style>
