import { conversationCache } from "../persistence/conversationCache.js";
import { V2WebProtocolClient } from "../protocol/v2/webProtocolClient";
import { V2WebSocketTransport } from "../protocol/v2/webSocketTransport";
import {
  V2WebChatApplication,
  type V2WebChatSnapshot,
} from "./v2WebChatApplication";
import {
  bundledWebPushWorkerUrl,
  createWebPushBrowserAdapter,
} from "../platform/webPushBrowserAdapter";
import {
  WebPushSubscriptionController,
  type WebPushBrowserPort,
} from "../platform/webPushSubscriptionController";
import { createWebPushSubscriptionHttpApi } from "../platform/webPushSubscriptionHttpApi";
import { createV2WebPushHttpCredentialLease } from "../platform/v2WebPushHttpCredentialLease";

export const V2_DEVICE_ID_STORAGE_KEY = "chat.v2.device-id";

type Environment = Record<string, string | boolean | undefined>;
type StorageLike = Pick<Storage, "getItem" | "setItem">;

export type V2PreviewRuntime =
  | { enabled: false; reason: string; dispose(): void }
  | {
      enabled: true;
      application: V2WebChatApplication;
      deviceIdentity: "persistent" | "ephemeral";
      webPushController: WebPushSubscriptionController | null;
      dispose(): void;
    };

export interface V2RuntimeOptions {
  storage?: StorageLike | null;
  createUuid?: () => string;
  onChange?: (snapshot: V2WebChatSnapshot) => void;
  webPushPlatform?: {
    origin: string;
    browser: WebPushBrowserPort;
    fetch?: typeof globalThis.fetch;
  };
}

export function createConfiguredV2Runtime(
  environment: Environment,
  options: V2RuntimeOptions = {},
): V2PreviewRuntime {
  const flag = environment.VITE_CHAT_V2_PREVIEW;
  if (flag === undefined || flag === false || flag === "false" || flag === "") {
    return disabled("V2 preview is disabled");
  }
  if (flag !== true && flag !== "true") return disabled("V2 preview flag is invalid");

  const forwardingFlag = environment.VITE_CHAT_V2_MESSAGE_FORWARDING;
  if (forwardingFlag !== undefined && forwardingFlag !== false
      && forwardingFlag !== "false" && forwardingFlag !== true
      && forwardingFlag !== "true" && forwardingFlag !== "") {
    return disabled("V2 message forwarding flag is invalid");
  }
  const forwardingEnabled = forwardingFlag === true || forwardingFlag === "true";
  const searchFlag = environment.VITE_CHAT_V2_MESSAGE_SEARCH;
  if (searchFlag !== undefined && searchFlag !== false
      && searchFlag !== "false" && searchFlag !== true
      && searchFlag !== "true" && searchFlag !== "") {
    return disabled("V2 message search flag is invalid");
  }
  const searchEnabled = searchFlag === true || searchFlag === "true";
  const blockingFlag = environment.VITE_CHAT_V2_ACCOUNT_BLOCKING;
  if (blockingFlag !== undefined && blockingFlag !== false
      && blockingFlag !== "false" && blockingFlag !== true
      && blockingFlag !== "true" && blockingFlag !== "") {
    return disabled("V2 account blocking flag is invalid");
  }
  const accountBlockingEnabled = blockingFlag === true || blockingFlag === "true";
  const notificationFlag = environment.VITE_CHAT_V2_NOTIFICATIONS;
  if (notificationFlag !== undefined && notificationFlag !== false
      && notificationFlag !== "false" && notificationFlag !== true
      && notificationFlag !== "true" && notificationFlag !== "") {
    return disabled("V2 notifications flag is invalid");
  }
  const notificationsEnabled = notificationFlag === true || notificationFlag === "true";
  const webPushFlag = environment.VITE_CHAT_V2_WEB_PUSH;
  if (webPushFlag !== undefined && webPushFlag !== false
      && webPushFlag !== "false" && webPushFlag !== true
      && webPushFlag !== "true" && webPushFlag !== "") {
    return disabled("V2 Web Push flag is invalid");
  }
  const webPushConfigured = webPushFlag === true || webPushFlag === "true";
  let webPushPublicKey: Uint8Array<ArrayBufferLike> = new Uint8Array();
  if (webPushConfigured) {
    try { webPushPublicKey = decodeApplicationServerKey(
      stringValue(environment.VITE_CHAT_V2_WEB_PUSH_PUBLIC_KEY)); }
    catch { return disabled("V2 Web Push public key is invalid"); }
  }

  const endpoint = stringValue(environment.VITE_CHAT_V2_WSS_URL);
  const fallbackEndpointValue = environment.VITE_CHAT_V2_WSS_FALLBACK_URLS;
  const appVersion = stringValue(environment.VITE_CHAT_APP_VERSION);
  if (!endpoint) return disabled("V2 preview endpoint is missing");
  if (!appVersion || new TextEncoder().encode(appVersion).byteLength > 64) {
    return disabled("V2 preview app version is invalid");
  }

  const createUuid = options.createUuid ?? (() => crypto.randomUUID());
  try {
    const fallbackEndpoints = parseFallbackEndpoints(fallbackEndpointValue);
    const storage = options.storage === undefined ? safeLocalStorage() : options.storage;
    const identity = resolveDeviceIdentity(storage, createUuid);
    const webPushEnabled = webPushConfigured && identity.persistence === "persistent";
    const transport = new V2WebSocketTransport({
      endpoint,
      fallbackEndpoints,
      createProtocolClient: () => new V2WebProtocolClient({
        appVersion,
        clientDeviceId: identity.deviceId,
        enableMessageEdits: true,
        enableMessageMentions: true,
        enableMessageForwarding: forwardingEnabled,
        enableMessageSearch: searchEnabled,
        enableAccountBlocking: accountBlockingEnabled,
        enableWebPushHttpCredential: webPushEnabled,
      }),
    });
    const application = new V2WebChatApplication({
      transport,
      cache: conversationCache,
      onChange: options.onChange,
      enableMessageForwarding: forwardingEnabled,
      enableMessageSearch: searchEnabled,
      enableAccountBlocking: accountBlockingEnabled,
      enableNotifications: notificationsEnabled,
    });
    const webPushController = webPushEnabled
      ? createWebPushController(
        identity.deviceId, webPushPublicKey, transport, options.webPushPlatform)
      : null;
    return {
      enabled: true,
      application,
      deviceIdentity: identity.persistence,
      webPushController,
      dispose: () => application.dispose(),
    };
  } catch {
    return disabled("V2 preview configuration is invalid");
  }
}

