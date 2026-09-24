<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getShopProductDetail } from '@/api/product'
import { listProductReviews } from '@/api/review'
import ProductImage from '@/components/ProductImage.vue'
import { useCartStore } from '@/stores/cart'
import { useUserStore } from '@/stores/user'
import { MAX_QUANTITY_PER_ITEM } from '@/utils/constants'
import { formatAmount } from '@/utils/format'

/**
 * 商品详情页。
 *
 * <h3>★ 这个页面要处理的核心问题：商品不存在 / 已下架怎么办？</h3>
 *
 * <p>后端对这两种情况返回的是同一个业务码 1003、同一句「商品不存在或已下架」。
 * 前端拿到之后<b>不能只是弹个红色错误提示就完了</b> ——
 * 因为这个页面的地址是会被收藏、会被分享、会出现在浏览器历史里的，
 * 半年后用户从收藏夹点进来，看到商品下架了，这是<b>完全正常</b>的事情，
 * 不是故障。
 *
 * <p>所以这里的处理是：<b>把整个页面换成一个说明性的空状态</b>，
 * 告诉他「这个商品可能已经下架了」，再给一个「去逛逛别的」的出口。
 * 不弹错误提示 —— 用户没做错任何事，不该被红色警告。
 *
 * <p>这个区分很重要，可以推广：
 * <pre>
 *   用户【做了什么不该做的】  → 弹错误提示（比如密码错了）
 *   用户【什么也没做错】      → 给一个友好的空状态（比如页面内容没有了）
 * </pre>
 * 把后者当成前者，产品会显得很凶。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

const product = ref(null)
const loading = ref(true)
/** 商品不存在或已下架 —— 和「网络出错」是两回事，要分开显示 */
const notFound = ref(false)
/** 网络 / 服务端出错 */
const loadFailed = ref(false)

// ★ 里程碑 15 阶段 3：「商品还有没有货」这个判断从这里【搬走了】。
//
//   它原来只是一句话：product.stock <= 0。
//   有了 SKU 之后「有没有货」不再是商品级的属性 —— 它属于
//   「用户当前选中的那个组合」，而商品级只剩一个跨规格的合计。
//   所以判断搬到了下面的规格选择器那一块（见 blockReason）。
//
//   ⚠️ 这一段留着是为了让「soldOut 去哪了」有个答案：
//      它没有消失，是【换了主语】。

/**
 * 数量选择器。
 *
 * <p>{@code el-input-number} 的 max 绑的是库存，看起来是个校验 ——
 * 但要清楚<b>它拦不住任何东西</b>：用户可以直接改 URL、
 * 直接调接口，或者就是点得比库存扣减慢了一步。
 * 真正的并发安全必须靠后端下单时的行锁 + 库存校验。
 * 这里的 max 纯粹是让用户少犯错（少一次「提交后才知道库存不够」的挫败）。
 */
const quantity = ref(1)

/**
 * 当前主图是图集里的第几张（里程碑 11 新增）。
 *
 * <p>★ 这个状态【必须】在 {@code loadProduct} 里重置，和上面的
 * {@code quantity.value = 1} 放在一起 —— 理由完全一样，而且后果更难看。
 *
 * <p>因为这个组件在路由参数变化时会被<b>复用</b>（见下面那个 watch）：
 * 从「有 5 张图、正看第 5 张」的 A 商品点进「只有 2 张图」的 B 商品，
 * 索引还停在 4 → 越界 → 主图拿到 {@code undefined} →
 * {@code ProductImage} 安静地退回「暂无图片」。
 *
 * <p>⚠️ <b>它不会报错</b>，看起来就像「这个商品没有图」——
 * 一个数据明明没问题的商品，被显示成了没图。
 */
const currentIndex = ref(0)

/**
 * 主图要显示的那一张。
 *
 * <h3>★★ 「图集为空就退回封面图」这一句是【承重】的，不是兼容代码</h3>
 *
 * <p>库里现有的 40 多个商品<b>一个图集都没有</b> —— 图集是里程碑 11
 * 才有的概念，老数据不可能有。它们必须照旧正常显示主图。
 *
 * <p>所以这不是「为了兼容旧数据而写的兜底」，而是
 * <b>「没把现在能用的东西弄坏」</b>。这两件事的区别在于：
 * 前者可以将来删掉，后者不能 —— 只要还有商品没上传图集，
 * 这条链就必须在。
 *
 * <p>用 {@code cover} 当唯一那一张，同时也让下面的缩略图条
 * 自然消失（只有一张图时列一排缩略图很傻，模板里用
 * {@code images.length > 1} 判了）。
 */
const gallery = computed(() => {
  const images = product.value?.images
  if (images && images.length) {
    return images
  }
  // 老商品 / 没有图集的商品：退回封面图。
  // ⚠️ 用 ?. 和 filter，兜住「连 cover 都没有」和「图集里有空串」两种情况 ——
  //   ProductImage 自己也会兜底，但让它少兜一层，主图的取值链更清楚
  return product.value?.cover ? [product.value.cover] : []
})

const currentImage = computed(() => gallery.value[currentIndex.value] ?? product.value?.cover)

/** 切主图。点当前那张不用做什么，但也不用特意拦 —— 赋值是幂等的 */
function selectImage(index) {
  currentIndex.value = index
}

// ---------------------------------------------------------------------------
// 规格选择器（★ 里程碑 15 阶段 3）
// ---------------------------------------------------------------------------
//
// ★★ 这一页之前的每一个显示都建立在「一件商品有一个价格、一个库存」上。
//    里程碑 15 之后那句话不再成立：product 表上已经没有价格也没有库存了，
//    价格和库存住在 product_sku 的每一行上。
//
//    所以下面这些 computed 回答的问题从「这件商品多少钱、还有几件」
//    变成了「用户【当前选中的那个组合】多少钱、还有几件」。
//
// ⚠️ 而「当前选中哪个组合」是一个【用户可能还没回答】的问题。
//    页面必须能诚实地表达「还没有答案」，不能用一个个差不多的数
//    （最低价、总库存）去充当答案 —— 那些数在界面上长得和真答案
//    一模一样，而它们是错的。

/** 规格定义，形如 {@code [{name:'颜色',values:['黑','白']}]}；无规格商品是空数组 */
const specGroups = computed(() => product.value?.specSchema ?? [])

/**
 * 用户选了哪些值，形如 {@code { 颜色: '黑', 尺码: 'S' }}。
 *
 * ★ 换商品时【必须重置】（见 loadProduct），理由和 quantity / currentIndex 一样，
 *   而且这一处的症状最像「系统坏了」：组件被复用之后，
 *   A 商品选的「颜色: 黑」会跟着到 B 商品 —— 如果 B 恰好也有
 *   「颜色」这一维、也有「黑」这个值，用户就会看到一个自己从没点过的
 *   选项亮着，而下面的价格是那个组合的价格。
 */
const selected = ref({})

/** 全部规格行。用 ?? [] 兜住字段缺失（后端配了 non_null，空数组正常返回、null 才整个消失） */
const skuList = computed(() => product.value?.skus ?? [])

/**
 * 每一维是不是都选过了。
 *
 * <p>⚠️ <b>无规格商品恒为 {@code true}</b> —— 它没有任何维度，
 * 而「空集合上的每一维都选过」为真。这不是一个需要特判的边界，
 * 而是让下面的逻辑统一的那一块基石：无规格商品走的是和
 * 「已经选好了规格」完全相同的那条路，所以价格、库存、数量上限
 * <b>都不需要写第二份</b>。这就是「统一模型」在前端的落点。
 */
const selectedComplete = computed(() =>
  specGroups.value.every((g) => selected.value[g.name]),
)

