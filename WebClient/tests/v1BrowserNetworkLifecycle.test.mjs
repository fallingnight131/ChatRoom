import assert from 'node:assert/strict'
import test from 'node:test'

import { ChatWebSocket } from '../src/services/websocket.js'


class FakeSocket {
  static OPEN = 1

  constructor(url) {
    this.url = url
    this.readyState = 0
    this.closed = false
  }

  open() {
    this.readyState = FakeSocket.OPEN
    this.onopen?.()
  }

  close() {
    this.closed = true
    this.readyState = 3
    this.onclose?.()
  }
}

function environment(initialOnline) {
  const listeners = new Map()
  const sockets = []
  const WebSocket = class extends FakeSocket {
    constructor(url) {
      super(url)
      sockets.push(this)
    }
  }
  WebSocket.OPEN = FakeSocket.OPEN
  return {
    navigator: { onLine: initialOnline },
    WebSocket,
    sockets,
    addEventListener(type, listener) { listeners.set(type, listener) },
    dispatch(type) { listeners.get(type)?.() },
  }
}

test('does not create or automatically recover a new V1 intent made while offline', () => {
  const host = environment(false)
  const client = new ChatWebSocket(host)
  const events = []
  client.on('offline', () => events.push('offline'))
  client.on('online', () => events.push('online'))

  client.connectUrl('wss://chat.example.test/ws')
  assert.equal(host.sockets.length, 0)
  assert.deepEqual(events, ['offline'])

  host.navigator.onLine = true
  host.dispatch('online')
  assert.equal(host.sockets.length, 0)
  host.dispatch('online')
  assert.equal(host.sockets.length, 0)
  assert.deepEqual(events, ['offline', 'online'])
})

test('offline transition closes the active socket and recovery creates only one replacement', () => {
  const host = environment(true)
  const client = new ChatWebSocket(host)
  const events = []
  client.on('connected', () => events.push('connected'))
  client.on('disconnected', () => events.push('disconnected'))
  client.on('offline', () => events.push('offline'))

  client.connectUrl('wss://chat.example.test/ws')
  host.sockets[0].open()
  assert.equal(client.connected.value, true)

  host.navigator.onLine = false
  host.dispatch('offline')
  assert.equal(host.sockets[0].closed, true)
  assert.equal(client.connected.value, false)
  assert.deepEqual(events, ['connected', 'disconnected', 'offline'])

  host.dispatch('offline')
  host.navigator.onLine = true
  host.dispatch('online')
  assert.equal(host.sockets.length, 2)
})

test('explicit disconnect prevents recovery from reconnecting', () => {
  const host = environment(false)
  const client = new ChatWebSocket(host)
  client.connectUrl('wss://chat.example.test/ws')
  client.disconnect()
  host.navigator.onLine = true
  host.dispatch('online')
  assert.equal(host.sockets.length, 0)
})
