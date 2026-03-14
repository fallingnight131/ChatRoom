<template>
  <div class="room-list">
    <div class="room-list-header">
      <span class="room-list-title">房间列表</span>
      <div class="room-actions-row">
        <button class="btn-icon" @click="showSearch = true" title="搜索房间">🔍</button>
        <button class="btn-icon" @click="showCreate = true" title="创建房间">➕</button>
        <button class="btn-icon" @click="refreshRooms" title="刷新">🔄</button>
      </div>
    </div>

    <div class="room-items">
      <div v-for="room in chatStore.rooms" :key="room.roomId"
           class="room-item"
           :class="{ active: room.roomId === chatStore.currentRoomId }"
           @click="selectRoom(room.roomId)"
           @contextmenu.prevent="onContextMenu($event, room)">
        <div class="room-avatar-wrap">
          <img v-if="getRoomAvatarSrc(room.roomId)" :src="getRoomAvatarSrc(room.roomId)" class="avatar avatar-sm" />
          <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(room.roomId) }">
            {{ room.roomName.charAt(0) }}
          </div>
        </div>
        <div class="room-info">
          <div class="room-name text-ellipsis">{{ room.roomName }}</div>
        </div>
        <span v-if="room.unread > 0" class="badge">{{ room.unread > 99 ? '99+' : room.unread }}</span>
      </div>
      <div v-if="chatStore.rooms.length === 0" class="room-empty">
        暂无房间，点击 ➕ 创建或 🔍 搜索
      </div>
    </div>

    <!-- 搜索房间弹窗 -->
    <div class="modal-overlay" v-if="showSearch" @click.self="showSearch = false">
      <div class="modal" style="max-width: 420px;">
        <div class="modal-title">搜索房间</div>
        <div class="search-row">
          <input class="input search-input" v-model="searchKeyword"
                 placeholder="输入房间名称或ID搜索"
                 @keyup.enter="doSearch" />
          <button class="btn btn-primary" @click="doSearch" :disabled="searching">
            {{ searching ? '搜索中…' : '搜索' }}
          </button>
        </div>
        <div class="search-results">
          <div v-if="searchResults === null" class="search-hint">输入关键词后点击搜索</div>
          <div v-else-if="searchResults.length === 0" class="search-hint">未找到匹配的房间</div>
          <div v-for="r in searchResults" :key="r.roomId" class="search-result-item">
            <div class="search-avatar-wrap">
              <img v-if="getRoomAvatarSrc(r.roomId)" :src="getRoomAvatarSrc(r.roomId)" class="avatar avatar-sm" />
              <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(r.roomId) }">
                {{ r.roomName.charAt(0) }}
              </div>
            </div>
            <div class="search-room-info">
              <div class="search-display-name text-ellipsis">{{ r.roomName }}</div>
              <div class="search-room-id">ID: {{ r.roomId }}  ·  {{ r.memberCount }} 人</div>
            </div>
            <button v-if="isRoomJoined(r.roomId)" class="btn btn-secondary btn-sm" disabled>已加入</button>
            <button v-else class="btn btn-primary btn-sm" @click="joinSearchedRoom(r.roomId)">加入</button>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="closeSearch">关闭</button>
        </div>
      </div>
    </div>

    <!-- 创建房间弹窗 -->
    <div class="modal-overlay" v-if="showCreate" @click.self="showCreate = false">
      <div class="modal">
        <div class="modal-title">创建房间</div>
        <div class="input-group">
          <label>房间名称</label>
          <input class="input" v-model="newRoomName" placeholder="输入房间名称"
                 @keyup.enter="createRoom" />
        </div>
        <div class="input-group">
          <label>密码（可选）</label>
          <input class="input" v-model="newRoomPassword" type="password" placeholder="留空则无密码" />
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" @click="createRoom">创建</button>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div v-if="contextMenu.show" class="context-menu"
         :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
         @click="contextMenu.show = false">
      <div class="context-menu-item" @click="openRoomSettings(contextMenu.room)">房间设置</div>
      <div class="context-menu-item" @click="openRoomFiles(contextMenu.room)">文件管理</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, reactive, watch } from 'vue'
