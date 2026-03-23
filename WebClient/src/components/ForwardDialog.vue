<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal forward-modal">
      <div class="modal-title">转发到其他会话</div>

      <div class="tab-bar">
        <button class="tab-btn" :class="{ active: activeTab === 'friends' }" @click="activeTab = 'friends'">
          好友
          <span class="tab-count">{{ friendCandidates.length }}</span>
        </button>
        <button class="tab-btn" :class="{ active: activeTab === 'rooms' }" @click="activeTab = 'rooms'">
          房间
          <span class="tab-count">{{ roomCandidates.length }}</span>
        </button>
      </div>

      <div class="search-row">
        <input
          class="input"
          v-model.trim="searchKeyword"
          :placeholder="activeTab === 'friends' ? '搜索好友用户名或昵称' : '搜索房间名或房间ID'"
        />
      </div>

      <div class="toolbar-row">
        <label class="check-all">
          <input type="checkbox" :checked="allChecked" @change="toggleAll($event)" />
          <span>全选</span>
        </label>
        <span class="selected-count">已选 {{ selectedCount }} 项</span>
      </div>

      <div class="list-wrap">
        <div v-if="activeTab === 'friends'">
          <label v-for="f in filteredFriendCandidates" :key="f.username" class="list-item">
            <input type="checkbox" :checked="selectedFriends.has(f.username)" @change="toggleFriend(f.username, $event.target.checked)" />
            <span class="item-main-row">
              <span class="item-main">{{ f.displayName || f.username }}</span>
              <span class="item-badge" :class="f.isOnline ? 'online' : 'offline'">{{ f.isOnline ? '在线' : '离线' }}</span>
              <span v-if="friendUnreadMap[f.username] > 0" class="item-unread">{{ friendUnreadMap[f.username] }}</span>
            </span>
            <span class="item-sub">@{{ f.username }}</span>
          </label>
          <div v-if="filteredFriendCandidates.length === 0" class="empty">暂无可选好友</div>
        </div>

        <div v-else>
          <label v-for="r in filteredRoomCandidates" :key="r.roomId" class="list-item">
            <input type="checkbox" :checked="selectedRooms.has(r.roomId)" @change="toggleRoom(r.roomId, $event.target.checked)" />
            <span class="item-main-row">
              <span class="item-main">{{ r.roomName }}</span>
              <span v-if="Number(r.unread || 0) > 0" class="item-unread">{{ r.unread }}</span>
            </span>
            <span class="item-sub">ID: {{ r.roomId }}</span>
          </label>
          <div v-if="filteredRoomCandidates.length === 0" class="empty">暂无可选房间</div>
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn btn-secondary" @click="$emit('close')">取消</button>
        <button class="btn btn-primary" :disabled="selectedCount === 0 || submitting" @click="submitForward">
          {{ submitting ? '转发中...' : '确认转发' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useChatStore } from '../stores/chat'

const props = defineProps({
  submitting: { type: Boolean, default: false },
})

const emit = defineEmits(['close', 'confirm'])
const chatStore = useChatStore()

const activeTab = ref('friends')
const selectedRooms = ref(new Set())
const selectedFriends = ref(new Set())
const searchKeyword = ref('')

const friendUnreadMap = computed(() => chatStore.friendUnread || {})

const roomCandidates = computed(() => {
  return (chatStore.rooms || []).filter(r => r.roomId !== chatStore.currentRoomId)
})

const friendCandidates = computed(() => {
  return (chatStore.friends || []).filter(f => f.username !== chatStore.currentFriendUsername)
})

const filteredRoomCandidates = computed(() => {
  const kw = (searchKeyword.value || '').toLowerCase()
  if (!kw) return roomCandidates.value
  return roomCandidates.value.filter((r) => {
    return String(r.roomId).includes(kw) || String(r.roomName || '').toLowerCase().includes(kw)
  })
})

const filteredFriendCandidates = computed(() => {
  const kw = (searchKeyword.value || '').toLowerCase()
  if (!kw) return friendCandidates.value
  return friendCandidates.value.filter((f) => {
    return String(f.username || '').toLowerCase().includes(kw) ||
      String(f.displayName || '').toLowerCase().includes(kw)
  })
})

const selectedCount = computed(() => selectedRooms.value.size + selectedFriends.value.size)

const allChecked = computed(() => {
  const list = activeTab.value === 'friends' ? filteredFriendCandidates.value : filteredRoomCandidates.value
  if (list.length === 0) return false
  if (activeTab.value === 'friends') return selectedFriends.value.size === list.length
  return selectedRooms.value.size === list.length
})

function toggleFriend(username, checked) {
  const next = new Set(selectedFriends.value)
  if (checked) next.add(username)
  else next.delete(username)
  selectedFriends.value = next
}

function toggleRoom(roomId, checked) {
  const next = new Set(selectedRooms.value)
  if (checked) next.add(roomId)
  else next.delete(roomId)
  selectedRooms.value = next
}

function toggleAll(e) {
  const checked = !!e.target.checked
  if (activeTab.value === 'friends') {
    if (!checked) {
      selectedFriends.value = new Set()
      return
    }
    const next = new Set(selectedFriends.value)
    filteredFriendCandidates.value.forEach((f) => next.add(f.username))
    selectedFriends.value = next
  } else {
    if (!checked) {
      selectedRooms.value = new Set()
      return
    }
    const next = new Set(selectedRooms.value)
    filteredRoomCandidates.value.forEach((r) => next.add(r.roomId))
    selectedRooms.value = next
  }
}

function submitForward() {
  const targets = []
  selectedRooms.value.forEach((roomId) => targets.push({ type: 'room', roomId }))
  selectedFriends.value.forEach((username) => targets.push({ type: 'friend', username }))
  emit('confirm', targets)
}
</script>

<style scoped>
.forward-modal {
  width: min(520px, 92vw);
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  color: var(--text-primary);
}

.tab-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.tab-btn {
  flex: 1;
  border: 1px solid var(--border-color);
  background: var(--bg-secondary);
  color: var(--text-secondary);
  border-radius: 8px;
  padding: 8px 10px;
  cursor: pointer;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.tab-btn.active {
  background: var(--primary-color, #4caf50);
  color: #fff;
  border-color: var(--primary-color, #4caf50);
}

.tab-count {
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  font-size: 11px;
  background: rgba(0, 0, 0, 0.16);
  color: inherit;
  padding: 0 4px;
}

.search-row {
  margin-bottom: 8px;
}

.toolbar-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}

.check-all {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
}

.selected-count {
  color: var(--text-secondary);
}

.list-wrap {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--bg-primary);
  overflow-y: auto;
  min-height: 220px;
  max-height: 46vh;
  padding: 6px;
}

.list-item {
  display: grid;
  grid-template-columns: 22px 1fr;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.item-main-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.list-item:hover {
  background: var(--bg-hover);
}

.item-main {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-sub {
  color: var(--text-tertiary);
  font-size: 12px;
  justify-self: end;
}

.item-badge {
  font-size: 11px;
  border-radius: 999px;
  padding: 1px 7px;
  border: 1px solid transparent;
}

.item-badge.online {
  color: #2f7d32;
  background: rgba(76, 175, 80, 0.12);
  border-color: rgba(76, 175, 80, 0.25);
}

.item-badge.offline {
  color: #777;
  background: rgba(120, 120, 120, 0.12);
  border-color: rgba(120, 120, 120, 0.25);
}

.item-unread {
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  border-radius: 999px;
  text-align: center;
  font-size: 11px;
  background: #e53935;
  color: #fff;
  padding: 0 5px;
}

.empty {
  color: var(--text-tertiary);
  text-align: center;
  padding: 22px 0;
}

@media (max-width: 768px) {
  .forward-modal {
    width: 94vw;
    max-height: 90vh;
  }

  .list-wrap {
    max-height: 52vh;
  }
}
</style>
