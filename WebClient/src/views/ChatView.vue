<template>
  <div class="chat-page">
    <div v-if="networkOffline" class="connection-banner" role="status" aria-live="polite">
      {{ shellMessages.offlineBanner }}
    </div>
    <!-- 移动端遮罩层 -->
    <div class="panel-overlay" v-if="mobilePanel" @click="mobilePanel = ''"></div>

    <!-- 左侧面板：个人信息 + 房间列表 -->
    <div class="left-panel" :class="{ 'panel-open': mobilePanel === 'left' }">
      <div class="user-header">
        <button class="user-profile-trigger" type="button" aria-haspopup="dialog"
                :aria-expanded="showProfile" :aria-label="shellMessages.openProfile"
                @click="showProfile = true">
          <img v-if="userStore.avatarData" :src="'data:image/png;base64,' + userStore.avatarData"
               class="avatar" :alt="shellMessages.userAvatar" />
          <div v-else class="avatar avatar-placeholder" aria-hidden="true"
               :style="{ background: hashColor(userStore.username) }">
            {{ (userStore.displayName || userStore.username).charAt(0) }}
          </div>
          <div class="user-info">
            <div class="user-name text-ellipsis">{{ userStore.displayName }}</div>
            <div class="user-id text-ellipsis">@{{ userStore.username }}</div>
          </div>
        </button>
        <button class="btn-icon theme-btn" type="button" @click="userStore.toggleDarkMode()" :title="shellMessages.theme"
                :aria-label="userStore.darkMode ? shellMessages.lightTheme : shellMessages.darkTheme">
          {{ userStore.darkMode ? '☀️' : '🌙' }}
        </button>
      </div>
      <div class="tab-bar" role="tablist" :aria-label="shellMessages.conversationTypes">
        <button id="friends-tab" class="tab-btn" type="button" role="tab"
                :class="{ active: activeTab === 'friends' }" :aria-selected="activeTab === 'friends'"
                :tabindex="activeTab === 'friends' ? 0 : -1"
                aria-controls="friends-panel" @click="switchToFriends" @keydown="onTabKeydown">
          {{ shellMessages.friends }}
          <span v-if="chatStore.totalFriendUnread > 0 || chatStore.hasPendingFriendReq" class="tab-dot" aria-hidden="true"></span>
        </button>
        <button id="rooms-tab" class="tab-btn" type="button" role="tab"
                :class="{ active: activeTab === 'rooms' }" :aria-selected="activeTab === 'rooms'"
                :tabindex="activeTab === 'rooms' ? 0 : -1"
                aria-controls="rooms-panel" @click="activeTab = 'rooms'" @keydown="onTabKeydown">
          {{ shellMessages.rooms }}
          <span v-if="chatStore.totalRoomUnread > 0" class="tab-dot" aria-hidden="true"></span>
        </button>
      </div>
      <RoomList v-if="activeTab === 'rooms'" id="rooms-panel" role="tabpanel"
            aria-labelledby="rooms-tab"
            @room-selected="onRoomSelected"
            @open-room-settings="showRoomSettings = true"
            @open-room-files="showRoomFiles = true" />
      <FriendList v-else id="friends-panel" role="tabpanel" aria-labelledby="friends-tab"
            @friend-selected="onFriendSelected" @view-user-info="onViewUserInfo" />
    </div>

    <!-- 中间面板：消息区域（房间模式） -->
    <div class="center-panel" v-if="chatStore.currentRoomId && !chatStore.isFriendChat">
      <!-- 房间标题栏 -->
      <div class="room-header">
        <button class="btn-icon mobile-menu-btn" type="button" :aria-label="shellMessages.openConversations"
                @click="mobilePanel = 'left'" :title="shellMessages.conversationList">☰</button>
        <div class="room-title text-ellipsis">{{ chatStore.currentRoomName }}</div>
        <div class="room-actions">
          <button class="btn-icon mobile-members-btn" type="button" :aria-label="shellMessages.openMembers"
                  @click="mobilePanel = 'right'" :title="shellMessages.memberList">👥</button>
          <button class="btn-icon" type="button" :aria-label="shellMessages.openRoomSettings" aria-haspopup="dialog"
                  :aria-expanded="showRoomSettings" @click="showRoomSettings = true" :title="shellMessages.roomSettings">⋯</button>
        </div>
      </div>
      <!-- 消息列表 -->
      <MessageList />
      <!-- 输入区域 -->
      <InputArea />
    </div>

    <!-- 中间面板：好友私聊模式 -->
    <div class="center-panel" v-else-if="chatStore.isFriendChat && chatStore.currentFriendUsername">
      <div class="room-header">
        <button class="btn-icon mobile-menu-btn" type="button" :aria-label="shellMessages.openConversations"
                @click="mobilePanel = 'left'" :title="shellMessages.conversationList">☰</button>
        <div class="room-title text-ellipsis">{{ shellMessages.directMessage }} - {{ chatStore.currentFriendDisplayName || chatStore.currentFriendUsername }}</div>
      </div>
      <MessageList :friend-mode="true" />
      <InputArea :friend-mode="true" />
    </div>

    <div class="center-panel empty-state" v-else>
      <button class="btn-icon mobile-menu-btn empty-menu-btn" type="button" :aria-label="shellMessages.openConversations"
              @click="mobilePanel = 'left'" :title="shellMessages.conversationList">☰</button>
      <div v-if="networkOffline" class="empty-icon" aria-hidden="true">📴</div>
      <div v-else-if="reconnecting" class="empty-icon" aria-hidden="true">⏳</div>
      <div v-else class="empty-icon">💬</div>
      <p role="status" aria-live="polite">{{ emptyStateMessage }}</p>
    </div>

    <!-- 右侧面板：成员列表（仅房间模式） -->
    <div class="right-panel" :class="{ 'panel-open': mobilePanel === 'right' }" v-if="chatStore.currentRoomId && !chatStore.isFriendChat">
      <div class="right-panel-header">
        <button class="btn-icon mobile-back-btn" type="button" :aria-label="shellMessages.closeMembers"
                @click="mobilePanel = ''" :title="shellMessages.close">✕</button>
        <span>{{ shellMessages.memberList }}</span>
      </div>
      <UserList />
    </div>

    <!-- 弹窗 -->
    <ProfileDialog v-if="showProfile" @close="showProfile = false" />
    <RoomSettingsDialog v-if="showRoomSettings" @close="showRoomSettings = false" />
    <RoomFileManagerDialog v-if="showRoomFiles" @close="showRoomFiles = false" />
    <UserInfoDialog v-if="showUserInfo" :user="selectedUser" @close="showUserInfo = false" />
    <RoomPasswordDialog v-if="showPasswordPrompt" :roomData="passwordRoomData"
                        @close="closePasswordPrompt" @submit="onPasswordSubmit" />

    <!-- 被顶号提示 -->
    <div class="modal-overlay" v-if="userStore.forceOfflineReason">
      <div ref="forceOfflineDialogRef" class="modal force-offline-dialog" role="alertdialog"
           aria-modal="true" aria-labelledby="force-offline-title"
           aria-describedby="force-offline-description" tabindex="-1"
           @keydown="onForceOfflineKeydown">
        <div id="force-offline-title" class="modal-title">{{ shellMessages.connectionLost }}</div>
        <p id="force-offline-description" class="force-offline-description">{{ userStore.forceOfflineReason }}</p>
        <div class="modal-actions">
          <button id="force-offline-login" class="btn btn-primary" type="button"
                  @click="onForceOfflineConfirm">{{ shellMessages.signInAgain }}</button>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { computed, nextTick, ref, onMounted, onUnmounted, provide } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType } from '../services/websocket'
