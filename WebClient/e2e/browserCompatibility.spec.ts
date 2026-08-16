import { expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";

const brandedTarget = process.env.CHATROOM_BRANDED_BROWSER_TARGET;
const tinyVideoBase64 = readFileSync(
  new URL("./fixtures/tiny.webm.base64", import.meta.url), "utf8").trim();
const tinyAudioBase64 = readFileSync(
  new URL("./fixtures/tiny.ogg.base64", import.meta.url), "utf8").trim();

async function exerciseMediaDecode(page: Page) {
  const result = await page.evaluate(async ({ videoBase64, audioBase64 }) => {
    const decode = (value: string) => {
      const binary = atob(value);
      return Uint8Array.from(binary, character => character.charCodeAt(0));
    };
    const load = <T extends HTMLMediaElement>(element: T, bytes: Uint8Array, type: string) =>
      new Promise<T>((resolve, reject) => {
        const url = URL.createObjectURL(new Blob([bytes], { type }));
        const timeout = window.setTimeout(() => {
          URL.revokeObjectURL(url);
          reject(new Error(`${type} metadata timeout`));
        }, 5000);
        element.onloadedmetadata = () => {
          window.clearTimeout(timeout);
          URL.revokeObjectURL(url);
          resolve(element);
        };
        element.onerror = () => {
          window.clearTimeout(timeout);
          URL.revokeObjectURL(url);
          reject(new Error(`${type} decode failed`));
        };
        element.preload = "metadata";
        element.src = url;
        element.load();
      });
    const video = await load(document.createElement("video"), decode(videoBase64), "video/webm");
    const audio = await load(document.createElement("audio"), decode(audioBase64), "audio/ogg; codecs=opus");
    return {
      videoDuration: video.duration,
      videoWidth: video.videoWidth,
      videoHeight: video.videoHeight,
      audioDuration: audio.duration,
    };
  }, { videoBase64: tinyVideoBase64, audioBase64: tinyAudioBase64 });
  expect(result.videoDuration).toBeGreaterThan(0);
  expect(result.videoWidth).toBe(16);
  expect(result.videoHeight).toBe(16);
  expect(result.audioDuration).toBeGreaterThan(0);
}

async function installV1ClientFixture(page: Page) {
  await page.addInitScript(() => {
    const sent: Array<{ type: string; data?: Record<string, unknown> }> = [];
    class FixtureWebSocket {
      static readonly CONNECTING = 0;
      static readonly OPEN = 1;
      static readonly CLOSING = 2;
      static readonly CLOSED = 3;
      readonly url: string;
      readyState = FixtureWebSocket.CONNECTING;
      onopen: (() => void) | null = null;
      onmessage: ((event: { data: string }) => void) | null = null;
      onclose: (() => void) | null = null;
      onerror: (() => void) | null = null;

      constructor(url: string) {
        this.url = url;
        queueMicrotask(() => {
          this.readyState = FixtureWebSocket.OPEN;
          this.onopen?.();
        });
      }

      send(raw: string) {
        const message = JSON.parse(raw);
        sent.push(message);
        let response: Record<string, unknown> | null = null;
        if (message.type === "LOGIN_REQ") {
          response = {
            type: "LOGIN_RSP", id: `fixture-${sent.length}`, timestamp: Date.now(),
            data: { success: true, userId: 7, username: message.data.username,
              displayName: "Browser Gate User" },
          };
        } else if (message.type === "ROOM_LIST_REQ") {
          response = { type: "ROOM_LIST_RSP", id: `fixture-${sent.length}`,
            timestamp: Date.now(), data: { rooms: [{ roomId: 42,
              roomName: "Browser Gate Room", unread: 0, isAdmin: false }] } };
        } else if (message.type === "FRIEND_LIST_REQ") {
          response = { type: "FRIEND_LIST_RSP", id: `fixture-${sent.length}`,
            timestamp: Date.now(), data: { friends: [{ username: "browser_friend",
              displayName: "Browser Gate Friend", isOnline: true, unread: 0 }],
              pendingFriendRequests: 0 } };
        } else if (message.type === "AVATAR_GET_REQ") {
          response = { type: "AVATAR_GET_RSP", id: `fixture-${sent.length}`,
            timestamp: Date.now(), data: { success: false,
              username: message.data.username } };
        }
        if (response) queueMicrotask(() => this.onmessage?.({ data: JSON.stringify(response) }));
      }

      close() {
        this.readyState = FixtureWebSocket.CLOSED;
        this.onclose?.();
      }
    }
    Object.defineProperty(window, "WebSocket", { value: FixtureWebSocket, configurable: true });
    Object.defineProperty(window, "__chatFixtureMessages", { value: sent });
  });
}

async function exerciseAuthenticatedClientShell(page: Page, context: BrowserContext) {
  await installV1ClientFixture(page);
  await page.goto("/");
  await page.getByLabel("用户ID (唯一标识)").fill("browser_gate_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/#\/chat$/);
  await expect(page.getByText("Browser Gate User", { exact: true })).toBeVisible();
  await expect(page.getByText("Browser Gate Friend", { exact: true })).toBeVisible();
  const storage = await page.evaluate(() => ({ ...localStorage, ...sessionStorage }));
  expect(JSON.stringify(storage)).not.toContain("non-secret-test-value");

  await context.setOffline(true);
  await expect(page.getByText("网络已断开，可继续查看已缓存消息，恢复后将自动连接")).toBeVisible();
  await expect(page.getByText("Browser Gate Friend", { exact: true })).toBeVisible();
  await context.setOffline(false);
  await expect(page.getByText("网络已断开，可继续查看已缓存消息，恢复后将自动连接")).toBeHidden();
  await expect.poll(() => page.evaluate(() =>
    (window as unknown as { __chatFixtureMessages: Array<{ type: string }> })
      .__chatFixtureMessages.filter(message => message.type === "LOGIN_REQ").length
  )).toBe(2);
  return storage;
}

async function exerciseDelayedClientBoot(browser: Browser) {
  const context = await browser.newContext();
  await context.route("**/*", async route => {
    if (["document", "script", "stylesheet"].includes(route.request().resourceType())) {
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    await route.continue();
  });
  const page = await context.newPage();
  const errors: Error[] = [];
  page.on("pageerror", error => errors.push(error));
  await exerciseAuthenticatedClientShell(page, context);
  expect(errors).toEqual([]);
  await context.close();
}

async function exerciseKeyboardLogin(page: Page) {
  await page.goto("/");
  await page.getByLabel("用户ID (唯一标识)").focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("密码")).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "登录" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert")).toHaveText("请输入用户ID和密码");
}

async function exerciseNativeIndexedDbMigration(page: Page) {
  await installV1ClientFixture(page);
  await page.goto("/");
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const deletion = indexedDB.deleteDatabase("chat-room-client");
      deletion.onerror = () => reject(deletion.error);
      deletion.onblocked = () => reject(new Error("legacy database deletion blocked"));
      deletion.onsuccess = () => resolve();
    });
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open("chat-room-client", 1);
      request.onerror = () => reject(request.error);
      request.onupgradeneeded = () => {
        const store = request.result.createObjectStore("conversations", { keyPath: "key" });
        store.put({
          key: "browser_gate_user\u001froom\u001f42",
          account: "browser_gate_user",
          kind: "room",
          conversationId: "42",
          cursor: 9,
          draft: "d".repeat(10020),
          token: "legacy-secret",
          messages: Array.from({ length: 510 }, (_, id) => ({
            id, content: `message-${id}`, thumbnail: "legacy-base64",
            fileData: "legacy-file-bytes", cosUrl: "https://temporary.invalid/secret",
          })),
        });
      };
      request.onsuccess = () => {
        request.result.close();
        resolve();
      };
    });
  });
  await page.getByLabel("用户ID (唯一标识)").fill("browser_gate_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/#\/chat$/);
  await expect.poll(() => page.evaluate(async () => {
    return await new Promise<Record<string, unknown> | null>((resolve, reject) => {
      const request = indexedDB.open("chat-room-client");
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const database = request.result;
        if (database.version !== 3
            || !database.objectStoreNames.contains("attachmentCommands")) {
          database.close();
          resolve(null);
          return;
        }
        const transaction = database.transaction("conversations", "readonly");
        const read = transaction.objectStore("conversations")
          .get("browser_gate_user\u001froom\u001f42");
        read.onerror = () => reject(read.error);
        read.onsuccess = () => {
          database.close();
          resolve(read.result ?? null);
        };
      };
    });
  })).not.toBeNull();
  const record = await page.evaluate(async () => await new Promise<Record<string, any>>((resolve, reject) => {
    const request = indexedDB.open("chat-room-client");
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const database = request.result;
      const read = database.transaction("conversations", "readonly")
        .objectStore("conversations").get("browser_gate_user\u001froom\u001f42");
      read.onerror = () => reject(read.error);
      read.onsuccess = () => { database.close(); resolve(read.result); };
    };
  }));
  expect(record.messages).toHaveLength(500);
  expect(record.messages[0].id).toBe(10);
  expect(record.draft).toHaveLength(10000);
  expect(record.token).toBeUndefined();
  for (const message of record.messages) {
    expect(message.thumbnail).toBeUndefined();
    expect(message.fileData).toBeUndefined();
    expect(message.cosUrl).toBeUndefined();
  }
}

