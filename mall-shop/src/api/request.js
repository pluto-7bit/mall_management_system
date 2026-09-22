import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 全局唯一的 axios 实例。
 *
 * <p>和管理端（mall-web）的 request.js 结构一致，但有三处必须不同：
 * <ol>
 *   <li><b>TOKEN_KEY</b> —— 用户端存 {@code mall_member_token}，
 *       管理端存 {@code mall_admin_token}</li>
 *   <li><b>401 的跳转</b> —— 都是跳登录页，但用户端的登录页是 {@code /login}，
 *       管理端也是 {@code /login}，恰好一样</li>
 *   <li><b>未登录时的措辞</b> —— 管理端说「登录已过期」，
 *       用户端更该说「请先登录」以外的客气话（下面有说明）</li>
 * </ol>
 *
 * <p><b>★ 为什么不把这两个 request.js 抽成公共包？</b>
 *
 * <p>现在是两个独立工程，抽公共包意味着要建一个私有 npm 包
 * 或者上 monorepo —— 那是为了「消除 60 行重复」付出的
 * 相当大的工程成本（构建配置、版本发布、两边升级同步）。
 *
 * <p>而且它们<b>会分岔</b>：用户端以后要加「购物车数量变化时
 * 全局刷新角标」这种完全是前台特有的逻辑，管理端永远不需要。
 * 到时候还是要拆开，那现在抽公共包就白做了。
 *
 * <p><b>「重复」和「耦合」之间要选一个的时候，先看份量。</b>
 * 60 行的重复，代价是两个文件各自演进；抽出公共包的代价是
 * 两个工程从此绑在一起。前者便宜得多。
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

/** localStorage 的 key，必须和 stores/user.js 里的一致 */
const TOKEN_KEY = 'mall_member_token'

/** 是否已经在跳转登录页的过程中（防止多个 401 触发多次跳转） */
let redirecting = false

// ---------------------------------------------------------------------------
// 请求拦截器
// ---------------------------------------------------------------------------
request.interceptors.request.use(
  (config) => {
    // 从 localStorage 读而不是从 Pinia store 读，避免循环依赖。
    // 详见 mall-web/src/api/request.js 里同样位置的注释
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// ---------------------------------------------------------------------------
// 响应拦截器
// ---------------------------------------------------------------------------
request.interceptors.response.use(
  (response) => {
    const res = response.data

    // 非标准结构（文件流等）直接透传
    if (res === null || typeof res !== 'object' || !('code' in res)) {
      return res
    }

    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')

      // ★ 抛出的错误对象要带上【业务码】，不能只带一句话。
      //
      //   以前这里抛的是裸的 new Error(message)，于是业务码在拦截器里
      //   就被丢掉了 —— 调用方只知道「失败了」，不知道「为什么失败」。
      //
      //   这造成过一个真实的 bug：ProductDetail.vue 需要区分
      //     「商品不存在或已下架」→ 正常情况，显示友好的空状态
      //     「网络/服务端出错」    → 故障，显示重试按钮
      //   但两种情况下它拿到的都是一个光秃秃的 message，
      //   只好靠 err.response 是否存在来猜。而业务错误的
      //   error 上【根本没有】response 属性（它压根不是 axios 抛的），
      //   结果「商品已下架」被显示成了「加载失败，请稍后重试」——
      //   把一件正常的事说成了故障，正好是该区分开的那两种情况的其中一种。
      //
      //   现在挂三个字段，调用方按需取：
      //     err.code            业务码（1003 不存在、1008 数量超限…）
      //     err.isBusinessError 一眼看出「后端答复了，只是业务上不行」
      //     err.response        保留原响应，让习惯看 err.response 的代码也能工作
      //
      //   ★ 一句话总结：**拦截器把错误「统一处理」的时候，
      //     不要把调用方可能需要的原始信息吃掉。**
      const err = new Error(res.message || '请求失败')
      err.code = res.code
      err.isBusinessError = true
      err.response = response
      return Promise.reject(err)
    }

    // 只把 data 返回给业务代码
    return res.data
  },

  (error) => {
    if (error.response?.status === 401) {
      handleUnauthorized()
      return Promise.reject(error)
    }

    let message = '网络异常，请稍后重试'

    if (error.code === 'ECONNABORTED') {
      message = '请求超时'
    } else if (error.response) {
      switch (error.response.status) {
        case 403:
          message = '没有权限访问'
          break
        case 404:
          message = '请求的接口不存在'
          break
        case 500:
          message = '服务器开小差了，请稍后重试'
          break
        default:
          message = `请求失败（${error.response.status}）`
      }
    }

    ElMessage.error(message)
    return Promise.reject(error)
  },
)

/**
 * 处理未登录 / 登录过期。
 *
 * <p>用 {@code window.location.href} 而不是 {@code router.push} 的理由
 * 和管理端一样：避免 request.js → router → stores → api → request.js
 * 的循环依赖，而且整页刷新能干净地清空所有内存状态。
 *
 * <p><b>★ 这里有一个用户端特有的、值得想清楚的问题：是不是所有 401
 * 都该跳登录页？</b>
 *
 * <p>管理端可以粗暴地「401 就踢回登录页」—— 后台就没有匿名能干的事。
 * 但用户端不一样：<b>浏览商品是允许匿名访问的</b>
 * （里程碑 6 会把商品查询接口开放给游客）。
 *
 * <p>那如果一个公开页面上的接口返回了 401 呢？本项目的处理是
 * <b>照样跳登录页</b>，理由是：能收到 401 就说明服务端认为
 * 「这个接口需要登录」，那把这个请求的发起人送去登录页是合理的，
 * 而且我们带着 {@code redirect} 参数，登录完会把他送回来。
 *
 * <p>代价是：如果哪天有个本该公开的接口被误配成了需要登录，
 * 表现会是「游客一打开首页就被弹去登录页」——
 * 一个挺让人困惑的现象。<b>所以里程碑 6 配置 WebMvcConfig 的
 * excludePathPatterns 时要格外小心</b>，那是这个问题的源头。
 *
 * <p>另一种做法是「只有当前页面本身需要登录才跳转」
 * （判断路由的 meta），但那需要 request.js 能读到路由信息，
 * 而路由又依赖 store、store 依赖 api 层 —— 循环依赖就回来了。
 * 为了这点体验引入一个循环依赖不划算，所以选择接受上面那个代价。
 */
function handleUnauthorized() {
  if (redirecting) return
  redirecting = true

  localStorage.removeItem(TOKEN_KEY)

  if (window.location.pathname === '/login') {
    // 已经在登录页了，不用再跳，也不弹提示 ——
    // 用户本来就打算登录，密码错了是预期内的结果
    redirecting = false
    return
  }

  ElMessage.error('请先登录')

  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}

export default request
