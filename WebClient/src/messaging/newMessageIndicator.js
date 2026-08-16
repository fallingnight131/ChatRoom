export const MAX_PENDING_NEW_MESSAGES = 99

export function addPendingNewMessages(current, added) {
  const safeCurrent = Number.isSafeInteger(current) && current > 0 ? current : 0
  const safeAdded = Number.isSafeInteger(added) && added > 0 ? added : 0
  return Math.min(MAX_PENDING_NEW_MESSAGES, safeCurrent + safeAdded)
}

export function pendingNewMessageLabel(count) {
  if (!Number.isSafeInteger(count) || count <= 0) return ''
  return count >= MAX_PENDING_NEW_MESSAGES ? '99+ 条新消息' : `${count} 条新消息`
}