import RoomList from '../components/RoomList.vue'
import FriendList from '../components/FriendList.vue'
import MessageList from '../components/MessageList.vue'
import InputArea from '../components/InputArea.vue'
import UserList from '../components/UserList.vue'
import ProfileDialog from '../components/ProfileDialog.vue'
import RoomSettingsDialog from '../components/RoomSettingsDialog.vue'
import RoomFileManagerDialog from '../components/RoomFileManagerDialog.vue'
import UserInfoDialog from '../components/UserInfoDialog.vue'
import RoomPasswordDialog from '../components/RoomPasswordDialog.vue'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { chatShellMessages } from '../localization/webLocale'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

const showProfile = ref(false)
const showRoomSettings = ref(false)
const showRoomFiles = ref(false)
const showUserInfo = ref(false)
const selectedUser = ref(null)
const showPasswordPrompt = ref(false)
const passwordRoomData = ref(null)
const mobilePanel = ref('')
const reconnecting = ref(false)
const networkOffline = ref(typeof navigator !== 'undefined' && navigator.onLine === false)
const activeTab = ref('friends')
const shellMessages = computed(() => chatShellMessages(userStore.locale))
const emptyStateMessage = computed(() => {
  if (networkOffline.value) return shellMessages.value.offlineEmpty
  if (reconnecting.value) return shellMessages.value.reconnecting
  return activeTab.value === 'friends'
    ? shellMessages.value.selectFriend
    : shellMessages.value.selectRoom
})
const forceOfflineActive = computed(() => !!userStore.forceOfflineReason)
const {
  dialogRef: forceOfflineDialogRef,
  onDialogKeydown: onForceOfflineKeydown,
} = useModalKeyboardBoundary({
  onClose: () => {},
  canClose: () => false,
  initialFocusSelector: '#force-offline-login',
  active: forceOfflineActive,
})

function isMobile() {
  return window.innerWidth <= 768
}

function onRoomSelected() {
  // 切回房间模式
  chatStore.exitFriendChat()
  // 移动端选择房间后关闭左侧面板
  if (isMobile()) {
    mobilePanel.value = ''
  }
}

