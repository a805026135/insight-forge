import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 开发时把 /api 和 /ws 反向代理到后端（默认 8080），免去跨域配置。
// 生产由 Nginx 统一反代（见 deploy/nginx/nginx.conf）。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      // WebSocket 大屏推送
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});
