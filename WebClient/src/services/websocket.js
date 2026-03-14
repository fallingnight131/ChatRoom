// WebSocket 连接管理 —— 与 Qt 服务端相同的 JSON 协议
import { ref, shallowRef } from 'vue'

const DEFAULT_PORT = 9528 // WebSocket 默认端口 (TCP 9527 + 1)
const HEARTBEAT_INTERVAL = 30000
const HEARTBEAT_TIMEOUT = 90000
const RECONNECT_INTERVAL = 5000
const MAX_RECONNECT = 10
const FILE_CHUNK_SIZE = 4 * 1024 * 1024 // 4MB
const MAX_SMALL_FILE = 8 * 1024 * 1024  // 8MB

// ==================== 消息类型（与 Protocol.h 完全一致） ====================
export const MsgType = {
  LOGIN_REQ: 'LOGIN_REQ', LOGIN_RSP: 'LOGIN_RSP',
  REGISTER_REQ: 'REGISTER_REQ', REGISTER_RSP: 'REGISTER_RSP',
  LOGOUT: 'LOGOUT',
  CHAT_MSG: 'CHAT_MSG', SYSTEM_MSG: 'SYSTEM_MSG',
  CREATE_ROOM_REQ: 'CREATE_ROOM_REQ', CREATE_ROOM_RSP: 'CREATE_ROOM_RSP',
  JOIN_ROOM_REQ: 'JOIN_ROOM_REQ', JOIN_ROOM_RSP: 'JOIN_ROOM_RSP',
  LEAVE_ROOM: 'LEAVE_ROOM', LEAVE_ROOM_RSP: 'LEAVE_ROOM_RSP',
  ROOM_LIST_REQ: 'ROOM_LIST_REQ', ROOM_LIST_RSP: 'ROOM_LIST_RSP',
  USER_LIST_REQ: 'USER_LIST_REQ', USER_LIST_RSP: 'USER_LIST_RSP',
  HISTORY_REQ: 'HISTORY_REQ', HISTORY_RSP: 'HISTORY_RSP',
  FILE_SEND: 'FILE_SEND', FILE_NOTIFY: 'FILE_NOTIFY',
  FILE_DOWNLOAD_REQ: 'FILE_DOWNLOAD_REQ', FILE_DOWNLOAD_RSP: 'FILE_DOWNLOAD_RSP',
  FILE_UPLOAD_START: 'FILE_UPLOAD_START', FILE_UPLOAD_START_RSP: 'FILE_UPLOAD_START_RSP',
  FILE_UPLOAD_CHUNK: 'FILE_UPLOAD_CHUNK', FILE_UPLOAD_CHUNK_RSP: 'FILE_UPLOAD_CHUNK_RSP',
  FILE_UPLOAD_END: 'FILE_UPLOAD_END', FILE_UPLOAD_CANCEL: 'FILE_UPLOAD_CANCEL',
  FILE_DOWNLOAD_CHUNK_REQ: 'FILE_DOWNLOAD_CHUNK_REQ', FILE_DOWNLOAD_CHUNK_RSP: 'FILE_DOWNLOAD_CHUNK_RSP',
  RECALL_REQ: 'RECALL_REQ', RECALL_RSP: 'RECALL_RSP', RECALL_NOTIFY: 'RECALL_NOTIFY',
  HEARTBEAT: 'HEARTBEAT', HEARTBEAT_ACK: 'HEARTBEAT_ACK',
  USER_JOINED: 'USER_JOINED', USER_LEFT: 'USER_LEFT',
  USER_ONLINE: 'USER_ONLINE', USER_OFFLINE: 'USER_OFFLINE',
  FORCE_OFFLINE: 'FORCE_OFFLINE',
  SET_ADMIN_REQ: 'SET_ADMIN_REQ', SET_ADMIN_RSP: 'SET_ADMIN_RSP',
  ADMIN_STATUS: 'ADMIN_STATUS',
  DELETE_MSGS_REQ: 'DELETE_MSGS_REQ', DELETE_MSGS_RSP: 'DELETE_MSGS_RSP',
  DELETE_MSGS_NOTIFY: 'DELETE_MSGS_NOTIFY',
  ROOM_SETTINGS_REQ: 'ROOM_SETTINGS_REQ', ROOM_SETTINGS_RSP: 'ROOM_SETTINGS_RSP',
  ROOM_SETTINGS_NOTIFY: 'ROOM_SETTINGS_NOTIFY',
  DELETE_ROOM_REQ: 'DELETE_ROOM_REQ', DELETE_ROOM_RSP: 'DELETE_ROOM_RSP',
  DELETE_ROOM_NOTIFY: 'DELETE_ROOM_NOTIFY',
  RENAME_ROOM_REQ: 'RENAME_ROOM_REQ', RENAME_ROOM_RSP: 'RENAME_ROOM_RSP',
  RENAME_ROOM_NOTIFY: 'RENAME_ROOM_NOTIFY',
  SET_ROOM_PASSWORD_REQ: 'SET_ROOM_PASSWORD_REQ', SET_ROOM_PASSWORD_RSP: 'SET_ROOM_PASSWORD_RSP',
  GET_ROOM_PASSWORD_REQ: 'GET_ROOM_PASSWORD_REQ', GET_ROOM_PASSWORD_RSP: 'GET_ROOM_PASSWORD_RSP',
  KICK_USER_REQ: 'KICK_USER_REQ', KICK_USER_RSP: 'KICK_USER_RSP',
  KICK_USER_NOTIFY: 'KICK_USER_NOTIFY',
  AVATAR_UPLOAD_REQ: 'AVATAR_UPLOAD_REQ', AVATAR_UPLOAD_RSP: 'AVATAR_UPLOAD_RSP',
  AVATAR_GET_REQ: 'AVATAR_GET_REQ', AVATAR_GET_RSP: 'AVATAR_GET_RSP',
  AVATAR_UPDATE_NOTIFY: 'AVATAR_UPDATE_NOTIFY',
  CHANGE_NICKNAME_REQ: 'CHANGE_NICKNAME_REQ', CHANGE_NICKNAME_RSP: 'CHANGE_NICKNAME_RSP',
  NICKNAME_CHANGE_NOTIFY: 'NICKNAME_CHANGE_NOTIFY',
  CHANGE_UID_REQ: 'CHANGE_UID_REQ', CHANGE_UID_RSP: 'CHANGE_UID_RSP',
  UID_CHANGE_NOTIFY: 'UID_CHANGE_NOTIFY',
  CHANGE_PASSWORD_REQ: 'CHANGE_PASSWORD_REQ', CHANGE_PASSWORD_RSP: 'CHANGE_PASSWORD_RSP',

  // 好友系统
  USER_SEARCH_REQ: 'USER_SEARCH_REQ', USER_SEARCH_RSP: 'USER_SEARCH_RSP',
  ROOM_SEARCH_REQ: 'ROOM_SEARCH_REQ', ROOM_SEARCH_RSP: 'ROOM_SEARCH_RSP',
  ROOM_AVATAR_UPLOAD_REQ: 'ROOM_AVATAR_UPLOAD_REQ', ROOM_AVATAR_UPLOAD_RSP: 'ROOM_AVATAR_UPLOAD_RSP',
  ROOM_AVATAR_GET_REQ: 'ROOM_AVATAR_GET_REQ', ROOM_AVATAR_GET_RSP: 'ROOM_AVATAR_GET_RSP',
  ROOM_AVATAR_UPDATE_NOTIFY: 'ROOM_AVATAR_UPDATE_NOTIFY',
  FRIEND_REQUEST_REQ: 'FRIEND_REQUEST_REQ', FRIEND_REQUEST_RSP: 'FRIEND_REQUEST_RSP',
  FRIEND_REQUEST_NOTIFY: 'FRIEND_REQUEST_NOTIFY',
  FRIEND_ACCEPT_REQ: 'FRIEND_ACCEPT_REQ', FRIEND_ACCEPT_RSP: 'FRIEND_ACCEPT_RSP',
  FRIEND_ACCEPT_NOTIFY: 'FRIEND_ACCEPT_NOTIFY',
  FRIEND_REJECT_REQ: 'FRIEND_REJECT_REQ', FRIEND_REJECT_RSP: 'FRIEND_REJECT_RSP',
  FRIEND_REMOVE_REQ: 'FRIEND_REMOVE_REQ', FRIEND_REMOVE_RSP: 'FRIEND_REMOVE_RSP',
  FRIEND_LIST_REQ: 'FRIEND_LIST_REQ', FRIEND_LIST_RSP: 'FRIEND_LIST_RSP',
  FRIEND_PENDING_REQ: 'FRIEND_PENDING_REQ', FRIEND_PENDING_RSP: 'FRIEND_PENDING_RSP',
  FRIEND_CHAT_MSG: 'FRIEND_CHAT_MSG',
  FRIEND_HISTORY_REQ: 'FRIEND_HISTORY_REQ', FRIEND_HISTORY_RSP: 'FRIEND_HISTORY_RSP',
  FRIEND_FILE_SEND: 'FRIEND_FILE_SEND', FRIEND_FILE_NOTIFY: 'FRIEND_FILE_NOTIFY',
  FRIEND_ONLINE_NOTIFY: 'FRIEND_ONLINE_NOTIFY', FRIEND_OFFLINE_NOTIFY: 'FRIEND_OFFLINE_NOTIFY',
  FRIEND_FILE_UPLOAD_START: 'FRIEND_FILE_UPLOAD_START', FRIEND_FILE_UPLOAD_START_RSP: 'FRIEND_FILE_UPLOAD_START_RSP',
  FRIEND_RECALL_REQ: 'FRIEND_RECALL_REQ', FRIEND_RECALL_RSP: 'FRIEND_RECALL_RSP', FRIEND_RECALL_NOTIFY: 'FRIEND_RECALL_NOTIFY',
  MARK_ROOM_READ: 'MARK_ROOM_READ', MARK_FRIEND_READ: 'MARK_FRIEND_READ',
}

