import { createApp, shallowReadonly, shallowRef } from 'vue'
import App from './App.vue'
import router from './router'
import { pinia } from './stores'
import './assets/style.css'
import { V2_RUNTIME_KEY } from './application/v2RuntimeKey'

const app = createApp(App)
const v2Runtime = shallowRef({ enabled: false, reason: 'V2 preview is disabled', dispose() {} })
app.use(pinia)
app.use(router)
app.provide(V2_RUNTIME_KEY, shallowReadonly(v2Runtime))
app.mount('#app')

let pageDisposed = false
if (import.meta.env.VITE_CHAT_V2_PREVIEW === 'true') {
  import('./application/v2Runtime').then(({ createConfiguredV2Runtime }) => {
    const runtime = createConfiguredV2Runtime(import.meta.env)
    if (pageDisposed) runtime.dispose()
    else v2Runtime.value = runtime
  }).catch(() => {
    if (!pageDisposed) {
      v2Runtime.value = { enabled: false, reason: 'V2 preview failed to load', dispose() {} }
    }
  })
}

globalThis.addEventListener?.('pagehide', () => {
  pageDisposed = true
  v2Runtime.value.dispose()
}, { once: true })
