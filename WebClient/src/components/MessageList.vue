<template>
  <div class="message-list-shell">
    <div class="message-list" ref="listRef" @scroll="onScroll" tabindex="-1"
         role="log" aria-live="polite" aria-relevant="additions text"
         :aria-busy="loadingMore" :aria-label="messages.timeline">
    <div v-if="loadingMore" class="loading-more" role="status">{{ messages.loading }}</div>
    <div v-if="virtualWindow.top > 0" class="virtual-spacer" aria-hidden="true"
         :style="{ height: virtualWindow.top + 'px' }"></div>

    <div v-for="(msg, idx) in visibleMessages" :key="messageKey(msg, virtualWindow.start + idx)"
         :ref="el => setMessageElement(el, messageKey(msg, virtualWindow.start + idx))"
         class="message-wrapper" role="article" :aria-label="messageAriaLabel(msg)"
         :aria-posinset="virtualWindow.start + idx + 1" :aria-setsize="displayMessages.length">
      <!-- 系统消息 -->
      <div v-if="msg.contentType === 'system'" class="system-message">
        {{ msg.content }}
      </div>

      <!-- 已撤回 -->
      <div v-else-if="msg.recalled" class="system-message recalled">
        {{ msg.senderName || msg.sender }}{{ messages.recalledSuffix }}
      </div>

      <!-- 普通消息 -->
      <div v-else class="message-row" :class="{ mine: isMine(msg) }">
        <!-- 头像 -->
        <button type="button" class="msg-avatar" @click="openUser(msg)"
                :aria-label="`${messages.viewProfilePrefix}${msg.senderName || msg.sender}${messages.viewProfileSuffix}`">
          <img v-if="getAvatarSrc(msg.sender)" :src="getAvatarSrc(msg.sender)" class="avatar"
               :alt="`${messages.avatarPrefix}${msg.senderName || msg.sender}${messages.avatarSuffix}`" />
          <div v-else class="avatar avatar-placeholder"
               :style="{ background: hashColor(msg.sender) }">
            {{ (msg.senderName || msg.sender || '?').charAt(0) }}
          </div>
        </button>

        <div class="msg-body">
          <!-- 发送者名 -->
          <div class="msg-sender" v-if="!isMine(msg)">{{ msg.senderName || msg.sender }}</div>

          <!-- 消息气泡 -->
          <div class="msg-bubble" :class="{ 'bubble-mine': isMine(msg) }"
               tabindex="0" @keydown="onBubbleKeydown($event, msg)"
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
              <button v-if="msg.fileCleared" type="button" class="msg-expired-image"
                      :aria-label="`${attachmentMessages.expiredImagePrefix}${msg.fileName || attachmentMessages.image}`"
                      @click="openPreview(msg)">
                <div class="expired-icon">📷</div>
                <div class="expired-name text-ellipsis">{{ msg.fileName || attachmentMessages.image }}</div>
                <div class="expired-text">{{ attachmentUnavailableText(msg) }}</div>
              </button>
              <button v-else-if="msg.imageData" type="button" class="msg-image-button"
                      :aria-label="`${attachmentMessages.previewImagePrefix}${msg.fileName || attachmentMessages.chatImage}`"
                      @click="openPreview(msg)">
                <img :src="'data:image/png;base64,' + msg.imageData" class="msg-image"
                     :alt="msg.fileName || attachmentMessages.chatImage" />
              </button>
              <button v-else-if="msg.thumbnail" type="button" class="msg-image-button"
                      :aria-label="`${attachmentMessages.previewImagePrefix}${msg.fileName || attachmentMessages.chatImage}`"
                      @click="openPreview(msg)">
                <img :src="'data:image/jpeg;base64,' + msg.thumbnail" class="msg-image"
                     :alt="msg.fileName || attachmentMessages.imageThumbnail" />
              </button>
              <button v-else type="button" class="msg-file"
                      :aria-label="`${attachmentMessages.previewImagePrefix}${msg.fileName || attachmentMessages.image}`"
                      @click="openPreview(msg)">
                📷 {{ msg.fileName || attachmentMessages.image }}
                <span class="file-size">{{ formatSize(msg.fileSize) }}</span>
              </button>
            </template>

            <!-- 视频 -->
            <template v-else-if="msg.contentType === 'video' || (msg.contentType === 'file' && isVideoFile(msg.fileName))">
              <button v-if="msg.fileCleared" type="button" class="msg-expired-video"
                      :aria-label="`${attachmentMessages.expiredVideoPrefix}${msg.fileName || attachmentMessages.video}`"
                      @click="openPreview(msg)">
                <div class="expired-icon">🎬</div>
                <div class="expired-name text-ellipsis">{{ msg.fileName || attachmentMessages.video }}</div>
                <div class="expired-text">{{ attachmentUnavailableText(msg) }}</div>
              </button>
              <button v-else type="button" class="msg-video-card"
                      :aria-label="`${attachmentMessages.previewVideoPrefix}${msg.fileName || attachmentMessages.video}`"
                      @click="openPreview(msg)">
                <img v-if="msg.thumbnail" :src="'data:image/jpeg;base64,' + msg.thumbnail"
                     class="video-thumbnail" :alt="`${msg.fileName || attachmentMessages.video}${attachmentMessages.thumbnailSuffix}`" />
                <div v-else class="video-placeholder">
                  <span>🎬</span>
                </div>
                <div class="video-play-btn">▶</div>
                <div class="video-info">
                  <span class="file-name text-ellipsis">{{ msg.fileName }}</span>
                  <span class="file-size">{{ formatSize(msg.fileSize) }}</span>
                </div>
              </button>
            </template>

            <!-- 其他文件 -->
            <template v-else-if="msg.contentType === 'file'">
              <button type="button" class="msg-file" :class="{ expired: msg.fileCleared }"
                      :aria-label="`${msg.fileCleared ? attachmentMessages.expiredFilePrefix : attachmentMessages.previewFilePrefix}${msg.fileName || attachmentMessages.file}`"
                      @click="openPreview(msg)">
                <div class="file-icon">{{ getFileIcon(msg.fileName) }}</div>
                <div class="file-info">
                  <div class="file-name text-ellipsis">{{ msg.fileName }}</div>
                  <div class="file-size">{{ msg.fileCleared ? attachmentUnavailableText(msg) : formatSize(msg.fileSize) }}</div>
                </div>
              </button>
            </template>
          </div>

          <!-- 时间 -->
          <div class="msg-time" :class="{ 'time-mine': isMine(msg) }">
            {{ formatTime(msg.timestamp) }}
            <span v-if="msg.deliveryState === 'sending'" class="delivery-state"> {{ messages.sending }}</span>
            <button v-else-if="msg.deliveryState === 'failed'" type="button" class="delivery-retry"
                    @click="chatStore.retryMessage(msg)" :aria-label="messages.failedRetryLabel">
              {{ messages.failedRetry }}</button>
            <span v-else-if="isMine(msg) && msg.deliveryState === 'read'"
                  class="delivery-state"> {{ messages.read }}</span>
            <span v-else-if="isMine(msg) && msg.id" class="delivery-state"> {{ messages.sent }}</span>
          </div>
        </div>
      </div>
    </div>
    <div v-if="virtualWindow.bottom > 0" class="virtual-spacer" aria-hidden="true"
         :style="{ height: virtualWindow.bottom + 'px' }"></div>
      <p class="visually-hidden" :role="copyFailed ? 'alert' : 'status'"
         aria-live="polite" aria-atomic="true">{{ copyAnnouncement }}</p>
    </div>

    <button v-if="pendingNewMessages" class="new-message-jump" type="button"
            :aria-label="`${pendingNewMessagesLabel}${messages.backToLatestSuffix}`"
            @click="revealNewMessages">
      {{ pendingNewMessagesLabel }} ↓
    </button>
    <p class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
      {{ pendingNewMessages ? `${pendingNewMessagesLabel}${messages.readingHistorySuffix}` : '' }}
    </p>

    <!-- 右键菜单 -->
    <Teleport to="body">
      <div v-if="contextMenu.show" class="context-menu-overlay"
           @click="closeMenu()" @contextmenu.prevent="closeMenu()">
        <div ref="contextMenuElement" class="context-menu" role="menu" :aria-label="actionMessages.menu"
             :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
             @click.stop @keydown="onContextMenuKeydown">
          <!-- 复制可用的普通文本 -->
          <div class="context-menu-item" role="menuitem" tabindex="0"
               v-if="canCopyText(contextMenu.msg)"
               @click="copyText(contextMenu.msg)" @keydown.enter="copyText(contextMenu.msg)">
            <span class="menu-icon">📋</span> {{ actionMessages.copyText }}
          </div>

          <!-- 预览文件 (文件/图片/视频消息，所有人可用) -->
          <div class="context-menu-item" role="menuitem" tabindex="0"
            v-if="contextMenu.msg && isFileType(contextMenu.msg) && !contextMenu.msg.recalled"
               @click="previewFromMenu(contextMenu.msg)" @keydown.enter="previewFromMenu(contextMenu.msg)">
            <span class="menu-icon">👁️</span> {{ actionMessages.previewFile }}
          </div>

          <!-- 下载文件 -->
          <div class="context-menu-item" role="menuitem" tabindex="0"
            v-if="contextMenu.msg && isFileType(contextMenu.msg) && !contextMenu.msg.recalled && !contextMenu.msg.fileCleared"
               @click="downloadFromMenu(contextMenu.msg)" @keydown.enter="downloadFromMenu(contextMenu.msg)">
            <span class="menu-icon">⬇️</span> {{ actionMessages.downloadFile }}
          </div>

          <!-- 转发 -->
          <div class="context-menu-item" role="menuitem" tabindex="0"
               v-if="contextMenu.msg && contextMenu.msg.id && !contextMenu.msg.recalled && contextMenu.msg.contentType !== 'system'"
               @click="forwardFromMenu(contextMenu.msg)" @keydown.enter="forwardFromMenu(contextMenu.msg)">
            <span class="menu-icon">📨</span> {{ actionMessages.forward }}
          </div>

          <!-- 撤回 (自己的消息, 2分钟内) -->
          <div class="context-menu-item" role="menuitem" tabindex="0"
               v-if="canRecall(contextMenu.msg)"
               @click="recallMsg(contextMenu.msg)" @keydown.enter="recallMsg(contextMenu.msg)">
            <span class="menu-icon">↩️</span> {{ actionMessages.recall }}
          </div>

          <!-- 管理员: 删除此消息 -->
          <div class="context-menu-item danger" role="menuitem" tabindex="0"
               v-if="!isPrivateMode() && chatStore.isAdmin && contextMenu.msg && contextMenu.msg.id && !contextMenu.msg.recalled"
               @click="deleteMsg(contextMenu.msg)" @keydown.enter="deleteMsg(contextMenu.msg)">
            <span class="menu-icon">🗑️</span> {{ actionMessages.deleteMessage }}
          </div>

          <!-- 管理员子菜单 -->
          <template v-if="!isPrivateMode() && chatStore.isAdmin">
            <div class="context-menu-divider"></div>
            <div class="context-menu-item danger" role="menuitem" tabindex="0"
                 @click="clearAllMessages" @keydown.enter="clearAllMessages">
              <span class="menu-icon">🧹</span> {{ actionMessages.clearAll }}
            </div>
            <div class="context-menu-item danger" role="menuitem" tabindex="0"
                 @click="deleteOldMessages" @keydown.enter="deleteOldMessages">
              <span class="menu-icon">📅</span> {{ actionMessages.deleteOlder }}
            </div>
            <div class="context-menu-item danger" role="menuitem" tabindex="0"
                 @click="deleteRecentMessages" @keydown.enter="deleteRecentMessages">
              <span class="menu-icon">🕐</span> {{ actionMessages.deleteRecent }}
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

    <ForwardDialog
      v-if="forwardDialogVisible"
      :submitting="forwardSubmitting"
      @close="closeForwardDialog"
      @confirm="confirmForward"
    />
  </div>
