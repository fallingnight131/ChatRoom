import type {
  AttachmentReady,
  AttachmentRegistered,
  AttachmentUploadAuthorized,
} from "../protocol/v2/generated/attachment_pb";
import type { ProtocolError } from "../protocol/v2/generated/control_pb";
import type { V2WebProtocolEvent } from "../protocol/v2/webProtocolClient";
import type {
  V2WebSocketTransportObserver,
  V2WebSocketTransportState,
} from "../protocol/v2/webSocketTransport";

const MAX_BROWSER_SIMPLE_PUT_BYTES = 100 * 1024 * 1024;
const MIN_GRANT_REMAINING_MS = 5_000;
const MAX_GRANT_ATTEMPTS = 2;

export interface V2AttachmentSource {
  readonly name: string;
  readonly type: string;
  readonly size: number;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export interface V2AttachmentUploadTransport {
  readonly state: V2WebSocketTransportState;
  subscribe(observer: V2WebSocketTransportObserver): () => void;
  registerAttachment(
    conversationId: string,
    clientAttachmentId: string,
    fileName: string,
    mediaType: string,
    byteSize: bigint,
    contentSha256: Uint8Array,
  ): string;
  authorizeAttachmentUpload(attachmentId: string): string;
  completeAttachmentUpload(attachmentId: string): string;
  cancelAttachmentRequest(requestId: string): void;
}

export interface V2AttachmentDirectUploader {
  put(
    uploadUri: string,
    requiredHeaders: ReadonlyMap<string, string>,
    bytes: Uint8Array,
    signal: AbortSignal,
  ): Promise<void>;
}

export interface V2AttachmentUploadInput {
  conversationId: string;
  clientAttachmentId: string;
  source: V2AttachmentSource;
}

export interface V2AttachmentUploadResult {
  attachmentId: string;
  conversationId: string;
  clientAttachmentId: string;
  registrationDuplicate: boolean;
  readyDuplicate: boolean;
  readyAtEpochMs: number;
}

export class V2AttachmentUploadError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly retryable: boolean,
  ) {
    super(message);
    this.name = "V2AttachmentUploadError";
  }
}

type Waiter = {
  expected: string;
  requestId: string;
  resolve(event: V2WebProtocolEvent): void;
  reject(error: V2AttachmentUploadError): void;
};

export interface V2AttachmentUploadCoordinatorOptions {
  transport: V2AttachmentUploadTransport;
  uploader: V2AttachmentDirectUploader;
  digest?: (bytes: Uint8Array) => Promise<Uint8Array>;
  now?: () => number;
}

/** Serial, memory-only V2 Web attachment flow; owns no durable client state. */
export class V2AttachmentUploadCoordinator {
  private readonly transport: V2AttachmentUploadTransport;
  private readonly uploader: V2AttachmentDirectUploader;
  private readonly digest: (bytes: Uint8Array) => Promise<Uint8Array>;
  private readonly now: () => number;
  private readonly unsubscribe: () => void;
  private waiter: Waiter | null = null;
  private abortController: AbortController | null = null;
  private terminalError: V2AttachmentUploadError | null = null;
  private running = false;
  private disposed = false;

  constructor(options: V2AttachmentUploadCoordinatorOptions) {
    this.transport = options.transport;
    this.uploader = options.uploader;
    this.digest = options.digest ?? sha256;
    this.now = options.now ?? Date.now;
    this.unsubscribe = this.transport.subscribe({
      onProtocolEvent: (event) => this.handleEvent(event),
      onStateChange: (state) => this.handleState(state),
    });
  }

