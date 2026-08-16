<template>
  <div class="room-list">
    <div class="room-list-header">
      <span class="room-list-title">房间列表</span>
      <div class="room-actions-row">
        <button class="btn-icon" type="button" aria-label="搜索房间" aria-haspopup="dialog"
                :aria-expanded="showSearch" @click="showSearch = true" title="搜索房间">🔍</button>
        <button class="btn-icon" type="button" aria-label="创建房间" aria-haspopup="dialog"
                :aria-expanded="showCreate" @click="showCreate = true" title="创建房间">➕</button>
        <button class="btn-icon" type="button" aria-label="刷新房间列表" @click="refreshRooms" title="刷新">🔄</button>
      </div>
    </div>

    <div class="room-items">
      <button v-for="room in chatStore.rooms" :key="room.roomId" type="button"
           class="room-item"
           :class="{ active: room.roomId === chatStore.currentRoomId }"
           :aria-current="room.roomId === chatStore.currentRoomId ? 'true' : undefined"
           @click="selectRoom(room.roomId)"
           @keydown="openRoomMenuFromKeyboard($event, room)"
           @contextmenu.prevent="openRoomMenuFromPointer($event, room)">
        <span class="room-avatar-wrap">
          <img v-if="getRoomAvatarSrc(room.roomId)" :src="getRoomAvatarSrc(room.roomId)" class="avatar avatar-sm"
               :alt="`${room.roomName} 的房间头像`" />
          <span v-else class="avatar avatar-sm avatar-placeholder" aria-hidden="true"
                :style="{ background: hashColor(room.roomId) }">
            {{ room.roomName.charAt(0) }}
          </span>
        </span>
        <span class="room-info">
          <span class="room-name text-ellipsis">{{ room.roomName }}</span>
        </span>
        <span v-if="room.unread > 0" class="badge">{{ room.unread > 99 ? '99+' : room.unread }}</span>
      </button>
      <div v-if="chatStore.rooms.length === 0" class="room-empty">
        暂无房间，点击 ➕ 创建或 🔍 搜索
      </div>
    </div>

    <!-- 搜索房间弹窗 -->
    <div class="modal-overlay" v-if="showSearch" @click.self="closeSearchDialog">
      <div ref="searchDialogRef" class="modal room-dialog" role="dialog" aria-modal="true"
           aria-labelledby="room-search-title" tabindex="-1" @keydown="onSearchKeydown">
        <div id="room-search-title" class="modal-title">搜索房间</div>
        <form class="search-row" @submit.prevent="doSearch">
          <label class="visually-hidden" for="room-search-keyword">房间名称或 ID</label>
          <input id="room-search-keyword" class="input search-input" v-model="searchKeyword"
                 placeholder="输入房间名称或ID搜索" />
          <button class="btn btn-primary" type="submit" :disabled="searching || !searchKeyword.trim()">
            {{ searching ? '搜索中…' : '搜索' }}
          </button>
        </form>
        <div class="search-results" aria-live="polite">
          <div v-if="searchResults === null" class="search-hint">输入关键词后点击搜索</div>
          <div v-else-if="searchResults.length === 0" class="search-hint">未找到匹配的房间</div>
          <div v-for="r in searchResults" :key="r.roomId" class="search-result-item">
            <div class="search-avatar-wrap">
              <img v-if="getRoomAvatarSrc(r.roomId)" :src="getRoomAvatarSrc(r.roomId)" class="avatar avatar-sm"
                   :alt="`${r.roomName} 的房间头像`" />
              <div v-else class="avatar avatar-sm avatar-placeholder" :style="{ background: hashColor(r.roomId) }">
                {{ r.roomName.charAt(0) }}
              </div>
            </div>
            <div class="search-room-info">
              <div class="search-display-name text-ellipsis">{{ r.roomName }}</div>
              <div class="search-room-id">ID: {{ r.roomId }}  ·  {{ r.memberCount }} 人</div>
            </div>
            <button v-if="isRoomJoined(r.roomId)" class="btn btn-secondary btn-sm" type="button" disabled>已加入</button>
            <button v-else class="btn btn-primary btn-sm" type="button" @click="joinSearchedRoom(r.roomId)">加入</button>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" type="button" @click="closeSearchDialog">关闭</button>
        </div>
      </div>
    </div>

    <!-- 创建房间弹窗 -->
    <div class="modal-overlay" v-if="showCreate" @click.self="closeCreateDialog">
      <form ref="createDialogRef" class="modal room-dialog" role="dialog" aria-modal="true"
            aria-labelledby="room-create-title" tabindex="-1" @keydown="onCreateKeydown"
            @submit.prevent="createRoom">
        <div id="room-create-title" class="modal-title">创建房间</div>
        <div class="input-group">
          <label for="new-room-name">房间名称</label>
          <input id="new-room-name" class="input" v-model="newRoomName"
                 placeholder="输入房间名称" required />
        </div>
        <div class="input-group">
          <label for="new-room-password">密码（可选）</label>
          <input id="new-room-password" class="input" v-model="newRoomPassword" type="password"
                 placeholder="留空则无密码" autocomplete="off" />
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" type="button" @click="closeCreateDialog">取消</button>
          <button class="btn btn-primary" type="submit" :disabled="!newRoomName.trim()">创建</button>
        </div>
      </form>
    </div>

    <!-- 右键菜单 -->
    <div v-if="roomMenu.show" ref="roomMenuRef" class="context-menu" role="menu"
         aria-label="房间操作" :style="{ left: roomMenu.x + 'px', top: roomMenu.y + 'px' }"
         @keydown="onRoomMenuKeydown">
      <button class="context-menu-item" type="button" role="menuitem"
              @click="openContextRoomSettings">房间设置</button>
      <button v-if="canManageRoom(roomMenu.item)" class="context-menu-item" type="button"
              role="menuitem" @click="openContextRoomFiles">文件管理</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '../stores/chat'
