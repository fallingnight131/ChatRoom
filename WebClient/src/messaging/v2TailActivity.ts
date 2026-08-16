export interface V2TailMessage {
  id: string;
  clientMessageId: string;
  sequence: string;
  deliveryState: "sending" | "accepted" | "failed";
}

export interface V2TailSnapshot {
  activeConversationId: string | null;
  messages: readonly V2TailMessage[];
  historyLoading: boolean;
  searchContextLoading: boolean;
}

export interface V2TailUpdate {
  conversationChanged: boolean;
  additions: number;
}

function positiveSequence(value: string): bigint | null {
  if (!/^[1-9][0-9]*$/.test(value)) return null;
  try { return BigInt(value); } catch { return null; }
}

export function countV2TailAdditions(
  previous: readonly V2TailMessage[],
  next: readonly V2TailMessage[],
): number {
  const knownServerIds = new Set(previous.map((message) => message.id).filter(Boolean));
  const knownClientIds = new Set(previous.map((message) => message.clientMessageId).filter(Boolean));
  let previousTail = 0n;
  for (const message of previous) {
    if (message.deliveryState !== "accepted") continue;
    const sequence = positiveSequence(message.sequence);
    if (sequence !== null && sequence > previousTail) previousTail = sequence;
  }

  let additions = 0;
  const observedServerIds = new Set(knownServerIds);
  const observedClientIds = new Set(knownClientIds);
  for (const message of next) {
    if ((message.id && observedServerIds.has(message.id))
        || (message.clientMessageId && observedClientIds.has(message.clientMessageId))) continue;

    if (message.id) observedServerIds.add(message.id);
    if (message.clientMessageId) observedClientIds.add(message.clientMessageId);
    if (message.deliveryState === "accepted") {
      const sequence = positiveSequence(message.sequence);
      if (sequence !== null && sequence > previousTail) additions += 1;
    } else if (message.clientMessageId) {
      additions += 1;
    }
  }
  return additions;
}

export function classifyV2TailUpdate(
  previous: V2TailSnapshot,
  next: V2TailSnapshot,
): V2TailUpdate {
  const conversationChanged = previous.activeConversationId !== next.activeConversationId;
  const initialHistory = !conversationChanged && previous.messages.length === 0
    && (previous.historyLoading || next.historyLoading);
  const searchContextRepair = previous.searchContextLoading || next.searchContextLoading;
  return {
    conversationChanged,
    additions: conversationChanged || initialHistory || searchContextRepair
      ? 0 : countV2TailAdditions(previous.messages, next.messages),
  };
}
