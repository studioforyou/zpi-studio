// Vite config: dev server with the backend proxy, the @/ alias and the test (Vitest) setup.

import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.API_PROXY_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      // IPv4 explicitly: on Windows 'localhost' can bind to ::1 only, and the emulator
      // reaches this machine as 10.0.2.2 over IPv4 (invitation links open the /invite page that way).
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/actuator': { target: proxyTarget, changeOrigin: true },
      },
    },
    test: {
      environment: 'jsdom',
      // Limit concurrent Mantine renders so UI timers stay reliable on developer machines.
      maxWorkers: 2,
      globals: false,
      setupFiles: ['./src/test/setup.ts'],
    },
  };
});
