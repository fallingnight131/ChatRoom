<template>
  <main class="v2-preview">
    <header class="preview-header">
      <div>
        <span class="preview-badge">V2 工程预览</span>
        <h1>ChatRoom V2</h1>
      </div>
      <div class="connection" role="status" aria-live="polite">
        <span :class="['status-dot', connectionTone]"></span>
        {{ connectionLabel }}
      </div>
    </header>

    <section v-if="!runtimeReady" class="state-card" aria-live="polite">
      <h2>正在加载安全连接组件</h2>
      <p>{{ runtimeReason }}</p>
      <router-link class="btn btn-secondary" to="/login">返回 V1 登录</router-link>
    </section>

    <section v-else-if="!snapshot.session" class="login-shell" aria-labelledby="v2-login-title">
      <form class="login-card" @submit.prevent="login">
        <span class="preview-badge">独立测试环境</span>
        <h2 id="v2-login-title">登录 V2</h2>
        <p class="muted">凭据仅用于本次认证请求，不写入浏览器存储。</p>

        <label for="v2-username">用户 ID</label>
        <input id="v2-username" v-model.trim="username" class="input" maxlength="128"
               autocomplete="username" required :disabled="authenticating" />

        <label for="v2-password">密码</label>
        <input id="v2-password" v-model="password" class="input" type="password"
               maxlength="1024" autocomplete="current-password" required
               :disabled="authenticating" />

        <p v-if="visibleFailure" class="error-msg" role="alert">{{ visibleFailure }}</p>
        <button class="btn btn-primary" type="submit" :disabled="!canAuthenticate">
          {{ authenticating ? '正在验证…' : connectionReady ? '登录' : '正在建立安全连接…' }}
        </button>
        <router-link class="back-link" to="/login">返回稳定版 V1</router-link>
      </form>
    </section>

    <section v-else class="chat-shell">
      <aside class="conversation-panel" aria-label="会话列表">
        <div class="account-block">
          <strong>{{ snapshot.session.displayName }}</strong>
          <span>{{ snapshot.session.accountId }}</span>
          <button class="device-entry" type="button" @click="openDevices">
            登录设备
            <span v-if="snapshot.devices.length">{{ snapshot.devices.length }}</span>
          </button>
        </div>
        <button v-for="conversation in snapshot.directory" :key="conversation.conversationId"
                :class="['conversation-button', { active: conversation.conversationId === snapshot.activeConversationId }]"
                type="button" @click="openConversation(conversation.conversationId)">
          <strong>{{ conversation.displayName }}</strong>
          <span>{{ conversation.kind === 'direct' ? '私聊' : '群聊' }} · #{{ conversation.latestSequence }}</span>
        </button>
        <p v-if="snapshot.directory.length === 0" class="empty-copy">当前没有可用会话</p>
        <button v-if="snapshot.directoryHasMore" class="btn btn-text" type="button" @click="loadMoreDirectory">
          加载更多会话
        </button>
      </aside>

      <section class="message-panel" aria-label="消息区域">
        <div v-if="!snapshot.activeConversationId" class="empty-state">
          <h2>选择一个会话</h2>
          <p>缓存消息会先显示，然后按服务器序列增量同步。</p>
        </div>
        <template v-else>
          <div class="message-header">
            <strong>{{ activeConversationName }}</strong>
            <span v-if="snapshot.historyLoading">同步中…</span>
          </div>
          <ol class="message-list" role="log" aria-live="polite"
              :aria-busy="snapshot.historyLoading" aria-label="消息记录">
            <li v-for="message in snapshot.messages" :key="message.id || message.clientMessageId"
                :class="['message-row', { mine: message.senderAccountId === snapshot.session.accountId }]">
              <div class="bubble">
                <div v-if="message.reply" class="reply-reference"
                     :aria-label="`回复：${replyPreview(message)}`">
                  <strong>回复</strong>
                  <span>{{ replyPreview(message) }}</span>
                </div>
                <p>{{ message.content }}</p>
                <span>#{{ message.sequence }} · {{ deliveryLabel(message.deliveryState) }}</span>
                <button v-if="message.deliveryState === 'accepted' && message.availability === 'available'"
                        class="reply-link" type="button" @click="startReply(message)">
                  回复
                </button>
                <button v-if="message.deliveryState === 'failed'" class="retry-link" type="button"
                        @click="retryMessage(message.clientMessageId)">
                  重试
                </button>
              </div>
            </li>
          </ol>
          <form class="composer" @submit.prevent="sendMessage">
            <div v-if="replyTarget" class="composer-reply" role="status">
              <div>
                <strong>回复消息 #{{ replyTarget.sequence }}</strong>
                <span>{{ replyTarget.content }}</span>
              </div>
              <button class="icon-button" type="button" aria-label="取消回复" @click="cancelReply">×</button>
            </div>
            <label class="visually-hidden" for="v2-message">输入消息</label>
            <textarea id="v2-message" v-model="draft" class="input" rows="2"
                      placeholder="输入消息" @keydown.enter.exact.prevent="sendMessage"></textarea>
            <button class="btn btn-primary" type="submit" :disabled="!draft.trim()">发送</button>
          </form>
        </template>
        <p v-if="actionError" class="action-error" role="alert">{{ actionError }}</p>
      </section>
    </section>

    <div v-if="devicesOpen" class="dialog-backdrop" @click.self="closeDevices" @keydown.esc="closeDevices">
      <section class="device-dialog" role="dialog" aria-modal="true"
               aria-labelledby="device-dialog-title" aria-describedby="device-dialog-description">
        <header class="device-dialog-header">
          <div>
            <h2 id="device-dialog-title">登录设备</h2>
            <p id="device-dialog-description">发现陌生设备时，可撤销它的全部登录会话。</p>
          </div>
          <button ref="deviceCloseButton" class="icon-button" type="button"
                  aria-label="关闭登录设备" @click="closeDevices">×</button>
        </header>
        <p v-if="snapshot.deviceFailure" class="error-msg" role="alert">
          {{ snapshot.deviceFailure }}
          <button class="retry-link" type="button" :disabled="!canManageDevices" @click="refreshDevices">重试</button>
        </p>
        <p v-if="!canManageDevices" class="device-notice" role="status">连接恢复后才能管理设备。</p>
        <ul class="device-list" :aria-busy="snapshot.devicesLoading">
          <li v-for="device in snapshot.devices" :key="device.deviceId" class="device-row">
            <div class="device-icon" aria-hidden="true">{{ device.platform === 'windows' ? '▣' : '◎' }}</div>
            <div class="device-copy">
              <strong>{{ device.platform === 'windows' ? 'Windows 客户端' : 'Web 浏览器' }}</strong>
              <span>{{ device.current ? '当前设备' : `最近活动：${formatDeviceTime(device.lastSeenAtEpochMs)}` }}</span>
              <small>{{ shortDeviceId(device.deviceId) }}</small>
            </div>
            <span v-if="device.current" class="current-device">当前</span>
            <button v-else-if="confirmingDeviceId !== device.deviceId" class="btn btn-danger-outline"
                    type="button" :disabled="!canManageDevices || Boolean(snapshot.revokingDeviceId)"
                    @click="confirmingDeviceId = device.deviceId">撤销</button>
            <div v-else class="revoke-confirm" role="group" aria-label="确认撤销设备">
              <span>撤销全部会话？</span>
              <button class="btn btn-danger" type="button"
                      :disabled="snapshot.revokingDeviceId === device.deviceId"
                      @click="revokeDevice(device.deviceId)">
                {{ snapshot.revokingDeviceId === device.deviceId ? '撤销中…' : '确认' }}
              </button>
              <button class="btn btn-text" type="button" :disabled="Boolean(snapshot.revokingDeviceId)"
                      @click="confirmingDeviceId = null">取消</button>
            </div>
          </li>
        </ul>
        <p v-if="snapshot.devicesLoading && snapshot.devices.length === 0" class="empty-copy" role="status">正在加载设备…</p>
        <footer class="device-dialog-footer">
          <button class="btn btn-secondary" type="button" :disabled="!canManageDevices || snapshot.devicesLoading"
                  @click="refreshDevices">刷新</button>
          <button class="btn btn-primary" type="button" @click="closeDevices">完成</button>
        </footer>
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { V2_RUNTIME_KEY } from '../application/v2RuntimeKey'

