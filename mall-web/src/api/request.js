import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 全局唯一的 axios 实例。
 *
 * 为什么不直接 import axios 用？因为项目里每个请求都需要一些共同的约定：
 *   1. 统一的 baseURL（不用每次都写 /api）
 *   2. 统一超时时间
 *   3. 统一带上 token
 *   4. 统一判断业务 code，失败自动弹提示
 * 建一个实例配一次，全项目共用，避免在每个页面里重复写这些。
 */
const request = axios.create({
  // 所有请求都会自动拼上 /api 前缀，
  // 配合 vite.config.js 里的 proxy，最终打到后端的 http://localhost:8080/api/xxx
  baseURL: '/api',
  timeout: 10000,
})

/** localStorage 的 key，必须和 stores/user.js 里的一致 */
const TOKEN_KEY = 'mall_admin_token'

/**
 * 是否已经在跳转登录页的过程中。
 *
 * ★ 这个标志位是必需的，不是过度设计。
 *
 * 场景：首页同时发了 3 个请求，token 又刚好过期，
 * 那 3 个请求会【各自】收到 401，触发 3 次「清 token + 跳登录页」，
 * 用户看到 3 条重复的错误提示，还可能因为多次 location.href 赋值
 * 造成页面闪烁甚至跳转异常。
 *
 * 加上标志位后，只有第一个 401 真正执行跳转，后面的直接忽略。
 */
let redirecting = false

// ---------------------------------------------------------------------------
// 请求拦截器：请求发出去之前做的事
// ---------------------------------------------------------------------------
request.interceptors.request.use(
  (config) => {
    // 从 localStorage 读而不是从 Pinia store 读，是为了避免循环依赖：
    // store 依赖 api 层，api 层再依赖 store 就成环了。
    //
    // 反正 token 在两个地方的值一定相同（setToken 会同时写两边），
    // 这里读 localStorage 是等价且更简单的做法。
    const token = localStorage.getItem(TOKEN_KEY)
    if (token) {
      // 格式必须是 "Bearer <token>"，注意中间有个空格。
      // 后端拦截器是按这个前缀解析的，少了空格会认不出来
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// ---------------------------------------------------------------------------
// 响应拦截器：收到响应之后、业务代码拿到数据之前做的事
// ---------------------------------------------------------------------------
request.interceptors.response.use(
  /**
   * HTTP 状态码是 2xx 时进这里。
   *
   * 注意：HTTP 200 不代表业务成功！后端可能返回
   *   { "code": 1001, "message": "库存不足" }
   * 这时 HTTP 层是 200，但业务上是失败的，所以还要判断 code。
   */
  (response) => {
    const res = response.data

    // 后端返回的不是标准结构（比如文件流），直接透传
    if (res === null || typeof res !== 'object' || !('code' in res)) {
      return res
    }

    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      // 交给 Promise.reject，业务代码里的 catch 才能捕获到
      //
      // ⚠️ 注意这里 reject 出去的错误【没带业务码】，只有一句话。
      //    管理端目前没有任何地方需要区分业务码，所以先这样。
      //
      //    但如果你哪天需要「某个业务码做特殊处理」（比如 1004
      //    「分类下还有商品，不能删」时弹一个「去看看这些商品」的按钮），
      //    就会发现在这里拿不到 code，然后很可能顺手写成
      //    `e.message.includes('不能删除')` —— 那就埋了个雷：
      //    后端一改文案，这个逻辑就静默失效了。
      //
      //    正确做法见 mall-shop/src/api/request.js：给 error 挂上
      //    err.code / err.isBusinessError 再抛出去。
      //    （那边已经这么改了，因为用户端确实需要按码分支）
      return Promise.reject(new Error(res.message || '请求失败'))
    }

    // ★ 关键：只把 data 返回给业务代码。
    //   所以页面里写 const list = await getProductList() 拿到的直接就是列表，
    //   不需要每次写 res.data.data.code === 200 这种三重嵌套判断
    return res.data
  },

  /**
   * HTTP 状态码不是 2xx（401 / 404 / 500 / 超时 / 断网）时进这里。
   * 这时后端可能压根没返回我们约定的结构，所以单独处理。
   */
  (error) => {
    // ---------------------------------------------------------------------------
    // ★ 401：token 无效或过期，统一在这里处理，业务代码不用管
    // ---------------------------------------------------------------------------
    // 后端有两处会产生 401（见 GlobalExceptionHandler 的注释）：
    //   1. 拦截器发现没带 token / token 过期
    //   2. Service 发现账号被删了或被禁用了
    // 两处都返回真正的 HTTP 401，所以这里只需要判断一个地方。
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
          message = '服务器内部错误'
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
 * <h3>为什么用 window.location.href 跳转，而不是 router.push？</h3>
 *
 * <p>因为 request.js 引入 router 会造成<b>循环依赖</b>：
 * <pre>
 *   request.js → router/index.js → stores/user.js → api/auth.js → request.js
 * </pre>
 * 虽然 ES Module 能处理循环依赖（靠提升），但拿到的可能是不完整的模块对象，
 * 表现为「平时没事，某个特定加载顺序下突然报 undefined」——
 * 这类问题极难排查。能在设计上避开就别去试探。
 *
 * <p>而且整页跳转在这里<b>恰好是更正确的选择</b>：
 * <ul>
 *   <li>整页刷新会清空所有内存状态（Pinia store、组件里的临时数据），
 *       不会留下上一个用户的痕迹</li>
 *   <li>退出登录本来就该是一次「干净的重来」</li>
 * </ul>
 * 代价是丢失了 SPA 的无刷新体验，但登录页跳转一辈子也就发生几次，
 * 这点代价换来的是「绝不会串数据」，很划算。
 */
function handleUnauthorized() {
  if (redirecting) return
  redirecting = true

  localStorage.removeItem(TOKEN_KEY)

  // 已经在登录页了就不用再跳 —— 再跳就是给 /login 加个多余的 query，
  // 然后触发一次没必要的整页刷新。
  // 这里也不弹提示：用户本来就在登录页上，token 被清掉是预期结果。
  if (window.location.pathname === '/login') {
    redirecting = false
    return
  }

  ElMessage.error('登录已过期，请重新登录')

  // 记下当前地址，登录成功后跳回去。
  // 用 pathname + search 而不是完整 URL，是为了只取路径部分
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}

export default request
