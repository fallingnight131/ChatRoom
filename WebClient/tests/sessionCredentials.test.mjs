import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  clearSessionCredentials,
  completeSessionPasswordChange,
  getSessionCredentials,
  purgeLegacyPersistedSession,
  setSessionCredentials,
  stageSessionPasswordChange
} from '../src/security/sessionCredentials.js'

test.afterEach(() => clearSessionCredentials())

test('keeps credentials only in module memory and returns a defensive copy', () => {
  assert.equal(setSessionCredentials('alice_01', 'correct horse battery staple'), true)

  const first = getSessionCredentials()
  assert.deepEqual(first, {
    username: 'alice_01',
    password: 'correct horse battery staple'
  })

  first.password = 'changed by caller'
  assert.equal(getSessionCredentials().password, 'correct horse battery staple')
})

test('rejects incomplete credentials and clears any previous value', () => {
  setSessionCredentials('alice_01', 'temporary')
  assert.equal(setSessionCredentials('alice_01', ''), false)
  assert.equal(getSessionCredentials(), null)
})

test('clears credentials on logout', () => {
  setSessionCredentials('alice_01', 'temporary')
  clearSessionCredentials()
  assert.equal(getSessionCredentials(), null)
})

test('updates the in-memory password only after a successful server response', () => {
  setSessionCredentials('alice_01', 'old password')
  stageSessionPasswordChange('new password')

  assert.equal(completeSessionPasswordChange('alice_01', true), true)
  assert.equal(getSessionCredentials().password, 'new password')
})

test('discards a rejected password change', () => {
  setSessionCredentials('alice_01', 'old password')
  stageSessionPasswordChange('rejected password')

  assert.equal(completeSessionPasswordChange('alice_01', false), false)
  assert.equal(getSessionCredentials().password, 'old password')
  assert.equal(completeSessionPasswordChange('alice_01', true), false)
})

test('removes plaintext credentials and stale identity from older web versions', () => {
  const removed = []
  const fakeStorage = { removeItem: key => removed.push(key) }

  purgeLegacyPersistedSession(fakeStorage)

  assert.deepEqual(removed, ['credentials', 'user'])
})

test('web authentication sources never write session state or credential-like local storage', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const sourceFiles = [
    '../src/stores/user.js',
    '../src/views/LoginView.vue',
    '../src/views/ChatView.vue',
    '../src/security/sessionCredentials.js'
  ]

  for (const relativePath of sourceFiles) {
    const source = readFileSync(resolve(here, relativePath), 'utf8')
    assert.doesNotMatch(source, /sessionStorage\.setItem\s*\(/)
    assert.doesNotMatch(
      source,
      /localStorage\.setItem\s*\([^\n]*(?:password|credential)/i
    )
  }
})