const runtimeRef = inject(V2_RUNTIME_KEY)
const username = ref('')
const password = ref('')
const draft = ref('')
const actionError = ref('')
const replyTarget = ref(null)
const authenticationPending = ref(false)
const devicesOpen = ref(false)
const confirmingDeviceId = ref(null)
const deviceCloseButton = ref(null)
const snapshot = ref({
  connectionState: 'idle', session: null, directory: [], directoryHasMore: false,
  activeConversationId: null, messages: [], historyLoading: false, devices: [],
  devicesLoading: false, revokingDeviceId: null, deviceFailure: '', lastFailure: ''
})
let unsubscribe = null
let startedApplication = null

const runtimeReady = computed(() => runtimeRef?.value?.enabled === true)
const runtimeReason = computed(() => runtimeRef?.value?.reason || 'V2 预览未启用')
const connectionReady = computed(() => snapshot.value.connectionState === 'connected')
const authenticating = computed(() => authenticationPending.value
  || ['connecting', 'negotiating', 'resuming'].includes(snapshot.value.connectionState))
const canAuthenticate = computed(() => Boolean(connectionReady.value && username.value && password.value && !authenticating.value))
const visibleFailure = computed(() => actionError.value || snapshot.value.lastFailure)
const activeConversationName = computed(() => snapshot.value.directory.find(
  item => item.conversationId === snapshot.value.activeConversationId
)?.displayName || '会话')
const connectionLabel = computed(() => ({
  idle: '尚未连接', connecting: '连接中', negotiating: '协商协议中', connected: '可登录',
  resuming: '恢复会话中', authenticated: '已安全连接', offline: '网络离线',
  'reconnect-wait': '等待重连', stopped: '已停止'
}[snapshot.value.connectionState] || '未知状态'))
const connectionTone = computed(() => snapshot.value.connectionState === 'authenticated'
  ? 'ok' : ['offline', 'reconnect-wait'].includes(snapshot.value.connectionState) ? 'warn' : '')
