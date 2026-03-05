<template>
  <div class="message-list" ref="listRef" @scroll="onScroll">
    <div v-if="loadingMore" class="loading-more">加载中...</div>

    <div v-for="(msg, idx) in displayMessages" :key="msg.id || idx" class="message-wrapper">
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
                   class="msg-image" @click="openPreview(msg)" />
              <img v-else-if="msg.thumbnail" :src="'data:image/jpeg;base64,' + msg.thumbnail"
                   class="msg-image" @click="openPreview(msg)" />
              <div v-else class="msg-file" @click="openPreview(msg)">
                📷 {{ msg.fileName || '图片' }}
                <span class="file-size">{{ formatSize(msg.fileSize) }}</span>
              </div>
            </template>

            <!-- 视频 -->
            <template v-else-if="msg.contentType === 'video' || (msg.contentType === 'file' && isVideoFile(msg.fileName))">
              <div class="msg-video-card" @click="openPreview(msg)">
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

            <!-- 其他文件 -->
            <template v-else-if="msg.contentType === 'file'">
              <div class="msg-file" @click="openPreview(msg)">
                <div class="file-icon">{{ getFileIcon(msg.fileName) }}</div>
                <div class="file-info">
                  <div class="file-name text-ellipsis">{{ msg.fileName }}</div>
                  <div class="file-size">{{ formatSize(msg.fileSize) }}</div>
                </div>
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
    <Teleport to="body">
      <div v-if="contextMenu.show" class="context-menu-overlay" @click="closeMenu" @contextmenu.prevent="closeMenu">
        <div class="context-menu"
             :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
             @click.stop>
          <!-- 复制文本 (非文件、非系统消息都可以) -->
          <div class="context-menu-item"
               v-if="contextMenu.msg && contextMenu.msg.content && !isFileType(contextMenu.msg)"
               @click="copyText(contextMenu.msg)">
            <span class="menu-icon">📋</span> 复制文本
          </div>

          <!-- 预览文件 (文件/图片/视频消息，所有人可用) -->
          <div class="context-menu-item"
               v-if="contextMenu.msg && isFileType(contextMenu.msg) && !contextMenu.msg.recalled"
               @click="previewFromMenu(contextMenu.msg)">
            <span class="menu-icon">👁️</span> 预览文件
          </div>

          <!-- 撤回 (自己的消息, 2分钟内) -->
          <div class="context-menu-item"
               v-if="canRecall(contextMenu.msg)"
               @click="recallMsg(contextMenu.msg)">
            <span class="menu-icon">↩️</span> 撤回
          </div>

          <!-- 管理员: 删除此消息 -->
          <div class="context-menu-item danger"
               v-if="chatStore.isAdmin && contextMenu.msg && !contextMenu.msg.recalled"
               @click="deleteMsg(contextMenu.msg)">
            <span class="menu-icon">🗑️</span> 删除此消息
          </div>

          <!-- 管理员子菜单 -->
          <template v-if="chatStore.isAdmin">
            <div class="context-menu-divider"></div>
            <div class="context-menu-item danger" @click="clearAllMessages">
              <span class="menu-icon">🧹</span> 清空所有消息
            </div>
            <div class="context-menu-item danger" @click="deleteOldMessages">
              <span class="menu-icon">📅</span> 删除N天前的消息
            </div>
            <div class="context-menu-item danger" @click="deleteRecentMessages">
              <span class="menu-icon">🕐</span> 删除最近N天的消息
            </div>
          </template>
        </div>
      </div>
    </Teleport>

    <!-- 文件预览组件 -->
    <FilePreview
      :visible="previewVisible"
      :msg="previewMsgData"
      @close="previewVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, watch, nextTick, inject, onMounted, onUnmounted } from 'vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType, MAX_SMALL_FILE } from '../services/websocket'
import FilePreview from './FilePreview.vue'

const userStore = useUserStore()
const chatStore = useChatStore()
const openUserInfo = inject('openUserInfo')
const hashColor = inject('hashColor')

const listRef = ref(null)
const loadingMore = ref(false)
const previewVisible = ref(false)
const previewMsgData = ref(null)
const contextMenu = ref({ show: false, x: 0, y: 0, msg: null })

