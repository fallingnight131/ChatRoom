<template>
  <div class="modal-overlay" @click.self="closeDialog">
    <div ref="dialogRef" class="modal user-info-modal" role="dialog" aria-modal="true"
         aria-labelledby="user-info-title" tabindex="-1" @keydown="onDialogKeydown">
      <div id="user-info-title" class="modal-title">用户信息</div>

      <div class="user-info-card">
        <!-- 头像 -->
        <div class="user-info-avatar">
          <button v-if="avatarSrc" type="button" class="avatar-preview-trigger"
                  :aria-label="`预览 ${userDisplayName} 的头像`" @click="showAvatarPreview = true">
            <img :src="avatarSrc" class="avatar avatar-large-preview"
                 :alt="`${userDisplayName} 的头像`" />
          </button>
          <div v-else class="avatar avatar-large-preview avatar-placeholder"
               :style="{ background: hashColor(props.user.username) }">
            {{ (props.user.displayName || props.user.username).charAt(0) }}
          </div>
        </div>

        <!-- 信息 -->
        <div class="user-detail">
          <div class="detail-row">
            <span class="detail-label">昵称</span>
            <span class="detail-value">{{ props.user.displayName }}</span>
          </div>
          <div class="detail-row">
            <span class="detail-label">用户ID</span>
            <span class="detail-value">@{{ props.user.username }}</span>
          </div>
          <div class="detail-row">
            <span class="detail-label">状态</span>
            <span class="detail-value">
              <span :class="['status-dot', props.user.isOnline ? 'online' : '']"></span>
              {{ props.user.isOnline ? '在线' : '离线' }}
            </span>
          </div>
          <div class="detail-row">
            <span class="detail-label">角色</span>
            <span class="detail-value">
              {{ props.user.isAdmin ? '管理员' : '普通成员' }}
            </span>
          </div>
        </div>
      </div>

      <!-- 管理员操作区域 -->
      <div v-if="showAdminActions" class="admin-actions">
        <div class="admin-actions-title">管理员操作</div>
        <div class="admin-actions-btns">
          <button v-if="!props.user.isAdmin" class="btn btn-secondary" type="button" @click="setAdmin">
            设为管理员
          </button>
          <button v-else class="btn btn-secondary" type="button" @click="unsetAdmin">
            取消管理员
          </button>
          <button v-if="!isSelf" class="btn btn-danger" type="button" @click="kickUser">
            踢出聊天室
          </button>
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn btn-secondary" type="button" @click="closeDialog">关闭</button>
      </div>
    </div>

    <AvatarPreviewDialog v-if="showAvatarPreview && avatarSrc" :src="avatarSrc"
                         :alt="`${userDisplayName} 的头像大图`"
                         @close="showAvatarPreview = false" />
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import AvatarPreviewDialog from './AvatarPreviewDialog.vue'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs } from '../services/websocket'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'

const props = defineProps({
  user: { type: Object, required: true }
})
const emit = defineEmits(['close'])
const hashColor = inject('hashColor')
const userStore = useUserStore()
const chatStore = useChatStore()
const showAvatarPreview = ref(false)
const { dialogRef, closeDialog, onDialogKeydown } = useModalKeyboardBoundary({
  onClose: () => emit('close'),
  canClose: () => !showAvatarPreview.value
})

const userDisplayName = computed(() => props.user.displayName || props.user.username)

const avatarSrc = computed(() => {
  const data = userStore.getAvatar(props.user.username)
  return data ? 'data:image/png;base64,' + data : ''
})

const isSelf = computed(() => props.user.username === userStore.username)

const showAdminActions = computed(() => {
  // 管理员可以操作非自己的用户（设管理员、踢出），也可以操作自己（取消管理员）
  return chatStore.isAdmin && chatStore.currentRoomId != null
})

function setAdmin() {
  chatWs.setAdmin(chatStore.currentRoomId, props.user.username, true)
  emit('close')
}

function unsetAdmin() {
  chatWs.setAdmin(chatStore.currentRoomId, props.user.username, false)
  emit('close')
}

function kickUser() {
  if (confirm(`确定要将 ${props.user.displayName || props.user.username} 踢出聊天室吗？`)) {
    chatWs.kickUser(chatStore.currentRoomId, props.user.username)
    emit('close')
  }
}

onMounted(() => {
  if (!userStore.avatarCache[props.user.username]) {
    chatWs.getAvatar(props.user.username)
  }
})
</script>

<style scoped>
.user-info-card {
  text-align: center;
}
.user-info-modal {
  width: min(420px, calc(100vw - 32px));
}
.user-info-avatar {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}
.avatar-large-preview {
  width: 112px;
  height: 112px;
  border-radius: 16px;
  object-fit: cover;
  display: block;
}
.avatar-preview-trigger {
  padding: 0;
  border: 0;
  border-radius: 16px;
  background: transparent;
  cursor: pointer;
}
.avatar-preview-trigger:focus-visible {
  outline: 3px solid var(--primary);
  outline-offset: 3px;
}
.user-detail {
  text-align: left;
}
.detail-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid var(--border-light);
  font-size: 14px;
}
.detail-row:last-child {
  border-bottom: none;
}
.detail-label {
  color: var(--text-secondary);
}
.detail-value {
  color: var(--text-primary);
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #888;
  display: inline-block;
}
.status-dot.online {
  background: var(--success);
}

.admin-actions {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--border-light);
}
.admin-actions-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 10px;
}
.admin-actions-btns {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.admin-actions-btns .btn {
  flex: 1;
  min-width: 100px;
}

@media (max-width: 768px) {
  .avatar-large-preview {
    width: 132px;
    height: 132px;
    border-radius: 18px;
  }
}
</style>
