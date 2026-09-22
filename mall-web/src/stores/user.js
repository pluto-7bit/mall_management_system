import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi, getCurrentAdmin } from '@/api/auth'

/**
 * 登录用户状态。
 *
 * <h3>为什么用 Pinia 而不是直接存 localStorage</h3>
 *
 * 因为 localStorage 有两个问题：
 *   1. 它【不是响应式的】—— 改了它，页面不会自动更新。
 *      想在导航栏显示用户名，就得手动触发刷新
 *   2. 它只能存字符串，对象要自己 JSON.stringify / parse
 *
 * Pinia 的 ref 是响应式的，改一下用户名，导航栏立刻变。
 * localStorage 只用来做「持久化」—— 存 token，
 * 这样用户刷新页面甚至关掉浏览器再打开，登录状态还在。
 *
 * <h3>这个 store 是「setup 风格」的</h3>
 *
 * Pinia 支持两种写法：
 *   - 选项式：defineStore('user', { state, getters, actions })
 *   - setup 风格：defineStore('user', () => { ... })
 *
 * 这里用 setup 风格，因为它和组件的 <script setup> 写法完全一致 ——
 * ref 就是 ref，computed 就是 computed，不用在两套心智模型间切换。
 * 官方现在也推荐这种。
 */

/**
 * localStorage 的 key。
 *
 * 管理端用 `mall_admin_token`，等做用户端（mall-shop）时会用
 * `mall_member_token`。
 *
 * 顺带一提：两个前端跑在不同端口（5173 / 5174），
 * 而 localStorage 是【按域名+端口隔离】的，
 * 所以它们本来就互不干扰。但 key 还是起得区分开更好 ——
 * 万一以后部署到同一个域名下的不同路径，也不会打架。
 */
const TOKEN_KEY = 'mall_admin_token'

export const useUserStore = defineStore('user', () => {
  // ---------------------------------------------------------------------------
  // state
  // ---------------------------------------------------------------------------

  /**
   * token 的初始值从 localStorage 读。
   *
   * ★ 这一步是「保持登录状态」的关键：
   *   ref 的初始值只在应用启动时算一次，
   *   所以刷新页面后会从这里把之前存的 token 读回来，
   *   而不是每次都从空字符串开始（那样一刷新就掉登录）。
   */
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')

  /**
   * 当前管理员信息。初始为 null。
   *
   * 为什么不也从 localStorage 读？因为它是会被改动的数据
   * （改了昵称、被禁用了），存下来就会和数据库不一致。
   * 只存 token 这个「凭证」，其他信息每次刷新都重新问服务端要，
   * 保证是最新的。见 loadCurrentAdmin()。
   */
  const admin = ref(null)

  // ---------------------------------------------------------------------------
  // getters
  // ---------------------------------------------------------------------------

  /** 有没有登录 —— 用 token 判断，不需要调接口 */
  const isLoggedIn = computed(() => !!token.value)

  /** 要显示的名字：优先昵称，没有就显示账号 */
  const displayName = computed(() => admin.value?.nickname || admin.value?.username || '')

  // ---------------------------------------------------------------------------
  // actions
  // ---------------------------------------------------------------------------

  /**
   * 保存 token —— 同时写内存和 localStorage，两边必须保持一致。
   *
   * 抽成一个函数而不是在两处分别写，就是为了保证「内存改了但没存」
   * 或者「存了但内存没改」这类不一致不会发生。
   */
  function setToken(value) {
    token.value = value || ''
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  /** 登录 */
  async function login(form) {
    // 注意：失败时这里会抛异常，由调用方 catch。
    // 不做 try-catch 吞掉，是因为「登录失败了」这件事
    // 调用方需要知道 —— 比如让它不要把弹窗关掉
    const data = await loginApi(form)

    setToken(data.token)
    admin.value = {
      id: data.id,
      username: data.username,
      nickname: data.nickname,
    }
    return data
  }

  /**
   * 拉取当前管理员信息（刷新页面后恢复用）。
   *
   * 失败时【不清除登录状态】，因为失败原因可能是网络抖动，
   * 不一定是 token 失效。真正的 401 会由请求拦截器统一处理
   * （清 token + 跳登录页），不需要在这里重复判断。
   */
  async function loadCurrentAdmin() {
    admin.value = await getCurrentAdmin()
    return admin.value
  }

  /**
   * 退出登录。
   *
   * ★ 注意这里【没有调任何接口】。
   *
   * JWT 是无状态的，服务端没有「登录状态」可以清除。
   * 退出登录就是把本地（内存 + localStorage）的 token 删掉，
   * 之后请求就不带 Authorization 头了，服务端自然把你当未登录。
   *
   * 代价是：如果 token 被攻击者复制走了，在它过期之前照样能用。
   * 「立刻踢下线」需要服务端维护 token 黑名单 —— 那是拿无状态换来的。
   */
  function logout() {
    setToken('')
    admin.value = null
  }

  return {
    token,
    admin,
    isLoggedIn,
    displayName,
    setToken,
    login,
    loadCurrentAdmin,
    logout,
  }
})