function uuid() {
  return crypto.randomUUID ? crypto.randomUUID() : (Date.now().toString(36) + Math.random().toString(36).slice(2))
}

export function makeMessage(type, data = {}) {
  return { type, id: uuid(), timestamp: Date.now(), data }
}

class ChatWebSocket {
  constructor() {
    this.ws = null
    this.handlers = new Map()
    this.heartbeatTimer = null
    this.heartbeatTimeout = null
    this.reconnectTimer = null
    this.reconnectCount = 0
    this.url = ''
    this.connected = ref(false)
    this.autoReconnect = true
  }

  // 连接服务器
  // host: 服务器地址, port: WebSocket端口, path: 路径(用于Nginx反向代理, 如 '/ws')
  connect(host = '127.0.0.1', port = DEFAULT_PORT, path = '') {
    // 自动检测协议 (https -> wss, http -> ws)
    const protocol = (typeof location !== 'undefined' && location.protocol === 'https:') ? 'wss' : 'ws'
    if (path) {
      // 通过 Nginx 反向代理 (path 模式, 如 ws://host/ws)
      this.url = `${protocol}://${host}${port && port !== 80 && port !== 443 ? ':' + port : ''}${path}`
    } else {
      // 直连 WebSocket 端口
      this.url = `${protocol}://${host}:${port}`
    }
    this.autoReconnect = true
    this.reconnectCount = 0
    this._doConnect()
  }

