<template>
  <div class="friend-list">
    <div class="friend-list-header">
      <span class="friend-list-title">好友列表</span>
      <div class="friend-actions-row">
        <button class="btn-icon" type="button" aria-label="搜索好友" aria-haspopup="dialog"
                :aria-expanded="showAddFriend" @click="showAddFriend = true" title="搜索好友">🔍</button>
        <button class="btn-icon" type="button" aria-haspopup="dialog" :aria-expanded="showPendingDialog"
                :aria-label="chatStore.hasPendingFriendReq ? '好友申请（有待处理申请）' : '好友申请'"
                @click="showPending" title="好友申请">
          📩
          <span v-if="chatStore.hasPendingFriendReq" class="req-dot" aria-hidden="true"></span>
        </button>
        <button class="btn-icon" type="button" aria-label="刷新好友列表" @click="refreshFriends" title="刷新">🔄</button>
      </div>
    </div>

    <div class="friend-items">
      <button v-for="fr in chatStore.friends" :key="fr.username" type="button"
           class="friend-item"
           :class="{ active: chatStore.isFriendChat && chatStore.currentFriendUsername === fr.username }"
           :aria-current="chatStore.isFriendChat && chatStore.currentFriendUsername === fr.username ? 'true' : undefined"
           @click="selectFriend(fr)"
           @keydown="onFriendKeydown($event, fr)"
           @contextmenu.prevent="onContextMenu($event, fr)">
        <span class="friend-avatar-wrap" :class="{ online: fr.isOnline }">
          <img v-if="getAvatarSrc(fr.username)" :src="getAvatarSrc(fr.username)" class="avatar avatar-sm"
               :alt="`${fr.displayName || fr.username} 的头像`" />
          <span v-else class="avatar avatar-sm avatar-placeholder" aria-hidden="true"
                :style="{ background: hashColor(fr.username) }">
            {{ (fr.displayName || fr.username).charAt(0) }}
          </span>
        </span>
        <span class="friend-info">
          <span class="friend-name text-ellipsis">{{ fr.displayName || fr.username }}</span>
          <span class="friend-status">{{ fr.isOnline ? '在线' : '离线' }}</span>
        </span>
        <span v-if="(chatStore.friendUnread[fr.username] || 0) > 0" class="badge">{{ chatStore.friendUnread[fr.username] > 99 ? '99+' : chatStore.friendUnread[fr.username] }}</span>
      </button>
      <div v-if="chatStore.friends.length === 0" class="friend-empty">
        暂无好友，点击 🔍 搜索
      </div>
    </div>

    <!-- 添加好友弹窗 -->
    <div class="modal-overlay" v-if="showAddFriend" @click.self="closeAddFriend">
      <div ref="addFriendDialogRef" class="modal friend-dialog" role="dialog" aria-modal="true"
           aria-labelledby="add-friend-title" tabindex="-1" @keydown="onAddFriendKeydown">
        <div id="add-friend-title" class="modal-title">搜索好友</div>
        <form class="search-row" @submit.prevent="doSearch">
          <label class="visually-hidden" for="friend-search-keyword">用户 ID 或昵称</label>
          <input id="friend-search-keyword" class="input search-input" v-model="searchKeyword"
                 placeholder="输入用户ID或昵称搜索" />
          <button class="btn btn-primary" type="submit" :disabled="searching || !searchKeyword.trim()">
            {{ searching ? '搜索中…' : '搜索' }}
          </button>
        </form>
        <div class="search-results" aria-live="polite">
          <div v-if="searchResults === null" class="search-hint">输入关键词后点击搜索</div>
          <div v-else-if="searchResults.length === 0" class="search-hint">未找到匹配的用户</div>
          <div v-for="u in searchResults" :key="u.username" class="search-result-item">
            <div class="search-avatar-wrap">
              <img v-if="getAvatarSrc(u.username)" :src="getAvatarSrc(u.username)" class="avatar avatar-sm"
                   :alt="`${u.displayName || u.username} 的头像`" />
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
            <button v-if="isFriend(u.username)" class="btn btn-secondary btn-sm" type="button" disabled>已添加</button>
            <button v-else class="btn btn-primary btn-sm" type="button" @click="sendRequestTo(u.username)">发送申请</button>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" type="button" @click="closeAddFriendDialog">关闭</button>
        </div>
      </div>
    </div>

    <!-- 好友申请弹窗 -->
    <div class="modal-overlay" v-if="showPendingDialog" @click.self="closePendingDialog">
      <div ref="pendingDialogRef" class="modal friend-dialog" role="dialog" aria-modal="true"
           aria-labelledby="pending-friend-title" tabindex="-1" @keydown="onPendingKeydown">
        <div id="pending-friend-title" class="modal-title">待处理的好友申请</div>
        <div class="search-results" aria-live="polite">
          <div v-if="pendingRequests.length === 0" class="search-hint">暂无待处理的好友申请</div>
          <div v-for="req in pendingRequests" :key="req.requestId" class="search-result-item">
            <div class="search-avatar-wrap">
              <img v-if="getAvatarSrc(req.fromUsername)" :src="getAvatarSrc(req.fromUsername)" class="avatar avatar-sm"
                   :alt="`${req.fromDisplayName || req.fromUsername} 的头像`" />
              <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(req.fromUsername) }">
                {{ (req.fromDisplayName || req.fromUsername).charAt(0) }}
              </div>
            </div>
            <div class="search-user-info">
              <div class="search-display-name text-ellipsis">{{ req.fromDisplayName || req.fromUsername }}</div>
              <div class="search-username">ID: {{ req.fromUsername }}</div>
            </div>
            <div class="pending-actions">
              <button class="btn btn-primary btn-sm" type="button" @click="acceptRequest(req)">接受</button>
              <button class="btn btn-secondary btn-sm" type="button" @click="rejectRequest(req)">拒绝</button>
            </div>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" type="button" @click="closePendingDialog">关闭</button>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div v-if="contextMenu.show" ref="contextMenuRef" class="context-menu" role="menu"
         aria-label="好友操作"
         :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
         @keydown="onContextMenuKeydown">
      <button class="context-menu-item" type="button" role="menuitem"
              @click="viewContextFriendInfo">查看信息</button>
      <button v-if="canRemoveFriend(contextMenu.friend)" class="context-menu-item danger"
              type="button" role="menuitem" @click="removeContextFriend">删除好友</button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, inject, nextTick, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { useUserStore } from '../stores/user'
