export async function copyMessageText(text, {
  clipboard = globalThis.navigator?.clipboard,
  document: documentRef = globalThis.document
} = {}) {
  if (typeof text !== 'string' || text.length === 0) return false

  if (clipboard && typeof clipboard.writeText === 'function') {
    try {
      await clipboard.writeText(text)
      return true
    } catch {
      // Continue to the legacy browser fallback below.
    }
  }

  if (!documentRef?.body || typeof documentRef.createElement !== 'function'
      || typeof documentRef.execCommand !== 'function') return false

  const textarea = documentRef.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.opacity = '0'

  try {
    documentRef.body.appendChild(textarea)
    textarea.select()
    return documentRef.execCommand('copy') === true
  } catch {
    return false
  } finally {
    if (textarea.parentNode) textarea.parentNode.removeChild(textarea)
  }
}