import { useChatStore } from '../stores/chat'
import { chatWs } from '../services/websocket'

const chatStore = useChatStore()

const emit = defineEmits(['room-selected', 'open-room-settings', 'open-room-files'])

const showCreate = ref(false)
const showSearch = ref(false)
const newRoomName = ref('')
const newRoomPassword = ref('')

// 搜索相关
const searchKeyword = ref('')
const searchResults = ref(null)
const searching = ref(false)

const contextMenu = reactive({ show: false, x: 0, y: 0, room: null })

function hashColor(id) {
  let hash = 0
  const s = String(id)
  for (let i = 0; i < s.length; i++) hash = s.charCodeAt(i) + ((hash << 5) - hash)
  const h = Math.abs(hash) % 360
  return `hsl(${h}, 55%, 50%)`
}

function getRoomAvatarSrc(roomId) {
  const src = chatStore.getRoomAvatarSrc(roomId)
  if (!src) chatStore.fetchRoomAvatar(roomId)
  return src
}

function selectRoom(roomId) {
  chatStore.setCurrentRoom(roomId)
  emit('room-selected')
}

function createRoom() {
  if (!newRoomName.value.trim()) return
  chatWs.createRoom(newRoomName.value.trim(), newRoomPassword.value)
  showCreate.value = false
  newRoomName.value = ''
  newRoomPassword.value = ''
}

function refreshRooms() {
  chatWs.requestRoomList()
}

function openRoomSettings(room) {
  if (room) {
    chatStore.setCurrentRoom(room.roomId)
    emit('open-room-settings')
  }
}

function openRoomFiles(room) {
  if (room) {
    chatStore.setCurrentRoom(room.roomId)
    emit('open-room-files')
  }
}

function onContextMenu(e, room) {
  contextMenu.show = true
  contextMenu.x = e.clientX
  contextMenu.y = e.clientY
  contextMenu.room = room
}

function closeMenu() {
  contextMenu.show = false
}

// 搜索
function doSearch() {
  const kw = searchKeyword.value.trim()
  if (!kw) return
  searching.value = true
  chatWs.searchRooms(kw)
}

function onSearchResults(rooms) {
  searching.value = false
  searchResults.value = rooms
  // 获取搜索结果中的头像
  for (const r of rooms) {
    chatStore.fetchRoomAvatar(r.roomId)
  }
}

function closeSearch() {
  showSearch.value = false
  searchKeyword.value = ''
  searchResults.value = null
}

function joinSearchedRoom(roomId) {
  chatWs.joinRoom(roomId)
}

function isRoomJoined(roomId) {
  return chatStore.rooms.some(r => r.roomId === roomId)
}

onMounted(() => {
  document.addEventListener('click', closeMenu)
  chatStore.onEvent('roomSearchResults', onSearchResults)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  chatStore.offEvent('roomSearchResults', onSearchResults)
})
</script>

<style scoped>
.room-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.room-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-light);
}
.room-list-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.room-actions-row {
  display: flex;
  gap: 2px;
}
.room-items {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px;
}
.room-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}
.room-item:hover {
  background: var(--bg-hover);
}
.room-item.active {
  background: var(--bg-active);
}
.room-avatar-wrap {
  flex-shrink: 0;
}
.room-info {
  flex: 1;
  min-width: 0;
}
.room-name {
  font-size: 14px;
  color: var(--text-primary);
}
.room-empty {
  text-align: center;
  padding: 40px 16px;
  color: var(--text-tertiary);
  font-size: 13px;
}

/* 搜索 */
.search-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.search-input {
  flex: 1;
}
.search-results {
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  margin-bottom: 12px;
}
.search-hint {
  text-align: center;
  padding: 24px;
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
.search-avatar-wrap {
  flex-shrink: 0;
}
.search-room-info {
  flex: 1;
  min-width: 0;
}
.search-display-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
}
.search-room-id {
  font-size: 12px;
  color: var(--text-secondary);
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
</style>