</template>

<script setup>
import { computed, ref, shallowRef, watch, nextTick, inject, onMounted, onUnmounted } from 'vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType, MAX_SMALL_FILE } from '../services/websocket'
import FilePreview from './FilePreview.vue'
import ForwardDialog from './ForwardDialog.vue'
import { calculateVirtualWindow } from '../ui/virtualWindow'
import { copyMessageText } from '../messaging/copyMessageText'
import { addPendingNewMessages } from '../messaging/newMessageIndicator'
import { messageActionMessages, messageAttachmentMessages, messageTimelineMessages } from '../localization/webLocale'

const userStore = useUserStore()
const chatStore = useChatStore()
const messages = computed(() => messageTimelineMessages(userStore.locale))
const attachmentMessages = computed(() => messageAttachmentMessages(userStore.locale))
const actionMessages = computed(() => messageActionMessages(userStore.locale))
const openUserInfo = inject('openUserInfo')
const hashColor = inject('hashColor')

const listRef = ref(null)
const loadingMore = ref(false)
const previewVisible = ref(false)
const previewMsgData = ref(null)
const contextMenu = ref({ show: false, x: 0, y: 0, msg: null })
const contextMenuElement = ref(null)
const copyAnnouncement = ref('')
const copyFailed = ref(false)
const forwardDialogVisible = ref(false)
const forwardSubmitting = ref(false)
const forwardSourceMsg = ref(null)

