<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getShopProductPage } from '@/api/product'
import ProductImage from '@/components/ProductImage.vue'
import { useCartStore } from '@/stores/cart'
import { useCategoryStore } from '@/stores/category'
import { readCategoryId } from '@/utils/query'
import { useUserStore } from '@/stores/user'
import { formatAmount } from '@/utils/format'

/**
 * 用户端首页 = 商品列表页。
 *
 * <h3>★ 这一版的核心设计：把筛选条件放在 URL 里</h3>
 *
 * <p>关键词、分类、排序这些条件，我<b>没有</b>用普通的 ref 存，
 * 而是存在<b>路由的 query 里</b>。也就是说：
 * <pre>
 *   /                     → 全部商品
 *   /?categoryId=1        → 只看手机数码
 *   /?keyword=手机         → 搜索「手机」
 *   /?sort=price_asc      → 按价格从低到高
 * </pre>
 *
 * <p>这样做有三个好处，都是用户能直接感觉到的：
 * <ol>
 *   <li><b>刷新页面不丢筛选条件</b> —— 用 ref 存的话，F5 一下
 *       就回到「全部商品」，用户得重新点一遍</li>
 *   <li><b>浏览器后退键符合预期</b> —— 从详情页返回，还是原来的筛选结果。
 *       用 ref 存的话返回会回到初始状态，这是电商网站最招人烦的体验之一</li>
 *   <li><b>链接可以分享</b> —— 想看「5000 元以下的手机」？
 *       把 URL 发给别人就行</li>
 * </ol>
 *
 * <p>代价是<b>「同一份状态有了两个来源」</b>：
 * 路由的 query 和组件里的变量。如果两边都允许改，就会互相打架
 * （改 A 触发 B 更新，B 更新又触发 A —— 无限循环）。
 *
 * <p>所以这里定了一条规矩：<b>route.query 是唯一的事实来源。</b>
 * <pre>
 *   用户点筛选 → router.replace({ query })  ← 只改 URL
 *                                              ↓
 *   watch(route.query) 监听到变化 → 发请求    ← 只读 URL
 * </pre>
 * 组件里<b>不存</b>「当前分类是哪个」这种变量，
 * 需要时就地计算（见下面的 categoryId）。
 * <b>只有一条写入路径，就不会有循环。</b>
 *
 * <h3>★ 这一版改成了无限滚动，「页码」从 URL 里搬了出来</h3>
 *
 * <p>分页时代 {@code pageNum} 也是存在 URL 里的，因为那时它确实是
 * 一个「可分享、可后退」的条件 —— 第 3 页和第 5 页是两个不同的页面。
 *
 * <p>改成往下滑自动加载之后，这个前提没了：
 * <ul>
 *   <li>「加载到第几页」取决于<b>用户滚了多远</b>，而滚动距离又取决于
 *       屏幕高度、鼠标滚轮速度 —— 它根本不是一份可以分享的状态；</li>
 *   <li>把每次滚动都写进 URL（哪怕是 replace）会让 URL 疯狂变化，
 *       而且下一次 watch 会把它当成「筛选条件变了」再查一遍，
 *       变成<b>滑一下查一次全部数据</b>。</li>
 * </ul>
 *
 * <p>所以规矩精确化成：<b>URL 存「查什么」，组件存「查了多少」。</b>
 * 筛选条件（keyword / categoryId / sort）是「查什么」，留在 URL；
 * 页码是「查了多少」，收进本地的 {@code pageNum}。
 *
 * <p>这不是妥协 —— 京东、淘宝、亚马逊的无限滚动列表全都这样：
 * 刷新之后回到第一批数据，因为「你上次滚到第几屏」本来就不该被记住。
 * <b>一个状态该不该进 URL，判断标准是「它能不能被分享」，不是「它是不是状态」。</b>
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()
const categoryStore = useCategoryStore()

// ---------------------------------------------------------------------------
// 页面数据
// ---------------------------------------------------------------------------

/**
 * 分类改从 store 里读 —— 因为头部导航条（App.vue）也要用它。
 *
 * ★ 用 computed 包一层而不是直接把 store.list 拿来用：
 *   store 里解构出来的 ref 会丢响应性（Pinia 的 setup store 里
 *   `const { list } = useCategoryStore()` 拿到的是一个快照、
 *   不是一个会跟着变的引用）。用 computed 显式地把「读」这件事
 *   记在依赖里，是这个坑的标准解法。
 */
const categories = computed(() => categoryStore.list)

/** 每批拉多少条。6 列 × 4 行 —— 一屏差不多正好铺满 */
const PAGE_SIZE = 24

const products = ref([])
const total = ref(0)
const pageNum = ref(0)
/** 首屏/换筛选条件的加载（会盖住整个网格） */
const loading = ref(false)
/** 往下滑追加的加载（只在底部转圈，不清空已有内容） */
const loadingMore = ref(false)
/** 请求失败时把列表区换成空状态，而不是留一片空白让人以为在加载 */
const loadFailed = ref(false)
/** 服务端说到头了、但实际返回空页时的兜底，见 loadMore 的注释 */
const exhausted = ref(false)

/**
 * 还有没有更多数据。
 *
 * ★ 拿 {@code products.length} 和 {@code total} 比，而不是拿
 *   {@code pageNum * PAGE_SIZE} 比 —— 后者是「理论上该有多少」，
 *   前者是「手上真有多少」。万一服务端数据变了（有商品下架），
 *   两者会不一致，而<b>按手上真有的算才不会出现「一直加载但永远加载不完」</b>。
 */
const hasMore = computed(() => !exhausted.value && products.value.length < total.value)

// ---------------------------------------------------------------------------
// 从 URL 里读筛选条件（全部是 computed —— 只读，不存副本）
// ---------------------------------------------------------------------------

const keyword = computed(() => (route.query.keyword || '').toString())

/**
 * ★ 用 readCategoryId 而不是在这里自己解析 ——
 *   App.vue 的导航条要回答同一个问题（哪个分类高亮），
 *   两边必须用同一个判断，否则会出现「导航点亮了手机数码、
 *   网格却显示全部商品」这种不报错的对不上。
 *   详见 utils/query.js 开头。
 */
