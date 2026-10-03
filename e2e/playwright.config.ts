import { defineConfig } from '@playwright/test';

/**
 * Full-stack end-to-end tests: real browser -> real frontend (Vite) -> real backend (Spring Boot). Nothing is
 * mocked. Playwright starts both apps if they are not already running, and reuses them if they are
 * (e.g. under `npm run dev`).
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
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