  _doConnect() {
    if (this.ws) {
      this.ws.onclose = null
      this.ws.close()
    }
    this.ws = new WebSocket(this.url)
    this.ws.onopen = () => {
      this.connected.value = true
      this.reconnectCount = 0
      this._startHeartbeat()
      this._emit('connected')
    }
    this.ws.onmessage = (ev) => {
      this._resetHeartbeatTimeout()
      try {
        const msg = JSON.parse(ev.data)
        this._handleMessage(msg)
      } catch (e) {
        console.error('[WS] 消息解析失败:', e)
      }
    }
    this.ws.onclose = () => {
      this.connected.value = false
      this._stopHeartbeat()
      this._emit('disconnected')
      if (this.autoReconnect && this.reconnectCount < MAX_RECONNECT) {
        this.reconnectCount++
        this.reconnectTimer = setTimeout(() => this._doConnect(), RECONNECT_INTERVAL)
      }
    }
    this.ws.onerror = (e) => {
      console.error('[WS] 错误:', e)
    }
  }

  disconnect() {
    this.autoReconnect = false
    clearTimeout(this.reconnectTimer)
    this._stopHeartbeat()
    if (this.ws) {
      this.ws.onclose = null
      this.ws.close()
      this.ws = null
    }
    this.connected.value = false
  }