async function exerciseBlockedIndexedDbUpgrade(page: Page) {
  await installV1ClientFixture(page);
  await page.goto("/");
  await page.evaluate(async () => {
    await new Promise<void>((resolve, reject) => {
      const deletion = indexedDB.deleteDatabase("chat-room-client");
      deletion.onerror = () => reject(deletion.error);
      deletion.onblocked = () => reject(new Error("legacy database deletion blocked"));
      deletion.onsuccess = () => resolve();
    });
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open("chat-room-client", 1);
      request.onerror = () => reject(request.error);
      request.onupgradeneeded = () => request.result.createObjectStore(
        "conversations", { keyPath: "key" });
      request.onsuccess = () => {
        Object.defineProperty(window, "__legacyBlockedDatabase", {
          value: request.result, configurable: true,
        });
        resolve();
      };
    });
  });

  await page.getByLabel("用户ID (唯一标识)").fill("browser_gate_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/#\/chat$/);
  await expect(page.getByText("Browser Gate Friend", { exact: true })).toBeVisible();

  await page.evaluate(() => {
    (window as unknown as { __legacyBlockedDatabase: IDBDatabase })
      .__legacyBlockedDatabase.close();
  });
  await page.getByText("Browser Gate Friend", { exact: true }).click();
  await expect.poll(() => page.evaluate(async () => await new Promise<number>((resolve, reject) => {
    const request = indexedDB.open("chat-room-client");
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const version = request.result.version;
      request.result.close();
      resolve(version);
    };
  }))).toBe(3);
}

