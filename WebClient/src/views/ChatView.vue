<template>
  <div class="chat-page">
    <!-- 左侧面板：个人信息 + 房间列表 -->
    <div class="left-panel">
      <div class="user-header" @click="showProfile = true">
        <img v-if="userStore.avatarData" :src="'data:image/png;base64,' + userStore.avatarData"
             class="avatar" />
        <div v-else class="avatar avatar-placeholder"
             :style="{ background: hashColor(userStore.username) }">
          {{ (userStore.displayName || userStore.username).charAt(0) }}
        </div>
        <div class="user-info">
          <div class="user-name text-ellipsis">{{ userStore.displayName }}</div>
          <div class="user-id text-ellipsis">@{{ userStore.username }}</div>
        </div>
        <button class="btn-icon theme-btn" @click.stop="userStore.toggleDarkMode()" title="切换主题">
          {{ userStore.darkMode ? '☀️' : '🌙' }}
        </button>
      </div>
      <RoomList />
    </div>

    <!-- 中间面板：消息区域 -->
    <div class="center-panel" v-if="chatStore.currentRoomId">
      <!-- 房间标题栏 -->
      <div class="room-header">
        <div class="room-title text-ellipsis">{{ chatStore.currentRoomName }}</div>
        <div class="room-actions">
          <button class="btn-icon" @click="showRoomSettings = true" title="房间设置">⋯</button>
        </div>
      </div>
      <!-- 消息列表 -->
      <MessageList />
      <!-- 输入区域 -->
      <InputArea />
    </div>
    <div class="center-panel empty-state" v-else>
      <div class="empty-icon">💬</div>
      <p>选择一个房间开始聊天</p>
    </div>

    <!-- 右侧面板：成员列表 -->
    <div class="right-panel" v-if="chatStore.currentRoomId">
      <UserList />
    </div>

    <!-- 弹窗 -->
    <ProfileDialog v-if="showProfile" @close="showProfile = false" />
    <RoomSettingsDialog v-if="showRoomSettings" @close="showRoomSettings = false" />
    <UserInfoDialog v-if="showUserInfo" :user="selectedUser" @close="showUserInfo = false" />
    <RoomPasswordDialog v-if="showPasswordPrompt" :roomData="passwordRoomData"
                        @close="showPasswordPrompt = false" @submit="onPasswordSubmit" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, provide } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs } from '../services/websocket'
import RoomList from '../components/RoomList.vue'
import MessageList from '../components/MessageList.vue'
import InputArea from '../components/InputArea.vue'
import UserList from '../components/UserList.vue'
import ProfileDialog from '../components/ProfileDialog.vue'
import RoomSettingsDialog from '../components/RoomSettingsDialog.vue'
import UserInfoDialog from '../components/UserInfoDialog.vue'
import RoomPasswordDialog from '../components/RoomPasswordDialog.vue'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

const showProfile = ref(false)
const showRoomSettings = ref(false)
const showUserInfo = ref(false)
const selectedUser = ref(null)
const showPasswordPrompt = ref(false)
const passwordRoomData = ref(null)

function hashColor(str) {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const h = Math.abs(hash) % 360
  return `hsl(${h}, 55%, 50%)`
}

function openUserInfo(user) {
  selectedUser.value = user
  showUserInfo.value = true
}

function onPasswordSubmit(password) {
  if (passwordRoomData.value) {
    chatWs.joinRoom(passwordRoomData.value.roomId, password)
  }
  showPasswordPrompt.value = false
}

// 需要密码事件
function onNeedPassword(data) {
  passwordRoomData.value = data
  showPasswordPrompt.value = true
}

// 提供给子组件使用
provide('openUserInfo', openUserInfo)
provide('hashColor', hashColor)

onMounted(() => {
  // 断开连接时跳回登录
  chatWs.on('disconnected', onDisconnected)
  chatStore.onEvent('needPassword', onNeedPassword)

  // 如果没有登录过，重定向
  if (!userStore.loggedIn) {
    const saved = JSON.parse(sessionStorage.getItem('user') || 'null')
    if (!saved) {
      router.push('/login')
    }
  }
})

onUnmounted(() => {
  chatWs.off('disconnected', onDisconnected)
  chatStore.offEvent('needPassword', onNeedPassword)
})

function onDisconnected() {
  // 意外断开
}
</script>

<style scoped>
.chat-page {
  height: 100%;
  display: flex;
  background: var(--bg-primary);
}

/* 左侧面板 */
.left-panel {
  width: 280px;
  min-width: 280px;
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
}

.user-header {
  display: flex;
  align-items: center;
  padding: 16px;
  gap: 12px;
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
  transition: background 0.15s;
}
.user-header:hover {
  background: var(--bg-hover);
}

.user-info {
  flex: 1;
  min-width: 0;
}
.user-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}
.user-id {
  font-size: 12px;
  color: var(--text-tertiary);
}

.theme-btn {
  font-size: 18px;
}

/* 中间面板 */
.center-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-primary);
}

.room-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
}
.room-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.empty-state {
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
}
.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

/* 右侧面板 */
.right-panel {
  width: 220px;
  min-width: 220px;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
}
</style>