  // 发送消息
  send(msg) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg))
    }
  }

  // 注册消息处理器
  on(type, handler) {
    if (!this.handlers.has(type)) this.handlers.set(type, [])
    this.handlers.get(type).push(handler)
  }

  off(type, handler) {
    const list = this.handlers.get(type)
    if (list) {
      const idx = list.indexOf(handler)
      if (idx >= 0) list.splice(idx, 1)
    }
  }

  _emit(type, data) {
    const list = this.handlers.get(type)
    if (list) list.forEach(fn => fn(data))
  }

  _handleMessage(msg) {
    const type = msg.type
    if (type === MsgType.HEARTBEAT) {
      this.send(makeMessage(MsgType.HEARTBEAT_ACK))
      return
    }
    if (type === MsgType.HEARTBEAT_ACK) return
    // 被顶号时立即禁止自动重连
    if (type === MsgType.FORCE_OFFLINE) {
      this.autoReconnect = false
      clearTimeout(this.reconnectTimer)
    }
    this._emit(type, msg)
  }

  // 心跳
  _startHeartbeat() {
    this._stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      this.send(makeMessage(MsgType.HEARTBEAT))
    }, HEARTBEAT_INTERVAL)
  }

  _resetHeartbeatTimeout() {
    clearTimeout(this.heartbeatTimeout)
    this.heartbeatTimeout = setTimeout(() => {
      console.warn('[WS] 心跳超时')
      if (this.ws) this.ws.close()
    }, HEARTBEAT_TIMEOUT)
  }

  _stopHeartbeat() {
    clearInterval(this.heartbeatTimer)
    clearTimeout(this.heartbeatTimeout)
  }

  // ==================== 便捷方法 ====================

  login(username, password) {
    this.send(makeMessage(MsgType.LOGIN_REQ, { username, password }))
  }

  register(username, displayName, password) {
    this.send(makeMessage(MsgType.REGISTER_REQ, { username, displayName, password }))
  }

  logout() {
    this.send(makeMessage(MsgType.LOGOUT))
    this.disconnect()
  }

  sendChat(roomId, sender, content, contentType = 'text') {
    this.send(makeMessage(MsgType.CHAT_MSG, { roomId, sender, content, contentType }))
  }

  createRoom(roomName, password = '') {
    const data = { roomName }
    if (password) data.password = password
    this.send(makeMessage(MsgType.CREATE_ROOM_REQ, data))
  }

  joinRoom(roomId, password = '') {
    const data = { roomId }
    if (password) data.password = password
    this.send(makeMessage(MsgType.JOIN_ROOM_REQ, data))
  }

  leaveRoom(roomId) {
    this.send(makeMessage(MsgType.LEAVE_ROOM, { roomId }))
  }

  requestRoomList() {
    this.send(makeMessage(MsgType.ROOM_LIST_REQ))
  }

  requestUserList(roomId) {
    this.send(makeMessage(MsgType.USER_LIST_REQ, { roomId }))
  }

  requestHistory(roomId, count = 50, before = 0) {
    const data = { roomId, count }
    if (before > 0) data.before = before
    this.send(makeMessage(MsgType.HISTORY_REQ, data))
  }

  recallMessage(messageId, roomId) {
    this.send(makeMessage(MsgType.RECALL_REQ, { messageId, roomId }))
  }

  recallFriendMessage(messageId, friendUsername) {
    this.send(makeMessage(MsgType.FRIEND_RECALL_REQ, { messageId, friendUsername }))
  }

  // 小文件直传
  sendFile(roomId, sender, fileName, fileSize, fileData, contentType = 'file', thumbnail = '') {
    const data = {
      roomId, sender, fileName, fileSize, fileData, contentType
    }
    if (thumbnail) data.thumbnail = thumbnail
    this.send(makeMessage(MsgType.FILE_SEND, data))
  }

  downloadFile(fileId) {
    this.send(makeMessage(MsgType.FILE_DOWNLOAD_REQ, { fileId }))
  }

  // 大文件分块上传
  startUpload(roomId, fileName, fileSize, contentType = 'file') {
    this.send(makeMessage(MsgType.FILE_UPLOAD_START, { roomId, fileName, fileSize, contentType }))
  }

  sendChunk(uploadId, chunkIndex, chunkData) {
    this.send(makeMessage(MsgType.FILE_UPLOAD_CHUNK, { uploadId, chunkIndex, chunkData }))
  }

  endUpload(uploadId, thumbnail = '') {
    const data = { uploadId }
    if (thumbnail) data.thumbnail = thumbnail
    this.send(makeMessage(MsgType.FILE_UPLOAD_END, data))
  }

  cancelUpload(uploadId) {
    this.send(makeMessage(MsgType.FILE_UPLOAD_CANCEL, { uploadId }))
  }

  downloadChunk(fileId, offset, chunkSize = FILE_CHUNK_SIZE) {
    this.send(makeMessage(MsgType.FILE_DOWNLOAD_CHUNK_REQ, { fileId, offset, chunkSize }))
  }

  // 管理员
  setAdmin(roomId, username, isAdmin) {
    this.send(makeMessage(MsgType.SET_ADMIN_REQ, { roomId, username, isAdmin }))
  }

  kickUser(roomId, username) {
    this.send(makeMessage(MsgType.KICK_USER_REQ, { roomId, username }))
  }

  deleteMessages(roomId, mode, messageIds = [], timestamp = 0) {
    const data = { roomId, mode }
    if (messageIds.length) data.messageIds = messageIds
    if (timestamp) data.timestamp = timestamp
    this.send(makeMessage(MsgType.DELETE_MSGS_REQ, data))
  }

  // 房间设置
  requestRoomSettings(roomId) {
    this.send(makeMessage(MsgType.ROOM_SETTINGS_REQ, { roomId }))
  }

  setRoomSettings(roomId, maxFileSize, totalFileSpace, maxFileCount, maxMembers, forceCleanup = false) {
    this.send(makeMessage(MsgType.ROOM_SETTINGS_REQ, {
      roomId, maxFileSize, totalFileSpace, maxFileCount, maxMembers, forceCleanup
    }))
  }

  deleteRoom(roomId, roomName) {
    this.send(makeMessage(MsgType.DELETE_ROOM_REQ, { roomId, roomName }))
  }

  renameRoom(roomId, newName) {
    this.send(makeMessage(MsgType.RENAME_ROOM_REQ, { roomId, newName }))
  }

  setRoomPassword(roomId, password) {
    this.send(makeMessage(MsgType.SET_ROOM_PASSWORD_REQ, { roomId, password }))
  }

  getRoomPassword(roomId) {
    this.send(makeMessage(MsgType.GET_ROOM_PASSWORD_REQ, { roomId }))
  }

  // 头像
  uploadAvatar(avatarData) {
    this.send(makeMessage(MsgType.AVATAR_UPLOAD_REQ, { avatarData }))
  }

  getAvatar(username) {
    this.send(makeMessage(MsgType.AVATAR_GET_REQ, { username }))
  }

  // 个人信息
  changeNickname(displayName) {
    this.send(makeMessage(MsgType.CHANGE_NICKNAME_REQ, { displayName }))
  }

  changeUid(newUid) {
    this.send(makeMessage(MsgType.CHANGE_UID_REQ, { newUid }))
  }

  changePassword(oldPassword, newPassword) {
    this.send(makeMessage(MsgType.CHANGE_PASSWORD_REQ, { oldPassword, newPassword }))
  }

  // ==================== 好友系统 ====================

  searchUsers(keyword) {
    this.send(makeMessage(MsgType.USER_SEARCH_REQ, { keyword }))
  }

  searchRooms(keyword) {
    this.send(makeMessage(MsgType.ROOM_SEARCH_REQ, { keyword }))
  }

  uploadRoomAvatar(roomId, avatarData) {
    this.send(makeMessage(MsgType.ROOM_AVATAR_UPLOAD_REQ, { roomId, avatarData }))
  }

  getRoomAvatar(roomId) {
    this.send(makeMessage(MsgType.ROOM_AVATAR_GET_REQ, { roomId }))
  }

  sendFriendRequest(username) {
    this.send(makeMessage(MsgType.FRIEND_REQUEST_REQ, { username }))
  }

  acceptFriendRequest(requestId, fromUsername) {
    this.send(makeMessage(MsgType.FRIEND_ACCEPT_REQ, { requestId, fromUsername }))
  }

  rejectFriendRequest(requestId) {
    this.send(makeMessage(MsgType.FRIEND_REJECT_REQ, { requestId }))
  }

  removeFriend(username) {
    this.send(makeMessage(MsgType.FRIEND_REMOVE_REQ, { username }))
  }

  requestFriendList() {
    this.send(makeMessage(MsgType.FRIEND_LIST_REQ))
  }

  requestFriendPending() {
    this.send(makeMessage(MsgType.FRIEND_PENDING_REQ))
  }

  sendFriendChat(friendUsername, content, contentType = 'text') {
    this.send(makeMessage(MsgType.FRIEND_CHAT_MSG, { friendUsername, content, contentType }))
  }

  requestFriendHistory(friendUsername, count = 50, before = 0) {
    const data = { friendUsername, count }
    if (before > 0) data.before = before
    this.send(makeMessage(MsgType.FRIEND_HISTORY_REQ, data))
  }

  sendFriendFile(friendUsername, fileName, fileSize, fileData, contentType = 'file', thumbnail = '') {
    const data = { friendUsername, fileName, fileSize, fileData, contentType }
    if (thumbnail) data.thumbnail = thumbnail
    this.send(makeMessage(MsgType.FRIEND_FILE_SEND, data))
  }

  startFriendUpload(friendUsername, fileName, fileSize) {
    this.send(makeMessage(MsgType.FRIEND_FILE_UPLOAD_START, { friendUsername, fileName, fileSize }))
  }
}