test("loads the production login surface with required browser capabilities", async ({ page }) => {
  const pageErrors: Error[] = [];
  page.on("pageerror", error => pageErrors.push(error));

  await page.goto("/", { waitUntil: "networkidle" });

  await expect(page).toHaveTitle("聊天室 - Web");
  await expect(page.getByRole("heading", { name: "ChatRoom" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  await expect(page.locator('input[placeholder="输入唯一用户ID"]')).toBeEditable();
  await expect(page.locator('input[type="password"]')).toHaveAttribute("autocomplete", "current-password");

  const capabilities = await page.evaluate(async () => {
    const databaseName = `chat-room-browser-gate-${crypto.randomUUID()}`;
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open(databaseName, 1);
      request.onerror = () => reject(request.error);
      request.onupgradeneeded = () => request.result.createObjectStore("health");
      request.onsuccess = () => {
        request.result.close();
        const deletion = indexedDB.deleteDatabase(databaseName);
        deletion.onerror = () => reject(deletion.error);
        deletion.onsuccess = () => resolve();
      };
    });
    return {
      abortController: typeof AbortController === "function",
      bigInt: typeof BigInt === "function",
      blob: typeof Blob === "function",
      cryptoRandomUuid: typeof crypto.randomUUID === "function",
      fetch: typeof fetch === "function",
      indexedDb: typeof indexedDB === "object",
      webSocket: typeof WebSocket === "function",
    };
  });
  expect(capabilities).toEqual({
    abortController: true,
    bigInt: true,
    blob: true,
    cryptoRandomUuid: true,
    fetch: true,
    indexedDb: true,
    webSocket: true,
  });
  expect(pageErrors).toEqual([]);
});

test("purges hostile legacy server overrides before a login connection", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("serverHost", "evil.example");
    localStorage.setItem("serverPort", "443");
    localStorage.setItem("wsPath", "/steal");
  });
  const socketUrls: string[] = [];
  page.on("websocket", socket => socketUrls.push(socket.url()));

  await page.goto("/");
  await page.locator('input[placeholder="输入唯一用户ID"]').fill("browser_gate_user");
  await page.locator('input[type="password"]').fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();

  await expect.poll(() => socketUrls.length).toBeGreaterThan(0);
  expect(new Set(socketUrls)).toEqual(new Set(["ws://127.0.0.1:9528/"]));
  const persisted = await page.evaluate(() => ({
    host: localStorage.getItem("serverHost"),
    port: localStorage.getItem("serverPort"),
    path: localStorage.getItem("wsPath"),
  }));
  expect(persisted).toEqual({ host: null, port: null, path: null });
});