const canManageDevices = computed(() => snapshot.value.connectionState === 'authenticated')

function attachRuntime(runtime) {
  unsubscribe?.()
  unsubscribe = null
  if (!runtime?.enabled || runtime.application === startedApplication) return
  startedApplication = runtime.application
  unsubscribe = runtime.application.subscribe(next => {
    snapshot.value = next
    if (next.session || next.lastFailure || next.connectionState !== 'connected') authenticationPending.value = false
  })
  runtime.application.start()
}

function login() {
  if (!canAuthenticate.value || !runtimeRef.value.enabled) return
  actionError.value = ''
  const passwordBytes = new TextEncoder().encode(password.value)
  password.value = ''
  authenticationPending.value = true
  try {
    runtimeRef.value.application.authenticate(username.value, passwordBytes)
  } catch (error) {
    authenticationPending.value = false
    actionError.value = error instanceof Error ? error.message : '无法发起认证'
  } finally {
    passwordBytes.fill(0)
  }
}

async function openConversation(conversationId) {
  actionError.value = ''
  replyTarget.value = null
  try { await runtimeRef.value.application.openConversation(conversationId) }
  catch (error) { actionError.value = error instanceof Error ? error.message : '无法打开会话' }
}

function loadMoreDirectory() {
  try { runtimeRef.value.application.loadMoreDirectory() }
  catch (error) { actionError.value = error instanceof Error ? error.message : '无法加载会话' }
}

function sendMessage() {
  const text = draft.value.trim()
  if (!text) return
  actionError.value = ''
  try {
    if (replyTarget.value) {
      runtimeRef.value.application.sendReply(replyTarget.value.id, text)
    } else {
      runtimeRef.value.application.sendText(text)
    }
    draft.value = ''
    replyTarget.value = null
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '消息发送失败'
  }
}

function startReply(message) {
  if (!message?.id || message.deliveryState !== 'accepted' || message.availability !== 'available') return
  replyTarget.value = { id: message.id, sequence: message.sequence, content: message.content }
  nextTick(() => document.getElementById('v2-message')?.focus())
}

function cancelReply() {
  replyTarget.value = null
}

