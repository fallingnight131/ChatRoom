import type { WebPushGenericCopy } from "./webPushPayload";

export type WebPushLocale = "zh-CN" | "en-US";

export const WEB_PUSH_LOCALE_CACHE = "chatroom-web-push-locale-v1";
const localePath = "/.well-known/chatroom-web-push-locale";

const copy: Record<WebPushLocale, WebPushGenericCopy> = {
  "zh-CN": {
    messageTitle: "ChatRoom 新消息",
    mentionTitle: "ChatRoom 中有人提到了你",
    body: "打开 ChatRoom 查看消息",
  },
  "en-US": {
    messageTitle: "New ChatRoom message",
    mentionTitle: "You were mentioned in ChatRoom",
    body: "Open ChatRoom to view the message",
  },
};

export async function persistWebPushLocale(
  locale: string,
  origin = globalThis.location?.origin ?? "",
  storage: CacheStorage | undefined = globalThis.caches,
): Promise<boolean> {
  if (!isLocale(locale) || !storage) return false;
  try {
    const cache = await storage.open(WEB_PUSH_LOCALE_CACHE);
    await cache.put(localeUrl(origin), new Response(locale, {
      headers: { "content-type": "text/plain;charset=UTF-8" },
    }));
    return true;
  } catch { return false; }
}

export async function loadWebPushGenericCopy(
  origin: string,
  storage: CacheStorage | undefined = globalThis.caches,
): Promise<WebPushGenericCopy> {
  let locale: WebPushLocale = "zh-CN";
  if (storage) {
    try {
      const cached = await (await storage.open(WEB_PUSH_LOCALE_CACHE)).match(localeUrl(origin));
      const candidate = cached && cached.ok ? await cached.text() : "";
      if (isLocale(candidate)) locale = candidate;
    } catch { /* generic Chinese copy remains available offline */ }
  }
  return { ...copy[locale] };
}

function isLocale(value: string): value is WebPushLocale {
  return value === "zh-CN" || value === "en-US";
}

function localeUrl(origin: string): string {
  const parsed = new URL(origin);
  if (parsed.protocol !== "https:" || parsed.origin !== origin) {
    throw new Error("invalid Web Push locale origin");
  }
  return `${origin}${localePath}`;
}
