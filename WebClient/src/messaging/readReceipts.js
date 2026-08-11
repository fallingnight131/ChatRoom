export function advanceReadWatermark(current, candidate) {
  return Math.max(0, Number(current) || 0, Number(candidate) || 0)
}

export function applyPeerReadWatermark(messages, watermark, username) {
  const limit = Math.max(0, Number(watermark) || 0)
  if (!Array.isArray(messages) || !username || limit <= 0) return false
  let changed = false
  for (const message of messages) {
    const messageId = Number(message?.id) || 0
    if (message?.sender !== username || messageId <= 0 || messageId > limit)
      continue
    if (message.deliveryState === 'sending' || message.deliveryState === 'failed')
      continue
    if (message.deliveryState !== 'read') {
      message.deliveryState = 'read'
      changed = true
    }
  }
  return changed
}
