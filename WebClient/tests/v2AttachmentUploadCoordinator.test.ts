import assert from "node:assert/strict";
import test from "node:test";

import { create } from "@bufbuild/protobuf";

import {
  AttachmentReadySchema,
  AttachmentRegisteredSchema,
  AttachmentUploadAuthorizedSchema,
} from "../src/protocol/v2/generated/attachment_pb";
import { ProtocolErrorCode, ProtocolErrorSchema } from "../src/protocol/v2/generated/control_pb";
import {
  V2AttachmentUploadCoordinator,
  V2AttachmentUploadError,
  createFetchV2AttachmentUploader,
  type V2AttachmentDirectUploader,
  type V2AttachmentUploadTransport,
} from "../src/application/v2AttachmentUploadCoordinator";
import type { V2WebProtocolEvent } from "../src/protocol/v2/webProtocolClient";
import type {
  V2WebSocketTransportObserver,
  V2WebSocketTransportState,
} from "../src/protocol/v2/webSocketTransport";

const CONVERSATION_ID = "50000000-0000-4000-8000-000000000001";
const ATTACHMENT_ID = "60000000-0000-4000-8000-000000000001";
const CLIENT_ATTACHMENT_ID = "client-attachment-1";
const NOW = 1_800_000_000_000;
const SOURCE_BYTES = Uint8Array.from([1, 2, 3, 4]);

class FakeTransport implements V2AttachmentUploadTransport {
  state: V2WebSocketTransportState = "authenticated";
  observer: V2WebSocketTransportObserver | null = null;
  readonly calls: string[] = [];
  onRegister: (() => void) | null = null;
  onAuthorize: (() => void) | null = null;
  onComplete: (() => void) | null = null;
  authorizationCalls = 0;
  readonly cancelledRequests: string[] = [];

  subscribe(observer: V2WebSocketTransportObserver): () => void {
    this.observer = observer;
    return () => { if (this.observer === observer) this.observer = null; };
  }

  registerAttachment(
    conversationId: string,
    clientAttachmentId: string,
    fileName: string,
    mediaType: string,
    byteSize: bigint,
    contentSha256: Uint8Array,
  ): string {
    this.calls.push(`register:${conversationId}:${clientAttachmentId}:${fileName}:${mediaType}:${byteSize}:${contentSha256.length}`);
    queueMicrotask(() => this.onRegister?.());
    return "request-1";
  }

  authorizeAttachmentUpload(attachmentId: string): string {
    this.authorizationCalls += 1;
    this.calls.push(`authorize:${attachmentId}`);
    queueMicrotask(() => this.onAuthorize?.());
    return "request-2";
  }

  completeAttachmentUpload(attachmentId: string): string {
    this.calls.push(`complete:${attachmentId}`);
    queueMicrotask(() => this.onComplete?.());
    return "request-3";
  }

  cancelAttachmentRequest(requestId: string): void { this.cancelledRequests.push(requestId); }

  event(event: V2WebProtocolEvent): void { this.observer?.onProtocolEvent?.(event); }

  transition(state: V2WebSocketTransportState): void {
    this.state = state;
    this.observer?.onStateChange?.(state);
  }
}

function registered(duplicate = false): V2WebProtocolEvent {
  return {
    type: "attachment-registered",
    requestId: "request-1",
    clientMessageId: CLIENT_ATTACHMENT_ID,
    value: create(AttachmentRegisteredSchema, {
      attachmentId: ATTACHMENT_ID,
      conversationId: CONVERSATION_ID,
      clientAttachmentId: CLIENT_ATTACHMENT_ID,
      duplicate,
    }),
  };
}

function authorization(expiresAt: number): V2WebProtocolEvent {
  return {
    type: "attachment-upload-authorized",
    requestId: "request-2",
    clientMessageId: "",
    value: create(AttachmentUploadAuthorizedSchema, {
      attachmentId: ATTACHMENT_ID,
      uploadUri: "https://objects.example.test/key?signature=secret",
      requiredHeaders: [
        { name: "content-type", value: "application/octet-stream" },
        { name: "if-none-match", value: "*" },
      ],
      expiresAtEpochMs: BigInt(expiresAt),
    }),
  };
}

function ready(duplicate = false): V2WebProtocolEvent {
  return {
    type: "attachment-ready",
    requestId: "request-3",
    clientMessageId: "",
    value: create(AttachmentReadySchema, {
      attachmentId: ATTACHMENT_ID,
      conversationId: CONVERSATION_ID,
      duplicate,
      readyAtEpochMs: BigInt(NOW + 10_000),
    }),
  };
}