function createWebPushController(
  installationId: string,
  applicationServerKey: Uint8Array,
  transport: V2WebSocketTransport,
  injected?: V2RuntimeOptions["webPushPlatform"],
): WebPushSubscriptionController {
  const platform = injected ?? defaultWebPushPlatform();
  return new WebPushSubscriptionController(
    true,
    installationId,
    applicationServerKey,
    platform.browser,
    createWebPushSubscriptionHttpApi({
      origin: platform.origin,
      credentials: createV2WebPushHttpCredentialLease(transport),
      fetch: platform.fetch,
    }),
  );
}

function defaultWebPushPlatform(): NonNullable<V2RuntimeOptions["webPushPlatform"]> {
  const serviceWorker = typeof navigator === "undefined"
    ? undefined
    : navigator.serviceWorker as unknown as
      Parameters<typeof createWebPushBrowserAdapter>[0]["serviceWorker"];
  const notification = typeof Notification === "undefined" ? undefined : Notification;
  const origin = typeof location === "undefined" ? "" : location.origin;
  return {
    origin,
    browser: createWebPushBrowserAdapter({
      serviceWorker,
      notification,
      pushManagerSupported: typeof PushManager !== "undefined",
      secureContext: globalThis.isSecureContext,
      workerUrl: bundledWebPushWorkerUrl,
      scope: "/",
    }),
  };
}

function decodeApplicationServerKey(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]{87}$/.test(value)) {
    throw new Error("invalid Web Push public key");
  }
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const decoded = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="));
  const bytes = Uint8Array.from(decoded, character => character.charCodeAt(0));
  if (bytes.byteLength !== 65 || bytes[0] !== 0x04) {
    throw new Error("invalid Web Push public key");
  }
  return bytes;
}

function resolveDeviceIdentity(
  storage: StorageLike | null,
  createUuid: () => string,
): { deviceId: string; persistence: "persistent" | "ephemeral" } {
  if (storage) {
    try {
      const existing = storage.getItem(V2_DEVICE_ID_STORAGE_KEY);
      if (canonicalUuid(existing)) return { deviceId: existing, persistence: "persistent" };
    } catch {
      return { deviceId: requireUuid(createUuid()), persistence: "ephemeral" };
    }
    const created = requireUuid(createUuid());
    try {
      storage.setItem(V2_DEVICE_ID_STORAGE_KEY, created);
      return { deviceId: created, persistence: "persistent" };
    } catch {
      return { deviceId: created, persistence: "ephemeral" };
    }
  }
  return { deviceId: requireUuid(createUuid()), persistence: "ephemeral" };
}

function safeLocalStorage(): StorageLike | null {
  try {
    return globalThis.localStorage;
  } catch {
    return null;
  }
}

function stringValue(value: string | boolean | undefined): string {
  return typeof value === "string" ? value.trim() : "";
}

function parseFallbackEndpoints(value: string | boolean | undefined): readonly string[] {
  if (value === undefined || value === false || value === "") return [];
  if (typeof value !== "string") throw new Error("V2 fallback endpoint list is invalid");
  const parsed: unknown = JSON.parse(value);
  if (!Array.isArray(parsed) || parsed.some(endpoint => typeof endpoint !== "string" || endpoint.trim() !== endpoint)) {
    throw new Error("V2 fallback endpoint list is invalid");
  }
  return parsed;
}

function canonicalUuid(value: string | null): value is string {
  return Boolean(value && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value));
}

function requireUuid(value: string): string {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) {
    throw new Error("device UUID generator returned an invalid value");
  }
  return value;
}

function disabled(reason: string): V2PreviewRuntime {
  return { enabled: false, reason, dispose() {} };
}