function replyPreview(message) {
  const target = snapshot.value.messages.find(item => item.id === message.reply?.targetMessageId)
  if (!target) return '原消息暂不可用'
  return target.availability === 'recalled' ? '原消息已撤回' : target.content
}

function retryMessage(clientMessageId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.retryMessage(clientMessageId)) actionError.value = '该消息暂时无法重试'
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '消息重试失败'
  }
}

function openDevices() {
  devicesOpen.value = true
  confirmingDeviceId.value = null
  if (snapshot.value.devices.length === 0 && canManageDevices.value) refreshDevices()
  nextTick(() => deviceCloseButton.value?.focus())
}

function closeDevices() {
  devicesOpen.value = false
  confirmingDeviceId.value = null
}

function refreshDevices() {
  actionError.value = ''
  try { runtimeRef.value.application.refreshDevices() }
  catch (error) { actionError.value = error instanceof Error ? error.message : '无法刷新设备' }
}

function revokeDevice(deviceId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.revokeDevice(deviceId)) actionError.value = '当前无法撤销该设备'
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '无法撤销该设备'
  }
}

function shortDeviceId(deviceId) {
  return `${deviceId.slice(0, 8)}…${deviceId.slice(-4)}`
}

function formatDeviceTime(epochMs) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(epochMs)
}

function deliveryLabel(state) {
  return state === 'accepted' ? '已接收' : state === 'sending' ? '发送中' : '发送失败'
}

onMounted(() => attachRuntime(runtimeRef?.value))
const stopRuntimeWatch = watch(() => runtimeRef?.value, attachRuntime)
onUnmounted(() => {
  stopRuntimeWatch()
  unsubscribe?.()
  if (startedApplication) {
    try { startedApplication.stop() } catch { /* page disposal may already own shutdown */ }
  }
})
</script>