const props = defineProps({
  friendMode: { type: Boolean, default: false }
})

const displayMessages = computed(() => {
  return props.friendMode ? chatStore.friendMessages : chatStore.messages
})
const scrollTop = ref(0)
const viewportHeight = ref(600)
const measuredHeights = shallowRef(new Map())
const stickToBottom = ref(true)
const pendingNewMessages = ref(0)
const pendingNewMessagesLabel = computed(() => pendingNewMessages.value > 0
  ? `${pendingNewMessages.value >= 99 ? '99+' : pendingNewMessages.value}${messages.value.newMessagesSuffix}`
  : '')
let historyAnchor = null
let historyTimer = null
let messageResizeObserver = null
let viewportResizeObserver = null
const messageElements = new Map()

function messageKey(msg, index) {
  if (msg?.id) return `server:${msg.id}`
  if (msg?.clientMessageId) return `client:${msg.clientMessageId}`
  if (msg?.sequence) return `sequence:${msg.sequence}`
  return `legacy:${msg?.timestamp || 0}:${index}`
}

function estimatedMessageHeight(msg) {
  if (msg?.contentType === 'system' || msg?.recalled) return 40
  if (isFileType(msg)) return 230
  if (msg?.contentType === 'emoji') return 86
  return Math.min(220, 72 + Math.ceil(String(msg?.content || '').length / 48) * 20)
}

