<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCart } from '@/api/cart'
import { listAddresses } from '@/api/address'
import { createOrderByBuyNow, createOrderFromCart } from '@/api/order'
import { getShopSku } from '@/api/product'
import { useCartStore } from '@/stores/cart'
import { clearIntent, getIntentKey } from '@/utils/checkoutIntent'
import { MAX_QUANTITY_PER_ITEM } from '@/utils/constants'
import { formatAmount } from '@/utils/format'
import AddressFormDialog from '@/components/AddressFormDialog.vue'
import ProductImage from '@/components/ProductImage.vue'

/**
 * 确认订单页 —— 两个下单入口的<b>公共落地页</b>。
 *
 * <h3>★ 一个页面，两种来源</h3>
 *
 * <p>两种下单方式在 URL 上这样区分：
 * <pre>
 *   购物车结算   /checkout?ids=204,311
 *   立即购买     /checkout?skuId=204&amp;quantity=2
 * </pre>
 *
 * <p>★ 里程碑 15 阶段 4：两组 URL 参数里的数字都从商品换成了<b>规格</b>。
 * 参数名 {@code ids} 保持不动（它本来就是中性的），
 * 但 {@code productId} 换成了 {@code skuId} —— 因为立即购买那条路上
 * 服务端就是按 skuId 查的（{@code GET /api/shop/skus/{skuId}}），
 * 名字不改的话下一个人会以为这个数字是商品 id。
 *
 * <p>这里没有写成两个页面（{@code CartCheckout.vue} / {@code BuyNowCheckout.vue}），
 * 因为<b>两个页面的界面几乎完全一样</b>：都是"收货地址 + 商品清单 +
 * 备注 + 提交按钮"。差别只有两处：商品从哪来、提交时调哪个接口。
 *
 * <p>而这两处差别，在页面里就是两个 {@code if}——
 * 一个在 {@code loadLines}（数据从哪来），一个在 {@code submit}（调哪个接口）。
 * 见 {@code OrderSource} 的注释：后端也是这样，八个步骤共用、两处分岔。
 *
 * <p><b>★ 前端和后端的"分岔点"如果完全一致，说明抽象的粒度选对了。</b>
 * 如果前端要分四个岔、后端只分两个，那多半是其中一边把不该合的东西合了。
 *
 * <h3>★ 为什么选中哪些商品要放在 URL 里，而不是用 store 传？</h3>
 *
 * <p>因为 URL 里的东西<b>能活过一次刷新</b>。用户在结算页按 F5，
 * 用 store 传的话数据全没了（store 是内存，刷新即清空），
 * 页面会变成一个空白或者报错。
 *
 * <p>放 URL 里的代价是这些 id 会出现在地址栏里、会被记进浏览器历史。
 * 但这些是<b>用户自己的、非敏感的</b>数据（自己的购物车里的商品 id），
 * 而且服务端本来就会重新校验一遍（是不是自己的购物车、
 * 商品还在不在、库存够不够）——<b>URL 里的值永远只是"线索"，不是"依据"。</b>
 *
 * <p>⚠️ 换个场景结论就不同：收货地址、金额这类信息绝不能这样传。
 * 不是"能不能被改"的问题（反正都要校验），而是"用户看到地址栏里
 * 有自己的手机号会不会不安"的问题。<b>能不暴露的就不暴露。</b>
 *
 * <h3>★ 幂等键在这页上怎么用</h3>
 *
 * <p>全部逻辑在 {@code utils/checkoutIntent.js} 里，这里只负责：
 * 提交前取出键、成功之后清掉它。⚠️ <b>一定要看那个文件的注释</b>——
 * 尤其是"为什么不能只是进页面时生成一个随机数"那一段，
 * 那里记着一个我一开始想错的设计。
 */

const route = useRoute()
const router = useRouter()
const cartStore = useCartStore()

// ---------------------------------------------------------------------------
// 来源解析
// ---------------------------------------------------------------------------

/**
 * {@code 'cart'} 或 {@code 'buyNow'}。
 *
 * <p>用到的判据是 URL 里出现了哪组参数。⚠️ 这里刻意<b>不去读
 * "用户是从哪个页面点过来的"</b>（比如 history.state 或者一个 store 标志位）——
 * 那些东西刷新之后就没了，而 URL 参数一直都在。
 * <b>能被"重新打开一次链接"复现的信息，才是可靠的判据。</b>
 */
const source = ref('cart')

/** 购物车结算：勾选的<b>规格</b> id（来自 URL 的 ids 参数） */
const selectedSkuIds = ref([])

/** 立即购买：单个<b>规格</b>和数量 */
const buyNowSkuId = ref(null)
const buyNowQuantity = ref(1)

/** 结算的商品行（含服务端给的名称、单价、可用性） */
const lines = ref([])
const loading = ref(true)
/** 网络/服务端故障 —— 和"商品没了"是两回事 */
const loadFailed = ref(false)