const props = defineProps({
  friendMode: { type: Boolean, default: false }
})

import { computed } from 'vue'
const displayMessages = computed(() => {
  return props.friendMode ? chatStore.friendMessages : chatStore.messages
})

function isMine(msg) {
  return msg.sender === userStore.username
}

function getAvatarSrc(username) {
  const data = userStore.getAvatar(username)
  if (data) return 'data:image/png;base64,' + data
  if (username && !userStore.avatarCache[username]) {
    userStore.avatarCache[username] = ''
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

const VIDEO_EXTS = /\.(mp4|webm|ogg|mov|avi|mkv|m4v|flv|3gp)$/i
const AUDIO_EXTS = /\.(mp3|wav|ogg|flac|aac|m4a|wma)$/i
const PDF_EXTS = /\.pdf$/i

function isVideoFile(fileName) {
  return fileName && VIDEO_EXTS.test(fileName)
}

function getFileIcon(fileName) {
  if (!fileName) return '📎'
  if (AUDIO_EXTS.test(fileName)) return '🎵'
  if (PDF_EXTS.test(fileName)) return '📕'
  if (/\.(doc|docx)$/i.test(fileName)) return '📘'
  if (/\.(xls|xlsx)$/i.test(fileName)) return '📗'
  if (/\.(ppt|pptx)$/i.test(fileName)) return '📙'
  if (/\.(zip|rar|7z|tar|gz)$/i.test(fileName)) return '📦'
  if (/\.(txt|md|log|json|xml|csv)$/i.test(fileName)) return '📝'
  if (/\.(py|js|ts|vue|html|css|cpp|c|h|java|go|rs)$/i.test(fileName)) return '💻'
  return '📎'
}

function openPreview(msg) {
  previewMsgData.value = msg
  previewVisible.value = true
}

function canRecall(msg) {
  if (!msg) return false
  if (msg.sender !== userStore.username) return false
  if (msg.recalled) return false
  const elapsed = Date.now() - (msg.timestamp || 0)
  return elapsed < 120000
}

function isFileType(msg) {
  return msg && (msg.contentType === 'file' || msg.contentType === 'image' || msg.contentType === 'video')
}

function copyText(msg) {
  if (msg && msg.content) {
    navigator.clipboard.writeText(msg.content).catch(() => {
      // fallback
      const ta = document.createElement('textarea')
      ta.value = msg.content
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    })
  }
  contextMenu.value.show = false
}

function previewFromMenu(msg) {
  contextMenu.value.show = false
  if (msg) {
    openPreview(msg)
  }
}

function recallMsg(msg) {
  if (msg) {
    if (chatStore.isFriendChat) {
      chatWs.recallFriendMessage(msg.id, chatStore.currentFriendUsername)
    } else {
      chatWs.recallMessage(msg.id, chatStore.currentRoomId)
    }
  }
  contextMenu.value.show = false
}

function deleteMsg(msg) {
  if (msg && confirm('确定删除此消息？')) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'selected', [msg.id])
  }
  contextMenu.value.show = false
}

function clearAllMessages() {
  contextMenu.value.show = false
  if (confirm('确定要清空所有聊天记录吗？\n此操作不可恢复！')) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'all')
  }
}

function deleteOldMessages() {
  contextMenu.value.show = false
  const days = prompt('删除多少天前的消息：', '7')
  if (days === null) return
  const n = parseInt(days)
  if (isNaN(n) || n < 1) { alert('请输入有效的天数'); return }
  const cutoff = Date.now() - n * 24 * 60 * 60 * 1000
  chatWs.deleteMessages(chatStore.currentRoomId, 'before', [], cutoff)
}

function deleteRecentMessages() {
  contextMenu.value.show = false
  const days = prompt('删除最近几天的消息：', '1')
  if (days === null) return
  const n = parseInt(days)
  if (isNaN(n) || n < 1) { alert('请输入有效的天数'); return }
  const cutoff = Date.now() - n * 24 * 60 * 60 * 1000
  chatWs.deleteMessages(chatStore.currentRoomId, 'after', [], cutoff)
}

