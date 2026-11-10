import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Keep API traffic on the local development server.
// The browser never needs direct cross-origin access to Spring Boot.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 15173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
    },
  },
});
