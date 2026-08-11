export function hasStableIdentity(message) {
  return Boolean(message?.id || message?.clientMessageId)
}

export function sameStableMessage(left, right) {
  if (!left || !right) return false
  if (left.id && right.id && left.id === right.id) return true
  return Boolean(
    left.clientMessageId &&
    right.clientMessageId &&
    left.clientMessageId === right.clientMessageId
  )
}

export function mergeUniqueMessages(existing, incoming, { prepend = false } = {}) {
  const merged = [...existing]
  const accepted = []
  for (const candidate of incoming) {
    const existingIndex = merged.findIndex(message =>
      sameStableMessage(message, candidate)
    )
    if (existingIndex >= 0) {
      merged[existingIndex] = { ...merged[existingIndex], ...candidate }
      continue
    }
    const acceptedIndex = accepted.findIndex(message =>
      sameStableMessage(message, candidate)
    )
    if (acceptedIndex >= 0) {
      accepted[acceptedIndex] = { ...accepted[acceptedIndex], ...candidate }
    } else {
      accepted.push(candidate)
    }
  }
  return prepend ? [...accepted, ...merged] : [...merged, ...accepted]
}
