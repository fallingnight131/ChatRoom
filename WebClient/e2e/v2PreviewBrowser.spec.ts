import { expect, test, type WebSocketRoute } from "@playwright/test";

import { MessageType } from "../src/protocol/v2/generated/control_pb";
import {
  FIXTURE_CONVERSATION_ID,
  KEYBOARD_CONVERSATION_ID,
  PEER_ACCOUNT_ID,
  createV2ProtocolFixture,
  type V2ProtocolFixtureOptions,
} from "./fixtures/v2ProtocolFixture";

const enabled = process.env.CHATROOM_V2_BROWSER_PREVIEW === "true";
const searchCandidate = process.env.CHATROOM_V2_BROWSER_SEARCH === "true";
const searchRollback = process.env.CHATROOM_V2_BROWSER_SEARCH_ROLLBACK === "true";
const forwardingCandidate = process.env.CHATROOM_V2_BROWSER_FORWARDING === "true";
const forwardingRollback = process.env.CHATROOM_V2_BROWSER_FORWARDING_ROLLBACK === "true";
const mentionsCandidate = process.env.CHATROOM_V2_BROWSER_MENTIONS === "true";
const notificationsCandidate = process.env.CHATROOM_V2_BROWSER_NOTIFICATIONS === "true";
const notificationsRollback = process.env.CHATROOM_V2_BROWSER_NOTIFICATIONS_ROLLBACK === "true";
const accountBlockingCandidate = process.env.CHATROOM_V2_BROWSER_ACCOUNT_BLOCKING === "true";
const accountBlockingRollback = process.env.CHATROOM_V2_BROWSER_ACCOUNT_BLOCKING_ROLLBACK === "true";
const FIXTURE_MESSAGE_ID = "60000000-0000-4000-8000-000000000001";

async function installV2SocketFixture(
  page: import("@playwright/test").Page,
  mode: "accept" | "reject",
  options: V2ProtocolFixtureOptions = {},
) {
  const fixture = createV2ProtocolFixture(mode, options);
  const socketUrls: string[] = [];
  const sockets: WebSocketRoute[] = [];
  const clientCloses: Array<{ code?: number; reason?: string }> = [];
  await page.routeWebSocket("wss://fixture.invalid/v2/web", socket => {
    socketUrls.push("wss://fixture.invalid/v2/web");
    sockets.push(socket);
    socket.onClose((code, reason) => clientCloses.push({ code, reason }));
    socket.onMessage(message => {
      if (typeof message === "string") {
        throw new Error("V2 fixture received a text WebSocket frame");
      }
      const response = fixture.respond(Array.from(message));
      if (response === null) return;
      if (options.participantResponseDelayMs
          && fixture.receivedTypes.at(-1) === MessageType.LIST_CONVERSATION_PARTICIPANTS) {
        setTimeout(() => socket.send(Buffer.from(response)), options.participantResponseDelayMs);
      } else {
        socket.send(Buffer.from(response));
      }
    });
  });
  return { fixture, socketUrls, sockets, clientCloses };
}

async function installNotificationFixture(page: import("@playwright/test").Page) {
  await page.addInitScript(() => {
    const state = { requests: 0, notifications: [] as Array<{
      title: string;
      options: { body?: string; tag?: string };
      closed: boolean;
    }> };
    const handles: Array<{ onclick: null | (() => void) }> = [];
    class FakeNotification {
      static permission: NotificationPermission = "default";
      static async requestPermission(): Promise<NotificationPermission> {
        state.requests += 1;
        FakeNotification.permission = "granted";
        return "granted";
      }
      onclick: null | (() => void) = null;
      private readonly index: number;
      constructor(title: string, options: NotificationOptions = {}) {
        this.index = state.notifications.length;
        state.notifications.push({
          title,
          options: { body: options.body, tag: options.tag },
          closed: false,
        });
        handles.push(this);
      }
      close() { state.notifications[this.index]!.closed = true; }
    }
    Object.defineProperty(window, "Notification", { configurable: true, value: FakeNotification });
    Object.assign(window, {
      __chatNotificationFixture: {
        state,
        click(index: number) { handles[index]?.onclick?.(); },
      },
    });
  });
}

