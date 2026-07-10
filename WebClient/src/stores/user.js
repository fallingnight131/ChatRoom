// 用户状态管理
import { defineStore } from 'pinia'
import { chatWs, MsgType, setHttpConfig } from '../services/websocket'
import {
  clearSessionCredentials as clearInMemoryCredentials,
  completeSessionPasswordChange,
  getSessionCredentials as getInMemoryCredentials,
  purgeLegacyPersistedSession,
  setSessionCredentials as setInMemoryCredentials,
  stageSessionPasswordChange
} from '../security/sessionCredentials'

purgeLegacyPersistedSession()

const isLocalDevelopment = typeof location !== 'undefined' &&
  (location.hostname === '127.0.0.1' || location.hostname === 'localhost')
const defaultServer = isLocalDevelopment
  ? { host: location.hostname, port: 9528, path: '' }
  : { host: 'fallingnight.cn', port: 443, path: '/ws' }

export const useUserStore = defineStore('user', {
  state: () => ({
    loggedIn: false,
    userId: 0,
    username: '',      // 唯一 ID
    displayName: '',   // 昵称
    avatarData: '',    // base64
    darkMode: localStorage.getItem('darkMode') !== 'false',
    serverHost: localStorage.getItem('serverHost') || defaultServer.host,
    serverPort: parseInt(localStorage.getItem('serverPort') || String(defaultServer.port)),
    wsPath: localStorage.getItem('wsPath') ?? defaultServer.path,
    // 缓存其他用户头像
    avatarCache: {},   // username -> base64
    forceOfflineReason: '',  // 被顶号时的提示信息
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
      if (data.httpPort && data.fileToken) {
        setHttpConfig(this.serverHost, data.httpPort, data.fileToken)
      }
    },

    // 只保留在当前页面进程内，用于断线重认证；不写入 Web Storage。
    setSessionCredentials(username, password) {
      return setInMemoryCredentials(username, password)
    },

    getSessionCredentials() {
      return getInMemoryCredentials()
    },

    clearSessionCredentials() {
      clearInMemoryCredentials()
    },

    stagePasswordChange(password) {
      return stageSessionPasswordChange(password)
    },

    onLogout() {
      this.loggedIn = false
      this.userId = 0
      this.username = ''
      this.displayName = ''
      this.avatarData = ''
      clearInMemoryCredentials()
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
    },

    updateUsername(newUid) {
      // UID修改后更新
      const oldUid = this.username
      this.username = newUid
      const currentCredentials = getInMemoryCredentials()
      if (currentCredentials) {
        setInMemoryCredentials(newUid, currentCredentials.password)
      }
      // 迁移头像缓存
      if (this.avatarCache[oldUid]) {
        this.avatarCache[newUid] = this.avatarCache[oldUid]
        delete this.avatarCache[oldUid]
      }
    },

    // 初始化 WebSocket 消息监听
    initListeners() {
      if (this._listenersInitialized) return
      this._listenersInitialized = true

      chatWs.on(MsgType.FORCE_OFFLINE, (msg) => {
        this.forceOfflineReason = msg.data.reason || '您已被强制下线'
        chatWs.disconnect()
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
        completeSessionPasswordChange(this.username, msg.data.success === true)
        if (msg.data.success) {
          alert('密码修改成功')
        } else {
          alert('密码修改失败: ' + (msg.data.error || ''))
        }
      })
    }
  }
})
