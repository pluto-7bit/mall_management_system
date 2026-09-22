import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// Element Plus 的样式必须引，否则组件是没有样式的裸 DOM
import 'element-plus/dist/index.css'
// 自己的全局样式放最后，方便覆盖组件库的默认样式
import './style.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

/**
 * 全局注册 Element Plus 的所有图标组件，
 * 这样模板里可以直接写 <el-icon><Search /></el-icon>，不用逐个 import。
 * 代价是打包体积变大，生产项目通常按需引入，学习阶段先图省事。
 */
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

/**
 * 注册插件的顺序有讲究：
 *
 * 1. Pinia 必须排在 Router 前面 —— 因为路由守卫里很可能要用 store
 *    （比如判断有没有登录），Pinia 没装好，守卫里 useUserStore() 会报错。
 *
 * 2. ElementPlus 传 { locale: zhCn } 是为了把组件内置文案变成中文，
 *    否则分页器会显示 "Go to"、日期选择器是英文，看着别扭。
 */
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// mount 挂载到 index.html 里 id="app" 的那个 div 上
app.mount('#app')