/**
 * 当前选中的那一条 SKU；还没选全、或那个组合不存在时是 null。
 *
 * <h3>★★ 匹配用的是【逐项比较】，不是「把规格拼成一个字符串再比」</h3>
 *
 * <p>拼字符串的写法更短，而且看起来顺手就能对上：
 * <pre>
 *   const key = Object.entries(selected).sort().map(([k, v]) =&gt; `${k}=${v}`).join('&amp;')
 *   const sku = skuList.value.find((s) =&gt; s.specKey === key)
 * </pre>
 * 后端的 {@code SpecJson.canonical()} 恰好就是这么拼的，所以让后者
 * 也吐一个 {@code specKey} 出来，「两端约定好怎么拼」似乎就成立了。
 *
 * <p><b>但它有一个致命的坏处：点击顺序会变成结果的一部分。</b>
 * 「先点颜色再点尺码」和「先点尺码再点颜色」拼出两个不同的字符串
 * （除非每一处都记得 sort，而那只把问题推到了「谁忘了 sort」）。
 *
 * <p>⚠️ 而对不上时的症状是<b>完全静默</b>的：选择器永远匹配不到任何 SKU，
 * 价格显示成起售价、按钮一直灰着、<b>没有任何报错、也没有任何请求发出</b>。
 * 用户只会觉得「这个商品坏了」，而开发者看到的是一片正常。
 *
 * <p>★ 逐项比较天然与顺序无关：它比的是「每个规格名的值对不对」，
 * 而不是「这个对象被序列化成了什么」。
 */
const currentSku = computed(() => {
  if (!selectedComplete.value) {
    return null
  }
  if (!specGroups.value.length) {
    // 无规格商品：恰好一条 spec_json = '[]' 的默认 SKU，直接就是它
    return skuList.value.length === 1 ? skuList.value[0] : null
  }
  return (
    skuList.value.find(
      (sku) =>
        sku.specs.length === specGroups.value.length &&
        sku.specs.every((x) => selected.value[x.name] === x.value),
    ) ?? null
  )
})

/**
 * 所选规格的库存。
 *
 * <p>⚠️⚠️ <b>【必须】用所选 SKU 的库存，不能用列表接口那个 totalStock。</b>
 * 它是跨规格的合计，回答的是「这件商品一共还剩几件」——
 * 而那不是一个用户买得到的数量：总库存 10 件分散在 4 个规格上、
 * 每个规格 2~3 件，用户选哪一档都买不到 10 件。
 * 拿它当数量上限，用户能选到一个加购必然失败的数字。
 *
 * <p>没选规格时是 0；下面 {@code maxQuantity} 的兜底会把它抬到 1。
 */
const skuStock = computed(() => currentSku.value?.stock ?? 0)

/**
 * 数量选择器的上限：<b>所选规格的库存</b>和单件上限取小的那个。
 *
 * <p>只绑库存是不够的。库存 500 的规格，用户能一路选到 200，
 * 然后加购被后端压到 99（业务码 1008）、立即购买直接报
 * 「最多购买 99 件」—— 用户看着库存写着 500，会以为系统坏了。
 *
 * <p>常量的来历和"抄过来过时了怎么办"，见 {@code utils/constants.js}
 */
const maxQuantity = computed(() =>
  Math.max(
    Math.min(skuStock.value, MAX_QUANTITY_PER_ITEM),
    1, // ⚠️ 兜底成 1：库存 0 时 el-input-number 的 max 是 0，
       //   而 min 是 1，min > max 会让组件行为变得奇怪。
       //   按钮那时本来就是禁用的，所以这个 1 不会被用到
  ),
)

/**
 * ★★ 换规格之后，把数量夹回新规格的库存以内。
 *
 * <p>这条 watch 守的是一条不变量：<b>{@code quantity &lt;= maxQuantity} 恒成立。</b>
 *
 * <p>不守会怎样（<b>实测过</b>，不是推演）：在「黑/S，库存 5」那一档把数量加到 5，
 * 然后切换到「黑/M，库存 2」——数量<b>还是 5</b>，而页面上写着「库存 2 件」，
 * 「加入购物车」也是亮着的。用户点下去，请求带着 5 件发出去。
 *
 * <p>⚠️ 为什么 {@code el-input-number} 自己没兜住？因为它<b>只在
 * modelValue 和 precision 变化时</b>重新校验，而 {@code max} 变了它不管 ——
 * 所以「上限调小」这件事不会触发任何夹取。上限调小时加号会变灰
 * （这一点组件是对的），但<b>已经填进去的那个数会留在原地</b>。
 *
 * <p>★ 为什么用 watch 而不是在 {@code addToCart} 里夹一下？
 * 因为在提交前夹，用户看到的那个数是错的 —— 页面写着 5、实际买 2，
 * 是「能选、能点、加不进去」的典型症状。修复的位置必须让
 * <b>显示</b>也跟着对，所以它只能发生在数量被渲染出来之前。
 *
 * <p>★ 夹取（而不是重置成 1）：用户表达了「我要 5 件」这个意图，
 * 新规格只买得到 2 件时，把意图保留到上限比丢弃它更接近他的意思。
 * 购物车那边也是这个口径（数量超限时压到上限而不是清零）。
 */
watch(maxQuantity, (max) => {
  if (quantity.value > max) {
    quantity.value = max
  }
})

/**
 * 价格区显示的那个数。
 *
 * <p>★ 选中了规格 → 那个规格的确切价格；还没选 → 起售价（所有规格里最低的）。
 *
 * <p>「还没选的时候显示什么」有一个错误答案和一个正确答案：
 * <pre>
 *   错误：随便挑一个规格的价格 → 用户会以为那就是他要买的价格
 *   正确：起售价 + 一个「起」字 → 明确告诉用户这只是个下界
 * </pre>
 * 后端给这个字段起名 {@code minPrice} 而不是 {@code price}，
 * 就是为了让上面这个区分在代码里也看得见。
 */
const priceValue = computed(() => currentSku.value?.price ?? product.value?.minPrice ?? null)

/**
 * 要不要在价格后面跟一个「起」字。
 *
 * <p>★ 判据是「<b>所有规格的价格不全一样</b>」，不是「规格数 &gt; 1」——
 * 4 个规格都卖 10 元时写「¥10.00 起」是在暗示还有更贵的，而实际上没有。
 * <b>一个永远为真的修饰语等于没有信息</b>，和模板里
 * 「库存超过 99 才提示单次限购」是同一个判断。
 *
 * <p>⚠️ 选中规格之后<b>不再显示</b>「起」：那时显示的是确定的价格。
 */
const priceIsFrom = computed(() => {
  const skus = skuList.value
  if (!skus.length) {
    return false
  }
  let min = null
  let max = null
  for (const s of skus) {
    const p = Number(s.price)
    if (min === null || p < min) {
      min = p
    }
    if (max === null || p > max) {
      max = p
    }
  }
  return min !== max
})

const showFrom = computed(() => !currentSku.value && priceIsFrom.value)

/**
 * 按钮为什么不能点；空串表示可以买。
 *
 * <h3>★★ 这里刻意分成三句话，而不是一句「暂时无法购买」</h3>
 *
 * <pre>
 *   还没选规格        → 「请选择规格」        —— 用户【能做点什么】
 *   选了但那个规格没货 → 「该规格暂时缺货」    —— 用户【可以换一个规格】
 *   无规格商品没货    → 「该商品暂时缺货」    —— 用户【什么也做不了】
 * </pre>
 *
 * <p>「商品级售罄」和「规格级缺货」是两种不同的状态。
 * 把它们混成一句话，最直接的损失是：<b>用户在「换个规格就能买」的时候
 * 直接离开了</b> —— 界面上告诉他这件商品没了，而它其实还在卖。
 *
 * <p>★ 同时也是这一页「按钮为什么禁用」的<b>唯一</b>判据：
 * 三个按钮和数量选择器全都读它，所以不存在
 * 「按钮灰着但提示说的是另一件事」这种不一致。
 */
const blockReason = computed(() => {
  if (!selectedComplete.value) {
    return '请选择规格'
  }
  if (skuStock.value <= 0) {
    return specGroups.value.length ? '该规格暂时缺货' : '该商品暂时缺货'
  }
  return ''
})

/** 选一个规格值。再点一次当前值不用做什么 —— 赋值是幂等的 */
function selectSpec(name, value) {
  if (!isValueAvailable(name, value)) {
    return
  }
  selected.value = { ...selected.value, [name]: value }
}