let _httpBaseUrl = ''
let _fileToken = ''

function normalizeHost(host) {
  if (!host) return ''
  if (host.startsWith('http://') || host.startsWith('https://')) return host
  const protocol = (typeof location !== 'undefined' && location.protocol === 'https:') ? 'https' : 'http'
  return `${protocol}://${host}`
}

function setHttpConfig(host, httpPort, token = '') {
  const normalized = normalizeHost(host)
  if (!normalized || !httpPort) {
    _httpBaseUrl = ''
    _fileToken = token || ''
    return
  }
  const url = new URL(normalized)
  url.port = String(httpPort)
  _httpBaseUrl = url.origin
  _fileToken = token || ''
}

function getHttpDownloadUrl(fileId, isFriendFile = false, disposition = 'attachment') {
  if (!_httpBaseUrl || !_fileToken || fileId == null) return ''
  const friend = isFriendFile || Number(fileId) < 0 ? '1' : '0'
  const disp = disposition === 'inline' ? 'inline' : 'attachment'
  return `${_httpBaseUrl}/api/download/${fileId}?token=${encodeURIComponent(_fileToken)}&friend=${friend}&disposition=${disp}`
}

function getHttpBaseUrl() {
  return _httpBaseUrl
}

function getFileToken() {
  return _fileToken
}

// 单例
export const chatWs = new ChatWebSocket()
export { FILE_CHUNK_SIZE, MAX_SMALL_FILE, setHttpConfig, getHttpDownloadUrl, getHttpBaseUrl, getFileToken }