function onContextMenu(e, msg) {
  // 计算菜单位置，确保不超出屏幕
  const menuW = 200, menuH = 260
  let x = Math.min(e.clientX, window.innerWidth - menuW)
  let y = Math.min(e.clientY, window.innerHeight - menuH)
  contextMenu.value = { show: true, x, y, msg }
}

let longPressTimer = null
let longPressTriggered = false

function onTouchStart(e, msg) {
  longPressTriggered = false
  longPressTimer = setTimeout(() => {
    longPressTriggered = true
    const touch = e.touches[0]
    const menuW = 200, menuH = 260
    const x = Math.min(touch.clientX, window.innerWidth - menuW)
    const y = Math.min(touch.clientY, window.innerHeight - menuH)
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
  if (listRef.value && listRef.value.scrollTop === 0) {
    const msgs = props.friendMode ? chatStore.friendMessages : chatStore.messages
    if (msgs.length > 0) {
      const firstMsg = msgs[0]
      if (firstMsg && firstMsg.timestamp) {
        loadingMore.value = true
        if (props.friendMode) {
          chatWs.requestFriendHistory(chatStore.currentFriendUsername, 50, firstMsg.timestamp)
        } else {
          chatWs.requestHistory(chatStore.currentRoomId, 50, firstMsg.timestamp)
        }
        setTimeout(() => { loadingMore.value = false }, 2000)
      }
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

watch(() => chatStore.messages.length, () => {
  scrollToBottom()
})

watch(() => chatStore.friendMessages.length, () => {
  scrollToBottom()
})

watch(() => chatStore.currentRoomId, () => {
  scrollToBottom()
})

watch(() => chatStore.currentFriendUsername, () => {
  scrollToBottom()
})

function closeMenu() {
  contextMenu.value.show = false
}

onMounted(() => {
  // overlay handles closing now
})
onUnmounted(() => {
  // cleanup
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

.system-message {
  text-align: center;
  color: var(--text-system);
  font-size: 12px;
  padding: 6px 0;
}
.recalled {
  font-style: italic;
}

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
  color: var(--text-primary);
  border-radius: 12px 12px 12px 4px;
  padding: 10px 14px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
  word-break: break-word;
  max-width: 100%;
}
.msg-bubble.bubble-mine {
  background: var(--bg-bubble-mine);
  color: #fff;
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

.msg-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  background: var(--bg-primary);
  border-radius: 8px;
  cursor: pointer;
  min-width: 200px;
  transition: background 0.15s;
}
.msg-file:hover {
  opacity: 0.85;
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

/* 右键菜单 - Teleported to body, uses :global */

/* 移动端适配 */
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
  .msg-video-card {
    max-width: 220px;
  }
  .video-placeholder {
    width: 220px;
    height: 130px;
  }
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

<style>
.context-menu-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 3000;
}
.context-menu {
  position: fixed;
  background: var(--bg-secondary, #fff);
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 10px;
  padding: 6px 0;
  box-shadow: 0 6px 20px rgba(0,0,0,0.18);
  z-index: 3001;
  min-width: 180px;
  max-width: 240px;
  animation: ctxFadeIn 0.12s ease-out;
}
@keyframes ctxFadeIn {
  from { opacity: 0; transform: scale(0.95); }
  to { opacity: 1; transform: scale(1); }
}
.context-menu-item {
  padding: 10px 16px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-primary, #333);
  display: flex;
  align-items: center;
  gap: 8px;
  transition: background 0.12s;
  white-space: nowrap;
}
.context-menu-item:hover {
  background: var(--bg-hover, rgba(0,0,0,0.05));
}
.context-menu-item.danger {
  color: var(--danger, #e74c3c);
}
.context-menu-item.danger:hover {
  background: rgba(231, 76, 60, 0.08);
}
.menu-icon {
  font-size: 15px;
  flex-shrink: 0;
  width: 20px;
  text-align: center;
}
.context-menu-divider {
  height: 1px;
  background: var(--border-light, #eee);
  margin: 4px 12px;
}

@media (max-width: 768px) {
  .context-menu {
    min-width: 160px;
  }
  .context-menu-item {
    padding: 12px 16px;
    font-size: 14px;
  }
}
</style>
