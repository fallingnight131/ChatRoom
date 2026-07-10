let credentials = null
let pendingPasswordChange = null

export function setSessionCredentials(username, password) {
  if (typeof username !== 'string' || !username || typeof password !== 'string' || !password) {
    credentials = null
    return false
  }
  credentials = Object.freeze({ username, password })
  return true
}

export function getSessionCredentials() {
  return credentials ? { ...credentials } : null
}

export function clearSessionCredentials() {
  credentials = null
  pendingPasswordChange = null
}

export function stageSessionPasswordChange(password) {
  pendingPasswordChange = typeof password === 'string' && password ? password : null
  return pendingPasswordChange !== null
}

export function completeSessionPasswordChange(username, success) {
  const password = pendingPasswordChange
  pendingPasswordChange = null
  if (!success || !password) return false
  return setSessionCredentials(username, password)
}

export function purgeLegacyPersistedSession(storage) {
  if (arguments.length === 0) {
    try {
      storage = globalThis.sessionStorage
    } catch {
      return
    }
  }
  if (!storage || typeof storage.removeItem !== 'function') return
  storage.removeItem('credentials')
  storage.removeItem('user')
}
