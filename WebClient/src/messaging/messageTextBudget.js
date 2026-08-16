export const MAX_MESSAGE_TEXT_BYTES = 65_536

const encoder = new TextEncoder()

export function messageTextBudget(value) {
  const text = String(value ?? '')
  const bytes = encoder.encode(text).byteLength
  return {
    bytes,
    maximum: MAX_MESSAGE_TEXT_BYTES,
    overage: Math.max(0, bytes - MAX_MESSAGE_TEXT_BYTES),
    withinBudget: bytes <= MAX_MESSAGE_TEXT_BYTES
  }
}

export function messageTextBudgetLabel(value) {
  const budget = messageTextBudget(value)
  return budget.withinBudget
    ? `${budget.bytes} / ${budget.maximum} 字节`
    : `超过上限 ${budget.overage} 字节（最多 ${budget.maximum} 字节）`
}