function source(bytes = SOURCE_BYTES) {
  return {
    name: "photo.bin",
    type: "",
    size: bytes.byteLength,
    async arrayBuffer(): Promise<ArrayBuffer> { return bytes.slice().buffer; },
  };
}

test("runs register, refresh, direct PUT, and idempotent READY without retaining bytes", async () => {
  const transport = new FakeTransport();
  let authorizeEvent = 0;
  transport.onRegister = () => transport.event(registered(true));
  transport.onAuthorize = () => transport.event(
    authorization(++authorizeEvent === 1 ? NOW + 1_000 : NOW + 60_000),
  );
  transport.onComplete = () => transport.event(ready(true));
  let uploaded: Uint8Array | null = null;
  let uploadedUri = "";
  let uploadedHeaders: ReadonlyMap<string, string> | null = null;
  const uploader: V2AttachmentDirectUploader = {
    async put(uri, headers, bytes, signal) {
      assert.equal(signal.aborted, false);
      uploadedUri = uri;
      uploadedHeaders = headers;
      uploaded = bytes;
      assert.deepEqual(bytes, SOURCE_BYTES);
    },
  };
  const coordinator = new V2AttachmentUploadCoordinator({
    transport,
    uploader,
    digest: async (bytes) => {
      assert.deepEqual(bytes, SOURCE_BYTES);
      return new Uint8Array(32).fill(7);
    },
    now: () => NOW,
  });

  const result = await coordinator.upload({
    conversationId: CONVERSATION_ID,
    clientAttachmentId: CLIENT_ATTACHMENT_ID,
    source: source(),
  });

  assert.equal(transport.authorizationCalls, 2, "near-expiry grants are refreshed before PUT");
  assert.equal(uploadedUri, "https://objects.example.test/key?signature=secret");
  assert.equal(uploadedHeaders!.get("if-none-match"), "*");
  assert.ok(uploaded!.every((byte) => byte === 0), "owned transient bytes are cleared after completion");
  assert.deepEqual(result, {
    attachmentId: ATTACHMENT_ID,
    conversationId: CONVERSATION_ID,
    clientAttachmentId: CLIENT_ATTACHMENT_ID,
    registrationDuplicate: true,
    readyDuplicate: true,
    readyAtEpochMs: NOW + 10_000,
  });
  assert.deepEqual(transport.calls.map((call) => call.split(":")[0]),
    ["register", "authorize", "authorize", "complete"]);
  coordinator.dispose();
  assert.equal(transport.observer, null);
});

test("maps protocol denial to a fixed error without exposing server text or URL", async () => {
  const transport = new FakeTransport();
  transport.onRegister = () => transport.event({
    type: "protocol-error",
    requestId: "request-1",
    clientMessageId: CLIENT_ATTACHMENT_ID,
    value: create(ProtocolErrorSchema, {
      code: ProtocolErrorCode.NOT_AUTHORIZED,
      safeMessage: "secret bucket https://objects.example.test/key?signature=secret",
      retryable: false,
    }),
  });
  const coordinator = new V2AttachmentUploadCoordinator({
    transport,
    uploader: { put: async () => assert.fail("PUT must not run") },
    digest: async () => new Uint8Array(32),
    now: () => NOW,
  });

  await assert.rejects(
    coordinator.upload({
      conversationId: CONVERSATION_ID,
      clientAttachmentId: CLIENT_ATTACHMENT_ID,
      source: source(),
    }),
    (error: unknown) => error instanceof V2AttachmentUploadError
      && error.code === `PROTOCOL_${ProtocolErrorCode.NOT_AUTHORIZED}`
      && !error.message.includes("objects.example"),
  );
});

test("ignores a stale same-type response with the wrong request id", async () => {
  const transport = new FakeTransport();
  transport.onRegister = () => {
    transport.event({ ...registered(), requestId: "cancelled-old-request" });
    transport.event(registered());
  };
  transport.onAuthorize = () => transport.event(authorization(NOW + 60_000));
  transport.onComplete = () => transport.event(ready());
  const coordinator = new V2AttachmentUploadCoordinator({
    transport,
    uploader: { put: async () => {} },
    digest: async () => new Uint8Array(32),
    now: () => NOW,
  });

  const result = await coordinator.upload({
    conversationId: CONVERSATION_ID,
    clientAttachmentId: CLIENT_ATTACHMENT_ID,
    source: source(),
  });

  assert.equal(result.attachmentId, ATTACHMENT_ID);
});

