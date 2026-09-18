import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      // import.meta.dirname, not __dirname: Vite's native config loader is
      // becoming the default and does not provide the CommonJS globals.
      "@": new URL('./src', import.meta.url).pathname,
    },
  },
  server: {
    proxy: {
      // The API is same-origin in dev, so the browser never makes a
      // cross-origin call and CORS is exercised only where it matters.
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
