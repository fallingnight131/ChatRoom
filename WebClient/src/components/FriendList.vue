<template>
  <div class="friend-list">
    <div class="friend-list-header">
      <span class="friend-list-title">好友列表</span>
      <div class="friend-actions-row">
        <button class="btn-icon" @click="showAddFriend = true" title="添加好友">➕</button>
        <button class="btn-icon" @click="showPending" title="好友申请">📩</button>
        <button class="btn-icon" @click="refreshFriends" title="刷新">🔄</button>
      </div>
    </div>

    <div class="friend-items">
      <div v-for="fr in chatStore.friends" :key="fr.username"
           class="friend-item"
           :class="{ active: chatStore.isFriendChat && chatStore.currentFriendUsername === fr.username }"
           @click="selectFriend(fr)"
           @contextmenu.prevent="onContextMenu($event, fr)">
        <div class="friend-avatar" :class="{ online: fr.isOnline }">👤</div>
        <div class="friend-info">
          <div class="friend-name text-ellipsis">{{ fr.displayName || fr.username }}</div>
          <div class="friend-status">{{ fr.isOnline ? '在线' : '离线' }}</div>
        </div>
      </div>
      <div v-if="chatStore.friends.length === 0" class="friend-empty">
        暂无好友，点击 ➕ 添加
      </div>
    </div>

    <!-- 添加好友弹窗 -->
    <div class="modal-overlay" v-if="showAddFriend" @click.self="showAddFriend = false">
      <div class="modal">
        <div class="modal-title">添加好友</div>
        <div class="input-group">
          <label>用户名</label>
          <input class="input" v-model="addFriendUsername" placeholder="输入对方用户名"
                 @keyup.enter="sendFriendRequest" />
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showAddFriend = false">取消</button>
          <button class="btn btn-primary" @click="sendFriendRequest">发送请求</button>
        </div>
      </div>
    </div>

    <!-- 好友申请弹窗 -->
    <div class="modal-overlay" v-if="showPendingDialog" @click.self="showPendingDialog = false">
      <div class="modal" style="max-width: 400px;">
        <div class="modal-title">待处理的好友申请</div>
        <div v-if="pendingRequests.length === 0" class="pending-empty">暂无待处理的好友申请</div>
        <div v-for="req in pendingRequests" :key="req.id" class="pending-item">
          <div class="pending-info">
            <span class="pending-name">{{ req.fromDisplayName || req.fromUsername }}</span>
            <span class="pending-uid">({{ req.fromUsername }})</span>
          </div>
          <div class="pending-actions">
            <button class="btn btn-primary btn-sm" @click="acceptRequest(req)">接受</button>
            <button class="btn btn-secondary btn-sm" @click="rejectRequest(req)">拒绝</button>
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
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { chatWs } from '../services/websocket'

const chatStore = useChatStore()

const emit = defineEmits(['friend-selected', 'view-user-info'])

const showAddFriend = ref(false)
const showPendingDialog = ref(false)
const addFriendUsername = ref('')
const pendingRequests = ref([])

const contextMenu = reactive({ show: false, x: 0, y: 0, friend: null })

function selectFriend(fr) {
  chatStore.setCurrentFriend(fr.username)
  emit('friend-selected')
}

function sendFriendRequest() {
  if (!addFriendUsername.value.trim()) return
  chatWs.sendFriendRequest(addFriendUsername.value.trim())
  showAddFriend.value = false
  addFriendUsername.value = ''
}

function showPending() {
  chatWs.requestFriendPending()
  showPendingDialog.value = true
}

function refreshFriends() {
  chatWs.requestFriendList()
}

function acceptRequest(req) {
  chatWs.acceptFriendRequest(req.id, req.fromUsername)
  pendingRequests.value = pendingRequests.value.filter(r => r.id !== req.id)
}

function rejectRequest(req) {
  chatWs.rejectFriendRequest(req.id)
  pendingRequests.value = pendingRequests.value.filter(r => r.id !== req.id)
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
  refreshFriends()
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  chatStore.offEvent('friendPending', onPendingData)
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
.friend-avatar {
  font-size: 20px;
  flex-shrink: 0;
  opacity: 0.5;
}
.friend-avatar.online {
  opacity: 1;
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
.pending-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-light);
}
.pending-info {
  flex: 1;
}
.pending-name {
  font-weight: 600;
}
.pending-uid {
  color: var(--text-tertiary);
  font-size: 12px;
  margin-left: 4px;
}
.pending-actions {
  display: flex;
  gap: 6px;
}
.btn-sm {
  padding: 4px 10px;
  font-size: 12px;
}
.danger {
  color: #e53935;
}
</style>
