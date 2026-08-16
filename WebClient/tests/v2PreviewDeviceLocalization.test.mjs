import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')

test('renders V2 server-authoritative device management from the active locale', () => {
  for (const marker of [
    'v2PreviewDeviceMessages(userStore.locale)', 'deviceMessages.title',
    'deviceMessages.description', ':aria-label="deviceMessages.close"',
    'visibleDeviceFailure', 'deviceMessages.reconnectNotice', 'deviceMessages.windows',
    'deviceMessages.web', 'deviceMessages.currentDevice', 'deviceMessages.recentActivity(',
    'deviceMessages.confirmGroup', 'deviceMessages.revokeAll', 'deviceMessages.revoking',
    'deviceMessages.loading', 'deviceMessages.refresh', 'deviceMessages.done',
  ]) assert.ok(source.includes(marker), `missing V2 device locale marker: ${marker}`)
})

test('preserves authenticated authority, stable device identity, current-device guard, and modal focus', () => {
  for (const marker of [
    "snapshot.value.connectionState === 'authenticated'", ':key="device.deviceId"',
    'v-if="device.current"', 'v-else-if="confirmingDeviceId !== device.deviceId"',
    'application.refreshDevices()', 'application.revokeDevice(deviceId)',
    ':disabled="!canManageDevices || Boolean(snapshot.revokingDeviceId)"',
    "initialFocusSelector: '#device-dialog-close'", 'active: devicesOpen',
  ]) assert.ok(source.includes(marker), `missing V2 device boundary marker: ${marker}`)
})
