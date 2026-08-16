import { expect, test, type WebSocketRoute } from "@playwright/test";

import { MessageType } from "../src/protocol/v2/generated/control_pb";
import { createV2ProtocolFixture } from "./fixtures/v2ProtocolFixture";

const enabled = process.env.CHATROOM_V2_BROWSER_PREVIEW === "true";

async function installV2SocketFixture(
  page: import("@playwright/test").Page,
  mode: "accept" | "reject",
) {
  const fixture = createV2ProtocolFixture(mode);
  const socketUrls: string[] = [];
  const sockets: WebSocketRoute[] = [];
  await page.routeWebSocket("wss://fixture.invalid/v2/web", socket => {
    socketUrls.push("wss://fixture.invalid/v2/web");
    sockets.push(socket);
    socket.onMessage(message => {
      if (typeof message === "string") {
        throw new Error("V2 fixture received a text WebSocket frame");
      }
      socket.send(Buffer.from(fixture.respond(Array.from(message))));
    });
  });
  return { fixture, socketUrls, sockets };
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
  await expect(page.getByRole("button", { name: /Browser Fixture Conversation/ })).toBeVisible();
  await page.getByRole("button", { name: /Browser Fixture Conversation/ }).click();

  const log = page.getByRole("log", { name: "消息记录" });
  await expect(log.getByText("Fixture incoming message")).toBeVisible();
  await expect(page.getByRole("button", { name: "复制消息 1 正文" })).toBeVisible();
  await expect(page.getByRole("button", { name: "回复消息 1" })).toBeVisible();

  const lowBandwidth = page.getByLabel("省流量模式");
  await expect(lowBandwidth).not.toBeChecked();
  await lowBandwidth.check();
  await expect(lowBandwidth).toBeChecked();
  expect(await page.evaluate(() => localStorage.getItem("lowBandwidthMode"))).toBe("true");

  await page.getByLabel("消息内容").fill("Fixture outgoing message");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await expect(log.getByText("Fixture outgoing message")).toBeVisible();
  await expect(page.getByLabel("消息 2：已接收")).toBeVisible();

  expect(fixture.receivedTypes).toEqual(expect.arrayContaining([
    MessageType.CLIENT_HELLO,
    MessageType.AUTHENTICATE,
    MessageType.LIST_CONVERSATIONS,
    MessageType.LIST_DEVICES,
    MessageType.READ_MESSAGE_HISTORY,
    MessageType.SUBMIT_MESSAGE,
  ]));
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain("non-secret-test-value");
  expect(socketUrls).toEqual(["wss://fixture.invalid/v2/web"]);
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
