import type { V2WebProtocolClient, V2WebProtocolEvent } from "./webProtocolClient";

const SUBPROTOCOL = "chat.v2";
const OPEN = 1;

export type V2WebSocketTransportState =
  | "idle"
  | "connecting"
  | "negotiating"
  | "connected"
  | "resuming"
  | "authenticated"
  | "offline"
  | "reconnect-wait"
  | "stopped";

export interface V2WebSocketLike {
  binaryType: string;
  readonly protocol: string;
  readonly readyState: number;
  onopen: ((event: Event) => unknown) | null;
  onmessage: ((event: MessageEvent) => unknown) | null;
  onerror: ((event: Event) => unknown) | null;
  onclose: ((event: CloseEvent) => unknown) | null;
  send(data: ArrayBuffer): void;
  close(code?: number, reason?: string): void;
}

type TimerHandle = ReturnType<typeof globalThis.setTimeout>;
type NetworkObserver = { onOnline(): void; onOffline(): void };

export interface V2WebSocketTransportOptions {
  endpoint: string;
  createProtocolClient: () => V2WebProtocolClient;
  createSocket?: (endpoint: string, protocols: string[]) => V2WebSocketLike;
  setTimer?: (callback: () => void, delayMs: number) => TimerHandle;
  clearTimer?: (handle: TimerHandle) => void;
  random?: () => number;
  connectTimeoutMs?: number;
  helloTimeoutMs?: number;
  authenticationTimeoutMs?: number;
  reconnectBaseMs?: number;
  reconnectMaximumMs?: number;
  isOnline?: () => boolean;
  observeNetwork?: (observer: NetworkObserver) => () => void;
  onStateChange?: (state: V2WebSocketTransportState) => void;
  onProtocolEvent?: (event: V2WebProtocolEvent) => void;
  onFailure?: (reason: string) => void;
}

export interface V2WebSocketTransportObserver {
  onStateChange?: (state: V2WebSocketTransportState) => void;
  onProtocolEvent?: (event: V2WebProtocolEvent) => void;
  onFailure?: (reason: string) => void;
}

export class V2WebSocketTransport {
  private readonly endpoint: string;
  private readonly createProtocolClient: () => V2WebProtocolClient;
  private readonly createSocket: (endpoint: string, protocols: string[]) => V2WebSocketLike;
  private readonly setTimer: (callback: () => void, delayMs: number) => TimerHandle;
  private readonly clearTimer: (handle: TimerHandle) => void;
  private readonly random: () => number;
  private readonly connectTimeoutMs: number;
  private readonly helloTimeoutMs: number;
  private readonly authenticationTimeoutMs: number;
  private readonly reconnectBaseMs: number;
  private readonly reconnectMaximumMs: number;
  private readonly isOnline: () => boolean;
  private readonly observeNetwork: (observer: NetworkObserver) => () => void;
  private readonly onStateChange?: (state: V2WebSocketTransportState) => void;
  private readonly onProtocolEvent?: (event: V2WebProtocolEvent) => void;
  private readonly onFailure?: (reason: string) => void;
  private readonly observers = new Set<V2WebSocketTransportObserver>();
  private socket: V2WebSocketLike | null = null;
  private protocolClient: V2WebProtocolClient | null = null;
  private phaseTimer: TimerHandle | null = null;
  private reconnectTimer: TimerHandle | null = null;
  private reconnectAttempt = 0;
  private unsubscribeNetwork: (() => void) | null = null;
  private resumeCredential: { sessionId: string; token: Uint8Array } | null = null;
  private desired = false;
  private currentState: V2WebSocketTransportState = "idle";

