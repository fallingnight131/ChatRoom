<template>
  <div class="modal-overlay" @click.self="closeDialog">
    <div ref="dialogRef" class="modal profile-modal" role="dialog" aria-modal="true"
         aria-labelledby="profile-dialog-title" tabindex="-1" @keydown="onDialogKeydown">
      <div id="profile-dialog-title" class="modal-title">{{ messages.title }}</div>

      <div class="input-group locale-section">
        <label for="profile-locale">{{ messages.language }}</label>
        <select id="profile-locale" class="input" :value="userStore.locale"
                @change="userStore.setLocale($event.target.value)">
          <option value="zh-CN">{{ messages.chinese }}</option>
          <option value="en-US">{{ messages.english }}</option>
        </select>
      </div>

      <!-- 头像 -->
      <div class="profile-avatar-section">
        <button type="button" class="profile-avatar-wrap" :aria-label="messages.changeAvatar"
                @click="triggerAvatarInput">
          <img v-if="userStore.avatarData"
               :src="'data:image/png;base64,' + userStore.avatarData"
               class="avatar avatar-xl" :alt="messages.currentAvatar" />
          <div v-else class="avatar avatar-xl avatar-placeholder"
               :style="{ background: hashColor(userStore.username) }">
            {{ (userStore.displayName || userStore.username).charAt(0) }}
          </div>
          <div class="avatar-overlay">📷</div>
        </button>
        <input ref="avatarInput" type="file" accept="image/*" class="visually-hidden" tabindex="-1"
               :aria-label="messages.selectAvatar"
               @change="onAvatarSelected" />
      </div>

      <!-- 昵称 -->
      <div class="input-group">
        <label for="profile-display-name">{{ messages.displayName }}</label>
        <div class="inline-edit">
          <input id="profile-display-name" class="input" v-model="displayName"
                 :disabled="!editingName" autocomplete="nickname" />
          <button v-if="!editingName" type="button" class="btn btn-text"
                  @click="editingName = true">{{ messages.edit }}</button>
          <button v-else type="button" class="btn btn-primary" @click="saveName">{{ messages.save }}</button>
        </div>
      </div>

      <!-- UID -->
      <div class="input-group">
        <label for="profile-user-id">{{ messages.userId }}</label>
        <div class="inline-edit">
          <input id="profile-user-id" class="input" v-model="uid" :disabled="!editingUid"
                 aria-describedby="profile-user-id-hint profile-user-id-feedback"
                 autocomplete="username" />
          <button v-if="!editingUid" type="button" class="btn btn-text"
                  @click="editingUid = true">{{ messages.edit }}</button>
          <button v-else type="button" class="btn btn-primary" @click="saveUid">{{ messages.save }}</button>
        </div>
        <div id="profile-user-id-hint" class="uid-hint">{{ messages.userIdHint }}</div>
        <div id="profile-user-id-feedback" aria-live="polite">
          <div v-if="uidError" class="uid-error" role="alert">{{ uidError }}</div>
          <div v-if="uidSuccess" class="uid-success" role="status">{{ uidSuccess }}</div>
        </div>
      </div>

      <!-- 修改密码 -->
      <div class="password-section">
        <button type="button" class="section-header" aria-controls="profile-password-panel"
                :aria-expanded="showPasswordChange" @click="togglePasswordChange">
          {{ messages.changePassword }} {{ showPasswordChange ? '▲' : '▼' }}
        </button>
        <div id="profile-password-panel">
          <form v-if="showPasswordChange" id="profile-password-form"
                @submit.prevent="changePassword">
            <div class="input-group">
              <label for="profile-current-password">{{ messages.currentPassword }}</label>
              <input id="profile-current-password" class="input" v-model="oldPassword"
                     type="password" autocomplete="current-password" required />
            </div>
            <div class="input-group">
              <label for="profile-new-password">{{ messages.newPassword }}</label>
              <input id="profile-new-password" class="input" v-model="newPassword"
                     type="password" autocomplete="new-password" required />
            </div>
            <div class="input-group">
              <label for="profile-confirm-password">{{ messages.confirmPassword }}</label>
              <input id="profile-confirm-password" class="input" v-model="confirmPassword"
                     type="password" autocomplete="new-password" required />
            </div>
            <button type="submit" class="btn btn-primary" style="width:100%">{{ messages.changePassword }}</button>
          </form>
        </div>
      </div>

      <div class="bandwidth-section">
        <label class="bandwidth-toggle">
          <input type="checkbox" :checked="userStore.lowBandwidthMode"
                 @change="userStore.setLowBandwidthMode($event.target.checked)" />
          <span>{{ messages.lowBandwidth }}</span>
        </label>
        <p>{{ messages.lowBandwidthDescription }}</p>
        <small v-if="userStore.lowBandwidthPreferenceSource === 'browser'" role="status">
          {{ messages.browserDataSaver }}
        </small>
        <small v-else-if="userStore.lowBandwidthPreferenceSource === 'session'" role="status">
          {{ messages.sessionOnly }}
        </small>
      </div>

      <div class="modal-actions">
        <button type="button" class="btn btn-danger" @click="doLogout">{{ messages.signOut }}</button>
        <button type="button" class="btn btn-secondary" @click="closeDialog">{{ messages.close }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, inject, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { chatWs, MsgType } from '../services/websocket'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { profileMessages } from '../localization/webLocale'

const emit = defineEmits(['close'])
const router = useRouter()
const chatStore = useChatStore()
const userStore = useUserStore()
const hashColor = inject('hashColor')
const messages = computed(() => profileMessages(userStore.locale))

const displayName = ref(userStore.displayName)
const uid = ref(userStore.username)
const editingName = ref(false)
const editingUid = ref(false)

const showPasswordChange = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')

const avatarInput = ref(null)
const uidErrorKey = ref('')
const uidServerError = ref('')
const uidSuccessKey = ref('')
const uidError = computed(() => uidErrorKey.value
  ? messages.value[uidErrorKey.value]
  : uidServerError.value)
const uidSuccess = computed(() => uidSuccessKey.value
  ? messages.value[uidSuccessKey.value]
  : '')
const { dialogRef, closeDialog, onDialogKeydown } = useModalKeyboardBoundary({
  onClose: () => {
    clearPasswordFields()
    emit('close')
  }
})

function triggerAvatarInput() {
  avatarInput.value?.click()
}

async function onAvatarSelected(e) {
  const file = e.target.files[0]
  if (!file) return

  // 裁剪为方形、缩放到256px、转PNG base64
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
    chatWs.uploadAvatar(base64)
    userStore.setAvatar(base64)
  }
  img.src = URL.createObjectURL(file)
}

