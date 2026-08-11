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
  const accepted = []
  for (const candidate of incoming) {
    const duplicate = [...existing, ...accepted].some(message =>
      sameStableMessage(message, candidate)
    )
    if (!duplicate) accepted.push(candidate)
  }
  return prepend ? [...accepted, ...existing] : [...existing, ...accepted]
}
