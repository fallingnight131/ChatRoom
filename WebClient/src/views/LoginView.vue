<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <div class="login-logo">💬</div>
        <h1>ChatRoom</h1>
        <p>{{ isRegister ? '注册新账号' : '登录到聊天室' }}</p>
      </div>

      <!-- 服务器设置 -->
      <div class="server-config" v-if="showServerConfig">
        <div class="input-group">
          <label>服务器地址</label>
          <input class="input" v-model="serverHost" placeholder="127.0.0.1" />
        </div>
        <div class="input-group">
          <label>WebSocket 端口</label>
          <input class="input" v-model.number="serverPort" type="number" placeholder="9528" />
        </div>
      </div>

      <div class="input-group">
        <label>用户ID (唯一标识)</label>
        <input class="input" v-model="username" placeholder="输入唯一用户ID"
               @keyup.enter="isRegister ? null : doLogin()" />
      </div>

      <div class="input-group" v-if="isRegister">
        <label>昵称</label>
        <input class="input" v-model="displayName" placeholder="输入显示昵称" />
      </div>

      <div class="input-group">
        <label>密码</label>
        <input class="input" v-model="password" type="password" placeholder="输入密码"
               @keyup.enter="isRegister ? doRegister() : doLogin()" />
      </div>

      <div class="input-group" v-if="isRegister">
        <label>确认密码</label>
        <input class="input" v-model="confirmPassword" type="password" placeholder="再次输入密码"
               @keyup.enter="doRegister()" />
      </div>

      <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

      <button class="btn btn-primary login-btn" @click="isRegister ? doRegister() : doLogin()"
              :disabled="loading">
        {{ loading ? '连接中...' : (isRegister ? '注册' : '登录') }}
      </button>

      <div class="login-footer">
        <span @click="isRegister = !isRegister" class="switch-link">
          {{ isRegister ? '已有账号？去登录' : '没有账号？注册' }}
        </span>
        <span @click="showServerConfig = !showServerConfig" class="config-link">
          ⚙ 服务器设置
        </span>
      </div>

      <!-- 主题切换 -->
      <button class="btn-icon theme-toggle" @click="userStore.toggleDarkMode()">
        {{ userStore.darkMode ? '☀️' : '🌙' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType } from '../services/websocket'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

const username = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const isRegister = ref(false)
const loading = ref(false)
const errorMsg = ref('')
const showServerConfig = ref(false)
const serverHost = ref(userStore.serverHost)
const serverPort = ref(userStore.serverPort)

function doLogin() {
  if (!username.value || !password.value) {
    errorMsg.value = '请输入用户ID和密码'
    return
  }
  errorMsg.value = ''
  loading.value = true
  userStore.setServer(serverHost.value, serverPort.value)
  chatWs.connect(serverHost.value, serverPort.value)
}

function doRegister() {
  if (!username.value || !displayName.value || !password.value) {
    errorMsg.value = '请填写所有字段'
    return
  }
  if (password.value !== confirmPassword.value) {
    errorMsg.value = '两次密码不一致'
    return
  }
  errorMsg.value = ''
  loading.value = true
  userStore.setServer(serverHost.value, serverPort.value)
  chatWs.connect(serverHost.value, serverPort.value)
}

// WebSocket 事件处理
function onConnected() {
  if (isRegister.value) {
    chatWs.register(username.value, displayName.value, password.value)
  } else {
    chatWs.login(username.value, password.value)
  }
}

function onDisconnected() {
  if (loading.value) {
    loading.value = false
    errorMsg.value = '无法连接到服务器'
  }
}

function onLoginRsp(msg) {
  loading.value = false
  if (msg.data.success) {
    userStore.onLoginSuccess(msg.data)
    // 初始化消息监听
    userStore.initListeners()
    chatStore.initListeners()
    // 请求头像和房间列表
    chatWs.getAvatar(msg.data.username)
    chatWs.requestRoomList()
    router.push('/chat')
  } else {
    errorMsg.value = msg.data.error || '登录失败'
  }
}

function onRegisterRsp(msg) {
  loading.value = false
  if (msg.data.success) {
    // 注册成功，自动登录
    isRegister.value = false
    errorMsg.value = ''
    chatWs.login(username.value, password.value)
    loading.value = true
  } else {
    errorMsg.value = msg.data.error || '注册失败'
  }
}

onMounted(() => {
  chatWs.on('connected', onConnected)
  chatWs.on('disconnected', onDisconnected)
  chatWs.on(MsgType.LOGIN_RSP, onLoginRsp)
  chatWs.on(MsgType.REGISTER_RSP, onRegisterRsp)
})

onUnmounted(() => {
  chatWs.off('connected', onConnected)
  chatWs.off('disconnected', onDisconnected)
  chatWs.off(MsgType.LOGIN_RSP, onLoginRsp)
  chatWs.off(MsgType.REGISTER_RSP, onRegisterRsp)
})
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
}
.login-card {
  background: var(--bg-secondary);
  border-radius: 16px;
  padding: 40px;
  width: 400px;
  box-shadow: var(--shadow-lg);
  position: relative;
}
.login-header {
  text-align: center;
  margin-bottom: 32px;
}
.login-logo {
  font-size: 48px;
  margin-bottom: 8px;
}
.login-header h1 {
  font-size: 24px;
  color: var(--text-primary);
  margin-bottom: 4px;
}
.login-header p {
  color: var(--text-secondary);
  font-size: 14px;
}
.login-btn {
  width: 100%;
  padding: 10px;
  font-size: 16px;
  margin-top: 8px;
}
.login-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
}
.switch-link, .config-link {
  color: var(--text-link);
  cursor: pointer;
  font-size: 13px;
}
.switch-link:hover, .config-link:hover {
  text-decoration: underline;
}
.error-msg {
  color: var(--danger);
  font-size: 13px;
  margin-bottom: 8px;
  text-align: center;
}
.theme-toggle {
  position: absolute;
  top: 12px;
  right: 12px;
  font-size: 20px;
}
.server-config {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 16px;
  background: var(--bg-primary);
}

/* ========== 移动端适配 ========== */
@media (max-width: 768px) {
  .login-card {
    width: 100%;
    max-width: 100%;
    border-radius: 0;
    padding: 32px 20px;
    box-shadow: none;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    justify-content: center;
  }
  .login-page {
    align-items: stretch;
  }
  .login-header {
    margin-bottom: 24px;
  }
  .login-header h1 {
    font-size: 22px;
  }
  .login-btn {
    padding: 12px;
    font-size: 16px;
    border-radius: 10px;
  }
  .login-footer {
    flex-direction: column;
    align-items: center;
    gap: 12px;
    margin-top: 20px;
  }
  .switch-link, .config-link {
    font-size: 15px;
    padding: 4px;
  }
  .theme-toggle {
    top: max(12px, env(safe-area-inset-top));
  }
}

@media (max-width: 480px) {
  .login-card {
    padding: 24px 16px;
  }
  .login-logo {
    font-size: 40px;
  }
}
</style>