import { chatWs } from '../services/websocket'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'

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
  userStore.requestAvatarIfAllowed(username)
  return ''
}

const emit = defineEmits(['friend-selected', 'view-user-info'])

const showAddFriend = ref(false)
const showPendingDialog = ref(false)
const searchKeyword = ref('')
const searchResults = ref(null)
const searching = ref(false)
const pendingRequests = ref([])
const {
  dialogRef: addFriendDialogRef,
  closeDialog: closeAddFriendDialog,
  onDialogKeydown: onAddFriendKeydown,
} = useModalKeyboardBoundary({
  onClose: closeAddFriend,
  initialFocusSelector: '#friend-search-keyword',
  active: showAddFriend,
})
const {
  dialogRef: pendingDialogRef,
  closeDialog: closePendingDialog,
  onDialogKeydown: onPendingKeydown,
} = useModalKeyboardBoundary({
  onClose: () => { showPendingDialog.value = false },
  active: showPendingDialog,
})

const contextMenu = reactive({ show: false, x: 0, y: 0, friend: null })
const contextMenuRef = ref(null)
let contextMenuInvoker = null

function selectFriend(fr) {
  chatStore.setCurrentFriend(fr.username)
  emit('friend-selected')
}

function doSearch() {
  if (searching.value) return
  const kw = searchKeyword.value.trim()
  if (!kw) return
  searching.value = true
  chatWs.searchUsers(kw)
}

function onSearchResults(users) {
  if (!showAddFriend.value) return
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
  if (!canRemoveFriend(fr)) return
  if (fr && confirm(`确定要删除好友 ${fr.displayName || fr.username} 吗？`)) {
    chatWs.removeFriend(fr.username)
  }
}

function canRemoveFriend(fr) {
  return !!(fr && fr.username && fr.username !== userStore.username)
}

function openContextMenu(fr, invoker, x, y) {
  contextMenu.show = true
  contextMenu.x = x
  contextMenu.y = y
  contextMenu.friend = fr
  contextMenuInvoker = invoker
  nextTick(() => contextMenuRef.value?.querySelector('[role="menuitem"]')?.focus())
}

function onContextMenu(e, fr) {
  openContextMenu(fr, e.currentTarget, e.clientX, e.clientY)
}

function onFriendKeydown(event, fr) {
  if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return
  event.preventDefault()
  const rect = event.currentTarget.getBoundingClientRect()
  openContextMenu(fr, event.currentTarget, rect.left + 12, rect.top + 12)
}

function closeMenu(restoreFocus = false) {
  contextMenu.show = false
  contextMenu.friend = null
  const invoker = contextMenuInvoker
  contextMenuInvoker = null
  if (restoreFocus) nextTick(() => invoker?.focus())
}

function dismissMenu() {
  closeMenu(false)
}

function viewContextFriendInfo() {
  const friend = contextMenu.friend
  closeMenu(true)
  viewFriendInfo(friend)
}

function removeContextFriend() {
  const friend = contextMenu.friend
  closeMenu(true)
  removeFriend(friend)
}

function onContextMenuKeydown(event) {
  const items = [...(contextMenuRef.value?.querySelectorAll('[role="menuitem"]') || [])]
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMenu(true)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key) || items.length === 0) return
  event.preventDefault()
  const current = items.indexOf(document.activeElement)
  let next = 0
  if (event.key === 'End') next = items.length - 1
  else if (event.key === 'ArrowUp') next = current <= 0 ? items.length - 1 : current - 1
  else if (event.key === 'ArrowDown') next = current < 0 || current === items.length - 1 ? 0 : current + 1
  items[next]?.focus()
}

// 监听好友申请数据
function onPendingData(requests) {
  pendingRequests.value = requests
}

onMounted(() => {
  document.addEventListener('click', dismissMenu)
  chatStore.onEvent('friendPending', onPendingData)
  chatStore.onEvent('userSearchResults', onSearchResults)
  refreshFriends()
})
onUnmounted(() => {
  document.removeEventListener('click', dismissMenu)
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
.friend-dialog {
  width: min(420px, calc(100vw - 32px));
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
  width: 100%;
  border: 0;
  text-align: left;
  background: transparent;
  color: inherit;
}
.friend-item:hover {
  background: var(--bg-hover);
}
.friend-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
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
  display: flex;
  flex-direction: column;
}
.context-menu-item {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  font-family: inherit;
}
.context-menu-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
  background: var(--bg-hover);
}
.friend-name {
  display: block;
  font-size: 14px;
  color: var(--text-primary);
}
.friend-status {
  display: block;
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
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  padding: 0 1px 3px;
  flex-shrink: 0;
  box-sizing: border-box;
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
