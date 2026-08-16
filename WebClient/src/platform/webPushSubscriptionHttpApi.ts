import type {
  BrowserPushSubscriptionJson,
  WebPushSubscriptionApiPort,
} from "./webPushSubscriptionController";

export interface WebPushHttpCredentialLease {
  withCredentials<T>(
    action: (bearerToken: Uint8Array, csrfToken: Uint8Array) => Promise<T>,
  ): Promise<T>;
}

export type WebPushSubscriptionHttpErrorCode =
  | "INVALID_SUBSCRIPTION" | "INVALID_CREDENTIALS" | "UNAUTHORIZED"
  | "FORBIDDEN" | "LIMIT_REACHED" | "RATE_LIMITED" | "UNAVAILABLE";

export class WebPushSubscriptionHttpError extends Error {
  constructor(
    readonly code: WebPushSubscriptionHttpErrorCode,
    readonly retryAfterSeconds?: number,
  ) {
    super(`Web Push subscription HTTP failure: ${code}`);
    this.name = "WebPushSubscriptionHttpError";
  }
}

const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const tokenPattern = /^[A-Za-z0-9_-]{32,256}$/;

export function createWebPushSubscriptionHttpApi(options: {
  origin: string;
  credentials: WebPushHttpCredentialLease;
  fetch?: typeof globalThis.fetch;
}): WebPushSubscriptionApiPort {
  const origin = exactHttpsOrigin(options.origin);
  const fetcher = options.fetch ?? globalThis.fetch;
  if (typeof fetcher !== "function") throw new Error("Web Push fetch is unavailable");
  const execute = async (installationId: string, method: "PUT" | "DELETE",
    subscription?: BrowserPushSubscriptionJson): Promise<void> => {
    if (!canonicalUuid.test(installationId)) throw failure("INVALID_SUBSCRIPTION");
    const body = method === "PUT" ? encodeSubscription(subscription) : undefined;
    await options.credentials.withCredentials(async (bearerBytes, csrfBytes) => {
      const bearer = asciiToken(bearerBytes);
      const csrf = asciiToken(csrfBytes);
      let response: Response;
      try {
        response = await fetcher(
          `${origin}/api/v2/web-push/subscriptions/${installationId}`,
          {
            method, body,
            credentials: "omit", redirect: "error", cache: "no-store",
            mode: "same-origin", referrerPolicy: "no-referrer",
            headers: {
              Authorization: `Bearer ${bearer}`,
              "X-CSRF-Token": csrf,
              ...(body === undefined ? {} : { "Content-Type": "application/json" }),
            },
          });
      } catch { throw failure("UNAVAILABLE"); }
      try {
        if (response.status === 204) return;
        if (response.status === 401) throw failure("UNAUTHORIZED");
        if (response.status === 403) throw failure("FORBIDDEN");
        if (response.status === 400 || response.status === 413 || response.status === 415) {
          throw failure("INVALID_SUBSCRIPTION");
        }
        if (response.status === 409) throw failure("LIMIT_REACHED");
        if (response.status === 429) throw new WebPushSubscriptionHttpError(
          "RATE_LIMITED", retryAfter(response.headers.get("Retry-After")));
        throw failure("UNAVAILABLE");
      } finally {
        try { await response.body?.cancel(); } catch { /* response text is discarded */ }
      }
    });
  };
  return {
    replace: (installationId, subscription) => execute(installationId, "PUT", subscription),
    delete: installationId => execute(installationId, "DELETE"),
  };
}

function encodeSubscription(value: BrowserPushSubscriptionJson | undefined): string {
  if (!value || typeof value.endpoint !== "string" || value.endpoint.length > 2_048
      || !value.keys || typeof value.keys.p256dh !== "string"
      || typeof value.keys.auth !== "string") throw failure("INVALID_SUBSCRIPTION");
  let endpoint: URL;
  try { endpoint = new URL(value.endpoint); } catch { throw failure("INVALID_SUBSCRIPTION"); }
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password
      || endpoint.hash || endpoint.hostname !== endpoint.hostname.toLowerCase()
      || endpoint.port === "443" || endpoint.toString() !== value.endpoint) {
    throw failure("INVALID_SUBSCRIPTION");
  }
  const p256dh = decodeBase64Url(value.keys.p256dh);
  const auth = decodeBase64Url(value.keys.auth);
  if (p256dh.byteLength !== 65 || p256dh[0] !== 0x04 || auth.byteLength !== 16) {
    throw failure("INVALID_SUBSCRIPTION");
  }
  const expirationTime = value.expirationTime;
  if (expirationTime !== null && expirationTime !== undefined
      && (!Number.isSafeInteger(expirationTime) || expirationTime <= 0)) {
    throw failure("INVALID_SUBSCRIPTION");
  }
  return JSON.stringify({ endpoint: value.endpoint,
    expirationTime: expirationTime ?? null,
    keys: { p256dh: value.keys.p256dh, auth: value.keys.auth } });
}

function exactHttpsOrigin(value: string): string {
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" || parsed.origin !== value) throw new Error("invalid API origin");
  return value;
}

function asciiToken(value: Uint8Array): string {
  if (value.some(byte => byte > 0x7f)) throw failure("INVALID_CREDENTIALS");
  const decoded = new TextDecoder("ascii", { fatal: true }).decode(value);
  if (!tokenPattern.test(decoded)) throw failure("INVALID_CREDENTIALS");
  return decoded;
}

function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) throw failure("INVALID_SUBSCRIPTION");
  try {
    const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
    const decoded = atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="));
    return Uint8Array.from(decoded, character => character.charCodeAt(0));
  } catch { throw failure("INVALID_SUBSCRIPTION"); }
}

function retryAfter(value: string | null): number | undefined {
  if (!value || !/^[0-9]{1,4}$/.test(value)) return undefined;
  const seconds = Number(value);
  return seconds >= 1 && seconds <= 3_600 ? seconds : undefined;
}

function failure(code: WebPushSubscriptionHttpErrorCode): WebPushSubscriptionHttpError {
  return new WebPushSubscriptionHttpError(code);
}
