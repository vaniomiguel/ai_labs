import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  reporter: "list",
  use: { baseURL: "http://127.0.0.1:14200", trace: "retain-on-failure", screenshot: "only-on-failure" },
  webServer: [
    { command: "bash ../scripts/run-e2e-backend.sh", url: "http://127.0.0.1:18081/api/v1/csrf", timeout: 120000 },
    { command: "npx ng serve --host 127.0.0.1 --port 14200 --proxy-config proxy.e2e.conf.json", url: "http://127.0.0.1:14200", timeout: 60000 },
  ],
});
