<script setup>
/**
 * 我的订单。
 *
 * <p>路由 {@code /orders}，需要登录。
 *
 * <h3>★★ 筛选状态放在 URL 里，和首页同一个思路</h3>
 *
 * <p>{@code Home.vue} 开头把这条规矩讲得很清楚，
 * 这里只补充「为什么这个页面属于该放 URL 的那一类」：
 *
 * <pre>
 *   Home 是无限滚动  →  「加载到第几页」取决于用户滚了多远，
 *                       不是一份可以分享的状态  →  pageNum 不进 URL
 *   本页是真的分页    →  /orders?status=1&pageNum=3 是一个
 *                       可以被分享、被后退的地址        → 两个都进 URL
 * </pre>
 *
 * <p>换句话说：<b>「分页」这个形态本身就恢复了「页码可分享」的前提。</b>
 * {@code Home.vue} 里那句「URL 存『查什么』，组件存『查了多少』」
 * 在这里的答案是「查什么」和「查了多少」都是 URL 的事 ——
 * 因为在这一页上，翻到第 3 页和筛选「已付款」是同一种性质的动作。
 *
 * <p>所以判断标准仍然是那一条：<b>一个状态该不该进 URL，
 * 看它能不能被分享，而不是看它是不是状态。</b>
 *
 * <h3>★ 只有一条写入路径</h3>
 *
 * <pre>
 *   用户点 Tab / 翻页  →  updateQuery(patch)  ← 只改 URL
 *                                              ↓
 *   watch(route.query) 监听到变化 → 发请求      ← 只读 URL
 * </pre>
 * 组件里<b>不存</b>「当前是哪个 Tab、第几页」——
 * 需要时就地从 {@code route.query} 算（见下面的 status / pageNum）。
 * <b>只有一条写入路径，就不会有循环。</b>
 *
 * <h3>★ 和管理端的约定刻意相反</h3>
 *
 * <p>{@code mall-web} 的列表页（商品 / 分类 / 订单）查询条件全部是
 * 组件里的 {@code reactive}，从来不入 URL。
 * <b>不要把这个文件的模式搬过去</b>，也不要因为那边那样做而怀疑这里 ——
 * 两个工程的使用场景不同（前台要分享链接、后台不需要），
 * 各自内部保持一致才是要紧的。
 * 在只有一个使用者的地方引入一套同步层，是纯粹的不一致。
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, completeOrder, getOrderLogistics, listMyOrders } from '@/api/order'
import { applyAfterSale } from '@/api/afterSale'
import ReviewFormDialog from '@/components/ReviewFormDialog.vue'
import { formatAmount } from '@/utils/format'
import { readOrderStatus } from '@/utils/query'
import { ORDER_STATUS, orderStatusLabel, orderStatusTagType } from '@/utils/orderStatus'
import {
  AFTER_SALE_REASONS,
  AFTER_SALE_TYPES,
  afterSaleReasonLabel,
  afterSaleStatusLabel,
  afterSaleStatusTagType,
  afterSaleTypeLabel,
} from '@/utils/afterSaleStatus'
import { logisticsStatusLabel, logisticsStatusTagType } from '@/utils/logisticsStatus'

const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 5

const loading = ref(false)
const orders = ref([])
const total = ref(0)

/**
 * ★★ 每行一个 loading 标记，用【对象】按订单号索引，
 * <b>不能学 Cart.vue 用单个 {@code ref(null)}</b>。
 *
 * <p>单个 ref 只允许「同时只有一个操作在进行」。购物车那样做没问题，
 * 因为那一页的删除是逐个点的、没有并发的必要。
 * 但订单列表里用户完全可能连点两行的「确认收货」——
 * 用单个 ref 的话，点第二行会把第一行的 loading 状态抢走：
 * 第一行的按钮提前恢复可点（用户以为没点上，再点一次 → 1002），
 * 而第二行看起来卡住了。
 *
 * <p>用对象之后，两行互不影响。<b>「一行一个」的状态就该按行存。</b>
 */
const acting = ref({})

// ---------------------------------------------------------------------------
// 评价（★ 里程碑 12）
// ---------------------------------------------------------------------------

const reviewVisible = ref(false)

/**
 * 当前正在评价的那条【订单明细】。
 *
 * <p>★★ 存的是明细，不是订单 —— 一张订单里的两件商品要<b>分别</b>评价，
 * 所以弹窗需要的是 {@code it}（明细），不是 {@code o}（订单）。
 * 这也是评价入口为什么放在明细行里而不是 {@code card-actions} 里
 * （见模板里那段注释）。
 */
const reviewingItem = ref(null)

/**
 * 打开评价弹窗。
 *
 * <p>★ 只负责「把哪一条记下来 + 打开弹窗」，别的什么都不做。
 * {@code acting[it.id]} 那个 loading 是给 {@code el-button} 自己用的，
 * 这里不设 —— 按钮点下去立刻就打开弹窗了，没有异步等待可言。
 * （对比 {@code handleComplete}：那是一次网络请求的等待，
 * 所以它必须自己管 loading。）
 */
function openReview(item) {
  reviewingItem.value = item
  reviewVisible.value = true
}

/**
 * 评价成功之后。
 *
 * <p>★★ <b>调 {@code load()} 重查，不做本地打补丁。</b>
 *
 * <p>本页已经有过一次「能不能本地改」的争论 —— 见 {@code handleComplete}
 * 里那段长注释，结论是「<b>一个带筛选的列表必须始终是那个筛选条件的忠实呈现，
 * 由服务端说了算</b>」。
 *
 * <p>而评价这件事看起来比「确认收货」更适合本地改：订单状态没变，
 * 只是把那一行的 {@code reviewId} 填上就行，本地改完全安全。
 *
 * <p>★ 但这里仍然选择重查，理由是<b>一条规矩比一条规矩加一个例外好维护</b>：
 * 一旦有了「这个操作可以本地改、那个不可以」的分界，下一个人就得
 * 每次都重新判断一次，而判断错的方向是「悄悄显示了一个后端已经不同意的状态」。
 * 何况重查是免费的 —— 一页就 5 张卡片。
 */
function handleReviewSuccess() {
  load()
}