/**
 * 某个规格值在当前数据下是不是【可选】的。
 *
 * <p>★ 只做<b>静态</b>可选性：这个值在任何一条 SKU 里都没出现过 → 标灰。
 * 「动态可选性」（选完颜色之后某些尺码跟着变灰）明确不做，理由见下。
 *
 * <p>⚠️ <b>诚实地说：靠管理端保存出来的数据不会触发这个判断。</b>
 * Service 有一条规则「skus 的条数必须等于各维取值数之积」，
 * 所以每一个声明过的值都必然出现在某条 SKU 里。
 * 它守着的是数据<b>漂移</b> —— 比如将来有人写了另一条路径去改
 * {@code spec_schema} 却没有同步 SKU 行。那时候用户至少能看出
 * 「这个值点了没用」，而不是点进去发现价格和库存全是空的。
 *
 * <p>★ 为什么不做动态可选性：那要在每次点击时重算
 * 「剩下每一维还有哪些值能凑出一个真实存在的组合」，
 * 也就是在这个函数里再跑一次叉乘。以本案的规格规模
 * （≤3 维 × ≤10 值 × ≤60 组合）算得动，但它引入的是一个
 * <b>新的交互约定</b>（点了一个值之后别的值会变），
 * 而这一轮的目标是让「一件商品多个规格」这件事先正确地存在。
 * 记在这里，不顺手做。
 */
function isValueAvailable(name, value) {
  return skuList.value.some((sku) =>
    sku.specs.some((x) => x.name === name && x.value === value),
  )
}

/**
 * 拉商品详情。
 *
 * @param {object} [options]
 * @param {boolean} [options.keepSelection] 保留用户已经选好的规格和数量。
 *   <b>只有</b>「加购失败之后刷新库存」那一条路会传它 ——
 *   见 addToCart 里的说明。换商品时<b>必须</b>走默认的「全部重置」。
 */
