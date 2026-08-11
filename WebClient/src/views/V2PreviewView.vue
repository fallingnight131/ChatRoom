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
                <p>{{ message.content }}</p>
                <span>#{{ message.sequence }} · {{ deliveryLabel(message.deliveryState) }}</span>
                <button v-if="message.deliveryState === 'failed'" class="retry-link" type="button"
                        @click="retryMessage(message.clientMessageId)">
                  重试
                </button>
              </div>
            </li>
          </ol>
          <form class="composer" @submit.prevent="sendMessage">
            <label class="visually-hidden" for="v2-message">输入消息</label>
            <textarea id="v2-message" v-model="draft" class="input" rows="2"
                      placeholder="输入消息" @keydown.enter.exact.prevent="sendMessage"></textarea>
            <button class="btn btn-primary" type="submit" :disabled="!draft.trim()">发送</button>
          </form>
        </template>
        <p v-if="actionError" class="action-error" role="alert">{{ actionError }}</p>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { V2_RUNTIME_KEY } from '../application/v2RuntimeKey'

const runtimeRef = inject(V2_RUNTIME_KEY)
const username = ref('')
const password = ref('')
const draft = ref('')
const actionError = ref('')
const authenticationPending = ref(false)
const snapshot = ref({
  connectionState: 'idle', session: null, directory: [], directoryHasMore: false,
  activeConversationId: null, messages: [], historyLoading: false, lastFailure: ''
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
    runtimeRef.value.application.sendText(text)
    draft.value = ''
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '消息发送失败'
  }
}

function retryMessage(clientMessageId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.retryMessage(clientMessageId)) actionError.value = '该消息暂时无法重试'
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : '消息重试失败'
  }
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
.conversation-button { width: 100%; display: grid; gap: 4px; padding: 14px 18px; border: 0; border-bottom: 1px solid var(--border-light); text-align: left; color: var(--text-primary); background: transparent; cursor: pointer; }
.conversation-button:hover { background: var(--bg-hover); }.conversation-button.active { background: var(--bg-active); }
.conversation-button span { color: var(--text-secondary); font-size: 12px; }.empty-copy { padding: 20px; }
.message-panel { min-width: 0; min-height: 0; display: flex; flex-direction: column; position: relative; }
.message-header { padding: 16px 20px; display: flex; justify-content: space-between; border-bottom: 1px solid var(--border-color); background: var(--bg-secondary); }
.message-list { flex: 1; overflow-y: auto; padding: 20px; list-style: none; }
.message-row { display: flex; margin-bottom: 12px; }.message-row.mine { justify-content: flex-end; }
.bubble { max-width: min(70%, 680px); padding: 10px 12px; border-radius: 12px; background: var(--bg-bubble-other); box-shadow: var(--shadow); }
.mine .bubble { background: var(--bg-bubble-mine); }.bubble span { display: inline-block; margin-top: 6px; color: var(--text-secondary); font-size: 11px; }
.retry-link { margin-left: 8px; border: 0; color: var(--danger); background: transparent; cursor: pointer; }
.composer { display: flex; gap: 12px; align-items: end; padding: 14px 20px; border-top: 1px solid var(--border-color); background: var(--bg-secondary); }
.composer textarea { resize: none; }.empty-state { flex: 1; display: grid; place-content: center; text-align: center; color: var(--text-secondary); }
.action-error { position: absolute; right: 20px; bottom: 86px; padding: 8px 12px; border-radius: 8px; background: var(--bg-secondary); box-shadow: var(--shadow); }
@media (max-width: 720px) { .preview-header { padding: 10px 14px; }.chat-shell { grid-template-columns: 112px 1fr; }.account-block { padding: 12px; }.account-block span { display: none; }.conversation-button { padding: 12px 10px; }.conversation-button span { display: none; }.bubble { max-width: 88%; }.composer { padding: 10px; } }
</style>