const addresses = ref([])
const selectedAddressId = ref(null)

const remark = ref('')
const submitting = ref(false)

/** 下单成功后的订单，非 null 就切到结果面板 */
const result = ref(null)

const addressDialogVisible = ref(false)

// ---------------------------------------------------------------------------
// 派生状态
// ---------------------------------------------------------------------------

const items = computed(() => lines.value)

/** 有任何一个商品现在买不了 → 不允许提交 */
const hasUnavailable = computed(() => items.value.some((l) => !l.available))

/**
 * 各明细小计的合计 —— <b>★ 里程碑 17 起它只是「商品合计」，不是「应付」。</b>
 *
 * <p>★ 为什么改了名字以外的东西：这一轮加了运费，而<b>运费规则
 * （固定运费 + 满额包邮的门槛）是后端的配置</b>，前端不知道、也不该抄一份。
 * 所以这个数从「应付金额」降级成了「商品合计」，
 * 页面上写它的那两句话也跟着改了（「共 N 项，商品合计」）。
 *
 * <p>⚠️ 为什么不让前端抄一遍运费规则自己算？
 * 抄了之后，运营改门槛的那一刻起，<b>结算页显示的和实际扣的就是两个数</b>——
 * 而这正是本项目反复在防的「同一事实两份实现」。
 * ★ 正解是加一个 {@code POST /api/shop/orders/preview}（只算不写、
 * 和下单共用同一个算钱函数），本轮不做，记在 README 的已知取舍里。
 *
 * <p>★ 这个缺口为什么可以接受：本项目的下单和付款是<b>两步</b> ——
 * 用户下单后落在收银台（{@code Pay.vue}），那里显示的才是权威金额
 * （{@code total_amount}）并带一行「其中运费」。
 * 「下单前不知道运费」不会导致用户被多扣钱。
 *
 * <p>★ 里程碑 14：返回<b>数字</b>而不是格式化好的字符串 ——
 * 「保留两位小数」在模板里由 {@code formatAmount} 做。
 * 理由和 {@code Cart.vue} 的 {@code selectedAmount} 是同一条
 * （「谁该知道这是一笔钱」），完整论证见 {@code utils/format.js} 开头。
 */
const estimateTotal = computed(() =>
  items.value
    .filter((l) => l.available)
    .reduce((sum, l) => sum + Number(l.subtotal || 0), 0),
)

const selectedAddress = computed(
  () => addresses.value.find((a) => a.id === selectedAddressId.value) || null,
)

const canSubmit = computed(
  () =>
    !submitting.value &&
    items.value.length > 0 &&
    !hasUnavailable.value &&
    !!selectedAddress.value,
)

// ---------------------------------------------------------------------------
// 加载
// ---------------------------------------------------------------------------

function parseIds() {
  const raw = route.query.ids
  if (typeof raw !== 'string' || !raw.trim()) {
    return []
  }
  // ★ 过滤掉不合法的值。URL 是用户能随手改的，
  //   /checkout?ids=abc 不该让页面崩掉，只该让这个 id 被忽略
  return raw
    .split(',')
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isInteger(n) && n > 0)
}