<style scoped>
.v2-preview { height: 100%; display: flex; flex-direction: column; background: var(--bg-primary); color: var(--text-primary); }
.preview-header { min-height: 72px; padding: 12px 24px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border-color); background: var(--bg-secondary); }
.preview-header h1 { font-size: 20px; }
.preview-badge { display: inline-block; color: var(--accent); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.connection { display: flex; gap: 8px; align-items: center; color: var(--text-secondary); }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--text-tertiary); }
.status-dot.ok { background: var(--success); }.status-dot.warn { background: var(--warning); }
.state-card, .login-shell { flex: 1; display: grid; place-content: center; gap: 16px; padding: 24px; text-align: center; }
.login-card { width: min(420px, calc(100vw - 32px)); display: grid; gap: 12px; padding: 32px; text-align: left; border-radius: 16px; background: var(--bg-secondary); box-shadow: var(--shadow-lg); }
.login-card h2 { font-size: 24px; }.muted, .empty-copy { color: var(--text-secondary); }.error-msg, .action-error { color: var(--danger); }
.back-link { text-align: center; color: var(--text-link); text-decoration: none; }
.chat-shell { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(220px, 300px) 1fr; }
.conversation-panel { overflow-y: auto; border-right: 1px solid var(--border-color); background: var(--bg-secondary); }
.account-block { display: grid; gap: 4px; padding: 18px; border-bottom: 1px solid var(--border-color); }
.account-block span { overflow: hidden; color: var(--text-secondary); font-size: 11px; text-overflow: ellipsis; }
.device-entry { margin-top: 8px; padding: 7px 9px; display: flex; justify-content: space-between; border: 1px solid var(--border-color); border-radius: 8px; color: var(--text-primary); background: var(--bg-primary); cursor: pointer; }
.device-entry:hover { background: var(--bg-hover); }.device-entry span { font-size: 12px; }
.conversation-button { width: 100%; display: grid; gap: 4px; padding: 14px 18px; border: 0; border-bottom: 1px solid var(--border-light); text-align: left; color: var(--text-primary); background: transparent; cursor: pointer; }
.conversation-button:hover { background: var(--bg-hover); }.conversation-button.active { background: var(--bg-active); }
.reply-reference { margin-bottom: 6px; padding: 6px 8px; display: grid; gap: 2px; border-left: 3px solid var(--accent); border-radius: 4px; color: var(--text-secondary); background: var(--bg-primary); font-size: 12px; }
.reply-reference span, .composer-reply span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.reply-link { margin-left: 8px; border: 0; color: var(--text-link); background: transparent; cursor: pointer; }
.composer-reply { flex: 1 0 100%; padding: 8px 10px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-left: 3px solid var(--accent); border-radius: 6px; background: var(--bg-primary); }
.composer-reply > div { min-width: 0; display: grid; gap: 2px; }
.conversation-button span { color: var(--text-secondary); font-size: 12px; }.empty-copy { padding: 20px; }
.message-panel { min-width: 0; min-height: 0; display: flex; flex-direction: column; position: relative; }
.message-header { padding: 16px 20px; display: flex; justify-content: space-between; border-bottom: 1px solid var(--border-color); background: var(--bg-secondary); }
.message-list { flex: 1; overflow-y: auto; padding: 20px; list-style: none; }
.message-row { display: flex; margin-bottom: 12px; }.message-row.mine { justify-content: flex-end; }
.bubble { max-width: min(70%, 680px); padding: 10px 12px; border-radius: 12px; background: var(--bg-bubble-other); box-shadow: var(--shadow); }
.mine .bubble { background: var(--bg-bubble-mine); }.bubble span { display: inline-block; margin-top: 6px; color: var(--text-secondary); font-size: 11px; }
.bubble .reply-reference span { display: block; margin-top: 0; }
.retry-link { margin-left: 8px; border: 0; color: var(--danger); background: transparent; cursor: pointer; }
.composer { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; padding: 14px 20px; border-top: 1px solid var(--border-color); background: var(--bg-secondary); }
.composer textarea { resize: none; }.empty-state { flex: 1; display: grid; place-content: center; text-align: center; color: var(--text-secondary); }
.action-error { position: absolute; right: 20px; bottom: 86px; padding: 8px 12px; border-radius: 8px; background: var(--bg-secondary); box-shadow: var(--shadow); }
.dialog-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 20px; background: rgb(0 0 0 / 48%); }
.device-dialog { width: min(620px, 100%); max-height: min(720px, calc(100vh - 40px)); display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--border-color); border-radius: 16px; background: var(--bg-secondary); box-shadow: var(--shadow-lg); }
.device-dialog-header { display: flex; justify-content: space-between; gap: 16px; padding: 20px; border-bottom: 1px solid var(--border-color); }.device-dialog-header h2 { font-size: 20px; }.device-dialog-header p { margin-top: 4px; color: var(--text-secondary); font-size: 13px; }
.icon-button { width: 36px; height: 36px; border: 0; border-radius: 8px; color: var(--text-primary); background: transparent; font-size: 24px; cursor: pointer; }.icon-button:hover { background: var(--bg-hover); }
.device-dialog > .error-msg, .device-notice { margin: 14px 20px 0; padding: 10px 12px; border-radius: 8px; background: var(--bg-primary); }.device-notice { color: var(--text-secondary); }
.device-list { overflow-y: auto; padding: 12px 20px; list-style: none; }.device-row { min-height: 76px; display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border-light); }.device-row:last-child { border-bottom: 0; }
.device-icon { width: 40px; height: 40px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 10px; color: var(--accent); background: var(--bg-active); font-size: 20px; }.device-copy { min-width: 0; flex: 1; display: grid; gap: 3px; }.device-copy span, .device-copy small { color: var(--text-secondary); font-size: 12px; }.current-device { padding: 4px 8px; border-radius: 999px; color: var(--success); background: var(--bg-primary); font-size: 12px; }
.revoke-confirm { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 6px; font-size: 12px; }.btn-danger, .btn-danger-outline { padding: 7px 10px; }.btn-danger { color: white; background: var(--danger); }.btn-danger-outline { border: 1px solid var(--danger); color: var(--danger); background: transparent; }
.device-dialog-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--border-color); }
@media (max-width: 720px) { .preview-header { padding: 10px 14px; }.chat-shell { grid-template-columns: 112px 1fr; }.account-block { padding: 12px; }.account-block span { display: none; }.conversation-button { padding: 12px 10px; }.conversation-button span { display: none; }.bubble { max-width: 88%; }.composer { padding: 10px; } }
</style>