const virtualWindow = computed(() => calculateVirtualWindow({
  heights: displayMessages.value.map((message, index) =>
    measuredHeights.value.get(messageKey(message, index)) || estimatedMessageHeight(message)),
  scrollTop: scrollTop.value,
  viewportHeight: viewportHeight.value,
  overscan: 700,
  threshold: 80
}))

const visibleMessages = computed(() => displayMessages.value.slice(
  virtualWindow.value.start, virtualWindow.value.end))

function setMessageElement(element, key) {
  const previous = messageElements.get(key)
  if (previous && previous !== element && messageResizeObserver)
    messageResizeObserver.unobserve(previous)
  if (!element) {
    messageElements.delete(key)
    return
  }
  if (previous === element) return
  element.dataset.virtualKey = key
  messageElements.set(key, element)
  messageResizeObserver?.observe(element)
}

function isMine(msg) {
  return msg.sender === userStore.username
}

function getAvatarSrc(username) {
  const data = userStore.getAvatar(username)
  if (data) return 'data:image/png;base64,' + data
  userStore.requestAvatarIfAllowed(username)
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

function messageAriaLabel(msg) {
  if (msg.contentType === 'system') return `${messages.value.systemPrefix}${msg.content || ''}`
  if (msg.recalled) return `${msg.senderName || msg.sender || messages.value.user}${messages.value.recalledSuffix}`
  const sender = isMine(msg) ? messages.value.self : (msg.senderName || msg.sender || messages.value.user)
  const content = isFileType(msg)
    ? `${messages.value.filePrefix}${msg.fileName || ''}`
    : (msg.content || '')
  const state = msg.deliveryState === 'sending' ? messages.value.sending
    : (msg.deliveryState === 'failed' ? messages.value.failedRetry
      : (isMine(msg) && msg.deliveryState === 'read' ? messages.value.read
        : (isMine(msg) && msg.id ? messages.value.sent : '')))
  const time = formatTime(msg.timestamp)
  return `${sender}${messages.value.contentSeparator}${content}`
    + `${state ? messages.value.separator + state : ''}`
    + `${time ? messages.value.separator + time : ''}`
}

function formatSize(size) {
  if (!size) return ''
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  if (size < 1024 * 1024 * 1024) return (size / (1024*1024)).toFixed(1) + ' MB'
  return (size / (1024*1024*1024)).toFixed(2) + ' GB'
}

function attachmentUnavailableText(msg) {
  const safeReason = typeof msg?.clearReason === 'string' ? msg.clearReason : ''
  return safeReason.trim() ? safeReason : attachmentMessages.value.expired
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
  if (msg?.fileCleared) {
    alert(attachmentMessages.value.cannotPreview)
    return
  }
  previewMsgData.value = msg
  previewVisible.value = true
}

function canRecall(msg) {
  if (!msg) return false
  if (msg.sender !== userStore.username) return false
  if (msg.recalled) return false
  if (!msg.id || msg.deliveryState === 'sending' || msg.deliveryState === 'failed') return false
  const elapsed = Date.now() - (msg.timestamp || 0)
  return elapsed < 120000
}

function isFileType(msg) {
  return msg && (msg.contentType === 'file' || msg.contentType === 'image' || msg.contentType === 'video')
}

function canCopyText(msg) {
  return Boolean(msg && !msg.recalled && msg.contentType !== 'system'
    && !isFileType(msg) && typeof msg.content === 'string' && msg.content.length > 0)
}

function isPrivateMode() {
  return props.friendMode || chatStore.isFriendChat
}

async function copyText(msg) {
  contextMenu.value.show = false
  if (!canCopyText(msg)) return
  copyAnnouncement.value = ''
  copyFailed.value = false
  await nextTick()
  const copied = await copyMessageText(msg.content)
  copyFailed.value = !copied
  copyAnnouncement.value = copied ? messages.value.copySucceeded : messages.value.copyFailed
}

function previewFromMenu(msg) {
  contextMenu.value.show = false
  if (msg) {
    openPreview(msg)
  }
}

function downloadFromMenu(msg) {
  contextMenu.value.show = false
  if (msg?.fileCleared) {
    alert(attachmentMessages.value.cannotDownload)
    return
  }
  if (msg && msg.fileId) {
    chatStore._triggerDownload(msg.fileId, msg.fileName, msg.fileSize)
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

async function forwardFromMenu(msg) {
  contextMenu.value.show = false
  if (!msg) return

  if (isFileType(msg) && msg.fileCleared) {
    alert(attachmentMessages.value.cannotForward)
    return
  }
  forwardSourceMsg.value = msg
  forwardDialogVisible.value = true
}

function closeForwardDialog() {
  if (forwardSubmitting.value) return
  forwardDialogVisible.value = false
  forwardSourceMsg.value = null
}

async function confirmForward(targets) {
  if (!forwardSourceMsg.value) return
  if (!Array.isArray(targets) || targets.length === 0) {
    alert(actionMessages.value.selectForwardTarget)
    return
  }

  try {
    forwardSubmitting.value = true
    await chatStore.forwardMessageToTargets(forwardSourceMsg.value, targets)
    alert(`${actionMessages.value.forwardSubmittedPrefix}${targets.length}${actionMessages.value.forwardSubmittedSuffix}`)
    closeForwardDialog()
  } catch (err) {
    alert(err?.code === 'ATTACHMENT_UNAVAILABLE'
      ? attachmentMessages.value.cannotForward
      : (err?.message || actionMessages.value.forwardFailed))
  } finally {
    forwardSubmitting.value = false
  }
}

function deleteMsg(msg) {
  if (isPrivateMode()) {
    contextMenu.value.show = false
    return
  }
  if (msg && confirm(actionMessages.value.confirmDelete)) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'selected', [msg.id])
  }
  contextMenu.value.show = false
}

function clearAllMessages() {
  if (isPrivateMode()) {
    contextMenu.value.show = false
    return
  }
  contextMenu.value.show = false
  if (confirm(actionMessages.value.confirmClear)) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'all')
  }
}

