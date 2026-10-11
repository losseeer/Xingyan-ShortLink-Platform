import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 生产形态由 nginx 静态托管并同源反代 /api/v1/stats（DESIGN 3.4/7.2），所以这里不需要 base 路径技巧。
// dev 服务器单独配一条 proxy：否则本地 5173 直连 8040 就成了跨域，
// 而那正是部署形态刻意避免的事——开发时走同一条路径，才不会写出"只在 dev 能跑"的代码。
export default defineConfig({
  plugins: [vue()],
  build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 1200 },
  server: {
    port: 5173,
    proxy: { '/api/v1/stats': { target: 'http://localhost:8040', changeOrigin: true } }
  }
})