test("switches and persists the V2 preview locale before authentication", async ({ page }) => {
  test.skip(!enabled, "requires an explicit V2-enabled preview build");

  await page.goto("/#/preview/v2");
  await expect(page.getByRole("heading", { name: "ChatRoom V2" })).toBeVisible();

  const locale = page.getByLabel("界面语言");
  await expect(locale).toHaveValue("zh-CN");
  await locale.selectOption("en-US");

  await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
  await expect(page.getByLabel("Interface language")).toHaveValue("en-US");
  expect(await page.evaluate(() => localStorage.getItem("chat.web.locale"))).toBe("en-US");

  await page.reload();
  await expect(page.getByLabel("Interface language")).toHaveValue("en-US");
  await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
});

test("contains a V2 authentication rejection without persisting credentials", async ({ page }) => {
  test.skip(!enabled, "requires an explicit V2-enabled preview build");
  const { fixture, socketUrls } = await installV2SocketFixture(page, "reject");

  await page.goto("/#/preview/v2");
  await expect.poll(() => socketUrls).toEqual(["wss://fixture.invalid/v2/web"]);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.CLIENT_HELLO);
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("rejected_user");
  await page.getByLabel("密码").fill("rejected-password");
  await page.getByRole("button", { name: "登录" }).click();

  await expect(page.getByRole("alert")).toHaveText("Authentication rejected");
  await expect(page.getByRole("heading", { name: "登录 V2" })).toBeVisible();
  expect(fixture.receivedTypes).toContain(MessageType.AUTHENTICATE);
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain("rejected-password");
});

