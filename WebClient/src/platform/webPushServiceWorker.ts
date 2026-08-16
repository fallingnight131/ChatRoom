import {
  decodeWebPushPayload,
  presentWebPushPayload,
  type WebPushGenericCopy,
} from "./webPushPayload";

interface PushEventLike {
  data?: { text(): string } | null;
  waitUntil(promise: Promise<unknown>): void;
}

interface NotificationClickEventLike {
  notification: {
    data?: unknown;
    close(): void;
  };
  waitUntil(promise: Promise<unknown>): void;
}

interface WindowClientLike {
  url: string;
  focus(): Promise<unknown>;
  navigate?(url: string): Promise<unknown>;
}

export interface WebPushServiceWorkerScope {
  registration: {
    showNotification(title: string, options: {
      body: string;
      tag: string;
      data: { navigationUrl: string; notificationId: string };
    }): Promise<unknown>;
  };
  clients: {
    matchAll(options: { type: "window"; includeUncontrolled: true }): Promise<WindowClientLike[]>;
    openWindow(url: string): Promise<unknown>;
  };
  addEventListener(type: "push", listener: (event: PushEventLike) => void): void;
  addEventListener(
    type: "notificationclick",
    listener: (event: NotificationClickEventLike) => void,
  ): void;
}

const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function installWebPushServiceWorker(
  scope: WebPushServiceWorkerScope,
  copy: WebPushGenericCopy,
  supportedOrigin: string,
): void {
  const origin = requireOrigin(supportedOrigin);
  scope.addEventListener("push", event => {
    let wire: string;
    try { wire = event.data?.text() ?? ""; } catch { return; }
    const payload = decodeWebPushPayload(wire);
    if (!payload) return;
    const presentation = presentWebPushPayload(payload, copy, origin);
    event.waitUntil(scope.registration.showNotification(
      presentation.title, presentation.options));
  });
  scope.addEventListener("notificationclick", event => {
    let target: string | null = null;
    const data = event.notification.data;
    if (data && typeof data === "object") {
      const record = data as Record<string, unknown>;
      if (Object.keys(record).sort().join("\0") === "navigationUrl\0notificationId"
          && typeof record.navigationUrl === "string"
          && typeof record.notificationId === "string"
          && canonicalUuid.test(record.notificationId)
          && validTarget(record.navigationUrl, origin)) target = record.navigationUrl;
    }
    event.notification.close();
    if (target) event.waitUntil(activate(scope, target, origin));
  });
}

async function activate(
  scope: WebPushServiceWorkerScope,
  target: string,
  origin: string,
): Promise<void> {
  const windows = await scope.clients.matchAll({ type: "window", includeUncontrolled: true });
  const existing = windows.find(client => {
    try { return new URL(client.url).origin === origin; } catch { return false; }
  });
  if (existing) {
    if (existing.navigate) await existing.navigate(target);
    await existing.focus();
    return;
  }
  await scope.clients.openWindow(target);
}

function requireOrigin(value: string): string {
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" || parsed.origin !== value) throw new Error("invalid origin");
  return value;
}

function validTarget(value: string, origin: string): boolean {
  try {
    const parsed = new URL(value);
    return parsed.origin === origin && parsed.pathname === "/"
      && parsed.search === "" && parsed.hash.startsWith("#/preview/v2?");
  } catch { return false; }
}