test("aborts an in-flight PUT on cancellation and rejects connection loss while waiting", async () => {
  const transport = new FakeTransport();
  transport.onRegister = () => transport.event(registered());
  transport.onAuthorize = () => transport.event(authorization(NOW + 60_000));
  let uploadStarted!: () => void;
  const started = new Promise<void>((resolve) => { uploadStarted = resolve; });
  const coordinator = new V2AttachmentUploadCoordinator({
    transport,
    uploader: {
      put: async (_uri, _headers, _bytes, signal) => new Promise<void>((_resolve, reject) => {
        uploadStarted();
        signal.addEventListener("abort", () => reject(new V2AttachmentUploadError("cancelled", "CANCELLED", true)));
      }),
    },
    digest: async () => new Uint8Array(32),
    now: () => NOW,
  });
  const running = coordinator.upload({
    conversationId: CONVERSATION_ID,
    clientAttachmentId: CLIENT_ATTACHMENT_ID,
    source: source(),
  });
  await started;
  coordinator.cancel();
  await assert.rejects(running, (error: unknown) =>
    error instanceof V2AttachmentUploadError && error.code === "CANCELLED");

  const waitingTransport = new FakeTransport();
  const waiting = new V2AttachmentUploadCoordinator({
    transport: waitingTransport,
    uploader: { put: async () => assert.fail("PUT must not run") },
    digest: async () => new Uint8Array(32),
  });
  const disconnected = waiting.upload({
    conversationId: CONVERSATION_ID,
    clientAttachmentId: CLIENT_ATTACHMENT_ID,
    source: source(),
  });
  while (!waitingTransport.calls.some((call) => call.startsWith("register:"))) {
    await Promise.resolve();
  }
  waitingTransport.transition("reconnect-wait");
  await assert.rejects(disconnected, (error: unknown) =>
    error instanceof V2AttachmentUploadError && error.code === "OFFLINE");
  assert.deepEqual(waitingTransport.cancelledRequests, ["request-1"]);
});

test("fetch adapter omits credentials, forbids redirects, and redacts rejection detail", async () => {
  let init: RequestInit | undefined;
  const uploader = createFetchV2AttachmentUploader(async (_input, request) => {
    init = request;
    return new Response("provider detail", { status: 403 });
  });
  const controller = new AbortController();

  await assert.rejects(
    uploader.put(
      "https://objects.example.test/key?signature=secret",
      new Map([["if-none-match", "*"]]),
      SOURCE_BYTES,
      controller.signal,
    ),
    (error: unknown) => error instanceof V2AttachmentUploadError
      && error.code === "UPLOAD_REJECTED"
      && !error.message.includes("objects.example")
      && !error.message.includes("provider detail"),
  );
  assert.equal(init?.method, "PUT");
  assert.equal(init?.credentials, "omit");
  assert.equal(init?.redirect, "error");
  assert.equal(init?.cache, "no-store");
});

test("rejects source revision changes and serializes one upload", async () => {
  const transport = new FakeTransport();
  const coordinator = new V2AttachmentUploadCoordinator({
    transport,
    uploader: { put: async () => assert.fail("PUT must not run") },
    digest: async () => new Uint8Array(32),
  });
  const changedSource = {
    name: "changed.bin",
    type: "application/octet-stream",
    size: 5,
    async arrayBuffer(): Promise<ArrayBuffer> { return SOURCE_BYTES.slice().buffer; },
  };
  await assert.rejects(
    coordinator.upload({
      conversationId: CONVERSATION_ID,
      clientAttachmentId: CLIENT_ATTACHMENT_ID,
      source: changedSource,
    }),
    (error: unknown) => error instanceof V2AttachmentUploadError && error.code === "SOURCE_CHANGED",
  );

  let release!: () => void;
  const gate = new Promise<ArrayBuffer>((resolve) => { release = () => resolve(SOURCE_BYTES.slice().buffer); });
  const first = coordinator.upload({
    conversationId: CONVERSATION_ID,
    clientAttachmentId: CLIENT_ATTACHMENT_ID,
    source: { ...source(), arrayBuffer: () => gate },
  });
  await assert.rejects(
    coordinator.upload({
      conversationId: CONVERSATION_ID,
      clientAttachmentId: "client-attachment-2",
      source: source(),
    }),
    (error: unknown) => error instanceof V2AttachmentUploadError && error.code === "BUSY",
  );
  coordinator.cancel();
  release();
  await assert.rejects(first, (error: unknown) =>
    error instanceof V2AttachmentUploadError && error.code === "CANCELLED");
});
