import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const routerSource = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
const previewSource = await readFile(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('keeps the V2 preview route build-gated and lazily loaded', () => {
  assert.match(routerSource, /VITE_CHAT_V2_PREVIEW === 'true'/)
  assert.match(routerSource, /path: '\/preview\/v2'/)
  assert.match(routerSource, /component: \(\) => import\('\.\.\/views\/V2PreviewView\.vue'\)/)
})

test('owns explicit V2 start, transient authentication, and basic chat actions', () => {
  assert.match(previewSource, /runtime\.application\.subscribe/)
  assert.match(previewSource, /runtime\.application\.start\(\)/)
  assert.match(previewSource, /password\.value = ''/)
  assert.match(previewSource, /passwordBytes\.fill\(0\)/)
  assert.match(previewSource, /application\.openConversation/)
  assert.match(previewSource, /application\.sendText/)
  assert.match(previewSource, /application\.retryMessage/)
  assert.match(previewSource, /startedApplication\.stop\(\)/)
  assert.match(previewSource, /aria-live="polite"/)
})

test('exposes keyboard-operable server-authoritative device management', () => {
  assert.match(previewSource, /role="dialog" aria-modal="true"/)
  assert.match(previewSource, /aria-labelledby="device-dialog-title"/)
  assert.match(previewSource, /ref="deviceDialogRef"/)
  assert.match(previewSource, /@keydown="onDeviceDialogKeydown"/)
  assert.match(previewSource, /initialFocusSelector: '#device-dialog-close'/)
  assert.match(previewSource, /active: devicesOpen/)
  assert.match(previewSource, /application\.refreshDevices\(\)/)
  assert.match(previewSource, /application\.revokeDevice\(deviceId\)/)
  assert.match(previewSource, /device\.current/)
  assert.doesNotMatch(previewSource, /localStorage.*device|indexedDB.*device/i)
})
