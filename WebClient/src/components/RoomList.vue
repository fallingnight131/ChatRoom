<template>
  <div class="room-list">
    <div class="room-list-header">
      <span class="room-list-title">房间列表</span>
      <div class="room-actions-row">
        <button class="btn-icon" @click="showJoin = true" title="加入房间">🔗</button>
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
        <div class="room-icon">🏠</div>
        <div class="room-info">
          <div class="room-name text-ellipsis">{{ room.roomName }}</div>
        </div>
        <span v-if="room.unread > 0" class="badge">{{ room.unread > 99 ? '99+' : room.unread }}</span>
      </div>
      <div v-if="chatStore.rooms.length === 0" class="room-empty">
        暂无房间，点击 ➕ 创建
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

    <!-- 加入房间弹窗 -->
    <div class="modal-overlay" v-if="showJoin" @click.self="showJoin = false">
      <div class="modal">
        <div class="modal-title">加入房间</div>
        <div class="input-group">
          <label>房间ID</label>
          <input class="input" v-model.number="joinRoomId" type="number" placeholder="输入房间ID"
                 @keyup.enter="joinRoom" />
        </div>
        <div class="modal-actions">
          <button class="btn btn-secondary" @click="showJoin = false">取消</button>
          <button class="btn btn-primary" @click="joinRoom">加入</button>
        </div>
      </div>
    </div>

    <!-- 右键菜单 -->
    <div v-if="contextMenu.show" class="context-menu"
         :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
         @click="contextMenu.show = false">
      <div class="context-menu-item" @click="openRoomSettings(contextMenu.room)">房间设置</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, reactive } from 'vue'
import { useChatStore } from '../stores/chat'
import { chatWs } from '../services/websocket'

const chatStore = useChatStore()

const emit = defineEmits(['room-selected', 'open-room-settings'])

const showCreate = ref(false)
const showJoin = ref(false)
const newRoomName = ref('')
const newRoomPassword = ref('')
const joinRoomId = ref('')

const contextMenu = reactive({ show: false, x: 0, y: 0, room: null })

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

function joinRoom() {
  if (!joinRoomId.value) return
  chatWs.joinRoom(joinRoomId.value)
  showJoin.value = false
  joinRoomId.value = ''
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

function onContextMenu(e, room) {
  contextMenu.show = true
  contextMenu.x = e.clientX
  contextMenu.y = e.clientY
  contextMenu.room = room
}

function closeMenu() {
  contextMenu.show = false
}

onMounted(() => {
  document.addEventListener('click', closeMenu)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
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
.room-icon {
  font-size: 20px;
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
</style>