// ---------------------------------------------------------------------------
// 读 URL —— 唯一的事实来源
// ---------------------------------------------------------------------------

/**
 * 当前选中的状态。
 *
 * <p>★★ 用 {@code readOrderStatus} 而不是 {@code toPositiveInt}。
 * 判据是「0 是不是一个合法的值」—— 对状态来说<b>是</b>（待付款），
 * 对分类 id / 商品 id 来说不是。
 * 详见 {@code utils/query.js} 里那个函数的注释。
 */
const status = computed(() => readOrderStatus(route.query))

/** 当前页码。翻页器也把它当 {@code v-model:current-page} 用 */
const pageNum = computed(() => {
  const n = Number(route.query.pageNum)
  return Number.isInteger(n) && n > 0 ? n : 1
})

/**
 * Tab 列表。
 *
 * <p>★ <b>没有「已取消」Tab，也不打算加。</b>
 * 已取消的订单只在「全部」里出现，理由是：<b>没有人会专门来找
 * 一笔自己取消掉的订单。</b> Tab 是一种稀缺资源，
 * 每多一个 Tab，其他 Tab 被点到的概率就下降一点。
 *
 * <p>⚠️ 更重要的是<b>不编造状态</b>：
 * 真实电商会在这里加「待评价」「退款中」之类，但
 * {@code OrderStatus} 里没有这些值。凭空编一个出来，
 * 就等于前端有了第二份状态定义，而后端永远查不出这个状态 ——
 * 用户点进去只会看到一个空列表。
 * <b>Tab 的取值必须来自后端的合法集合，一个都不能多。</b>
 *
 * <p>注意「全部」的 value 是 {@code null}，会被 {@code updateQuery}
 * 从 URL 里删掉 —— <b>用「不存在」表示「全部」，而不是用一个哨兵值</b>
 * （比如 -1）。哨兵值是个魔法数字，迟早会和真实取值撞车。
 */
const TABS = [
  { value: null, label: '全部' },
  { value: ORDER_STATUS.PENDING_PAY, label: '待付款' },
  { value: ORDER_STATUS.PAID, label: '已付款' },
  { value: ORDER_STATUS.SHIPPED, label: '已发货' },
  { value: ORDER_STATUS.COMPLETED, label: '已完成' },
]

/**
 * 写入 URL。
 *
 * <p>★ 和 {@code Home.vue} 的 {@code updateQuery} 是同一个模式，
 * 但这里<b>多了一个必须一起写的参数</b>：切 Tab 时要顺带把页码归零。
 * 见下面 {@code changeTab}。
 *
 * <p>⚠️ 清理空值时判断 {@code === null || === undefined || === ''}，
 * <b>绝不能写成 {@code if (!v)}</b>：{@code status=0}（待付款）
 * 是个真值判断里的假值，会被当成空值删掉 ——
 * 于是「待付款」Tab 点了没反应。
 * 这和 {@code readOrderStatus} 里那个 {@code > 0} 的坑
 * <b>是同一个 bug 的入口和出口</b>，两处都要防住才算真的防住。
 *
 * <h3>★★ 为什么用 replace 而不是 push —— 包括【翻页】也用 replace</h3>
 *
 * <p>{@code Home.vue} 立过这条规矩：
 * <b>「筛选条件的变化用 replace，页面之间的跳转用 push。」</b>
 * 理由是「点了 10 个分类再按后退，就得按 10 次才能回到最开始那个页面」。
 *
 * <p>⚠️ <b>翻页要不要用 push？看起来该用（很多人会觉得「后退回上一页」
 * 是分页的天经地义），但这里刻意不用。</b> 因为上面那条理由
 * <b>逐字适用于翻页</b>：
 * <pre>
 *   一个用户翻到第 10 页找订单，看完按后退键 ——
 *     use push     → 他得按 10 次才能退出这个页面
 *     use replace  → 一次就退出去
 * </pre>
 * 「按 10 次」这个抱怨不因为被按的是分类还是页码而改变。
 * 所以这里对本页的处理是：<b>同一个页面内的内容变化（切 Tab、翻页）
 * 一律 replace；只有真正跳到别的页面（去收银台、去首页）才 push。</b>
 *
 * <p>★ 顺带说清一个容易搞混的后果：<b>用 replace 的话，
 * 浏览器后退键不会在 Tab 之间「往回走」，而是直接离开订单页。</b>
 * 这不是 bug，正是 replace 的定义（替换当前这条历史记录）。
 * 如果哪天有人觉得「后退应该回到上一个 Tab」而把它改成 push，
 * 请回去读 {@code Home.vue} 那段 —— 那是同一个决定，
 * 已经权衡过一次了，不要在这里反向改回去。
 */
function updateQuery(patch) {
  const next = { ...route.query, ...patch }
  Object.keys(next).forEach((k) => {
    const v = next[k]
    if (v === null || v === undefined || v === '') {
      delete next[k]
    }
  })
  router.replace({ query: next })
}

/**
 * 切换 Tab。
 *
 * <p>★★ <b>页码必须和状态一起写。</b>
 * 分两步写（先改 status、再改 pageNum）会触发两次
 * {@code watch(route.query)}，于是<b>发两次请求</b>；
 * 而且第一次请求会是「新状态 + 旧页码」的组合 ——
 * 如果旧页码在新的状态下已经越界，用户会先看到一个空列表，
 * 然后才被第二个请求修正。一闪而过的空列表比慢一点更糟，
 * 因为它看起来像「这个状态下没有订单」。
 *
 * <p>一次 {@code router.replace} 只产生一次 query 变化，
 * 所以只发一次请求，也不会有中间的错乱状态。
 * <b>「一起变的东西要一次写完」是这种 URL 状态模式里最容易忘的一条。</b>
 */
function changeTab(value) {
  updateQuery({ status: value, pageNum: 1 })
}

function changePage(page) {
  updateQuery({ pageNum: page })
}

// ---------------------------------------------------------------------------
// 取数据
// ---------------------------------------------------------------------------

