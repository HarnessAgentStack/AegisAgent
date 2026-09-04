/**
 * @file Vite 构建配置
 * @description 配置 React 插件、路径别名、开发代理与生产分包策略
 * @author wang.zhen
 * @since 1.0.0
 */
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    open: false,
    proxy: {
      // 认证接口：转发到 aegis-admin 8082（优先匹配，需在 /api/admin 之前）
      '/api/admin/auth': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      // 运行平面（对话/会话/SSE）：转发到 aegis-runtime 8081
      // SSE 流式响应需禁用超时与缓冲，确保 text/event-stream 透传
      '/api/runtime': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        timeout: 0,
        proxyTimeout: 0,
        selfHandleResponse: false,
        // SSE 关键：不压缩、保持长连接、透传原始响应
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('Accept-Encoding', 'identity');
            proxyReq.setHeader('Cache-Control', 'no-cache');
            proxyReq.setHeader('Connection', 'keep-alive');
          });
          proxy.on('proxyRes', (proxyRes) => {
            // 确保 SSE 响应头正确透传
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache';
              proxyRes.headers['connection'] = 'keep-alive';
            }
          });
        },
      },
      // 管理平面（智能体/资源/租户/监控）：转发到 aegis-admin 8082
      '/api/admin': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      // 其他 /api 默认转发到 admin
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2022',
    cssCodeSplit: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd', '@ant-design/icons'],
          'query-vendor': ['@tanstack/react-query'],
          'utils-vendor': ['axios', 'dayjs'],
        },
      },
    },
  },
});