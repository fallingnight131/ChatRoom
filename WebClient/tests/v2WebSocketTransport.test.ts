import assert from "node:assert/strict";
import test from "node:test";

import { create, fromBinary, toBinary } from "@bufbuild/protobuf";

import {
  AuthenticationRejectedSchema,
  ResumeSessionSchema,
  SessionEstablishedSchema,
} from "../src/protocol/v2/generated/authentication_pb";
import { MessageType, ServerHelloSchema } from "../src/protocol/v2/generated/control_pb";
import { EnvelopeSchema, MessageKind, type Envelope } from "../src/protocol/v2/generated/envelope_pb";
import { V2WebProtocolClient } from "../src/protocol/v2/webProtocolClient";
import {
  V2WebSocketTransport,
  type V2WebSocketLike,
  type V2WebSocketTransportState,
} from "../src/protocol/v2/webSocketTransport";

const NOW = 1_800_000_000_000;
const ACCOUNT_ID = "20000000-0000-4000-8000-000000000001";
const DEVICE_ID = "30000000-0000-4000-8000-000000000001";
const SESSION_ID = "40000000-0000-4000-8000-000000000001";

class FakeSocket implements V2WebSocketLike {
  binaryType = "blob";
  protocol = "chat.v2";
  readyState = 0;
  onopen: ((event: Event) => unknown) | null = null;
  onmessage: ((event: MessageEvent) => unknown) | null = null;
  onerror: ((event: Event) => unknown) | null = null;
  onclose: ((event: CloseEvent) => unknown) | null = null;
  readonly sent: ArrayBuffer[] = [];
  readonly closes: Array<{ code?: number; reason?: string }> = [];

  send(data: ArrayBuffer): void {
    this.sent.push(data.slice(0));
  }

  close(code?: number, reason?: string): void {
    this.closes.push({ code, reason });
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event("open"));
  }

  receive(data: ArrayBuffer | string): void {
    this.onmessage?.(new MessageEvent("message", { data }));
  }

  finishClose(code = 1006, reason = "lost"): void {
    this.readyState = 3;
    this.onclose?.({ code, reason } as CloseEvent);
  }
}

class FakeTimers {
  private nextId = 0;
  readonly tasks = new Map<number, { callback: () => void; delayMs: number }>();

  set = (callback: () => void, delayMs: number): ReturnType<typeof setTimeout> => {
    const id = ++this.nextId;
    this.tasks.set(id, { callback, delayMs });
    return id as unknown as ReturnType<typeof setTimeout>;
  };

  clear = (handle: ReturnType<typeof setTimeout>): void => {
    this.tasks.delete(handle as unknown as number);
  };

  runOnly(): number {
    assert.equal(this.tasks.size, 1);
    const [id, task] = [...this.tasks.entries()][0]!;
    this.tasks.delete(id);
    task.callback();
    return task.delayMs;
  }
}

class FakeNetwork {
  online = true;
  observer: { onOnline(): void; onOffline(): void } | null = null;
  subscriptions = 0;
  unsubscriptions = 0;

  isOnline = (): boolean => this.online;
  observe = (observer: { onOnline(): void; onOffline(): void }): (() => void) => {
    this.observer = observer;
    this.subscriptions += 1;
    return () => {
      if (this.observer === observer) this.observer = null;
      this.unsubscriptions += 1;
    };
  };

  setOnline(online: boolean): void {
    this.online = online;
    if (online) this.observer?.onOnline();
    else this.observer?.onOffline();
  }
}

function protocolFactory(): () => V2WebProtocolClient {
  let connection = 0;
  return () => {
    const connectionNumber = ++connection;
    let request = 0;
    return new V2WebProtocolClient({
      appVersion: "2.0.0-test",
      clientDeviceId: "web-test-device",
      createRequestId: () => `10000000-0000-4000-${connectionNumber.toString().padStart(4, "8")}-${String(++request).padStart(12, "0")}`,
      now: () => NOW,
    });
  };
}

function sentEnvelope(socket: FakeSocket, index: number): Envelope {
  return fromBinary(EnvelopeSchema, new Uint8Array(socket.sent[index]!));
}