test("authenticates, synchronizes, and accepts one V2 message", async ({ page }) => {
  test.skip(!enabled, "requires an explicit V2-enabled preview build");
  const { fixture, socketUrls } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect.poll(() => socketUrls).toEqual(["wss://fixture.invalid/v2/web"]);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.CLIENT_HELLO);
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();

  const navigation = page.getByRole("navigation", { name: "V2 会话导航" });
  await expect(navigation).toBeVisible();
  const primaryConversation = page.getByRole("button", { name: /Browser Fixture Conversation/ });
  const keyboardConversation = page.getByRole("button", { name: /Keyboard Target Conversation/ });
  await expect(primaryConversation).toBeVisible();
  const navigationAccessibilityTree = await navigation.ariaSnapshot();
  expect(navigationAccessibilityTree).toContain('- navigation "V2 会话导航"');
  expect(navigationAccessibilityTree).toContain('button "Browser Fixture Conversation');
  expect(navigationAccessibilityTree).toContain('button "Keyboard Target Conversation');
  await primaryConversation.focus();
  await primaryConversation.press("ArrowDown");
  await expect(keyboardConversation).toBeFocused();
  await keyboardConversation.press("Home");
  await expect(primaryConversation).toBeFocused();
  await expect(primaryConversation).not.toHaveAttribute("aria-current", "page");
  await expect(keyboardConversation).not.toHaveAttribute("aria-current", "page");
  await primaryConversation.press("Enter");

  const log = page.getByRole("log", { name: "消息记录" });
  await expect(log).toHaveAttribute("aria-live", "polite");
  await expect(log.getByText("Fixture incoming message")).toBeVisible();
  await expect(page.getByRole("button", { name: "复制消息 1 正文" })).toBeVisible();
  await expect(page.getByRole("button", { name: "回复消息 1" })).toBeVisible();
  const timelineAccessibilityTree = await log.ariaSnapshot();
  expect(timelineAccessibilityTree).toContain('- log "消息记录"');
  expect(timelineAccessibilityTree).toContain("Fixture incoming message");
  expect(timelineAccessibilityTree).toContain('button "复制消息 1 正文"');
  expect(timelineAccessibilityTree).toContain('button "回复消息 1"');

  const lowBandwidth = page.getByLabel("省流量模式");
  await expect(lowBandwidth).not.toBeChecked();
  await lowBandwidth.check();
  await expect(lowBandwidth).toBeChecked();
  expect(await page.evaluate(() => localStorage.getItem("lowBandwidthMode"))).toBe("true");

  const devicesTrigger = page.getByRole("button", { name: "登录设备 1" });
  await devicesTrigger.click();
  const deviceDialog = page.getByRole("dialog", { name: "登录设备" });
  await expect(deviceDialog).toBeVisible();
  await expect(deviceDialog.getByRole("button", { name: "关闭登录设备" })).toBeFocused();
  await expect(deviceDialog.getByText("当前设备", { exact: true })).toBeVisible();
  await expect(deviceDialog.getByRole("button", { name: "撤销", exact: true })).toHaveCount(0);
  await page.keyboard.press("Shift+Tab");
  await expect(deviceDialog.getByRole("button", { name: "完成" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(deviceDialog).toBeHidden();
  await expect(devicesTrigger).toBeFocused();

  const composer = page.getByLabel("消息内容");
  await composer.fill("Fixture outgoing message");
  await composer.press("Enter");
  await expect(log.getByText("Fixture outgoing message")).toBeVisible();
  await expect(page.getByLabel("消息 2：已接收")).toBeVisible();
  await expect(composer).toBeFocused();
  await expect(composer).toHaveValue("");

  const receivedBeforeLocaleChange = fixture.receivedTypes.length;
  await page.getByLabel("界面语言").selectOption("en-US");
  await expect(page.locator("html")).toHaveAttribute("lang", "en-US");
  await expect(page.getByRole("navigation", { name: "V2 conversation navigation" })).toBeVisible();
  await expect(page.getByRole("log", { name: "Message history" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Copy message 1 text" })).toBeVisible();
  const localizedAccessibilityTree = await page.getByRole("log", { name: "Message history" }).ariaSnapshot();
  expect(localizedAccessibilityTree).toContain('- log "Message history"');
  expect(localizedAccessibilityTree).toContain('button "Copy message 1 text"');
  expect(localizedAccessibilityTree).not.toContain("复制消息 1 正文");
  expect(fixture.receivedTypes).toHaveLength(receivedBeforeLocaleChange);

  expect(fixture.receivedTypes).toEqual(expect.arrayContaining([
    MessageType.CLIENT_HELLO,
    MessageType.AUTHENTICATE,
    MessageType.LIST_CONVERSATIONS,
    MessageType.LIST_DEVICES,
    MessageType.READ_MESSAGE_HISTORY,
    MessageType.SUBMIT_MESSAGE,
  ]));
  expect(fixture.receivedTypes.filter(type => type === MessageType.SUBMIT_MESSAGE)).toHaveLength(1);
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain("non-secret-test-value");
  expect(socketUrls).toEqual(["wss://fixture.invalid/v2/web"]);
});

test("enables privacy-safe notifications from a gesture and opens the stable conversation", async ({ page }) => {
  test.skip(!notificationsCandidate, "requires the notification candidate build");
  await installNotificationFixture(page);
  const { fixture, sockets } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();

  const enable = page.getByRole("button", { name: "启用桌面通知" });
  await expect(enable).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { state: { requests: number } };
  }).__chatNotificationFixture.state.requests)).toBe(0);
  await enable.click();
  await expect(page.getByText("桌面通知已启用", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem("chat.v2.notifications-enabled"))).toBe("true");

  const primary = page.getByRole("button", { name: /Browser Fixture Conversation/ });
  const target = page.getByRole("button", { name: /Keyboard Target Conversation/ });
  await target.click();
  await expect(target).toHaveAttribute("aria-current", "page");
  await primary.click();
  await expect(primary).toHaveAttribute("aria-current", "page");

  const event = fixture.publishedMessage({ mentioned: true, content: "@private fixture notification text" });
  sockets.at(-1)?.send(Buffer.from(event));
  await expect.poll(() => page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { state: { notifications: unknown[] } };
  }).__chatNotificationFixture.state.notifications.length)).toBe(1);
  const shown = await page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { state: { notifications: Array<{
      title: string; options: { body?: string; tag?: string };
    }> } };
  }).__chatNotificationFixture.state.notifications[0]);
  expect(shown).toEqual({
    title: "ChatRoom 中有人提到了你",
    closed: false,
    options: {
      body: "打开 ChatRoom 查看消息",
      tag: "chat-v2-message-60000000-0000-4000-8000-000000000010",
    },
  });
  expect(JSON.stringify(shown)).not.toContain("@private fixture notification text");
  expect(JSON.stringify(shown)).not.toContain(PEER_ACCOUNT_ID);

  sockets.at(-1)?.send(Buffer.from(event));
  await page.waitForTimeout(100);
  expect(await page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { state: { notifications: unknown[] } };
  }).__chatNotificationFixture.state.notifications.length)).toBe(1);
  await page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { click(index: number): void };
  }).__chatNotificationFixture.click(0));
  await expect(target).toHaveAttribute("aria-current", "page");

  await page.getByRole("button", { name: "关闭桌面通知" }).click();
  await expect(page.getByText("桌面通知未启用", { exact: true })).toBeVisible();
  sockets.at(-1)?.send(Buffer.from(fixture.publishedMessage({
    messageId: "60000000-0000-4000-8000-000000000011",
    conversationId: FIXTURE_CONVERSATION_ID,
    conversationSequence: 2n,
  })));
  await page.waitForTimeout(100);
  const finalNotificationState = await page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { state: { notifications: unknown[]; requests: number } };
  }).__chatNotificationFixture.state);
  expect(finalNotificationState.requests).toBe(1);
  expect(finalNotificationState.notifications).toHaveLength(1);
  expect(await page.evaluate(() => localStorage.getItem("chat.v2.notifications-enabled"))).toBe("false");
});