import { chatWs } from '../services/websocket'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { useKeyboardContextMenu } from '../ui/useKeyboardContextMenu'

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
const {
  dialogRef: searchDialogRef,
  closeDialog: closeSearchDialog,
  onDialogKeydown: onSearchKeydown,
} = useModalKeyboardBoundary({
  onClose: closeSearch,
  initialFocusSelector: '#room-search-keyword',
  active: showSearch,
})
const {
  dialogRef: createDialogRef,
  closeDialog: closeCreateDialog,
  onDialogKeydown: onCreateKeydown,
} = useModalKeyboardBoundary({
  onClose: closeCreate,
  initialFocusSelector: '#new-room-name',
  active: showCreate,
})

const {
  menu: roomMenu,
  menuRef: roomMenuRef,
  openFromPointer: openRoomMenuFromPointer,
  openFromKeyboard: openRoomMenuFromKeyboard,
  close: closeRoomMenu,
  dismiss: dismissRoomMenu,
  onKeydown: onRoomMenuKeydown,
} = useKeyboardContextMenu()

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
  const roomName = newRoomName.value.trim()
  if (!roomName) return
  const password = newRoomPassword.value
  showCreate.value = false
  newRoomName.value = ''
  newRoomPassword.value = ''
  chatWs.createRoom(roomName, password)
}

function closeCreate() {
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
  if (room && canManageRoom(room)) {
    chatStore.setCurrentRoom(room.roomId)
    emit('open-room-files')
  }
}

function canManageRoom(room) {
  return !!(room && room.isAdmin)
}

function openContextRoomSettings() {
  const room = roomMenu.item
  closeRoomMenu(true)
  openRoomSettings(room)
}

function openContextRoomFiles() {
  const room = roomMenu.item
  closeRoomMenu(true)
  openRoomFiles(room)
}

// 搜索
function doSearch() {
  if (searching.value) return
  const kw = searchKeyword.value.trim()
  if (!kw) return
  searching.value = true
  chatWs.searchRooms(kw)
}

function onSearchResults(rooms) {
  if (!showSearch.value) return
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
  searching.value = false
}

function joinSearchedRoom(roomId) {
  chatWs.joinRoom(roomId)
}

function isRoomJoined(roomId) {
  return chatStore.rooms.some(r => r.roomId === roomId)
}

onMounted(() => {
  document.addEventListener('click', dismissRoomMenu)
  chatStore.onEvent('roomSearchResults', onSearchResults)
})
onUnmounted(() => {
  document.removeEventListener('click', dismissRoomMenu)
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
.room-dialog {
  width: min(420px, calc(100vw - 32px));
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
  width: 100%;
  border: 0;
  text-align: left;
  background: transparent;
  color: inherit;
}
.room-item:hover {
  background: var(--bg-hover);
}
.room-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
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
  display: block;
  font-size: 14px;
  color: var(--text-primary);
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
