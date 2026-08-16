import type {
  BrowserPushSubscription,
  WebPushBrowserPort,
} from "./webPushSubscriptionController";

interface ServiceWorkerRegistrationLike {
  pushManager: {
    getSubscription(): Promise<BrowserPushSubscription | null>;
    subscribe(options: {
      userVisibleOnly: true;
      applicationServerKey: Uint8Array;
    }): Promise<BrowserPushSubscription>;
  };
}

interface ServiceWorkerContainerLike {
  register(url: string, options: { scope: string }): Promise<ServiceWorkerRegistrationLike>;
  getRegistration(scope: string): Promise<ServiceWorkerRegistrationLike | undefined>;
}

export function createWebPushBrowserAdapter(options: {
  serviceWorker?: ServiceWorkerContainerLike;
  notification?: Pick<typeof Notification, "permission" | "requestPermission">;
  pushManagerSupported: boolean;
  secureContext?: boolean;
  workerUrl: string | (() => Promise<string>);
  scope?: string;
}): WebPushBrowserPort {
  const scope = options.scope ?? "/";
  if (!scope.startsWith("/") || scope.includes("?") || scope.includes("#")) {
    throw new Error("invalid Web Push worker scope");
  }
  const fixedWorkerUrl = typeof options.workerUrl === "string"
    ? exactWorkerUrl(options.workerUrl) : null;
  const resolveWorkerUrl = async (): Promise<string> => fixedWorkerUrl
    ?? exactWorkerUrl(await (options.workerUrl as () => Promise<string>)());
  let registration: ServiceWorkerRegistrationLike | null = null;
  const resolveRegistration = async (): Promise<ServiceWorkerRegistrationLike | null> => {
    if (registration) return registration;
    registration = await options.serviceWorker?.getRegistration(scope) ?? null;
    return registration;
  };
  return {
    supported: () => Boolean(options.serviceWorker && options.notification
      && options.pushManagerSupported && (options.secureContext ?? globalThis.isSecureContext)),
    permission: () => options.notification?.permission ?? "denied",
    requestPermission: () => options.notification?.requestPermission()
      ?? Promise.resolve("denied"),
    async registerWorker() {
      if (!options.serviceWorker) throw new Error("Service Worker unavailable");
      registration = await options.serviceWorker.register(await resolveWorkerUrl(),
        { scope });
    },
    async currentSubscription() {
      return (await resolveRegistration())?.pushManager.getSubscription() ?? null;
    },
    async subscribe(applicationServerKey) {
      const current = await resolveRegistration();
      if (!current) throw new Error("Web Push worker is not registered");
      return current.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: applicationServerKey.slice(),
      });
    },
  };
}

export async function bundledWebPushWorkerUrl(): Promise<string> {
  const module = await import("./webPushServiceWorkerEntry?worker&url");
  return module.default;
}

function exactWorkerUrl(value: string): string {
  if (!value.startsWith("/") || value.startsWith("//") || value.includes("\\")
      || value.includes("#") || value.includes("?")) throw new Error("invalid worker URL");
  return value;
}