  async upload(input: V2AttachmentUploadInput): Promise<V2AttachmentUploadResult> {
    if (this.disposed) throw new V2AttachmentUploadError("attachment upload coordinator is closed", "CLOSED", false);
    if (this.running) throw new V2AttachmentUploadError("another attachment upload is active", "BUSY", true);
    if (this.transport.state !== "authenticated") {
      throw new V2AttachmentUploadError("V2 transport is not authenticated", "OFFLINE", true);
    }
    validateInput(input);
    this.running = true;
    this.abortController = new AbortController();
    this.terminalError = null;
    let bytes = new Uint8Array();
    let digest: Uint8Array<ArrayBufferLike> = new Uint8Array();
    try {
      bytes = new Uint8Array(await input.source.arrayBuffer());
      if (bytes.byteLength !== input.source.size) {
        throw new V2AttachmentUploadError("attachment source changed while reading", "SOURCE_CHANGED", false);
      }
      this.requireActive();
      digest = await this.digest(bytes);
      if (digest.byteLength !== 32) {
        throw new V2AttachmentUploadError("attachment digest provider returned invalid SHA-256", "HASH_FAILED", false);
      }
      this.requireActive();
      const mediaType = input.source.type || "application/octet-stream";
      const registered = await this.waitFor("attachment-registered", () =>
        this.transport.registerAttachment(
          input.conversationId,
          input.clientAttachmentId,
          input.source.name,
          mediaType,
          BigInt(bytes.byteLength),
          digest,
        ));
      assertRegistration(registered, input);

      const authorization = await this.freshAuthorization(registered.attachmentId);
      const headers = authorizationHeaders(authorization);
      this.requireActive();
      if (Number(authorization.expiresAtEpochMs) <= this.now()) {
        throw new V2AttachmentUploadError("attachment upload authorization expired", "GRANT_EXPIRED", true);
      }
      try {
        await this.uploader.put(
          authorization.uploadUri,
          headers,
          bytes,
          this.abortController.signal,
        );
      } catch (error) {
        if (this.terminalError) throw this.terminalError;
        throw error;
      }
      this.requireActive();

      const ready = await this.waitFor("attachment-ready", () =>
        this.transport.completeAttachmentUpload(registered.attachmentId));
      assertReady(ready, registered);
      return {
        attachmentId: ready.attachmentId,
        conversationId: ready.conversationId,
        clientAttachmentId: input.clientAttachmentId,
        registrationDuplicate: registered.duplicate,
        readyDuplicate: ready.duplicate,
        readyAtEpochMs: safeEpoch(ready.readyAtEpochMs),
      };
    } finally {
      bytes.fill(0);
      digest.fill(0);
      this.waiter = null;
      this.abortController = null;
      this.terminalError = null;
      this.running = false;
    }
  }

  cancel(): void {
    if (!this.running) return;
    this.terminalError = new V2AttachmentUploadError("attachment upload was cancelled", "CANCELLED", true);
    this.abortController?.abort();
    this.rejectWaiter(this.terminalError);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancel();
    this.unsubscribe();
  }

  private async freshAuthorization(attachmentId: string): Promise<AttachmentUploadAuthorized> {
    for (let attempt = 0; attempt < MAX_GRANT_ATTEMPTS; attempt += 1) {
      const authorization = await this.waitFor("attachment-upload-authorized", () =>
        this.transport.authorizeAttachmentUpload(attachmentId));
      if (authorization.attachmentId !== attachmentId) {
        throw new V2AttachmentUploadError("attachment authorization identity mismatch", "INVALID_RESPONSE", false);
      }
      const expiry = safeEpoch(authorization.expiresAtEpochMs);
      if (expiry > this.now() + MIN_GRANT_REMAINING_MS) return authorization;
    }
    throw new V2AttachmentUploadError("attachment upload authorization is too close to expiry", "GRANT_EXPIRED", true);
  }

  private waitFor<T extends "attachment-registered" | "attachment-upload-authorized" | "attachment-ready">(
    expected: T,
    action: () => string,
  ): Promise<EventValue<T>> {
    this.requireActive();
    if (this.waiter) throw new V2AttachmentUploadError("attachment response is already pending", "BUSY", true);
    return new Promise<EventValue<T>>((resolve, reject) => {
      try {
        const requestId = action();
        this.waiter = {
          expected,
          requestId,
          resolve: (event) => (resolve as (value: unknown) => void)(event.value),
          reject,
        };
      } catch {
        this.waiter = null;
        reject(new V2AttachmentUploadError("attachment command could not be sent", "TRANSPORT_UNAVAILABLE", true));
      }
    });
  }

  private handleEvent(event: V2WebProtocolEvent): void {
    const waiter = this.waiter;
    if (!waiter) return;
    if (event.requestId !== waiter.requestId) return;
    if (event.type === "protocol-error") {
      this.waiter = null;
      waiter.reject(protocolFailure(event.value));
      return;
    }
    if (event.type !== waiter.expected) return;
    this.waiter = null;
    waiter.resolve(event);
  }

  private handleState(state: V2WebSocketTransportState): void {
    if (this.running && state !== "authenticated") {
      this.terminalError = new V2AttachmentUploadError("attachment upload connection was lost", "OFFLINE", true);
      this.abortController?.abort();
      this.rejectWaiter(this.terminalError);
    }
  }