async function load() {
  loading.value = true
  try {
    // ★ 只传「有值」的参数。
    //   特别是 status：不传表示「全部」，传 0 表示「只看待付款」——
    //   两者完全不同，所以这里必须显式判断 null 而不是用真假值。
    const params = { pageNum: pageNum.value, pageSize: PAGE_SIZE }
    if (status.value !== null) {
      params.status = status.value
    }

    const data = await listMyOrders(params)
    orders.value = data.list || []
    total.value = data.total || 0
  } catch {
    // ★ 失败时清空列表。
    //   留着上一次的数据会让用户以为看到的是当前筛选的结果 ——
    //   那比空白更危险（他会以为"搜到了这些"）。
    //   错误提示已经由 request.js 的统一拦截器弹过了，这里不再弹一次。
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * ★ 翻页后的「回退」。
 *
 * <p>在某一页上取消/确认收货之后，这一页可能就空了 ——
 * 比如第 2 页只有一条，操作完再查就是「暂无数据」，
 * 而用户看到的是一个空白页，很容易以为操作出了问题。
 *
 * <p>{@code mall-web} 的 {@code views/product/List.vue} 已经解决过
 * 同一个问题（删掉最后一条后看到空白页），这里用的是同一个办法：
 * <b>本页只剩这一条、而且不在第 1 页时，页码减一。</b>
 *
 * <p>⚠️ 本页有<b>两个</b>触发点（取消订单、确认收货），
 * 所以抽成一个函数，而不是在两处各写一遍 ——
 * 这种「补丁」很容易只补一处。
 *
 * @returns {boolean} 是否发生了页码回退（回退时不需要再 load，
 *          watch 会因为 URL 变化自动再查一次）
 */
function backOffIfLastRow() {
  if (orders.value.length === 1 && pageNum.value > 1) {
    updateQuery({ pageNum: pageNum.value - 1 })
    return true
  }
  return false
}

// ---------------------------------------------------------------------------
// 申请售后（★ 里程碑 17）
// ---------------------------------------------------------------------------

const afterSaleVisible = ref(false)
const applyingOrder = ref(null)
/** 这一单里【够资格】申请售后的明细，弹窗把它们列成可勾选项 */
const applyingItems = ref([])
/** 勾选的明细 id。默认勾上被点的那一行 —— 整单退只是多勾几个 */
const selectedItemIds = ref([])
const afterSaleForm = reactive({ reason: '', description: '' })
const applying = ref(false)

/**
 * ★ 售后类型【不给用户选】，由订单状态唯一决定。
 * 已付款（未发货）→ 仅退款（货还在仓库，没什么可寄回的）；
 * 已发货 / 已完成 → 退货退款。
 * 后端 checkTypeMatchesStatus 是真正的闸门，这里只是不让用户选一个注定被拒的值。
 */
const afterSaleType = computed(() =>
  applyingOrder.value?.status === ORDER_STATUS.PAID
    ? AFTER_SALE_TYPES.ONLY_REFUND
    : AFTER_SALE_TYPES.RETURN_REFUND,
)

/** 原因下拉从字典反推，避免再抄一份文案 */
const reasonOptions = Object.values(AFTER_SALE_REASONS).map((v) => ({
  value: v,
  label: afterSaleReasonLabel(v),
}))

/** 'YYYY-MM-DD HH:mm:ss' → Date。和 Pay.vue 同一个写法（带空格的格式 Safari 不认） */
function parseTime(s) {
  return new Date(String(s).replace(' ', 'T'))
}

/**
 * 申请期限只作用于【已完成】的订单（其余状态没有 complete_time，没有起点）。
 * 后端给的是绝对时刻，前端不自己算「7 天」—— 见 OrderVO.afterSaleDeadline 的注释。
 */
function isAfterSaleExpired(order) {
  if (!order.afterSaleDeadline) {
    return false
  }
  return Date.now() > parseTime(order.afterSaleDeadline).getTime()
}

/**
 * 这一行能不能申请售后（前端只是体验优化，后端才是不变量）。
 * ⚠️ it.afterSaleNo / it.refunded 都用真值判断 —— 全局 non_null 会把
 * 没值时的整个 key 删掉，写 === null 判断恒为 false，入口永远显示。
 */
function canApplyAfterSale(order, item) {
  if (
    order.status !== ORDER_STATUS.PAID &&
    order.status !== ORDER_STATUS.SHIPPED &&
    order.status !== ORDER_STATUS.COMPLETED
  ) {
    return false
  }
  if (item.afterSaleNo || item.refunded) {
    return false
  }
  return !isAfterSaleExpired(order)
}

function openAfterSale(order, item) {
  applyingOrder.value = order
  applyingItems.value = (order.items || []).filter((it) => canApplyAfterSale(order, it))
  selectedItemIds.value = [item.id]
  afterSaleForm.reason = ''
  afterSaleForm.description = ''
  afterSaleVisible.value = true
}

const selectedSubtotal = computed(() =>
  applyingItems.value
    .filter((it) => selectedItemIds.value.includes(it.id))
    .reduce((sum, it) => sum + Number(it.subtotal || 0), 0),
)

async function submitAfterSale() {
  if (selectedItemIds.value.length === 0) {
    ElMessage.warning('请至少选择一件商品')
    return
  }
  if (!afterSaleForm.reason) {
    ElMessage.warning('请选择申请原因')
    return
  }

  applying.value = true
  try {
    await applyAfterSale({
      orderNo: applyingOrder.value.orderNo,
      orderItemIds: selectedItemIds.value,
      type: afterSaleType.value,
      reason: afterSaleForm.reason,
      description: afterSaleForm.description,
    })
    ElMessage.success('申请已提交，等待商家处理')
    afterSaleVisible.value = false
    load()
  } catch {
    // 失败最常见的原因是「并发重复申请」（1012）—— 那一行已经是「处理中」了，
    // 界面还显示着入口就是错的，所以照样重查一次
    load()
  } finally {
    applying.value = false
  }
}

// ---------------------------------------------------------------------------
// 行内操作
// ---------------------------------------------------------------------------

/**
 * 取消订单。
 *
 * <p>⚠️ 界面上的二次确认和 {@code Pay.vue} 里那句保持一致的说法 ——
 * 「不可恢复」和「库存会释放」是用户需要知道的两件事。
 */
async function handleCancel(order) {
  try {
    await ElMessageBox.confirm(
      '取消后订单不可恢复，占用的库存会释放。确定取消吗？',
      '取消订单',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '再想想' },
    )
  } catch {
    return // 用户点了「再想想」
  }

  acting.value[order.orderNo] = true
  try {
    await cancelOrder(order.orderNo)
    ElMessage.success('订单已取消')
    // ★ 取消成功后有两种可能，两条路都通向「重查」，只是触发方式不同：
    //     回退页码时 → updateQuery 改了 URL → watch(route.query) 触发重查，
    //                  所以这里【不能】再手动 load 一次（会发两次请求）
    //     不退页码时 → 没有任何东西会变，所以得自己 load 一次
    //   backOffIfLastRow() 的返回值就是在回答「走了哪条路」。
    if (!backOffIfLastRow()) {
      load()
    }
  } catch {
    // ★ 失败也要重新拉一次。
    //   失败的原因可能是「别人已经取消了」或者「已经超时被系统取消了」——
    //   这两种情况下界面上还显示着可取消的样子就是错的。问服务端要真相。
    load()
  } finally {
    // ★ 用 delete 而不是赋 false：让这个 key 消失，
    //   对象里不留一堆 false（和 updateQuery 清理空值是同一个洁癖）。
    delete acting.value[order.orderNo]
  }
}

/**
 * 确认收货。
 *
 * <p>⚠️ <b>这个操作不可逆</b> —— 本项目没有「撤销确认收货」。
 * 所以二次确认不是走过场，它是这个操作唯一的后悔机会。
 *
 * <p>★ 给用户看的文案说「交易完成」，<b>不解释库存那件事</b> ——
 * 那是实现细节，用户不需要知道，知道了反而会以为「不还库存是不是有问题」。
 * 技术上的理由写在 {@code api/order.js} 和 {@code Orders.vue} 的注释里，
 * 那是给读代码的人看的，不是给用户看的。
 * <b>注释和界面文案是给两种不同的人读的，别把技术细节漏到界面上。</b>
 */
async function handleComplete(order) {
  try {
    await ElMessageBox.confirm(
      '确认收货后这笔交易就完成了，不能撤销。确定已经收到货了吗？',
      '确认收货',
      { type: 'warning', confirmButtonText: '确认收货', cancelButtonText: '还没收到' },
    )
  } catch {
    return
  }

  acting.value[order.orderNo] = true
  try {
    await completeOrder(order.orderNo)
    ElMessage.success('已确认收货，交易完成')
    // ★ 和「取消订单」完全一样的收尾 —— 这也是【改过一次】的地方。
    //
    //   我最初写的是「把后端返回的那笔订单原地替换掉列表里那一行」，
    //   理由是后端确实返回了完整的订单，能省一次请求。
    //   这个理由在 Pay.vue 那种【单笔订单】的页面上是成立的，
    //   但在这里站不住：
    //
    //   ⚠️ 「确认收货」只出现在【全部】和【已发货】两个 Tab 下。
    //      在「已发货」Tab 里确认收货之后，这一单的状态变成了「已完成」——
    //      **它已经不属于当前这个筛选条件了**，可它还在列表上。
    //      于是页面显示着「已发货」的列表里躺着一行「已完成」，
    //      刷新一下又没了。这不是边缘情况，「已发货」Tab 恰恰是
    //      用户来点确认收货最自然的入口。
    //
    //   ★ 由此得到一条比"能省一次请求"重要得多的规矩：
    //     **一个带筛选的列表，必须始终是那个筛选条件的忠实呈现。**
    //     凡是让某一行变得不再符合筛选条件的操作，都得重新问服务端 ——
    //     因为「这一行现在还符不符合条件」是服务端说了算的判断，
    //     前端自己在本地重算一遍就等于抄了一份筛选逻辑，而那份逻辑
    //     迟早和后端的不一致（这正是 orderStatus.js 开头反复讲的那件事）。
    //
    //   ★ 附带的好处：两个行内操作（取消 / 确认收货）现在形状完全一样，
    //     都是「操作 → 提示 → 可能翻页回退 → 重查」。一条规矩，
    //     而不是两条各说各的。
    if (!backOffIfLastRow()) {
      load()
    }
  } catch {
    load()
  } finally {
    delete acting.value[order.orderNo]
  }
}

// ---------------------------------------------------------------------------

/**
 * ★ URL 变了就重新查 —— 这是唯一的读取入口。
 *
 * <p>{@code immediate: true} 让首次进入页面时也会执行一次，
 * 所以<b>不需要在 onMounted 里再调一次 load</b>
 * （那会导致打开页面发两次请求）。
 *
 * <p>⚠️ 监听整个 {@code route.query} 而不是某几个字段：
 * 只要 URL 上任何一个查询参数变了，就重查一次。
 * 这样将来加一个新筛选条件时，不用记得回来改这个 watch ——
 * 「忘了改这里」的症状是「URL 变了但列表没变」，很难查。
 */
watch(() => route.query, load, { immediate: true })

// ---------------------------------------------------------------------------
// 物流（里程碑 18）
// ---------------------------------------------------------------------------

/**
 * 物流弹窗的状态。★ 这是本页唯一的【只读】弹窗。
 *
 * <p>★ 和售后弹窗（{@code afterSaleVisible}）有一个关键差别：
 * 那个弹窗里有表单、有提交；这个<b>一个字都不能改</b>。
 * 轨迹是管理员在管理端手工录的，会员能做的只有看。
 * 所以模板里不该出现任何输入框 —— 否则用户会试着填它。
 *
 * <p>★ {@code traces} 初始值是空数组而不是 null：弹窗一打开就渲染时间线，
 * {@code v-for} 撞上 null 会白屏。（服务端也保证它永远是数组。）
 */
const logisticsVisible = ref(false)
const logisticsLoading = ref(false)
const logisticsData = ref({ traces: [] })

/**
 * 打开物流弹窗。
 *
 * <p>★ <b>先开弹窗再发请求</b>，并且立刻转圈 —— 否则从点击到弹窗出现
 * 之间有一段空白，用户会以为按钮没生效、再点一次。
 *
 * <p>⚠️ 这里【不】做成「把轨迹塞进订单对象里一起查回来」。理由是
 * 一个有性能代价的：订单列表是分页的（{@code pageSize = 5}），
 * 把轨迹嵌进每一行意味着<b>每翻一页都要把所有订单的轨迹全查一遍</b>，
 * 而其中绝大多数根本没人会点开。轨迹走独立接口之后，
 * 只有真正点开的那一单会产生这次查询。
 *
 * <p>★ 附带好处：列表接口的响应形状没变，所以这一轮
 * {@code test-frontend-contract.py} 里订单列表那一节不用改。
 */
async function openLogistics(order) {
  logisticsData.value = { traces: [] }
  logisticsVisible.value = true
  logisticsLoading.value = true
  try {
    logisticsData.value = await getOrderLogistics(order.orderNo)
  } catch {
    // 提示已在响应拦截器里统一处理。★ 这里【不关】弹窗 ——
    // 关掉的话用户只会看到一个弹窗闪过去，不知道发生了什么。
  } finally {
    logisticsLoading.value = false
  }
}
</script>

<template>
  <div class="page-container orders-page">
    <h1 class="page-title">我的订单</h1>

    <!--
      ★★ el-tabs 用 :model-value 而不是 v-model。

        v-model 会引入【第二个事实来源】：它自己在组件里存一份
        「当前选中哪个 Tab」，而 route.query 也存一份 ——
        正是这个页面开头那段明令禁止的。

        用 :model-value（从 URL 算出来）+ @tab-change（写回 URL）之后，
        选中态完全由 URL 决定，组件没有任何自己的状态。
        用户按浏览器后退键，Tab 会跟着回到上一个状态 ——
        因为它本来就是从 URL 算出来的。
    -->
    <el-tabs
      :model-value="status === null ? 'all' : String(status)"
      class="status-tabs"
      @tab-change="(name) => changeTab(name === 'all' ? null : Number(name))"
    >
      <!--
        ⚠️⚠️ el-tab-pane 的 name 必须是【字符串】。
          :name="0"（数字）和 URL 里读出来的 "0"（字符串）永远不相等，
          症状是「待付款」这个 Tab 永远不高亮 —— 而且不报任何错。
          所以这里统一用字符串（"all" 或 "0"/"1"/...），
          在 @tab-change 里再转回数字。
      -->
      <el-tab-pane
        v-for="t in TABS"
        :key="t.label"
        :label="t.label"
        :name="t.value === null ? 'all' : String(t.value)"
      />
    </el-tabs>

    <div v-loading="loading" class="orders-wrap">
      <el-empty v-if="!loading && orders.length === 0" description="这里还没有订单">
        <el-button type="primary" @click="router.push('/')">去逛逛</el-button>
      </el-empty>

      <!--
        ★ 卡片式布局，所以【不需要「查看详情」按钮】——
          订单号、金额、收货信息、商品明细、状态、时间全在这一屏里。
          多一个"详情"入口只会让用户多跳一次页去看同样的东西。
      -->
      <el-card v-for="o in orders" :key="o.orderNo" shadow="never" class="order-card">
        <div class="card-head">
          <span class="head-no">
            <span class="label">订单号</span>
            <span class="mono">{{ o.orderNo }}</span>
          </span>
          <el-tag :type="orderStatusTagType(o.status)" size="small">
            {{ orderStatusLabel(o.status) }}
          </el-tag>
        </div>

        <div class="card-meta">
          <span>{{ o.createTime }}</span>
          <span v-if="o.payTime" class="muted">支付于 {{ o.payTime }}</span>
          <span v-if="o.shipTime" class="muted">发货于 {{ o.shipTime }}</span>
          <span v-if="o.completeTime" class="muted">完成于 {{ o.completeTime }}</span>

          <!--
            ★★ 里程碑 18：承运商 + 单号 + 「查看物流」入口。

            ★★ 入口放在【单号旁边】，不放在下面的 card-actions 里。
               原因是一个具体的 bug：card-actions 的 v-if 是
               `status === PENDING_PAY || status === SHIPPED` ——
               【已完成的订单根本没有这个区块】。
               把按钮放进 card-actions，货收到之后就再也看不到物流了，
               而那恰恰是最需要它的时候（「到底哪天送到的」）。
               ★ 这是一条可以照抄的判据：**入口的归属看「这件事属于哪个
                 区块的语义」，而不是看「放哪里代码少写两行」。**

            ⚠️ v-if 只能用假值判断，【不能写 === null】：
               后端配了 non_null，没发货时这个 key 会被整个从 JSON 里删掉，
               对消失的 key 取属性得到的是 undefined，`=== null` 恒为 false
               → 入口永远不显示。同一个坑本项目已经踩过 5 次。
          -->
          <span v-if="o.trackingNo" class="muted">
            {{ o.logisticsCompany }} {{ o.trackingNo }}
            <el-button type="primary" link size="small" @click="openLogistics(o)">
              查看物流
            </el-button>
          </span>
        </div>

        <!-- 商品明细。★ 这一屏就能看清「这笔钱花在了什么上」 -->
        <ul class="item-list">
          <!--
            ★★ :key 从 i（下标）改成了 it.id —— 这不是洁癖，是承重的。

              评价按钮的 loading 状态按【明细 id】存（acting[it.id]，
              和本页 acting[order.orderNo] 那条「一行一个的状态就该按行存」
              是同一个规矩）。用下标当 key 的话，列表一旦重排
              （比如评价成功后 load() 拿回一份顺序不同的数据），
              Vue 会按位置复用 DOM，于是状态挂到了【另一行】上。

            ⚠️ 「这次数据没重排」不是理由 —— 重排与否由后端决定。
          -->
          <li v-for="it in o.items" :key="it.id" class="item-row">
            <span class="item-name">
              {{ it.productName }}
              <!--
                ★ 规格文本（{@code sku_spec} 的快照）。里程碑 15 阶段 4 新增。

                ★ 为什么订单里要显示它？因为下单时同一件商品可能占了
                  【两行明细】（「黑色 M」和「白色 L」），
                  只显示商品名的话这两行长得一模一样 ——
                  用户根本没法核对自己到底买的是哪个规格、
                  该评价的是哪一行。

                ⚠️ 这里用 {@code v-if="it.skuSpec"} 而不是判断 null：
                  {@code sku_spec} 是 NOT NULL DEFAULT '' 的列，
                  无规格商品和历史订单都是【空串】—— 空串和缺值在这里
                  是同一件事（都没规格可显示），一个宽松真值判断就够了。

                ⚠️ 顺带对比一下同一个对象里的其他字段，三者的 null 规则
                  完全不同，别记混了（完整版见 OrderItemVO 的注释）：
                    it.skuSpec → 一定是字符串（可能是 ''）
                    it.skuId   → 【可能整个 key 消失】（历史订单 / 孤儿明细）
                    it.reviewId→ 【可能整个 key 消失】（还没评价）
                  所以这个文件里判断可评价用 {@code !it.reviewId}，
                  判断有无规格用 {@code it.skuSpec} —— 都用真值判断是对的。
              -->
              <span v-if="it.skuSpec" class="item-spec">{{ it.skuSpec }}</span>
            </span>
            <span class="item-qty">× {{ it.quantity }}</span>
            <span class="item-sub">¥{{ formatAmount(it.subtotal) }}</span>

            <!--
              ★★ 评价按钮在【明细行】里，不在下面的 card-actions 里。
                这条分界是承重的：

                  card-actions   → 【订单级】的动作（去支付 / 取消订单 / 确认收货）
                  .item-row      → 【明细级】的动作（评价）

                因为一张订单里的两件商品要【分别】评价 ——
                「这一单收货了没有」是订单的属性，
                「这件商品我评过没有」是明细的属性。

              ★ 上面条件里的 COMPLETED 是「确认收货后才能评价」这条业务规则
                在前端的【一半】表达 —— 另一半是「这一行评过没有」。
                ⚠️ 但这【两半都只是体验优化】，后端才是不变量：
                   接口会自己校验资格（见 ReviewFormDialog 的注释）。
                   前端少判一次的症状是「点进去被拒」，不是「越权写进去了」。

              ★ 「不显示禁用按钮，而是不显示」这条规矩在这里的用法：
                没确认收货时按钮整个不出现（这件事还轮不到用户做），
                而【评过之后】显示的是「已评价」标签而不是禁用按钮 ——
                因为评价这件事对那一行【已经永远结束了】，
                给一个禁用的「评价」按钮会让人以为「等会儿还能点」。
            -->
            <span class="item-action">
              <!--
                ★ 售后优先显示状态，不显示入口：正在处理中 / 已退款的行
                  不该再看到「申请售后」。三个字段的 null 规则各不相同
                  （完整版见 OrderItemVO）： afterSaleNo 可能整个 key 消失、
                  afterSaleStatus 跟它同一次 join、refunded 是 Boolean。
              -->
              <template v-if="it.afterSaleNo">
                <el-tag :type="afterSaleStatusTagType(it.afterSaleStatus)" size="small">
                  {{ afterSaleStatusLabel(it.afterSaleStatus) }}
                </el-tag>
              </template>
              <el-tag v-else-if="it.refunded" type="info" size="small">已退款</el-tag>
              <el-button
                v-else-if="canApplyAfterSale(o, it)"
                type="primary"
                link
                @click="openAfterSale(o, it)"
              >
                申请售后
              </el-button>

              <!--
                ⚠️ 是 !it.reviewId 而不是 it.reviewId === null ——
                全局 Jackson 配了 non_null，没评价时这个键【整个不存在】，
                写成 === null 的话判断恒为 false，每一行都会显示「评价」按钮。
                （同一个坑里程碑 11 咬过两次。）
                ★ 退过款的行不再显示评价入口：钱都退了就没有「评价这件商品」的资格。
              -->
              <template v-if="o.status === ORDER_STATUS.COMPLETED && !it.refunded">
                <el-button
                  v-if="!it.reviewId"
                  type="primary"
                  link
                  :loading="acting[it.id]"
                  @click="openReview(it)"
                >
                  评价
                </el-button>
                <el-tag v-else type="info" size="small">已评价</el-tag>
              </template>
            </span>
          </li>
        </ul>

        <div class="card-foot">
          <div class="foot-info">
            <div class="recv">
              收货：{{ o.receiverName }} {{ o.receiverPhone }}
            </div>
            <div class="recv muted">{{ o.receiverAddress }}</div>
            <div v-if="o.remark" class="recv muted">备注：{{ o.remark }}</div>
          </div>
          <div class="foot-amount">
            <!--
              ★★ 件数是【数量之和】，不是【明细行数】。

                items.length 是「这笔订单里有几种商品」，
                quantity 之和才是「一共几件东西」。

                ⚠️ 写成 items.length 是个非常容易犯的错，因为：
                  - 在「每种各买 1 件」的数据上，两个答案【完全一样】——
                    而验收时最常造的就是这种数据，所以它看起来是对的
                  - 它不会报错，只是数字偏小

                真实例子（本页刚做完时就这样）：一笔「1 种商品 × 2 件」的订单
                显示成「共 1 件，合计 ¥19.80」—— 数量说 1、钱说 19.80（= 2 × 9.90）,
                两个数字自己就对不上。而「2 种 × 1 件 + 1 种 × 3 件」
                会显示成「共 2 件」，实际是 4 件。

                ★ 管理端（mall-web 的 order/List.vue）用的是数量之和 ——
                  两个端对【同一笔订单】必须给出同一个件数。
                  这里写错的话，症状就是「客服看到 4 件、顾客看到 2 件」。
            -->
            <span class="label">
              共 {{ (o.items || []).reduce((s, it) => s + (it.quantity || 0), 0) }} 件，合计
            </span>
            <span class="amount">¥{{ formatAmount(o.totalAmount) }}</span>
          </div>
        </div>

        <!--
          ★ 按状态显示动作 —— 【不显示禁用按钮，而是不显示】。

            这条规矩是 Pay.vue 定下的，但那边的方向【相反】：
              已付款  → 取消这件事对这单【不存在】  → 不显示取消按钮
              已超时  → 支付仍然存在于用户的意图里 → 显示但禁用
            判断标准是「这件事还存在于用户的意图里吗」：
              能取消吗？不能 —— 而且永远不能了。所以按钮没有意义。
              能付款吗？能，只是错过了时间。所以按钮该在，只是不能点。
            ⚠️ 两类情况处理方式相反，不要照抄其中一种。
        -->
        <div v-if="o.status === ORDER_STATUS.PENDING_PAY
                  || o.status === ORDER_STATUS.SHIPPED" class="card-actions">
          <template v-if="o.status === ORDER_STATUS.PENDING_PAY">
            <el-button
              type="primary"
              :loading="acting[o.orderNo]"
              @click="router.push('/pay/' + o.orderNo)"
            >
              去支付
            </el-button>
            <el-button :loading="acting[o.orderNo]" @click="handleCancel(o)">
              取消订单
            </el-button>
          </template>

          <!--
            ★ 已付款那一档【没有按钮】—— 用户在等卖家发货，没有任何能做的事。
              这一档不出现在上面的条件里，所以这里只剩「已发货」。
              写一个空的 v-if 分支会让模板读起来更整齐，但那是假的分支：
              没有按钮就是没有按钮。
          -->
          <el-button
            v-if="o.status === ORDER_STATUS.SHIPPED"
            type="primary"
            :loading="acting[o.orderNo]"
            @click="handleComplete(o)"
          >
            确认收货
          </el-button>
        </div>
      </el-card>

      <!--
        ★ 分页器。总数小于等于一页时 EP 会自己隐藏（hide-on-single-page），
          所以不需要手动 v-if —— 让组件自己决定更不容易出错。
      -->
      <el-pagination
        v-if="total > 0"
        class="pager"
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="PAGE_SIZE"
        :current-page="pageNum"
        hide-on-single-page
        @current-change="changePage"
      />
    </div>

    <!--
      ★ 评价弹窗放在【页面级】而不是放进 v-for 的卡片里。

        放进卡片的话，每一张卡片都会渲染一个（隐藏的）弹窗组件 ——
        一页 5 张卡片就是 5 个实例，而同时只有 1 个可能被打开。
        更要紧的是：**放进 v-for 里的组件会被列表重排带着走**
        （load() 之后卡片顺序变了，正在输入的弹窗可能被卸载重建）。

      ★ :order-item 传的是明细对象，弹窗要的是它的 id 和 productName。
      ★ 成功交给 handleReviewSuccess 处理，弹窗自己不管刷新
        （「谁能刷新列表」只有一个答案）。
    -->
    <ReviewFormDialog
      v-model="reviewVisible"
      :order-item="reviewingItem"
      @success="handleReviewSuccess"
    />

    <!--
      ★ 售后弹窗同样放在页面级，理由和上面那段一样。
      ★ 整单退 = 在这里多勾几行，提交时 body 是 orderItemIds: [...]，
        后端一个事务建 N 张售后单（全成或全败）。
    -->
    <el-dialog v-model="afterSaleVisible" title="申请售后" width="480px">
      <el-form label-width="90px">
        <el-form-item label="订单号">
          <span class="plain-text">{{ applyingOrder?.orderNo }}</span>
        </el-form-item>
        <el-form-item label="售后类型">
          <span class="plain-text">{{ afterSaleTypeLabel(afterSaleType) }}</span>
        </el-form-item>
        <el-form-item label="申请商品">
          <el-checkbox-group v-model="selectedItemIds">
            <el-checkbox v-for="it in applyingItems" :key="it.id" :value="it.id">
              {{ it.productName }}
              <span v-if="it.skuSpec">/ {{ it.skuSpec }}</span>
              × {{ it.quantity }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="申请原因">
          <el-select v-model="afterSaleForm.reason" placeholder="请选择">
            <el-option
              v-for="r in reasonOptions"
              :key="r.value"
              :label="r.label"
              :value="r.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="补充说明">
          <el-input
            v-model="afterSaleForm.description"
            type="textarea"
            :rows="2"
            maxlength="255"
            placeholder="选填"
          />
        </el-form-item>
      </el-form>

      <!--
        ★ 这里【不】写「将退 ¥x」—— 退款金额由服务端在退款那一刻算，
          运费退不退取决于之后整单是否退完。前端能确定的只有货款，
          所以文案说「货款」，并说明运费的条件。
      -->
      <p class="refund-tip">
        货款合计：¥{{ formatAmount(selectedSubtotal) }}<br />
        整笔订单的每一件都退款成功时，运费会一并退还。最终金额以商家退款时核算为准。
      </p>

      <template #footer>
        <el-button @click="afterSaleVisible = false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="submitAfterSale">
          提交申请
        </el-button>
      </template>
    </el-dialog>

    <!--
      ============ 物流弹窗（里程碑 18） ============

      ★★ 这是本页唯一的【只读】弹窗：没有表单、没有提交按钮、
         连「复制单号」都没有。轨迹是管理员手工录的，用户只能看。

      ★ 为什么不做「复制单号」：它要多一个 navigator.clipboard 调用，
         而那个 API 在非安全上下文（http 且非 localhost）下会【静默失败】——
         用户点一下，什么都没发生，也没有任何提示。
         收益是省一次拖选，代价是一个「有时候能用」的按钮。不值。

      ★ 弹窗打开时立刻显示 loading（见 openLogistics）——
         因为没有 loading 的空白弹窗看起来就像「这单没有物流」。
    -->
    <el-dialog v-model="logisticsVisible" title="物流信息" width="480px">
      <div v-loading="logisticsLoading" class="logi-body">
        <!--
          ⚠️ 三个字段都用假值判断，【不能写 === null】——
             未发货时它们会被 non_null 从 JSON 里整个删掉。
             ★ 未发货的订单能打开这个弹窗吗？界面上不能（入口是
               v-if="o.trackingNo"），但代码不该依赖「界面上不可能」——
               那样的话一旦有人加了别的入口，这里会显示三个空白行。
        -->
        <div class="logi-head">
          <div class="logi-line">
            <span class="muted">承运商</span>{{ logisticsData.logisticsCompany || '—' }}
          </div>
          <div class="logi-line">
            <span class="muted">单号</span>
            <span class="mono">{{ logisticsData.trackingNo || '—' }}</span>
          </div>
          <div class="logi-line">
            <span class="muted">发货时间</span>{{ logisticsData.shipTime || '—' }}
          </div>
        </div>

        <el-divider />

        <div class="logi-title">物流轨迹</div>

        <!--
          ⚠️ 空轨迹是【正常状态】——刚发货、快递还没揽收就是这样。
             所以这里必须有一句明确的说明，而不是一片空白：
             空白会让人以为「页面坏了 / 还没加载出来」，然后反复刷新。
        -->
        <div
          v-if="!logisticsData.traces || logisticsData.traces.length === 0"
          class="logi-empty"
        >
          暂时没有物流轨迹。快递有进展后，商家会更新在这里。
        </div>

        <!--
          ★ 顺序完全按服务端给的（trace_time DESC, id DESC），前端【不排】——
            重排就是第二个定义者，而且很容易写成「按 id 排」，
            那样商家补录一条昨天的节点会让时间线倒过来。
            ★ 时间线显示的是 traceTime（【发生】的时刻），不是 createTime
              （商家录入的时刻）—— 用户的问题是「我的包裹什么时候到的」。
        -->
        <div v-else class="timeline">
          <div v-for="t in logisticsData.traces" :key="t.id" class="trace-item">
            <div class="trace-main">
              <el-tag :type="logisticsStatusTagType(t.status)" size="small">
                {{ logisticsStatusLabel(t.status) }}
              </el-tag>
              <span class="trace-time mono">{{ t.traceTime }}</span>
            </div>
            <div class="trace-desc">{{ t.description }}</div>
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="logisticsVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.orders-page {
  padding-top: 20px;
  padding-bottom: 40px;
}

.page-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 400;
  color: #333;
}