test("keeps the login path usable at a narrow responsive viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  const card = page.locator(".login-card");
  await expect(card).toBeVisible();
  const bounds = await card.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  await expect(page.locator('input[placeholder="输入唯一用户ID"]')).toBeEditable();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
});

test("supports keyboard login order and announced validation", async ({ page }) => {
  await exerciseKeyboardLogin(page);
});

test("persists the supported login locale and updates document language", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("界面语言").selectOption("en-US");
  await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
  await expect(page.getByLabel("User ID (unique)")).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("Interface language")).toHaveValue("en-US");
  await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
});

test("supports authenticated tab navigation and modal focus restoration", async ({ page }) => {
  await installV1ClientFixture(page);
  await page.goto("/");
  await page.getByLabel("用户ID (唯一标识)").fill("browser_gate_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/#\/chat$/);

  const friendsTab = page.getByRole("tab", { name: "好友" });
  const roomsTab = page.getByRole("tab", { name: "房间" });
  await friendsTab.focus();
  await page.keyboard.press("ArrowRight");
  await expect(roomsTab).toBeFocused();
  await expect(roomsTab).toHaveAttribute("aria-selected", "true");
  await page.keyboard.press("ArrowLeft");
  await expect(friendsTab).toBeFocused();
  await expect(friendsTab).toHaveAttribute("aria-selected", "true");

  const profileTrigger = page.getByRole("button", { name: "打开个人资料" });
  await profileTrigger.click();
  await expect(page.getByRole("dialog", { name: "个人资料" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "个人资料" })).toBeHidden();
  await expect(profileTrigger).toBeFocused();
});

test("switches the authenticated shell locale from profile and persists it", async ({ page }) => {
  await installV1ClientFixture(page);
  await page.goto("/");
  await page.getByLabel("用户ID (唯一标识)").fill("browser_gate_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/#\/chat$/);

  await page.getByRole("button", { name: "打开个人资料" }).click();
  await page.getByLabel("界面语言").selectOption("en-US");
  await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
  await expect(page.getByRole("tab", { name: "Friends" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Rooms" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Open profile" })).toBeVisible();
  await expect(page.getByRole("dialog", { name: "Profile" })).toBeVisible();
  await expect(page.getByLabel("Low-bandwidth mode")).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("chat.web.locale"))).toBe("en-US");
  await page.keyboard.press("Escape");
  await expect(page.getByText("Friend list", { exact: true })).toBeVisible();
  await expect(page.getByText("Online", { exact: true })).toBeVisible();
  await page.getByRole("tab", { name: "Rooms" }).click();
  await expect(page.getByText("Room list", { exact: true })).toBeVisible();
  await expect(page.getByText("Browser Gate Room", { exact: true })).toBeVisible();
  await page.getByText("Browser Gate Room", { exact: true }).click();
  await expect(page.getByText("0 online", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Online members" })).toBeVisible();
  await expect(page.getByRole("toolbar", { name: "Message tools" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Message content" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Send message" })).toBeVisible();
  await page.getByRole("button", { name: "Choose emoji" }).click();
  await expect(page.getByRole("dialog", { name: "Choose an emoji to send" })).toBeVisible();
  await expect(page.getByRole("grid", { name: "Emoji" })).toBeVisible();
  await page.keyboard.press("Escape");
});

test("pauses an offline login attempt and requires explicit retry after recovery", async ({ context, page }) => {
  const socketUrls: string[] = [];
  page.on("websocket", socket => socketUrls.push(socket.url()));
  await page.goto("/");
  await context.setOffline(true);
  await expect.poll(() => page.evaluate(() => navigator.onLine)).toBe(false);
  await page.getByLabel("用户ID (唯一标识)").fill("offline_gate_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("alert")).toHaveText("网络已断开，请在恢复连接后重试");
  expect(socketUrls).toEqual([]);
  await context.setOffline(false);
  await expect(page.getByRole("alert")).toHaveText("网络已恢复，可以重新登录");
  await page.waitForTimeout(100);
  expect(socketUrls).toEqual([]);
});

test("keeps the authenticated client shell visible and reauthenticates once after recovery", async ({ context, page }) => {
  await exerciseAuthenticatedClientShell(page, context);
});

test("decodes the baseline WebM video and Opus audio fixtures", async ({ page }) => {
  await page.goto("/");
  await exerciseMediaDecode(page);
});

test("loads the authenticated production shell with fixed high response latency", async ({ browser }) => {
  await exerciseDelayedClientBoot(browser);
});

test("migrates a native schema-1 conversation cache without retained media or secrets", async ({ page }) => {
  await exerciseNativeIndexedDbMigration(page);
});

test("keeps the authenticated shell usable while a legacy tab blocks cache upgrade", async ({ page }) => {
  await exerciseBlockedIndexedDbUpgrade(page);
});

test("records one exact branded-browser candidate smoke", async ({ browser }) => {
  test.skip(!brandedTarget, "Only the protected branded-browser matrix emits support evidence");
  const required = (name: string): string => {
    const value = process.env[name];
    if (!value) throw new Error(`Missing branded-browser evidence input: ${name}`);
    return value;
  };
  const context = await browser.newContext();
  const page = await context.newPage();
  const pageErrors: Error[] = [];
  page.on("pageerror", error => pageErrors.push(error));

  await page.goto("/", { waitUntil: "networkidle" });
  await expect(page).toHaveTitle("聊天室 - Web");
  await expect(page.getByRole("heading", { name: "ChatRoom" })).toBeVisible();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  await expect(page.locator('input[placeholder="输入唯一用户ID"]')).toBeEditable();

  const runtime = await page.evaluate(async () => {
    const databaseName = `chat-room-branded-gate-${crypto.randomUUID()}`;
    await new Promise<void>((resolve, reject) => {
      const request = indexedDB.open(databaseName, 1);
      request.onerror = () => reject(request.error);
      request.onupgradeneeded = () => request.result.createObjectStore("health");
      request.onsuccess = () => {
        request.result.close();
        const deletion = indexedDB.deleteDatabase(databaseName);
        deletion.onerror = () => reject(deletion.error);
        deletion.onsuccess = () => resolve();
      };
    });
    return {
      capabilities: [AbortController, BigInt, Blob, fetch, WebSocket]
        .every(value => typeof value === "function")
        && typeof crypto.randomUUID === "function",
      userAgent: navigator.userAgent,
    };
  });
  expect(runtime.capabilities).toBe(true);

  await page.addInitScript(() => {
    localStorage.setItem("serverHost", "evil.example");
    localStorage.setItem("serverPort", "443");
    localStorage.setItem("wsPath", "/steal");
  });
  const socketUrls: string[] = [];
  page.on("websocket", socket => socketUrls.push(socket.url()));
  await page.reload();
  await page.locator('input[placeholder="输入唯一用户ID"]').fill("branded_gate_user");
  await page.locator('input[type="password"]').fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect.poll(() => socketUrls.length).toBeGreaterThan(0);
  expect(new Set(socketUrls)).toEqual(new Set(["ws://127.0.0.1:9528/"]));
  await expect.poll(() => page.evaluate(() => ({
    host: localStorage.getItem("serverHost"),
    port: localStorage.getItem("serverPort"),
    path: localStorage.getItem("wsPath"),
  }))).toEqual({ host: null, port: null, path: null });

  const responsive = await context.newPage();
  responsive.on("pageerror", error => pageErrors.push(error));
  await responsive.setViewportSize({ width: 390, height: 844 });
  await responsive.goto("/");
  const bounds = await responsive.locator(".login-card").boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  expect(pageErrors).toEqual([]);

  const keyboard = await context.newPage();
  keyboard.on("pageerror", error => pageErrors.push(error));
  await exerciseKeyboardLogin(keyboard);
  expect(pageErrors).toEqual([]);

  const offline = await context.newPage();
  offline.on("pageerror", error => pageErrors.push(error));
  await offline.goto("/");
  const offlineSocketUrls: string[] = [];
  offline.on("websocket", socket => offlineSocketUrls.push(socket.url()));
  await context.setOffline(true);
  await expect.poll(() => offline.evaluate(() => navigator.onLine)).toBe(false);
  await offline.getByLabel("用户ID (唯一标识)").fill("offline_gate_user");
  await offline.getByLabel("密码").fill("non-secret-test-value");
  await offline.getByRole("button", { name: "登录" }).click();
  await expect(offline.getByRole("alert")).toHaveText("网络已断开，请在恢复连接后重试");
  expect(offlineSocketUrls).toEqual([]);
  await context.setOffline(false);
  await expect.poll(() => offline.evaluate(() => navigator.onLine)).toBe(true);
  await expect(offline.getByRole("alert")).toHaveText("网络已恢复，可以重新登录");
  await offline.waitForTimeout(100);
  expect(offlineSocketUrls).toEqual([]);
  expect(pageErrors).toEqual([]);

  const authenticatedContext = await browser.newContext();
  const authenticatedPage = await authenticatedContext.newPage();
  authenticatedPage.on("pageerror", error => pageErrors.push(error));
  await exerciseAuthenticatedClientShell(authenticatedPage, authenticatedContext);
  await exerciseMediaDecode(authenticatedPage);
  await authenticatedContext.close();
  expect(pageErrors).toEqual([]);
  await exerciseDelayedClientBoot(browser);
  const migrationContext = await browser.newContext();
  const migrationPage = await migrationContext.newPage();
  migrationPage.on("pageerror", error => pageErrors.push(error));
  await exerciseNativeIndexedDbMigration(migrationPage);
  await migrationContext.close();
  const blockedContext = await browser.newContext();
  const blockedPage = await blockedContext.newPage();
  blockedPage.on("pageerror", error => pageErrors.push(error));
  await exerciseBlockedIndexedDbUpgrade(blockedPage);
  await blockedContext.close();

  const architecture = process.arch === "x64" ? "x86_64" : process.arch;
  const evidence = {
    schemaVersion: 8,
    evidenceType: "web-browser-host-acceptance",
    status: "candidate-smoke-observed",
    product: "chat-room-web-client",
    targetId: brandedTarget,
    browserFamily: required("CHATROOM_BRANDED_BROWSER_FAMILY"),
    browserProduct: required("CHATROOM_BRANDED_BROWSER_PRODUCT"),
    supportPosition: required("CHATROOM_BRANDED_BROWSER_POSITION"),
    browserVersion: browser.version(),
    browserExecutableSha256: required("CHATROOM_BRANDED_BROWSER_EXECUTABLE_SHA256"),
    platform: `${process.platform}-${required("CHATROOM_BRANDED_BROWSER_HOST")}`,
    architecture,
    userAgent: runtime.userAgent,
    releaseId: required("CHATROOM_WEB_RELEASE_ID"),
    version: required("CHATROOM_WEB_VERSION"),
    sourceRevision: required("CHATROOM_WEB_SOURCE_REVISION"),
    artifactManifestSha256: required("CHATROOM_WEB_MANIFEST_SHA256"),
    checks: {
      productionLoginSurface: true,
      requiredWebCapabilities: true,
      indexedDb: true,
      serverEndpointIsolation: true,
      responsiveLogin: true,
      keyboardAccessibleLogin: true,
      announcedValidationError: true,
      offlineLoginPaused: true,
      recoveryStateAnnounced: true,
      authenticatedClientShell: true,
      credentialsRemainMemoryOnly: true,
      authenticatedOfflineRecovery: true,
      baselineMediaDecoded: true,
      delayedClientBoot: true,
      nativeIndexedDbMigration: true,
      blockedIndexedDbUpgradeRecovered: true,
      noPageErrors: true,
    },
    observedAt: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
  };
  writeFileSync(required("CHATROOM_BRANDED_BROWSER_EVIDENCE"), `${JSON.stringify(evidence, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
  await context.close();
});