  private rejectWaiter(error: V2AttachmentUploadError): void {
    const waiter = this.waiter;
    this.waiter = null;
    if (waiter) this.transport.cancelAttachmentRequest(waiter.requestId);
    waiter?.reject(error);
  }

  private requireActive(): void {
    if (this.disposed) throw new V2AttachmentUploadError("attachment upload coordinator is closed", "CLOSED", false);
    if (this.terminalError) throw this.terminalError;
    if (this.abortController?.signal.aborted) {
      throw new V2AttachmentUploadError("attachment upload was cancelled", "CANCELLED", true);
    }
    if (this.transport.state !== "authenticated") {
      throw new V2AttachmentUploadError("attachment upload connection was lost", "OFFLINE", true);
    }
  }
}

type EventValue<T extends string> = Extract<V2WebProtocolEvent, { type: T }>["value"];

export function createFetchV2AttachmentUploader(
  fetcher: typeof fetch = fetch,
): V2AttachmentDirectUploader {
  return {
    async put(uploadUri, requiredHeaders, bytes, signal) {
      let response: Response;
      try {
        response = await fetcher(uploadUri, {
          method: "PUT",
          headers: Object.fromEntries(requiredHeaders),
          body: bytes.slice().buffer,
          credentials: "omit",
          cache: "no-store",
          redirect: "error",
          signal,
        });
      } catch {
        throw new V2AttachmentUploadError("attachment upload request failed", "UPLOAD_FAILED", true);
      }
      if (!response.ok) {
        throw new V2AttachmentUploadError("attachment upload was rejected", "UPLOAD_REJECTED", response.status >= 500 || response.status === 429);
      }
    },
  };
}

async function sha256(bytes: Uint8Array): Promise<Uint8Array> {
  if (!globalThis.crypto?.subtle) {
    throw new V2AttachmentUploadError("SHA-256 is unavailable", "HASH_FAILED", false);
  }
  return new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", bytes.slice().buffer));
}

function validateInput(input: V2AttachmentUploadInput): void {
  if (!input.conversationId || !input.clientAttachmentId) {
    throw new V2AttachmentUploadError("attachment identity is missing", "INVALID_INPUT", false);
  }
  if (!input.source.name || input.source.name === "." || input.source.name === ".."
      || input.source.name.includes("/") || input.source.name.includes("\\")) {
    throw new V2AttachmentUploadError("attachment file name is invalid", "INVALID_INPUT", false);
  }
  if (!Number.isSafeInteger(input.source.size) || input.source.size < 1
      || input.source.size > MAX_BROWSER_SIMPLE_PUT_BYTES) {
    throw new V2AttachmentUploadError("attachment exceeds the Web simple-upload limit", "INVALID_INPUT", false);
  }
}

function assertRegistration(
  value: AttachmentRegistered,
  input: V2AttachmentUploadInput,
): void {
  if (value.conversationId !== input.conversationId
      || value.clientAttachmentId !== input.clientAttachmentId) {
    throw new V2AttachmentUploadError("attachment registration identity mismatch", "INVALID_RESPONSE", false);
  }
}

function assertReady(value: AttachmentReady, registration: AttachmentRegistered): void {
  if (value.attachmentId !== registration.attachmentId
      || value.conversationId !== registration.conversationId) {
    throw new V2AttachmentUploadError("attachment completion identity mismatch", "INVALID_RESPONSE", false);
  }
}

function authorizationHeaders(value: AttachmentUploadAuthorized): ReadonlyMap<string, string> {
  const headers = new Map<string, string>();
  for (const header of value.requiredHeaders) {
    if (headers.has(header.name)) {
      throw new V2AttachmentUploadError("attachment authorization repeated a header", "INVALID_RESPONSE", false);
    }
    headers.set(header.name, header.value);
  }
  return headers;
}

function protocolFailure(value: ProtocolError): V2AttachmentUploadError {
  return new V2AttachmentUploadError("attachment command was rejected", `PROTOCOL_${value.code}`, value.retryable);
}

function safeEpoch(value: bigint): number {
  const result = Number(value);
  if (!Number.isSafeInteger(result) || result <= 0) {
    throw new V2AttachmentUploadError("attachment response timestamp is invalid", "INVALID_RESPONSE", false);
  }
  return result;
}