test("keeps notification UI absent in the rollback build", async ({ page }) => {
  test.skip(!notificationsRollback, "requires the notification rollback build");
  await installNotificationFixture(page);
  await installV2SocketFixture(page, "accept");
  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page.getByRole("button", { name: "启用桌面通知" })).toHaveCount(0);
  expect(await page.evaluate(() => (window as unknown as {
    __chatNotificationFixture: { state: { requests: number } };
  }).__chatNotificationFixture.state.requests)).toBe(0);
});

test("selects a non-self participant and sends one identity-backed Unicode mention", async ({ page }) => {
  test.skip(!enabled || !mentionsCandidate,
    "requires the explicit V2 structured-mention browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept", {
    participantResponseDelayMs: 400,
  });

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  const trigger = page.getByRole("button", { name: "@ 提及成员" });
  await trigger.focus();
  await trigger.press("Enter");
  const picker = page.getByRole("dialog", { name: "选择要提及的成员" });
  await expect(picker).toBeVisible();
  await expect(picker.getByRole("button", { name: "关闭成员选择器" }))
    .toBeFocused({ timeout: 250 });
  const participants = picker.getByRole("listbox", { name: "会话成员" });
  const peer = participants.getByRole("option", { name: /李雷/ });
  await expect(peer).toBeVisible();
  await expect(peer).toBeFocused();
  await expect(participants.getByRole("option", { name: /Browser V2 User/ })).toHaveCount(0);
  const participantTree = await participants.ariaSnapshot();
  expect(participantTree).toContain('- listbox "会话成员"');
  expect(participantTree).toContain('option "李雷');
  expect(participantTree).not.toContain("Browser V2 User");

  await peer.focus();
  await peer.press("Enter");
  await expect(picker).toBeHidden();
  const composer = page.getByLabel("消息内容");
  await expect(composer).toBeFocused();
  await expect(composer).toHaveValue("@李雷 ");
  await composer.pressSequentially("Fixture mentioned message");
  await composer.press("Enter");

  const log = page.getByRole("log", { name: "消息记录" });
  await expect(log.getByText("@李雷 Fixture mentioned message")).toBeVisible();
  await expect(log.locator(".message-mention", { hasText: "@李雷" }))
    .toHaveAttribute("title", `账号 ${PEER_ACCOUNT_ID}`);
  await expect(page.getByLabel("消息 2：已接收")).toBeVisible();
  expect(fixture.participantRequests).toEqual([{
    conversationId: FIXTURE_CONVERSATION_ID,
    afterAccountId: "",
    limit: 100,
  }]);
  expect(fixture.mentionSubmissions).toEqual([{
    text: "@李雷 Fixture mentioned message",
    targetAccountId: PEER_ACCOUNT_ID,
    startUtf8Byte: 0,
    lengthUtf8Bytes: 7,
  }]);
  expect(fixture.receivedTypes.filter(
    type => type === MessageType.LIST_CONVERSATION_PARTICIPANTS)).toHaveLength(1);
  expect(fixture.receivedTypes.filter(type => type === MessageType.SUBMIT_MESSAGE)).toHaveLength(1);
});

