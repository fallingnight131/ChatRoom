import type { WebNotificationPermission } from "./webMessageNotification";

export const WEB_NOTIFICATION_PREFERENCE_KEY = "chat.v2.notifications-enabled";

export type WebNotificationPreferenceState =
  | "disabled"
  | "enabled"
  | "denied"
  | "unavailable"
  | "request-failed";

export interface WebNotificationPreferenceSnapshot {
  enabled: boolean;
  persistence: "browser" | "session";
  state: WebNotificationPreferenceState;
}

export interface WebNotificationPreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export interface WebNotificationPreferenceOptions {
  supported(): boolean;
  permission(): WebNotificationPermission;
  requestPermission(): Promise<WebNotificationPermission>;
  storage?: WebNotificationPreferenceStorage | null;
}

export class WebNotificationPreferenceController {
  private readonly options: WebNotificationPreferenceOptions;
  private current: WebNotificationPreferenceSnapshot;

  constructor(options: WebNotificationPreferenceOptions) {
    this.options = options;
    this.current = this.initialize();
  }

  get snapshot(): WebNotificationPreferenceSnapshot {
    return { ...this.current };
  }

  async enableFromUserGesture(): Promise<WebNotificationPreferenceSnapshot> {
    if (!this.isSupported()) return this.set(false, "unavailable", false);
    let permission: WebNotificationPermission;
    try {
      permission = await this.options.requestPermission();
    } catch {
      return this.set(false, "request-failed", false);
    }
    if (permission !== "granted") {
      return this.set(false, permission === "denied" ? "denied" : "disabled", false);
    }
    return this.set(true, "enabled", true);
  }

  disable(): WebNotificationPreferenceSnapshot {
    return this.set(false, "disabled", false);
  }

  refreshPermission(): WebNotificationPreferenceSnapshot {
    if (!this.isSupported()) return this.set(false, "unavailable", false);
    let permission: WebNotificationPermission;
    try {
      permission = this.options.permission();
    } catch {
      return this.set(false, "unavailable", false);
    }
    if (permission !== "granted" && this.current.enabled) {
      return this.set(false, permission === "denied" ? "denied" : "disabled", false);
    }
    return this.snapshot;
  }

  private initialize(): WebNotificationPreferenceSnapshot {
    if (!this.isSupported()) return { enabled: false, persistence: "browser", state: "unavailable" };
    let stored = false;
    let persistence: WebNotificationPreferenceSnapshot["persistence"] = "browser";
    try {
      stored = this.options.storage?.getItem(WEB_NOTIFICATION_PREFERENCE_KEY) === "true";
    } catch {
      persistence = "session";
    }
    let permission: WebNotificationPermission;
    try {
      permission = this.options.permission();
    } catch {
      return { enabled: false, persistence, state: "unavailable" };
    }
    if (stored && permission === "granted") return { enabled: true, persistence, state: "enabled" };
    if (stored) this.persist(false);
    return {
      enabled: false,
      persistence,
      state: permission === "denied" ? "denied" : "disabled",
    };
  }

  private isSupported(): boolean {
    try { return this.options.supported(); } catch { return false; }
  }

  private set(
    enabled: boolean,
    state: WebNotificationPreferenceState,
    storedValue: boolean,
  ): WebNotificationPreferenceSnapshot {
    const persistence = this.persist(storedValue) ? "browser" : "session";
    this.current = { enabled, persistence, state };
    return this.snapshot;
  }

  private persist(enabled: boolean): boolean {
    try {
      if (!this.options.storage) return false;
      this.options.storage.setItem(WEB_NOTIFICATION_PREFERENCE_KEY, String(enabled));
      return true;
    } catch {
      return false;
    }
  }
}
