import { conversationCache } from "../persistence/conversationCache.js";
import { V2WebProtocolClient } from "../protocol/v2/webProtocolClient";
import { V2WebSocketTransport } from "../protocol/v2/webSocketTransport";
import {
  V2WebChatApplication,
  type V2WebChatSnapshot,
} from "./v2WebChatApplication";

export const V2_DEVICE_ID_STORAGE_KEY = "chat.v2.device-id";

type Environment = Record<string, string | boolean | undefined>;
type StorageLike = Pick<Storage, "getItem" | "setItem">;

export type V2PreviewRuntime =
  | { enabled: false; reason: string; dispose(): void }
  | {
      enabled: true;
      application: V2WebChatApplication;
      deviceIdentity: "persistent" | "ephemeral";
      dispose(): void;
    };

export interface V2RuntimeOptions {
  storage?: StorageLike | null;
  createUuid?: () => string;
  onChange?: (snapshot: V2WebChatSnapshot) => void;
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

  const endpoint = stringValue(environment.VITE_CHAT_V2_WSS_URL);
  const appVersion = stringValue(environment.VITE_CHAT_APP_VERSION);
  if (!endpoint) return disabled("V2 preview endpoint is missing");
  if (!appVersion || new TextEncoder().encode(appVersion).byteLength > 64) {
    return disabled("V2 preview app version is invalid");
  }

  const createUuid = options.createUuid ?? (() => crypto.randomUUID());
  try {
    const storage = options.storage === undefined ? safeLocalStorage() : options.storage;
    const identity = resolveDeviceIdentity(storage, createUuid);
    const transport = new V2WebSocketTransport({
      endpoint,
      createProtocolClient: () => new V2WebProtocolClient({
        appVersion,
        clientDeviceId: identity.deviceId,
        enableMessageEdits: true,
        enableMessageMentions: true,
        enableMessageForwarding: forwardingEnabled,
      }),
    });
    const application = new V2WebChatApplication({
      transport,
      cache: conversationCache,
      onChange: options.onChange,
      enableMessageForwarding: forwardingEnabled,
    });
    return {
      enabled: true,
      application,
      deviceIdentity: identity.persistence,
      dispose: () => application.dispose(),
    };
  } catch {
    return disabled("V2 preview configuration is invalid");
  }
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