.status-tabs {
  margin-bottom: 12px;
}

.orders-wrap {
  min-height: 300px;
}

/* 圆角交给 --el-card-border-radius（见 theme.css），这里不重复写 */
.order-card {
  margin-bottom: 14px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--jd-border-light);
}

.head-no {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}

.label {
  color: #999;
  font-size: 12px;
}

.mono {
  font-family: Consolas, Menlo, monospace;
}

.card-meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  margin: 10px 0 0;
  font-size: 12px;
  color: #666;
}

.muted {
  color: #999;
}

.item-list {
  margin: 10px 0 0;
  padding: 10px 0 0;
  list-style: none;
  border-top: 1px dashed var(--jd-border-light);
}

.item-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
  font-size: 13px;
}

.item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * ★ 规格文本。.item-name 是 nowrap 的，所以这个 span 会跟在商品名
 *   后面【同一行】显示 —— "某某T恤  颜色:黑 / 尺码:M"。
 *   样式上只压低颜色，不加 margin-top 之类的换行暗示：
 *   订单明细行本来就窄，多一行会让整行高度不齐。
 *   完整地看完由 .item-name 的 ellipsis 兜底（截断比换行好）。
 */
.item-spec {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}

.item-qty {
  color: #999;
}

.item-sub {
  width: 90px;
  text-align: right;
  color: #333;
}

