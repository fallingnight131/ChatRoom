import assert from "node:assert/strict";
import test from "node:test";
import {
  createWebPushSubscriptionHttpApi,
  WebPushSubscriptionHttpError,
  type WebPushHttpCredentialLease,
} from "../src/platform/webPushSubscriptionHttpApi";

const installation = "10000000-0000-4000-8000-000000000001";
const bearer = new TextEncoder().encode("a".repeat(32));
const csrf = new TextEncoder().encode("b".repeat(32));
const p256dh = new Uint8Array(65); p256dh[0] = 0x04;
const auth = new Uint8Array(16); auth.fill(7);

const subscription = {
  endpoint: "https://push.example/sub/opaque",
  expirationTime: null,
  keys: { p256dh: base64Url(p256dh), auth: base64Url(auth) },
};

function lease(calls: string[]): WebPushHttpCredentialLease {
  return { async withCredentials(action) {
    calls.push("credentials"); return action(bearer.slice(), csrf.slice());
  } };
}

test("uses one short-lived credential lease and exact same-origin hardened requests", async () => {
  const calls: string[] = []; const requests: Array<[RequestInfo | URL, RequestInit | undefined]> = [];
  const api = createWebPushSubscriptionHttpApi({
    origin: "https://chat.example", credentials: lease(calls),
    fetch: async (input, init) => { requests.push([input, init]); return new Response(null, { status: 204 }); },
  });
  await api.replace(installation, subscription);
  await api.delete(installation);
  assert.deepEqual(calls, ["credentials", "credentials"]);
  assert.equal(requests[0][0], `https://chat.example/api/v2/web-push/subscriptions/${installation}`);
  assert.equal(requests[0][1]?.method, "PUT");
  assert.equal(requests[0][1]?.credentials, "omit");
  assert.equal(requests[0][1]?.redirect, "error");
  assert.equal(requests[0][1]?.mode, "same-origin");
  assert.equal(requests[0][1]?.referrerPolicy, "no-referrer");
  const headers = requests[0][1]?.headers as Record<string, string>;
  assert.equal(headers.Authorization, `Bearer ${"a".repeat(32)}`);
  assert.equal(headers["X-CSRF-Token"], "b".repeat(32));
  const body = JSON.parse(String(requests[0][1]?.body));
  assert.deepEqual(Object.keys(body).sort(), ["endpoint", "expirationTime", "keys"]);
  assert.equal("accountId" in body, false);
  assert.equal(requests[1][1]?.method, "DELETE");
  assert.equal(requests[1][1]?.body, undefined);
});

test("maps status and retry metadata without exposing response bodies", async () => {
  const api = createWebPushSubscriptionHttpApi({
    origin: "https://chat.example", credentials: lease([]),
    fetch: async () => new Response("provider secret detail", {
      status: 429, headers: { "Retry-After": "17" },
    }),
  });
  await assert.rejects(api.delete(installation), (error: unknown) =>
    error instanceof WebPushSubscriptionHttpError
      && error.code === "RATE_LIMITED" && error.retryAfterSeconds === 17
      && !error.message.includes("provider secret"));
});

test("rejects unsafe origin, malformed subscription, and credentials before fetch", async () => {
  assert.throws(() => createWebPushSubscriptionHttpApi({
    origin: "http://chat.example", credentials: lease([]), fetch: async () => new Response(),
  }), /invalid API origin/);
  let fetched = 0; const credentialCalls: string[] = [];
  const api = createWebPushSubscriptionHttpApi({
    origin: "https://chat.example", credentials: lease(credentialCalls),
    fetch: async () => { fetched++; return new Response(null, { status: 204 }); },
  });
  await assert.rejects(api.replace(installation, { ...subscription,
    endpoint: "https://push.example:443/sub/opaque" }), (error: unknown) =>
      error instanceof WebPushSubscriptionHttpError && error.code === "INVALID_SUBSCRIPTION");
  assert.equal(fetched, 0); assert.deepEqual(credentialCalls, []);

  const badCredentials = createWebPushSubscriptionHttpApi({
    origin: "https://chat.example",
    credentials: { withCredentials: action => action(new Uint8Array([0xff]), csrf) },
    fetch: async () => { fetched++; return new Response(); },
  });
  await assert.rejects(badCredentials.delete(installation), (error: unknown) =>
    error instanceof WebPushSubscriptionHttpError && error.code === "INVALID_CREDENTIALS");
  assert.equal(fetched, 0);
});

function base64Url(value: Uint8Array): string {
  return Buffer.from(value).toString("base64url");
}