const categoryId = computed(() => readCategoryId(route.query))

const sort = computed(() => {
  const v = (route.query.sort || '').toString()
  // 白名单。URL 是人手改得出来的，所以前端也要防一手 ——
  // 后端虽然也会兜底，但前端先兜住能少发一次无意义的请求
  return ['price_asc', 'price_desc'].includes(v) ? v : 'default'
})

// ---------------------------------------------------------------------------
// 改筛选条件 = 改 URL
// ---------------------------------------------------------------------------

/**
 * 更新 URL 上的查询参数。
 *
 * <p>用 {@code router.replace} 而不是 {@code router.push} ——
 * 这个区别很关键：
 * <pre>
 *   push    → 往历史记录里【加一条】
 *   replace → 【替换】当前这条
 * </pre>
 * 如果切分类用 push，用户点了 10 个分类再按后退键，
 * 就得按 10 次才能回到最开始那个页面。这是很糟的体验。
 *
 * <p><b>规矩：筛选条件的变化用 replace，页面之间的跳转用 push。</b>
 *
 * <p>值为 null / 空串 / 默认值的参数会被删掉，让 URL 保持干净：
 * {@code /?sort=default} 和 {@code /} 是同一个页面，
 * 没必要留一长串没用的参数。
 */
function updateQuery(patch) {
  const next = { ...route.query, ...patch }

  // 排序的「默认值」是单词 "default"，不能混在下面那个通用清理里判断 ——
  // 否则用户搜「default」这个词时，关键词会被当成默认值删掉。
  // 这类「哨兵值和正常数据长得一样」的坑，只要出现就迟早会被踩到，
  // 所以要专门处理，别图省事用一条通用规则
  if (next.sort === 'default') {
    delete next.sort
  }

  Object.keys(next).forEach((k) => {
    const v = next[k]
    if (v === null || v === undefined || v === '') {
      delete next[k]
    }
  })

  router.replace({ query: next })
}

/**
 * 切分类。
 *
 * ★ 注意这是【切换】语义：点已经高亮的分类会取消选择。
 *
 * <p>这个行为在以前那排药丸按钮上很好用（再点一下 = 取消筛选），
 * 现在搬到左侧栏之后稍微有点意外 —— 左侧栏的直觉是「点了就选中」。
 *
 * <p>但它是<b>既有的、有测试覆盖的行为</b>，所以这里原样复用，
 * 不在改样式的时候顺手「修」它。要改就单独一个提交、单独一份说明。
 * 顺手改掉别人依赖的行为，是协作里最招人烦的一类改动。
 */
function selectCategory(id) {
  updateQuery({ categoryId: id === categoryId.value ? null : id })
}

function changeSort(value) {
  updateQuery({ sort: value })
}

function resetAll() {
  router.replace({ query: {} })
}

// ---------------------------------------------------------------------------
// 取数据
// ---------------------------------------------------------------------------

/**
 * 加载分类。
 *
 * ★ 这里【保留这次调用】，不依赖 App.vue 已经拉过：
 *   「App 的 onMounted 一定先于 Home 的 onMounted」这件事
 *   不是可以依赖的约定（子组件的 mounted 其实先于父组件触发）。
 *   而 store 里的 load() 是幂等的、还是单飞的，
 *   所以「多调一次」的代价是零 —— 不需要靠挂载顺序来省这一次。
 */
async function loadCategories() {
  await categoryStore.load()
}

/** 按当前筛选条件取第 n 页。两个加载入口共用，保证参数完全一致 */
function fetchPage(page) {
  // ★ 这里只传「有值」的参数。
  //   虽然 axios 会自动丢掉 undefined，但显式地不传更清楚 ——
  //   而且后端的关键词是【模糊匹配】，误传一个空串
  //   在有些数据库里会变成 LIKE '%%'（匹配所有），
  //   虽然本项目后端 normalize() 把空串转成了 null，
  //   但别把正确性寄托在后端的兜底上
  const params = { pageNum: page, pageSize: PAGE_SIZE }
  if (keyword.value) params.keyword = keyword.value
  if (categoryId.value) params.categoryId = categoryId.value
  if (sort.value !== 'default') params.sort = sort.value
  return getShopProductPage(params)
}

/**
 * ★ 请求序号 —— 解决无限滚动里最典型的一个竞态。
 *
 * <p>场景：用户在第 1 页上往下滑，第 2 页的请求发出去了还没回来，
 * 这时他点了「手机数码」。分类变化触发 reload，第 1 页（新分类）回来了、
 * 渲染完毕 —— <b>然后第 2 页（旧分类）的响应到了</b>，
 * 它会把旧分类的商品追加到新列表后面。
 *
 * <p>表现是：「切了分类，列表最下面混着几个上一个分类的商品」。
 * 它不报错，而且是偶发的（取决于两次请求谁先回来），
 * 属于最难复现的那类 bug。
 *
 * <p>解法：每次「重置」就把序号 +1。异步响应回来时对一下序号，
 * 对不上就说明<b>自己已经过期了</b>，直接丢弃。
 * 追加不清空序号（追加和它所属的那次重置是同一批）。
 */
let requestSeq = 0

