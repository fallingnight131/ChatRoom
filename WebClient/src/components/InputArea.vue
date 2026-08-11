<template>
  <div class="input-area">
    <!-- 工具栏 -->
    <div class="input-toolbar" role="toolbar" aria-label="消息工具">
      <button class="btn-icon" @click="showEmoji = !showEmoji" title="表情"
              aria-label="选择表情" :aria-expanded="showEmoji">😊</button>
      <button class="btn-icon" @click="triggerFileInput" title="发送文件"
              aria-label="选择要发送的文件">📎</button>
      <input ref="fileInput" type="file" class="visually-hidden" tabindex="-1"
             aria-label="选择要发送的文件" @change="onFileSelected" />
      <input ref="recoveryFileInput" type="file" class="visually-hidden" tabindex="-1"
             aria-label="重新选择待发送文件" @change="onRecoveryFileSelected" />

      <!-- 上传进度 -->
      <div v-if="Object.keys(chatStore.uploads).length > 0" class="upload-status"
           role="status" aria-live="polite" aria-label="文件上传状态">
        <div v-for="(u, uid) in chatStore.uploads" :key="uid" class="upload-item">
          <span class="text-ellipsis" style="max-width:120px">{{ u.fileName }}</span>
          <div class="progress-bar" style="width:80px" role="progressbar"
               aria-label="文件上传进度" aria-valuemin="0" aria-valuemax="100"
               :aria-valuenow="uploadPercent(u)">
            <div class="progress-fill" :style="{ width: uploadPercent(u) + '%' }"></div>
          </div>
          <span class="upload-pct">{{ uploadPercent(u) }}%</span>
          <span v-if="u.status === 'cos_uploading'" class="upload-phase">☁同步中</span>
          <button v-if="u.status === 'uploading'" class="btn-icon" @click="chatStore.pauseUpload(uid)" title="暂停">⏸</button>
          <button v-if="u.status === 'paused'" class="btn-icon" @click="chatStore.resumeUpload(uid)" title="继续">▶</button>
          <button class="btn-icon" @click="chatStore.cancelUpload(uid)" title="取消">✖</button>
        </div>
      </div>

      <div v-if="recoverableAttachmentCommands.length" class="attachment-recovery"
           role="status" aria-live="polite" aria-label="待恢复的文件发送">
        <div v-for="command in recoverableAttachmentCommands"
             :key="command.clientMessageId" class="upload-item recovery-item">
          <span class="text-ellipsis recovery-name">{{ command.fileName }}</span>
          <span class="recovery-state">{{ attachmentStateLabel(command) }}</span>
          <button v-if="command.state === 'needs_source'" type="button" class="btn-link"
                  :aria-label="`重新选择 ${command.fileName}`"
                  @click="chooseReplacement(command)">重新选择</button>
          <button v-else type="button" class="btn-link"
                  :aria-label="`重试发送 ${command.fileName}`"
                  @click="chatStore.retryAttachmentCommand(command)">重试</button>
          <button type="button" class="btn-link btn-link-danger"
                  :aria-label="`取消发送 ${command.fileName}`"
                  @click="chatStore.cancelAttachmentCommand(command)">取消</button>
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
                aria-label="消息内容"
                @keydown.enter.exact="sendMessage"
                @keydown.enter.shift.exact.prevent="text += '\n'"
                rows="1"></textarea>
      <button class="btn btn-primary send-btn" @click="sendMessage" :disabled="!text.trim()"
              aria-label="发送消息">
        发送
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MAX_SMALL_FILE } from '../services/websocket'
import { conversationCache } from '../persistence/conversationCache'
import EmojiPicker from './EmojiPicker.vue'

const props = defineProps({
  friendMode: { type: Boolean, default: false }
})

const userStore = useUserStore()
const chatStore = useChatStore()

const text = ref('')
const showEmoji = ref(false)
const fileInput = ref(null)
const recoveryFileInput = ref(null)
const textareaRef = ref(null)
const replacementCommand = ref(null)
let activeDraftIdentity = null
let draftSaveTimer = null
let draftLoadGeneration = 0
let restoringDraft = false

function currentDraftIdentity() {
  if (!userStore.username) return null
  if (props.friendMode) {
    return chatStore.currentFriendUsername
      ? { account: userStore.username, kind: 'friend', id: chatStore.currentFriendUsername }
      : null
  }
  return chatStore.currentRoomId
    ? { account: userStore.username, kind: 'room', id: chatStore.currentRoomId }
    : null
}

function persistDraft(identity, value) {
  if (!identity) return
  void conversationCache.saveDraft(identity.account, identity.kind, identity.id, value)
    .catch(error => console.warn('[Cache] unable to persist draft:', error))
}

function flushDraft() {
  if (draftSaveTimer) clearTimeout(draftSaveTimer)
  draftSaveTimer = null
  persistDraft(activeDraftIdentity, text.value)
}

