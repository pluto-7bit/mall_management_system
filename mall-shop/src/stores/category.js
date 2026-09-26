import { computed, ref } from 'vue'
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

  // -------------------------------------------------------------------------
  // ★★ 里程碑 16：两级分类。三个"导航与左栏共用"的判断。
  //
  //   ⚠️ 注意这里【没有一棵树】。
  //
  //   接口返回的是**扁平数组 + parentId**（不是嵌套的 children），
  //   而「两级」这件事用两个 filter 就够了 ——
  //   递归建树的代码在这里是纯粹的浪费，而且会多出两个形状
  //   （tree 和 list），然后有人拿 tree 去 find、有人拿 list 去 filter，
  //   分岔就是从那儿开始的。
  //
  //   刻意保持扁平的完整理由写在 `ShopCategoryController` 的注释里：
  //   商城页有【两个按名字/id 平铺查找分类】的地方（banner 的 find、
  //   左栏说明文案的 find），它们拿到树会**静默失效**。
  //   「形状由谁在用决定，不由数据长什么样决定。」
  // -------------------------------------------------------------------------

  /**
   * 一级分类（`parentId === 0`）。
   *
   * <p>★ 判据写成宽松真值 `!c.parentId`，而不是 `c.parentId === 0`：
   * 后端把它序列化成数字 0，两者都对，但宽松写法对
   * 「字段不存在」也成立（更旧的接口 / 缓存里的老数据）。
   * ⚠️ 这是个例外判断 —— 本项目其他地方**不能**这么写，
   * 因为 0 常常是合法值（见 ProductForm 里「毛利 0」那个坑）。
   * 这里能用，是因为 `parentId` 的 0 和"缺失"表达的是同一件事。
   */
  const roots = computed(() => list.value.filter((c) => !c.parentId))

  /**
   * 某个一级分类下的二级分类。
   *
   * <p>★ 是<b>函数</b>而不是 computed：它带参数，没法缓存。
   * 每次调用都遍历一遍扁平数组 —— 分类是十几个的量级，
   * 而模板里每个根调一次，总共十几次 filter，代价可以忽略。
   * （如果哪天分类到了几千个，这里要换成按 parentId 分好组的 Map，
   *   但那时真正的问题会是"为什么导航里有几千个分类"。）
   *
   * @param {number} id 一级分类的 id
   * @returns {Array} 该分类下的二级分类（没有就是空数组）
   */
  function childrenOf(id) {
    return list.value.filter((c) => c.parentId === id)
  }

  /**
   * ★★ 某个分类<b>这一支</b>是不是当前选中的。
   *
   * <h3>为什么需要它，而不是简单地 `activeId === id`</h3>
   *
   * <p>因为点了一个二级分类之后，它的<b>父分类也该亮</b>。
   * 否则用户看到的是「导航条上什么都没高亮，但列表确实被筛过了」——
   * 他会以为筛选坏了，或者以为自己点错了。
   *
   * <h3>★ 为什么 activeId 是参数，而不是在 store 里读 URL</h3>
   *
   * <p>这是这个文件开头那条边界的又一次应用：
   * <pre>
   *   分类列表 → 服务端数据 → store
   *   筛选条件 → 界面状态   → URL
   * </pre>
   * 让 store 自己去读 `route.query`，就是把「界面状态」搬进了 store ——
   * 那正是这个 store 存在的理由反过来打自己。而且 store 一旦依赖 router，
   * 它就再也没法在组件外面单独测了。
   *
   * <p><b>所以：判断的"实现"在 store 里（只有一份），
   * 判断的"输入"由调用方给（App 和 Home 各自从 URL 算出来，
   * 两边算的是同一个 `readCategoryId(route.query)`）。</b>
   * 这就同时做到了「只有一份实现」和「store 不碰路由」。
   *
   * <h3>它和 utils/query.js 那条原则的关系</h3>
   *
   * <p>query.js 开头写着「同一个语义判断只能有一个实现」。
   * 这条就是那个判断 —— 所以它必须是<b>一个函数</b>，
   * 而不是 App.vue 里写一遍、Home.vue 里再写一遍。
   * 两处各写一遍的后果很具体：导航条会亮、左栏不会亮（或反过来），
   * 而两者都是"对的"，只是对「选中了哪一支」的理解不一样。
   *
   * @param {number} id       要判断的分类 id
   * @param {number|null} activeId 当前选中的分类 id（来自 URL）
   */
  function isBranchActive(id, activeId) {
    if (activeId == null) {
      return false
    }
    if (activeId === id) {
      return true
    }
    // 两级封顶，所以"往上走一层"就够了 —— 不需要循环，
    // 也不可能出现更深的祖先（后端规则 4 挡着三级）。
    // ⚠️ 这一行依赖「最多两级」这个不变量。哪天后端放开三级，
    //    这里必须改成沿 parentId 往上走，而【不会有任何测试报警】——
    //    症状只是"选中三级分类时二级分类不亮"。
    return list.value.some((c) => c.id === activeId && c.parentId === id)
  }

  return { list, load, roots, childrenOf, isBranchActive }
})
