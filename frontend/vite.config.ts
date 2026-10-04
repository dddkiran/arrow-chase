/// <reference types="vitest/config" />
import { createLogger, defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

// When a browser tab closes or reloads, the backend's next game message is written into a socket the browser has
// already dropped, and Vite's dev proxy logs that with a full stack trace. That is a player leaving, not a fault,
// so drop just those codes. Real proxy failures (e.g. ECONNREFUSED when the backend is down) are still logged.
const CLIENT_WENT_AWAY = new Set(['ECONNABORTED', 'ECONNRESET', 'EPIPE']);
const logger = createLogger();
const logError = logger.error;
logger.error = (message, options) => {
  const code = (options?.error as NodeJS.ErrnoException | null | undefined)?.code;
  if (message.includes('ws proxy') && code && CLIENT_WENT_AWAY.has(code)) return;
  logError(message, options);
};

export default defineConfig({
  plugins: [react()],
  customLogger: logger,
  server: {
    port: 5173,
    proxy: { '/ws': { target: backend, ws: true } },
  },
  preview: {
    port: 4173,
    proxy: { '/ws': { target: backend, ws: true } },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test-setup.ts'],
  },
});
