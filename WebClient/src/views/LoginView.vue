<template>
  <main class="login-page">
    <form class="login-card" aria-labelledby="login-title"
          @submit.prevent="isRegister ? doRegister() : doLogin()">
      <div class="login-header">
        <div class="login-logo" aria-hidden="true">💬</div>
        <h1 id="login-title">ChatRoom</h1>
        <p>{{ isRegister ? messages.registerSubtitle : messages.loginSubtitle }}</p>
        <label class="visually-hidden" for="login-locale">{{ messages.language }}</label>
        <select id="login-locale" class="locale-select" :value="userStore.locale"
                :aria-label="messages.language" @change="userStore.setLocale($event.target.value)">
          <option value="zh-CN">简体中文</option>
          <option value="en-US">English</option>
        </select>
      </div>

      <div class="input-group">
        <label for="login-username">{{ messages.userId }}</label>
        <input id="login-username" class="input" v-model="username" :placeholder="messages.userIdPlaceholder"
               autocomplete="username" :aria-describedby="errorMsg ? 'login-error' : undefined" />
      </div>

      <div class="input-group" v-if="isRegister">
        <label for="register-display-name">{{ messages.displayName }}</label>
        <input id="register-display-name" class="input" v-model="displayName"
               :placeholder="messages.displayNamePlaceholder" autocomplete="nickname" />
      </div>

      <div class="input-group">
        <label for="login-password">{{ messages.password }}</label>
        <input id="login-password" class="input" v-model="password" type="password" :placeholder="messages.passwordPlaceholder"
               :autocomplete="isRegister ? 'new-password' : 'current-password'"
               :aria-describedby="errorMsg ? 'login-error' : undefined" />
      </div>

      <div class="input-group" v-if="isRegister">
        <label for="register-password-confirmation">{{ messages.confirmPassword }}</label>
        <input id="register-password-confirmation" class="input" v-model="confirmPassword"
               type="password" :placeholder="messages.confirmPasswordPlaceholder" autocomplete="new-password" />
      </div>

      <div v-if="errorMsg" id="login-error" class="error-msg" role="alert" aria-live="assertive">
        {{ errorMsg }}
      </div>

      <button type="submit" class="btn btn-primary login-btn" :disabled="loading">
        {{ loading ? messages.connecting : (isRegister ? messages.register : messages.login) }}
      </button>

      <div class="login-footer">
        <button type="button" @click="isRegister = !isRegister" class="switch-link">
          {{ isRegister ? messages.switchToLogin : messages.switchToRegister }}
        </button>
        <router-link v-if="v2PreviewEnabled" to="/preview/v2" class="switch-link">
          {{ messages.v2Preview }}
        </router-link>
      </div>

      <!-- 主题切换 -->
      <button type="button" class="btn-icon theme-toggle"
              :aria-label="userStore.darkMode ? messages.lightTheme : messages.darkTheme"
              @click="userStore.toggleDarkMode()">
        {{ userStore.darkMode ? '☀️' : '🌙' }}
      </button>
    </form>
  </main>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType } from '../services/websocket'
import { loginMessages } from '../localization/webLocale'

const router = useRouter()
const userStore = useUserStore()
const chatStore = useChatStore()

const username = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const isRegister = ref(false)
const loading = ref(false)
const v2PreviewEnabled = import.meta.env.VITE_CHAT_V2_PREVIEW === 'true'
const messages = computed(() => loginMessages(userStore.locale))
const localErrorKey = ref('')
const remoteError = ref('')
const errorMsg = computed(() => localErrorKey.value
  ? messages.value[localErrorKey.value]
  : remoteError.value)

function doLogin() {
  if (!username.value || !password.value) {
    setLocalError('loginRequired')
    return
  }
  if (userStore.endpointPolicyError) {
    setRemoteError(userStore.endpointPolicyError)
    return
  }
  clearError()
  loading.value = true
  chatWs.connectUrl(userStore.websocketUrl)
}

function doRegister() {
  if (!username.value || !displayName.value || !password.value) {
    setLocalError('registerRequired')
    return
  }
  if (password.value !== confirmPassword.value) {
    setLocalError('passwordMismatch')
    return
  }
  if (userStore.endpointPolicyError) {
    setRemoteError(userStore.endpointPolicyError)
    return
  }
  clearError()
  loading.value = true
  chatWs.connectUrl(userStore.websocketUrl)
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
    setLocalError('connectionFailed')
  }
}

function onOffline() {
  // No authenticated session exists on this screen; recovery remains an
  // explicit user action and must not retain an automatic login attempt.
  chatWs.disconnect()
  loading.value = false
  setLocalError('offline')
}

function onOnline() {
  if (localErrorKey.value === 'offline') {
    setLocalError('onlineAgain')
  }
}

function onLoginRsp(msg) {
  loading.value = false
  if (msg.data.success) {
    userStore.onLoginSuccess(msg.data)
    userStore.setSessionCredentials(username.value, password.value)
    password.value = ''
    confirmPassword.value = ''
    // 初始化消息监听
    userStore.initListeners()
    chatStore.initListeners()
    chatStore.beginAttachmentSession(msg.data.username)
    // 请求头像和房间列表
    userStore.requestAvatarIfAllowed(msg.data.username)
    chatWs.requestRoomList()
    chatWs.requestFriendList()
    router.push('/chat')
  } else {
    userStore.clearSessionCredentials()
    if (msg.data.error) setRemoteError(msg.data.error)
    else setLocalError('loginFailed')
  }
}

function onRegisterRsp(msg) {
  loading.value = false
  if (msg.data.success) {
    // 注册成功，自动登录
    isRegister.value = false
    clearError()
    chatWs.login(username.value, password.value)
    loading.value = true
  } else {
    if (msg.data.error) setRemoteError(msg.data.error)
    else setLocalError('registerFailed')
  }
}

function clearError() {
  localErrorKey.value = ''
  remoteError.value = ''
}

function setLocalError(key) {
  localErrorKey.value = key
  remoteError.value = ''
}

function setRemoteError(message) {
  localErrorKey.value = ''
  remoteError.value = message
}

onMounted(() => {
  chatWs.on('connected', onConnected)
  chatWs.on('disconnected', onDisconnected)
  chatWs.on('offline', onOffline)
  chatWs.on('online', onOnline)
  chatWs.on(MsgType.LOGIN_RSP, onLoginRsp)
  chatWs.on(MsgType.REGISTER_RSP, onRegisterRsp)
})

onUnmounted(() => {
  chatWs.off('connected', onConnected)
  chatWs.off('disconnected', onDisconnected)
  chatWs.off('offline', onOffline)
  chatWs.off('online', onOnline)
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
.locale-select {
  margin-top: 12px;
  padding: 6px 28px 6px 10px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-primary);
  background: var(--bg-primary);
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
.switch-link {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 0;
  font: inherit;
  color: var(--text-link);
  cursor: pointer;
  font-size: 13px;
}
.switch-link:hover {
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
  .switch-link {
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
