export type WebPushSubscriptionState =
  | "disabled" | "enabled" | "unsupported" | "permission-denied"
  | "permission-failed" | "registration-failed" | "subscription-failed" | "server-failed"
  | "unsubscribe-failed";

export interface BrowserPushSubscriptionJson {
  endpoint?: string;
  expirationTime?: number | null;
  keys?: { p256dh?: string; auth?: string };
}

export interface BrowserPushSubscription {
  toJSON(): BrowserPushSubscriptionJson;
  unsubscribe(): Promise<boolean>;
}

export interface WebPushBrowserPort {
  supported(): boolean;
  permission(): NotificationPermission;
  requestPermission(): Promise<NotificationPermission>;
  registerWorker(): Promise<void>;
  currentSubscription(): Promise<BrowserPushSubscription | null>;
  subscribe(applicationServerKey: Uint8Array): Promise<BrowserPushSubscription>;
}

export interface WebPushSubscriptionApiPort {
  replace(installationId: string, subscription: BrowserPushSubscriptionJson): Promise<void>;
  delete(installationId: string): Promise<void>;
}

export interface WebPushSubscriptionSnapshot {
  enabled: boolean;
  pending: boolean;
  state: WebPushSubscriptionState;
}

const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export class WebPushSubscriptionController {
  private current: WebPushSubscriptionSnapshot;
  private inFlight = false;

  constructor(
    private readonly featureEnabled: boolean,
    private readonly installationId: string,
    private readonly applicationServerKey: Uint8Array,
    private readonly browser: WebPushBrowserPort,
    private readonly api: WebPushSubscriptionApiPort,
  ) {
    if (featureEnabled) {
      if (!canonicalUuid.test(installationId)) throw new Error("invalid Web Push installation ID");
      if (applicationServerKey.byteLength !== 65 || applicationServerKey[0] !== 0x04) {
        throw new Error("invalid Web Push application server key");
      }
    }
    this.applicationServerKey = applicationServerKey.slice();
    this.current = featureEnabled && this.isSupported()
      ? { enabled: false, pending: false, state: "disabled" }
      : { enabled: false, pending: false, state: "unsupported" };
  }

  get snapshot(): WebPushSubscriptionSnapshot { return { ...this.current }; }

  async refresh(): Promise<WebPushSubscriptionSnapshot> {
    if (!this.featureEnabled || !this.isSupported()) return this.set(false, "unsupported");
    try {
      const permission = this.browser.permission();
      if (permission !== "granted") return this.set(false,
        permission === "denied" ? "permission-denied" : "disabled");
      const subscription = await this.browser.currentSubscription();
      return this.set(Boolean(subscription), subscription ? "enabled" : "disabled");
    } catch { return this.set(false, "registration-failed"); }
  }

  async enableFromUserGesture(): Promise<WebPushSubscriptionSnapshot> {
    if (!this.featureEnabled || !this.isSupported() || this.inFlight) return this.snapshot;
    this.inFlight = true; this.current = { ...this.current, pending: true };
    let created: BrowserPushSubscription | null = null;
    let phase: "permission" | "registration" | "subscription" = "permission";
    try {
      const permission = await this.browser.requestPermission();
      if (permission !== "granted") return this.set(false,
        permission === "denied" ? "permission-denied" : "disabled");
      phase = "registration";
      await this.browser.registerWorker();
      let subscription = await this.browser.currentSubscription();
      if (!subscription) {
        phase = "subscription";
        subscription = await this.browser.subscribe(this.applicationServerKey.slice());
        created = subscription;
      }
      try { await this.api.replace(this.installationId, subscription.toJSON()); }
      catch {
        if (created) try { await created.unsubscribe(); } catch { /* rollback is best effort */ }
        return this.set(false, "server-failed");
      }
      return this.set(true, "enabled");
    } catch { return this.set(false, phase === "permission" ? "permission-failed"
      : phase === "subscription" ? "subscription-failed" : "registration-failed"); }
    finally { this.inFlight = false; this.current = { ...this.current, pending: false }; }
  }

  async disable(): Promise<WebPushSubscriptionSnapshot> {
    if (!this.featureEnabled || !this.isSupported() || this.inFlight) return this.snapshot;
    this.inFlight = true; this.current = { ...this.current, pending: true };
    try {
      const subscription = await this.browser.currentSubscription();
      try { await this.api.delete(this.installationId); }
      catch { return this.set(Boolean(subscription), "server-failed"); }
      if (subscription) {
        try {
          if (!await subscription.unsubscribe()) return this.set(true, "unsubscribe-failed");
        } catch { return this.set(true, "unsubscribe-failed"); }
      }
      return this.set(false, "disabled");
    } catch { return this.set(this.current.enabled, "registration-failed"); }
    finally { this.inFlight = false; this.current = { ...this.current, pending: false }; }
  }

  private isSupported(): boolean {
    try { return this.browser.supported(); } catch { return false; }
  }

  private set(enabled: boolean, state: WebPushSubscriptionState): WebPushSubscriptionSnapshot {
    this.current = { enabled, pending: false, state };
    return this.snapshot;
  }
}