  constructor(options: V2WebSocketTransportOptions) {
    this.endpoint = requireWebEndpoint(options.endpoint);
    this.createProtocolClient = options.createProtocolClient;
    this.createSocket = options.createSocket ?? ((endpoint, protocols) => new WebSocket(endpoint, protocols));
    this.setTimer = options.setTimer ?? globalThis.setTimeout;
    this.clearTimer = options.clearTimer ?? globalThis.clearTimeout;
    this.random = options.random ?? Math.random;
    this.connectTimeoutMs = positiveDuration("connectTimeoutMs", options.connectTimeoutMs ?? 10_000);
    this.helloTimeoutMs = positiveDuration("helloTimeoutMs", options.helloTimeoutMs ?? 5_000);
    this.authenticationTimeoutMs = positiveDuration(
      "authenticationTimeoutMs",
      options.authenticationTimeoutMs ?? 15_000,
    );
    this.reconnectBaseMs = positiveDuration("reconnectBaseMs", options.reconnectBaseMs ?? 500);
    this.reconnectMaximumMs = positiveDuration("reconnectMaximumMs", options.reconnectMaximumMs ?? 30_000);
    if (this.reconnectBaseMs > this.reconnectMaximumMs) {
      throw new Error("reconnectBaseMs must not exceed reconnectMaximumMs");
    }
    this.isOnline = options.isOnline ?? browserIsOnline;
    this.observeNetwork = options.observeNetwork ?? observeBrowserNetwork;
    this.onStateChange = options.onStateChange;
    this.onProtocolEvent = options.onProtocolEvent;
    this.onFailure = options.onFailure;
  }

  get state(): V2WebSocketTransportState {
    return this.currentState;
  }

  subscribe(observer: V2WebSocketTransportObserver): () => void {
    this.observers.add(observer);
    return () => this.observers.delete(observer);
  }

  start(): void {
    if (this.desired) return;
    this.desired = true;
    try {
      this.unsubscribeNetwork = this.observeNetwork({
        onOnline: () => this.handleOnline(),
        onOffline: () => this.handleOffline(),
      });
    } catch {
      this.unsubscribeNetwork = null;
    }
    if (!this.isOnline()) {
      this.transition("offline");
      return;
    }
    this.connect();
  }

  authenticate(username: string, passwordUtf8: Uint8Array): void {
    if (this.currentState !== "connected" || !this.protocolClient) {
      throw new Error("V2 transport is not ready for authentication");
    }
    this.send(this.protocolClient.authenticate(username, passwordUtf8));
    this.armPhaseTimeout(this.authenticationTimeoutMs, "V2 authentication timeout");
  }

  resumeSession(sessionId: string, resumeToken: Uint8Array): void {
    if (this.currentState !== "connected" || !this.protocolClient) {
      throw new Error("V2 transport is not ready for session resume");
    }
    this.send(this.protocolClient.resumeSession(sessionId, resumeToken));
    this.armPhaseTimeout(this.authenticationTimeoutMs, "V2 authentication timeout");
  }

  listConversations(limit: number, after?: { updatedAtEpochMs: bigint; conversationId: string }): void {
    this.send(this.requireAuthenticated().listConversations(limit, after));
  }

  readMessageHistory(conversationId: string, afterSequence: bigint, limit: number): void {
    this.send(this.requireAuthenticated().readMessageHistory(conversationId, afterSequence, limit));
  }

  submitText(conversationId: string, clientMessageId: string, text: string): void {
    this.send(this.requireAuthenticated().submitText(conversationId, clientMessageId, text));
  }

  stop(): void {
    this.desired = false;
    this.unsubscribeNetwork?.();
    this.unsubscribeNetwork = null;
    this.cancelTimers();
    const socket = this.socket;
    this.socket = null;
    this.clearProtocolClient();
    this.clearResumeCredential();
    if (socket) safeClose(socket, 1000, "client stopped");
    this.transition("stopped");
  }