test("does not steal focus after the user leaves a loading mention picker", async ({ page }) => {
  test.skip(!enabled || !mentionsCandidate,
    "requires the explicit V2 structured-mention browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept", {
    participantResponseDelayMs: 400,
  });

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  const trigger = page.getByRole("button", { name: "@ 提及成员" });
  await trigger.click();
  const picker = page.getByRole("dialog", { name: "选择要提及的成员" });
  await expect(picker.getByRole("button", { name: "关闭成员选择器" }))
    .toBeFocused({ timeout: 250 });
  await trigger.focus();
  await expect(trigger).toBeFocused();
  await expect(picker.getByRole("option", { name: /李雷/ })).toBeVisible();
  await expect(trigger).toBeFocused();
  expect(fixture.participantRequests).toHaveLength(1);
});

test("repairs an ACK-lost mention from history without duplicate submission", async ({ page }) => {
  test.skip(!enabled || !mentionsCandidate,
    "requires the explicit V2 structured-mention browser candidate");
  const { fixture, sockets } = await installV2SocketFixture(page, "accept", {
    dropFirstMentionAcceptance: true,
  });

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
  await page.getByRole("button", { name: "@ 提及成员" }).click();
  await page.getByRole("dialog", { name: "选择要提及的成员" })
    .getByRole("option", { name: /李雷/ }).click();
  const composer = page.getByLabel("消息内容");
  await composer.pressSequentially("Fixture mentioned message");
  await composer.press("Enter");
  await expect.poll(() => fixture.mentionSubmissions.length).toBe(1);

  await sockets[0]!.close({ code: 1012, reason: "fixture dropped mention acceptance" });
  await expect.poll(() => sockets.length).toBe(2);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.RESUME_SESSION);
  await expect(page.getByText("已安全连接", { exact: true })).toBeVisible();

  const log = page.getByRole("log", { name: "消息记录" });
  await expect(log.getByText("@李雷 Fixture mentioned message")).toBeVisible();
  await expect(log.locator(".message-mention", { hasText: "@李雷" }))
    .toHaveAttribute("title", `账号 ${PEER_ACCOUNT_ID}`);
  await expect(page.getByLabel("消息 2：已接收")).toBeVisible();
  await page.waitForTimeout(250);
  expect(fixture.mentionSubmissions).toHaveLength(1);
  expect(fixture.receivedTypes.filter(type => type === MessageType.SUBMIT_MESSAGE)).toHaveLength(1);
});

test("activates bounded V2 search and reveals one result through the keyboard", async ({ page }) => {
  test.skip(!enabled || !searchCandidate,
    "requires the explicit V2 message-search browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
  await expect(page.getByRole("log", { name: "消息记录" })
    .getByText("Fixture incoming message")).toBeVisible();

  const searchTrigger = page.getByRole("button", { name: "搜索消息" });
  await searchTrigger.focus();
  await searchTrigger.press("Enter");
  const query = page.getByRole("searchbox", { name: "搜索当前会话" });
  await expect(query).toBeFocused();
  await query.fill("Fixture");
  await query.press("Enter");

  await expect(page.getByText("已找到 1 条结果")).toBeVisible();
  const results = page.getByRole("list", { name: "消息搜索结果" });
  const result = results.getByRole("button", { name: /Fixture incoming message/ });
  await expect(result).toBeVisible();
  const resultAccessibilityTree = await results.ariaSnapshot();
  expect(resultAccessibilityTree).toContain('- list "消息搜索结果"');
  expect(resultAccessibilityTree).toContain('button "Fixture incoming message');

  await result.focus();
  await result.press("Enter");
  await expect(page.locator(`#v2-message-${FIXTURE_MESSAGE_ID}`)).toBeFocused();
  await expect.poll(() => fixture.receivedTypes.filter(
    type => type === MessageType.READ_MESSAGE_HISTORY).length).toBe(2);
  expect(fixture.searchQueries).toEqual([{
    conversationId: FIXTURE_CONVERSATION_ID,
    literalQuery: "Fixture",
    beforeSequence: 0n,
    limit: 50,
  }]);
  expect(fixture.receivedTypes.filter(
    type => type === MessageType.SEARCH_CONVERSATION_MESSAGES)).toHaveLength(1);
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain("Fixture");
});

test("clears V2 search on disconnect and requires an explicit query after resume", async ({ page }) => {
  test.skip(!enabled || !searchCandidate,
    "requires the explicit V2 message-search browser candidate");
  const { fixture, sockets } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
  await page.getByRole("button", { name: "搜索消息" }).click();
  const query = page.getByRole("searchbox", { name: "搜索当前会话" });
  await query.fill("Fixture");
  await query.press("Enter");
  await expect(page.getByText("已找到 1 条结果")).toBeVisible();
  await expect(page.getByRole("list", { name: "消息搜索结果" })).toBeVisible();
  expect(fixture.searchQueries).toHaveLength(1);

  await sockets[0]!.close({ code: 1012, reason: "fixture restart during search" });
  await expect.poll(() => sockets.length).toBe(2);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.RESUME_SESSION);
  await expect(page.getByText("已安全连接", { exact: true })).toBeVisible();
  await expect(page.getByRole("list", { name: "消息搜索结果" })).toHaveCount(0);
  await expect(page.getByText("已找到 1 条结果")).toHaveCount(0);
  await expect(query).toHaveValue("Fixture");
  await page.waitForTimeout(250);
  expect(fixture.searchQueries).toHaveLength(1);

  await query.press("Enter");
  await expect(page.getByText("已找到 1 条结果")).toBeVisible();
  await expect(page.getByRole("list", { name: "消息搜索结果" })).toBeVisible();
  expect(fixture.searchQueries).toHaveLength(2);
  expect(fixture.searchQueries[1]).toEqual(fixture.searchQueries[0]);
});

