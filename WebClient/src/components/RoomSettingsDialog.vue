<template>
  <div class="modal-overlay" @click.self="closeDialog">
    <div ref="dialogRef" class="modal room-settings-modal" role="dialog" aria-modal="true"
         aria-labelledby="room-settings-title" tabindex="-1" :aria-busy="hasPendingOperation"
         @keydown="onDialogKeydown">
      <div id="room-settings-title" class="modal-title">{{ messages.title }}</div>

      <div class="setting-info">
        <div class="room-avatar-section">
          <div class="room-avatar-display">
            <img v-if="roomAvatarSrc" :src="roomAvatarSrc" class="avatar avatar-lg"
                 :alt="`${chatStore.currentRoomName}${messages.avatarSuffix}`" />
            <div v-else class="avatar avatar-lg avatar-placeholder" :style="{ background: hashColor(chatStore.currentRoomId) }">
              {{ (chatStore.currentRoomName || '').charAt(0) }}
            </div>
          </div>
          <div class="room-basic-info">
            <div class="info-row">
              <span class="info-label">{{ messages.roomName }}</span>
              <span class="info-value">{{ chatStore.currentRoomName }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ messages.roomId }}</span>
              <span class="info-value">{{ chatStore.currentRoomId }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ messages.administrator }}</span>
              <span class="info-value">{{ chatStore.isAdmin ? messages.yes : messages.no }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ messages.maxFileSize }}</span>
              <span class="info-value">{{ formatGB(roomLimits.maxFileSize) }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ messages.totalFileSpace }}</span>
              <span class="info-value">{{ formatGB(roomLimits.totalFileSpace) }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ messages.maxFileCount }}</span>
              <span class="info-value">{{ roomLimits.maxFileCount }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ messages.maxMembers }}</span>
              <span class="info-value">{{ roomLimits.maxMembers }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 管理员功能 -->
      <template v-if="chatStore.isAdmin">
        <!-- 房间头像 -->
        <div class="setting-section">
          <div class="setting-label">{{ messages.roomAvatar }}</div>
          <div class="inline-edit">
            <button class="btn btn-secondary" type="button" :disabled="pendingAvatarUpload"
                    @click="selectRoomAvatar">{{ messages.selectImage }}</button>
            <input ref="avatarFileInput" type="file" accept="image/*" class="visually-hidden"
                   tabindex="-1" :aria-label="messages.selectNewAvatar" @change="onAvatarFileSelected" />
            <span v-if="avatarUploading" class="upload-hint" role="status">{{ messages.uploading }}</span>
          </div>
        </div>

        <!-- 重命名 -->
        <div class="setting-section">
          <label class="setting-label" for="room-new-name">{{ messages.renameRoom }}</label>
          <div class="inline-edit">
            <input id="room-new-name" class="input" v-model="newName" :placeholder="messages.newName"
                   :disabled="pendingRename" />
            <button class="btn btn-primary" type="button" :disabled="pendingRename || !newName.trim()"
                    @click="renameRoom">{{ messages.modify }}</button>
          </div>
        </div>

        <!-- 密码管理 -->
        <div class="setting-section">
          <label class="setting-label" for="room-settings-password">{{ messages.roomPassword }}</label>
          <div class="inline-edit">
            <input id="room-settings-password" class="input" v-model="roomPassword" type="password"
                   :placeholder="messages.passwordPlaceholder" autocomplete="off"
                   :disabled="pendingPasswordSave" />
            <button class="btn btn-primary" type="button" :disabled="pendingPasswordSave"
                    @click="setPassword">{{ messages.set }}</button>
            <button class="btn btn-text" type="button" :disabled="pendingPasswordSave"
                    @click="checkPasswordStatus">{{ messages.checkStatus }}</button>
          </div>
          <div v-if="hasRoomPassword !== null" class="password-display" role="status">
            {{ hasRoomPassword ? messages.passwordSet : messages.passwordNone }}
          </div>
        </div>

        <!-- 管理成员 -->
        <div class="setting-section">
          <label class="setting-label" for="room-member-select">{{ messages.manageMembers }}</label>
          <div class="member-actions">
            <select id="room-member-select" class="input" v-model="selectedUser" style="flex:1">
              <option value="">{{ messages.selectUser }}</option>
              <option v-for="u in chatStore.users" :key="u.username" :value="u.username">
                {{ u.displayName }} (@{{ u.username }})
                {{ u.isAdmin ? messages.adminBadge : '' }}
              </option>
            </select>
            <button class="btn btn-secondary" type="button" @click="toggleAdmin" :disabled="!selectedUser">
              {{ isSelectedAdmin ? messages.unsetAdmin : messages.setAdmin }}
            </button>
            <button class="btn btn-danger" type="button" @click="kickUser" :disabled="!selectedUser">{{ messages.kick }}</button>
          </div>
        </div>

        <!-- 消息管理 -->
        <div class="setting-section">
          <div class="setting-label">{{ messages.messageManagement }}</div>
          <button class="btn btn-danger" type="button" @click="clearAllMessages">{{ messages.clearAll }}</button>
        </div>

        <!-- 危险操作 -->
        <div class="setting-section danger-zone">
          <div class="setting-label" style="color:var(--danger)">{{ messages.danger }}</div>
          <button class="btn btn-danger" type="button" @click="deleteRoom">{{ messages.deleteRoom }}</button>
        </div>
      </template>

      <div v-if="chatStore.isAdmin" class="setting-section">
        <div class="setting-label">{{ messages.limitsTitle }}</div>
        <div class="limit-grid">
          <div class="limit-row">
            <label class="limit-key" for="room-max-file-size">{{ messages.maxFileSizeGb }}</label>
            <input id="room-max-file-size" class="input" v-model.number="maxFileSize" type="number"
                   min="1" max="10240" step="0.1" :disabled="pendingSaveLimits" />
          </div>
          <div class="limit-row">
            <label class="limit-key" for="room-total-file-space">{{ messages.totalFileSpaceGb }}</label>
            <input id="room-total-file-space" class="input" v-model.number="totalFileSpace" type="number"
                   min="1" max="10240" step="1" :disabled="pendingSaveLimits" />
          </div>
        </div>
        <div class="limit-grid" style="margin-top:8px">
          <div class="limit-row">
            <label class="limit-key" for="room-max-file-count">{{ messages.maxFileCount }}</label>
            <input id="room-max-file-count" class="input" v-model.number="maxFileCount" type="number"
                   min="1" max="1000000" :disabled="pendingSaveLimits" />
          </div>
          <div class="limit-row">
            <label class="limit-key" for="room-max-members">{{ messages.maxMembers }}</label>
            <input id="room-max-members" class="input" v-model.number="maxMembers" type="number"
                   min="2" max="1000000" :disabled="pendingSaveLimits" />
          </div>
        </div>
        <div class="limit-grid" style="margin-top:8px">
          <div class="limit-row">
            <label class="limit-key" for="room-developer-key">{{ messages.developerKey }}</label>
            <input id="room-developer-key" class="input" v-model="developerKey" type="password"
                   :placeholder="messages.developerKeyPlaceholder" autocomplete="off"
                   :disabled="pendingSaveLimits" />
          </div>
        </div>
        <div class="inline-edit" style="margin-top:8px">
          <button class="btn btn-primary" type="button" :disabled="pendingSaveLimits"
                  @click="setRoomLimits">{{ messages.saveLimits }}</button>
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn btn-danger" type="button" @click="leaveRoom">{{ messages.leaveRoom }}</button>
        <button class="btn btn-secondary" type="button" @click="closeDialog">{{ messages.close }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { chatWs } from '../services/websocket'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { roomSettingsMessages } from '../localization/webLocale'

const emit = defineEmits(['close'])
const chatStore = useChatStore()
const userStore = useUserStore()
const messages = computed(() => roomSettingsMessages(userStore.locale))

const newName = ref('')
const roomPassword = ref('')
const hasRoomPassword = ref(null)
const maxFileSize = ref(10)
const totalFileSpace = ref(10)
const maxFileCount = ref(1500)
const maxMembers = ref(50)
const developerKey = ref('')
const pendingSaveLimits = ref(false)
const pendingRename = ref(false)
const pendingPasswordSave = ref(false)
const pendingAvatarUpload = ref(false)
const pendingDeveloperKey = ref('')
const selectedUser = ref('')
const avatarFileInput = ref(null)
const avatarUploading = ref(false)
const hasPendingOperation = computed(() => pendingSaveLimits.value || pendingRename.value ||
  pendingPasswordSave.value || pendingAvatarUpload.value)
const { dialogRef, closeDialog, onDialogKeydown } = useModalKeyboardBoundary({
  onClose: () => emit('close')
})

const roomLimits = computed(() => {
  const s = chatStore.roomSettings[chatStore.currentRoomId] || {}
  return {
    maxFileSize: s.maxFileSize || 10 * 1024 * 1024 * 1024,
    totalFileSpace: s.totalFileSpace || 10 * 1024 * 1024 * 1024,
    maxFileCount: s.maxFileCount || 1500,
    maxMembers: s.maxMembers || 50,
  }
})

const roomAvatarSrc = computed(() => chatStore.getRoomAvatarSrc(chatStore.currentRoomId))

function hashColor(id) {
  let hash = 0
  const s = String(id)
  for (let i = 0; i < s.length; i++) hash = s.charCodeAt(i) + ((hash << 5) - hash)
  const h = Math.abs(hash) % 360
  return `hsl(${h}, 55%, 50%)`
}

const isSelectedAdmin = computed(() => {
  const u = chatStore.users.find(u => u.username === selectedUser.value)
  return u ? u.isAdmin : false
})

function selectRoomAvatar() {
  if (pendingAvatarUpload.value) return
  avatarFileInput.value?.click()
}

function onAvatarFileSelected(e) {
  const file = e.target.files[0]
  if (!file) return

  // 裁剪为方形、缩放到256px、转PNG base64（与用户头像相同处理）
  const img = new Image()
  img.onload = () => {
    const size = Math.min(img.width, img.height)
    const canvas = document.createElement('canvas')
    canvas.width = 256
    canvas.height = 256
    const ctx = canvas.getContext('2d')
    const sx = (img.width - size) / 2
    const sy = (img.height - size) / 2
    ctx.drawImage(img, sx, sy, size, size, 0, 0, 256, 256)
    const dataUrl = canvas.toDataURL('image/png')
    const base64 = dataUrl.split(',')[1]

    // 检查大小 < 256KB
    if (base64.length > 256 * 1024 * 1.37) {
      alert(messages.value.avatarTooLarge)
      return
    }
    avatarUploading.value = true
    pendingAvatarUpload.value = true
    chatWs.uploadRoomAvatar(chatStore.currentRoomId, base64)
  }
  img.src = URL.createObjectURL(file)
}

function onRoomAvatarUploaded(data) {
  avatarUploading.value = false
  if (!pendingAvatarUpload.value) return
  pendingAvatarUpload.value = false
  alert(messages.value.avatarSaved)
}

function onRoomAvatarUploadFailed() {
  avatarUploading.value = false
  pendingAvatarUpload.value = false
}

function renameRoom() {
  if (pendingRename.value) return
  if (newName.value.trim()) {
    pendingRename.value = true
    chatWs.renameRoom(chatStore.currentRoomId, newName.value.trim())
    newName.value = ''
  }
}

function onRoomRenamed() {
  if (!pendingRename.value) return
  pendingRename.value = false
  alert(messages.value.nameSaved)
}

function onRoomRenameFailed() {
  pendingRename.value = false
}

function setPassword() {
  if (pendingPasswordSave.value) return
  pendingPasswordSave.value = true
  chatWs.setRoomPassword(chatStore.currentRoomId, roomPassword.value)
  roomPassword.value = ''
}

function onRoomPasswordSaved(data) {
  if (!pendingPasswordSave.value) return
  pendingPasswordSave.value = false
  const hasPassword = !!data?.hasPassword
  alert(hasPassword ? messages.value.passwordSaved : messages.value.passwordRemoved)
}

function onRoomPasswordSaveFailed() {
  pendingPasswordSave.value = false
}

function checkPasswordStatus() {
  chatWs.getRoomPassword(chatStore.currentRoomId)
}

function onRoomPassword(data) {
  hasRoomPassword.value = !!data.hasPassword
}

function onRoomSettingsNeedConfirm(data) {
  const summary = data.cleanupSummary || {}
  const clearCount = summary.clearFileCount || 0
  const afterCount = summary.afterFileCount || 0
  const afterSpaceGB = ((summary.afterUsedSpace || 0) / 1024 / 1024 / 1024).toFixed(2)
  const ok = confirm(
    `${messages.value.cleanupPrefix}${clearCount}${messages.value.cleanupFileSuffix}` +
    `${messages.value.cleanupAfterPrefix}${afterCount}${messages.value.cleanupAfterMiddle}${afterSpaceGB}${messages.value.cleanupAfterSuffix}` +
    messages.value.cleanupExpiry
  )
  if (!ok) {
    pendingSaveLimits.value = false
    pendingDeveloperKey.value = ''
    return
  }

  pendingSaveLimits.value = true
  chatWs.setRoomSettings(
    data.roomId,
    data.maxFileSize,
    data.totalFileSpace,
    data.maxFileCount,
    data.maxMembers,
    true,
    pendingDeveloperKey.value,
  )
}

function onRoomSettingsSaved() {
  if (!pendingSaveLimits.value) return
  pendingSaveLimits.value = false
  pendingDeveloperKey.value = ''
  alert(messages.value.limitsSaved)
}

function onRoomSettingsFailed() {
  pendingSaveLimits.value = false
  pendingDeveloperKey.value = ''
}

function setRoomLimits() {
  if (pendingSaveLimits.value) return
  if (maxFileSize.value <= 0 || totalFileSpace.value <= 0 || maxFileCount.value <= 0 || maxMembers.value <= 0) {
    alert(messages.value.limitsPositive)
    return
  }
  if (totalFileSpace.value < maxFileSize.value) {
    alert(messages.value.totalTooSmall)
    return
  }
  if (!developerKey.value.trim()) {
    alert(messages.value.developerKeyRequired)
    return
  }
  pendingSaveLimits.value = true
  pendingDeveloperKey.value = developerKey.value
  developerKey.value = ''
  chatWs.setRoomSettings(
    chatStore.currentRoomId,
    maxFileSize.value * 1024 * 1024 * 1024,
    totalFileSpace.value * 1024 * 1024 * 1024,
    maxFileCount.value,
    maxMembers.value,
    false,
    pendingDeveloperKey.value,
  )
}

function formatGB(bytes) {
  return `${((bytes || 0) / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function toggleAdmin() {
  if (!selectedUser.value) return
  chatWs.setAdmin(chatStore.currentRoomId, selectedUser.value, !isSelectedAdmin.value)
}

function kickUser() {
  if (!selectedUser.value) return
  if (confirm(`${messages.value.kickPrefix}${selectedUser.value}${messages.value.kickSuffix}`)) {
    chatWs.kickUser(chatStore.currentRoomId, selectedUser.value)
    selectedUser.value = ''
  }
}

function clearAllMessages() {
  if (confirm(messages.value.clearConfirm)) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'all')
  }
}

function deleteRoom() {
  if (confirm(`${messages.value.deletePrefix}${chatStore.currentRoomName}${messages.value.deleteSuffix}`)) {
    chatWs.deleteRoom(chatStore.currentRoomId, chatStore.currentRoomName)
    emit('close')
  }
}

function leaveRoom() {
  if (confirm(`${messages.value.leavePrefix}${chatStore.currentRoomName}${messages.value.leaveSuffix}`)) {
    chatWs.leaveRoom(chatStore.currentRoomId)
    emit('close')
  }
}

onMounted(() => {
  chatStore.onEvent('roomPassword', onRoomPassword)
  chatStore.onEvent('roomAvatarUploaded', onRoomAvatarUploaded)
  chatStore.onEvent('roomRenameFailed', onRoomRenameFailed)
  chatStore.onEvent('roomRenamed', onRoomRenamed)
  chatStore.onEvent('roomPasswordSaved', onRoomPasswordSaved)
  chatStore.onEvent('roomPasswordSaveFailed', onRoomPasswordSaveFailed)
  chatStore.onEvent('roomAvatarUploadFailed', onRoomAvatarUploadFailed)
  chatStore.onEvent('roomSettingsNeedConfirm', onRoomSettingsNeedConfirm)
  chatStore.onEvent('roomSettingsSaved', onRoomSettingsSaved)
  chatStore.onEvent('roomSettingsFailed', onRoomSettingsFailed)
  chatStore.fetchRoomAvatar(chatStore.currentRoomId)
  // 加载当前设置
  const s = chatStore.roomSettings[chatStore.currentRoomId]
  if (s) {
    maxFileSize.value = Number((s.maxFileSize / (1024 * 1024 * 1024)).toFixed(1))
    totalFileSpace.value = Math.round((s.totalFileSpace || 10 * 1024 * 1024 * 1024) / (1024 * 1024 * 1024))
    maxFileCount.value = s.maxFileCount || 1500
    maxMembers.value = s.maxMembers || 50
  }
})

onUnmounted(() => {
  roomPassword.value = ''
  developerKey.value = ''
  pendingDeveloperKey.value = ''
  chatStore.offEvent('roomPassword', onRoomPassword)
  chatStore.offEvent('roomAvatarUploaded', onRoomAvatarUploaded)
  chatStore.offEvent('roomRenameFailed', onRoomRenameFailed)
  chatStore.offEvent('roomRenamed', onRoomRenamed)
  chatStore.offEvent('roomPasswordSaved', onRoomPasswordSaved)
  chatStore.offEvent('roomPasswordSaveFailed', onRoomPasswordSaveFailed)
  chatStore.offEvent('roomAvatarUploadFailed', onRoomAvatarUploadFailed)
  chatStore.offEvent('roomSettingsNeedConfirm', onRoomSettingsNeedConfirm)
  chatStore.offEvent('roomSettingsSaved', onRoomSettingsSaved)
  chatStore.offEvent('roomSettingsFailed', onRoomSettingsFailed)
})
</script>

<style scoped>
.room-settings-modal {
  width: min(560px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  overflow-y: auto;
}
.setting-info {
  margin-bottom: 16px;
  padding: 12px;
  background: var(--bg-primary);
  border-radius: 8px;
}
.room-avatar-section {
  display: flex;
  gap: 16px;
  align-items: center;
}
.room-avatar-display {
  flex-shrink: 0;
}
.room-basic-info {
  flex: 1;
}
.info-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
}
.info-label {
  color: var(--text-secondary);
}
.info-value {
  color: var(--text-primary);
  font-weight: 500;
}
.setting-section {
  margin-bottom: 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-light);
}
.setting-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.inline-edit {
  display: flex;
  gap: 8px;
}
.inline-edit .input {
  flex: 1;
}
.limit-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.limit-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.limit-key {
  width: 130px;
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.limit-row .input {
  flex: 1;
}
.member-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.danger-zone {
  border: 1px solid var(--danger);
  border-radius: 8px;
  padding: 12px;
}
.password-display {
  margin-top: 8px;
  font-size: 13px;
  color: var(--text-primary);
  background: var(--bg-primary);
  padding: 6px 10px;
  border-radius: 4px;
}
.upload-hint {
  font-size: 12px;
  color: var(--text-secondary);
  align-self: center;
}

/* ========== 移动端适配 ========== */
@media (max-width: 768px) {
  .inline-edit {
    flex-direction: column;
  }
  .limit-row {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }
  .limit-key {
    width: auto;
  }
  .inline-edit .btn {
    width: 100%;
  }
  .member-actions {
    flex-direction: column;
  }
  .member-actions select {
    width: 100%;
  }
  .member-actions .btn {
    width: 100%;
  }
  .setting-section {
    margin-bottom: 12px;
    padding-bottom: 12px;
  }
  .danger-zone {
    padding: 10px;
  }
  .danger-zone .btn {
    width: 100%;
  }
}
</style>
