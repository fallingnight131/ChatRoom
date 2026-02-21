<template>
  <div class="user-list">
    <div class="user-list-header">
      <span>在线 {{ chatStore.onlineUsers.length }}</span>
    </div>

    <!-- 在线用户 -->
    <div class="user-section">
      <div v-for="user in chatStore.onlineUsers" :key="user.username"
           class="user-item" @click="openUserInfo(user)">
        <div class="user-avatar-wrap">
          <img v-if="getAvatarSrc(user.username)" :src="getAvatarSrc(user.username)"
               class="avatar avatar-sm" />
          <div v-else class="avatar avatar-sm avatar-placeholder"
               :style="{ background: hashColor(user.username) }">
            {{ (user.displayName || user.username).charAt(0) }}
          </div>
          <div class="online-dot online"></div>
        </div>
        <div class="user-item-info">
          <div class="user-item-name text-ellipsis">
            {{ user.displayName }}
            <span v-if="user.isAdmin" class="admin-badge">管理员</span>
          </div>
          <div class="user-item-id text-ellipsis">@{{ user.username }}</div>
        </div>
      </div>
    </div>

    <!-- 离线用户 -->
    <div v-if="chatStore.offlineUsers.length > 0" class="user-section">
      <div class="section-label">离线 {{ chatStore.offlineUsers.length }}</div>
      <div v-for="user in chatStore.offlineUsers" :key="user.username"
           class="user-item offline" @click="openUserInfo(user)">
        <div class="user-avatar-wrap">
          <img v-if="getAvatarSrc(user.username)" :src="getAvatarSrc(user.username)"
               class="avatar avatar-sm" />
          <div v-else class="avatar avatar-sm avatar-placeholder"
               :style="{ background: hashColor(user.username), opacity: 0.5 }">
            {{ (user.displayName || user.username).charAt(0) }}
          </div>
          <div class="online-dot"></div>
        </div>
        <div class="user-item-info">
          <div class="user-item-name text-ellipsis">
            {{ user.displayName }}
            <span v-if="user.isAdmin" class="admin-badge">管理员</span>
          </div>
          <div class="user-item-id text-ellipsis">@{{ user.username }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { inject } from 'vue'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { chatWs } from '../services/websocket'

const chatStore = useChatStore()
const userStore = useUserStore()
const openUserInfo = inject('openUserInfo')
const hashColor = inject('hashColor')

function getAvatarSrc(username) {
  const data = userStore.getAvatar(username)
  if (data) return 'data:image/png;base64,' + data
  if (username && !userStore.avatarCache[username]) {
    userStore.avatarCache[username] = ''
    chatWs.getAvatar(username)
  }
  return ''
}
</script>

<style scoped>
.user-list {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.user-list-header {
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-light);
}
.user-section {
  padding: 4px 8px;
}
.section-label {
  padding: 8px 12px 4px;
  font-size: 12px;
  color: var(--text-tertiary);
  font-weight: 600;
}
.user-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.user-item:hover {
  background: var(--bg-hover);
}
.user-item.offline {
  opacity: 0.6;
}
.user-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}
.online-dot {
  position: absolute;
  bottom: -1px;
  right: -1px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #888;
  border: 2px solid var(--bg-secondary);
}
.online-dot.online {
  background: var(--success);
}
.user-item-info {
  flex: 1;
  min-width: 0;
}
.user-item-name {
  font-size: 13px;
  color: var(--text-primary);
}
.user-item-id {
  font-size: 11px;
  color: var(--text-tertiary);
}
.admin-badge {
  font-size: 10px;
  background: var(--warning);
  color: #fff;
  padding: 1px 5px;
  border-radius: 3px;
  margin-left: 4px;
  font-weight: 600;
}

/* ========== 移动端适配 ========== */
@media (max-width: 768px) {
  .user-item {
    padding: 10px 12px;
  }
  .user-item-name {
    font-size: 14px;
  }
  .user-item-id {
    font-size: 12px;
  }
}
</style>
