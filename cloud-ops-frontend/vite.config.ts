import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流式专用配置：禁用缓冲，保证 token 实时推送
        configure: (proxy) => {
          // 禁用 http-proxy 的 gzip 解压缓冲（SSE 流式需要）
          ;(proxy as any).on('proxyRes', (proxyRes: any) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['X-Accel-Buffering'] = 'no'
              proxyRes.headers['Cache-Control'] = 'no-cache'
            }
          })
        }
      },
      '/health': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