/** 换筛选条件：清空重来，从第 1 页拉 */
async function reload() {
  const seq = ++requestSeq
  loading.value = true
  loadFailed.value = false
  exhausted.value = false

  try {
    const data = await fetchPage(1)
    if (seq !== requestSeq) return // 期间用户又改了条件，这次结果作废
    products.value = data.list || []
    total.value = data.total || 0
    pageNum.value = 1
    // 一屏可能装得下不止 24 件（大屏显示器），装得下就接着拉，
    // 否则用户会看到一个「没满但也没法再加载」的列表 ——
    // 滚动条根本不出来，自然也就触发不了下一次加载
    fillViewport()
  } catch {
    if (seq !== requestSeq) return
    // request.js 已经弹过错误提示了，这里只负责把界面切成空状态。
    // ★ 分工：拦截器管「告诉用户出错了」，组件管「界面长什么样」
    products.value = []
    total.value = 0
    pageNum.value = 0
    loadFailed.value = true
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/**
 * 往下滑：追加下一页。
 *
 * <p>★ 三个前置判断缺一不可：
 * <ul>
 *   <li>{@code loading} —— 重置加载还没完，这时候追加会追加到一份
 *       即将被整个替换掉的列表上，白做一次请求</li>
 *   <li>{@code loadingMore} —— 防止同一次滚动触发两次。
 *       IntersectionObserver 在快速滚动时可能连着回调两次</li>
 *   <li>{@code hasMore} —— 到底了就别再问了</li>
 * </ul>
 *
 * <p>它们不是「保险起见」加的，每一条都对应一个具体的坏结果。
 */
async function loadMore() {
  if (loading.value || loadingMore.value || !hasMore.value) return

  // ★ 注意这里是【读】不是 +1：追加请求和它所属的那次重置属于同一批，
  //   只有 reload 才有资格作废序号
  const seq = requestSeq
  loadingMore.value = true

  try {
    const data = await fetchPage(pageNum.value + 1)
    if (seq !== requestSeq) return
    const list = data.list || []
    products.value = products.value.concat(list)
    total.value = data.total || 0
    pageNum.value += 1

    // ★ 兜底：服务端说还有（total 大于已加载），但这一页返回了空数组。
    //   按 hasMore 的算法这时候还会继续请求下一页 —— 而下一页同样是空的，
    //   于是变成【无限请求风暴】，页面卡死、控制台刷屏。
    //   只要出现一次空页就认定到底了，宁可少加载也不要打着转。
    if (!list.length) exhausted.value = true

    // 拉回来的还没铺满一屏就继续拉，见 reload 里同样的调用
    fillViewport()
  } catch {
    // ★ 追加失败【不动已有内容】。用户手上那几十件商品是好的，
    //   不能因为第 3 页拿不到就把前两页也清掉 —— 那是「越加载越少」。
    //   提示已经由 request.js 弹过了，这里只负责停手。
    //   注意这里【不调用】fillViewport()：否则一失败就立刻重试，
    //   接口持续报错时就成了死循环重试。
  } finally {
    if (seq === requestSeq) loadingMore.value = false
  }
}

/**
 * 唯一的数据加载入口（重置那一路）：URL 一变就重新拉数据。
 *
 * <p>注意这里监听的是 {@code () => route.query} 而不是某个具体字段 ——
 * 任何一个筛选条件变了都要重查。
 *
 * <p>{@code immediate: true} 让它在组件首次挂载时也跑一次，
 * 所以不需要在 onMounted 里再单独调用一次 reload。
 * <b>少一处入口就少一处能被漏掉的地方。</b>
 *
 * <p>★ 这里【不会】被自己的滚动加载反复触发 ——
 *   因为 pageNum 已经不在 URL 里了（见文件开头的说明）。
 *   如果哪天有人把 pageNum 挪回 URL，这个 watch 就会变成
 *   「滑一下 → 写 URL → watch 触发 → 整个列表重查 → 回到第一批」，
 *   而且页面看起来只是「怎么老在闪」，不会报任何错。
 */
watch(
  () => route.query,
  () => {
    reload()
  },
  { immediate: true },
)

onMounted(() => {
  loadCategories()
})

// ---------------------------------------------------------------------------
// 无限滚动
// ---------------------------------------------------------------------------

/** 网格底部那个「哨兵」元素，它一进入视口就说明该加载下一页了 */
const sentinel = ref(null)

/**
 * 提前多少像素触发。200px 大约是一屏的 1/4，
 * 用户滑到底之前数据就已经在了，看起来是「无缝」的。
 */
const BOTTOM_OFFSET = 200

/**
 * ★ 用 IntersectionObserver，不用 `@scroll` + scrollTop 判断。
 *
 * <p>两件事它替我们做了：
 * <ol>
 *   <li><b>不用手动算位置</b>。scroll 事件里要读
 *       {@code document.documentElement.scrollHeight}、
 *       {@code scrollTop}、{@code clientHeight} 去凑一个判断，
 *       而这三个值在图片陆续加载出来时会不断变化，判断很容易失准
 *       （经典症状：滑到底了却不加载，或者还没滑就加载了）。
 *       IntersectionObserver 交给浏览器算，它知道真实布局。</li>
 *   <li><b>不阻塞滚动</b>。scroll 事件触发极其频繁，
 *       回调里只要碰到布局读取就会掉帧。IntersectionObserver
 *       是异步批量回调的，天然躲开这个问题。</li>
 * </ol>
 *
 * <p>观察动作放在 watch(sentinel) 里，而不是 onMounted 里 ——
 * 因为哨兵元素会被 v-if 换来换去（空状态、加载失败时它根本不存在），
 * 在 onMounted 里只观察一次的话，等元素真的出现时观察的还是那个
 * <b>已经被销毁的旧节点</b>，于是<b>永远不再触发</b>，
 * 而且没有任何报错。watch 一个模板 ref 能精确地在元素
 * 挂上/摘下的时刻做观察和取消观察。
 */
const observer = new IntersectionObserver(
  () => {
    // ★★ 注意这里【没有】判断 entries[0].isIntersecting，是故意的。
    //
    //   原本写的是 `if (entries[0].isIntersecting) loadMore()`，
    //   结果首页一加载就把 42 件商品全拉下来了 —— 一开始只显示 24 件。
    //
    //   原因：IntersectionObserver 回调里那个 isIntersecting 是
    //   【生成这条记录那一刻的快照】，而回调是作为任务异步派发的，
    //   派发到的时候布局可能早就变了。实测抓到的一条：
    //
    //     { cards: 24, sentinelTop: 1951, innerH: 1000, isIntersecting: true }
    //
    //   哨兵在 1951px 处、视口只有 1000px 高，却报告「相交了」——
    //   因为这条记录是【首屏加载中】生成的：那时商品还是 0 条，
    //   哨兵就贴在筛选栏下面（大约 600px 处），确实在视口里。
    //   等回调真的跑起来，24 条商品已经渲染完毕、哨兵被推到了 1951px，
    //   可记录里写的还是当初那个 true。
    //
    //   更巧的是它刚好能通过三个前置判断：
    //   请求已经回来了（loading=false）、没有并发的追加（loadingMore=false）、
    //   确实还有下一页（hasMore=true）—— 于是白拉了一页。
    //
    //   修法不是「再补一个判断」，而是【根本不相信那个快照】：
    //   统一走 fillViewport()，它在被调用的这一刻用
    //   getBoundingClientRect() 重新量一次。**几何信息只在用的那一刻量**，
    //   这是这类 bug 的通用解法。
    //
    //   顺带也就没有「两个入口对『可见』有不同定义」的问题了。
    fillViewport()
  },
  { rootMargin: `${BOTTOM_OFFSET}px` },
)

watch(
  sentinel,
  (el, oldEl) => {
    if (oldEl) observer.unobserve(oldEl)
    if (el) observer.observe(el)
  },
  // flush: 'post' 让回调在 DOM 更新【之后】跑，
  // 不然拿到的 el 可能还没真正插进文档树
  { flush: 'post' },
)

/**
 * ★ 观察者不会在「已经交叉」的情况下重复回调 ——
 *   它只在交叉状态【发生变化】时通知你。
 *
 * <p>于是有个漏洞：如果第一批数据还没铺满一屏，哨兵从一开始就在视口里，
 * 它就永远不会「进入视口」，回调一次都不会发生。
 * 用户看到的是一个没满又不能加载的列表，滚动条都不出现。
 *
 * <p>所以每次加载成功后主动补一次位置检查，把「铺满一屏」
 * 这件事做完。检查放在 nextTick 之后 —— 必须等 DOM 真的长高了
 * 再量，否则量到的还是加载之前的高度。
 */
function fillViewport() {
  nextTick(() => {
    const el = sentinel.value
    if (!el) return
    if (el.getBoundingClientRect().top <= window.innerHeight + BOTTOM_OFFSET) {
      loadMore()
    }
  })
}

/**
 * 组件卸载时必须断开观察者。
 *
 * <p>不断开的话，这个观察者还在盯着一个已经从文档里摘掉的节点，
 * 每次「交叉状态变化」都会回调到一个已经销毁的组件上。
 * 单页应用里反复进出首页就会累积一堆这样的观察者 ——
 * 内存泄漏就是这么一点点攒起来的。
 */
onBeforeUnmount(() => {
  observer.disconnect()
})

// ---------------------------------------------------------------------------
// 主视觉的轮播
// ---------------------------------------------------------------------------

/**
 * 三张主视觉图，都是本地生成的 SVG（见 sql/gen-shop-assets.py）。
 *
 * ★ 图上【不写字】，标题和按钮用 HTML 浮在上面。
 *   因为 <img> 引用的 SVG 是一份独立文档：引不到页面的字体和 CSS，
 *   中文字体解析不到就会渲染成一排空心方框（豆腐块）。
 *   而且写进图里的字不能选中、不能复制、也搜不到 ——
 *   商品文案本来就该是 HTML。
 *
 * ★ 点击跳转的 categoryId 是【查出来的】，不是写死的数字。
 *   category 表的 AUTO_INCREMENT 已经被测试烧到 62 了，
 *   下次重建库 id 又会变，写死就是埋雷。查不到就退回首页 ——
 *   宁可少一个跳转，也不要跳到一个 404 的分类上。
 */
const bannerDefs = [
  {
    src: '/images/banner-01.svg',
    title: '数码焕新季',
    subtitle: '手机 · 耳机 · 智能穿戴',
    categoryName: '手机数码',
  },
  {
    src: '/images/banner-02.svg',
    title: '家电换新专场',
    subtitle: '冰箱 · 洗衣机 · 厨房小电',
    categoryName: '家用电器',
  },
  {
    src: '/images/banner-03.svg',
    title: '入冬穿搭指南',
    subtitle: '羽绒服 · 鞋靴 · 箱包',
    categoryName: '服饰鞋包',
  },
]

const banners = computed(() =>
  bannerDefs.map((b) => {
    const c = categoryStore.list.find((x) => x.name === b.categoryName)
    return { ...b, to: c ? { path: '/', query: { categoryId: c.id } } : { path: '/' } }
  }),
)

const bannerIndex = ref(0)
let bannerTimer = null
const BANNER_INTERVAL = 4000

function startBannerTimer() {
  stopBannerTimer()
  bannerTimer = setInterval(() => {
    bannerIndex.value = (bannerIndex.value + 1) % bannerDefs.length
  }, BANNER_INTERVAL)
}

function stopBannerTimer() {
  if (bannerTimer) {
    clearInterval(bannerTimer)
    bannerTimer = null
  }
}

/**
 * 手动点小圆点。
 *
 * <p>★ 点完必须<b>重置计时器</b>，不能让它继续跑。
 *   不重置的话会出现这种别扭的情况：用户刚点了第 2 张，
 *   0.3 秒后（因为上一轮的 4 秒刚好到点）自动跳到了第 3 张 ——
 *   看起来像是「点了但没反应」或者「点错了」。
 */
function goBanner(i) {
  bannerIndex.value = i
  startBannerTimer()
}

/**
 * ★ 定时器必须在卸载时清掉。
 *
 * <p>不清的话，用户从首页点进商品详情，首页组件销毁了，
 * 但这个定时器还在每 4 秒往一个死掉的组件里写值。
 * 进一次首页漏一个，逛一会儿就有七八个定时器在跑 ——
 * 这是单页应用里最经典的泄漏之一。
 *
 * <p>规律很简单：<b>凡是 setInterval，就必须有一个对应的 clearInterval，
 * 而且通常写在 onBeforeUnmount 里。</b>
 */
onMounted(startBannerTimer)
onBeforeUnmount(stopBannerTimer)

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

/** 有没有任何筛选条件生效 —— 用来决定要不要显示「清空筛选」按钮 */
const hasFilter = computed(
  () => !!(keyword.value || categoryId.value || sort.value !== 'default'),
)

/** 结果是空的时候，提示语要分情况：筛出来的空 ≠ 一件商品都没有 */
const emptyText = computed(() =>
  hasFilter.value ? '没有找到符合条件的商品' : '暂时还没有上架的商品',
)

/**
 * 网格上方的标题。
 *
 * <p>三种情况全都是<b>真实状态</b>的反映，没有一个是编的：
 * 搜索时显示搜的词、选了分类显示分类名、都没选就说「为你推荐」。
 * 京东这里是个「猜你喜欢 / 为你推荐」的 Tab，
 * 那需要一套推荐算法 —— 我们没有，就不装作有。
 */
const gridTitle = computed(() => {
  if (keyword.value) return `“${keyword.value}” 的搜索结果`
  const c = categories.value.find((x) => x.id === categoryId.value)
  if (c) return c.name
  return '为你推荐'
})

function goDetail(id) {
  router.push(`/product/${id}`)
}

/**
 * 在列表页直接加入购物车。
 *
 * <p>★ 注意 @click 后面带了 {@code .stop} —— 这是必须的。
 *
 * <p>因为整个卡片上绑了 {@code @click="goDetail(p.id)"}，
 * 而按钮在卡片<b>里面</b>。不加 .stop 的话，
 * 点「加入购物车」会同时触发按钮和卡片的点击处理：
 * 用户想加购，结果被跳到了详情页（而且加购请求也发出去了）。
 *
 * <p>{@code .stop} 对应的是 {@code event.stopPropagation()}，
 * 阻止事件继续往父元素冒泡。
 *
 * <p><b>这个坑在「卡片可点击 + 卡片内有按钮」的布局里几乎必然遇到</b>，
 * 而且不加也能跑（只是行为不对），很容易漏。写这类布局时
 * 要习惯性地问一句：里面的按钮点了会冒泡到外面吗？
 */
async function quickAdd(product) {
  // ★★ 里程碑 15 阶段 3：多规格商品在卡片上【不做加购】，先去详情页。
  //
  //   ★ 为什么不能「随便挑一个规格加进购物车」：
  //     那样用户以为买的是"那个商品"，实际上系统替他选了一个规格 ——
  //     而不同的规格【价格可以不一样】。他会以为什么价都行，
  //     到结算页才发现金额和他看到的不一样，或者到货发现颜色不对。
  //     **一个回答不了的问题（你要哪个规格？）不应该有一个假答案。**
  //
  //   ★ 这也正是后端那条「多规格时不给 defaultSkuId」的规矩在界面上
  //     的落点：接口只对单规格商品给出「加购直接用它」的那个 id，
  //     多规格时那个 key 整个不存在。前端在这里做的判断
  //     （skuCount > 1）和它是同一件事，不是第二套规则。
  //
  //   ⚠️ 登录检查排在它后面：【看规格不需要登录】。
  //     先跳登录页会打断一条本来可以继续的浏览路径。
  if ((product.skuCount ?? 0) > 1) {
    ElMessage.info('这件商品有多个规格，请先选择规格')
    goDetail(product.id)
    return
  }

  // ★★ 里程碑 15 阶段 4：这里传的是 {@code defaultSkuId}，不是商品 id。
  //
  //   ⚠️ 走到这里时 {@code skuCount} 必然是 1（多规格的在上面就跳走了），
  //      所以后端【一定】会给出 defaultSkuId —— 这条链是闭合的。
  //      但为了防止数据漂移（比如 skuCount 是脏的、或者某件商品真的
  //      一条 SKU 都没有），下面还是兜一道：拿不到就退回去详情页，
  //      而不是发一个注定失败的请求，让用户看到一句他看不懂的错误。
  //
  //   ★ 为什么用宽松真值判断而不是 === undefined：后端配了 non_null，
  //     值为 null 的字段【整个 key 消失】，这里读到的是 undefined。
  //     （这个语义在本项目里出现过很多次，见 CartItemVO / OrderItemVO。）
  //
  //   ⚠️ 这道检查排在登录检查【前面】。理由和上面那条一样：
  //     它跟登不登录没关系，是数据的问题。
  //     把一个"商品数据有问题"的用户送去登录页，他会登录完再回来，
  //     再撞一次同一堵墙 —— 而且始终没人告诉他到底怎么了。
  if (!product.defaultSkuId) {
    goDetail(product.id)
    return
  }

  if (!userStore.isLoggedIn) {
    ElMessage.info('请先登录后再加入购物车')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }

  const ok = await cartStore.add(product.defaultSkuId, 1)
  if (ok) {
    await cartStore.refresh(true)
    ElMessage.success('已加入购物车')
  }
}

/**
 * 卡片上那个按钮的字。
 *
 * <p>三种状态，判据和上面那个 {@code :disabled} 是同一组字段：
 * <pre>
 *   整件商品都没货   → 「已售罄」    （disabled，点不动）
 *   多个规格         → 「选规格」    （点了去详情页）
 *   一个规格         → 「加入购物车」（点了直接加）
 * </pre>
 *
 * <p>★ 写成函数而不是三段内联三元表达式：同一个判断在模板里要用两次
 * （一次决定文字、一次决定禁用），写成两处内联就是两份会分叉的规则。
 */
function cardLabel(p) {
  if ((p.totalStock ?? 0) <= 0) {
    return '已售罄'
  }
  return (p.skuCount ?? 0) > 1 ? '选规格' : '加入购物车'
}
</script>

<template>
  <div class="page-container">
    <!-- ==================== 三栏主视觉 ==================== -->
    <div class="hero">
      <!--
        左栏：分类。
        ★ 它【不是一份新状态】—— 点击调用的还是原来那个 selectCategory()，
          只是换一个地方去写同一个 categoryId。
          「同一个变量有两种写法」是可以的；「两个变量各写各的」才是错的。
          这正是把筛选条件放在 URL 里的好处：新增一个入口不需要新增状态。
      -->
      <aside class="hero-cats">
        <div class="hero-cats-title">全部商品分类</div>
        <ul class="cat-list">
          <li
            class="cat-item"
            :class="{ active: categoryId === null }"
            @click="selectCategory(null)"
          >
            全部商品
          </li>
          <li
            v-for="c in categories"
            :key="c.id"
            class="cat-item"
            :class="{ active: categoryId === c.id }"
            @click="selectCategory(c.id)"
          >
            {{ c.name }}
          </li>
        </ul>
        <!--
          分类没加载出来时列表是空的 —— 静默降级。
          不写「加载失败」是因为用户对这个不感兴趣，
          他要的是商品，商品还在就够了
        -->
      </aside>

      <!-- 中栏：主视觉轮播 -->
      <div class="hero-carousel">
        <!--
          ★ 三张图【同时挂在 DOM 里】，靠 opacity 切换做交叉淡入。
            不是只渲染当前那张 —— 只渲染当前张的话，第一轮切换时
            下一张才开始下载，用户会先看到一片空白再看到图。
            三张 SVG 加起来不到 6KB，一起加载的代价可以忽略。
        -->
        <img
          v-for="(b, i) in banners"
          :key="b.src"
          :src="b.src"
          :alt="b.title"
          class="banner-img"
          :class="{ active: i === bannerIndex }"
        />

        <!-- 文字浮在图上。图的左侧三分之一是特意留白的，就是给这里用的 -->
        <div class="banner-text">
          <h2>{{ banners[bannerIndex].title }}</h2>
          <p>{{ banners[bannerIndex].subtitle }}</p>
          <router-link class="banner-btn" :to="banners[bannerIndex].to">
            立即查看
          </router-link>
        </div>

        <div class="banner-dots">
          <span
            v-for="(b, i) in banners"
            :key="i"
            class="dot"
            :class="{ active: i === bannerIndex }"
            @click="goBanner(i)"
          />
        </div>
      </div>

      <!-- 右栏：快捷入口 + 公告 -->
      <aside class="hero-side">
        <div class="side-card">
          <div class="side-title">快捷入口</div>
          <router-link to="/cart" class="side-link">我的购物车</router-link>
          <router-link to="/orders" class="side-link">我的订单</router-link>
          <router-link to="/addresses" class="side-link">收货地址</router-link>
        </div>

        <div class="side-card">
          <div class="side-title">商城公告</div>
          <!--
            ★ 这里三条全是【真话】。公告区是最容易塞假数据的地方
              （「满 199 减 50」这种谁也没实现的活动），
              而假的公告比没有公告更糟 —— 用户会去找那个活动入口，
              找不到就以为网站坏了。写清楚它是什么，反而是有用的信息。
          -->
          <ul class="notice-list">
            <li>本站是 Spring Boot 3 + Vue 3 学习项目</li>
            <li>商品与价格均为演示数据，不产生真实交易</li>
            <li>下单走模拟支付流程，可自行取消订单</li>
          </ul>
        </div>
      </aside>
    </div>

    <!-- ==================== 网格头 ==================== -->
    <div class="grid-header">
      <h3 class="grid-title">{{ gridTitle }}</h3>

      <div class="grid-tools">
        <!--
          ★ 这里【只剩排序】了。原来还有一个搜索框，
            已经搬到头部导航栏（App.vue）—— 那是全局入口，
            而首页这里再放一个就是同一个功能有两个入口。
            关键词筛选的能力一点没少，只是入口少了一个。
        -->
        <el-select :model-value="sort" size="small" class="sort-select" @change="changeSort">
          <el-option label="默认排序" value="default" />
          <el-option label="价格从低到高" value="price_asc" />
          <el-option label="价格从高到低" value="price_desc" />
        </el-select>

        <el-button v-if="hasFilter" text type="primary" size="small" @click="resetAll">
          清空筛选
        </el-button>
      </div>
    </div>

    <!--
      ★ loading 用 v-loading 指令而不是 v-if 显示骨架屏。
        骨架屏体验更好但要多写不少代码，教学项目里
        v-loading 的性价比更高（一行搞定）
    -->
    <div v-loading="loading" class="content">
      <el-empty v-if="loadFailed" description="加载失败，请稍后重试">
        <el-button type="primary" @click="reload">重新加载</el-button>
      </el-empty>

      <el-empty v-else-if="!products.length && !loading" :description="emptyText">
        <el-button v-if="hasFilter" @click="resetAll">清空筛选条件</el-button>
      </el-empty>

      <template v-else>
        <div class="product-grid">
          <div
            v-for="p in products"
            :key="p.id"
            class="product-card"
            @click="goDetail(p.id)"
          >
            <div class="product-cover">
              <!--
                ★ 占位逻辑和「加载失败」兜底都搬进了 ProductImage。
                  原来是这里自己一份 coverOf()、Cart 一份、ProductDetail 一份，
                  三份长得差不多；更要紧的是它们【只处理了空值，
                  没处理「有值但加载不出来」】—— cover 是后台手填的字符串，
                  填错了浏览器只会露一个碎图图标，而那会让整页看起来是坏的。
                  详见组件里的注释。
              -->
              <ProductImage :src="p.cover" :alt="p.name" :size="16" />
            </div>

            <div class="product-info">
              <!--
                ★ 价格在标题【上面】—— 这是京东商品卡最显著的特征。
                  一般网站的卡片是「图 → 标题 → 价格」，京东是反的。
                  原因很实际：用户扫一屏卡片时先看价格，价格在上面
                  就不用每张都往下找一遍。
              -->
              <div class="product-meta">
                <!--
                  ⚠️ <i> 不能去掉、也不能把 ¥ 并进 formatAmount 的返回值 ——
                     符号比数字小一号这件事是靠这个标签的 CSS 实现的
                     （见本文件 <style> 里 .product-price i），
                     而 formatAmount 的契约是【不带货币符号】。
                     里程碑 14 改的是插值里的表达式，不是标签结构。
                -->
                <span class="product-price"><i>¥</i>{{ formatAmount(p.minPrice) }}<span v-if="(p.skuCount ?? 0) > 1" class="price-from">起</span></span>
                <!--
                  ★★ 里程碑 15 阶段 3：这里原来是「库存 N 件」。

                  ⚠️ 换成「N 个规格」不是换了个说法，是那个数【变得没有意义了】。
                     SKU 化之后「这件商品的库存」是一个跨规格的合计
                     （totalStock = SUM(product_sku.stock)），而它回答的
                     「一共还有几件」不是一个买得到的数量 ——
                     总库存 10 件分散在 4 个规格上，用户选哪一档都买不到 10 件。
                     一个用户用不到的数摆在卡片上，比不摆更糟：
                     他会拿它当"我最多能买几件"。

                  ★ 而「几个规格」是【真的】：它就是这个商品的规格数，
                    也正是卡片上最该告诉用户的那件事 ——
                    他要不要为了一件商品多点一次进去选规格。
                -->
                <span class="product-stock">{{ p.skuCount ?? 0 }} 个规格</span>
              </div>

              <div class="product-name" :title="p.name">{{ p.name }}</div>

              <!--
                ★ 这里放的是【分类名】，不是店铺名。
                  京东卡在这个位置是店铺名（「XX旗舰店」），但
                  本项目的 ShopProductVO 里根本没有店铺字段 ——
                  编一个「自营」角标或者假店铺名，会得到一个
                  「看起来像数据但不是数据」的东西，三周后一定坑到自己。
                  分类名是真实存在的，放这儿也算有用（能看出这是什么类型的东西）。
              -->
              <div class="product-category">{{ p.categoryName || '未分类' }}</div>

              <!--
                ★ .stop 是必须的：阻止点击冒泡到卡片的 goDetail，
                  否则点加购会同时跳转到详情页。详见 quickAdd 的注释

                ★★ 里程碑 15 阶段 3：这个按钮有了【三种】状态。

                  判据是 totalStock（跨规格合计）—— 它是唯一一个
                  「这件商品整体还有没有货」的判据，而这里问的正是这个问题。

                  ⚠️ 但 totalStock 【只能】用来判断「整件商品售罄」，
                     不能拿它当「最多能买几件」：
                       判断整体有没有货 → 合计是对的，全部规格都没货才算售罄
                       决定单次买几件   → 必须用【所选规格】的库存（详情页里）
                     ⚠️ 用错方向不会报错，只会让数量选择器的上限比实际大几倍。

                  ⚠️ 而且这个禁用它【拦不住任何东西】——库存可能在这几秒里
                     被别人买走。真正的判定在后端下单时的事务里。
                     这里做只是为了让用户少点一个注定失败的按钮。
              -->
              <el-button
                class="quick-add"
                type="danger"
                size="small"
                :disabled="(p.totalStock ?? 0) <= 0"
                @click.stop="quickAdd(p)"
              >
                {{ cardLabel(p) }}
              </el-button>
            </div>
          </div>
        </div>

        <!--
          ★ 哨兵元素。它自己什么都不做，唯一的作用是「被观察到」——
            它一进视口就说明用户滑到底了。所以它必须【一直被渲染】，
            不能 v-if 掉，否则观察者盯着的是一个不存在的节点。
            到底了/正在加载这些状态用里面的文字表达即可。
        -->
        <div ref="sentinel" class="load-more">
          <span v-if="loadingMore" class="load-more-text">正在加载更多…</span>
          <span v-else-if="!hasMore" class="load-more-text end">
            — 已经到底啦，共 {{ total }} 件商品 —
          </span>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* ============================ 三栏主视觉 ============================ */
.hero {
  display: grid;
  /* 左 200 / 中自适应 / 右 240。
     中间那列写 minmax(0, 1fr) 而不是 1fr —— 这是 grid 的一个坑：
     1fr 的最小值是 auto，内容撑不下时会把整行顶宽、溢出容器。
     写 minmax(0, 1fr) 才是「真的可以缩到 0」。 */
  grid-template-columns: 200px minmax(0, 1fr) 240px;
  gap: 10px;
  /* ★ 高度跟着视口长，不写死 400px。
     中间那栏的宽度 = 内容宽 − 200 − 240 − 20，而内容宽是随屏幕变的
     （见 style.css 的 .page-container）。高度不跟着长的话，
     横幅图会被 object-fit: cover 从上到下裁掉一大截 ——
     裁掉的是产品图形本身，不是空白。
     27vw 大致等于「中间那栏 ÷ 1.8」，上下用 clamp 卡住：
     小屏不矮于 400（再矮文字就挤了），大屏不高过 470（再高首屏就看不到商品了）。 */
  height: clamp(400px, 27vw, 470px);
  margin-bottom: 20px;
}

/* ---- 左栏：分类 ---- */
.hero-cats {
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border: 1px solid var(--jd-border-light);
  overflow: hidden;
}

.hero-cats-title {
  flex-shrink: 0;
  height: 40px;
  line-height: 40px;
  padding-left: 14px;
  background-color: var(--jd-red);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
}

.cat-list {
  /* ★ 做成 flex 列，让分类行去摊掉剩余高度。
     左栏和主视觉一样高（400~470px），而分类只有 6 个 + 「全部」，
     按固定行高排完底下会空出 190px 的一整块白 ——
     比「行距大一点」难看得多，也更容易被看成「没加载出来」。
     flex:1 的老写法保留在下面：分类多到装不下时它照样能滚。 */
  display: flex;
  flex-direction: column;
  flex: 1;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  overflow-y: auto;
}

.cat-item {
  display: flex;
  align-items: center;
  /* 34px 是【下限】而不是固定行高：装得下就按 flex-grow 摊开，
     装不下（将来分类变多）就退到 34px 并交给上面的 overflow-y 滚动。
     max-height 卡住上限 —— 再高就变成「一行一个大按钮」，
     看着像设置菜单，不像分类导航了。 */
  flex: 1 1 34px;
  min-height: 34px;
  max-height: 52px;
  padding: 0 14px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s;
}

.cat-item:hover {
  background-color: var(--jd-red-bg);
  color: var(--jd-red);
}

.cat-item.active {
  background-color: var(--jd-red-bg);
  color: var(--jd-red);
  font-weight: 700;
}

/* ---- 中栏：轮播 ---- */
.hero-carousel {
  position: relative;
  overflow: hidden;
  background-color: #1e3a8a; /* 图还没加载出来时的兜底底色，和 banner-01 同色 */
}

.banner-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  /* cover：图是 1000×650，容器比例更扁，按宽度铺满后会裁掉上下一点点。
     这比 contain 好 —— contain 会在两侧留出两条底色，很难看。
     裁掉的是上下边缘的空白，商品图形本身留了足够的边距 */
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.6s;
}

.banner-img.active {
  opacity: 1;
}

.banner-text {
  position: absolute;
  left: 30px;
  top: 78px;
  color: #fff;
}

.banner-text h2 {
  margin: 0 0 10px;
  font-size: 32px;
  letter-spacing: 2px;
  /* 图上是深色渐变底，白字够亮；加一层阴影是防止某张图恰好左侧偏亮 */
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.25);
}