  private connect(): void {
    if (!this.desired || this.socket) return;
    if (!this.isOnline()) {
      this.transition("offline");
      return;
    }
    this.cancelPhaseTimer();
    this.transition("connecting");
    let socket: V2WebSocketLike;
    try {
      socket = this.createSocket(this.endpoint, [SUBPROTOCOL]);
    } catch {
      this.emitFailure("V2 WebSocket could not be created");
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    socket.binaryType = "arraybuffer";
    socket.onopen = () => this.handleOpen(socket);
    socket.onmessage = (event) => this.handleMessage(socket, event.data);
    socket.onerror = () => this.emitFailure("V2 WebSocket transport error");
    socket.onclose = () => this.handleClose(socket);
    this.armPhaseTimeout(this.connectTimeoutMs, "V2 connection timeout", socket);
  }

  private handleOpen(socket: V2WebSocketLike): void {
    if (socket !== this.socket || !this.desired) return;
    this.cancelPhaseTimer();
    if (socket.protocol !== SUBPROTOCOL) {
      this.failSocket(socket, "V2 WebSocket subprotocol mismatch", 1002);
      return;
    }
    let hello: Uint8Array;
    try {
      this.clearProtocolClient();
      this.protocolClient = this.createProtocolClient();
      this.transition("negotiating");
      hello = this.protocolClient.createClientHello();
    } catch {
      this.failSocket(socket, "V2 hello could not be sent", 1002);
      return;
    }
    try { this.send(hello); } catch { return; }
    this.armPhaseTimeout(this.helloTimeoutMs, "V2 hello timeout", socket);
  }

  private handleMessage(socket: V2WebSocketLike, data: unknown): void {
    if (socket !== this.socket || !this.protocolClient) return;
    if (!(data instanceof ArrayBuffer)) {
      this.failSocket(socket, "V2 WebSocket received a non-binary frame", 1002);
      return;
    }
    try {
      const event = this.protocolClient.receive(new Uint8Array(data));
      let observableEvent = event;
      if (event.type === "server-hello") {
        this.cancelPhaseTimer();
        if (this.resumeCredential) {
          const credential = this.resumeCredential;
          this.transition("resuming");
          try {
            this.send(this.protocolClient.resumeSession(credential.sessionId, credential.token));
            this.armPhaseTimeout(this.authenticationTimeoutMs, "V2 authentication timeout", socket);
          } catch {
            return;
          }
        } else {
          this.transition("connected");
        }
      } else if (event.type === "session-established") {
        this.cancelPhaseTimer();
        this.replaceResumeCredential(event.value.sessionId, event.value.resumeToken);
        observableEvent = {
          ...event,
          value: { ...event.value, resumeToken: new Uint8Array() },
        };
        this.reconnectAttempt = 0;
        this.transition("authenticated");
      } else if (event.type === "authentication-rejected") {
        this.cancelPhaseTimer();
        this.clearResumeCredential();
      }
      this.emitProtocolEvent(observableEvent);
    } catch {
      this.failSocket(socket, "V2 WebSocket received invalid protocol data", 1002);
    }
  }

  private handleClose(socket: V2WebSocketLike): void {
    if (socket !== this.socket) return;
    this.cancelPhaseTimer();
    this.socket = null;
    this.clearProtocolClient();
    if (!this.desired) return;
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer || !this.desired) return;
    if (!this.isOnline()) {
      this.transition("offline");
      return;
    }
    const exponent = Math.min(this.reconnectAttempt, 30);
    const ceiling = Math.min(this.reconnectMaximumMs, this.reconnectBaseMs * (2 ** exponent));
    this.reconnectAttempt += 1;
    const delay = Math.floor(boundedRandom(this.random()) * ceiling);
    this.transition("reconnect-wait");
    this.reconnectTimer = this.setTimer(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }

  private handleOffline(): void {
    if (!this.desired) return;
    this.cancelTimers();
    const socket = this.socket;
    this.socket = null;
    this.clearProtocolClient();
    if (socket) safeClose(socket, 1001, "network offline");
    this.transition("offline");
  }

  private handleOnline(): void {
    if (!this.desired || !this.isOnline()) return;
    if (this.reconnectTimer !== null) this.clearTimer(this.reconnectTimer);
    this.reconnectTimer = null;
    this.reconnectAttempt = 0;
    this.connect();
  }

  private armPhaseTimeout(delayMs: number, reason: string, expectedSocket = this.socket): void {
    this.cancelPhaseTimer();
    this.phaseTimer = this.setTimer(() => {
      this.phaseTimer = null;
      if (expectedSocket && expectedSocket === this.socket) this.failSocket(expectedSocket, reason, 4000);
    }, delayMs);
  }

  private failSocket(socket: V2WebSocketLike, reason: string, code: number): void {
    this.emitFailure(reason);
    safeClose(socket, code, reason);
  }

  private send(bytes: Uint8Array): void {
    if (!this.socket || this.socket.readyState !== OPEN) throw new Error("V2 WebSocket is not open");
    try {
      this.socket.send(bytes.slice().buffer);
    } catch {
      this.failSocket(this.socket, "V2 WebSocket send failed", 1011);
      throw new Error("V2 WebSocket send failed");
    }
  }

  private requireAuthenticated(): V2WebProtocolClient {
    if (this.currentState !== "authenticated" || !this.protocolClient) {
      throw new Error("V2 transport is not authenticated");
    }
    return this.protocolClient;
  }

  private clearProtocolClient(): void {
    this.protocolClient?.close();
    this.protocolClient = null;
  }

  private replaceResumeCredential(sessionId: string, token: Uint8Array): void {
    this.clearResumeCredential();
    this.resumeCredential = { sessionId, token: token.slice() };
  }

  private clearResumeCredential(): void {
    this.resumeCredential?.token.fill(0);
    this.resumeCredential = null;
  }

  private cancelPhaseTimer(): void {
    if (this.phaseTimer !== null) this.clearTimer(this.phaseTimer);
    this.phaseTimer = null;
  }

  private cancelTimers(): void {
    this.cancelPhaseTimer();
    if (this.reconnectTimer !== null) this.clearTimer(this.reconnectTimer);
    this.reconnectTimer = null;
  }

  private transition(state: V2WebSocketTransportState): void {
    if (state === this.currentState) return;
    this.currentState = state;
    try { this.onStateChange?.(state); } catch { /* observers do not own transport */ }
    for (const observer of this.observers) {
      try { observer.onStateChange?.(state); } catch { /* observers do not own transport */ }
    }
  }

  private emitProtocolEvent(event: V2WebProtocolEvent): void {
    try { this.onProtocolEvent?.(event); } catch { /* observers do not own transport */ }
    for (const observer of this.observers) {
      try { observer.onProtocolEvent?.(event); } catch { /* observers do not own transport */ }
    }
  }

  private emitFailure(reason: string): void {
    try { this.onFailure?.(reason); } catch { /* observers do not own transport */ }
    for (const observer of this.observers) {
      try { observer.onFailure?.(reason); } catch { /* observers do not own transport */ }
    }
  }
}

function requireWebEndpoint(value: string): string {
  const endpoint = new URL(value);
  if (endpoint.protocol !== "wss:" || endpoint.pathname !== "/v2/web"
      || endpoint.search !== "" || endpoint.hash !== "" || endpoint.username || endpoint.password) {
    throw new Error("V2 Web endpoint must be an exact wss:// authority/v2/web URL");
  }
  return endpoint.toString();
}

function positiveDuration(field: string, value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${field} must be a positive integer`);
  return value;
}

function boundedRandom(value: number): number {
  if (!Number.isFinite(value)) return 0.5;
  return Math.min(Math.max(value, 0), 0.999999999999);
}

function safeClose(socket: V2WebSocketLike, code: number, reason: string): void {
  try { socket.close(code, reason.slice(0, 123)); } catch { /* close is best effort */ }
}

function browserIsOnline(): boolean {
  try { return typeof navigator === "undefined" || navigator.onLine !== false; }
  catch { return true; }
}

function observeBrowserNetwork(observer: NetworkObserver): () => void {
  if (typeof globalThis.addEventListener !== "function") return () => {};
  globalThis.addEventListener("online", observer.onOnline);
  globalThis.addEventListener("offline", observer.onOffline);
  return () => {
    globalThis.removeEventListener("online", observer.onOnline);
    globalThis.removeEventListener("offline", observer.onOffline);
  };
}
