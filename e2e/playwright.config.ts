import { defineConfig } from '@playwright/test';

/**
 * Functional tests of the running system, one folder per app:
 *   backend/   black-box tests of the WebSocket API (no browser)
 *   frontend/  the game in a real browser, through the real frontend, against the real backend
 * Nothing is mocked. Playwright starts both apps if they are not already running and reuses them if they are
 * (e.g. under `npm run dev`).
 */
export default defineConfig({
  timeout: 30_000,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  projects: [
    {
      name: 'backend',
      testDir: './backend',
    },
    {
      name: 'frontend',
      testDir: './frontend',
      use: {
        browserName: 'chromium',
        baseURL: 'http://localhost:5173',
        screenshot: 'only-on-failure',
        trace: 'retain-on-failure',
      },
    },
  ],
  webServer: [
    {
      name: 'backend',
      command: 'node ../scripts/mvnw.mjs -q spring-boot:run',
      url: 'http://localhost:8080/actuator/health',
      reuseExistingServer: true,
      timeout: 240_000, // first run downloads Maven and dependencies
    },
    {
      name: 'frontend',
      command: 'npm --prefix ../frontend run dev',
      url: 'http://localhost:5173',
      reuseExistingServer: true,
      timeout: 60_000,
    },
  ],
});
