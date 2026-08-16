import assert from 'node:assert/strict'
import test from 'node:test'

import { copyMessageText } from '../src/messaging/copyMessageText.js'

test('copies the exact plain message body through the Clipboard API', async () => {
  const writes = []
  const copied = await copyMessageText('<b>你好</b> 👋', {
    clipboard: { writeText: async text => writes.push(text) },
    document: null
  })

  assert.equal(copied, true)
  assert.deepEqual(writes, ['<b>你好</b> 👋'])
})

test('falls back to a temporary readonly textarea and always removes it', async () => {
  const children = []
  const textarea = {
    value: '', style: {}, parentNode: null, selected: false,
    setAttribute(name, value) { this[name] = value },
    select() { this.selected = true }
  }
  const body = {
    appendChild(node) { children.push(node); node.parentNode = body },
    removeChild(node) { children.splice(children.indexOf(node), 1); node.parentNode = null }
  }
  const document = {
    body,
    createElement(tag) { assert.equal(tag, 'textarea'); return textarea },
    execCommand(command) { assert.equal(command, 'copy'); return true }
  }

  const copied = await copyMessageText('fallback text', {
    clipboard: { writeText: async () => { throw new Error('permission denied') } },
    document
  })

  assert.equal(copied, true)
  assert.equal(textarea.value, 'fallback text')
  assert.equal(textarea.readonly, '')
  assert.equal(textarea.selected, true)
  assert.deepEqual(children, [])
})

test('fails closed for empty content or unavailable browser facilities', async () => {
  assert.equal(await copyMessageText('', { clipboard: null, document: null }), false)
  assert.equal(await copyMessageText('text', { clipboard: null, document: null }), false)
})
