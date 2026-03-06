// 聊天状态管理 —— 房间、消息、用户列表、文件传输
import { defineStore } from 'pinia'
import { chatWs, MsgType, FILE_CHUNK_SIZE, MAX_SMALL_FILE } from '../services/websocket'
import { useUserStore } from './user'

export const useChatStore = defineStore('chat', {
  state: () => ({
    rooms: [],              // [{ roomId, roomName, creatorId, unread }]
    currentRoomId: null,
    currentRoomName: '',
    isAdmin: false,
    users: [],              // [{ username, displayName, isAdmin, isOnline }]
    messages: [],           // 当前房间消息
    // 文件上传
    uploads: {},            // uploadId -> { fileName, fileSize, sent, status, file, roomId, paused }
    // 文件下载
    downloads: {},          // fileId -> { fileName, fileSize, received, chunks[], status, paused }
    // 房间设置
    roomSettings: {},       // roomId -> { maxFileSize }
    // 预览模式下载（不触发自动保存）
    _previewFileIds: new Set(),

    // 聊天室头像缓存  roomId -> base64
    roomAvatarCache: {},

    // 好友系统
    friends: [],              // [{ friendshipId, friendId, username, displayName, isOnline }]
    friendPendingRequests: [], // [{ id, fromUsername, fromDisplayName, timestamp }]
    currentFriendUsername: null,
    currentFriendDisplayName: '',
    currentFriendshipId: null,
    isFriendChat: false,
    friendMessages: [],       // 当前好友私聊消息
  }),

  getters: {
    currentRoom: (state) => state.rooms.find(r => r.roomId === state.currentRoomId),
    onlineUsers: (state) => state.users.filter(u => u.isOnline),
    offlineUsers: (state) => state.users.filter(u => !u.isOnline),
  },

  actions: {
    // ==================== 房间 ====================
    setCurrentRoom(roomId) {
      const room = this.rooms.find(r => r.roomId === roomId)
      if (room) {
        this.currentRoomId = roomId
        this.currentRoomName = room.roomName
        room.unread = 0
        this.messages = []
        this.users = []
        chatWs.requestUserList(roomId)
        chatWs.requestHistory(roomId, 50)
      }
    },

    // ==================== 聊天室头像 ====================
    getRoomAvatarSrc(roomId) {
      if (this.roomAvatarCache[roomId]) {
        return 'data:image/png;base64,' + this.roomAvatarCache[roomId]
      }
      return null
    },
    fetchRoomAvatar(roomId) {
      if (this.roomAvatarCache[roomId] !== undefined) return
      this.roomAvatarCache[roomId] = null // mark as loading
      chatWs.getRoomAvatar(roomId)
    },

    // ==================== 文件上传（小文件直传） ====================
    async uploadSmallFile(roomId, file) {
      const userStore = useUserStore()
      const reader = new FileReader()
      return new Promise((resolve) => {
        reader.onload = async () => {
          const base64 = reader.result.split(',')[1]
          let contentType = 'file'
          if (file.type.startsWith('image/')) contentType = 'image'
          else if (file.type.startsWith('video/')) contentType = 'video'

          // 客户端生成缩略图（服务端 QCoreApplication 下 QImage 可能不可用）
          let thumbnail = ''
          if (contentType === 'image') {
            thumbnail = await this._generateImageThumbnail(file)
          } else if (contentType === 'video') {
            thumbnail = await this._generateVideoThumbnail(file)
          }

          chatWs.sendFile(roomId, userStore.username, file.name, file.size, base64, contentType, thumbnail)
          resolve()
        }
        reader.readAsDataURL(file)
      })
    },

    // 生成图片缩略图
    _generateImageThumbnail(file) {
      return new Promise((resolve) => {
        const img = new Image()
        const url = URL.createObjectURL(file)
        img.onload = () => {
          const canvas = document.createElement('canvas')
          const maxSize = 200
          let w = img.width, h = img.height
          if (w > h) { h = Math.round(h * maxSize / w); w = maxSize }
          else { w = Math.round(w * maxSize / h); h = maxSize }
          canvas.width = w
          canvas.height = h
          canvas.getContext('2d').drawImage(img, 0, 0, w, h)
          const dataUrl = canvas.toDataURL('image/jpeg', 0.7)
          URL.revokeObjectURL(url)
          resolve(dataUrl.split(',')[1])
        }
        img.onerror = () => {
          URL.revokeObjectURL(url)
          resolve('')
        }
        img.src = url
      })
    },

    // 生成视频缩略图
    _generateVideoThumbnail(file) {
      return new Promise((resolve) => {
        const video = document.createElement('video')
        video.preload = 'metadata'
        video.muted = true
        const url = URL.createObjectURL(file)
        video.src = url
        video.onloadeddata = () => {
          video.currentTime = Math.min(1, video.duration / 4)
        }
        video.onseeked = () => {
          const canvas = document.createElement('canvas')
          const maxSize = 200
          let w = video.videoWidth, h = video.videoHeight
          if (w > h) { h = Math.round(h * maxSize / w); w = maxSize }
          else { w = Math.round(w * maxSize / h); h = maxSize }
          canvas.width = w
          canvas.height = h
          canvas.getContext('2d').drawImage(video, 0, 0, w, h)
          const dataUrl = canvas.toDataURL('image/jpeg', 0.7)
          URL.revokeObjectURL(url)
          resolve(dataUrl.split(',')[1])
        }
        video.onerror = () => {
          URL.revokeObjectURL(url)
          resolve('')
        }
        // 超时保护
        setTimeout(() => {
          URL.revokeObjectURL(url)
          resolve('')
        }, 5000)
      })
    },

    // ==================== 大文件分块上传 ====================
    async startChunkedUpload(roomId, file) {
      let contentType = 'file'
      if (file.type.startsWith('image/')) contentType = 'image'
      else if (file.type.startsWith('video/')) contentType = 'video'

      // 预先生成缩略图，等上传完成后随 END 消息发送
      let thumbnail = ''
      if (contentType === 'image') {
        thumbnail = await this._generateImageThumbnail(file)
      } else if (contentType === 'video') {
        thumbnail = await this._generateVideoThumbnail(file)
      }

      chatWs.startUpload(roomId, file.name, file.size, contentType)
      // 保存 file 对象，等 START_RSP 返回 uploadId 后开始发块
      this._pendingUploadFile = file
      this._pendingUploadRoom = roomId
      this._pendingUploadThumbnail = thumbnail
    },

    async _sendNextChunk(uploadId) {
      const u = this.uploads[uploadId]
      if (!u || u.status === 'cancelled' || u.paused) return

      if (u.sent >= u.fileSize) {
        // 完成，发送缩略图
        chatWs.endUpload(uploadId, u.thumbnail || '')
        u.status = 'finishing'
        // 上传完成，延迟清理进度条
        setTimeout(() => { delete this.uploads[uploadId] }, 1500)
        return
      }

      const start = u.sent
      const end = Math.min(start + FILE_CHUNK_SIZE, u.fileSize)
      const blob = u.file.slice(start, end)
      const reader = new FileReader()
      reader.onload = () => {
        const base64 = reader.result.split(',')[1]
        chatWs.sendChunk(uploadId, start, base64)
      }
      reader.readAsDataURL(blob)
    },

    pauseUpload(uploadId) {
      const u = this.uploads[uploadId]
      if (u) { u.paused = true; u.status = 'paused' }
    },

    resumeUpload(uploadId) {
      const u = this.uploads[uploadId]
      if (u && u.paused) {
        u.paused = false
        u.status = 'uploading'
        this._sendNextChunk(uploadId)
      }
    },

    cancelUpload(uploadId) {
      const u = this.uploads[uploadId]
      if (u) {
        u.status = 'cancelled'
        chatWs.cancelUpload(uploadId)
        delete this.uploads[uploadId]
      }
    },

    // ==================== 大文件分块下载 ====================
    startChunkedDownload(fileId, fileName, fileSize) {
      this.downloads[fileId] = {
        fileName, fileSize, received: 0, chunks: [], status: 'downloading', paused: false
      }
      chatWs.downloadChunk(fileId, 0, FILE_CHUNK_SIZE)
    },

    _onDownloadChunk(data) {
      const d = this.downloads[data.fileId]
      if (!d || d.paused) return

      if (data.success && data.chunkData) {
        // base64 -> Uint8Array
        const binary = atob(data.chunkData)
        const bytes = new Uint8Array(binary.length)
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
        d.chunks.push(bytes)
        d.received = data.offset + data.chunkSize

        if (d.received >= data.fileSize) {
          // 下载完成 - 合并并保存
          d.status = 'completed'
          const blob = new Blob(d.chunks)
          const url = URL.createObjectURL(blob)
          const a = document.createElement('a')
          a.href = url
          a.download = d.fileName
          a.click()
          URL.revokeObjectURL(url)
          delete this.downloads[data.fileId]
        } else {
          // 继续下载下一块
          chatWs.downloadChunk(data.fileId, d.received, FILE_CHUNK_SIZE)
        }
      }
    },

    pauseDownload(fileId) {
      const d = this.downloads[fileId]
      if (d) { d.paused = true; d.status = 'paused' }
    },

    resumeDownload(fileId) {
      const d = this.downloads[fileId]
      if (d && d.paused) {
        d.paused = false
        d.status = 'downloading'
        chatWs.downloadChunk(fileId, d.received, FILE_CHUNK_SIZE)
      }
    },

    // ==================== 好友系统 ====================
    setCurrentFriend(friendUsername) {
      const fr = this.friends.find(f => f.username === friendUsername)
      if (fr) {
        this.isFriendChat = true
        this.currentFriendUsername = friendUsername
        this.currentFriendDisplayName = fr.displayName || friendUsername
        this.currentFriendshipId = fr.friendshipId
        this.friendMessages = []
        // 清除房间选中
        this.currentRoomId = null
        this.currentRoomName = ''
        this.messages = []
        this.users = []
        chatWs.requestFriendHistory(friendUsername, 50)
      }
    },

    exitFriendChat() {
      this.isFriendChat = false
      this.currentFriendUsername = null
      this.currentFriendDisplayName = ''
      this.currentFriendshipId = null
      this.friendMessages = []
    },

    async uploadFriendSmallFile(friendUsername, file) {
      const reader = new FileReader()
      return new Promise((resolve) => {
        reader.onload = async () => {
          const base64 = reader.result.split(',')[1]
          let contentType = 'file'
          if (file.type.startsWith('image/')) contentType = 'image'
          else if (file.type.startsWith('video/')) contentType = 'video'

          let thumbnail = ''
          if (contentType === 'image') {
            thumbnail = await this._generateImageThumbnail(file)
          } else if (contentType === 'video') {
            thumbnail = await this._generateVideoThumbnail(file)
          }

          chatWs.sendFriendFile(friendUsername, file.name, file.size, base64, contentType, thumbnail)
          resolve()
        }
        reader.readAsDataURL(file)
      })
    },

    async startFriendChunkedUpload(friendUsername, file) {
      // 预先生成缩略图，等上传完成后随 END 消息发送（与房间大文件上传一致）
      let contentType = 'file'
      if (file.type.startsWith('image/')) contentType = 'image'
      else if (file.type.startsWith('video/')) contentType = 'video'

      let thumbnail = ''
      if (contentType === 'image') {
        thumbnail = await this._generateImageThumbnail(file)
      } else if (contentType === 'video') {
        thumbnail = await this._generateVideoThumbnail(file)
      }

      chatWs.startFriendUpload(friendUsername, file.name, file.size)
      this._pendingUploadFile = file
      this._pendingFriendUpload = friendUsername
      this._pendingUploadThumbnail = thumbnail
    },

    // ==================== 初始化消息监听 ====================
    initListeners() {
      if (this._listenersInitialized) return
      this._listenersInitialized = true
      // --- 房间列表 ---
      chatWs.on(MsgType.ROOM_LIST_RSP, (msg) => {
        const list = msg.data.rooms || []
        // 合并 unread
        this.rooms = list.map(r => {
          const old = this.rooms.find(o => o.roomId === r.roomId)
          return { ...r, unread: old ? old.unread : 0 }
        })
      })

      chatWs.on(MsgType.CREATE_ROOM_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          this.rooms.push({ roomId: d.roomId, roomName: d.roomName, creatorId: 0, unread: 0 })
          this.setCurrentRoom(d.roomId)
          this.isAdmin = d.isAdmin
        } else {
          alert('创建房间失败: ' + (d.error || ''))
        }
      })

      chatWs.on(MsgType.JOIN_ROOM_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          if (!this.rooms.find(r => r.roomId === d.roomId)) {
            this.rooms.push({ roomId: d.roomId, roomName: d.roomName, creatorId: 0, unread: 0 })
          }
          this.setCurrentRoom(d.roomId)
          this.isAdmin = d.isAdmin || false
        } else if (d.needPassword) {
          // 需要密码 - 触发事件
          this._emit('needPassword', d)
        } else {
          alert('加入房间失败: ' + (d.error || ''))
        }
      })

      chatWs.on(MsgType.LEAVE_ROOM_RSP, (msg) => {
        if (msg.data.success) {
          const rid = msg.data.roomId
          this.rooms = this.rooms.filter(r => r.roomId !== rid)
          if (this.currentRoomId === rid) {
            this.currentRoomId = null
            this.currentRoomName = ''
            this.messages = []
            this.users = []
          }
        }
      })

      chatWs.on(MsgType.DELETE_ROOM_NOTIFY, (msg) => {
        const d = msg.data
        this.rooms = this.rooms.filter(r => r.roomId !== d.roomId)
        if (this.currentRoomId === d.roomId) {
          this.currentRoomId = null
          this.currentRoomName = ''
          this.messages = []
          this.users = []
          alert(`房间 "${d.roomName}" 已被 ${d.operator} 删除`)
        }
      })

      chatWs.on(MsgType.RENAME_ROOM_RSP, (msg) => {
        if (msg.data.success) {
          const r = this.rooms.find(r => r.roomId === msg.data.roomId)
          if (r) r.roomName = msg.data.newName
          if (this.currentRoomId === msg.data.roomId) {
            this.currentRoomName = msg.data.newName
          }
        }
      })

      chatWs.on(MsgType.RENAME_ROOM_NOTIFY, (msg) => {
        const r = this.rooms.find(r => r.roomId === msg.data.roomId)
        if (r) r.roomName = msg.data.newName
        if (this.currentRoomId === msg.data.roomId) {
          this.currentRoomName = msg.data.newName
        }
      })

      // --- 用户列表 ---
      chatWs.on(MsgType.USER_LIST_RSP, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          this.users = msg.data.users || []
          // 根据用户列表更新当前用户的管理员状态
          const userStore = useUserStore()
          const me = this.users.find(u => u.username === userStore.username)
          this.isAdmin = me ? me.isAdmin : false
        }
      })

      chatWs.on(MsgType.USER_JOINED, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          const existing = this.users.find(u => u.username === msg.data.username)
          if (!existing) {
            this.users.push({
              username: msg.data.username,
              displayName: msg.data.displayName,
              isAdmin: false,
              isOnline: true
            })
          } else {
            existing.isOnline = true
          }
        }
      })

      chatWs.on(MsgType.USER_LEFT, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          this.users = this.users.filter(u => u.username !== msg.data.username)
        }
      })

      chatWs.on(MsgType.USER_ONLINE, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          const u = this.users.find(u => u.username === msg.data.username)
          if (u) u.isOnline = true
        }
      })

      chatWs.on(MsgType.USER_OFFLINE, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          const u = this.users.find(u => u.username === msg.data.username)
          if (u) u.isOnline = false
        }
      })

      // --- 聊天消息 ---
      chatWs.on(MsgType.CHAT_MSG, (msg) => {
        const d = { ...msg.data, timestamp: msg.data.timestamp || msg.timestamp }
        if (d.roomId === this.currentRoomId) {
          this.messages.push(d)
        } else {
          // 未读+1
          const room = this.rooms.find(r => r.roomId === d.roomId)
          if (room) room.unread = (room.unread || 0) + 1
        }
      })

      chatWs.on(MsgType.SYSTEM_MSG, (msg) => {
        const d = { ...msg.data, timestamp: msg.data.timestamp || msg.timestamp }
        if (d.roomId === this.currentRoomId) {
          this.messages.push({ ...d, contentType: 'system', sender: '' })
        }
      })

      chatWs.on(MsgType.HISTORY_RSP, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          const msgs = msg.data.messages || []
          // 倒序追加（历史消息从旧到新）
          this.messages = [...msgs, ...this.messages]
        }
      })

      // --- 消息撤回 ---
      chatWs.on(MsgType.RECALL_RSP, (msg) => {
        if (!msg.data.success) {
          alert('撤回失败: ' + (msg.data.error || ''))
        }
      })

      chatWs.on(MsgType.RECALL_NOTIFY, (msg) => {
        const d = msg.data
        if (d.roomId === this.currentRoomId) {
          const m = this.messages.find(m => m.id === d.messageId)
          if (m) m.recalled = true
        }
      })

      // --- 好友私聊撤回 ---
      chatWs.on(MsgType.FRIEND_RECALL_RSP, (msg) => {
        if (!msg.data.success) {
          alert('撤回失败: ' + (msg.data.error || ''))
        } else if (this.isFriendChat) {
          const m = this.friendMessages.find(m => m.id === msg.data.messageId)
          if (m) m.recalled = true
        }
      })

      chatWs.on(MsgType.FRIEND_RECALL_NOTIFY, (msg) => {
        const d = msg.data
        if (this.isFriendChat && this.currentFriendUsername === d.friendUsername) {
          const m = this.friendMessages.find(m => m.id === d.messageId)
          if (m) m.recalled = true
        }
      })

      // --- 文件消息 ---
      chatWs.on(MsgType.FILE_NOTIFY, (msg) => {
        const d = { ...msg.data, timestamp: msg.data.timestamp || msg.timestamp }
        if (d.roomId === this.currentRoomId) {
          this.messages.push(d)
        } else {
          const room = this.rooms.find(r => r.roomId === d.roomId)
          if (room) room.unread = (room.unread || 0) + 1
        }
      })

      chatWs.on(MsgType.FILE_DOWNLOAD_RSP, (msg) => {
        const d = msg.data
        // 预览模式跳过自动下载
        if (d.fileId && this._previewFileIds.has(d.fileId)) {
          this._previewFileIds.delete(d.fileId)
          return
        }
        if (d.success && d.fileData) {
          const binary = atob(d.fileData)
          const bytes = new Uint8Array(binary.length)
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
          const blob = new Blob([bytes])
          const url = URL.createObjectURL(blob)
          const a = document.createElement('a')
          a.href = url
          a.download = d.fileName
          a.click()
          URL.revokeObjectURL(url)
        }
      })

      // --- 大文件分块上传 ---
      chatWs.on(MsgType.FILE_UPLOAD_START_RSP, (msg) => {
        const d = msg.data
        if (d.success && d.uploadId) {
          const file = this._pendingUploadFile
          const roomId = this._pendingUploadRoom
          this.uploads[d.uploadId] = {
            fileName: file.name,
            fileSize: file.size,
            sent: 0,
            status: 'uploading',
            file: file,
            roomId,
            paused: false,
            thumbnail: this._pendingUploadThumbnail || ''
          }
          this._sendNextChunk(d.uploadId)
        } else {
          alert('上传失败: ' + (d.error || ''))
        }
      })

      chatWs.on(MsgType.FILE_UPLOAD_CHUNK_RSP, (msg) => {
        const d = msg.data
        const u = this.uploads[d.uploadId]
        if (u && d.success) {
          u.sent = d.received
          this._sendNextChunk(d.uploadId)
        }
      })

      // --- 大文件分块下载 ---
      chatWs.on(MsgType.FILE_DOWNLOAD_CHUNK_RSP, (msg) => {
        this._onDownloadChunk(msg.data)
      })

      // --- 管理员 ---
      chatWs.on(MsgType.ADMIN_STATUS, (msg) => {
        if (msg.data.roomId === this.currentRoomId) {
          this.isAdmin = msg.data.isAdmin
        }
      })

      chatWs.on(MsgType.SET_ADMIN_RSP, (msg) => {
        if (!msg.data.success) {
          alert('设置管理员失败: ' + (msg.data.error || ''))
        } else {
          // 更新用户列表中的管理员状态
          chatWs.requestUserList(this.currentRoomId)
        }
      })

      chatWs.on(MsgType.DELETE_MSGS_RSP, (msg) => {
        if (msg.data.success) {
          // 刷新消息列表
          if (msg.data.mode === 'all') {
            this.messages = []
          }
        }
      })

      chatWs.on(MsgType.DELETE_MSGS_NOTIFY, (msg) => {
        const d = msg.data
        if (d.roomId === this.currentRoomId) {
          if (d.mode === 'all') {
            this.messages = []
          } else if (d.messageIds) {
            this.messages = this.messages.filter(m => !d.messageIds.includes(m.id))
          }
        }
      })

      chatWs.on(MsgType.KICK_USER_NOTIFY, (msg) => {
        const d = msg.data
        const userStore = useUserStore()
        // 被踢出
        this.rooms = this.rooms.filter(r => r.roomId !== d.roomId)
        if (this.currentRoomId === d.roomId) {
          this.currentRoomId = null
          this.currentRoomName = ''
          this.messages = []
          this.users = []
          alert(`您已被 ${d.operator} 踢出房间 "${d.roomName}"`)
        }
      })

      chatWs.on(MsgType.KICK_USER_RSP, (msg) => {
        if (!msg.data.success) {
          alert('踢出用户失败: ' + (msg.data.error || ''))
        } else {
          chatWs.requestUserList(this.currentRoomId)
        }
      })

      // --- 房间设置 ---
      chatWs.on(MsgType.ROOM_SETTINGS_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          this.roomSettings[d.roomId] = { maxFileSize: d.maxFileSize }
        }
      })

      chatWs.on(MsgType.ROOM_SETTINGS_NOTIFY, (msg) => {
        this.roomSettings[msg.data.roomId] = { maxFileSize: msg.data.maxFileSize }
      })

      // --- 昵称/UID变更通知 ---
      chatWs.on(MsgType.NICKNAME_CHANGE_NOTIFY, (msg) => {
        const d = msg.data
        const u = this.users.find(u => u.username === d.username)
        if (u) u.displayName = d.displayName
        // 更新消息中的发送者名字
        this.messages.forEach(m => {
          if (m.sender === d.username) m.senderName = d.displayName
        })
      })

      chatWs.on(MsgType.UID_CHANGE_NOTIFY, (msg) => {
        const d = msg.data
        // 更新用户列表
        const u = this.users.find(u => u.username === d.oldUid)
        if (u) u.username = d.newUid
        // 更新消息中的 sender
        this.messages.forEach(m => {
          if (m.sender === d.oldUid) m.sender = d.newUid
        })
      })

      // 房间密码
      chatWs.on(MsgType.SET_ROOM_PASSWORD_RSP, (msg) => {
        if (!msg.data.success) {
          alert('设置密码失败: ' + (msg.data.error || ''))
        }
      })

      chatWs.on(MsgType.GET_ROOM_PASSWORD_RSP, (msg) => {
        if (msg.data.success) {
          this._emit('roomPassword', msg.data)
        }
      })

      chatWs.on(MsgType.DELETE_ROOM_RSP, (msg) => {
        if (msg.data.success) {
          this.rooms = this.rooms.filter(r => r.roomId !== msg.data.roomId)
          if (this.currentRoomId === msg.data.roomId) {
            this.currentRoomId = null
            this.currentRoomName = ''
            this.messages = []
            this.users = []
          }
        } else {
          alert('删除房间失败: ' + (msg.data.error || ''))
        }
      })

      // ==================== 好友系统监听 ====================

      chatWs.on(MsgType.FRIEND_REQUEST_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          this._emit('info', '好友请求已发送')
        } else {
          this._emit('error', d.error || '发送好友请求失败')
        }
      })

      chatWs.on(MsgType.FRIEND_REQUEST_NOTIFY, (msg) => {
        const d = msg.data
        // 自动刷新待处理列表
        chatWs.requestFriendPending()
        this._emit('friendRequest', d)
        this._emit('info', `${d.fromDisplayName || d.fromUsername} 请求加你为好友`)
      })

      chatWs.on(MsgType.FRIEND_ACCEPT_RSP, (msg) => {
        if (msg.data.success) {
          chatWs.requestFriendList()
          this._emit('info', '已接受好友请求')
        }
      })

      chatWs.on(MsgType.FRIEND_ACCEPT_NOTIFY, (msg) => {
        chatWs.requestFriendList()
        this._emit('info', `${msg.data.acceptedByDisplay || msg.data.acceptedBy} 已接受你的好友请求`)
      })

      chatWs.on(MsgType.FRIEND_REJECT_RSP, (msg) => {
        if (msg.data.success) {
          this._emit('info', '已拒绝好友请求')
        }
      })

      chatWs.on(MsgType.FRIEND_REMOVE_RSP, (msg) => {
        if (msg.data.success) {
          this.friends = this.friends.filter(f => f.username !== msg.data.username)
          if (this.isFriendChat && this.currentFriendUsername === msg.data.username) {
            this.exitFriendChat()
          }
          this._emit('info', `已删除好友 ${msg.data.username}`)
        } else {
          this._emit('error', msg.data.error || '删除好友失败')
        }
      })

      chatWs.on(MsgType.FRIEND_LIST_RSP, (msg) => {
        this.friends = msg.data.friends || []
      })

      chatWs.on(MsgType.FRIEND_PENDING_RSP, (msg) => {
        this.friendPendingRequests = msg.data.requests || []
        this._emit('friendPending', this.friendPendingRequests)
      })

      chatWs.on(MsgType.USER_SEARCH_RSP, (msg) => {
        if (msg.data.success) {
          this._emit('userSearchResults', msg.data.users || [])
        } else {
          this._emit('error', msg.data.error || '搜索失败')
        }
      })

      // --- 聊天室搜索 ---
      chatWs.on(MsgType.ROOM_SEARCH_RSP, (msg) => {
        if (msg.data.success) {
          this._emit('roomSearchResults', msg.data.rooms || [])
        } else {
          this._emit('error', msg.data.error || '搜索失败')
        }
      })

      // --- 聊天室头像 ---
      chatWs.on(MsgType.ROOM_AVATAR_UPLOAD_RSP, (msg) => {
        if (msg.data.success) {
          // 重新获取头像以立即更新缓存
          const roomId = msg.data.roomId
          if (roomId) {
            delete this.roomAvatarCache[roomId]
            chatWs.getRoomAvatar(roomId)
          }
          this._emit('roomAvatarUploaded', msg.data)
        } else {
          this._emit('error', msg.data.error || '上传聊天室头像失败')
        }
      })

      chatWs.on(MsgType.ROOM_AVATAR_GET_RSP, (msg) => {
        const d = msg.data
        if (d.success && d.avatarData) {
          this.roomAvatarCache[d.roomId] = d.avatarData
        }
        this._emit('roomAvatarLoaded', d)
      })

      chatWs.on(MsgType.ROOM_AVATAR_UPDATE_NOTIFY, (msg) => {
        const d = msg.data
        if (d.avatarData) {
          this.roomAvatarCache[d.roomId] = d.avatarData
        }
      })

      chatWs.on(MsgType.FRIEND_CHAT_MSG, (msg) => {
        const d = msg.data
        const userStore = useUserStore()
        const chatWith = d.sender === userStore.username ? d.friendUsername : d.sender

        if (this.isFriendChat && this.currentFriendUsername === chatWith) {
          this.friendMessages.push(d)
        }
        // 如果不是当前聊天对象，可以加 unread 逻辑（暂略）
      })

      chatWs.on(MsgType.FRIEND_HISTORY_RSP, (msg) => {
        const d = msg.data
        if (this.isFriendChat && this.currentFriendUsername === d.friendUsername) {
          const msgs = d.messages || []
          this.friendMessages = [...msgs, ...this.friendMessages]
        }
      })

      chatWs.on(MsgType.FRIEND_FILE_NOTIFY, (msg) => {
        const d = msg.data
        const userStore = useUserStore()
        const chatWith = d.sender === userStore.username ? d.friendUsername : d.sender

        if (this.isFriendChat && this.currentFriendUsername === chatWith) {
          this.friendMessages.push(d)
        }
      })

      chatWs.on(MsgType.FRIEND_ONLINE_NOTIFY, (msg) => {
        const fr = this.friends.find(f => f.username === msg.data.username)
        if (fr) fr.isOnline = true
      })

      chatWs.on(MsgType.FRIEND_OFFLINE_NOTIFY, (msg) => {
        const fr = this.friends.find(f => f.username === msg.data.username)
        if (fr) fr.isOnline = false
      })

      chatWs.on(MsgType.FRIEND_FILE_UPLOAD_START_RSP, (msg) => {
        const d = msg.data
        if (d.success && d.uploadId) {
          const file = this._pendingUploadFile
          this.uploads[d.uploadId] = {
            fileName: file.name,
            fileSize: file.size,
            sent: 0,
            status: 'uploading',
            file: file,
            roomId: -d.friendshipId,
            paused: false,
            thumbnail: this._pendingUploadThumbnail || ''
          }
          this._sendNextChunk(d.uploadId)
        } else {
          alert('好友文件上传失败: ' + (d.error || ''))
        }
      })
    },

    // 手动触发下载（用于预览界面的下载按钮）
    _triggerDownload(fileId, fileName, fileSize) {
      if (fileSize && fileSize > MAX_SMALL_FILE) {
        this.startChunkedDownload(fileId, fileName, fileSize)
      } else {
        chatWs.downloadFile(fileId)
      }
    },

    // 简单事件系统
    _callbacks: {},
    _emit(event, data) {
      const cbs = this._callbacks[event]
      if (cbs) cbs.forEach(fn => fn(data))
    },
    onEvent(event, fn) {
      if (!this._callbacks[event]) this._callbacks[event] = []
      this._callbacks[event].push(fn)
    },
    offEvent(event, fn) {
      const cbs = this._callbacks[event]
      if (cbs) {
        const idx = cbs.indexOf(fn)
        if (idx >= 0) cbs.splice(idx, 1)
      }
    }
  }
})
