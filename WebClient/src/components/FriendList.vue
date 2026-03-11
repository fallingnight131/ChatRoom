<template>
  <div class="friend-list">
    <div class="friend-list-header">
      <span class="friend-list-title">好友列表</span>
      <div class="friend-actions-row">
        <button class="btn-icon" @click="showAddFriend = true" title="搜索好友">🔍</button>
        <button class="btn-icon" @click="showPending" title="好友申请">
          📩
          <span v-if="chatStore.hasPendingFriendReq" class="req-dot"></span>
        </button>
        <button class="btn-icon" @click="refreshFriends" title="刷新">🔄</button>
      </div>
    </div>

    <div class="friend-items">
      <div v-for="fr in chatStore.friends" :key="fr.username"
           class="friend-item"
           :class="{ active: chatStore.isFriendChat && chatStore.currentFriendUsername === fr.username }"
           @click="selectFriend(fr)"
           @contextmenu.prevent="onContextMenu($event, fr)">
        <div class="friend-avatar-wrap" :class="{ online: fr.isOnline }">
          <img v-if="getAvatarSrc(fr.username)" :src="getAvatarSrc(fr.username)" class="avatar avatar-sm" />
          <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(fr.username) }">
            {{ (fr.displayName || fr.username).charAt(0) }}
          </div>
        </div>
        <div class="friend-info">
          <div class="friend-name text-ellipsis">{{ fr.displayName || fr.username }}</div>
          <div class="friend-status">{{ fr.isOnline ? '在线' : '离线' }}</div>
        </div>
        <span v-if="(chatStore.friendUnread[fr.username] || 0) > 0" class="badge">{{ chatStore.friendUnread[fr.username] > 99 ? '99+' : chatStore.friendUnread[fr.username] }}</span>
      </div>
      <div v-if="chatStore.friends.length === 0" class="friend-empty">
        暂无好友，点击 🔍 搜索
      </div>
    </div>

    <!-- 添加好友弹窗 -->
    <div class="modal-overlay" v-if="showAddFriend" @click.self="showAddFriend = false">
      <div class="modal" style="max-width: 420px;">
        <div class="modal-title">搜索好友</div>
        <div class="search-row">
          <input class="input search-input" v-model="searchKeyword"
                 placeholder="输入用户ID或昵称搜索"
                 @keyup.enter="doSearch" />
          <button class="btn btn-primary" @click="doSearch" :disabled="searching">
            {{ searching ? '搜索中…' : '搜索' }}
          </button>
        </div>
        <div class="search-results">
          <div v-if="searchResults === null" class="search-hint">输入关键词后点击搜索</div>
          <div v-else-if="searchResults.length === 0" class="search-hint">未找到匹配的用户</div>
          <div v-for="u in searchResults" :key="u.username" class="search-result-item">
            <div class="search-avatar-wrap">
              <img v-if="getAvatarSrc(u.username)" :src="getAvatarSrc(u.username)" class="avatar avatar-sm" />
              <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(u.username) }">
                {{ (u.displayName || u.username).charAt(0) }}
              </div>
            </div>
            <div class="search-user-info">
              <div class="search-display-name text-ellipsis">{{ u.displayName }}</div>
              <div class="search-username">ID: {{ u.username }}</div>
            </div>
            <div class="search-user-status" v-if="u.online">
              <span class="online-dot"></span>
            </div>
            <button v-if="isFriend(u.username)" class="btn btn-secondary btn-sm" disabled>已添加</button>
            <button v-else class="btn btn-primary btn-sm" @click="sendRequestTo(u.username)">发送申请</button>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="closeAddFriend">关闭</button>
        </div>
      </div>
    </div>

    <!-- 好友申请弹窗 -->
    <div class="modal-overlay" v-if="showPendingDialog" @click.self="showPendingDialog = false">
      <div class="modal" style="max-width: 420px;">
        <div class="modal-title">待处理的好友申请</div>
        <div class="search-results">
          <div v-if="pendingRequests.length === 0" class="search-hint">暂无待处理的好友申请</div>
          <div v-for="req in pendingRequests" :key="req.requestId" class="search-result-item">
            <div class="search-avatar-wrap">
              <img v-if="getAvatarSrc(req.fromUsername)" :src="getAvatarSrc(req.fromUsername)" class="avatar avatar-sm" />
              <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(req.fromUsername) }">
                {{ (req.fromDisplayName || req.fromUsername).charAt(0) }}
              </div>
            </div>
            <div class="search-user-info">
              <div class="search-display-name text-ellipsis">{{ req.fromDisplayName || req.fromUsername }}</div>
              <div class="search-username">ID: {{ req.fromUsername }}</div>
            </div>
            <div class="pending-actions">
              <button class="btn btn-primary btn-sm" @click="acceptRequest(req)">接受</button>
              <button class="btn btn-secondary btn-sm" @click="rejectRequest(req)">拒绝</button>
            </div>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showPendingDialog = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div v-if="contextMenu.show" class="context-menu"
         :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
         @click="contextMenu.show = false">
      <div class="context-menu-item" @click="viewFriendInfo(contextMenu.friend)">查看信息</div>
      <div class="context-menu-item danger" @click="removeFriend(contextMenu.friend)">删除好友</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, inject, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { chatWs } from '../services/websocket'