watch(text, () => {
  if (restoringDraft || !activeDraftIdentity) return
  if (draftSaveTimer) clearTimeout(draftSaveTimer)
  const identity = activeDraftIdentity
  const value = text.value
  draftSaveTimer = setTimeout(() => persistDraft(identity, value), 250)
}, { flush: 'sync' })

watch(
  () => [props.friendMode, chatStore.currentRoomId,
    chatStore.currentFriendUsername, userStore.username],
  async () => {
    flushDraft()
    const identity = currentDraftIdentity()
    activeDraftIdentity = identity
    const generation = ++draftLoadGeneration
    restoringDraft = true
    text.value = ''
    try {
      if (!identity) return
      const draft = await conversationCache.loadDraft(
        identity.account, identity.kind, identity.id)
      if (generation === draftLoadGeneration) text.value = draft
    } catch (error) {
      console.warn('[Cache] unable to load draft:', error)
    } finally {
      if (generation === draftLoadGeneration) restoringDraft = false
    }
  },
  { immediate: true }
)

onBeforeUnmount(flushDraft)

function sendMessage(e) {
  if (e) e.preventDefault()
  const msg = text.value.trim()
  if (!msg) return

  if (props.friendMode) {
    if (!chatStore.currentFriendUsername) return
    chatStore.sendCurrentFriendMessage(msg)
  } else {
    if (!chatStore.currentRoomId) return
    chatStore.sendCurrentRoomMessage(msg, 'text')
  }
  text.value = ''
  persistDraft(activeDraftIdentity, '')
}

function onEmojiSelect(emoji) {
  if (props.friendMode) {
    if (!chatStore.currentFriendUsername) return
    chatStore.sendCurrentFriendMessage(emoji, 'emoji')
  } else {
    chatStore.sendCurrentRoomMessage(emoji, 'emoji')
  }
  showEmoji.value = false
}

function triggerFileInput() {
  fileInput.value?.click()
}

const recoverableAttachmentCommands = computed(() => {
  const kind = props.friendMode ? 'direct' : 'room'
  const conversationId = String(props.friendMode
    ? chatStore.currentFriendUsername || ''
    : chatStore.currentRoomId || '')
  return chatStore.attachmentCommands.filter(command =>
    command.kind === kind
      && command.conversationId === conversationId
      && ['failed', 'needs_source'].includes(command.state))
})

function attachmentStateLabel(command) {
  return command.state === 'needs_source' ? '需要重新选择原文件' : '发送失败'
}

function chooseReplacement(command) {
  replacementCommand.value = command
  recoveryFileInput.value?.click()
}

async function onRecoveryFileSelected(event) {
  const file = event.target.files[0]
  event.target.value = ''
  const command = replacementCommand.value
  replacementCommand.value = null
  if (!file || !command) return
  await chatStore.reselectAttachmentSource(command, file)
}

async function onFileSelected(e) {
  const file = e.target.files[0]
  if (!file) return
  e.target.value = '' // 重置

  if (props.friendMode) {
    if (!chatStore.currentFriendUsername) return
    const MAX_FRIEND_FILE = 100 * 1024 * 1024 // 100MB
    if (file.size > MAX_FRIEND_FILE) {
      alert('私聊单文件不能超过 100MB')
      return
    }
    if (file.size <= MAX_SMALL_FILE) {
      await chatStore.uploadFriendSmallFile(chatStore.currentFriendUsername, file)
    } else {
      await chatStore.startFriendChunkedUpload(chatStore.currentFriendUsername, file)
    }
  } else {
    const s = chatStore.roomSettings[chatStore.currentRoomId]
    const maxRoomFile = s?.maxFileSize || 10 * 1024 * 1024 * 1024
    if (file.size > maxRoomFile) {
      alert(`文件大小超过房间上限（${Math.round(maxRoomFile / 1024 / 1024)}MB）`)
      return
    }
    if (file.size <= MAX_SMALL_FILE) {
      await chatStore.uploadSmallFile(chatStore.currentRoomId, file)
    } else {
      await chatStore.startChunkedUpload(chatStore.currentRoomId, file)
    }
  }
}

function uploadPercent(u) {
  if (!u.fileSize) return 0
  // 两阶段进度：上传到服务器 0-60%，COS 上传 60-100%
  const serverPct = Math.min(u.sent / u.fileSize, 1) * 60
  if (u.cosPhase && u.cosTotal > 0) {
    const cosPct = Math.min(u.cosSent / u.cosTotal, 1) * 40
    return Math.floor(serverPct + cosPct)
  }
  return Math.floor(serverPct)
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
.attachment-recovery {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: 8px;
}
.recovery-item {
  border: 1px solid var(--border-color);
}
.recovery-name {
  max-width: 150px;
}
.recovery-state {
  color: var(--warning);
}
.btn-link {
  border: 0;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
  padding: 2px 4px;
}
.btn-link-danger {
  color: var(--danger);
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