function response(
  request: Envelope,
  type: MessageType,
  payload: Uint8Array,
  sessionId = "",
  kind = MessageKind.RESPONSE,
): ArrayBuffer {
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, {
    protocolVersion: 2,
    kind,
    messageType: type,
    requestId: request.requestId,
    sessionId,
    clientMessageId: request.clientMessageId,
    sentAtEpochMs: BigInt(NOW + 1),
    payload,
  })).slice().buffer;
}

function helloResponse(request: Envelope): ArrayBuffer {
  return response(request, MessageType.SERVER_HELLO, toBinary(ServerHelloSchema, create(ServerHelloSchema, {
    selectedProtocolVersion: 2,
    connectionId: "gateway-connection-1",
    serverTimeEpochMs: BigInt(NOW),
    maximumFrameBytes: 1024 * 1024 + 1024,
  })));
}

test("owns exact Web V2 upgrade, negotiation, authentication, and command sending", () => {
  const timers = new FakeTimers();
  const sockets: FakeSocket[] = [];
  const states: V2WebSocketTransportState[] = [];
  const events: string[] = [];
  let requestedEndpoint = "";
  let requestedProtocols: string[] = [];
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: (endpoint, protocols) => {
      requestedEndpoint = endpoint;
      requestedProtocols = protocols;
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    setTimer: timers.set,
    clearTimer: timers.clear,
    onStateChange: (state) => states.push(state),
    onProtocolEvent: (event) => events.push(event.type),
  });

  transport.start();
  assert.equal(requestedEndpoint, "wss://chat.example/v2/web");
  assert.deepEqual(requestedProtocols, ["chat.v2"]);
  const socket = sockets[0]!;
  assert.equal(socket.binaryType, "arraybuffer");
  socket.open();
  const hello = sentEnvelope(socket, 0);
  assert.equal(hello.messageType, MessageType.CLIENT_HELLO);
  socket.receive(helloResponse(hello));
  assert.equal(transport.state, "connected");

  const password = new TextEncoder().encode("password");
  transport.authenticate("alice", password);
  const authenticate = sentEnvelope(socket, 1);
  assert.equal(authenticate.messageType, MessageType.AUTHENTICATE);
  socket.receive(response(authenticate, MessageType.SESSION_ESTABLISHED, toBinary(
    SessionEstablishedSchema,
    create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: new Uint8Array(32),
      expiresAtEpochMs: BigInt(NOW + 60_000),
      displayName: "Alice",
    }),
  ), SESSION_ID));
  assert.equal(transport.state, "authenticated");
  assert.deepEqual(events, ["server-hello", "session-established"]);
  assert.equal(timers.tasks.size, 0);

  transport.listConversations(20);
  assert.equal(sentEnvelope(socket, 2).messageType, MessageType.LIST_CONVERSATIONS);
  transport.registerAttachment(
    "50000000-0000-4000-8000-000000000001",
    "client-attachment-1",
    "a.bin",
    "application/octet-stream",
    4n,
    new Uint8Array(32),
  );
  assert.equal(sentEnvelope(socket, 3).messageType, MessageType.REGISTER_ATTACHMENT);
  transport.authorizeAttachmentUpload("60000000-0000-4000-8000-000000000001");
  assert.equal(sentEnvelope(socket, 4).messageType, MessageType.AUTHORIZE_ATTACHMENT_UPLOAD);
  transport.completeAttachmentUpload("60000000-0000-4000-8000-000000000001");
  assert.equal(sentEnvelope(socket, 5).messageType, MessageType.COMPLETE_ATTACHMENT_UPLOAD);
  transport.stop();
  assert.equal(transport.state, "stopped");
  assert.deepEqual(socket.closes.at(-1), { code: 1000, reason: "client stopped" });
  assert.ok(states.includes("negotiating"));
});