async function loadLines() {
  if (source.value === 'cart') {
    // ★ 只传 id、不传数量给后端 —— 但这里为了【显示】，
    //   需要知道买几件。所以从购物车接口读回来。
    //
    //   ⚠️ 注意这只是"用来显示的数量"。真正下单时前端【不传数量】，
    //   后端会自己去 Redis 读一遍（见 CartOrderDTO）。
    //   所以即使这里显示的和最终下单的差了一件（比如用户在另一个
    //   标签页改了购物车），下单结果也仍然是对的 ——
    //   这正是"前端传得越少越安全"的好处。
    const cart = await getCart()
    const all = cart.items || []
    // ★★ 里程碑 15 阶段 4：这把钥匙从 productId 换成了 skuId。
    //    不换的话，「黑色 S」和「白色 M」在 Map 里会互相覆盖 ——
    //    只剩最后一条，用户勾的两行变成一行，而且是<b>错的那一行</b>。
    //    症状：结算页少了一件商品、金额不对，但一路没有任何报错。
    const map = new Map(all.map((i) => [i.skuId, i]))

    // ⚠️ 勾选的商品可能已经不在购物车里了（用户在别处删了，
    //    或者刚刚下过单被清掉了）。这些 id 直接跳过，
    //    下面 items 为空时会有一个专门的空状态
    lines.value = selectedSkuIds.value
      .map((id) => map.get(id))
      .filter(Boolean)
      .map(toLine)
  } else {
    // 立即购买：服务端没有别的真相来源，只能查这个规格
    //
    // ★★ 里程碑 15 阶段 4：这里从 getShopProductDetail 换成了 getShopSku。
    //    为什么不能继续用商品详情？因为【URL 上只有一个 skuId】，
    //    而商品详情接口要的是 productId —— 拿 skuId 去查商品，
    //    查到的要么是 404，要么（更糟）是<b>另一个商品的详情</b>
    //    （skuId 和 productId 都是自增数字，撞号是必然的）。
    //
    //    ★ 这也解释了为什么后端的接口挂在 /shop/skus/{skuId} 而不是
    //      /shop/products/{id}/skus/{skuId}：调用方手里只有一个 id，
    //      接口就只该要一个 id。
    const p = await getShopSku(buyNowSkuId.value)
    const qty = buyNowQuantity.value
    const stock = p.stock ?? 0

    // ★ 数量还得再挡一道「单件上限」。
    //
    //   商品详情页的选择器已经限到 99 了，但这里是【从 URL 读的】——
    //   用户手改成 ?quantity=500 就绕过那个限制进来了。
    //   不挡的话：库存 500 的商品会让 available 算成 true，
    //   用户点提交，后端返回 1008「最多购买 99 件」。
    //   一次注定失败的请求，本可以在页面上就说清楚。
    //
    //   ⚠️ 这里【不去"自动改成 99"】，而是标记成买不了。
    //   自动改的话，用户以为自己买 500 件，实际下单 99 件 ——
    //   **静默修正用户输入比拒绝更危险**，因为他不会发现。
    const overLimit = qty > MAX_QUANTITY_PER_ITEM
    const notEnough = stock < qty

    lines.value = [
      {
        // ★ 这一行的身份是 skuId（来自 URL）。
        //   ⚠️ ShopSkuVO 里也有 productId，这里【故意不要它】——
        //   结算页是这条链路的最后一屏，用户此刻要做的事是「确认然后提交」，
        //   不是「回商品页逛逛」。加一个能点出去、结果丢掉当前勾选的链接，
        //   只会让人误点。（Cart.vue 那边有这个链接，因为它是购物车。）
        //   **一个字段该不该出现在这一行，看的是这一屏有没有人读它。**
        skuId: p.id,
        name: p.productName,
        // ★ 规格文本。【这一行】必须带上：结算页上用户要核对
        //   「我买的是黑色还是白色」，而立即购买这条路上
        //   这个信息只存在于接口返回里
        specText: p.specText,
        price: p.price,
        quantity: qty,
        // ⚠️ 前端自己乘出来的小计，只用于显示。
        //    JS 的浮点乘法会有误差（9.90 * 3 = 29.700000000000003），
        //    显示时由 formatAmount 收成两位小数 —— 它在 utils/format.js，
        //    里程碑 14 起【这个文件里不再有第二份】；
        //    真正的金额始终由后端用 BigDecimal 算
        subtotal: Number(p.price) * qty,

        // ★★ 这里只判断库存，为什么【不判断 status（是否下架）】？
        //
        //   因为这条查询本身已经带了 `AND p.status = 1`，
        //   而且 ShopSkuVO 里【根本没有 status 字段】——
        //   查询本身就保证了"能查到 ⇒ 在售"。
        //   ★ 里程碑 15 阶段 4：换了接口（getShopProductDetail → getShopSku），
        //     但这条推理一个字都不用改 —— 两个 VO 都没有 status 字段，
        //     两条 SQL 都带 status = 1。这正是「规则只定义一次」的好处：
        //     换的是取数的方式，不是规则本身。
        //   既然服务端已经把这件事挡在外面了，这里再查一遍就是
        //   在一个不可能为假的条件上做判断。
        //
        //   ⚠️ 我一开始确实写了 `p.status === 1`。它不是"多余"，
        //   而是【错的】：status 是 undefined，`undefined !== 1` 恒为真，
        //   于是每一件立即购买的商品都被判成「商品已下架」，
        //   整个立即购买入口一点就废。
        //   这类 bug 的特征是"读代码时看着完全合理"——
        //   因为它访问了一个【这个类型上不存在的字段】，
        //   JS 不报错，只是给你 undefined。
        //
        //   ★ 教训：**写判断之前，先确认这个字段真的存在。**
        //   前端没有类型检查（这个项目没上 TypeScript），
        //   字段名写错的代价就是这种静默的错。
        // ★ 缩略图。cover 本来就在 ShopProductDetailVO 里，
        //   只是以前这里没读它 —— 数据一直在手上，只是没接上。
        cover: p.cover,
        available: !overLimit && !notEnough,
        // ⚠️ 超限的判断要排在库存前面。一件库存 500 的商品被要求买 500 件，
        //   两个条件都成立，但"最多买 99 件"才是用户真正需要知道的原因 ——
        //   说"库存不足"会让他去找一件库存更多的商品，那是白费功夫
        unavailableReason: overLimit
          ? `单次最多购买 ${MAX_QUANTITY_PER_ITEM} 件`
          : notEnough
            ? `库存不足，仅剩 ${stock} 件`
            : '',
        stock,
      },
    ]
  }
}

