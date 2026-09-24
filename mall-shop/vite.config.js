import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      // 和 mall-web 保持一致的 @ 别名配置。
      // 两个工程各配一份是必要的 —— 它们是独立的 npm 包，
      // 不存在「共用一份配置」的可能（除非上 monorepo，那是另一回事）
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  // ----------------------------------------------------------------------
  // 开发服务器配置
  // ----------------------------------------------------------------------
  server: {
    // ★ 端口必须和 mall-web 错开（那边是 5173），否则第二个 npm run dev 起不来。
    //   Vite 发现端口被占用时会自动换成 5174、5175……但那是「碰运气」，
    //   显式写死才能保证「管理端永远是 5173、用户端永远是 5174」，
    //   不然你书签里的地址会在重启后失效。
    //
    //   ★ strictPort 才是关键：不加它的话，如果 5174 被占了，
    //     Vite 会默默换成 5175 然后正常启动 —— 你打开 5174 会看到
    //     另一个项目的页面，或者一个空白页，但控制台没有任何报错。
    //     这类问题能查半小时。加上它，端口冲突会直接启动失败并说明原因。
    strictPort: true,
    port: 5174,

    // 跨域代理：和 mall-web 完全一样。
    // 两个前端都往同一个后端（8080）发请求，所以两边都要配。
    //
    // 这也是「为什么两个前端工程各自配一份 proxy」的答案：
    // proxy 是【开发服务器】的行为，谁的服务器转发谁的请求，
    // 没法共用。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },

      // ★ 里程碑 11 新增：商品图片改由【后端】提供，所以也要转发。
      //
      // 在这之前用户端从不转发 /api 以外的东西，因为图片是
      // public/images/ 下的 105 个 SVG，Vite 自己就发了。
      // 现在上传的图在 mall-server/uploads/ 下，得转发给 8080。
      //
      // ⚠️⚠️ 漏配这条的失败方式是 **200 + text/html**，不是 404：
      //   Vite 对不存在的静态文件走 SPA fallback，返回 index.html。
      //   <img> 解码失败 → ProductImage.vue 的 @error → 安静显示「暂无图片」。
      //   没有报错、控制台干净，看起来就像「后端没存下文件」。
      //   sql/test-upload.py 里有一条断言同时守着 5173 和 5174 两个端口。
      //
      // ⚠️ 注意它【不会】影响老的 /images/*.svg：那条路径仍然由
      //   public/ 目录直接提供，压根不走 proxy（proxy 只看前缀匹配，
      //   而 '/images' 没有配）。两类图片来源不同，这是过渡期的现状。
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
