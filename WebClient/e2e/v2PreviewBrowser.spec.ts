import { expect, test } from "@playwright/test";

const enabled = process.env.CHATROOM_V2_BROWSER_PREVIEW === "true";

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
