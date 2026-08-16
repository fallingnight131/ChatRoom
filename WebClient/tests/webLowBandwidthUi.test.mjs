import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8')
const store = read('../src/stores/user.js')
const profile = read('../src/components/ProfileDialog.vue')
const v2Preview = read('../src/views/V2PreviewView.vue')

test('exposes a persistent, accessible Web low-bandwidth preference', () => {
  for (const marker of [
    'lowBandwidthMode: bandwidthPreference.enabled',
    'setLowBandwidthMode(enabled)',
    'requestAvatarIfAllowed(username)',
    'shouldAutoRequestAvatar(',
    'const webStorage = resolveWebStorage()',
    'darkMode: readDarkMode(webStorage)',
    "? 'user' : 'session'",
  ]) assert.ok(store.includes(marker), `missing bandwidth store marker: ${marker}`)
  for (const marker of [
    'type="checkbox" :checked="userStore.lowBandwidthMode"',
    '{{ messages.lowBandwidth }}',
    '{{ messages.sessionOnly }}',
  ]) assert.ok(profile.includes(marker), `missing bandwidth UI marker: ${marker}`)
  for (const marker of [
    'id="v2-low-bandwidth" type="checkbox"',
    ':checked="userStore.lowBandwidthMode"',
    'aria-describedby="v2-low-bandwidth-description"',
    '@change="setLowBandwidthMode"',
    'userStore.lowBandwidthPreferenceSource === \'browser\'',
    'userStore.lowBandwidthPreferenceSource === \'session\'',
  ]) assert.ok(v2Preview.includes(marker), `missing V2 bandwidth UI marker: ${marker}`)
})

test('suppresses automatic avatar requests without blocking explicit profile loading', () => {
  for (const path of [
    '../src/components/MessageList.vue',
    '../src/components/FriendList.vue',
    '../src/components/UserList.vue',
  ]) assert.ok(read(path).includes('userStore.requestAvatarIfAllowed(username)'),
    `${path} does not guard automatic avatar loading`)
  for (const path of ['../src/views/LoginView.vue', '../src/views/ChatView.vue']) {
    assert.ok(read(path).includes('userStore.requestAvatarIfAllowed(msg.data.username)'),
      `${path} does not guard own-avatar loading`)
  }
  assert.ok(read('../src/components/UserInfoDialog.vue').includes('chatWs.getAvatar(props.user.username)'),
    'explicit user-profile avatar loading must remain available')
})

test('keeps V2 messaging and synchronization outside the presentation preference boundary', () => {
  assert.ok(v2Preview.includes('userStore.setLowBandwidthMode(event.target.checked)'))
  assert.doesNotMatch(v2Preview, /lowBandwidthMode[^\n]*(sendText|openConversation|loadMoreDirectory|start)/)
})
