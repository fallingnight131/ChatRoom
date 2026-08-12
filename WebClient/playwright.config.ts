import { defineConfig, devices } from "@playwright/test";

const brandedTarget = process.env.CHATROOM_BRANDED_BROWSER_TARGET;
const brandedFamily = process.env.CHATROOM_BRANDED_BROWSER_FAMILY;
const brandedExecutable = process.env.CHATROOM_BRANDED_BROWSER_EXECUTABLE;
const brandedMode = Boolean(brandedTarget || brandedFamily || brandedExecutable);

if (brandedMode && (!brandedTarget || !brandedExecutable || !["chrome", "edge", "firefox"].includes(brandedFamily ?? ""))) {
  throw new Error("Branded browser mode requires an exact target, family, and executable");
}

const projects = brandedMode
  ? [{
      name: brandedTarget!,
      use: {
        ...(brandedFamily === "firefox" ? devices["Desktop Firefox"] : devices["Desktop Chrome"]),
        browserName: brandedFamily === "firefox" ? "firefox" as const : "chromium" as const,
        launchOptions: { executablePath: brandedExecutable },
      },
    }]
  : [
      { name: "chromium", use: { ...devices["Desktop Chrome"] } },
      { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    ];

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["line"], ["html", { open: "never" }]] : "line",
  use: {
    baseURL: process.env.CHATROOM_BROWSER_BASE_URL ?? "http://127.0.0.1:4173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects,
  webServer: {
    command: process.env.CHATROOM_BROWSER_SERVER_COMMAND
      ?? "npm run preview -- --host 127.0.0.1 --port 4173",
    url: process.env.CHATROOM_BROWSER_BASE_URL ?? "http://127.0.0.1:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