const chatStore = useChatStore()
const userStore = useUserStore()
const hashColor = inject('hashColor', (str) => {
  let hash = 0
  for (let i = 0; i < str.length; i++) hash = str.charCodeAt(i) + ((hash << 5) - hash)
  return `hsl(${Math.abs(hash) % 360}, 55%, 50%)`
})

function getAvatarSrc(username) {
  const data = userStore.getAvatar(username)
  if (data) return 'data:image/png;base64,' + data
  if (username && !userStore.avatarCache[username]) {
    userStore.avatarCache[username] = ''
    chatWs.getAvatar(username)
  }
  return ''
}

const emit = defineEmits(['friend-selected', 'view-user-info'])

const showAddFriend = ref(false)
const showPendingDialog = ref(false)
const searchKeyword = ref('')
const searchResults = ref(null)
const searching = ref(false)
const pendingRequests = ref([])

const contextMenu = reactive({ show: false, x: 0, y: 0, friend: null })

function selectFriend(fr) {
  chatStore.setCurrentFriend(fr.username)
  emit('friend-selected')
}

function doSearch() {
  const kw = searchKeyword.value.trim()
  if (!kw) return
  searching.value = true
  chatWs.searchUsers(kw)
}

function onSearchResults(users) {
  searching.value = false
  searchResults.value = users
}

function sendRequestTo(username) {
  chatWs.sendFriendRequest(username)
  // 从搜索结果中移除已发送的用户
  if (searchResults.value) {
    searchResults.value = searchResults.value.filter(u => u.username !== username)
  }
}

function isFriend(username) {
  return chatStore.friends.some(f => f.username === username)
}

function closeAddFriend() {
  showAddFriend.value = false
  searchKeyword.value = ''
  searchResults.value = null
  searching.value = false
}

function showPending() {
  chatWs.requestFriendPending()
  chatStore.hasPendingFriendReq = false
  showPendingDialog.value = true
}

function refreshFriends() {
  chatWs.requestFriendList()
}

function acceptRequest(req) {
  chatWs.acceptFriendRequest(req.requestId, req.fromUsername)
  pendingRequests.value = pendingRequests.value.filter(r => r.requestId !== req.requestId)
}

function rejectRequest(req) {
  chatWs.rejectFriendRequest(req.requestId)
  pendingRequests.value = pendingRequests.value.filter(r => r.requestId !== req.requestId)
}

function viewFriendInfo(fr) {
  if (fr) emit('view-user-info', fr.username, fr.displayName)
}

function removeFriend(fr) {
  if (fr && confirm(`确定要删除好友 ${fr.displayName || fr.username} 吗？`)) {
    chatWs.removeFriend(fr.username)
  }
}

function onContextMenu(e, fr) {
  contextMenu.show = true
  contextMenu.x = e.clientX
  contextMenu.y = e.clientY
  contextMenu.friend = fr
}

function closeMenu() {
  contextMenu.show = false
}

// 监听好友申请数据
function onPendingData(requests) {
  pendingRequests.value = requests
}

onMounted(() => {
  document.addEventListener('click', closeMenu)
  chatStore.onEvent('friendPending', onPendingData)
  chatStore.onEvent('userSearchResults', onSearchResults)
  refreshFriends()
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  chatStore.offEvent('friendPending', onPendingData)
  chatStore.offEvent('userSearchResults', onSearchResults)
})
</script>

<style scoped>
.friend-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.friend-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-light);
}
.friend-list-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.friend-actions-row {
  display: flex;
  gap: 2px;
}
.friend-items {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px;
}
.friend-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}
.friend-item:hover {
  background: var(--bg-hover);
}
.friend-item.active {
  background: var(--bg-active);
}
.friend-avatar-wrap {
  flex-shrink: 0;
  opacity: 0.5;
}
.friend-avatar-wrap.online {
  opacity: 1;
}
.search-avatar-wrap {
  flex-shrink: 0;
}
.friend-info {
  flex: 1;
  min-width: 0;
}
.friend-name {
  font-size: 14px;
  color: var(--text-primary);
}
.friend-status {
  font-size: 11px;
  color: var(--text-tertiary);
}
.friend-empty {
  text-align: center;
  padding: 40px 16px;
  color: var(--text-tertiary);
  font-size: 13px;
}

/* 好友申请 */
.pending-empty {
  text-align: center;
  padding: 20px;
  color: var(--text-tertiary);
}
.pending-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

/* 搜索相关 */
.search-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.search-input {
  flex: 1;
}
.search-results {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  margin-bottom: 12px;
}
.search-hint {
  text-align: center;
  padding: 24px 16px;
  color: var(--text-tertiary);
  font-size: 13px;
}
.search-result-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-light);
}
.search-result-item:last-child {
  border-bottom: none;
}

.search-user-info {
  flex: 1;
  min-width: 0;
}
.search-display-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.search-username {
  font-size: 11px;
  color: var(--text-tertiary);
}
.search-user-status {
  flex-shrink: 0;
  margin-right: 4px;
}
.online-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4caf50;
}
.btn-sm {
  padding: 4px 10px;
  font-size: 12px;
}
.danger {
  color: #e53935;
}
.badge {
  background: #e53935;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  padding: 0 5px;
  flex-shrink: 0;
}
.btn-icon {
  position: relative;
}
.req-dot {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #e53935;
}
</style>