function toLine(item) {
  return {
    // ★★ 里程碑 15 阶段 4：这个白名单里【新增了 skuId 和 specText】两个字段。
    //
    //   白名单的好处是"加字段要显式写一行"，坏处是
    //   "忘了写就静默丢掉"—— 加一行就补上了，而且下面
    //   cover 的注释里已经记过一次同样的教训（那次漏的是 cover，
    //   症状是结算页只有一行行文字，而数据一直在接口的返回里）。
    //
    //   ⚠️ specText 漏掉的症状更隐蔽：同一件商品的两个规格
    //   在结算页上看起来【一模一样】—— 用户核对的正是这一屏，
    //   而他没有别的办法知道自己选的是黑色还是白色。
    skuId: item.skuId,
    specText: item.specText,
    name: item.name,
    price: item.price,
    quantity: item.quantity,
    subtotal: item.subtotal,
    // ★ 从购物车过来时也要带上缩略图。
    cover: item.cover,
    // ★ available / unavailableReason 都是【后端算好给的】，
    //   前端只负责显示，不在前端猜"为什么不能买"。
    //   前端猜的话，规则要在两个地方各写一遍，迟早不一致 ——
    //   Cart.vue 里也是这么处理的
    available: item.available !== false,
    unavailableReason: item.unavailableReason || '',
    stock: item.stock,
  }
}

async function loadAddresses() {
  addresses.value = await listAddresses()

  // ★ 默认选中谁？规则是：默认地址 > 第一条 > 不选。
  //
  //   为什么不直接不要这个默认选中？因为绝大多数用户只有一个地址，
  //   让他每次下单都点一下选择框是多余的。
  //
  //   为什么不"永远记得上次选的那个"？因为那需要额外的持久化状态，
  //   而"默认地址"这个功能本来就已经表达了用户的意思 ——
  //   **如果用户希望某条被优先选中，他应该把它设为默认。**
  //   再搞一套"上次选的"就是第二个真相来源，会和默认地址打架。
  const preferred = addresses.value.find((a) => a.isDefault === 1) || addresses.value[0]
  selectedAddressId.value = preferred ? preferred.id : null
}