function saveName() {
  if (displayName.value.trim()) {
    chatWs.changeNickname(displayName.value.trim())
    editingName.value = false
  }
}

function saveUid() {
  uidErrorKey.value = ''
  uidServerError.value = ''
  uidSuccessKey.value = ''
  const newUid = uid.value.trim()
  if (!newUid || newUid === userStore.username) {
    editingUid.value = false
    return
  }
  // 前端格式校验
  if (!/^[a-zA-Z0-9_]{6,20}$/.test(newUid)) {
    uidErrorKey.value = 'invalidUserId'
    return
  }
  chatWs.changeUid(newUid)
  editingUid.value = false
}

function clearPasswordFields() {
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
}

function togglePasswordChange() {
  showPasswordChange.value = !showPasswordChange.value
  if (!showPasswordChange.value) clearPasswordFields()
}

function onUidChangeRsp(msg) {
  if (msg.data.success) {
    uidErrorKey.value = ''
    uidServerError.value = ''
    uidSuccessKey.value = 'userIdChanged'
    uid.value = msg.data.newUid
    setTimeout(() => { uidSuccessKey.value = '' }, 3000)
  } else {
    uidErrorKey.value = msg.data.error ? '' : 'changeFailed'
    uidServerError.value = msg.data.error || ''
    uid.value = userStore.username  // 还原
  }
}

function changePassword() {
  if (!oldPassword.value || !newPassword.value) {
    alert(messages.value.requiredFields)
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    alert(messages.value.passwordMismatch)
    return
  }
  userStore.stagePasswordChange(newPassword.value)
  chatWs.changePassword(oldPassword.value, newPassword.value)
  clearPasswordFields()
  showPasswordChange.value = false
}

function doLogout() {
  chatWs.logout()
  chatStore.endAttachmentSession()
  userStore.onLogout()
  emit('close')
  router.push('/login')
}

onMounted(() => {
  chatWs.on(MsgType.CHANGE_UID_RSP, onUidChangeRsp)
})
onUnmounted(() => {
  chatWs.off(MsgType.CHANGE_UID_RSP, onUidChangeRsp)
})
</script>

<style scoped>
.profile-avatar-section {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}
.locale-section { margin-bottom: 16px; }
.bandwidth-section { margin: 16px 0; padding: 12px; border: 1px solid var(--border-color); border-radius: 10px; }
.bandwidth-toggle { display: flex; align-items: center; gap: 8px; font-weight: 600; cursor: pointer; }
.bandwidth-section p, .bandwidth-section small { display: block; margin-top: 6px; color: var(--text-secondary); font-size: 12px; line-height: 1.5; }
.profile-avatar-wrap {
  position: relative;
  cursor: pointer;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: transparent;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0,0,0,0.4);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  opacity: 0;
  transition: opacity 0.2s;
}
.profile-avatar-wrap:hover .avatar-overlay {
  opacity: 1;
}
.inline-edit {
  display: flex;
  gap: 8px;
}
.inline-edit .input {
  flex: 1;
}
.password-section {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 16px;
}
.section-header {
  cursor: pointer;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}
.uid-hint {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 4px;
}
.uid-error {
  font-size: 12px;
  color: var(--danger, #e53e3e);
  margin-top: 4px;
}
.uid-success {
  font-size: 12px;
  color: #38a169;
  margin-top: 4px;
}

/* ========== 移动端适配 ========== */
@media (max-width: 768px) {
  .inline-edit {
    flex-direction: column;
  }
  .inline-edit .btn {
    width: 100%;
  }
  .password-section {
    padding: 10px;
  }
  .profile-avatar-section {
    margin-bottom: 16px;
  }
  .avatar-xl {
    width: 80px;
    height: 80px;
  }
}
</style>
