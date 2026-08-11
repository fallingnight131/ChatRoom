export function makeOptimisticMessage(fields, clientMessageId, timestamp = Date.now()) {
  return {
    ...fields,
    id: 0,
    clientMessageId,
    timestamp,
    deliveryState: 'sending'
  }
}

export function applySendAcknowledgement(messages, result) {
  const message = messages.find(candidate =>
    candidate.clientMessageId === result?.clientMessageId)
  if (!message) return null
  if (result.success) {
    Object.assign(message, {
      id: result.id,
      sequence: result.sequence,
      timestamp: result.timestamp,
      deliveryState: 'accepted',
      errorCode: ''
    })
  } else {
    message.deliveryState = 'failed'
    message.errorCode = result?.errorCode || 'SEND_REJECTED'
  }
  return message
}

export function pendingMessagesFor(messages, username) {
  return messages.filter(message =>
    message.sender === username && message.deliveryState === 'sending' &&
    Boolean(message.clientMessageId))
}
