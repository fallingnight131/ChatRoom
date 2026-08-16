export const WEB_PUSH_PAYLOAD_VERSION = 1;
export const WEB_PUSH_MAX_PAYLOAD_BYTES = 2_048;

export interface WebPushPayload {
  version: 1;
  notificationId: string;
  conversationId: string;
  messageId: string;
  mentioned: boolean;
}

export interface WebPushGenericCopy {
  messageTitle: string;
  mentionTitle: string;
  body: string;
}

export interface WebPushPresentation {
  title: string;
  options: {
    body: string;
    tag: string;
    data: { navigationUrl: string; notificationId: string };
  };
}

const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const fields = ["conversationId", "mentioned", "messageId", "notificationId", "version"];

export function decodeWebPushPayload(wire: string): WebPushPayload | null {
  if (typeof wire !== "string") return null;
  const byteLength = new TextEncoder().encode(wire).byteLength;
  if (byteLength < 1 || byteLength > WEB_PUSH_MAX_PAYLOAD_BYTES) return null;
  try {
    const value: unknown = JSON.parse(wire);
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const record = value as Record<string, unknown>;
    if (Object.keys(record).sort().join("\0") !== fields.join("\0")
        || record.version !== WEB_PUSH_PAYLOAD_VERSION
        || typeof record.mentioned !== "boolean"
        || typeof record.notificationId !== "string"
        || typeof record.conversationId !== "string"
        || typeof record.messageId !== "string"
        || !canonicalUuid.test(record.notificationId)
        || !canonicalUuid.test(record.conversationId)
        || !canonicalUuid.test(record.messageId)) return null;
    return record as unknown as WebPushPayload;
  } catch {
    return null;
  }
}

export function presentWebPushPayload(
  payload: WebPushPayload,
  copy: WebPushGenericCopy,
  supportedOrigin: string,
): WebPushPresentation {
  const origin = new URL(supportedOrigin);
  if (origin.protocol !== "https:" || origin.origin !== supportedOrigin) {
    throw new Error("Web Push navigation origin must be an exact HTTPS origin");
  }
  const navigation = new URL("/", origin);
  const query = new URLSearchParams({
    conversationId: payload.conversationId,
    messageId: payload.messageId,
    notificationId: payload.notificationId,
  });
  navigation.hash = `#/preview/v2?${query.toString()}`;
  return {
    title: payload.mentioned ? copy.mentionTitle : copy.messageTitle,
    options: {
      body: copy.body,
      tag: `chat-v2-push-${payload.notificationId}`,
      data: { navigationUrl: navigation.toString(), notificationId: payload.notificationId },
    },
  };
}
