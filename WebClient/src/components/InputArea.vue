<template>
  <div class="input-area">
    <!-- 工具栏 -->
    <div class="input-toolbar" role="toolbar" :aria-label="messages.toolbar">
      <button ref="emojiButton" type="button" class="btn-icon"
              aria-controls="emoji-picker" aria-haspopup="dialog"
              @click="toggleEmojiPicker" :title="messages.emoji"
              :aria-label="messages.selectEmoji" :aria-expanded="showEmoji">😊</button>
      <button type="button" class="btn-icon" @click="triggerFileInput" :title="messages.sendFile"
              :aria-label="messages.selectFile">📎</button>
      <input ref="fileInput" type="file" class="visually-hidden" tabindex="-1"
             :aria-label="messages.selectFile" @change="onFileSelected" />
      <input ref="recoveryFileInput" type="file" class="visually-hidden" tabindex="-1"
             :aria-label="messages.reselectFileInput" @change="onRecoveryFileSelected" />

      <!-- 上传进度 -->
      <div v-if="Object.keys(chatStore.uploads).length > 0" class="upload-status"
           role="status" aria-live="polite" :aria-label="messages.uploadStatus">
        <div v-for="(u, uid) in chatStore.uploads" :key="uid" class="upload-item">
          <span class="text-ellipsis" style="max-width:120px">{{ u.fileName }}</span>
          <div class="progress-bar" style="width:80px" role="progressbar"
               :aria-label="`${u.fileName}${messages.uploadProgressSuffix}`" aria-valuemin="0" aria-valuemax="100"
               :aria-valuenow="uploadPercent(u)">
            <div class="progress-fill" :style="{ width: uploadPercent(u) + '%' }"></div>
          </div>
          <span class="upload-pct">{{ uploadPercent(u) }}%</span>
          <span v-if="u.status === 'cos_uploading'" class="upload-phase">{{ messages.syncing }}</span>
          <button v-if="u.status === 'uploading'" type="button" class="btn-icon"
                  :aria-label="`${messages.pauseUploadPrefix}${u.fileName}`"
                  @click="chatStore.pauseUpload(uid)" :title="messages.pause">⏸</button>
          <button v-if="u.status === 'paused'" type="button" class="btn-icon"
                  :aria-label="`${messages.resumeUploadPrefix}${u.fileName}`"
                  @click="chatStore.resumeUpload(uid)" :title="messages.resume">▶</button>
          <button type="button" class="btn-icon" :aria-label="`${messages.cancelUploadPrefix}${u.fileName}`"
                  @click="chatStore.cancelUpload(uid)" :title="messages.cancel">✖</button>
        </div>
      </div>

      <div v-if="recoverableAttachmentCommands.length" class="attachment-recovery"
           role="status" aria-live="polite" :aria-label="messages.recoveryStatus">
        <div v-for="command in recoverableAttachmentCommands"
             :key="command.clientMessageId" class="upload-item recovery-item">
          <span class="text-ellipsis recovery-name">{{ command.fileName }}</span>
          <span class="recovery-state">{{ attachmentStateLabel(command) }}</span>
          <button v-if="command.state === 'needs_source'" type="button" class="btn-link"
                  :aria-label="`${messages.reselectPrefix}${command.fileName}`"
                  @click="chooseReplacement(command)">{{ messages.reselect }}</button>
          <button v-else type="button" class="btn-link"
                  :aria-label="`${messages.retrySendPrefix}${command.fileName}`"
                  @click="chatStore.retryAttachmentCommand(command)">{{ messages.retry }}</button>
          <button type="button" class="btn-link btn-link-danger"
                  :aria-label="`${messages.cancelSendPrefix}${command.fileName}`"
                  @click="chatStore.cancelAttachmentCommand(command)">{{ messages.cancel }}</button>
        </div>
      </div>
    </div>

    <!-- 表情选择器 -->
    <div v-if="showEmoji" class="emoji-overlay" aria-hidden="true"
         @click="closeEmojiPicker(false)"></div>
    <EmojiPicker v-if="showEmoji" @select="onEmojiSelect"
                 @close="closeEmojiPicker(true)" />

    <!-- 文本输入 -->
    <div class="input-row">
      <textarea ref="textareaRef" class="input chat-input" v-model="text"
                :placeholder="messages.messagePlaceholder"
                :aria-label="messages.messageContent"
                @keydown.enter.exact="sendMessage"
                @keydown.enter.shift.exact.prevent="text += '\n'"
                rows="1"></textarea>
      <button type="button" class="btn btn-primary send-btn" @click="sendMessage"
              :disabled="!canSendText"
              :aria-label="messages.sendMessage">
        {{ messages.send }}
      </button>
    </div>
    <p class="message-budget" :class="{ 'over-budget': !textBudget.withinBudget }"
       role="status" aria-live="polite" :aria-label="messages.messageBytes">
      {{ textBudgetLabel }}
    </p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MAX_SMALL_FILE } from '../services/websocket'
import { conversationCache } from '../persistence/conversationCache'
import { messageTextBudget } from '../messaging/messageTextBudget.js'
import { composerMessages } from '../localization/webLocale'
import EmojiPicker from './EmojiPicker.vue'

const props = defineProps({
  friendMode: { type: Boolean, default: false }
})

const userStore = useUserStore()
const chatStore = useChatStore()
const messages = computed(() => composerMessages(userStore.locale))

const text = ref('')
const showEmoji = ref(false)
const fileInput = ref(null)
const recoveryFileInput = ref(null)
const textareaRef = ref(null)
const emojiButton = ref(null)
const replacementCommand = ref(null)
const textBudget = computed(() => messageTextBudget(text.value))
const textBudgetLabel = computed(() => textBudget.value.withinBudget
  ? `${textBudget.value.bytes} / ${textBudget.value.maximum} ${messages.value.bytes}`
  : `${messages.value.overLimitPrefix}${textBudget.value.overage} ${messages.value.bytes}`
    + `${messages.value.maximumPrefix}${textBudget.value.maximum}${messages.value.maximumSuffix}`)
const canSendText = computed(() => Boolean(text.value.trim()) && textBudget.value.withinBudget)
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
  if (!msg || !textBudget.value.withinBudget) return

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
  nextTick(() => textareaRef.value?.focus())
}

function toggleEmojiPicker() {
  if (showEmoji.value) {
    closeEmojiPicker(true)
    return
  }
  showEmoji.value = true
}

function closeEmojiPicker(restoreFocus) {
  showEmoji.value = false
  if (restoreFocus) nextTick(() => emojiButton.value?.focus())
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
  return command.state === 'needs_source' ? messages.value.needsSource : messages.value.sendFailed
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
      alert(messages.value.friendFileTooLarge)
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
      alert(`${messages.value.roomFileTooLargePrefix}${Math.round(maxRoomFile / 1024 / 1024)}${messages.value.roomFileTooLargeSuffix}`)
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
.message-budget {
  margin: 4px 0 0;
  text-align: right;
  font-size: 12px;
  color: var(--text-secondary);
}
.message-budget.over-budget {
  color: var(--danger-color, #c62828);
  font-weight: 600;
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
