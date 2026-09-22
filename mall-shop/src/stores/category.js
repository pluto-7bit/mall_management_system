import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getShopCategories } from '@/api/category'

/**
 * 分类列表的<b>全局</b>缓存。
 *
 * <h3>★ 为什么分类要进 store，而「筛选条件」坚决留在 URL？</h3>
 *
 * <p>这两个东西看起来都是「和分类有关的界面数据」，但性质完全不同：
 *
 * <pre>
 *   分类列表   → 【服务端数据】。全局一份，这一轮会话里读了就不会变  → store
 *   筛选条件   → 【界面状态】。用户点出来的，需要能被刷新、被分享  → URL
 * </pre>
 *
 * <p>放错任何一边都会坏：
 * <ul>
 *   <li>把服务端数据塞进 URL —— URL 会变得又长又丑，
 *       而且「分类列表」根本不是用户的状态，刷新后重新拉一次就行。</li>
 *   <li>把界面状态塞进 store —— 按 F5 筛选就丢了，
 *       用户也没法把「手机数码第 3 页」这个链接发给别人。</li>
 * </ul>
 *
 * <p>判断标准就是这一条：<b>「这是服务器告诉我的，还是用户点出来的？」</b>
 * 和 {@code cart.js} 里「件数放 store、内容放页面」是同一个判断。
 *
 * <h3>★ 为什么现在才抽出来？</h3>
 *
 * <p>里程碑 8 之前只有首页在用分类，放 {@code Home.vue} 里天经地义。
 * 但这一轮要给头部加一条<b>分类导航条</b> —— 于是 App 和 Home
 * 会同时需要这份数据，各拉一次就是每次进首页多发一个一模一样的请求。
 * <b>「出现第二个使用方」才是抽取的时机</b>，
 * 提前抽是为了「以后可能会复用」，那通常只是多一层间接。
 *
 * <h3>⚠️ 诚实的代价：这是一个行为变化</h3>
 *
 * <p>原来每次进首页都会重新拉一次分类；现在变成<b>每次加载页面只拉一次</b>。
 * 后果是：在管理后台新加了一个分类，用户端要刷新页面才看得到。
 *
 * <p>这几乎肯定是想要的（分类极少变，为此每次都发请求不值得），
 * 但它确实不是「纯粹的优化」—— 所以写在这里，而不是假装没有代价。
 */
export const useCategoryStore = defineStore('category', () => {
  /** 分类列表。空数组 = 还没加载 或 加载失败，两者用 loaded 区分 */
  const list = ref([])

  /**
   * 是否已经成功加载过。
   *
   * ★ 只有【成功】才置 true。失败时保持 false，
   *   这样下一次调用会重试 —— 网络抖一下不该让分类永久消失。
   */
  let loaded = false

  /**
   * 正在进行的那个请求的 Promise。
   *
   * ★★ <b>这个变量是这个 store 里唯一有点绕的地方，也是它必须存在的原因。</b>
   *
   * <p>只靠 {@code loaded} 一个布尔值是<b>挡不住并发的</b>：
   * <pre>
   *   App.vue  的 onMounted  ─┐
   *                           ├─ 几乎同时执行 → 两边都看到 loaded === false
   *   Home.vue 的 onMounted  ─┘               → 两边都发请求
   * </pre>
   *
   * <p>因为 {@code await} 之后才轮到对方执行，而对方看到的
   * <b>还是那个还没被改的 false</b>。这类「检查过了，但检查完到使用之间
   * 状态变了」的问题，靠加一个锁标志位是解决不了的 ——
   * 要解决的是<b>「第二次调用不该发请求，而该等第一次的结果」</b>，
   * 所以存的必须是 Promise 本身。
   *
   * <p>这个模式叫<b>单飞（single-flight）</b>：
   * 同一个请求在飞行途中被调用多次，只有第一次真的发出去，
   * 其余的搭同一趟车。写缓存的时候想不到这一点，
   * 表现就是「明明加了缓存，Network 里还是两个请求」——
   * 而且大概率不会有人发现，因为它不快也不慢，只是白费一次请求。
   */
  let pending = null

  /**
   * 加载分类列表（幂等，可以随便调）。
   *
   * @returns {Promise<Array>} 分类数组；失败时是空数组
   */
  async function load() {
    if (loaded) {
      return list.value
    }
    if (!pending) {
      pending = getShopCategories()
        .then((data) => {
          list.value = data || []
          loaded = true // ★ 只有成功才标记
        })
        .catch(() => {
          // ★ 静默降级，和原来 Home.vue 里的做法一致。
          //
          //   分类加载失败不该让整个页面挂掉 —— 商品还是能看的，
          //   只是筛不了。所以【不弹提示】（request.js 已经弹过一次了）、
          //   也【不设 loadFailed】。
          //
          //   ⚠️ 但仍然要把 list 置空：留着上一次的数据会让用户
          //     看到一份「看起来正常但已经过期」的分类列表，
          //     比明确显示「没有分类」更糟。
          list.value = []
        })
        .finally(() => {
          // ★ 无论成功失败都要清掉，否则一次失败会让 pending
          //   永远停在一个已完成的 Promise 上，后面所有调用
          //   都会「搭上那趟已经到站的车」，永远不会重试。
          pending = null
        })
    }
    await pending
    return list.value
  }

  return { list, load }
})