test("fails closed on subprotocol, non-binary data, and phase timeout", () => {
  assert.throws(
    () => new V2WebSocketTransport({ endpoint: "ws://chat.example/v2/web", createProtocolClient: protocolFactory() }),
    /exact wss/,
  );
  const failures: string[] = [];
  const timers = new FakeTimers();
  const socket = new FakeSocket();
  socket.protocol = "";
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => socket,
    setTimer: timers.set,
    clearTimer: timers.clear,
    random: () => 0,
    onFailure: (reason) => failures.push(reason),
  });
  transport.start();
  socket.open();
  assert.deepEqual(socket.closes.at(-1), { code: 1002, reason: "V2 WebSocket subprotocol mismatch" });
  socket.protocol = "chat.v2";
  socket.finishClose();
  assert.equal(timers.runOnly(), 0, "first reconnect uses bounded full jitter");
  socket.open();
  socket.receive("not binary");
  assert.deepEqual(socket.closes.at(-1), { code: 1002, reason: "V2 WebSocket received a non-binary frame" });
  transport.stop();

  const timeoutSocket = new FakeSocket();
  const timeoutTimers = new FakeTimers();
  const timeoutTransport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => timeoutSocket,
    setTimer: timeoutTimers.set,
    clearTimer: timeoutTimers.clear,
    random: () => 0,
    connectTimeoutMs: 123,
  });
  timeoutTransport.start();
  assert.equal(timeoutTimers.runOnly(), 123);
  assert.deepEqual(timeoutSocket.closes.at(-1), { code: 4000, reason: "V2 connection timeout" });
  timeoutTransport.stop();
  assert.ok(failures.includes("V2 WebSocket subprotocol mismatch"));
});

test("sends an explicitly supplied resume proof after negotiation", () => {
  const timers = new FakeTimers();
  const socket = new FakeSocket();
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => socket,
    setTimer: timers.set,
    clearTimer: timers.clear,
  });
  transport.start();
  socket.open();
  socket.receive(helloResponse(sentEnvelope(socket, 0)));
  transport.resumeSession(SESSION_ID, new Uint8Array(32));
  const resume = sentEnvelope(socket, 1);
  assert.equal(resume.messageType, MessageType.RESUME_SESSION);
  socket.receive(response(resume, MessageType.SESSION_ESTABLISHED, toBinary(
    SessionEstablishedSchema,
    create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: new Uint8Array(32),
      expiresAtEpochMs: BigInt(NOW + 60_000),
      displayName: "Alice",
    }),
  ), SESSION_ID));
  assert.equal(transport.state, "authenticated");
  transport.stop();
});

test("clears per-connection protocol state and backs off before reconnect", () => {
  const timers = new FakeTimers();
  const sockets: FakeSocket[] = [];
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    setTimer: timers.set,
    clearTimer: timers.clear,
    random: () => 0.5,
    reconnectBaseMs: 100,
    reconnectMaximumMs: 1_000,
  });
  transport.start();
  sockets[0]!.open();
  sockets[0]!.receive(helloResponse(sentEnvelope(sockets[0]!, 0)));
  sockets[0]!.finishClose();
  assert.equal(transport.state, "reconnect-wait");
  assert.equal(timers.runOnly(), 50);
  assert.equal(sockets.length, 2);
  sockets[1]!.open();
  assert.equal(sentEnvelope(sockets[1]!, 0).messageType, MessageType.CLIENT_HELLO);
  transport.stop();
  assert.equal(timers.tasks.size, 0);
});

test("turns synchronous socket construction failure into a cancellable retry", () => {
  const timers = new FakeTimers();
  const socket = new FakeSocket();
  let attempts = 0;
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => {
      if (++attempts === 1) throw new Error("browser rejected constructor");
      return socket;
    },
    setTimer: timers.set,
    clearTimer: timers.clear,
    random: () => 0.5,
    reconnectBaseMs: 100,
  });
  transport.start();
  assert.equal(transport.state, "reconnect-wait");
  assert.equal(timers.runOnly(), 50);
  assert.equal(attempts, 2);
  transport.stop();
  assert.equal(timers.tasks.size, 0);
});

test("pauses attempts while offline and reconnects immediately after browser recovery", () => {
  const network = new FakeNetwork();
  const timers = new FakeTimers();
  const sockets: FakeSocket[] = [];
  network.online = false;
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    setTimer: timers.set,
    clearTimer: timers.clear,
    isOnline: network.isOnline,
    observeNetwork: network.observe,
  });
  transport.start();
  assert.equal(transport.state, "offline");
  assert.equal(sockets.length, 0);
  assert.equal(timers.tasks.size, 0);

  network.setOnline(true);
  assert.equal(transport.state, "connecting");
  assert.equal(sockets.length, 1);
  network.setOnline(false);
  assert.equal(transport.state, "offline");
  assert.deepEqual(sockets[0]!.closes.at(-1), { code: 1001, reason: "network offline" });
  assert.equal(timers.tasks.size, 0);

  network.setOnline(true);
  assert.equal(sockets.length, 2);
  transport.stop();
  assert.equal(network.subscriptions, 1);
  assert.equal(network.unsubscriptions, 1);
  network.setOnline(true);
  assert.equal(sockets.length, 2, "stopped transport no longer observes browser network events");
});

