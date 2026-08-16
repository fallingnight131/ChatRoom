import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const view = fs.readFileSync(new URL('../src/views/V2PreviewView.vue', import.meta.url), 'utf8')
const runtime = fs.readFileSync(new URL('../src/application/v2Runtime.ts', import.meta.url), 'utf8')

test('keeps accessible account blocking behind one exact Web capability gate', () => {
  for (const fragment of [
    "VITE_CHAT_V2_ACCOUNT_BLOCKING",
    'enableAccountBlocking: accountBlockingEnabled',
    "snapshot.accountBlockingEnabled && activeConversationKind === 'direct'",
    'aria-haspopup="dialog"',
    'aria-labelledby="account-block-dialog-title"',
    'aria-describedby="account-block-dialog-description"',
    'aria-live="polite"',
  ]) assert.ok(runtime.includes(fragment) || view.includes(fragment), `missing ${fragment}`)
})

test('uses the authoritative direct participant and preserves explicit retry identity', () => {
  for (const fragment of [
    'snapshot.value.participants.length === 1',
    'snapshot.value.participantsHasMore',
    'application.setAccountBlock(target.accountId, blocked)',
    'application.retryAccountBlock(command.clientOperationId)',
    'accountBlockConfirmation',
    'v2PreviewAccountBlockMessages(userStore.locale)',
  ]) assert.ok(view.includes(fragment), `missing ${fragment}`)
})
