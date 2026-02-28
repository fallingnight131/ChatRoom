<template>
  <div class="chat-page">
    <!-- 移动端遮罩层 -->
    <div class="panel-overlay" v-if="mobilePanel" @click="mobilePanel = ''"></div>

    <!-- 左侧面板：个人信息 + 房间列表 -->
    <div class="left-panel" :class="{ 'panel-open': mobilePanel === 'left' }">
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
      <RoomList @room-selected="onRoomSelected" />
    </div>

    <!-- 中间面板：消息区域 -->
    <div class="center-panel" v-if="chatStore.currentRoomId">
      <!-- 房间标题栏 -->
      <div class="room-header">
        <button class="btn-icon mobile-menu-btn" @click="mobilePanel = 'left'" title="房间列表">☰</button>
        <div class="room-title text-ellipsis">{{ chatStore.currentRoomName }}</div>
        <div class="room-actions">
          <button class="btn-icon mobile-members-btn" @click="mobilePanel = 'right'" title="成员列表">👥</button>
          <button class="btn-icon" @click="showRoomSettings = true" title="房间设置">⋯</button>
        </div>
      </div>
      <!-- 消息列表 -->
      <MessageList />
      <!-- 输入区域 -->
      <InputArea />
    </div>
    <div class="center-panel empty-state" v-else>
      <button class="btn-icon mobile-menu-btn empty-menu-btn" @click="mobilePanel = 'left'" title="房间列表">☰</button>
      <div v-if="reconnecting" class="empty-icon">⏳</div>
      <div v-else class="empty-icon">💬</div>
      <p>{{ reconnecting ? '正在重新连接...' : '选择一个房间开始聊天' }}</p>
    </div>

    <!-- 右侧面板：成员列表 -->
    <div class="right-panel" :class="{ 'panel-open': mobilePanel === 'right' }" v-if="chatStore.currentRoomId">
      <div class="right-panel-header">
        <button class="btn-icon mobile-back-btn" @click="mobilePanel = ''" title="关闭">✕</button>
        <span>成员列表</span>
      </div>
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
import { chatWs, MsgType } from '../services/websocket'
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
const mobilePanel = ref('')
const reconnecting = ref(false)

function isMobile() {
  return window.innerWidth <= 768
}

function onRoomSelected() {
  // 移动端选择房间后关闭左侧面板
  if (isMobile()) {
    mobilePanel.value = ''
  }
}

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

// 自动重连登录的处理器
function onReconnectLogin(msg) {
  if (msg.data.success) {
    userStore.onLoginSuccess(msg.data)
    userStore.initListeners()
    chatStore.initListeners()
    chatWs.getAvatar(msg.data.username)
    chatWs.requestRoomList()
    reconnecting.value = false
  } else {
    // 登录失败，跳转到登录页
    reconnecting.value = false
    userStore.clearCredentials()
    router.push('/login')
  }
  // 移除一次性监听
  chatWs.off(MsgType.LOGIN_RSP, onReconnectLogin)
}

function onReconnected() {
  // WebSocket连接成功后自动登录
  const creds = userStore.getCredentials()
  if (creds && !userStore.loggedIn) {
    chatWs.on(MsgType.LOGIN_RSP, onReconnectLogin)
    chatWs.login(creds.username, creds.password)
  }
  chatWs.off('connected', onReconnected)
}

onMounted(() => {
  // 断开连接时的处理
  chatWs.on('disconnected', onDisconnected)
  chatStore.onEvent('needPassword', onNeedPassword)

  // 如果没有登录过，尝试自动重连
  if (!userStore.loggedIn) {
    const saved = JSON.parse(sessionStorage.getItem('user') || 'null')
    const creds = userStore.getCredentials()
    if (saved && creds) {
      // 有保存的会话和凭证，自动重连
      reconnecting.value = true
      userStore.username = saved.username
      userStore.displayName = saved.displayName
      userStore.userId = saved.userId

      chatWs.on('connected', onReconnected)
      chatWs.connect(userStore.serverHost, userStore.serverPort, userStore.wsPath)
    } else {
      router.push('/login')
    }
  }
})

onUnmounted(() => {
  chatWs.off('disconnected', onDisconnected)
  chatWs.off('connected', onReconnected)
  chatWs.off(MsgType.LOGIN_RSP, onReconnectLogin)
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
  position: relative;
  overflow: hidden;
}

/* 遮罩层（移动端面板展开时） */
.panel-overlay {
  display: none;
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
  gap: 8px;
}
.room-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  flex: 1;
  min-width: 0;
}

.room-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.empty-state {
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
  position: relative;
}
.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

/* 移动端菜单/成员按钮，桌面端隐藏 */
.mobile-menu-btn,
.mobile-members-btn,
.mobile-back-btn,
.empty-menu-btn {
  display: none;
}

/* 右侧面板 */
.right-panel {
  width: 220px;
  min-width: 220px;
  background: var(--bg-secondary);
  border-left: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
}

.right-panel-header {
  display: none;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

/* ========== 平板适配 (769px - 1024px) ========== */
@media (max-width: 1024px) {
  .left-panel {
    width: 240px;
    min-width: 240px;
  }
  .right-panel {
    width: 200px;
    min-width: 200px;
  }
}

/* ========== 手机适配 (≤768px) ========== */
@media (max-width: 768px) {
  .chat-page {
    position: relative;
  }

  /* 遮罩层 */
  .panel-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 500;
    animation: fadeIn 0.2s;
  }

  /* 左侧面板 - 抽屉式 */
  .left-panel {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 85vw;
    max-width: 320px;
    min-width: unset;
    z-index: 600;
    transform: translateX(-100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: none;
  }
  .left-panel.panel-open {
    transform: translateX(0);
    box-shadow: 4px 0 16px rgba(0, 0, 0, 0.2);
  }

  /* 右侧面板 - 抽屉式 */
  .right-panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: 85vw;
    max-width: 320px;
    min-width: unset;
    z-index: 600;
    transform: translateX(100%);
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: none;
  }
  .right-panel.panel-open {
    transform: translateX(0);
    box-shadow: -4px 0 16px rgba(0, 0, 0, 0.2);
  }

  .right-panel-header {
    display: flex;
  }

  /* 中间面板全宽 */
  .center-panel {
    width: 100%;
  }

  /* 显示移动端专属按钮 */
  .mobile-menu-btn,
  .mobile-members-btn {
    display: inline-flex;
  }
  .empty-menu-btn {
    display: inline-flex;
    position: absolute;
    top: 16px;
    left: 16px;
    font-size: 24px;
  }
  .mobile-back-btn {
    display: inline-flex;
  }

  /* 头部调整 */
  .room-header {
    padding: 10px 12px;
  }
  .room-title {
    font-size: 15px;
  }

  /* 用户头部 */
  .user-header {
    padding: 14px 12px;
    /* 安全区适配 */
    padding-top: max(14px, env(safe-area-inset-top));
  }
}

/* ========== 小屏手机 (≤480px) ========== */
@media (max-width: 480px) {
  .left-panel {
    width: 90vw;
  }
  .right-panel {
    width: 90vw;
  }
  .empty-icon {
    font-size: 48px;
  }
}
</style>