test("keeps V2 search absent after the Web candidate rollback", async ({ page }) => {
  test.skip(!enabled || !searchRollback,
    "requires the explicit V2 message-search rollback browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  await expect(page.getByRole("button", { name: "搜索消息" })).toHaveCount(0);
  expect(fixture.searchQueries).toEqual([]);
  expect(fixture.receivedTypes).not.toContain(MessageType.SEARCH_CONVERSATION_MESSAGES);
});

test("sets direct-account block state through an accessible confirmed dialog", async ({ page }) => {
  test.skip(!enabled || !accountBlockingCandidate,
    "requires the explicit V2 account-blocking browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  const trigger = page.getByRole("button", { name: "隐私与屏蔽" });
  await trigger.focus();
  await trigger.press("Enter");
  const dialog = page.getByRole("dialog", { name: "管理账号屏蔽" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "关闭账号屏蔽管理" })).toBeFocused();
  await expect(dialog.getByText("李雷", { exact: true })).toBeVisible();
  await expect(dialog.getByText("已确认未屏蔽", { exact: true })).toBeVisible();
  await expect(dialog.getByText("暂无已屏蔽账号。", { exact: true })).toBeVisible();
  expect(await dialog.ariaSnapshot()).toContain('- dialog "管理账号屏蔽"');

  await dialog.getByRole("button", { name: "屏蔽账号" }).click();
  const confirmation = dialog.getByRole("group", { name: "确认屏蔽此账号？" });
  await expect(confirmation).toBeVisible();
  await confirmation.getByRole("button", { name: "确认" }).click();
  await expect(dialog.getByText("已确认屏蔽", { exact: true })).toBeVisible();
  await expect(dialog.getByRole("list", { name: "已屏蔽账号" })
    .getByText("李雷", { exact: true })).toBeVisible();
  await expect(dialog.getByText("此状态来自本次操作结果。", { exact: true })).toBeVisible();
  expect(fixture.accountBlockRequests).toHaveLength(1);
  expect(fixture.accountBlockRequests[0]).toEqual({
    targetAccountId: PEER_ACCOUNT_ID,
    blocked: true,
    clientOperationId: expect.stringMatching(/^[0-9a-f-]{36}$/),
  });

  await dialog.getByRole("button", { name: "解除屏蔽 李雷", exact: true }).click();
  await dialog.getByRole("group", { name: "确认解除屏蔽？" })
    .getByRole("button", { name: "确认" }).click();
  await expect(dialog.getByText("已确认未屏蔽", { exact: true })).toBeVisible();
  await expect(dialog.getByText("暂无已屏蔽账号。", { exact: true })).toBeVisible();
  expect(fixture.accountBlockRequests).toHaveLength(2);
  expect(fixture.accountBlockRequests[1]?.blocked).toBe(false);

  await dialog.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(trigger).toBeFocused();
});

test("keeps account blocking absent after the Web candidate rollback", async ({ page }) => {
  test.skip(!enabled || !accountBlockingRollback,
    "requires the explicit V2 account-blocking rollback candidate");
  const { fixture } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  await expect(page.getByRole("button", { name: "隐私与屏蔽" })).toHaveCount(0);
  expect(fixture.accountBlockRequests).toEqual([]);
  expect(fixture.receivedTypes).not.toContain(MessageType.SET_ACCOUNT_BLOCK);
});

test("forwards one server-authoritative message to a distinct conversation", async ({ page }) => {
  test.skip(!enabled || !forwardingCandidate,
    "requires the explicit V2 message-forwarding browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
  await expect(page.getByText("Fixture incoming message")).toBeVisible();

  const forward = page.getByRole("button", { name: "转发消息 1" });
  await forward.focus();
  await forward.press("Enter");
  const dialog = page.getByRole("dialog", { name: "转发到会话" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "关闭转发目标选择" })).toBeFocused();
  const targets = dialog.getByRole("listbox", { name: "转发目标会话" });
  await expect(targets.getByRole("option", { name: /Browser Fixture Conversation/ })).toHaveCount(0);
  const target = targets.getByRole("option", { name: /Keyboard Target Conversation/ });
  await expect(target).toBeVisible();
  const targetTree = await targets.ariaSnapshot();
  expect(targetTree).toContain('- listbox "转发目标会话"');
  expect(targetTree).toContain('option "Keyboard Target Conversation');
  expect(targetTree).not.toContain("Browser Fixture Conversation");

  await target.focus();
  await target.press("Enter");
  await expect(dialog).toBeHidden();
  await expect.poll(() => fixture.forwardRequests.length).toBe(1);
  expect(fixture.forwardRequests[0]).toEqual({
    sourceConversationId: FIXTURE_CONVERSATION_ID,
    sourceMessageId: FIXTURE_MESSAGE_ID,
    expectedSourceContentRevision: 0,
    targetConversationId: KEYBOARD_CONVERSATION_ID,
    clientMessageId: expect.stringMatching(/^[0-9a-f-]{36}$/),
  });

  await page.getByRole("button", { name: /Keyboard Target Conversation/ }).click();
  const targetLog = page.getByRole("log", { name: "消息记录" });
  await expect(targetLog.getByText("Fixture incoming message")).toBeVisible();
  await expect(targetLog.getByText("已转发", { exact: true })).toBeVisible();
  await expect(page.getByLabel("消息 1：已接收")).toBeVisible();
  expect(fixture.receivedTypes.filter(type => type === MessageType.FORWARD_MESSAGE)).toHaveLength(1);
  expect(await targetLog.innerText()).not.toContain(FIXTURE_CONVERSATION_ID);
  expect(await targetLog.innerText()).not.toContain(FIXTURE_MESSAGE_ID);
});

test("repairs an ACK-lost forward from target history without duplicate submission", async ({ page }) => {
  test.skip(!enabled || !forwardingCandidate,
    "requires the explicit V2 message-forwarding browser candidate");
  const { fixture, sockets } = await installV2SocketFixture(page, "accept", {
    dropFirstForwardAcceptance: true,
  });

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
  await page.getByRole("button", { name: "转发消息 1" }).click();
  const dialog = page.getByRole("dialog", { name: "转发到会话" });
  await dialog.getByRole("option", { name: /Keyboard Target Conversation/ }).click();
  await expect(dialog).toBeHidden();
  await expect.poll(() => fixture.forwardRequests.length).toBe(1);

  await sockets[0]!.close({ code: 1012, reason: "fixture dropped forward acceptance" });
  await expect.poll(() => sockets.length).toBe(2);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.RESUME_SESSION);
  await expect(page.getByText("已安全连接", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: /Keyboard Target Conversation/ }).click();
  const targetLog = page.getByRole("log", { name: "消息记录" });
  await expect(targetLog.getByText("Fixture incoming message")).toBeVisible();
  await expect(targetLog.getByText("已转发", { exact: true })).toBeVisible();
  await expect(page.getByLabel("消息 1：已接收")).toBeVisible();
  await page.waitForTimeout(250);
  expect(fixture.forwardRequests).toHaveLength(1);
  expect(fixture.receivedTypes.filter(type => type === MessageType.FORWARD_MESSAGE)).toHaveLength(1);
  expect(await targetLog.innerText()).not.toContain(FIXTURE_CONVERSATION_ID);
  expect(await targetLog.innerText()).not.toContain(FIXTURE_MESSAGE_ID);
});

