<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal" style="min-width: 340px;">
      <div class="modal-title">用户信息</div>

      <div class="user-info-card">
        <!-- 头像 -->
        <div class="user-info-avatar">
          <img v-if="avatarSrc" :src="avatarSrc" class="avatar avatar-xl" />
          <div v-else class="avatar avatar-xl avatar-placeholder"
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

      <div class="modal-actions">
        <button class="btn btn-secondary" @click="$emit('close')">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted } from 'vue'
import { useUserStore } from '../stores/user'
import { chatWs } from '../services/websocket'

const props = defineProps({
  user: { type: Object, required: true }
})
defineEmits(['close'])
const hashColor = inject('hashColor')
const userStore = useUserStore()

const avatarSrc = computed(() => {
  const data = userStore.getAvatar(props.user.username)
  return data ? 'data:image/png;base64,' + data : ''
})

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
.user-info-avatar {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
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
</style>