async function load() {
  loading.value = true
  loadFailed.value = false

  try {
    // 并行发出去。两个请求互不依赖，串行等就是白白多等一个来回
    await Promise.all([loadLines(), loadAddresses()])
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 提交
// ---------------------------------------------------------------------------

async function submit() {
  if (!canSubmit.value) {
    return
  }

  submitting.value = true
  try {
    // ★★ 幂等键：把「买什么」交给工具函数，它决定是复用还是新生成。
    //
    //   注意这里是在【点提交时】调的，看起来和"要在进页面时生成"
    //   矛盾 —— 不矛盾，因为真正决定"是不是同一次意图"的是
    //   传进去的 lines（签名），而不是调用时机。
    //   刷新页面后再提交，算出来的签名一样，拿到的就是【同一个键】。
    //   详见 utils/checkoutIntent.js
    // ★★ 里程碑 15 阶段 4：签名里带上的是 skuId，不是 productId。
    //    这是本轮第二隐蔽的一处改动，完整场景见
    //    utils/checkoutIntent.js 的 signatureOf —— 简单说：
    //    只带 productId 的话，「黑色/128G」改成「白色/256G」而数量不变
    //    → 签名不变 → 复用幂等键 → 后端把上一单原样返回。
    //    下单"成功"、跳转"成功"，只是买错了规格。
    const linesForSign = items.value.map((l) => ({
      skuId: l.skuId,
      quantity: l.quantity,
    }))
    const key = getIntentKey(linesForSign)

    const order =
      source.value === 'cart'
        ? await createOrderFromCart(
            // ⚠️⚠️ 这里传的是 skuIds。传成 productId 的后果不是
            //   "什么都没删掉"，而是清理购物车时【删掉用户车里另一行】
            //   —— 详见 api/order.js 里那段说明
            linesForSign.map((l) => l.skuId),
            selectedAddressId.value,
            key,
            remark.value.trim(),
          )
        : await createOrderByBuyNow(
            buyNowSkuId.value,
            buyNowQuantity.value,
            selectedAddressId.value,
            key,
            remark.value.trim(),
          )

    // ★★ 成功后【立刻】清掉这次意图，理由见 checkoutIntent.js 里的说明：
    //    留着它会让用户下一次想再买一份同样的东西时，
    //    被服务端当成重复提交，把上一笔订单还回来 ——
    //    购买没有发生，界面却显示成功。这是比重复下单更坏的 bug
    clearIntent()

    result.value = order
    ElMessage.success('订单提交成功')

    // ★ 购物车结算才需要刷角标：后端在事务提交后把那几种商品
    //   从 Redis 购物车里清掉了（见 OrderServiceImpl 的 afterCommit），
    //   不刷新角标的话右上角的数字还是旧的。
    //
    //   立即购买不碰购物车，所以不用刷 —— 但刷一次也无害。
    //   这里按语义只刷该刷的，免得以后有人以为"立即购买也会清购物车"
    if (source.value === 'cart') {
      await cartStore.refresh(true)
    }
  } catch {
    // 错误提示已由 request.js 弹出。
    //
    // ⚠️ 注意这里【不清幂等键】—— 失败就失败，键留着。
    //   因为失败可能是"库存不足"这种确定性原因（用户改完再提交，
    //   签名变了会自然生成新键），也可能是"响应丢了"这种
    //   不确定原因（那这次提交其实成功了，键必须留着，
    //   用户再点一次才能被识别成重复提交）。
    //   **失败时保留、成功时清除** —— 反过来就正好把幂等保护的
    //   两个场景都毁掉了。
  } finally {
    submitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 其他
// ---------------------------------------------------------------------------

function onAddressSaved() {
  // 新增完地址后重新拉一次，并选中它。
  //
  // ⚠️ 这里没有直接拿 createAddress 返回的新 id 去选中 ——
  //   因为 AddressFormDialog 是"新增/修改"共用的，
  //   它不该知道调用方要不要立刻选中新增的那条。
  //   并且我们确实需要重新拉一次（默认地址的归属可能变了）。
  //   所以：先拉，再把"最后一条"选上。
  //
  //   为什么是最后一条？因为后端把默认地址排在最前、
  //   其余按创建时间倒序 —— 新增的那条一定在最前面或者就是默认。
  //   这个假设不够稳，但退一步说：即使选错了，
  //   用户看到的是【一个真实的、自己的地址】，再点一下即可。
  //   **有瑕疵的便利，比没有便利好；但不能让这个瑕疵涉及安全。**
  const before = addresses.value.length
  loadAddresses().then(() => {
    if (addresses.value.length > before) {
      selectedAddressId.value = addresses.value[0].id
    }
  })
}

function backToCart() {
  router.push('/cart')
}

function goShopping() {
  router.push('/')
}

/**
 * 去收银台。
 *
 * <p>★ 里程碑 9 把它从「查看订单」改成了「去支付」，跳的是
 * {@code /pay/<订单号>} 而不是 {@code /orders}。
 *
 * <p>当时的原因很直接：{@code /orders} 那一步还是 {@code Placeholder.vue}（占位页）。
 * <b>把一个主按钮指向占位页，比没有这个按钮更糟</b> ——
 * 用户点下去期待看到自己的订单，结果看到「功能开发中」，
 * 会开始怀疑刚才那一步到底成功了没有。
 * 而跳收银台是<b>真的能做完一件事</b>的：付款。
 *
 * <p>★★ 里程碑 10 把订单列表做出来了，所以当初留下的那个待办
 * （「再考虑要不要多一个『查看订单』的次要入口」）有了答案：
 * <b>不加。</b> 三条理由：
 *
 * <ol>
 *   <li><b>这个面板是一个「过渡页」，不是目的地。</b>
 *       用户刚下完单，此刻唯一该做的事是付款，而且<b>只有 30 分钟</b>。
 *       这一屏的主按钮是「去支付」，次要按钮是「继续购物」——
 *       两个正好。加第三个会把注意力从那个有时限的动作上分走。
 *       <b>按钮的个数不是越多越好，而是「这一屏要让人做的决定有几个」。</b></li>
 *
 *   <li><b>入口已经存在，再放一个就是重复。</b>
 *       {@code App.vue} 的顶部下拉菜单里就有「我的订单」
 *       （{@code handleCommand} 的 {@code orders} 分支 →
 *       {@code router.push('/orders')}），而且它在<b>每一个页面</b>都能点到。
 *       在结算成功页再放一个，只是把同一条路铺两遍。</li>
 *
 *   <li><b>用户此刻去订单列表没有事可做。</b>
 *       那一单现在只有一个状态：待付款 —— 而处理它需要的是收银台，
 *       不是列表。等他要做的事变成「确认收货」时，他会自己去列表
 *       （那时候列表才是他真正需要的地方）。
 *       <b>「入口该不该出现在这里」，取决于用户在此刻此地的下一个动作，
 *       而不是那个页面存不存在。</b></li>
 * </ol>
 */
function goPay() {
  router.push('/pay/' + result.value.orderNo)
}

// ★ 里程碑 14：这里原来有一个本地的 formatAmount 函数，已经删掉了 ——
//   它和 utils/format.js 那份逐字相同，改成从那里 import（见文件顶部的 import）。
//   为什么当时留了一份、后来又是怎么还的，完整写在 utils/format.js 的开头。

onMounted(() => {
  // 解析来源。放在 onMounted 里而不是写在顶层，
  // 是为了和"加载"在同一个时机完成，避免渲染时用的还是旧值
  //
  // ★ 里程碑 15 阶段 4：判据参数名从 productId 换成 skuId。
  //   这里【必须】跟着改 —— 不改的话立即购买会静默地退化成
  //   「购物车结算」分支（因为 route.query.productId 是 undefined），
  //   然后拿 ids 去购物车里找，一条都找不到，页面显示「没有要结算的商品」。
  //   用户刚点了「立即购买」，看到这句话只会以为系统坏了。
  if (route.query.skuId) {
    source.value = 'buyNow'
    buyNowSkuId.value = Number(route.query.skuId)
    buyNowQuantity.value = Number(route.query.quantity) || 1
    // ⚠️ 数量也得挡一道。URL 是能随手改的，
    //   /checkout?skuId=204&quantity=-3 不该让页面算出个负数金额。
    //   后端 BuyNowDTO 上有 @Min(1) @Max(999)，这里只是让界面别太难看
    if (!Number.isInteger(buyNowQuantity.value) || buyNowQuantity.value < 1) {
      buyNowQuantity.value = 1
    }
  } else {
    source.value = 'cart'
    selectedSkuIds.value = parseIds()
  }

  load()
})
</script>

<template>
  <div class="page-container">
    <h2 class="page-title">确认订单</h2>

    <div v-loading="loading" class="checkout-wrap">
      <!-- ============ 下单成功 ============ -->
      <!--
        ★ 成功之后整页换成结果面板，用户就【没法再点一次提交】了。
          这是幂等之外的第二道保险 —— 幂等键防的是"用户想点第二次"，
          这个界面防的是"用户能点第二次"。两道都要有。
      -->
      <el-card v-if="result" shadow="never" class="result-card">
        <el-result icon="success" title="订单提交成功">
          <template #sub-title>
            <div class="result-lines">
              <div>
                订单号：<span class="mono">{{ result.orderNo }}</span>
              </div>
              <div>
                应付金额：
                <span class="amount">¥{{ formatAmount(result.totalAmount) }}</span>
              </div>
              <div class="muted">
                收货：{{ result.receiverName }} {{ result.receiverPhone }}
              </div>
              <div class="muted">{{ result.receiverAddress }}</div>
            </div>
          </template>
          <template #extra>
            <el-button type="primary" @click="goPay">去支付</el-button>
            <el-button @click="goShopping">继续购物</el-button>
          </template>
        </el-result>

        <!--
          ★ 这一段必须【如实说明】模拟支付的现状。
            用户刚花了一笔"钱"，有权利知道这笔钱到底动没动。
            含糊其辞（只说"订单已创建"）比说实话更让人不安 ——
            他会去查银行短信，然后更困惑。
        -->
        <p class="pay-hint">
          订单已创建，当前状态是「待付款」。
          <b>请点「去支付」完成付款</b> —— 30 分钟内未支付，订单会被自动取消并释放库存。
          这是模拟支付，不会真实扣款。
        </p>
      </el-card>

      <!-- ============ 加载失败 ============ -->
      <el-result
        v-else-if="loadFailed"
        icon="error"
        title="加载失败"
        sub-title="网络或服务端出了点问题"
      >
        <template #extra>
          <el-button type="primary" @click="load">重新加载</el-button>
        </template>
      </el-result>

      <!-- ============ 没有可结算的商品 ============ -->
      <!--
        ★ 这个状态有好几种成因，但对外都是同一件事：
          「没有东西可以结算了」。所以文案写通用的，并给一个出口。
          可能的原因：购物车里那些商品被删了、刚刚下过单被清空了、
          或者用户手改了 URL 里不存在的 id
      -->
      <el-result
        v-else-if="!items.length"
        icon="info"
        title="没有要结算的商品"
        sub-title="购物车里没有这些商品了，可能已经被移除或者下过单"
      >
        <template #extra>
          <el-button type="primary" @click="backToCart">回购物车看看</el-button>
        </template>
      </el-result>

      <!-- ============ 正常结算 ============ -->
      <template v-else>
        <!-- ---- 收货地址 ---- -->
        <el-card shadow="never" class="block">
          <template #header>
            <div class="block-head">
              <span class="block-title">收货地址</span>
              <el-button text type="primary" @click="addressDialogVisible = true">
                新增地址
              </el-button>
            </div>
          </template>

          <el-empty
            v-if="!addresses.length"
            description="还没有收货地址，先添加一个吧"
            :image-size="80"
          >
            <el-button type="primary" @click="addressDialogVisible = true">
              添加收货地址
            </el-button>
          </el-empty>

          <div v-else class="addr-list">
            <!--
              ★ 用 el-radio 而不是 el-select 下拉框。
                有几个地址一眼就能看完，下拉框反而要多点一次才能知道有哪些。
                地址是"选择"不是"搜索"，能平铺就平铺 ——
                **选项少的时候，可见即所得比省空间重要。**
            -->
            <el-radio-group v-model="selectedAddressId" class="addr-group">
              <label
                v-for="addr in addresses"
                :key="addr.id"
                class="addr-option"
                :class="{ active: addr.id === selectedAddressId }"
              >
                <el-radio :value="addr.id">
                  <span class="receiver">{{ addr.receiver }}</span>
                  <span class="phone">{{ addr.phone }}</span>
                  <el-tag
                    v-if="addr.isDefault === 1"
                    type="danger"
                    size="small"
                    effect="plain"
                  >
                    默认
                  </el-tag>
                  <div class="addr-detail">{{ addr.region }} {{ addr.detail }}</div>
                </el-radio>
              </label>
            </el-radio-group>
          </div>
        </el-card>

        <!-- ---- 商品清单 ---- -->
        <el-card shadow="never" class="block">
          <template #header>
            <span class="block-title">商品清单</span>
          </template>

          <!--
            ★★ 里程碑 15 阶段 4：:key 从 l.productId 换成了 l.skuId。
                这既是为了正确性（同一商品的两行不会互相复用 DOM），
                也是【必须的】：失效行的 productId 是 null，
                而 Vue 的 :key 不允许 undefined —— 多行 key 相同会让
                列表的 DOM 复用错乱，改一行显示的是另一行的内容。
                skuId 一定有值（它来自 Redis），见 CartItemVO.skuId 的注释。
          -->
          <div v-for="l in items" :key="l.skuId" class="line">
            <!--
              ★ 缩略图。加它不只是「好看」——
                纯文字的订单清单在结算前这一屏上很难核对：
                用户是靠【看图】确认「我要买的是不是这几件」的，
                名字往往只记住一半。
            -->
            <div class="line-cover">
              <ProductImage :src="l.cover" :alt="l.name" :size="10" />
            </div>
            <div class="line-name">
              {{ l.name }}
              <!--
                ★ 规格文本。里程碑 15 阶段 4 新增。
                  【这一屏尤其需要它】：用户点「提交订单」之前
                  唯一能核对的依据就是这里，而同一件商品的两个规格
                  在名字上是完全一样的（都叫「某某 T 恤」）。

                ⚠️ 用 v-if="l.specText"（宽松真值）覆盖两种"没有规格"：
                  无规格商品是空串，失效行是 null 且 key 消失。
                  两种都不该显示这一行。
              -->
              <div v-if="l.specText" class="line-spec">{{ l.specText }}</div>
              <!-- ★ 买不了的商品【照样列出来】并写明原因，
                   不是悄悄从清单里去掉。理由同 Cart.vue -->
              <div v-if="!l.available" class="line-reason">
                {{ l.unavailableReason || '这件商品现在买不了' }}
              </div>
            </div>
            <div class="line-price">¥{{ formatAmount(l.price) }}</div>
            <div class="line-qty">×{{ l.quantity }}</div>
            <div class="line-subtotal">¥{{ formatAmount(l.subtotal) }}</div>
          </div>

          <div class="total-row">
            <!--
              ★ 里程碑 15 阶段 4：量词从「种商品」改成了「项」。
                因为 items 现在是【规格行】不是商品行 ——
                一件 T 恤的「黑色 S」和「白色 M」算两项，
                说成「共 2 种商品」是错的（只有一种商品）。
                ★ 量词错了不是小事：它会让用户以为自己买重了。
            -->
            <!--
              ★★ 里程碑 17：这句文案改了，「合计」→「商品合计」。
                改字不是措辞问题 —— 以前这个数【就是】应付金额，
                加了运费之后它不再是一个订单最终要付的钱了。
                留着「合计」两个字，这一页就在说一句假话，
                而且用户要到收银台才发现多出 10 元。
                ★ 「按规则计算」那半句也不能删：用户有权知道
                  还有一个数没算进来，而不是以为这就是全部。
            -->
            共 <b>{{ items.length }}</b> 项，商品合计：
            <span class="amount">¥{{ formatAmount(estimateTotal) }}</span>
            <div class="freight-hint">运费按下单时的规则计算（满额包邮）</div>
          </div>
        </el-card>

        <!-- ---- 备注 ---- -->
        <el-card shadow="never" class="block">
          <template #header>
            <span class="block-title">订单备注</span>
          </template>
          <el-input
            v-model="remark"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="选填。比如：工作日送、放前台（255 字以内）"
          />
        </el-card>

        <!-- ---- 提交栏 ---- -->
        <div class="submit-bar">
          <div class="submit-left">
            <el-button text @click="backToCart">
              {{ source === 'cart' ? '← 回购物车改一改' : '← 返回' }}
            </el-button>
            <span v-if="hasUnavailable" class="warn">
              有商品现在买不了，请回购物车处理后再结算
            </span>
            <span v-else-if="!addresses.length" class="warn">请先添加收货地址</span>
          </div>

          <div class="submit-right">
            <!--
              ★★ 里程碑 17：这里的「应付」改成了「商品合计」。
                这是全页最容易被漏掉的一处 —— 它长得像一个可以不管的按钮文案，
                但它【原来是一句关于钱的承诺】：用户读到「应付 ¥99」就会以为要付 99。
                ★ 权威数字在下单之后的收银台（Pay.vue 读 total_amount + 运费那一行）。
            -->
            <div class="total">
              商品合计：<span class="amount">¥{{ formatAmount(estimateTotal) }}</span>
              <div class="freight-hint">不含运费，下单后按规则计算</div>
            </div>
            <el-button
              type="danger"
              size="large"
              :disabled="!canSubmit"
              :loading="submitting"
              @click="submit"
            >
              提交订单
            </el-button>
          </div>
        </div>
      </template>
    </div>

    <AddressFormDialog
      v-model="addressDialogVisible"
      :address="null"
      @saved="onAddressSaved"
    />
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 18px;
  font-size: 20px;
  color: #303133;
}

.checkout-wrap {
  min-height: 300px;
  /* 给 sticky 的提交栏留出空间，否则滚到底会被它挡住最后一块内容 */
  padding-bottom: 8px;
}

.block {
  /* 圆角交给 --el-card-border-radius，见 theme.css */
  margin-bottom: 16px;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.block-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

/* ---- 地址选择 ---- */
.addr-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.addr-option {
  display: block;
  padding: 10px 12px;
  border: 1px solid var(--jd-border);
  border-radius: var(--jd-radius);
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}

/* 悬停只是淡淡地提示「这里可以点」，不抢选中态的视觉重量 */
.addr-option:hover {
  border-color: var(--jd-red-border);
}

/* 选中态：整块边框变色，让"当前选的是哪个"一眼可见 */
.addr-option.active {
  border-color: var(--jd-red);
  background-color: var(--jd-red-bg);
}

.addr-option :deep(.el-radio) {
  height: auto;
  align-items: flex-start;
  width: 100%;
}

.addr-option :deep(.el-radio__label) {
  width: 100%;
  line-height: 1.5;
}

.receiver {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-right: 10px;
}

.phone {
  font-size: 13px;
  color: #606266;
  margin-right: 10px;
}

.addr-detail {
  margin-top: 4px;
  font-size: 13px;
  color: #909399;
  word-break: break-all;
}

/* ---- 商品清单 ---- */
.line {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--jd-border-light);
}

.line:last-of-type {
  border-bottom: none;
}

/* 缩略图。★ 外层容器必须给死宽高：img 自己写的是 width/height 100%，
   容器不定义尺寸的话它会塌成 0（和首页 .product-cover 同一个道理） */
.line-cover {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  border: 1px solid var(--jd-border-light);
  border-radius: var(--jd-radius);
  overflow: hidden;
  background-color: #f7f7f7;
}

.line-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.line-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #303133;
  line-height: 1.5;
}

/* ★ 规格文本。和 Cart.vue 的 .row-spec 同一套配色与截断策略 ——
   两个页面在用户眼里是"同一条流程的两步"，样式不该各走各的 */
.line-spec {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-reason {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}

.line-price {
  width: 100px;
  text-align: right;
  font-size: 14px;
  color: #606266;
  flex-shrink: 0;
}

.line-qty {
  width: 70px;
  text-align: center;
  font-size: 14px;
  color: #606266;
  flex-shrink: 0;
}

.line-subtotal {
  width: 110px;
  text-align: right;
  font-size: 15px;
  font-weight: 600;
  color: var(--jd-red);
  flex-shrink: 0;
}

.total-row {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--jd-border-light);
  text-align: right;
  font-size: 14px;
  color: #606266;
}

.total-row b {
  color: var(--jd-red);
}

.amount {
  font-size: 20px;
  font-weight: 700;
  color: var(--jd-red);
}

/*
 * ★ 里程碑 17：运费那一句提示。
 * 刻意做得比 .amount 明显轻 —— 它是一个「还有一个数没算进来」的说明，
 * 不是要用户去读的金额。轻一点才不会被误读成「运费 ¥10」。
 */
.freight-hint {
  font-size: 12px;
  font-weight: 400;
  color: #999;
  margin-top: 2px;
}

/* ---- 提交栏 ---- */
.submit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 20px;
  background-color: #fff;
  border: 1px solid #ebeef5;
  /* 和 Cart.vue 的结算栏一样吸在底部 —— 下单页更长，更需要 */
  position: sticky;
  bottom: 0;
}

.submit-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.warn {
  font-size: 13px;
  color: #e6a23c;
}

.submit-right {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-shrink: 0;
}

.total {
  font-size: 14px;
  color: #606266;
}

/* ---- 结果面板 ---- */
/* .result-card 的圆角交给 --el-card-border-radius（见 theme.css），
   这里不再写任何东西 —— 留着一条空规则只会让人以为这里该有样式 */

.result-lines {
  font-size: 14px;
  color: #606266;
  line-height: 2;
}

.result-lines .muted {
  font-size: 13px;
  color: #909399;
}

.mono {
  font-family: Consolas, Menlo, monospace;
  color: #303133;
}

.pay-hint {
  margin: 0 0 8px;
  text-align: center;
  font-size: 13px;
  color: #909399;
}
</style>