test("keeps V2 forwarding absent after the Web candidate rollback", async ({ page }) => {
  test.skip(!enabled || !forwardingRollback,
    "requires the explicit V2 message-forwarding rollback browser candidate");
  const { fixture } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  await expect(page.getByRole("button", { name: "转发消息 1" })).toHaveCount(0);
  expect(fixture.forwardRequests).toEqual([]);
  expect(fixture.receivedTypes).not.toContain(MessageType.FORWARD_MESSAGE);
});

test("resumes an in-memory V2 session and repairs ordered history", async ({ page }) => {
  test.skip(!enabled, "requires an explicit V2-enabled preview build");
  const { fixture, socketUrls, sockets } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  const log = page.getByRole("log", { name: "消息记录" });
  await expect(log.getByText("Fixture incoming message")).toBeVisible();
  await sockets[0]!.close({ code: 1012, reason: "fixture restart" });

  await expect.poll(() => sockets.length).toBe(2);
  await expect.poll(() => fixture.receivedTypes.filter(
    type => type === MessageType.CLIENT_HELLO).length).toBe(2);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.RESUME_SESSION);
  await expect(log.getByText("Fixture repaired message")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "V2 会话导航" })).toBeVisible();
  expect(socketUrls).toEqual([
    "wss://fixture.invalid/v2/web",
    "wss://fixture.invalid/v2/web",
  ]);
  expect(await page.evaluate(() => Object.keys(localStorage).filter(
    key => /session|resume|token/i.test(key)))).toEqual([]);
});

