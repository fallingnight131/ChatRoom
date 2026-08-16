import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const login = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
const user = readFileSync(new URL('../src/stores/user.js', import.meta.url), 'utf8')

test('renders the login and registration surface from the typed locale catalog', () => {
  for (const marker of [
    "import { loginMessages } from '../localization/webLocale'",
    'const messages = computed(() => loginMessages(userStore.locale))',
    'id="login-locale" class="locale-select"',
    '@change="userStore.setLocale($event.target.value)"',
    '{{ messages.userId }}',
    ':placeholder="messages.passwordPlaceholder"',
    'messages.switchToRegister',
    'messages.darkTheme',
  ]) assert.ok(login.includes(marker), `missing localized login marker: ${marker}`)
})

test('keeps local error identity stable across a language change', () => {
  for (const marker of [
    "const localErrorKey = ref('')",
    'messages.value[localErrorKey.value]',
    "setLocalError('offline')",
    "localErrorKey.value === 'offline'",
    "setLocalError('onlineAgain')",
    'setRemoteError(msg.data.error)',
  ]) assert.ok(login.includes(marker), `missing localized error marker: ${marker}`)
})

test('owns locale preference and document language in the user preference boundary', () => {
  for (const marker of [
    'const initialWebLocale = resolveWebLocale(webStorage)',
    'locale: initialWebLocale',
    'setLocale(locale)',
    'persistWebLocale(webStorage, locale)',
    'document.documentElement',
  ]) assert.ok(user.includes(marker), `missing locale preference marker: ${marker}`)
})
