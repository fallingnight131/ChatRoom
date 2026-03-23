<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal" style="min-width: 440px;">
      <div class="modal-title">房间设置</div>

      <div class="setting-info">
        <div class="room-avatar-section">
          <div class="room-avatar-display">
            <img v-if="roomAvatarSrc" :src="roomAvatarSrc" class="avatar avatar-lg" />
            <div v-else class="avatar avatar-lg avatar-placeholder" :style="{ background: hashColor(chatStore.currentRoomId) }">
              {{ (chatStore.currentRoomName || '').charAt(0) }}
            </div>
          </div>
          <div class="room-basic-info">
            <div class="info-row">
              <span class="info-label">房间名称</span>
              <span class="info-value">{{ chatStore.currentRoomName }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">房间ID</span>
              <span class="info-value">{{ chatStore.currentRoomId }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">管理员</span>
              <span class="info-value">{{ chatStore.isAdmin ? '是' : '否' }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">单文件最大</span>
              <span class="info-value">{{ formatGB(roomLimits.maxFileSize) }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">总文件空间</span>
              <span class="info-value">{{ formatGB(roomLimits.totalFileSpace) }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">文件数量上限</span>
              <span class="info-value">{{ roomLimits.maxFileCount }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">聊天室最大人数</span>
              <span class="info-value">{{ roomLimits.maxMembers }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 管理员功能 -->
      <template v-if="chatStore.isAdmin">
        <!-- 房间头像 -->
        <div class="setting-section">
          <div class="setting-label">房间头像</div>
          <div class="inline-edit">
            <button class="btn btn-secondary" @click="selectRoomAvatar">选择图片</button>
            <input ref="avatarFileInput" type="file" accept="image/*" style="display:none" @change="onAvatarFileSelected" />
            <span v-if="avatarUploading" class="upload-hint">上传中…</span>
          </div>
        </div>

        <!-- 重命名 -->
        <div class="setting-section">
          <div class="setting-label">重命名房间</div>
          <div class="inline-edit">
            <input class="input" v-model="newName" placeholder="新名称" />
            <button class="btn btn-primary" @click="renameRoom">修改</button>
          </div>
        </div>

        <!-- 密码管理 -->
        <div class="setting-section">
          <div class="setting-label">房间密码</div>
          <div class="inline-edit">
            <input class="input" v-model="roomPassword" type="text" placeholder="留空则取消密码" />
            <button class="btn btn-primary" @click="setPassword">设置</button>
            <button class="btn btn-text" @click="viewPassword">查看</button>
          </div>
          <div v-if="currentPassword !== null" class="password-display">
            当前密码: {{ currentPassword || '(无)' }}
          </div>
        </div>

        <!-- 管理成员 -->
        <div class="setting-section">
          <div class="setting-label">管理成员</div>
          <div class="member-actions">
            <select class="input" v-model="selectedUser" style="flex:1">
              <option value="">选择用户...</option>
              <option v-for="u in chatStore.users" :key="u.username" :value="u.username">
                {{ u.displayName }} (@{{ u.username }})
                {{ u.isAdmin ? '[管理员]' : '' }}
              </option>
            </select>
            <button class="btn btn-secondary" @click="toggleAdmin" :disabled="!selectedUser">
              {{ isSelectedAdmin ? '取消管理员' : '设为管理员' }}
            </button>
            <button class="btn btn-danger" @click="kickUser" :disabled="!selectedUser">踢出</button>
          </div>
        </div>

        <!-- 消息管理 -->
        <div class="setting-section">
          <div class="setting-label">消息管理</div>
          <button class="btn btn-danger" @click="clearAllMessages">清空所有消息</button>
        </div>

        <!-- 危险操作 -->
        <div class="setting-section danger-zone">
          <div class="setting-label" style="color:var(--danger)">危险操作</div>
          <button class="btn btn-danger" @click="deleteRoom">删除房间</button>
        </div>
      </template>

      <div class="setting-section">
        <div class="setting-label">限制设置（需开发者秘钥）</div>
        <div class="limit-grid">
          <div class="limit-row">
            <span class="limit-key">单文件最大(GB)</span>
            <input class="input" v-model.number="maxFileSize" type="number" min="1" max="10240" step="0.1" />
          </div>
          <div class="limit-row">
            <span class="limit-key">总文件空间(GB)</span>
            <input class="input" v-model.number="totalFileSpace" type="number" min="1" max="10240" step="1" />
          </div>
        </div>
        <div class="limit-grid" style="margin-top:8px">
          <div class="limit-row">
            <span class="limit-key">文件数量上限</span>
            <input class="input" v-model.number="maxFileCount" type="number" min="1" max="1000000" />
          </div>
          <div class="limit-row">
            <span class="limit-key">聊天室最大人数</span>
            <input class="input" v-model.number="maxMembers" type="number" min="2" max="1000000" />
          </div>
        </div>
        <div class="limit-grid" style="margin-top:8px">
          <div class="limit-row">
            <span class="limit-key">开发者秘钥</span>
            <input class="input" v-model="developerKey" type="password" placeholder="输入开发者秘钥后可保存限制" />
          </div>
        </div>
        <div class="inline-edit" style="margin-top:8px">
          <button class="btn btn-primary" @click="setRoomLimits">保存限制</button>
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn btn-danger" @click="leaveRoom">退出房间</button>
        <button class="btn btn-secondary" @click="$emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType } from '../services/websocket'

const emit = defineEmits(['close'])
const chatStore = useChatStore()

const newName = ref('')
const roomPassword = ref('')
const currentPassword = ref(null)
const maxFileSize = ref(10)
const totalFileSpace = ref(10)
const maxFileCount = ref(1500)
const maxMembers = ref(50)
const developerKey = ref('')
const selectedUser = ref('')
const avatarFileInput = ref(null)
const avatarUploading = ref(false)

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
      alert('头像太大，请选择更小的图片')
      return
    }
    avatarUploading.value = true
    chatWs.uploadRoomAvatar(chatStore.currentRoomId, base64)
  }
  img.src = URL.createObjectURL(file)
}

function onRoomAvatarUploaded(data) {
  avatarUploading.value = false
}

function renameRoom() {
  if (newName.value.trim()) {
    chatWs.renameRoom(chatStore.currentRoomId, newName.value.trim())
    newName.value = ''
  }
}

function setPassword() {
  chatWs.setRoomPassword(chatStore.currentRoomId, roomPassword.value)
  roomPassword.value = ''
}

function viewPassword() {
  chatWs.getRoomPassword(chatStore.currentRoomId)
}

function onRoomPassword(data) {
  currentPassword.value = data.password || ''
}

function onRoomSettingsNeedConfirm(data) {
  const summary = data.cleanupSummary || {}
  const clearCount = summary.clearFileCount || 0
  const afterCount = summary.afterFileCount || 0
  const afterSpaceGB = ((summary.afterUsedSpace || 0) / 1024 / 1024 / 1024).toFixed(2)
  const ok = confirm(
    `调整限制将清理 ${clearCount} 个历史文件。\n` +
    `清理后预计保留 ${afterCount} 个文件，约 ${afterSpaceGB} GB。\n` +
    `被清理文件会在聊天中保留记录，但状态显示为“文件已过期或被清除”。\n\n是否继续？`
  )
  if (!ok) return

  chatWs.setRoomSettings(
    data.roomId,
    data.maxFileSize,
    data.totalFileSpace,
    data.maxFileCount,
    data.maxMembers,
    true,
    developerKey.value,
  )
}

function setRoomLimits() {
  if (maxFileSize.value <= 0 || totalFileSpace.value <= 0 || maxFileCount.value <= 0 || maxMembers.value <= 0) {
    alert('限制值必须大于0')
    return
  }
  if (totalFileSpace.value < maxFileSize.value) {
    alert('总文件空间不能小于单文件最大值')
    return
  }
  if (!developerKey.value.trim()) {
    alert('请输入开发者秘钥')
    return
  }
  chatWs.setRoomSettings(
    chatStore.currentRoomId,
    maxFileSize.value * 1024 * 1024 * 1024,
    totalFileSpace.value * 1024 * 1024 * 1024,
    maxFileCount.value,
    maxMembers.value,
    false,
    developerKey.value,
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
  if (confirm(`确定踢出 ${selectedUser.value} ？`)) {
    chatWs.kickUser(chatStore.currentRoomId, selectedUser.value)
    selectedUser.value = ''
  }
}

function clearAllMessages() {
  if (confirm('确定清空所有消息？此操作不可撤销。')) {
    chatWs.deleteMessages(chatStore.currentRoomId, 'all')
  }
}

function deleteRoom() {
  if (confirm(`确定删除房间 "${chatStore.currentRoomName}" ？此操作不可撤销。`)) {
    chatWs.deleteRoom(chatStore.currentRoomId, chatStore.currentRoomName)
    emit('close')
  }
}

function leaveRoom() {
  if (confirm(`确定退出房间 "${chatStore.currentRoomName}" ？`)) {
    chatWs.leaveRoom(chatStore.currentRoomId)
    emit('close')
  }
}

onMounted(() => {
  chatStore.onEvent('roomPassword', onRoomPassword)
  chatStore.onEvent('roomAvatarUploaded', onRoomAvatarUploaded)
  chatStore.onEvent('roomSettingsNeedConfirm', onRoomSettingsNeedConfirm)
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
  chatStore.offEvent('roomPassword', onRoomPassword)
  chatStore.offEvent('roomAvatarUploaded', onRoomAvatarUploaded)
  chatStore.offEvent('roomSettingsNeedConfirm', onRoomSettingsNeedConfirm)
})
</script>

<style scoped>
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
