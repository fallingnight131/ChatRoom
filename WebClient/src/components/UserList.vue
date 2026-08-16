<template>
  <div class="user-list">
    <div class="user-list-header">
      <span role="status" aria-live="polite">{{ onlineCountLabel }}</span>
    </div>

    <!-- 在线用户 -->
    <section class="user-section" aria-labelledby="online-members-title">
      <h2 id="online-members-title" class="visually-hidden">{{ messages.onlineMembers }}</h2>
      <ul class="member-list" role="list">
      <li v-for="user in chatStore.onlineUsers" :key="user.username">
        <button class="user-item" type="button" :aria-label="memberLabel(user, true)"
                @click="openUserInfo(user)">
        <span class="user-avatar-wrap">
          <img v-if="getAvatarSrc(user.username)" :src="getAvatarSrc(user.username)"
               class="avatar avatar-sm" :alt="avatarLabel(user.displayName || user.username)" />
          <span v-else class="avatar avatar-sm avatar-placeholder" aria-hidden="true"
               :style="{ background: hashColor(user.username) }">
            {{ (user.displayName || user.username).charAt(0) }}
          </span>
          <span class="online-dot online" aria-hidden="true"></span>
        </span>
        <span class="user-item-info">
          <span class="user-item-name text-ellipsis">
            {{ user.displayName }}
            <span v-if="user.isAdmin" class="admin-badge">{{ messages.admin }}</span>
          </span>
          <span class="user-item-id text-ellipsis">@{{ user.username }}</span>
        </span>
        </button>
      </li>
      </ul>
    </section>

    <!-- 离线用户 -->
    <section v-if="chatStore.offlineUsers.length > 0" class="user-section"
             aria-labelledby="offline-members-title">
      <h2 id="offline-members-title" class="section-label">{{ offlineCountLabel }}</h2>
      <ul class="member-list" role="list">
      <li v-for="user in chatStore.offlineUsers" :key="user.username">
        <button class="user-item offline" type="button" :aria-label="memberLabel(user, false)"
                @click="openUserInfo(user)">
        <span class="user-avatar-wrap">
          <img v-if="getAvatarSrc(user.username)" :src="getAvatarSrc(user.username)"
               class="avatar avatar-sm" :alt="avatarLabel(user.displayName || user.username)" />
          <span v-else class="avatar avatar-sm avatar-placeholder" aria-hidden="true"
               :style="{ background: hashColor(user.username), opacity: 0.5 }">
            {{ (user.displayName || user.username).charAt(0) }}
          </span>
          <span class="online-dot" aria-hidden="true"></span>
        </span>
        <span class="user-item-info">
          <span class="user-item-name text-ellipsis">
            {{ user.displayName }}
            <span v-if="user.isAdmin" class="admin-badge">{{ messages.admin }}</span>
          </span>
          <span class="user-item-id text-ellipsis">@{{ user.username }}</span>
        </span>
        </button>
      </li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { memberListMessages } from '../localization/webLocale'

const chatStore = useChatStore()
const userStore = useUserStore()
const messages = computed(() => memberListMessages(userStore.locale))
const onlineCountLabel = computed(() =>
  `${messages.value.onlineCountPrefix}${chatStore.onlineUsers.length}${messages.value.onlineCountSuffix}`)
const offlineCountLabel = computed(() =>
  `${messages.value.offlineCountPrefix}${chatStore.offlineUsers.length}${messages.value.offlineCountSuffix}`)
const openUserInfo = inject('openUserInfo')
const hashColor = inject('hashColor')

function getAvatarSrc(username) {
  const data = userStore.getAvatar(username)
  if (data) return 'data:image/png;base64,' + data
  userStore.requestAvatarIfAllowed(username)
  return ''
}

function avatarLabel(name) {
  return `${messages.value.avatarPrefix}${name}${messages.value.avatarSuffix}`
}

function memberLabel(user, online) {
  const displayName = user.displayName || user.username
  const separator = messages.value.separator
  const status = online ? messages.value.online : messages.value.offline
  const role = user.isAdmin ? `${separator}${messages.value.admin}` : ''
  return `${displayName}${separator}@${user.username}${separator}${status}${role}`
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
.member-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.section-label {
  margin: 0;
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
  width: 100%;
  border: 0;
  text-align: left;
  background: transparent;
  color: inherit;
}
.user-item:hover {
  background: var(--bg-hover);
}
.user-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
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
  display: flex;
  flex-direction: column;
}
.user-item-name {
  display: block;
  font-size: 13px;
  color: var(--text-primary);
}
.user-item-id {
  display: block;
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
