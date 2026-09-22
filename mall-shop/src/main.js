import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

/**
 * ★ 这三行 CSS 的【顺序是功能性的，不是排版习惯】。
 *
 * Element Plus 把色阶（--el-color-primary 那一整套）定义在 `:root` 上，
 * 特异性是 0,1,0。theme.css 也在 `:root` 上定义 —— 特异性打平，
 * 于是只剩【源码顺序】能分胜负：后导入的赢。
 *
 * 所以 theme.css 必须在最后。写反了整页还是 EP 的蓝，
 * 而且不会报任何错、控制台里也没有任何提示。
 */
import 'element-plus/dist/index.css'
import './style.css'
import './theme.css'

import App from './App.vue'
import router from './router'

/**
 * 用户端的入口文件。
 *
 * <p>和管理端的 main.js 几乎一模一样，这不是疏漏 ——
 * 两个工程的启动流程本来就该一样（装 Pinia、装 Router、装组件库），
 * 强行抽出差异只会让两边都变难读。
 */
const app = createApp(App)

for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

/**
 * 注册插件的顺序有讲究：
 *
 * 1. Pinia 必须排在 Router 前面 —— 路由守卫里要用 store 判断登录状态，
 *    Pinia 没装好，守卫里 useUserStore() 会直接报错。
 *
 * 2. ElementPlus 传 { locale: zhCn } 把组件内置文案变成中文。
 *    用户端尤其明显：分页器、空状态提示、确认弹窗的按钮全是英文的话，
 *    顾客会觉得这网站没做完。
 */
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