function deleteOldMessages() {
  if (isPrivateMode()) {
    contextMenu.value.show = false
    return
  }
  contextMenu.value.show = false
  const days = prompt(actionMessages.value.deleteOlderPrompt, '7')
  if (days === null) return
  const n = parseInt(days)
  if (isNaN(n) || n < 1) { alert(actionMessages.value.invalidDays); return }
  const cutoff = Date.now() - n * 24 * 60 * 60 * 1000
  chatWs.deleteMessages(chatStore.currentRoomId, 'before', [], cutoff)
}

function deleteRecentMessages() {
  if (isPrivateMode()) {
    contextMenu.value.show = false
    return
  }
  contextMenu.value.show = false
  const days = prompt(actionMessages.value.deleteRecentPrompt, '1')
  if (days === null) return
  const n = parseInt(days)
  if (isNaN(n) || n < 1) { alert(actionMessages.value.invalidDays); return }
  const cutoff = Date.now() - n * 24 * 60 * 60 * 1000
  chatWs.deleteMessages(chatStore.currentRoomId, 'after', [], cutoff)
}

function onContextMenu(e, msg) {
  // 计算菜单位置，确保不超出屏幕
  const menuW = 200, menuH = 260
  let x = Math.min(e.clientX, window.innerWidth - menuW)
  let y = Math.min(e.clientY, window.innerHeight - menuH)
  contextMenuTrigger = typeof e.currentTarget?.focus === 'function' ? e.currentTarget : null
  contextMenu.value = { show: true, x, y, msg }
  nextTick(() => contextMenuElement.value?.querySelector('[role="menuitem"]')?.focus())
}

