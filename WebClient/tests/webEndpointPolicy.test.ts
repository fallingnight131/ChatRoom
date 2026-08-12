import assert from "node:assert/strict";
import test from "node:test";

import {
  purgeLegacyServerOverrides,
  resolveWebEndpointPolicy,
} from "../src/security/webEndpointPolicy";

test("pins production WebSocket and HTTP traffic to the HTTPS page origin", () => {
  const policy = resolveWebEndpointPolicy({
    protocol: "https:",
    hostname: "chat.example",
    host: "chat.example:8443",
    origin: "https://chat.example:8443",
  });

  assert.deepEqual(policy, {
    usable: true,
    mode: "same-origin-production",
    websocketUrl: "wss://chat.example:8443/ws",
    httpBaseUrl: "https://chat.example:8443",
  });
});

test("allows the fixed direct socket only for loopback development", () => {
  const policy = resolveWebEndpointPolicy({
    protocol: "http:",
    hostname: "localhost",
    host: "localhost:5173",
    origin: "http://localhost:5173",
  });

  assert.deepEqual(policy, {
    usable: true,
    mode: "local-development",
    websocketUrl: "ws://localhost:9528",
    httpBaseUrl: "http://localhost:5173",
  });
});

test("fails closed for insecure production pages and unsafe path overrides", () => {
  const productionHttp = {
    protocol: "http:",
    hostname: "chat.example",
    host: "chat.example",
    origin: "http://chat.example",
  };
  assert.equal(resolveWebEndpointPolicy(productionHttp).usable, false);

  for (const path of ["ws", "//evil.example/ws", "/a/../ws", "/a/%2e%2e/ws", "/ws?target=evil", "/ws#fragment"]) {
    const policy = resolveWebEndpointPolicy(
      { ...productionHttp, protocol: "https:", origin: "https://chat.example" },
      { VITE_CHAT_V1_WS_PATH: path },
    );
    assert.equal(policy.usable, false, path);
  }
});

test("removes legacy persisted server overrides without trusting storage availability", () => {
  const removed: string[] = [];
  purgeLegacyServerOverrides({ removeItem: key => removed.push(key) });
  assert.deepEqual(removed, ["serverHost", "serverPort", "wsPath"]);

  assert.doesNotThrow(() => purgeLegacyServerOverrides({ removeItem: () => { throw new Error("denied"); } }));
});