test("automatically resumes with rotated memory-only proof and clears it after rejection", () => {
  const timers = new FakeTimers();
  const network = new FakeNetwork();
  const sockets: FakeSocket[] = [];
  const transport = new V2WebSocketTransport({
    endpoint: "wss://chat.example/v2/web",
    createProtocolClient: protocolFactory(),
    createSocket: () => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    },
    setTimer: timers.set,
    clearTimer: timers.clear,
    random: () => 0,
    isOnline: network.isOnline,
    observeNetwork: network.observe,
  });
  const firstToken = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
  const rotatedToken = Uint8Array.from({ length: 32 }, (_, index) => 32 - index);
  const exposedTokenLengths: number[] = [];
  transport.subscribe({
    onProtocolEvent: (event) => {
      if (event.type === "session-established") exposedTokenLengths.push(event.value.resumeToken.byteLength);
    },
  });

  transport.start();
  sockets[0]!.open();
  sockets[0]!.receive(helloResponse(sentEnvelope(sockets[0]!, 0)));
  transport.authenticate("alice", new TextEncoder().encode("password"));
  const authentication = sentEnvelope(sockets[0]!, 1);
  sockets[0]!.receive(response(authentication, MessageType.SESSION_ESTABLISHED, toBinary(
    SessionEstablishedSchema,
    create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: firstToken,
      expiresAtEpochMs: BigInt(NOW + 60_000),
      displayName: "Alice",
    }),
  ), SESSION_ID));

  network.setOnline(false);
  assert.deepEqual(sockets[0]!.closes.at(-1), { code: 1001, reason: "network offline" });
  assert.equal(timers.tasks.size, 0);
  network.setOnline(true);
  sockets[1]!.open();
  sockets[1]!.receive(helloResponse(sentEnvelope(sockets[1]!, 0)));
  assert.equal(transport.state, "resuming");
  const firstResumeEnvelope = sentEnvelope(sockets[1]!, 1);
  const firstResume = fromBinary(ResumeSessionSchema, firstResumeEnvelope.payload);
  assert.deepEqual(firstResume.resumeToken, firstToken);
  sockets[1]!.receive(response(firstResumeEnvelope, MessageType.SESSION_ESTABLISHED, toBinary(
    SessionEstablishedSchema,
    create(SessionEstablishedSchema, {
      accountId: ACCOUNT_ID,
      deviceId: DEVICE_ID,
      sessionId: SESSION_ID,
      resumeToken: rotatedToken,
      expiresAtEpochMs: BigInt(NOW + 120_000),
      displayName: "Alice",
    }),
  ), SESSION_ID));

  sockets[1]!.finishClose();
  timers.runOnly();
  sockets[2]!.open();
  sockets[2]!.receive(helloResponse(sentEnvelope(sockets[2]!, 0)));
  const rotatedResumeEnvelope = sentEnvelope(sockets[2]!, 1);
  assert.deepEqual(fromBinary(ResumeSessionSchema, rotatedResumeEnvelope.payload).resumeToken, rotatedToken);
  sockets[2]!.receive(response(
    rotatedResumeEnvelope,
    MessageType.AUTHENTICATION_REJECTED,
    toBinary(AuthenticationRejectedSchema, create(AuthenticationRejectedSchema, {})),
    "",
    MessageKind.ERROR,
  ));
  sockets[2]!.finishClose();
  timers.runOnly();
  sockets[3]!.open();
  sockets[3]!.receive(helloResponse(sentEnvelope(sockets[3]!, 0)));
  assert.equal(transport.state, "connected");
  assert.equal(sockets[3]!.sent.length, 1, "rejected proof is not replayed again");
  assert.deepEqual(exposedTokenLengths, [0, 0], "resume proofs stay inside the transport boundary");
  transport.stop();
});
