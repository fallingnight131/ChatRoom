<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal" style="min-width: 420px;">
      <div class="modal-title">个人资料</div>

      <!-- 头像 -->
      <div class="profile-avatar-section">
        <div class="profile-avatar-wrap" @click="triggerAvatarInput">
          <img v-if="userStore.avatarData"
               :src="'data:image/png;base64,' + userStore.avatarData"
               class="avatar avatar-xl" />
          <div v-else class="avatar avatar-xl avatar-placeholder"
               :style="{ background: hashColor(userStore.username) }">
            {{ (userStore.displayName || userStore.username).charAt(0) }}
          </div>
          <div class="avatar-overlay">📷</div>
        </div>
        <input ref="avatarInput" type="file" accept="image/*" style="display:none"
               @change="onAvatarSelected" />
      </div>

      <!-- 昵称 -->
      <div class="input-group">
        <label>昵称</label>
        <div class="inline-edit">
          <input class="input" v-model="displayName" :disabled="!editingName" />
          <button v-if="!editingName" class="btn btn-text" @click="editingName = true">编辑</button>
          <button v-else class="btn btn-primary" @click="saveName">保存</button>
        </div>
      </div>

      <!-- UID -->
      <div class="input-group">
        <label>用户ID</label>
        <div class="inline-edit">
          <input class="input" v-model="uid" :disabled="!editingUid" />
          <button v-if="!editingUid" class="btn btn-text" @click="editingUid = true">编辑</button>
          <button v-else class="btn btn-primary" @click="saveUid">保存</button>
        </div>
      </div>

      <!-- 修改密码 -->
      <div class="password-section">
        <div class="section-header" @click="showPasswordChange = !showPasswordChange">
          修改密码 {{ showPasswordChange ? '▲' : '▼' }}
        </div>
        <div v-if="showPasswordChange">
          <div class="input-group">
            <label>当前密码</label>
            <input class="input" v-model="oldPassword" type="password" />
          </div>
          <div class="input-group">
            <label>新密码</label>
            <input class="input" v-model="newPassword" type="password" />
          </div>
          <div class="input-group">
            <label>确认新密码</label>
            <input class="input" v-model="confirmPassword" type="password" />
          </div>
          <button class="btn btn-primary" @click="changePassword" style="width:100%">修改密码</button>
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn btn-danger" @click="doLogout">退出登录</button>
        <button class="btn btn-secondary" @click="$emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, inject } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { chatWs } from '../services/websocket'

const emit = defineEmits(['close'])
const router = useRouter()
const userStore = useUserStore()
const hashColor = inject('hashColor')

const displayName = ref(userStore.displayName)
const uid = ref(userStore.username)
const editingName = ref(false)
const editingUid = ref(false)

const showPasswordChange = ref(false)
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')

const avatarInput = ref(null)

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
      alert('头像太大，请选择更小的图片')
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
  if (uid.value.trim() && uid.value !== userStore.username) {
    chatWs.changeUid(uid.value.trim())
    editingUid.value = false
  }
}

function changePassword() {
  if (!oldPassword.value || !newPassword.value) {
    alert('请填写所有字段')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    alert('两次新密码不一致')
    return
  }
  chatWs.changePassword(oldPassword.value, newPassword.value)
  oldPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  showPasswordChange.value = false
}

function doLogout() {
  chatWs.logout()
  userStore.onLogout()
  emit('close')
  router.push('/login')
}
</script>

<style scoped>
.profile-avatar-section {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}
.profile-avatar-wrap {
  position: relative;
  cursor: pointer;
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
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}
</style>
