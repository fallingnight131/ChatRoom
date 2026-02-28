// 用户状态管理
import { defineStore } from 'pinia'
import { chatWs, MsgType } from '../services/websocket'

export const useUserStore = defineStore('user', {
  state: () => ({
    loggedIn: false,
    userId: 0,
    username: '',      // 唯一 ID
    displayName: '',   // 昵称
    avatarData: '',    // base64
    darkMode: localStorage.getItem('darkMode') !== 'false',
    serverHost: localStorage.getItem('serverHost') || 'fallingnight.cn',
    serverPort: parseInt(localStorage.getItem('serverPort') || '443'),
    wsPath: localStorage.getItem('wsPath') || '/ws',
    // 缓存其他用户头像
    avatarCache: {},   // username -> base64
  }),

  actions: {
    setServer(host, port, path = '') {
      this.serverHost = host
      this.serverPort = port
      this.wsPath = path
      localStorage.setItem('serverHost', host)
      localStorage.setItem('serverPort', String(port))
      localStorage.setItem('wsPath', path)
    },

    toggleDarkMode() {
      this.darkMode = !this.darkMode
      localStorage.setItem('darkMode', String(this.darkMode))
    },

    onLoginSuccess(data) {
      this.loggedIn = true
      this.userId = data.userId
      this.username = data.username
      this.displayName = data.displayName || data.username
      sessionStorage.setItem('user', JSON.stringify({
        userId: this.userId,
        username: this.username,
        displayName: this.displayName
      }))
    },

    // 保存登录凭证（用于刷新自动重连）
    saveCredentials(username, password) {
      sessionStorage.setItem('credentials', JSON.stringify({ username, password }))
    },

    getCredentials() {
      try {
        return JSON.parse(sessionStorage.getItem('credentials') || 'null')
      } catch { return null }
    },

    clearCredentials() {
      sessionStorage.removeItem('credentials')
    },

    onLogout() {
      this.loggedIn = false
      this.userId = 0
      this.username = ''
      this.displayName = ''
      this.avatarData = ''
      sessionStorage.removeItem('user')
      sessionStorage.removeItem('credentials')
    },

    setAvatar(data) {
      this.avatarData = data
    },

    cacheAvatar(username, data) {
      this.avatarCache[username] = data
    },

    getAvatar(username) {
      return this.avatarCache[username] || ''
    },

    updateDisplayName(name) {
      this.displayName = name
      const u = JSON.parse(sessionStorage.getItem('user') || '{}')
      u.displayName = name
      sessionStorage.setItem('user', JSON.stringify(u))
    },

    updateUsername(newUid) {
      // UID修改后更新
      const oldUid = this.username
      this.username = newUid
      const u = JSON.parse(sessionStorage.getItem('user') || '{}')
      u.username = newUid
      sessionStorage.setItem('user', JSON.stringify(u))
      // 迁移头像缓存
      if (this.avatarCache[oldUid]) {
        this.avatarCache[newUid] = this.avatarCache[oldUid]
        delete this.avatarCache[oldUid]
      }
    },

    // 初始化 WebSocket 消息监听
    initListeners() {
      chatWs.on(MsgType.FORCE_OFFLINE, (msg) => {
        alert(msg.data.reason || '您已被强制下线')
        this.onLogout()
        chatWs.disconnect()
        window.location.hash = '#/login'
      })

      chatWs.on(MsgType.AVATAR_GET_RSP, (msg) => {
        const d = msg.data
        if (d.success && d.avatarData) {
          this.cacheAvatar(d.username, d.avatarData)
          if (d.username === this.username) {
            this.avatarData = d.avatarData
          }
        }
      })

      chatWs.on(MsgType.AVATAR_UPLOAD_RSP, (msg) => {
        if (!msg.data.success) {
          alert('头像上传失败: ' + (msg.data.error || ''))
        }
      })

      chatWs.on(MsgType.AVATAR_UPDATE_NOTIFY, (msg) => {
        const d = msg.data
        this.cacheAvatar(d.username, d.avatarData)
        if (d.username === this.username) {
          this.avatarData = d.avatarData
        }
      })

      chatWs.on(MsgType.CHANGE_NICKNAME_RSP, (msg) => {
        if (msg.data.success) {
          this.updateDisplayName(msg.data.displayName)
        }
      })

      chatWs.on(MsgType.CHANGE_UID_RSP, (msg) => {
        if (msg.data.success) {
          this.updateUsername(msg.data.newUid)
        }
      })

      chatWs.on(MsgType.CHANGE_PASSWORD_RSP, (msg) => {
        if (msg.data.success) {
          alert('密码修改成功')
        } else {
          alert('密码修改失败: ' + (msg.data.error || ''))
        }
      })
    }
  }
})
