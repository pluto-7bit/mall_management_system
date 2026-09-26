import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 路由表。
 *
 * <p><b>★ 注意这里和管理端有一个方向相反的设计：</b>
 * <pre>
 *   mall-web（管理端）：标注 meta.public 的是例外，其他都要登录 —— 默认封闭
 *   mall-shop（用户端）：标注 meta.requiresAuth 的才要登录，其他都开放 —— 默认开放
 * </pre>
 *
 * <p>为什么反过来？因为两个端的「常态」不同：
 * <ul>
 *   <li>后台是给员工用的，<b>没有一个页面是游客该看的</b>。
 *       所以默认封闭、把登录页标成例外，最安全</li>
 *   <li>电商前台是给顾客用的，<b>绝大多数页面游客都该能看</b> ——
 *       商品列表、商品详情、搜索、分类，全都不需要登录。
 *       只有「加入购物车、下单、我的订单」这些涉及个人数据的才需要。
 *       默认开放、把要保护的标出来，才不会出现「新加一个商品页面
 *       结果忘了标 public，游客一进来就被弹去登录页」的尴尬</li>
 * </ul>
 *
 * <p>这不是「哪个写法更规范」的问题，而是<b>哪种默认值更贴合这个端的性质</b>。
 * 安全设计里有个原则叫「默认安全」（fail-safe defaults），
 * 但它指的是<b>权限</b>该默认拒绝；至于「哪些页面需要权限」，
 * 要按业务实际来定，不能机械地一律默认封闭。
 *
 * <p>⚠️ 无论哪种写法，都要记住：<b>前端守卫永远只是体验优化，
 * 不是安全措施。</b>真正拦住未授权访问的是后端拦截器。
 */