function onBubbleKeydown(event, msg) {
  if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return
  event.preventDefault()
  const rect = event.currentTarget.getBoundingClientRect()
  onContextMenu({
    clientX: rect.left + 12,
    clientY: rect.bottom + 4,
    currentTarget: event.currentTarget
  }, msg)
}

function onContextMenuKeydown(event) {
  const items = Array.from(contextMenuElement.value?.querySelectorAll('[role="menuitem"]') || [])
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    closeMenu(true)
    return
  }
  if (!items.length || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const current = items.indexOf(document.activeElement)
  const target = event.key === 'Home' ? 0
    : event.key === 'End' ? items.length - 1
      : event.key === 'ArrowDown' ? (current < 0 ? 0 : (current + 1) % items.length)
        : (current < 0 ? items.length - 1 : (current - 1 + items.length) % items.length)
  items[target].focus()
}

let longPressTimer = null
let longPressTriggered = false
let contextMenuTrigger = null

function onTouchStart(e, msg) {
  longPressTriggered = false
  longPressTimer = setTimeout(() => {
    longPressTriggered = true
    const touch = e.touches[0]
    const menuW = 200, menuH = 260
    const x = Math.min(touch.clientX, window.innerWidth - menuW)
    const y = Math.min(touch.clientY, window.innerHeight - menuH)
    contextMenuTrigger = null
    contextMenu.value = { show: true, x, y, msg }
    nextTick(() => contextMenuElement.value?.querySelector('[role="menuitem"]')?.focus())
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
  if (!listRef.value) return
  scrollTop.value = listRef.value.scrollTop
  viewportHeight.value = listRef.value.clientHeight
  stickToBottom.value = listRef.value.scrollHeight
    - listRef.value.scrollTop - listRef.value.clientHeight < 80
  if (stickToBottom.value) pendingNewMessages.value = 0
  if (listRef.value.scrollTop <= 2 && !loadingMore.value) {
    const msgs = props.friendMode ? chatStore.friendMessages : chatStore.messages
    if (msgs.length > 0) {
      const firstMsg = msgs[0]
      if (firstMsg && firstMsg.timestamp) {
        loadingMore.value = true
        historyAnchor = {
          scrollHeight: listRef.value.scrollHeight,
          scrollTop: listRef.value.scrollTop
        }
        if (props.friendMode) {
          chatWs.requestFriendHistory(chatStore.currentFriendUsername, 50, firstMsg.timestamp)
        } else {
          chatWs.requestHistory(chatStore.currentRoomId, 50, firstMsg.timestamp)
        }
        if (historyTimer) clearTimeout(historyTimer)
        historyTimer = setTimeout(() => {
          loadingMore.value = false
          historyAnchor = null
        }, 2000)
      }
    }
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) {
      listRef.value.scrollTop = listRef.value.scrollHeight
      scrollTop.value = listRef.value.scrollTop
      viewportHeight.value = listRef.value.clientHeight
      stickToBottom.value = true
      pendingNewMessages.value = 0
    }
  })
}

function revealNewMessages() {
  pendingNewMessages.value = 0
  scrollToBottom()
  nextTick(() => listRef.value?.focus({ preventScroll: true }))
}