test("pauses V2 retries offline and recovers one queued message explicitly", async ({ context, page }) => {
  test.skip(!enabled, "requires an explicit V2-enabled preview build");
  const { fixture, sockets } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  await page.getByLabel("用户 ID").fill("browser_v2_user");
  await page.getByLabel("密码").fill("non-secret-test-value");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();
  await expect(page.getByText("已安全连接", { exact: true })).toBeVisible();

  await context.setOffline(true);
  await expect(page.getByText("网络离线", { exact: true })).toBeVisible();
  await page.waitForTimeout(650);
  expect(sockets).toHaveLength(1);

  await page.getByLabel("消息内容").fill("Offline queued message");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(page.getByRole("button", { name: "重试这条发送失败的消息" })).toBeVisible();
  expect(fixture.receivedTypes).not.toContain(MessageType.SUBMIT_MESSAGE);

  await context.setOffline(false);
  await expect.poll(() => sockets.length).toBe(2);
  await expect.poll(() => fixture.receivedTypes).toContain(MessageType.RESUME_SESSION);
  await expect(page.getByText("已安全连接", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "重试这条发送失败的消息" }).click();
  await expect(page.getByLabel("消息 3：已接收")).toBeVisible();
  expect(fixture.receivedTypes.filter(
    type => type === MessageType.SUBMIT_MESSAGE)).toHaveLength(1);
});

test("stops the V2 socket when returning to the stable V1 route", async ({ page }) => {
  test.skip(!enabled, "requires an explicit V2-enabled preview build");
  const { sockets, clientCloses } = await installV2SocketFixture(page, "accept");

  await page.goto("/#/preview/v2");
  await expect(page.getByText("可登录", { exact: true })).toBeVisible();
  expect(sockets).toHaveLength(1);
  await page.goto("/#/login");

  await expect(page.getByRole("form", { name: "ChatRoom" })).toBeVisible();
  await expect.poll(() => clientCloses).toEqual([{
    code: 1000,
    reason: "client stopped",
  }]);
  await page.waitForTimeout(650);
  expect(sockets).toHaveLength(1);
});
