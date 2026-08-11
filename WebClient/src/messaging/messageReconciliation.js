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

export function applyDeletionEvents(messages, events = []) {
  return [...events]
    .sort((left, right) => Number(left?.syncSequence || left?.sequence || 0) -
      Number(right?.syncSequence || right?.sequence || 0))
    .reduce((current, event) => {
      if (!event || (event.eventType && event.eventType !== 'messagesDeleted')) {
        return current
      }
      if (event.mode === 'all') return []
      if (event.mode === 'selected') {
        const ids = new Set((event.messageIds || []).map(Number))
        return current.filter(message => !ids.has(Number(message.id)))
      }
      const cutoff = Number(event.timestamp ?? event.cutoffMs ?? 0)
      if (!Number.isFinite(cutoff) || cutoff <= 0) return current
      if (event.mode === 'before') {
        return current.filter(message => Number(message.timestamp || 0) >= cutoff)
      }
      if (event.mode === 'after') {
        return current.filter(message => Number(message.timestamp || 0) <= cutoff)
      }
      return current
    }, [...messages])
}

export function syncSequenceOf(item) {
  return Number(item?.syncSequence || item?.mutationSequence || item?.sequence || 0)
}

export function reconcileRoomSyncPage(existing, messages = [], events = []) {
  const items = [
    ...messages.map(value => ({ kind: 'message', value })),
    ...events.map(value => ({ kind: 'event', value }))
  ].sort((left, right) => syncSequenceOf(left.value) - syncSequenceOf(right.value))

  return items.reduce((current, item) => item.kind === 'event'
    ? applyDeletionEvents(current, [item.value])
    : mergeUniqueMessages(current, [item.value]), [...existing])
}
