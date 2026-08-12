export interface BrowserLocationLike {
  protocol: string;
  hostname: string;
  host: string;
  origin: string;
}

export interface WebEndpointEnvironment {
  VITE_CHAT_V1_WS_PATH?: unknown;
}

export type WebEndpointPolicy =
  | {
      usable: true;
      mode: "local-development" | "same-origin-production";
      websocketUrl: string;
      httpBaseUrl: string;
    }
  | {
      usable: false;
      mode: "blocked";
      reason: string;
    };

export interface StorageLike {
  removeItem(key: string): void;
}

const LEGACY_SERVER_KEYS = ["serverHost", "serverPort", "wsPath"] as const;
const DEFAULT_PRODUCTION_WS_PATH = "/ws";
const DEFAULT_LOCAL_WS_PORT = 9528;

function isLoopbackHostname(hostname: string): boolean {
  const normalized = hostname.toLowerCase();
  return normalized === "localhost" || normalized === "127.0.0.1" || normalized === "[::1]";
}

function normalizeWebSocketPath(value: unknown, fallback: string): string | null {
  const candidate = value == null || value === "" ? fallback : value;
  if (typeof candidate !== "string" || candidate.length > 128) return null;
  if (candidate === "") return "";
  if (!candidate.startsWith("/") || candidate.startsWith("//")) return null;
  if (candidate.includes("?") || candidate.includes("#") || candidate.includes("\\")) return null;
  if (!/^\/[A-Za-z0-9/_-]+$/.test(candidate)) return null;

  const segments = candidate.split("/");
  if (segments.some(segment => segment === "." || segment === "..")) return null;
  return candidate;
}

export function resolveWebEndpointPolicy(
  browserLocation: BrowserLocationLike | null | undefined,
  environment: WebEndpointEnvironment = {},
): WebEndpointPolicy {
  if (!browserLocation) {
    return { usable: false, mode: "blocked", reason: "浏览器地址不可用" };
  }

  let pageOrigin: URL;
  try {
    pageOrigin = new URL(browserLocation.origin);
  } catch {
    return { usable: false, mode: "blocked", reason: "浏览器来源无效" };
  }
  if (pageOrigin.protocol !== browserLocation.protocol ||
      pageOrigin.hostname !== browserLocation.hostname ||
      pageOrigin.host !== browserLocation.host ||
      pageOrigin.origin !== browserLocation.origin) {
    return { usable: false, mode: "blocked", reason: "浏览器来源不一致" };
  }

  const local = isLoopbackHostname(browserLocation.hostname);
  const defaultPath = local ? "" : DEFAULT_PRODUCTION_WS_PATH;
  const websocketPath = normalizeWebSocketPath(environment.VITE_CHAT_V1_WS_PATH, defaultPath);
  if (websocketPath === null) {
    return { usable: false, mode: "blocked", reason: "WebSocket 路径配置无效" };
  }

  if (!local && browserLocation.protocol !== "https:") {
    return { usable: false, mode: "blocked", reason: "生产 Web 客户端必须通过 HTTPS 访问" };
  }
  if (local && browserLocation.protocol !== "http:" && browserLocation.protocol !== "https:") {
    return { usable: false, mode: "blocked", reason: "本地开发页面必须使用 HTTP 或 HTTPS" };
  }

  if (local && websocketPath === "") {
    const protocol = browserLocation.protocol === "https:" ? "wss:" : "ws:";
    const hostname = browserLocation.hostname === "[::1]" ? "[::1]" : browserLocation.hostname;
    return {
      usable: true,
      mode: "local-development",
      websocketUrl: `${protocol}//${hostname}:${DEFAULT_LOCAL_WS_PORT}`,
      httpBaseUrl: pageOrigin.origin,
    };
  }

  const websocketUrl = new URL(pageOrigin.origin);
  websocketUrl.protocol = browserLocation.protocol === "https:" ? "wss:" : "ws:";
  websocketUrl.pathname = websocketPath;
  websocketUrl.search = "";
  websocketUrl.hash = "";
  return {
    usable: true,
    mode: local ? "local-development" : "same-origin-production",
    websocketUrl: websocketUrl.toString(),
    httpBaseUrl: pageOrigin.origin,
  };
}

export function purgeLegacyServerOverrides(storage: StorageLike | null | undefined): void {
  if (!storage) return;
  for (const key of LEGACY_SERVER_KEYS) {
    try {
      storage.removeItem(key);
    } catch {
      // A denied storage API must not prevent the endpoint policy from loading.
    }
  }
}
