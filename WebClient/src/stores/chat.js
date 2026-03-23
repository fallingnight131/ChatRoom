// 聊天状态管理 —— 房间、消息、用户列表、文件传输
import { defineStore } from 'pinia'
import { chatWs, MsgType, FILE_CHUNK_SIZE, MAX_SMALL_FILE, makeMessage, getHttpDownloadUrl } from '../services/websocket'
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
    _uploadQueue: [],       // 上传队列：[{ type:'room'|'friend', roomId, friendUsername, file, thumbnail }]
    _isUploading: false,    // 是否有正在进行的大文件上传
    // 文件下载
    downloads: {},          // fileId -> { fileName, fileSize, received, blob, status, paused }
    // 房间设置
    roomSettings: {},       // roomId -> { maxFileSize, totalFileSpace, maxFileCount, maxMembers }
    roomFiles: [],          // 当前房间文件管理列表
    roomFileUsage: { used: 0, max: 0 },
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
    friendUnread: {},         // username -> unread count
    hasPendingFriendReq: false, // 是否有未处理的好友申请
  }),

  getters: {
    currentRoom: (state) => state.rooms.find(r => r.roomId === state.currentRoomId),
    onlineUsers: (state) => state.users.filter(u => u.isOnline),
    offlineUsers: (state) => state.users.filter(u => !u.isOnline),
    totalRoomUnread: (state) => state.rooms.reduce((sum, r) => sum + (r.unread || 0), 0),
    totalFriendUnread: (state) => Object.values(state.friendUnread).reduce((s, v) => s + v, 0),
  },

  actions: {
    // ==================== 房间 ====================
    setCurrentRoom(roomId) {
      const room = this.rooms.find(r => r.roomId === roomId)
      if (room) {
        this.currentRoomId = roomId
        this.currentRoomName = room.roomName
        this.isAdmin = !!room.isAdmin
        room.unread = 0
        this.messages = []
        this.users = []
        chatWs.requestUserList(roomId)
        chatWs.requestHistory(roomId, 50)
        chatWs.requestRoomSettings(roomId)
        this.roomFiles = []
        this.roomFileUsage = { used: 0, max: 0 }
        chatWs.send(makeMessage(MsgType.MARK_ROOM_READ, { roomId }))
      }
    },

    requestRoomFiles(roomId) {
      chatWs.requestRoomFiles(roomId)
    },

    deleteRoomFiles(roomId, fileIds) {
      chatWs.deleteRoomFiles(roomId, fileIds)
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

      // 如果有正在进行的上传，加入队列等待
      if (this._isUploading) {
        this._uploadQueue.push({ type: 'room', roomId, file, thumbnail, contentType })
        return
      }
      this._isUploading = true

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
        // 上传完成，延迟清理进度条并启动队列中下一个上传
        setTimeout(() => { delete this.uploads[uploadId] }, 1500)
        this._isUploading = false
        this._processNextUpload()
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
        this._isUploading = false
        this._processNextUpload()
      }
    },

    // 处理上传队列中的下一个文件
    _processNextUpload() {
      if (this._uploadQueue.length === 0) return
      const next = this._uploadQueue.shift()
      this._isUploading = true
      this._pendingUploadFile = next.file
      this._pendingUploadThumbnail = next.thumbnail
      if (next.type === 'room') {
        this._pendingUploadRoom = next.roomId
        chatWs.startUpload(next.roomId, next.file.name, next.file.size, next.contentType)
      } else {
        this._pendingFriendUpload = next.friendUsername
        chatWs.startFriendUpload(next.friendUsername, next.file.name, next.file.size)
      }
    },

    // ==================== 大文件分块下载 ====================
    startChunkedDownload(fileId, fileName, fileSize) {
      this.downloads[fileId] = {
        fileName, fileSize, received: 0, blob: null, status: 'downloading', paused: false
      }
      chatWs.downloadChunk(fileId, 0, FILE_CHUNK_SIZE)
    },

    _onDownloadChunk(data) {
      // 预览模式跳过，由 FilePreview 自己处理
      if (this._previewFileIds.has(data.fileId)) return
      const d = this.downloads[data.fileId]
      if (!d || d.paused) return

      if (data.success && data.chunkData) {
        // base64 -> Uint8Array
        const binary = atob(data.chunkData)
        const bytes = new Uint8Array(binary.length)
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
        d.blob = d.blob ? new Blob([d.blob, bytes]) : new Blob([bytes])
        d.received = data.offset + data.chunkSize

        if (d.received >= data.fileSize) {
          // 下载完成 - 直接保存最终 Blob
          d.status = 'completed'
          const url = URL.createObjectURL(d.blob || new Blob())
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

    cancelDownload(fileId) {
      delete this.downloads[fileId]
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
        // 清除该好友未读计数
        delete this.friendUnread[friendUsername]
        // 清除房间选中
        this.currentRoomId = null
        this.currentRoomName = ''
        this.messages = []
        this.users = []
        chatWs.requestFriendHistory(friendUsername, 50)
        chatWs.send(makeMessage(MsgType.MARK_FRIEND_READ, { friendshipId: fr.friendshipId }))
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

      // 如果有正在进行的上传，加入队列等待
      if (this._isUploading) {
        this._uploadQueue.push({ type: 'friend', friendUsername, file, thumbnail, contentType })
        return
      }
      this._isUploading = true

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
        // 使用服务器返回的未读计数
        this.rooms = list.map(r => {
          return { ...r, unread: r.unread || 0, isAdmin: !!r.isAdmin }
        })
      })

      chatWs.on(MsgType.CREATE_ROOM_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          this.rooms.push({ roomId: d.roomId, roomName: d.roomName, creatorId: 0, unread: 0, isAdmin: !!d.isAdmin })
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
            this.rooms.push({ roomId: d.roomId, roomName: d.roomName, creatorId: 0, unread: 0, isAdmin: !!d.isAdmin })
          } else {
            const room = this.rooms.find(r => r.roomId === d.roomId)
            if (room) room.isAdmin = !!d.isAdmin
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
        const rid = msg.data.roomId
        const room = this.rooms.find(r => r.roomId === rid)
        if (room) room.isAdmin = !!msg.data.isAdmin
        if (rid === this.currentRoomId) this.isAdmin = !!msg.data.isAdmin
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
        const d = msg.data
        if (d.success) {
          if (d.roomId !== this.currentRoomId) return
          if (d.mode === 'all') {
            this.messages = []
          } else if (Array.isArray(d.messageIds) && d.messageIds.length > 0) {
            const idSet = new Set(d.messageIds.map(Number))
            this.messages = this.messages.filter(m => !idSet.has(Number(m.id)))
          }
        }
      })

      chatWs.on(MsgType.DELETE_MSGS_NOTIFY, (msg) => {
        const d = msg.data
        if (d.roomId === this.currentRoomId) {
          if (d.mode === 'all') {
            this.messages = []
          } else if (Array.isArray(d.messageIds) && d.messageIds.length > 0) {
            const idSet = new Set(d.messageIds.map(Number))
            this.messages = this.messages.filter(m => !idSet.has(Number(m.id)))
          }
        }
      })

      chatWs.on(MsgType.ROOM_FILES_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          this.roomFiles = d.files || []
          this.roomFileUsage = {
            used: Number(d.usedFileSpace || 0),
            max: Number(d.maxFileSpace || 0)
          }
          this._emit('roomFilesLoaded', d)
        } else {
          this._emit('error', d.error || '加载房间文件失败')
        }
      })

      chatWs.on(MsgType.ROOM_FILES_DELETE_RSP, (msg) => {
        const d = msg.data
        if (d.success) {
          if (Array.isArray(d.messageIds) && d.messageIds.length > 0) {
            const idSet = new Set(d.messageIds.map(Number))
            this.messages = this.messages.filter(m => !idSet.has(Number(m.id)))
          }
          this.roomFileUsage = {
            used: Number(d.usedFileSpace || 0),
            max: Number(d.maxFileSpace || 0)
          }
          this._emit('roomFilesDeleted', d)
          chatWs.requestRoomFiles(d.roomId)
        } else {
          this._emit('error', d.error || '删除文件失败')
        }
      })

      chatWs.on(MsgType.ROOM_FILES_NOTIFY, (msg) => {
        const d = msg.data
        this.roomFileUsage = {
          used: Number(d.usedFileSpace || 0),
          max: Number(d.maxFileSpace || 0)
        }
        if (d.roomId === this.currentRoomId) {
          chatWs.requestRoomFiles(d.roomId)
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
          this.roomSettings[d.roomId] = {
            maxFileSize: d.maxFileSize,
            totalFileSpace: d.totalFileSpace,
            maxFileCount: d.maxFileCount,
            maxMembers: d.maxMembers,
          }
          if (Array.isArray(d.clearedFileIds) && d.clearedFileIds.length > 0) {
            const idSet = new Set(d.clearedFileIds)
            this.messages.forEach(m => {
              if (idSet.has(m.fileId)) {
                m.fileCleared = true
                m.clearReason = '文件已过期或被清除'
              }
            })
          }
          this._emit('roomSettingsSaved', d)
        } else if (d.needConfirm) {
          this._emit('roomSettingsNeedConfirm', d)
        } else {
          this._emit('roomSettingsFailed', d)
          alert(d.error || '保存限制失败')
        }
      })

      chatWs.on(MsgType.ROOM_SETTINGS_NOTIFY, (msg) => {
        const d = msg.data
        this.roomSettings[d.roomId] = {
          maxFileSize: d.maxFileSize,
          totalFileSpace: d.totalFileSpace,
          maxFileCount: d.maxFileCount,
          maxMembers: d.maxMembers,
        }
        if (Array.isArray(d.clearedFileIds) && d.clearedFileIds.length > 0) {
          const idSet = new Set(d.clearedFileIds)
          this.messages.forEach(m => {
            if (idSet.has(m.fileId)) {
              m.fileCleared = true
              m.clearReason = '文件已过期或被清除'
            }
          })
        }
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
        this.hasPendingFriendReq = true
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
        // 使用服务器返回的未读计数
        const newUnread = {}
        for (const fr of this.friends) {
          if (fr.unread > 0) newUnread[fr.username] = fr.unread
        }
        this.friendUnread = newUnread
        this.hasPendingFriendReq = (msg.data.pendingFriendRequests || 0) > 0
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
        const d = { ...msg.data, timestamp: msg.data.timestamp || msg.timestamp }
        const userStore = useUserStore()
        const chatWith = d.sender === userStore.username ? d.friendUsername : d.sender

        if (this.isFriendChat && this.currentFriendUsername === chatWith) {
          this.friendMessages.push(d)
        } else if (d.sender !== userStore.username) {
          this.friendUnread[chatWith] = (this.friendUnread[chatWith] || 0) + 1
        }
      })

      chatWs.on(MsgType.FRIEND_HISTORY_RSP, (msg) => {
        const d = msg.data
        if (this.isFriendChat && this.currentFriendUsername === d.friendUsername) {
          const msgs = d.messages || []
          this.friendMessages = [...msgs, ...this.friendMessages]
        }
      })

      chatWs.on(MsgType.FRIEND_FILE_NOTIFY, (msg) => {
        const d = { ...msg.data, timestamp: msg.data.timestamp || msg.timestamp }
        const userStore = useUserStore()
        const chatWith = d.sender === userStore.username ? d.friendUsername : d.sender

        if (this.isFriendChat && this.currentFriendUsername === chatWith) {
          this.friendMessages.push(d)
        } else if (d.sender !== userStore.username) {
          this.friendUnread[chatWith] = (this.friendUnread[chatWith] || 0) + 1
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

    async _buildForwardFile(msg) {
      if (!msg?.fileId) {
        throw new Error('文件ID不存在，无法转发')
      }
      const isFriendFile = Number(msg.fileId) < 0
      const url = getHttpDownloadUrl(msg.fileId, isFriendFile, 'attachment')
      if (!url) {
        throw new Error('下载地址不可用，无法转发')
      }

      const resp = await fetch(url)
      if (!resp.ok) {
        throw new Error('下载原文件失败，无法转发')
      }

      const blob = await resp.blob()
      const fileName = msg.fileName || `forward_${msg.fileId}`
      return new File([blob], fileName, { type: blob.type || 'application/octet-stream' })
    },

    async _forwardFileToTarget(target, file) {
      if (target.type === 'room') {
        if (file.size > MAX_SMALL_FILE) {
          await this.startChunkedUpload(target.roomId, file)
        } else {
          await this.uploadSmallFile(target.roomId, file)
        }
        return
      }

      if (target.type === 'friend') {
        if (file.size > MAX_SMALL_FILE) {
          await this.startFriendChunkedUpload(target.username, file)
        } else {
          await this.uploadFriendSmallFile(target.username, file)
        }
      }
    },

    async forwardMessageToTargets(msg, targets = []) {
      if (!msg || !Array.isArray(targets) || targets.length === 0) {
        return
      }

      const isFileMessage = msg.contentType === 'file' || msg.contentType === 'image' || msg.contentType === 'video'
      if (isFileMessage && msg.fileCleared) {
        throw new Error('文件已过期或被清除，Web 端禁止转发')
      }

      if (!isFileMessage) {
        const content = msg.content || ''
        const contentType = msg.contentType === 'emoji' ? 'emoji' : 'text'
        targets.forEach((target) => {
          if (target.type === 'room') {
            chatWs.sendChat(target.roomId, useUserStore().username, content, contentType)
          } else if (target.type === 'friend') {
            chatWs.sendFriendChat(target.username, content, contentType)
          }
        })
        return
      }

      const file = await this._buildForwardFile(msg)
      for (const target of targets) {
        await this._forwardFileToTarget(target, file)
      }
    },

    // 手动触发下载（用于预览界面的下载按钮）
    _triggerDownload(fileId, fileName, fileSize) {
      const httpUrl = getHttpDownloadUrl(fileId, Number(fileId) < 0)
      if (httpUrl) {
        const a = document.createElement('a')
        a.href = httpUrl
        a.download = fileName || ''
        a.click()
        return
      }

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