watch(() => displayMessages.value.length, async (nextLength, previousLength) => {
  await nextTick()
  if (!listRef.value) return
  if (historyAnchor && nextLength > previousLength) {
    listRef.value.scrollTop = historyAnchor.scrollTop
      + (listRef.value.scrollHeight - historyAnchor.scrollHeight)
    scrollTop.value = listRef.value.scrollTop
    loadingMore.value = false
    historyAnchor = null
    if (historyTimer) clearTimeout(historyTimer)
    historyTimer = null
    return
  }
  if (stickToBottom.value) {
    scrollToBottom()
  } else if (nextLength > previousLength) {
    pendingNewMessages.value = addPendingNewMessages(
      pendingNewMessages.value, nextLength - previousLength)
  }
})

watch(() => [chatStore.currentRoomId, chatStore.currentFriendUsername, props.friendMode], () => {
  measuredHeights.value = new Map()
  historyAnchor = null
  scrollTop.value = 0
  stickToBottom.value = true
  pendingNewMessages.value = 0
  scrollToBottom()
})

function closeMenu(restoreFocus = false) {
  contextMenu.value.show = false
  if (restoreFocus && contextMenuTrigger) nextTick(() => contextMenuTrigger?.focus())
  if (!restoreFocus) contextMenuTrigger = null
}

onMounted(() => {
  messageResizeObserver = new ResizeObserver(entries => {
    let changed = false
    const next = new Map(measuredHeights.value)
    for (const entry of entries) {
      const key = entry.target.dataset.virtualKey
      if (!key) continue
      const height = Math.ceil(entry.borderBoxSize?.[0]?.blockSize
        || entry.contentRect.height) + 4
      if (Math.abs((next.get(key) || 0) - height) > 1) {
        next.set(key, height)
        changed = true
      }
    }
    if (changed) measuredHeights.value = next
  })
  for (const element of messageElements.values())
    messageResizeObserver.observe(element)
  if (listRef.value) {
    viewportHeight.value = listRef.value.clientHeight
    viewportResizeObserver = new ResizeObserver(() => {
      if (listRef.value) viewportHeight.value = listRef.value.clientHeight
    })
    viewportResizeObserver.observe(listRef.value)
  }
})
onUnmounted(() => {
  messageResizeObserver?.disconnect()
  viewportResizeObserver?.disconnect()
  if (historyTimer) clearTimeout(historyTimer)
})
</script>

<style scoped>
.message-list-shell {
  position: relative;
  flex: 1;
  min-height: 0;
}

.message-list {
  height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.new-message-jump {
  position: absolute;
  right: 20px;
  bottom: 14px;
  z-index: 4;
  padding: 8px 14px;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  color: var(--text-link);
  background: var(--bg-secondary);
  box-shadow: var(--shadow-md);
  cursor: pointer;
}

.new-message-jump:hover,
.new-message-jump:focus-visible {
  background: var(--bg-hover);
}

.loading-more {
  text-align: center;
  color: var(--text-tertiary);
  font-size: 12px;
  padding: 8px;
}

.virtual-spacer {
  flex: 0 0 auto;
  width: 1px;
  pointer-events: none;
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
  border: 0;
  padding: 0;
  background: transparent;
  border-radius: 50%;
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
.msg-image-button {
  display: block;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
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
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
}
.msg-file:hover {
  opacity: 0.85;
}
.msg-file.expired {
  opacity: 0.6;
  cursor: not-allowed;
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
.delivery-state {
  color: var(--text-tertiary);
}
.delivery-retry {
  border: 0;
  padding: 0;
  background: transparent;
  color: #d9534f;
  cursor: pointer;
  font-size: inherit;
}

/* 视频卡片 */
.msg-video-card {
  position: relative;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  max-width: 280px;
  background: #000;
  border: 0;
  padding: 0;
  color: inherit;
  font: inherit;
  text-align: left;
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

.msg-expired-image,
.msg-expired-video {
  width: 240px;
  height: 140px;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  cursor: pointer;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 8px;
  border: 0;
  font: inherit;
}

.msg-expired-image {
  background: linear-gradient(135deg, #7a7a7a, #4b4b4b);
}

.msg-expired-video {
  background: linear-gradient(135deg, #7a7a7a, #4b4b4b);
}

.expired-icon {
  font-size: 26px;
  line-height: 1;
  margin-bottom: 8px;
}

.expired-name {
  max-width: 100%;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}

.expired-text {
  font-size: 12px;
  opacity: 0.92;
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
