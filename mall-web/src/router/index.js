import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由表：URL 路径 -> 显示哪个页面组件。
 *
 * component 用动态 import（() => import(...)）而不是顶部静态 import，
 * 这样 Vite 会把每个页面单独打包，访问到才加载，叫「路由懒加载」。
 * 页面一多，不做懒加载的话首屏要下载所有页面的代码，会明显变慢。
 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    // ★ meta.public 表示「这个页面不需要登录」。
    //   路由守卫正是靠这个标记来决定放不放行的 ——
    //   见下面 router.beforeEach 的注释
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    redirect: '/home',
  },
  {
    path: '/home',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    meta: { title: '首页' },
  },
  {
    path: '/product',
    name: 'ProductList',
    component: () => import('@/views/product/List.vue'),
    meta: { title: '商品管理' },
  },
  {
    path: '/category',
    name: 'CategoryList',
    component: () => import('@/views/category/List.vue'),
    meta: { title: '分类管理' },
  },

  {
    // 订单管理。★ 里程碑 10 之前这一行是注释掉的占位，
    //   现在它的组件真的存在了（`@/views/order/List.vue`）。
    //
    // ⚠️⚠️ 路径是【单数】`/order`，不是 `/orders`。
    //   用户端（mall-shop）那个「我的订单」页是复数的 `/orders` ——
    //   两个工程里长得几乎一样的两个路径，含义完全不同：
    //     mall-web   /order   管理员看【所有人】的订单，能发货
    //     mall-shop  /orders  会员看【自己】的订单，能确认收货
    //   所以这里不能"顺手改成复数以保持一致" —— 它们本来就不是同一个页面。
    //
    //   而且这个字符串必须和 App.vue 里那个 <el-menu-item index="/order">
    //   【逐字相同】：菜单是硬编码的，点击靠 index 跳转、
    //   高亮靠 activeMenu = route.path 比对。
    //   差一个字母的症状是「点进去 404，而且菜单永远不高亮」。
    path: '/order',
    name: 'OrderList',
    component: () => import('@/views/order/List.vue'),
    meta: { title: '订单管理' },
  },
  {
    // 商品评价。★ 里程碑 12 加的，是这条流水线上最后一块功能。
    //
    // ⚠️ 路径是【单数】`/review`，和用户端没有对应页面 ——
    //   会员看自己的评价是在「商品详情页」看的，
    //   那边没有（也不需要）一个「我的评价」页。
    //   所以这一条没有 /order vs /orders 那种容易搞混的对照。
    //
    // ★ 和上面那条一样的硬要求：路径字符串必须和
    //   App.vue 里 <el-menu-item index="/review"> 【逐字相同】。
    //   差一个字母的症状是「点进去 404，而且菜单永远不高亮」。
    path: '/review',
    name: 'ReviewList',
    component: () => import('@/views/review/List.vue'),
    meta: { title: '商品评价' },
  },
  // ↓↓↓ 后续里程碑会陆续加到这里 ↓↓↓
  // { path: '/member', name: 'MemberList', component: () => import('@/views/member/List.vue'), meta: { title: '会员管理' } },

  // 兜底路由：以上都没匹配上时显示 404。
  // 必须放在最后 —— 路由是按顺序匹配的，放前面会把所有请求都拦截掉
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  // createWebHistory 是 HTML5 History 模式，URL 长这样：/product
  // 另一种是 createWebHashHistory，URL 长这样：/#/product
  // History 模式更好看，但需要服务端配合（Nginx 里配 try_files 兜底），
  // 否则用户刷新 /product 页面会 404。Vite 开发服务器已经帮我们处理了
  history: createWebHistory(),
  routes,
})

/**
 * 全局前置守卫：每次路由跳转前都会执行。
 *
 * ★ vue-router 5 用的是「返回值」风格，不是老的「回调 + next()」：
 *     return                 → 放行（什么都不返回也是放行）
 *     return false           → 取消本次导航，停在原页面
 *     return '/login'        → 重定向到别的路径
 *     return { path, query } → 重定向到别的路径，可以带参数
 *   旧的 next() 写法在 v5 里已废弃，虽然还能跑但会刷警告。
 *
 * <h3>这里做两件事</h3>
 * 1. 改浏览器标签页标题
 * 2. 登录校验
 *
 * <h3>★ 前端路由守卫不是安全措施</h3>
 *
 * 它只是<b>体验优化</b>：让未登录的用户直接看到登录页，
 * 而不是先看到页面骨架再被一堆报错刷屏。
 *
 * <b>它挡不住任何真正的攻击。</b>用户可以打开 F12 直接把
 * localStorage 里的假 token 塞进去，或者干脆绕过前端直接调接口。
 * 真正拦住未授权访问的是<b>后端的拦截器</b>。
 *
 * 一个常见的误解是「前端已经拦住了，后端就不用校验了」——
 * 那样的话，任何人用 Postman 就能读写你的全部数据。
 * 记住：<b>凡是前端做的校验，都只是为了用户体验。</b>
 */
router.beforeEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 商城管理系统` : '商城管理系统'

  // ★ 必须在守卫【函数内部】调用 useUserStore()，不能在模块顶层。
  //   因为模块顶层执行时 Pinia 还没被 app.use() 装好，
  //   此时调用会报 "getActivePinia() was called but there was no active Pinia"。
  const userStore = useUserStore()

  // 公开页面（登录页）
  if (to.meta.public) {
    // 已经登录了还想去登录页 → 直接送进首页。
    // 不然用户手贱点了个「登录」链接，会看到登录页还以为自己掉线了
    if (userStore.isLoggedIn) {
      return '/home'
    }
    return
  }

  // 需要登录的页面，没登录就踢到登录页
  if (!userStore.isLoggedIn) {
    return {
      path: '/login',
      // ★ 把原本想去的地址记下来，登录成功后跳回去。
      //   没有这一步的话，用户访问 /product 被踢到登录页，
      //   登录完却落在首页，还得自己再点一次商品管理 —— 很烦
      query: { redirect: to.fullPath },
    }
  }

  // 走到这里说明有 token，放行。
  //
  // ⚠️ 注意这里【只检查有没有 token，不检查 token 是否有效】。
  //   判断有效性必须问服务端，而路由守卫是同步的，
  //   不能在这里发请求（会让每次跳转都卡一下）。
  //
  //   那过期的 token 怎么办？交给请求拦截器：
  //   token 过期后，页面一调接口就会收到 401，
  //   拦截器自动清 token 并跳回登录页。
  //   用户看到的是一次短暂的加载，然后回到登录页 —— 可以接受。
})

export default router