const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/Home.vue'),
    meta: { title: '首页' },
  },
  {
    // 商品详情。游客也能看（后端把 /api/shop/products/** 开放了），
    // 所以这里【不加】requiresAuth
    //
    // ★ 参数名用 :id 是惯例。这个 id 会出现在 URL 里，
    //   所以详情页必须能优雅地处理「这个 id 不存在」——
    //   用户是可以随手把地址栏改成 /product/99999 的。
    //   详见 ProductDetail.vue 里 notFound 那段的注释
    path: '/product/:id',
    name: 'ProductDetail',
    component: () => import('@/views/ProductDetail.vue'),
    meta: { title: '商品详情' },
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册' },
  },

  // ↓↓↓ 需要登录的页面（里程碑 7、10 会填充真正的内容）↓↓↓
  {
    path: '/cart',
    name: 'Cart',
    component: () => import('@/views/Cart.vue'),
    meta: { title: '购物车', requiresAuth: true },
  },
  {
    // 确认订单页。
    //
    // ★ 只有【一个】路由，但服务两种下单来源，靠 query 参数区分：
    //     /checkout?ids=204,311             购物车结算
    //     /checkout?skuId=204&quantity=2    立即购买
    //
    //   ⚠️ 里程碑 15 阶段 4：这两组参数里的数字都从商品 id 换成了规格 id。
    //      参数名 {@code ids} 没改（它本来就是中性的），
    //      但 {@code productId} 换成了 {@code skuId}。
    //
    //   为什么不拆成两个页面？因为两个页面的界面几乎完全一样，
    //   差别只有"商品从哪来"和"提交时调哪个接口"两处。
    //   详见 Checkout.vue 的注释
    //
    //   ⚠️ 顺带一提：这个决定和路由守卫是配套的 ——
    //   一个页面只需要在【一处】标 requiresAuth，
    //   拆成两个就要标两次，而漏标的那次意味着一个能匿名访问的
    //   下单页（虽然后端会拦下来，但前端会先发一个注定 401 的请求）。
    //   **需要被保护的入口越集中，漏配的机会就越少。**
    path: '/checkout',
    name: 'Checkout',
    component: () => import('@/views/Checkout.vue'),
    meta: { title: '确认订单', requiresAuth: true },
  },
  {
    // 收货地址管理。
    //
    // ★ 它是独立的一页，而不是结算页里的一个弹窗 ——
    //   因为用户在完全没打算买东西的时候也会想维护地址簿。
    //   详见 Addresses.vue 的注释
    path: '/addresses',
    name: 'Addresses',
    component: () => import('@/views/Addresses.vue'),
    meta: { title: '收货地址', requiresAuth: true },
  },
  {
    // 收银台。
    //
    // ★ 做成【独立的一页】而不是订单旁边的一个按钮，理由见 Pay.vue 的注释。
    //   这里只说路由本身的一点：**订单号放在路径里，而不是 query 里**
    //   （对比 /checkout 用 ?ids=3,7 传参）。
    //   区别在于「这个参数是不是这个页面身份的一部分」：
    //     收银台如果没有订单号就【根本不存在】—— 它整个页面就是在讲那一单
    //     结算页没有 ids 也还成立 —— 它会自己去读购物车
    //   前者的参数属于路径，后者的属于查询条件。
    //
    //   ⚠️ 而且路径参数的改变会让 vue-router 视为【同一个组件】的复用，
    //   不会重新执行 onMounted（从 /pay/A 跳到 /pay/B 时）。
    //   当前没有页面内互相跳转的入口，所以不处理；
    //   但如果以后加了「订单列表 → 下一单」，要记得给 Pay.vue 补
    //   一个 watch(() => route.params.orderNo) 或者给 <router-view> 加 :key。
    //   **这是 vue-router 里最容易被忽略的一个坑：换参数不换组件。**
    path: '/pay/:orderNo',
    name: 'Pay',
    component: () => import('@/views/Pay.vue'),
    meta: { title: '收银台', requiresAuth: true },
  },
  {
    // 我的订单。★ 里程碑 10 之前这里指向 Placeholder.vue，
    //   现在换成真页面了（那个占位组件已经删掉 ——
    //   它是最后一个使用者，见 Placeholder.vue 原本的说明：
    //   「等到真页面做出来，把路由表的 component 换掉、删掉这个文件就行」）。
    //
    // ★ 这个页面把筛选条件放在 query 里（?status=1&pageNum=2），
    //   和首页同一个思路，理由见 Orders.vue 开头的说明。
    //
    // ⚠️ 因为没有路径参数，翻页/切 Tab 都【不走这个路由表】，
    //   只是 query 变化 —— vue-router 不会重新执行 onMounted，
    //   所以 Orders.vue 必须用 watch(route.query) 来响应。
    //   （这正是上面 Pay.vue 那条注释里警告的同一件事，
    //     只是那边是路径参数、这边是查询参数。）
    path: '/orders',
    name: 'Orders',
    component: () => import('@/views/Orders.vue'),
    meta: { title: '我的订单', requiresAuth: true },
  },

  {
    // 我的售后（里程碑 17）
    path: '/after-sales',
    name: 'AfterSales',
    component: () => import('@/views/AfterSales.vue'),
    meta: { title: '退款/售后', requiresAuth: true },
  },

  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,

  /**
   * 切换路由时滚动到页面顶部。
   *
   * <p>管理端不需要这个（表格和表单页面都很短），
   * 但用户端必须有：在商品列表滚到底部、点进详情页，
   * 如果还停在原来的滚动位置，用户会以为页面没跳转。
   *
   * <p>用 {@code behavior: 'instant'} 而不是默认的平滑滚动 ——
   * 切页面时的平滑滚动会让人有「页面在滑」的错觉，很头晕。
   * 平滑滚动适合「同一页内跳转到某个锚点」，不适合换页。
   */
  scrollBehavior(to, from, savedPosition) {
    // 浏览器前进/后退时，恢复用户离开时记下的位置 —— 这是符合直觉的
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0, behavior: 'instant' }
  },
})

router.beforeEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 商城` : '商城'

  // ★ 必须在守卫【函数内部】调用 useUserStore()，不能在模块顶层 ——
  //   模块顶层执行时 Pinia 还没被 app.use() 装好
  const userStore = useUserStore()

  // 需要登录的页面（购物车、我的订单），没登录就送去登录页
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    return {
      path: '/login',
      // 记下原本想去的地址，登录成功后跳回去
      query: { redirect: to.fullPath },
    }
  }

  // 已经登录了还想去登录/注册页 → 送回首页。
  // 不然用户点了「登录」链接会看到登录表单，以为自己掉线了
  if ((to.path === '/login' || to.path === '/register') && userStore.isLoggedIn) {
    return '/'
  }

  // 其余全部放行 —— 商品浏览不需要登录
})

export default router