.banner-text p {
  margin: 0 0 22px;
  font-size: 15px;
  opacity: 0.85;
  letter-spacing: 1px;
}

.banner-btn {
  display: inline-block;
  padding: 8px 22px;
  background-color: #fff;
  color: var(--jd-red);
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
  transition: transform 0.15s;
}

.banner-btn:hover {
  transform: translateX(3px);
}

.banner-dots {
  position: absolute;
  left: 30px;
  bottom: 22px;
  display: flex;
  gap: 8px;
}

.dot {
  width: 22px;
  height: 4px;
  background-color: rgba(255, 255, 255, 0.45);
  cursor: pointer;
  transition: background-color 0.2s;
}

.dot.active {
  background-color: #fff;
}

/* ---- 右栏：快捷入口 + 公告 ---- */
.hero-side {
  display: grid;
  grid-template-rows: 1fr 1fr;
  gap: 10px;
}

.side-card {
  padding: 12px 14px;
  background-color: #fff;
  border: 1px solid var(--jd-border-light);
  overflow: hidden;
}

.side-title {
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--jd-border-light);
  font-size: 14px;
  font-weight: 700;
  color: #333;
}

.side-link {
  display: block;
  padding: 6px 0;
  font-size: 13px;
  color: #666;
  text-decoration: none;
  transition: color 0.15s, padding-left 0.15s;
}

