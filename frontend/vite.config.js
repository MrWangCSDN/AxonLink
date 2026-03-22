import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  // 构建产物直接输出到 Spring Boot 的静态资源目录
  build: {
    outDir: path.resolve(__dirname, '../src/main/resources/static'),
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // 开发时：/api/* 请求转发到 Spring Boot
      '/api': {
        target: 'http://localhost:8123',
        changeOrigin: true,
      }
    }
  }
})
