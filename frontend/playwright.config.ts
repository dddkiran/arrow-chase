import { defineConfig } from '@playwright/test';

/**
 * Browser end-to-end smoke test. Needs the backend running on :8080 (see README); starts Vite itself if it is
 * not already running.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:5173',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
  },
});
