import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8082'

export default defineConfig({
  plugins: [
    vue(),
  ],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (
              proxyRes.headers['content-type'] &&
              proxyRes.headers['content-type'].includes('text/event-stream')
            ) {
              proxyRes.headers['cache-control'] = 'no-cache'
            }
          })
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