function switchToFriends() {
  activeTab.value = 'friends'
  chatWs.requestFriendList()
}

function selectNavigationTab(tab) {
  if (tab === 'friends') switchToFriends()
  else activeTab.value = 'rooms'
  nextTick(() => document.getElementById(`${tab}-tab`)?.focus())
}

function onTabKeydown(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const nextTab = event.key === 'ArrowLeft' || event.key === 'Home' ? 'friends' : 'rooms'
  selectNavigationTab(nextTab)
}

function onFriendSelected() {
  if (isMobile()) {
    mobilePanel.value = ''
  }
}

function onViewUserInfo(username, displayName) {
  openUserInfo({ username, displayName })
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
  const roomId = passwordRoomData.value?.roomId
  closePasswordPrompt()
  if (roomId != null) chatWs.joinRoom(roomId, password)
}

function closePasswordPrompt() {
  showPasswordPrompt.value = false
  passwordRoomData.value = null
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
    chatStore.beginAttachmentSession(msg.data.username)
    userStore.requestAvatarIfAllowed(msg.data.username)
    chatWs.requestRoomList()
    chatWs.requestFriendList()
    chatStore.resumeCurrentRoom()
    chatStore.resumeCurrentFriend()
    reconnecting.value = false
  } else {
    // 登录失败，跳转到登录页
    reconnecting.value = false
    chatStore.endAttachmentSession()
    userStore.onLogout()
    router.push('/login')
  }
  // 移除一次性监听
  chatWs.off(MsgType.LOGIN_RSP, onReconnectLogin)
}

function onReconnected() {
  // WebSocket连接成功后自动登录
  const creds = userStore.getSessionCredentials()
  if (creds) {
    chatWs.off(MsgType.LOGIN_RSP, onReconnectLogin)
    chatWs.on(MsgType.LOGIN_RSP, onReconnectLogin)
    chatWs.login(creds.username, creds.password)
  } else {
    reconnecting.value = false
    chatStore.endAttachmentSession()
    userStore.onLogout()
    router.push('/login')
  }
  chatWs.off('connected', onReconnected)
}

onMounted(() => {
  // 断开连接时的处理
  chatWs.on('disconnected', onDisconnected)
  chatWs.on('offline', onNetworkOffline)
  chatWs.on('online', onNetworkOnline)
  chatStore.onEvent('needPassword', onNeedPassword)

  // 页面刷新后内存凭证已丢失，必须重新登录。
  if (!userStore.loggedIn) {
    router.push('/login')
  }
})

onUnmounted(() => {
  chatWs.off('disconnected', onDisconnected)
  chatWs.off('offline', onNetworkOffline)
  chatWs.off('online', onNetworkOnline)
  chatWs.off('connected', onReconnected)
  chatWs.off(MsgType.LOGIN_RSP, onReconnectLogin)
  chatStore.offEvent('needPassword', onNeedPassword)
})

function onDisconnected() {
  if (!userStore.loggedIn) return
  reconnecting.value = true
  chatWs.off('connected', onReconnected)
  chatWs.on('connected', onReconnected)
}

function onNetworkOffline() {
  networkOffline.value = true
  reconnecting.value = false
}

function onNetworkOnline() {
  networkOffline.value = false
  if (userStore.loggedIn) reconnecting.value = true
}

function onForceOfflineConfirm() {
  userStore.forceOfflineReason = ''
  chatStore.endAttachmentSession()
  userStore.onLogout()
  router.push('/login')
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

.connection-banner {
  position: fixed;
  z-index: 1200;
  top: max(8px, env(safe-area-inset-top));
  left: 50%;
  transform: translateX(-50%);
  max-width: calc(100vw - 32px);
  padding: 8px 14px;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  box-shadow: var(--shadow-md);
  font-size: 13px;
  text-align: center;
}

.force-offline-dialog {
  width: min(380px, calc(100vw - 32px));
  text-align: center;
}
.force-offline-description {
  padding: 16px 20px;
  margin: 0;
  line-height: 1.6;
  color: var(--text-primary);
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
  padding: 8px 10px;
  gap: 4px;
  border-bottom: 1px solid var(--border-color);
}
.user-profile-trigger {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 6px;
  border: 0;
  border-radius: 8px;
  text-align: left;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s;
}
.user-profile-trigger:hover,
.user-profile-trigger:focus-visible {
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

/* 标签栏 */
.tab-bar {
  display: flex;
  border-bottom: 1px solid var(--border-color);
}
.tab-btn {
  flex: 1;
  padding: 10px;
  font-size: 14px;
  font-weight: 600;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: all 0.2s;
  position: relative;
}
.tab-dot {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  right: 8px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #e53935;
}
.tab-btn.active {
  color: var(--accent-color, #4CAF50);
  border-bottom-color: var(--accent-color, #4CAF50);
}
.tab-btn:hover {
  background: var(--bg-hover);
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