.side-link:hover {
  color: var(--jd-red);
  padding-left: 4px;
}

.notice-list {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  line-height: 1.7;
  color: #999;
}

/* ============================ 网格头 ============================ */
.grid-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--jd-border);
}

.grid-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: #333;
}

.grid-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sort-select {
  width: 130px;
}

/* ============================ 商品网格 ============================ */
.content {
  min-height: 320px;
}

.product-grid {
  display: grid;
  /* ★ 固定 6 列 —— 用户明确要求每行 6 个。
     不用 auto-fill/minmax 自适应：那样在宽屏上会变成 7 列、8 列，
     每个卡片越缩越小，商品图就看不清了。
     6 列在 1200px 容器里每张卡约 190px，和京东的卡片尺寸一致。

     minmax(0, 1fr) 而不是 1fr：防止「商品名是两个很长的英文单词」
     这种内容把某一列顶宽（1fr 的最小尺寸是 auto）。 */
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.product-card {
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border: 1px solid var(--jd-border-light);
  border-radius: var(--jd-radius);
  overflow: hidden;
  cursor: pointer;
  /* 用 transform 做悬停位移，而不是改 margin —
     transform 不触发重排（reflow），性能好得多 */
  transition: transform 0.2s, box-shadow 0.2s;
}

.product-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
}