/*
 * 明细行右侧的操作位（评价按钮 / 已评价标签）。
 *
 * ★ 给一个【固定宽度】而不是让它自适应 ——
 *   评价按钮和「已评价」标签的宽度不一样，不给宽度的话，
 *   同一屏里几行的商品名结尾会参差不齐（看起来像排版坏了）。
 *   这里和 .item-sub 给死 90px 是同一个理由。
 *
 * ⚠️ 它是 flex 项，必须 flex: none —— 否则上面 .item-name 的
 *   flex: 1 + min-width: 0 会把它一起压扁（长商品名时按钮被挤没）。
 */
.item-action {
  flex: none;
  /* ★ 里程碑 17 从 60px 加宽：这一列现在【可能上下堆两样东西】
     （售后的入口/状态 + 评价的入口/状态），60px 装不下「申请售后」四个字。
     用列布局而不是让它撑开宽度 —— 撑开的话同一页里有的行宽有的行窄。 */
  width: 88px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.plain-text {
  color: #606266;
}

.refund-tip {
  margin: 0;
  padding: 8px 10px;
  background: #fafafa;
  border-radius: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
}

.card-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--jd-border-light);
}

.foot-info {
  font-size: 12px;
  line-height: 1.7;
  color: #333;
  min-width: 0;
}