async function loadProduct({ keepSelection = false } = {}) {
  loading.value = true
  notFound.value = false
  loadFailed.value = false

  // ★★ 换商品时要重置三样东西：数量、主图索引、选中的规格。
  //
  //   三样的理由是同一条（组件会被复用，A 商品的状态跟着到 B 商品），
  //   但后果一种比一种隐蔽：
  //     数量     → 用户看到数量是 3 而不是 1，一眼就知道不对劲
  //     主图索引 → 索引越界，主图安静地退回「暂无图片」（见 currentIndex）
  //     选中的规格 → 更安静：只要 B 商品也有「颜色」这一维、
  //                  也有「黑」这个值，那个选项就会亮着，
  //                  而价格显示的是那个组合的价格 ——
  //                  【页面上没有任何一处看起来是错的】
  if (!keepSelection) {
    quantity.value = 1
    currentIndex.value = 0
    selected.value = {}
  }

  const id = route.params.id

  try {
    product.value = await getShopProductDetail(id)
  } catch (err) {
    product.value = null

    // ★ 怎么区分「商品不存在」和「网络出错」？
    //
    //   判断依据是 err.isBusinessError（由 request.js 挂上）：
    //     有它 → 后端【答复了】，只是业务上说不行（商品不存在 / 已下架）
    //     没它 → 请求根本没走通（网络断了、超时、502…），是真正的故障
    //
    //   为什么不看 HTTP 状态码？因为按本项目的约定，
    //   业务结果一律是 HTTP 200 + body.code，
    //   「商品不存在」的 HTTP 状态码也是 200 —— 看不出来。
    //
    //   ⚠️ 这里曾经有个 bug，值得记下来：
    //   原来判断的是 err.response，想法是「拿到了响应体就说明后端答复了」。
    //   但 request.js 那时 reject 的是裸的 new Error(message)，
    //   而这个 error 上【没有】response 属性（它不是 axios 抛的），
    //   于是「商品不存在」被判成了「网络出错」，
    //   用户看到一个收藏已久的商品下架了，却被告知「服务端出了点问题，请稍后重试」——
    //   一件再正常不过的事被说成了故障，还让他去重试一件永远不会成功的事。
    //
    //   教训：**判断条件要盯着「你真正想知道的那个事实」，
    //   而不是一个碰巧相关的旁证。** 「有没有 response」
    //   和「后端有没有答复」在当时的实现下恰好不等价，
    //   而代码按「应该等价」来写 —— 这种 bug 读代码时很难发现，
    //   因为它看起来完全合理。
    if (err?.isBusinessError) {
      notFound.value = true
    } else {
      loadFailed.value = true
    }
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 商品评价（★ 里程碑 12）
// ---------------------------------------------------------------------------

const REVIEW_PAGE_SIZE = 5

const reviews = ref([])
/** 评价列表的总条数 —— 给分页器用。★ 它和上面「商品评价 (N)」那个 N 的来源不同，见模板里的注释 */
const reviewTotal = ref(0)
const reviewLoading = ref(false)

/**
 * 评价列表的当前页码。
 *
 * <h3>★★ 这是本页对「用户端筛选进 URL」惯例的一处【刻意例外】</h3>
 *
 * <p>本工程立下的规矩是「<b>能被分享出去的状态才进 URL</b>」
 * （见 {@code Home.vue} 的分类 / 搜索、{@code Orders.vue} 的订单状态）。
 * 而评价页码两条都不满足：
 *
 * <p>1. <b>进 URL 会引入一个真实的 bug。</b>商品详情页的主体由
 * <b>路径参数 {@code :id}</b> 决定，而评价翻页只是页面内部的次要区块。
 * 如果写成 {@code ?reviewPage=3}，那么从「评价有 3 页的商品 A」
 * 跳到「商品 B」时，URL 里的 {@code reviewPage=3} 会跟着过去 ——
 * B 的评价列表直接从第 3 页开始，很可能是一片空白，
 * 而用户根本没做过「翻到第 3 页」这个动作。
 * <b>这正是本轮 {@code currentIndex}（图集选中项）那个坑的翻版</b>，
 * 那次的结论就是「换商品时必须重置」。
 *
 * <p>2. <b>分享一个商品链接带上 {@code ?reviewPage=3} 没有任何意义。</b>
 * 别人打开只会看到商品本身，评价在第几页并不是他想表达的东西。
 *
 * <p>★ 所以它是一个纯粹的组件内状态：<b>换商品时重置为 1</b>
 * （见下面那个 watch，和 {@code quantity.value = 1} /
 * {@code currentIndex.value = 0} 放在一起）。
 */
const reviewPage = ref(1)

/**
 * 星级分布，来自 {@code product.reviewSummary}（商品详情接口一起返回的）。
 *
 * <p>★ 它<b>不是</b>评价列表接口的一部分 —— 后端把它挂在商品详情上，
 * 理由是「每次打开这个页面都要用」（见后端 {@code ShopProductDetailVO}）。
 * 好处是它随商品一起到达，所以「商品评价 (N)」这一行在评价列表
 * 还在路上时就已经是对的，不会先闪一下 0。
 */
const reviewSummary = computed(() => product.value?.reviewSummary ?? null)

/**
 * 平均分，保留 1 位小数。
 *
 * <p>★ <b>四舍五入到 1 位是【展示层】的事</b> —— 后端返回的是原始值
 * （{@code AVG(rating)} 在 MySQL 里给到 4 位小数，比如 4.6667）。
 * 和后端注释里那条「SQL 里不 ROUND」是同一个判断的两半：
 * 一旦在 SQL 里舍掉，前端就再也算不了别的了。
 *
 * <p>⚠️ 用 {@code Number(...)} 兜一下：{@code avgRating} 是后端的
 * {@code BigDecimal}，序列化成 JSON 数字没问题，但这里不能假设
 * 它一定是 JS 的 number（万一是字符串，{@code .toFixed} 会直接报错）。
 */
const avgRatingText = computed(() =>
  Number(reviewSummary.value?.avgRating ?? 0).toFixed(1),
)

/**
 * 星级分布的展示顺序：5 星在最上面。
 *
 * <p>★ 后端返回的字段名是 {@code count5}…{@code count1}
 * （见 {@code ReviewSummaryVO}），这里是把它拼出来取 ——
 * {@code summary[`count${star}`]}。<b>不写成五个 computed</b>：
 * 那样五行的模板要写五遍，而它们唯一的区别就是那个数字。
 */
const REVIEW_STARS = [5, 4, 3, 2, 1]

function countOfStar(star) {
  return reviewSummary.value?.[`count${star}`] ?? 0
}

/**
 * 某一档占的百分比，给进度条用。
 *
 * <p>⚠️ {@code el-progress} 的 {@code percentage} 只接受 0~100 的数值，
 * 所以这里必须自己算好再传（不能传「2 / 3」这样的分数）。
 * 取整用的是 {@code Math.round} —— 1/3 会变成 33 而不是 33.333…，
 * 那个精度在一条 200px 宽的进度条上本来就看不出来。
 */
function percentOfStar(star) {
  const total = reviewSummary.value?.total ?? 0
  return total ? Math.round((countOfStar(star) * 100) / total) : 0
}

/**
 * 拉某一页的评价。
 *
 * <p>⚠️ <b>它失败时【不把整页变成错误状态】</b>（对比上面的 {@code loadProduct}）：
 * 评价区挂了不代表商品本身有问题，商品的价格、库存、按钮全都是好的。
 * 把整页换成一个「加载失败」是把小故障放大成大故障。
 * 所以这里只是把列表清空，页面其余部分照常工作。
 * （代价是用户看不出来评价没加载出来 —— 已知的取舍，
 *   真要做得更好需要在这个区块内部显示一个小的错误状态。）
 */
async function loadReviews() {
  reviewLoading.value = true
  try {
    const res = await listProductReviews(route.params.id, {
      pageNum: reviewPage.value,
      pageSize: REVIEW_PAGE_SIZE,
    })
    reviews.value = res.list
    reviewTotal.value = res.total
  } catch {
    // 具体原因 request.js 的拦截器已经弹过提示了，这里不重复弹
    reviews.value = []
    reviewTotal.value = 0
  } finally {
    reviewLoading.value = false
  }
}

/**
 * 翻页。
 *
 * <h3>★★ 为什么是一个显式的处理函数，而不是 {@code watch(reviewPage)}</h3>
 *
 * <p>如果既在换商品时 {@code reviewPage.value = 1}，又用
 * {@code watch(reviewPage, loadReviews)} 来加载，那么从第 3 页跳到
 * 另一个商品时会发<b>两个</b>请求：
 * <pre>
 *   reviewPage: 3 → 1   触发 watch → loadReviews()   ← 新商品的第 1 页 ✔
 *   紧接着 watch(route.params.id) → 又要 loadReviews() ← 重复 ✘
 * </pre>
 * 而且两个请求是并发的，谁先回来不确定 —— 页面最终显示哪一份数据取决于
 * 网络快慢。<b>「同一件事有两个触发源」是这类竞态的固定成因。</b>
 *
 * <p>所以这里只有<b>一个</b>触发源：用户点了分页器 → 这个函数 →
 * 改页码 + 查数据。程序里改页码（换商品重置）时不会顺手触发查询，
 * 因为那时候 {@code loadReviews} 本来就会被显式调用一次。
 */
function handleReviewPageChange(page) {
  reviewPage.value = page
  loadReviews()
}

// ---------------------------------------------------------------------------
// 晒图大图预览
// ---------------------------------------------------------------------------

/**
 * 点开晒图看大图。
 *
 * <p>★ 用的是独立的 {@code el-image-viewer}，而不是把缩略图换成
 * {@code el-image} 的 {@code preview-src-list}。理由是<b>缩略图必须继续用
 * {@code ProductImage}</b>：晒图和商品图一样是「可能会挂的地址」，
 * 而 {@code el-image} 没有「加载失败退回占位图」这个能力。
 *
 * <p>（{@code ProductImage.vue} 的类注释里那份「直接选中 img 的选择器」
 * 清单，第 5 条就是 {@code .review-images img}。）
 *
 * <p>★ 两个状态分开存而不是合成一个对象：{@code url-list} 要的是
 * <b>完整的一组</b>（好让用户在大图里左右翻），{@code initial-index} 要的是
 * 「从哪一张点进来的」。{@code el-image-viewer} 的接口就是这样分的。
 */
const viewerVisible = ref(false)
const viewerList = ref([])
const viewerIndex = ref(0)

function openViewer(images, index) {
  viewerList.value = images
  viewerIndex.value = index
  viewerVisible.value = true
}

// ★ 监听路由参数而不是只在 onMounted 里加载一次 ——
//   从「商品 A 的详情」点进「商品 B 的详情」时组件会被复用，
//   onMounted 不会再跑，页面上就会一直显示 A 的数据。
//   这种 bug 表现是「点了个商品，地址栏变了，内容没变」，很迷惑人。
//
// ★ 里程碑 12：这个 watch 的回调从一个「直接传 loadProduct」变成了
//   一个函数体，因为换商品时要重置的东西从一个变成了三个
//   （数量、主图索引、评价页码）。这三件事都必须
//   【在加载之前】做完 —— 顺序反了就会先发一个用旧状态拼出来的请求
//   （比如评价列表去查了上一个商品的第 3 页）。
//
// ★ loadProduct 和 loadReviews 是【并行】的，不是串行 ——
//   两者之间没有前后依赖，串起来只会让页面白等一次往返。
watch(
  () => route.params.id,
  () => {
    reviewPage.value = 1
    loadProduct()
    loadReviews()
  },
  { immediate: true },
)

/**
 * 加入购物车（里程碑 7 已实现）。
 *
 * <p>★ 注意这里<b>没有在前端做库存校验</b>。
 * 数量选择器的 max 已经绑了当前库存，看起来像校验，
 * 但它拦不住任何东西 —— 库存可能在这几秒里被别人买走了。
 * 真正的判定在后端：{@code CartServiceImpl.add} 会重新查一次商品，
 * 数量超限时压到上限并返回业务码 1008 提示用户。
 *
 * <p>前端的角色是「让用户少犯错」，后端的角色是「保证不出错」。
 * 这两件事不能互相替代。
 */
async function addToCart() {
  if (!requireLogin('加入购物车')) {
    return
  }
  // 按钮本来就是按 blockReason 禁用的，走到这里说明状态刚好变了
  // （比如存货在这几秒里被别人买走）。拦一道只是少发一个注定失败的请求。
  if (blockReason.value) {
    ElMessage.warning(blockReason.value)
    return
  }

  // ★★ 里程碑 15 阶段 4：这一行从 product.value.id 换成了 currentSku.value.id。
  //
  //   阶段 3 结束时这里还写着 product.value.id，注释里留了一句
  //   「属于阶段 4，那时这里会变成 cartStore.add(currentSku.value.id, ...)」——
  //   这就是那一句的兑现。
  //
  //   ⚠️ 为什么必须换：后端购物车的 field 现在是 skuId。
  //     传 productId 不会报错，它会【静默地加错行】——
  //     productId 撞上另一个商品的 skuId 时，用户车里会多出一件
  //     他根本没选的东西；撞不上时后端查不到这个规格，
  //     返回「商品不存在或已下架」，而用户明明看着商品好好的。
  //
  //   ★ currentSku.value 在这里【一定不为 null】：
  //     上面 blockReason 那一关已经拦掉了「还没选规格」的情况
  //     （blockReason 为真时函数早就 return 了）。
  const ok = await cartStore.add(currentSku.value.id, quantity.value)
  if (!ok) {
    // 失败的原因可能是库存不够、商品刚好被下架、或者数量超限。
    // 具体原因 request.js 已经弹出提示了，这里不重复弹。
    //
    // ★ 但要刷新一下商品详情 —— 如果失败的原因是「库存变了」，
    //   页面上显示的库存还是旧的，用户会不明白为什么失败。
    //   拉一次最新的，让「看到的」和「真实的」重新对上。
    //
    // ★ keepSelection：刷新【不重置用户的选择】。这两件事必须分开 ——
    //   重置是给「换商品」用的（另一个商品不该继承这个商品的选择），
    //   而这里是同一个商品，用户刚点了「黑/M」，把选择清空
    //   只会让他再点一遍。更糟的是：如果他选的正是那个刚卖完的规格，
    //   清空选择之后页面显示的是起售价和「请选择规格」，
    //   而真正该告诉他的是「你选的那个规格没货了」。
    await loadProduct({ keepSelection: true })
    return
  }

  // ★ 加购成功后要刷新角标。
  //   这里就是 stores/cart.js 注释里说的「写操作负责通知」。
  //   漏了这一句的现象是：加购成功了、提示也弹了，
  //   但右上角购物车上的数字没变 —— 用户会怀疑到底加没加进去。
  await cartStore.refresh(true)

  ElMessage.success('已加入购物车')
}

/**
 * 检查登录状态，没登录就送去登录页。
 *
 * <p>这是「浏览不用登录、下单才要登录」这条规则在<b>界面层</b>的落地。
 * 后端也拦了一道（{@code /api/shop/cart/**} 没有排除出拦截器），
 * 两层都做不是为了冗余，而是因为职责不同：
 * <pre>
 *   前端这一层：让用户少等一个注定失败的请求，并且带他回到这里
 *   后端那一层：真正的安全保证，防止有人绕过页面直接调接口
 * </pre>
 *
 * <p>{@code redirect} 参数让用户登录完能回到当前商品页 ——
 * 不加的话他会发现自己被丢到首页，还得重新找刚才那个商品。
 *
 * @returns {boolean} 已登录返回 true，未登录会跳转并返回 false
 */
function requireLogin(action) {
  if (userStore.isLoggedIn) {
    return true
  }
  ElMessage.info(`请先登录后再${action}`)
  router.push({ path: '/login', query: { redirect: route.fullPath } })
  return false
}

/**
 * 立即购买 —— 不经过购物车，直接去结算页。
 *
 * <p>★ 跳转时只带<b>规格 id 和数量</b>，而且数量是放进 URL 的：
 * <pre>
 *   /checkout?skuId=204&amp;quantity=2
 * </pre>
 *
 * <p>为什么数量可以放 URL，而购物车结算那边连数量都不传？
 * 因为<b>这两条路的"真相来源"不同</b>：
 * <pre>
 *   购物车结算  数量在 Redis 购物车里，服务端有权威来源 → 前端不传
 *   立即购买    数量是用户刚在数量选择器上决定的，服务端没有 → 只能传
 * </pre>
 * 详见 {@code api/order.js} 里 createOrderByBuyNow 的注释。
 *
 * <p>★ 里程碑 15 阶段 4：带的是 {@code skuId}。结算页会拿它去
 * {@code GET /api/shop/skus/{skuId}} 把这行商品的名字、封面、
 * 规格文字、单价取回来 —— 那条链接上只有这一个 id，
 * <b>所以它必须指向「一样可买的货」，也就是规格</b>。
 *
 * <p>⚠️ 数量放进 URL 意味着用户能随手改（{@code ?quantity=99999}）。
 * 这<b>不是安全问题</b> —— 后端 {@code BuyNowDTO} 上有 {@code @Min(1) @Max(999)}，
 * 下单时还会重新查库存。它只是个"界面别太难看"的问题，
 * {@code Checkout.vue} 里会把不合法的数量夹回 1。
 *
 * <p>★ 这里<b>故意不把"加入购物车"和"立即购买"合成一个操作</b>
 * （比如"立即购买"= 先加购再跳结算）。合成的话有两个后果：
 * 一是用户的购物车会莫名多出东西，二是那条路上购物车会被清空 ——
 * 用户只是看了一眼想买，结果车里攒了一个月的东西被结掉了。
 * <b>两个不同的意图，就应该是两个不同的操作。</b>
 */
function buyNow() {
  if (!requireLogin('购买')) {
    return
  }
  // 按钮本来就是按 blockReason 禁用的，但"正常不会"不等于"一定不会"：
  // 页面上的库存可能是几分钟前拉的，早被人买走了。
  // 这里拦一道只是少一次注定失败的请求，真正的判定在后端。
  //
  // ★ 提示文案直接用 blockReason（「请选择规格」/「该规格暂时缺货」），
  //   不另写一句 —— 否则同一件事会有两种说法，而且总会有一处忘了改。
  if (blockReason.value) {
    ElMessage.warning(blockReason.value)
    return
  }
  // ★ 里程碑 15 阶段 4：带的是 currentSku.value.id（规格），不是商品 id。
  //   结算页的立即购买分支会用它调 GET /api/shop/skus/{skuId}。
  //   ⚠️ 传 productId 的后果在这里【尤其难查】：结算页去查
  //     /shop/skus/<某个商品id>，那个 id 很可能【真的是一条 SKU】
  //     （两张表都是自增，撞号是常态）—— 于是页面正常渲染，
  //     只是显示的是【另一件商品】的某个规格。用户点提交，买错了东西。
  //
  //   ★ 和 addToCart 一样，currentSku 在这里一定不为 null ——
  //     blockReason 那一关已经拦在前面了。
  router.push({
    path: '/checkout',
    query: {
      skuId: currentSku.value.id,
      quantity: quantity.value,
    },
  })
}
</script>

<template>
  <div class="page-container">
    <!--
      面包屑 + 返回按钮，两个都要留 —— 它们干的是【不同的事】：
        面包屑「分类」→ 去这个分类的商品列表（一条新的浏览路径）
        「返回」按钮  → router.back()，回到【刚才那一屏】
        用户从「手机数码第 3 页」点进来时，只有 back() 能回到第 3 页；
        面包屑会把他送到分类的第 1 屏。反过来，用户从别处直接
        打开这个链接（没有"上一页"可回）时，面包屑才是唯一的出口。
    -->
    <div class="crumb-bar">
      <el-button text class="back" @click="router.back()">← 返回</el-button>

      <div v-if="product" class="crumb">
        <router-link to="/" class="crumb-link">首页</router-link>
        <span class="crumb-sep">›</span>
        <!--
          ★ categoryId 和 categoryName 都是接口真给的字段，不是拼出来的。
            点中间这级回到该分类 —— 走的是和首页左侧栏、头部导航
            【同一条】写入路径（写 URL 的 query），所以三处对
            「当前选中哪个分类」的理解必然一致
        -->
        <router-link
          v-if="product.categoryId"
          :to="{ path: '/', query: { categoryId: product.categoryId } }"
          class="crumb-link"
        >
          {{ product.categoryName || '未分类' }}
        </router-link>
        <span v-else class="crumb-link">未分类</span>
        <span class="crumb-sep">›</span>
        <span class="crumb-current" :title="product.name">{{ product.name }}</span>
      </div>
    </div>

    <div v-loading="loading" class="detail-wrap">
      <!--
        三种状态，互斥显示。顺序有讲究：
        先判断「加载失败」这种真正的故障，再判断「商品没了」这种正常情况
      -->
      <el-result
        v-if="loadFailed"
        icon="error"
        title="加载失败"
        sub-title="网络或服务端出了点问题，请稍后重试"
      >
        <template #extra>
          <!--
            ⚠️ 括号不能省。写成 @click="loadProduct" 的话，Vue 会把
               MouseEvent 当成第一个参数传进去（loadProduct(event)），
               而它期待的是一个 { keepSelection } 对象 ——
               现在恰好不会出错（event 上没有 keepSelection 这个属性，
               解构出来是 undefined → 走默认的 false），
               但那是【碰巧】。这种"靠参数形状恰好对得上"的写法，
               会在下一次给这个函数加参数时变成 bug。
          -->
          <el-button type="primary" @click="loadProduct()">重新加载</el-button>
        </template>
      </el-result>

      <el-result
        v-else-if="notFound"
        icon="info"
        title="商品不存在或已下架"
        sub-title="它可能是被下架了，也可能是链接不对"
      >
        <template #extra>
          <el-button type="primary" @click="router.push('/')">去逛逛别的商品</el-button>
        </template>
      </el-result>

      <div v-else-if="product" class="detail">
        <!--
          ★★ 里程碑 11：「主图 + 缩略图条」。

          ⚠️⚠️ 注意 .thumb-strip 是 .detail-cover 的【兄弟】，不是子元素。
          这不是排版偏好，是必须的 —— .detail-cover 的 CSS 里有：

              .detail-cover img { width: 100%; height: 100%; object-fit: cover; }

          那是个【后代选择器】，会命中里面每一个 <img>。缩略图放进去的话，
          每张缩略图都会变成 380×380 被裁切，整条缩略图变成一坨大图。

          这个陷阱在 ProductImage.vue 的类注释里已经写成文档了
          （它列了 .product-cover img / .row-cover img / .detail-cover img
          三条「直接选中 img」的规则），这里是同一个陷阱的第四次出现 ——
          这次靠「另起一个容器」避开了，而不是靠记住它。
        -->
        <div class="detail-gallery">
          <div class="detail-cover">
            <ProductImage :src="currentImage" :alt="product.name" :size="18" />
          </div>

          <!--
            只有一张图时不渲染缩略图条 —— 一个只有一项的选择器没有意义，
            反而占掉纵向空间。所以老商品（无图集）看到的就是原来的样子。

            ★ 每一项也用 ProductImage，不裸写 <img>：这个组件的全部意义
              就是「单张图挂了要退回占位图，不能让整页看起来是坏的」。
              缩略图同样是「可能会挂的 URL」，没有理由不受这条保护。
          -->
          <div v-if="gallery.length > 1" class="thumb-strip">
            <div
              v-for="(img, index) in gallery"
              :key="img + index"
              class="thumb-item"
              :class="{ active: index === currentIndex }"
              :title="`查看第 ${index + 1} 张`"
              @click="selectImage(index)"
            >
              <ProductImage :src="img" :alt="`${product.name} 第 ${index + 1} 张`" :size="10" />
            </div>
          </div>
        </div>

        <div class="detail-info">
          <h1 class="detail-name">{{ product.name }}</h1>

          <div class="price-box">
            <span class="label">价格</span>
            <span class="price">¥{{ formatAmount(priceValue) }}</span>
            <!--
              ★ 「起」写在 {{ }} 【外面】，是这一行的注解，不是价格的一部分。
                放进去的话 formatAmount 的返回值会被拼成一个非数字字符串，
                任何拿它当数用的地方都会跟着出问题。
                （sql/test-frontend-format.py 的规则 2 就盯着这一条。）

              ★ 有规格但还没选时显示「起」；选中之后显示的是确定的价格，
                不再跟「起」—— 一个已经确定的数后面跟「起」是自相矛盾的。
            -->
            <span v-if="showFrom" class="price-from">起</span>
          </div>

          <!--
            ★★ 规格选择器（里程碑 15 阶段 3）。

            ⚠️ 它整个由 v-if="specGroups.length" 保护，所以
              【库里现有 100 件商品一件都看不到这块】——
              它们的 spec_schema 是 NULL，后端返回空数组。
              这不是"没做完"，而是「无规格的商品本来就没有规格可选」。

            ⚠️ 每一维是一行（.spec-group），不是一列 —— 维度最多 3 个，
              横着一行放得下，纵着排会把「价格」挤到看不见的地方。
          -->
          <div v-if="specGroups.length" class="meta-row spec-row">
            <span class="label">规格</span>
            <div class="spec-groups">
              <div v-for="g in specGroups" :key="g.name" class="spec-group">
                <span class="spec-name">{{ g.name }}</span>
                <!--
                  :class 里的 disabled 是【静态】可选性：这个值在任何一条
                  SKU 里都没出现过 → 标灰。正常情况下不会触发，
                  理由见 isValueAvailable 的注释。
                -->
                <span
                  v-for="v in g.values"
                  :key="v"
                  class="spec-value"
                  :class="{
                    active: selected[g.name] === v,
                    disabled: !isValueAvailable(g.name, v),
                  }"
                  @click="selectSpec(g.name, v)"
                >
                  {{ v }}
                </span>
              </div>
            </div>
          </div>

          <div class="meta-row">
            <span class="label">分类</span>
            <span>{{ product.categoryName || '未分类' }}</span>
          </div>

          <div class="meta-row">
            <span class="label">库存</span>
            <!--
              ★★ 三种状态，而不是两种。

              还没选规格时【不显示任何库存数字】。可选的做法有两种，
              都不如留白诚实：
                显示总库存 → 那个数用户买不到（详见 skuStock 的注释）
                显示某个规格的 → 那是替用户选了他没选的规格
              「还没选规格」本身就是一个信息，把它说出来。

              ★ 顺便注意无规格商品【不会】走到上面那一支：
                它没有维度，selectedComplete 恒为真，
                所以它照旧直接显示「N 件」——和这一轮之前一模一样。
            -->
            <span v-if="!selectedComplete" class="stock-hint">选择规格后显示</span>
            <span v-else :class="{ 'stock-warn': skuStock <= 0 }">
              {{ skuStock <= 0 ? '暂时缺货' : `${skuStock} 件` }}
            </span>
          </div>

          <div class="meta-row">
            <span class="label">数量</span>
            <div class="qty-box">
              <el-input-number
                v-model="quantity"
                :min="1"
                :max="maxQuantity"
                :disabled="!!blockReason"
              />
              <!--
                ★ 库存超过 99 时才提示。库存本来就只有 5 件的规格
                  不需要看这句废话 —— 「提示要有信息量」，
                  一条永远为真的提示等于没有提示。

                ⚠️ 比较的是【所选规格】的库存，不是商品的总库存。
            -->
              <div v-if="skuStock > MAX_QUANTITY_PER_ITEM" class="qty-hint">
                单次最多购买 {{ MAX_QUANTITY_PER_ITEM }} 件
              </div>
            </div>
          </div>

          <div class="actions">
            <!--
              ★ 这一对按钮的样式是京东详情页的标志：
                  加入购物车 → 幽灵按钮（红描边 + 红字，hover 才填实心红）
                  立即购买   → 实心红
                哪个是「主」一目了然，同时两个都还是红的（都是品牌色）。
                ⚠️ 已知副作用：plain 按钮在禁用时是「浅红底 + 浅红字」
                而不是灰色 —— 这是 Element Plus 的固有行为，
                要真灰色得写 5 个 class 去盖 .is-disabled:hover，不值得。
            -->
            <!--
              ★ 两个按钮的禁用条件是同一句话：blockReason。
                「请选择规格」和「该规格暂时缺货」都会让它们变灰，
                但下面那行提示会说明是哪一种（见 blockReason 的注释）。
            -->
            <el-button
              type="primary"
              plain
              size="large"
              :disabled="!!blockReason"
              :loading="cartStore.loading"
              @click="addToCart"
            >
              加入购物车
            </el-button>
            <el-button
              type="danger"
              size="large"
              :disabled="!!blockReason"
              @click="buyNow"
            >
              立即购买
            </el-button>
            <!-- 提示文案就是 blockReason 本身，不另写一句 —— 见 blockReason 的注释 -->
            <span v-if="blockReason" class="sold-out-tip">{{ blockReason }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 商品描述单独放一栏，因为可能很长 -->
    <el-card v-if="product" shadow="never" class="desc-card">
      <template #header>
        <span>商品详情</span>
      </template>
      <!--
        白空格处理：描述里的换行要能显示出来。
        用 white-space: pre-wrap 而不是 <br> 替换 ——
        后者要动数据，前者只是显示方式
      -->
      <p class="desc-text">{{ product.description || '这个商品还没有填写描述' }}</p>
    </el-card>

    <!--
      ★★ 评价区块和 desc-card 是【同级】的兄弟节点，由同一个 v-if="product" 保护。

        为什么不放进上面那个 .detail（价格/按钮那一栏）里面：
        评价可以很长，塞进商品信息那一栏会把「价格、库存、加购按钮」
        挤到看不见的地方 —— 那些才是这一页的主动作。

        为什么跟随 v-if="product" 而不是自己判断：
        上面三种状态（加载失败 / 商品不存在 / 正常）已经互斥地覆盖了所有情况。
        评价区自己再判一次，就多了一处「什么时候该显示」的定义，
        而那两处迟早会不一致。
    -->
    <el-card v-if="product" shadow="never" class="review-card">
      <template #header>
        <!--
          ★ 条数用的是 reviewSummary.total（随商品详情一起回来的），
            不是评价列表接口返回的 reviewTotal。

            两者是同一个数、来自两个接口，这里必须说清为什么允许：

              用 reviewSummary  → 这一行在评价列表还没回来时就是对的，
                                  不会先显示「商品评价 (0)」再跳成 (12)，
                                  也不会一直在「加载中」和数字之间闪
              用 reviewTotal     → 分页器必须用它，因为它和当前这一页
                                  数据是【同一次请求】拿回来的

            ⚠️ 换句话说：**标题属于「商品」这个整体，分页器属于「这一次查询」。**
            把归属分清，两个来源就不是重复，而是各管各的。
        -->
        <span>商品评价 ({{ reviewSummary?.total ?? 0 }})</span>
      </template>

      <div v-loading="reviewLoading" class="review-body">
        <!--
          ★ 零评价是【空状态】，不是一块空白。

            判据用的是 reviewSummary.total === 0，而不是 reviews.length === 0 ——
            后者在「正在加载第 2 页」的那一瞬间也是 0，于是分页翻到
            第 2 页时会闪一下「还没有评价」，然后才出现内容。
            reviewSummary 是随商品一起到的，它说 0 才是真的 0。

          ⚠️ 这也正是「聚合查询不带 GROUP BY 永远返回一行」那个设计
             在前端的落点：后端不需要为「一件商品都没被评过」返回 null，
             所以这里也不用写 reviewSummary && 那半截。
        -->
        <el-empty
          v-if="!reviewSummary || reviewSummary.total === 0"
          description="这件商品还没有评价"
        />

        <template v-else>
          <div class="review-summary">
            <div class="avg-box">
              <div class="avg-num">{{ avgRatingText }}</div>
              <div class="avg-label">共 {{ reviewSummary.total }} 条评价</div>
            </div>

            <!--
              ★ 这里【不放 el-rate】—— 这是有理由的删减，不是漏做。

                el-rate 表达不了 4.7。不给 allow-half 时它是整颗星的，
                4.7 会被显示成 4 颗（或 5 颗，取决于内部怎么取整）；
                给了 allow-half 也只能表达 4.5。
                **一个「看起来像精确值、其实不是」的图形比没有更糟** ——
                用户会按星星的颗数去理解评分。

                所以左边用大字写 4.7，右边用条形图给出分布。
                想知道「4.7 是怎么来的」，看分布条比看星星准确得多。
            -->
            <div class="dist-box">
              <div v-for="star in REVIEW_STARS" :key="star" class="dist-row">
                <span class="dist-star">{{ star }} 星</span>
                <el-progress
                  class="dist-bar"
                  :percentage="percentOfStar(star)"
                  :stroke-width="8"
                  :show-text="false"
                />
                <span class="dist-count">{{ countOfStar(star) }} 条</span>
              </div>
            </div>
          </div>

          <ul class="review-list">
            <li v-for="r in reviews" :key="r.id" class="review-item">
              <div class="review-head">
                <!--
                  ★ 昵称是可空的（会员可以不填），后端也确实只给了昵称 ——
                    没有 username、没有 phone。兜底成「匿名用户」而不是空着，
                    否则会有几条评价的用户名位置是一片空白。
                -->
                <span class="review-user">{{ r.memberNickname || '匿名用户' }}</span>
                <el-rate :model-value="r.rating" disabled />
                <!-- 时间是后端已经格式化好的字符串（JacksonConfig），这里不做任何处理 -->
                <span class="review-time">{{ r.createTime }}</span>
              </div>

              <p class="review-content">{{ r.content }}</p>

              <!--
                ★ images 为空时不渲染这一块（而不是渲染一个空 div）——
                  后者会带出一个 margin，每条没晒图的评价都会多出一截空白。

                ★ :key 用 url 而不是下标：同一组晒图不会重复（服务端生成的是
                  带 UUID 的路径），而用下标当 key 在删改时会串位。
                  点开大图时传进去的是【整组】images，
                  这样用户在大图里还能左右翻 —— 只看一张是「点开了一张图」，
                  能翻才是「在看这组图」。
              -->
              <div v-if="r.images && r.images.length" class="review-images">
                <ProductImage
                  v-for="(url, i) in r.images"
                  :key="url"
                  :src="url"
                  :alt="`${r.memberNickname || '匿名用户'} 的晒图 ${i + 1}`"
                  :size="11"
                  class="review-image"
                  @click="openViewer(r.images, i)"
                />
              </div>
            </li>
          </ul>

          <el-pagination
            class="review-pager"
            background
            layout="prev, pager, next, total"
            :total="reviewTotal"
            :page-size="REVIEW_PAGE_SIZE"
            :current-page="reviewPage"
            hide-on-single-page
            @current-change="handleReviewPageChange"
          />
        </template>
      </div>
    </el-card>

    <!--
      ★ 大图预览器。挂在页面级而不是每条评价里 ——
        el-image-viewer 是个全屏浮层，放进 v-for 里会渲染出 N 个浮层，
        而同时只有 1 个可能被打开。
        ⚠️ 用 v-if 而不是 v-show：没打开时它不该存在于 DOM 里
        （它会监听键盘 Esc、还会锁滚动条）。

      ★ teleported 让浮层挂到 body 上，绕开父元素的
        overflow / z-index 上下文 —— 否则它可能被卡在卡片里。
    -->
    <el-image-viewer
      v-if="viewerVisible"
      :url-list="viewerList"
      :initial-index="viewerIndex"
      teleported
      @close="viewerVisible = false"
    />
  </div>
</template>

<style scoped>
.crumb-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 13px;
  /* 面包屑一行放不下时截断，不要换行 —— 换了行会把下面的详情卡往下推，
     而这一块本来就只是导航，不值得占两行 */
  overflow: hidden;
}

.back {
  padding-left: 0;
  color: #606266;
  flex-shrink: 0;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: #999;
}

.crumb-link {
  color: #999;
  text-decoration: none;
  white-space: nowrap;
  transition: color 0.15s;
}

.crumb-link:hover {
  color: var(--jd-red);
}

.crumb-sep {
  color: #ccc;
}

/* 当前这一级不可点，而且要比上面几级更显眼（#333 vs #999）——
   面包屑的规矩是「最后一级是当前位置，不是链接」 */
.crumb-current {
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-wrap {
  min-height: 300px;
  background-color: #fff;
  border-radius: var(--jd-radius);
  padding: 20px;
}

.detail {
  display: flex;
  gap: 30px;
  flex-wrap: wrap;
}

/* 主图 + 缩略图条的列容器。
   ★ 它的存在就是为了把「380×380」这条规则【圈】在主图这一个盒子里 ——
     见模板里那段关于后代选择器的注释 */
.detail-gallery {
  width: 380px;
  flex-shrink: 0;
}

.detail-cover {
  width: 380px;
  height: 380px;
  border-radius: var(--jd-radius);
  overflow: hidden;
  /* 图没加载出来时的垫底色，和首页卡片同一个中性灰 */
  background-color: #f7f7f7;
}

/* ⚠️ 这是【后代选择器】，作用范围是 .detail-cover 里面的一切。
   所以缩略图条必须是它的兄弟节点，不能放进去 ——
   放进去的话每张缩略图都会被这条规则拉成 380×380 */
.detail-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* ---------------- 里程碑 11：缩略图条 ---------------- */

.thumb-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.thumb-item {
  width: 60px;
  height: 60px;
  border: 2px solid transparent;
  border-radius: 4px;
  overflow: hidden;
  cursor: pointer;
  background-color: #f7f7f7;
  /* 边框常驻（透明）而不是 hover 时才加 ——
     否则选中时加边框会让整体尺寸变一下，一排缩略图跟着抖 */
  transition: border-color 0.15s;
}

.thumb-item:hover {
  border-color: #ffb1a8;
}

/* 当前选中那张。红色描边和京东的选中态一致 */
.thumb-item.active {
  border-color: var(--jd-red);
}

.thumb-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.detail-info {
  flex: 1;
  min-width: 280px;
}

.detail-name {
  margin: 0 0 18px;
  font-size: 22px;
  line-height: 1.4;
  color: #303133;
}

.price-box {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 18px;
  border-radius: var(--jd-radius);
  /* ★ 原来是橙色的暖色渐变。京东的价格区是一块【平的】极浅红，
     不用渐变 —— 渐变会把「价格」这个信息稀释成一个装饰元素 */
  background-color: var(--jd-red-bg);
}

.price-box .label {
  font-size: 13px;
  color: #909399;
}

.price-box .price {
  font-size: 30px;
  font-weight: 700;
  color: var(--jd-red);
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
  font-size: 14px;
  color: #303133;
}

.meta-row .label {
  width: 42px;
  color: #909399;
  flex-shrink: 0;
}

/* 「起」—— 跟在价格后面那个字。
   ★ 比价格小得多、颜色也不是价格那种红：它是【注解】不是数字本身。
     写成和价格一样大，用户会把它读成价格的一部分 */
.price-from {
  font-size: 14px;
  color: #909399;
}

/* ---------------- 里程碑 15：规格选择器 ---------------- */

/* 规格那一行可能是两三行（每一维一行），顶部对齐比垂直居中好看 ——
   否则「规格」两个字会飘在整块的中间，和任何一维都对不起 */
.spec-row {
  align-items: flex-start;
}

/* 把「规格」两个字和第一行的按钮对齐。
   .meta-row .label 没有定行高，而 .spec-value 有 24px 高，
   不补这一句的话标签会比第一行按钮高一点点 */
.spec-row .label {
  line-height: 24px;
}

.spec-groups {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.spec-group {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.spec-name {
  font-size: 13px;
  color: #909399;
  min-width: 36px;
}

/* 一个规格值。
   ★ 边框常驻（透明）而不是 hover 时才加 —— 和缩略图条 .thumb-item
     是同一条理由：选中时加边框会让整体尺寸变一下，一排按钮跟着抖 */
.spec-value {
  padding: 3px 12px;
  font-size: 13px;
  line-height: 18px;
  color: #303133;
  background-color: #fff;
  border: 1px solid var(--jd-border-light);
  border-radius: 3px;
  cursor: pointer;
  user-select: none;
  transition:
    border-color 0.15s,
    color 0.15s;
}

.spec-value:hover {
  border-color: var(--jd-red);
  color: var(--jd-red);
}

/* 选中态：红描边 + 红字 + 一层极浅红底，和首页的分类选中保持一致 */
.spec-value.active {
  border-color: var(--jd-red);
  color: var(--jd-red);
  background-color: var(--jd-red-bg);
  font-weight: 600;
}

/* 静态不可选（这个值在任何一条 SKU 里都不存在）。
   ⚠️ 这里【必须】把 cursor 改回去 —— 一个灰色的按钮配着 pointer 光标，
   用户会一直点它，然后以为页面卡住了 */
.spec-value.disabled {
  color: #c0c4cc;
  border-color: #ebeef5;
  background-color: #fafafa;
  cursor: not-allowed;
  text-decoration: line-through;
}

.spec-value.disabled:hover {
  border-color: #ebeef5;
  color: #c0c4cc;
}

/* 还没选规格时库存那一格显示的提示，比正文浅，表示「这里暂时没有数」 */
.stock-hint {
  color: #a8abb2;
  font-size: 13px;
}

.stock-warn {
  color: #f56c6c;
  font-weight: 600;
}

.qty-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.qty-hint {
  font-size: 12px;
  color: #e6a23c;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 26px;
  flex-wrap: wrap;
}

.sold-out-tip {
  font-size: 13px;
  color: #f56c6c;
}

.desc-card {
  /* 圆角交给 --el-card-border-radius，见 theme.css */
  margin-top: 20px;
}

.desc-text {
  margin: 0;
  line-height: 1.9;
  color: #606266;
  font-size: 14px;
  /* 保留换行和连续空格，同时允许自动换行 */
  white-space: pre-wrap;
}

/* ---------------- 里程碑 12：商品评价 ---------------- */

.review-card {
  margin-top: 20px;
}

/* 评价区至少要有一点高度，否则加载中的转圈会挤在一个很扁的盒子里，
   v-loading 的遮罩看起来像页面坏了 */
.review-body {
  min-height: 120px;
}

.review-summary {
  display: flex;
  align-items: center;
  gap: 40px;
  flex-wrap: wrap;
  padding-bottom: 16px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--jd-border-light);
}

.avg-box {
  text-align: center;
  flex: none;
}

/* 平均分的大字 */
.avg-num {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--jd-red);
}

.avg-label {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}

/* flex: 1 + min-width: 0 —— 和 .detail-info 同一个套路：
   允许它撑满剩余宽度，同时在窄屏时能被压缩而不是撑破容器 */
.dist-box {
  flex: 1;
  min-width: 220px;
}

.dist-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #999;
}

.dist-star {
  flex: none;
  width: 34px;
  text-align: right;
}

/* ★ 必须给 .dist-bar 自己的宽度。
   el-progress 默认 width: 100%，而它的父元素 .dist-row 是 flex ——
   那条「百分比宽」在 flex 项里会按内容算，结果就是五条进度条宽度不一。
   给一个能伸缩的固定基数（flex: 1）比 width 更稳。 */
.dist-bar {
  flex: 1;
  min-width: 0;
}

.dist-count {
  flex: none;
  width: 44px;
}

/* 进度条的颜色 —— EP 默认是主题色（本项目是京东红），和评分语义正好一致。
   ⚠️ 这里【不改】它：评分条的红色是「好评」的意思，不是品牌色，
   两者在本项目里恰好是同一个值，所以没有冲突。 */

.review-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.review-item {
  padding: 14px 0;
  border-bottom: 1px dashed var(--jd-border-light);
}

/* 最后一条不再画分隔线 —— 它下面紧跟着分页器，
   一条贴到底的虚线看起来像是被截断了 */
.review-item:last-child {
  border-bottom: none;
}

.review-head {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.review-user {
  color: #333;
  /* 昵称可能有长有短，给个最小宽度让星星大致对齐；
     ⚠️ 用 min-width 而不是 width —— 长昵称要能把它撑开，
     固定宽度会把长昵称截断 */
  min-width: 80px;
}

.review-time {
  margin-left: auto;
  color: #999;
  font-size: 12px;
}

.review-content {
  margin: 8px 0 0;
  line-height: 1.7;
  color: #606266;
  font-size: 14px;
  /* 保留用户输入里的换行（评价是自由文本，textarea 里换的行本来就该显示出来）。
     和 .desc-text 同一条理由，但这两处的文案来自不同的地方，
     所以各写一份而不是抽个公共 class —— 一个是运营写的商品描述，
     一个是买家随手打的字，将来很可能只会有一边需要调整 */
  white-space: pre-wrap;
  word-break: break-word;
}

.review-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

/* ⚠️ 这是【后代选择器】，作用范围是 .review-images 里面的一切。

   ★ 它是 ProductImage.vue 类注释里那份清单的第 5 条
     （那份清单列的是「直接选中 img 的 CSS」，每加一条都要回去补一行）。

   ★ 这里用的是 .review-images img 而不是写 .review-image ——
     因为 ProductImage 的根元素就是 <img>，而【子组件的根元素会带上
     父组件的 scoped 属性】，所以这条规则能命中它。
     （对照 .detail-cover img / .thumb-item img，三条写法完全一样。）

   ⚠️ 反过来说：如果哪天这个组件改成渲染一个 <div> 包着 <img>，
     这三条规则会【静默失效】（选择器匹配不到任何元素，不报错），
     晒图会变成原始尺寸。ProductImage 的注释里已经写明「根元素必须还是 img」。 */
.review-images img {
  width: 88px;
  height: 88px;
  object-fit: cover;
  display: block;
  border-radius: 4px;
  border: 1px solid var(--jd-border-light);
  cursor: zoom-in;
}

.review-pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