.product-cover {
  width: 100%;
  aspect-ratio: 1 / 1;
  overflow: hidden;
  background-color: #f7f7f7;
}

/* ★ 这条规则是 ProductImage 组件的根元素【必须】还是 <img> 的原因。
   如果组件在占位时改渲染 <div>，这里就匹配不到，
   图片高度会塌成 0，整张卡片只剩一条缝。 */
.product-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.product-info {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding: 8px 10px 10px;
}

.product-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px;
}

.product-price {
  font-size: 17px;
  font-weight: 700;
  color: var(--jd-red);
}

/* ¥ 符号比数字小一号，是电商价格的常见做法 ——
   让数字占视觉主体，扫一眼就能比较大小 */
.product-price i {
  font-style: normal;
  font-size: 12px;
  margin-right: 1px;
}

/* 「起」——跟在起售价后面那个字（里程碑 15）。
   ★ 用中性的灰，不用价格那个红：它是【注解】不是价格的一部分，
     染成红的会被一起读成数字 */
.price-from {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.product-stock {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}

.product-name {
  margin-top: 6px;
  font-size: 13px;
  color: #333;
  line-height: 18px;
  height: 36px;
  /* 商品名可能很长，用 -webkit-line-clamp 截成两行加省略号。
     这个属性虽然带 -webkit- 前缀，但所有现代浏览器都支持。
     ★ height 必须等于 line-height × 行数（18 × 2 = 36），
       少了最后一行会被切掉半截，多了卡片高度会参差不齐 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.product-category {
  margin-top: 4px;
  font-size: 12px;
  color: #bbb;
}

.quick-add {
  width: 100%;
  /* ★ margin-top: auto 把按钮顶到卡片底部。
     商品名有一行和两行两种情况，不加这个的话按钮会一高一低，
     一屏看过去就是参差不齐的。
     注意上面 .product-info 必须是 display:flex + flex-direction:column，
     margin-top:auto 才有效 —— 它在普通块级布局里什么都不做。 */
  margin-top: auto;
}

/* ============================ 加载更多 ============================ */
.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  /* 高度不能是 0：哨兵元素高度为 0 时，它在视口边缘的交叉判定
     会变得很敏感，滚动时反复进出，触发一堆无效回调 */
  min-height: 60px;
  margin-top: 16px;
}

.load-more-text {
  font-size: 13px;
  color: #999;
}

.load-more-text.end {
  color: #ccc;
}
</style>