.foot-amount {
  display: flex;
  align-items: baseline;
  gap: 8px;
  white-space: nowrap;
}

/*
 * 金额。★ 用 --jd-red 而不是 EP 的 danger 色 ——
 * 它们在本项目里其实是同一个值（theme.css 里把 danger 也设成了 #e1251b），
 * 但语义不同：这里是「价格」，不是「危险」。
 * （同 Pay.vue 的 .amount。）
 */
.amount {
  color: var(--jd-red);
  font-size: 20px;
  font-weight: 700;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
}

.pager {
  margin-top: 18px;
  justify-content: center;
}

/* ---- 物流弹窗（里程碑 18） ---- */

.logi-head {
  padding: 8px 10px;
  border-radius: 4px;
  background: #fafafa;
}

.logi-line {
  font-size: 13px;
  line-height: 1.9;
  color: #333;
}

/* ★ 给标签一个固定宽度，三行的值才会左对齐 ——
     不给它的话「承运商 / 单号 / 发货时间」三个词不一样宽，
     三行的值参差不齐（看起来像排版坏了）。同 .item-sub 那个 90px。 */
.logi-line .muted {
  display: inline-block;
  width: 64px;
  color: #909399;
}

.logi-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}

.logi-empty {
  color: #909399;
  font-size: 13px;
  line-height: 1.8;
}

.timeline {
  max-height: 300px;
  overflow-y: auto;
}

/* ★ 左边那条竖线是「这是一串按时间排的事件」的唯一视觉线索 */
.trace-item {
  padding: 8px 0 8px 12px;
  border-left: 2px solid var(--jd-border-light);
}

.trace-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trace-time {
  color: #909399;
  font-size: 12px;
}

.trace-desc {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.6;
  color: #333;
  word-break: break-all;
}
</style>
