import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi, register as registerApi, getCurrentMember } from '@/api/auth'
import { useCartStore } from '@/stores/cart'

/**
 * 用户端登录状态。
 *
 * <p>结构和管理端的 store 几乎一样。这是一个<b>可以有意的重复</b>：
 * 两个工程是独立的 npm 包，本来就没法共用，而且它们会分岔
 * （用户端以后要加购物车数量、浏览历史这些前台特有状态）。
 */

/**
 * localStorage 的 key。
 *
 * ⚠️ 必须和 {@code api/request.js} 里的 TOKEN_KEY 一致，否则会出现
 * 「登录成功了但下一个请求还是 401」这种极难查的问题。
 *
 * <p>顺带一提：管理端用的是 {@code mall_admin_token}。
 * 两个前端跑在不同端口（5173 / 5174），而 localStorage 按
 * 「域名 + 端口」隔离，所以它们本来就互不干扰。
 * 但 key 还是起得区分开 —— 万一以后部署到同一域名下的不同路径，
 * 就不会打架了。<b>依赖「运行环境的隔离」不如依赖「命名上的隔离」</b>，
 * 因为前者会在部署方式一变就失效，而后者不会。
 */
const TOKEN_KEY = 'mall_member_token'

export const useUserStore = defineStore('user', () => {
  // ---------------------------------------------------------------------------
  // state
  // ---------------------------------------------------------------------------

  /** token 的初始值从 localStorage 读 —— 这是「刷新不掉登录」的关键 */
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')

  /**
   * 当前会员信息，初始为 null。
   *
   * <p>不从 localStorage 读，因为它会变（改了昵称、被禁用了）。
   * 只持久化 token 这个「凭证」，其他信息每次刷新重新问服务端要。
   */
  const member = ref(null)

  // ---------------------------------------------------------------------------
  // getters
  // ---------------------------------------------------------------------------

  const isLoggedIn = computed(() => !!token.value)

  /**
   * 要显示的名字。
   *
   * <p>★ 注意这里比管理端多一层兜底：注册时昵称是<b>选填</b>的，
   * 所以 {@code member.nickname} 很可能是 null。
   * 这时候显示账号，总比显示一片空白好 ——
   * 页面上出现一个没有名字的头像，用户会怀疑自己是不是没登录上。
   */
  const displayName = computed(
    () => member.value?.nickname || member.value?.username || '',
  )

  // ---------------------------------------------------------------------------
  // actions
  // ---------------------------------------------------------------------------

  /** 保存 token，同时写内存和 localStorage，保证两边一致 */
  function setToken(value) {
    token.value = value || ''
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  /**
   * 把接口返回的登录信息写进 store。
   *
   * <p>抽出来是因为<b>登录和注册返回的结构完全一样</b>
   * （都是一个 LoginVO：token + id + username + nickname），
   * 所以这两个流程的后续处理是同一段代码。
   * 写两遍的话，哪天返回值多了个字段（比如头像），
   * 就很可能只改了一处。
   */
  function applyLoginResult(data) {
    setToken(data.token)
    member.value = {
      id: data.id,
      username: data.username,
      nickname: data.nickname,
    }
    return data
  }

  /** 登录 */
  async function login(form) {
    // 失败时抛异常让调用方处理 —— 不要在这里 try-catch 吞掉，
    // 调用方需要知道失败了才能不关弹窗/不跳转
    return applyLoginResult(await loginApi(form))
  }

  /** 注册（注册成功后直接就是已登录状态） */
  async function register(form) {
    return applyLoginResult(await registerApi(form))
  }

  /**
   * 拉取当前会员信息（刷新页面后恢复用）。
   *
   * <p>失败时不清登录状态 —— 失败可能是网络抖动，
   * 不一定是 token 失效。真正的 401 由请求拦截器统一处理。
   */
  async function loadCurrentMember() {
    member.value = await getCurrentMember()
    return member.value
  }

  /**
   * 退出登录。
   *
   * <p>没有调任何接口 —— JWT 是无状态的，服务端没有「登录状态」可清。
   * 把本地的 token 删掉，之后请求就不带 Authorization 头了。
   *
   * <h3>★ 里程碑 7 补充：退出登录时必须一起清购物车的 store</h3>
   *
   * <p>购物车数据存在 Redis 里、按会员 id 归属。
   * 退出登录时<b>服务端那些数据不该被删</b> ——
   * 用户换台设备登录、或者过两天回来，购物车还应该在。
   *
   * <p>但<b>前端 store 里缓存的件数必须清掉</b>。
   * 因为 store 是全局单例，它不会随页面切换而销毁 ——
   * 不清的话，下一个在这台电脑上登录的人（宿舍、网吧、家里的共用电脑）
   * 会看到上一个人的购物车角标。
   *
   * <p>★ 这个坑的可怕之处在于<b>它不会报错</b>：
   * 角标上的数字看起来完全正常，只是数字属于另一个人。
   * 没有任何异常、没有任何日志，只有用户偶然发现
   * 「我购物车里怎么有东西？」
   *
   * <p>本章开头的注释预判了这个位置，现在兑现了它 ——
   * <b>写代码时留下「这里以后要注意什么」的注释，
   * 比事后回忆「当时是不是漏了什么」便宜得多。</b>
   *
   * <p>顺带一提，这里用的是 {@code useCartStore()} 而不是在文件顶部
   * {@code import} 后就在模块作用域调用 —— Pinia 的 store
   * 必须在 {@code app.use(pinia)} 之后才能实例化，
   * 模块顶层调用会报 "getActivePinia was called with no active Pinia"。
   * <b>在函数内部调用就没这个问题</b>，因为那时应用早就启动了。
   */
  function logout() {
    setToken('')
    member.value = null
    useCartStore().reset()
  }

  return {
    token,
    member,
    isLoggedIn,
    displayName,
    setToken,
    login,
    register,
    loadCurrentMember,
    logout,
  }
})
