import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      // 配置 @ 指向 src 目录，这样 import 可以写 '@/api/request'
      // 而不用写 '../../api/request' 这种数不清层数的相对路径
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  // ----------------------------------------------------------------------
  // 开发服务器配置
  // ----------------------------------------------------------------------
  server: {
    port: 5173,

    // ★ 跨域代理 —— 前后端分离的第一个关键点
    //
    // 前端跑在 localhost:5173，后端跑在 localhost:8080，
    // 浏览器出于同源策略，默认禁止 5173 的页面去请求 8080
    // （协议、域名、端口三者有一个不同就算跨域）。
    //
    // 这里的配置让开发服务器充当「中间人」：
    //   浏览器请求 http://localhost:5173/api/health/ping
    //     ↓ Vite 开发服务器截获（因为它同样在 5173，不跨域）
    //     ↓ 转发到 http://localhost:8080/api/health/ping
    //     ↓ 拿到结果原样返回给浏览器
    //
    // 浏览器视角里，请求从头到尾都是发给 5173 的，压根没跨域。
    //
    // 注意：这只在开发环境有效。生产环境由 Nginx 做同样的转发，
    //       所以业务代码里的请求路径可以一直写成 /api/xxx，两种环境都不用改。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true, // 把请求头里的 Host 改成目标地址，部分后端会校验这个
      },

      // ------------------------------------------------------------------
      // ★ 里程碑 11 新增：商品图片由【后端】提供，所以也要转发
      // ------------------------------------------------------------------
      //
      // 在这之前，两个前端工程从不转发 /api 以外的东西 ——
      // 图片是 mall-shop/public/images/ 下的静态文件，由 Vite 自己就发了，
      // 后端一行都不参与。
      //
      // 里程碑 11 起，上传的图存在 mall-server/uploads/ 下，
      // 由后端 WebMvcConfig 的 addResourceHandlers 映射到 /uploads/**。
      // 前端页面里的 <img src="/uploads/2026/09/xx.png"> 是发给
      // 【当前这个 dev server】的 → 必须转发给 8080，否则取不到。
      //
      // ⚠️⚠️ 漏配这条的失败方式非常隐蔽，值得单独写下来：
      //
      //   Vite 对「不存在的静态文件」不会返回 404，它会走 SPA fallback，
      //   返回 **200 + text/html**（就是 index.html 那段 HTML）。
      //   于是 <img> 拿到一段 HTML，解码失败，触发
      //   ProductImage.vue 里的 @error 兜底，页面上安安静静显示「暂无图片」。
      //
      //   没有 404、没有报错、控制台干干净净 ——
      //   排查的时候会一直去怀疑后端有没有把文件存下来，
      //   而真正的原因在这里。sql/test-upload.py 里有一条断言专门守它。
      //
      // 生产环境不需要这一段：Nginx 会把 /api 和 /uploads 一起托管，
      // 两个路径本来就是同一个后端。
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
