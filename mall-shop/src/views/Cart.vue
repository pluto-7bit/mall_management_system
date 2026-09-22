<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearCart, getCart, removeCartItem, updateCartItem } from '@/api/cart'
import ProductImage from '@/components/ProductImage.vue'
import { useCartStore } from '@/stores/cart'
import { MAX_QUANTITY_PER_ITEM as MAX_PER_ITEM } from '@/utils/constants'

/**
 * 购物车页面。
 *
 * <h3>★ 这个页面的核心是「失效商品」的处理</h3>
 *
 * <p>后端返回的条目里，{@code available === false} 的表示现在买不了：
 * 商品下架了、卖光了，或者库存不够用户加购的数量。
 *
 * <p>界面上的处理原则：
 * <pre>
 *   失效商品【照样显示】，沉在列表底部，灰掉，并写明原因
 *   计价时【排除】失效商品
 *   结算按钮在【有失效商品时也能点】，但如果一件能买的都没有就禁用
 * </pre>
 *
 * <p>为什么不干脆不显示失效商品？因为用户会困惑 ——
 * 「我明明加了 3 件，怎么只剩 2 件了？是我记错了还是系统有问题？」
 * <b>让用户看到「东西还在，只是买不了了」，比让它悄悄消失友好得多。</b>
 * 这也是所有主流电商的做法。
 *
 * <h3>本地状态和服务端状态的关系</h3>
 *
 * <p>这个页面把购物车数据放在<b>本地 ref</b> 里，而不是放 store。
 * 理由见 {@code stores/cart.js} 的注释：只有这个页面关心内容。
 *
 * <p>每次改数量/删除之后都<b>重新拉一次完整的购物车</b>，
 * 而不是本地改一下数字就完事。这样做的代价是多一次网络请求，
 * 好处是<b>本地永远和服务端一致</b> —— 尤其是失效状态、库存、
 * 合计金额这些只有服务端算得准的东西。
 *
 * <p>「改完重新拉一次」是很朴素的做法，但在这个页面上完全够用，
 * 而且不会出现「本地改对了、服务端没改」这类难查的 bug。
 * <b>先要正确，再要快。</b>
 */

const router = useRouter()
const cartStore = useCartStore()

const cart = ref({ items: [], totalQuantity: 0, totalAmount: 0 })
const loading = ref(false)
const loadFailed = ref(false)

/** 正在改数量的商品 id —— 用来给那一行的输入框加 loading */
const updatingId = ref(null)

const items = computed(() => cart.value.items || [])
const availableItems = computed(() => items.value.filter((i) => i.available))
const unavailableItems = computed(() => items.value.filter((i) => !i.available))

/** 购物车里是不是一件能买的都没有了 */
const nothingBuyable = computed(() => availableItems.value.length === 0)

// ---------------------------------------------------------------------------
// ★ 勾选（里程碑 8 新增）
// ---------------------------------------------------------------------------

/** 勾选了的商品 id。只可能包含【能买的】商品 —— 失效商品不给勾 */
const selectedIds = ref([])

/**
 * 首次加载后是否已经做过默认勾选。
 *
 * <p>★ 为什么需要这个标志位？因为「默认全选」只能做一次。
 *
 * <p>没有它的话，每次 {@code loadCart()}（改数量、删一件、清空失效…
 * 都会触发）之后都会重新全选一遍 —— 用户辛辛苦苦勾掉的两件
 * 会在改完数量之后"自己又勾上了"。用户会觉得这个页面在跟他作对。
 */
let selectionInitialized = false

/**
 * 让勾选状态和服务端数据对上。
 *
 * <p>规则：
 * <pre>
 *   第一次加载        →  能买的全部勾上（绝大多数用户是全部结算）
 *   之后的每次刷新    →  【保留】用户的勾选，但把已经不能买的剔掉
 * </pre>
 *
 * <p>⚠️ 第二步的"剔除"是必须的。一个商品在用户勾上之后、
 * 提交之前被下架了，它就会从 available 变成 unavailable。
 * 这时候如果还留在 selectedIds 里，结算请求就会带上一个
 * <b>买不了的商品 id</b> —— 整单失败，而且用户看着自己
 * 明明没勾它，不知道为什么失败。
 */
function syncSelection() {
  const available = availableItems.value.map((i) => i.productId)
  if (!selectionInitialized) {
    selectedIds.value = available
    selectionInitialized = true
    return
  }
  // ⚠️ 用 filter 保留【原来的顺序】，不是直接赋成 available。
  //   顺序不影响提交（后端不关心），但勾选框的视觉状态稳定一些
  selectedIds.value = selectedIds.value.filter((id) => available.includes(id))
}

/** 全选：能买的是不是都勾上了 */
const allSelected = computed(
  () =>
    availableItems.value.length > 0 &&
    selectedIds.value.length === availableItems.value.length,
)

/**
 * 半选状态 —— 勾了一部分时，全选框显示成横杠。
 *
 * <p>这不是个装饰：没有它，用户勾了 3 件中的 1 件后看到全选框是空的，
 * 会以为自己的勾选没生效。
 */
const indeterminate = computed(
  () => selectedIds.value.length > 0 && !allSelected.value,
)

function toggleAll(checked) {
  selectedIds.value = checked
    ? availableItems.value.map((i) => i.productId)
    : []
}

/** 勾选的商品合计【件数】（不是条目数，和角标的口径一致） */
const selectedQuantity = computed(() =>
  availableItems.value
    .filter((i) => selectedIds.value.includes(i.productId))
    .reduce((sum, i) => sum + Number(i.quantity || 0), 0),
)

/**
 * 勾选的商品合计【金额】。
 *
 * <p>★ 这里必须解释一下，因为它看起来违反了这个页面原来的一条原则 ——
 * 「金额来自后端，前端不自己算」（见下面模板里结算栏原来的注释）。
 *
 * <p>原来不自己算，是因为 {@code cart.totalAmount} 是后端算好的，
 * 前端再算一遍就是两个真相来源。但现在有了勾选，
 * <b>后端给的 totalAmount 是【全部能买的商品】的合计，不是【勾选的】合计</b>——
 * 直接把那个数字显示出来，用户勾掉两件之后会看到一个没变的金额，
 * 那才是真的错。
 *
 * <p>那为什么不是"再加一个后端接口算勾选的合计"？
 * 因为<b>下单时后端会用它自己的规则重算一遍</b>（见 {@code OrderServiceImpl}），
 * 前端算的这个数从头到尾只是给用户看的预估。为了一个预估值
 * 多加一个接口和一次往返，不划算。
 *
 * <p>⚠️ 但要注意：<b>这个数字是「把后端给的每条小计相加」，不是
 * 「单价 × 数量再相加」</b>。每条 subtotal 都是后端用 BigDecimal
 * 算好的，前端只做加法，误差的来源就少了一层。
 * 而且最终金额永远以后端下单时算的为准 ——
 * 万一这里因为浮点误差差了 1 分钱，也不会影响实际扣款。
 */
const selectedAmount = computed(() =>
  availableItems.value
    .filter((i) => selectedIds.value.includes(i.productId))
    .reduce((sum, i) => sum + Number(i.subtotal || 0), 0)
    .toFixed(2),
)

async function loadCart() {
  loading.value = true
  loadFailed.value = false
  try {
    cart.value = await getCart()
    // ★ 顺手把角标同步一下。因为「删除全部商品」之后
    //   后端返回的 totalQuantity 变了，但 store 里还是旧值。
    //   在这里同步一次，比在每个操作后面都记得调 refresh() 更不容易漏
    cartStore.count = cart.value.totalQuantity || 0
    syncSelection()
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 改数量。
 *
 * <p>⚠️ 这里用的是 {@code updateCartItem}（改成某个值），
 * <b>不是</b> {@code addCartItem}（累加）。两个长得像但语义完全不同 ——
 * 用错了的后果是「把数量从 2 改成 3，结果变成 5」。
 * 详见 {@code api/cart.js} 里这两个函数的注释。
 *
 * @param item     购物车条目
 * @param newValue el-input-number 传回来的新值
 */
async function changeQuantity(item, newValue) {
  const qty = Number(newValue)
  if (!qty || qty < 1) {
    // el-input-number 有 min=1，正常不会走到这里。
    // 但「正常不会」不等于「一定不会」—— 用户可能手动输入了 0
    return
  }
  if (qty === item.quantity) {
    // 值没变就什么都不做。
    // el-input-number 在失焦时即使值没变也会触发 change，
    // 不做这个判断就会白发一次请求
    return
  }

  updatingId.value = item.productId
  try {
    await updateCartItem(item.productId, qty)
    await loadCart()
  } catch {
    // ★ 失败了要【重新拉一次】，把输入框的数字恢复成服务端的真实值。
    //
    //   否则界面上会显示着一个后端并没有接受的数量 ——
    //   比如用户输入 50 但库存只有 10，后端拒绝了，
    //   而输入框里还明晃晃地写着 50，用户会以为改成功了。
    //   「界面显示的内容必须是真实状态」是交互的基本要求。
    await loadCart()
  } finally {
    updatingId.value = null
  }
}

async function removeItem(item) {
  try {
    await ElMessageBox.confirm(
      `确定要把「${item.name || '这件商品'}」从购物车移除吗？`,
      '提示',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  try {
    await removeCartItem(item.productId)
    ElMessage.success('已移除')
    await loadCart()
  } catch {
    // 提示已由 request.js 弹出
  }
}

async function handleClear() {
  try {
    await ElMessageBox.confirm('确定要清空购物车吗？此操作不可撤销。', '提示', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }

  try {
    await clearCart()
    ElMessage.success('购物车已清空')
    await loadCart()
  } catch {
    // 提示已由 request.js 弹出
  }
}

/** 清理所有失效商品 —— 一次删一个（后端没有批量删除接口） */
async function clearUnavailable() {
  try {
    await ElMessageBox.confirm(
      `确定要移除这 ${unavailableItems.value.length} 件失效商品吗？`,
      '提示',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  // ★ 用 Promise.all 并发发请求，而不是 for 循环里 await 一个个来。
  //   5 件商品的话，串行要等 5 个来回，并发只要等最慢的那个。
  //
  //   ⚠️ 但如果数量很多（比如几百件），并发会瞬间打开几百个连接，
  //   可能把浏览器或服务端打垮。到那时候要改成「分批并发」
  //   或者让后端提供一个批量删除接口。
  //   **并发的度要看数据量，不能无脑并发。**
  try {
    await Promise.all(
      unavailableItems.value.map((i) => removeCartItem(i.productId)),
    )
    ElMessage.success('已清理失效商品')
    await loadCart()
  } catch {
    // 部分失败也重新拉一次，让界面显示真实状态
    await loadCart()
  }
}

function goDetail(id) {
  router.push(`/product/${id}`)
}

function goShopping() {
  router.push('/')
}

/**
 * 去结算。
 *
 * <p>★ 注意这里跳到结算页时，只带了<b>商品 id</b>，没带数量、没带价格：
 * <pre>
 *   /checkout?ids=3,7
 * </pre>
 * 数量由结算页重新从购物车接口读（因为那是服务端的真相），
 * 价格由后端在下单时算。详见 {@code Checkout.vue} 的注释。
 *
 * <p>⚠️ 也不用 store 传这些 id，而是放在 URL 里 ——
 * 这样用户在结算页按 F5 时数据不会丢。<b>URL 是唯一能活过刷新的地方。</b>
 *
 * <p>勾选顺序不影响结果，所以这里<b>不排序</b>（后端也不关心顺序）。
 */
function goCheckout() {
  if (!selectedIds.value.length) {
    // 正常不会走到这里（按钮是禁用的），但"正常不会"不等于"一定不会"：
    // 用户可能在点下的同时刚好有商品被下架，syncSelection 把勾选清空了
    ElMessage.warning('请先选择要结算的商品')
    return
  }
  router.push({
    path: '/checkout',
    query: { ids: selectedIds.value.join(',') },
  })
}

// 这个页面在路由守卫里标了 requiresAuth，所以走到这里一定是登录状态
onMounted(loadCart)
</script>

<template>
  <div class="page-container">
    <h2 class="page-title">我的购物车</h2>

    <div v-loading="loading" class="cart-wrap">
      <el-result
        v-if="loadFailed"
        icon="error"
        title="加载失败"
        sub-title="网络或服务端出了点问题"
      >
        <template #extra>
          <el-button type="primary" @click="loadCart">重新加载</el-button>
        </template>
      </el-result>

      <el-empty v-else-if="!items.length && !loading" description="购物车还是空的">
        <el-button type="primary" @click="goShopping">去逛逛</el-button>
      </el-empty>

      <template v-else>
        <!--
          ★ 能买的商品。这一块是「主区」，用户可以正常改数量、移除
        -->
        <el-card v-if="availableItems.length" shadow="never" class="list-card">
          <!--
            ★ 表头行：全选 + 各列的名字。
              原来没有表头，因为每行都是自解释的。
              加了勾选框之后必须有 ——"这列方块是干嘛的"需要一个标题来说清
          -->
          <div class="cart-row head-row">
            <el-checkbox
              :model-value="allSelected"
              :indeterminate="indeterminate"
              @change="toggleAll"
            >
              全选
            </el-checkbox>
            <div class="head-col head-info">商品信息</div>
            <div class="head-col head-price">单价</div>
            <div class="head-col head-qty">数量</div>
            <div class="head-col head-subtotal">小计</div>
            <div class="head-col head-op">操作</div>
          </div>

          <!--
            ★★ 每行的勾选框必须包在 el-checkbox-group 里。

            这一点我查过 element-plus 2.14.6 的源码才敢确定：
              isGroup ? checkboxGroup.modelValue : props.modelValue
            也就是说，【数组形式的 v-model 只在 group 里成立】。
            单独一个 el-checkbox 上绑数组属于未定义行为 ——
            它不会报错，但勾选状态会一直不对。

            ⚠️ 而这正是前端最讨厌的一类 bug：模板里写得
            `<el-checkbox v-model="selectedIds" :value="..." />`
            看着完全合理，构建也通过，构建工具和 JS 都不会告诉你
            "这个用法不成立"。**没有类型检查的地方，
            "看起来对"和"确实对"之间的距离要靠查源码来填。**

            ⚠️ 另外 el-checkbox-group 会渲染一个 div 包住所有行，
            所以它必须在【行的循环外面】，而不是每一行一个 group ——
            每行一个 group 的话，每个 group 各管一个 id，
            "全选"就没法一眼算出勾了几个。
          -->
          <el-checkbox-group v-model="selectedIds">
            <div v-for="item in availableItems" :key="item.productId" class="cart-row">
              <!--
                ★ 只有【能买的】商品才有勾选框。失效商品不给勾 ——
                  一个能勾上却一定提交失败的选项，是纯粹的陷阱
              -->
              <el-checkbox :value="item.productId" />

              <div class="row-cover" @click="goDetail(item.productId)">
                <ProductImage :src="item.cover" :alt="item.name" :size="12" />
              </div>

              <div class="row-info">
                <div class="row-name" @click="goDetail(item.productId)">
                  {{ item.name }}
                </div>
                <div class="row-category">{{ item.categoryName || '未分类' }}</div>
              </div>

              <div class="row-price">¥{{ item.price }}</div>

              <div class="row-quantity">
                <el-input-number
                  :model-value="item.quantity"
                  :min="1"
                  :max="Math.max(Math.min(item.stock, MAX_PER_ITEM), 1)"
                  :loading="updatingId === item.productId"
                  size="small"
                  @change="(v) => changeQuantity(item, v)"
                />
                <!-- 库存提示：让用户知道为什么不能再加了 -->
                <div class="stock-hint">库存 {{ item.stock }}</div>
              </div>

              <div class="row-subtotal">¥{{ item.subtotal }}</div>

              <el-button text type="danger" @click="removeItem(item)">移除</el-button>
            </div>
          </el-checkbox-group>
        </el-card>

        <!--
          ★★ 失效商品区。灰掉、沉底、写明原因，但不隐藏。
             「为什么不能买」必须告诉用户，否则他会反复重试
        -->
        <el-card v-if="unavailableItems.length" shadow="never" class="list-card invalid-card">
          <template #header>
            <div class="invalid-header">
              <span>失效商品 {{ unavailableItems.length }} 件</span>
              <el-button text type="primary" @click="clearUnavailable">
                清理失效商品
              </el-button>
            </div>
          </template>

          <div
            v-for="item in unavailableItems"
            :key="item.productId"
            class="cart-row invalid-row"
          >
            <!--
              ★ 失效行放一个【空的占位】而不是把整行左移。
                让它和上面能买的行对齐 —— 不占位的话，
                上下两块的商品图会错开一格，看起来像两个列表
            -->
            <div class="check-placeholder"></div>

            <div class="row-cover">
              <ProductImage :src="item.cover" :alt="item.name || '失效商品'" :size="12" />
              <!-- 灰色蒙层 + 「失效」二字，一眼就能看出状态 -->
              <div class="invalid-mask">失效</div>
            </div>

            <div class="row-info">
              <div class="row-name">{{ item.name || '商品已下架' }}</div>
              <!-- ★ 原因是后端算好给的，前端只负责显示，
                   不在前端猜「为什么不能买」 -->
              <div class="row-reason">{{ item.unavailableReason }}</div>
            </div>

            <div class="row-price">¥{{ item.price ?? '—' }}</div>

            <div class="row-quantity">×{{ item.quantity }}</div>

            <div class="row-subtotal">—</div>

            <el-button text type="danger" @click="removeItem(item)">移除</el-button>
          </div>
        </el-card>

        <!--
          结算栏。

          ★ 里程碑 8 之前，这里显示的是后端算好的 cart.totalAmount。
            加了勾选之后不能再用它了 —— 那是【全部能买的商品】的合计，
            用户勾掉两件之后金额不会变，那就是错的。

            所以现在显示 selectedAmount：把【勾选的】那几条的
            subtotal 加起来。每条 subtotal 仍然是后端算的，
            前端只做加法（详见 script 里 selectedAmount 的注释）。
            真正下单时的金额完全由后端重算，这个数字只是给用户看的预估。
        -->
        <div class="checkout-bar">
          <div class="checkout-left">
            <el-button text @click="handleClear">清空购物车</el-button>
            <span v-if="unavailableItems.length" class="invalid-tip">
              有 {{ unavailableItems.length }} 件商品已失效，不计入合计
            </span>
          </div>

          <div class="checkout-right">
            <div class="total">
              已选 <b>{{ selectedQuantity }}</b> 件，合计：
              <span class="total-amount">¥{{ selectedAmount }}</span>
            </div>
            <el-button
              type="danger"
              size="large"
              :disabled="nothingBuyable || !selectedIds.length"
              @click="goCheckout"
            >
              去结算
            </el-button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 18px;
  font-size: 20px;
  color: #303133;
}

.cart-wrap {
  min-height: 300px;
}

.list-card {
  /* 圆角交给 --el-card-border-radius（见 theme.css）。
     这里一旦写死 10px，就是 .list-card[data-v-x]（0,2,0）压过 .el-card（0,1,0），
     以后再改主题都盖不动它 */
  margin-bottom: 16px;
}

.cart-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid #f2f6fc;
}

.cart-row:last-child {
  border-bottom: none;
}

/* ---- 表头行 ---- */
.head-row {
  padding: 4px 0 12px;
  font-size: 13px;
  color: #909399;
}

/* ★ 表头文字的对齐必须和下面数据行【逐列对上】。
   这里几个宽度值是照着下面 .row-price / .row-quantity /
   .row-subtotal 抄的 —— 改一个就要一起改，
   否则表头和数据会错位。这是"表头和数据是两个独立的地方"
   必然带来的耦合，只能用注释提醒自己 */
.head-col {
  flex-shrink: 0;
  text-align: right;
}

.head-info {
  flex: 1;
  min-width: 0;
  text-align: left;
}

.head-price {
  width: 100px;
}

.head-qty {
  width: 150px;
  text-align: center;
}

.head-subtotal {
  width: 110px;
}

.head-op {
  width: 56px;
  text-align: center;
}

/* ★ 失效行的占位，宽度和勾选框一致，让上下两块横向对齐 */
.check-placeholder {
  width: 14px;
  flex-shrink: 0;
}

.row-cover {
  position: relative;
  width: 80px;
  height: 80px;
  flex-shrink: 0;
  border-radius: 6px;
  overflow: hidden;
  /* 图没加载出来时的垫底色，和首页卡片用同一个中性灰。
     原来是 #f5f7fa（偏蓝），和新底色的冷暖不一致 */
  background-color: #f7f7f7;
  cursor: pointer;
}

.row-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.row-info {
  flex: 1;
  min-width: 0;
}

.row-name {
  font-size: 14px;
  color: #303133;
  line-height: 1.5;
  cursor: pointer;
  /* 名称过长时截断。min-width: 0 配合 flex:1 才能让 ellipsis 生效 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-name:hover {
  color: var(--jd-red);
}

.row-category {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

.row-price {
  width: 100px;
  text-align: right;
  font-size: 14px;
  color: #606266;
  flex-shrink: 0;
}

.row-quantity {
  width: 150px;
  text-align: center;
  flex-shrink: 0;
}

.stock-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}

.row-subtotal {
  width: 110px;
  text-align: right;
  font-size: 16px;
  font-weight: 600;
  color: var(--jd-red);
  flex-shrink: 0;
}

/* ---- 失效商品区 ---- */
.invalid-card {
  background-color: #fafafa;
}

.invalid-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  color: #909399;
}

.invalid-row {
  /* 整行降低饱和度，视觉上"退到后面" */
  opacity: 0.65;
}

.invalid-row .row-name,
.invalid-row .row-price,
.invalid-row .row-subtotal {
  color: #909399;
}

.invalid-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 14px;
  letter-spacing: 2px;
}

.row-reason {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}

/* ---- 结算栏 ---- */
.checkout-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 16px 20px;
  background-color: #fff;
  border: 1px solid #ebeef5;
  /* sticky 到视口底部，滚动时也能随时看到合计和结算按钮 */
  position: sticky;
  bottom: 0;
}

.checkout-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.invalid-tip {
  font-size: 13px;
  color: #e6a23c;
}

.checkout-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.total {
  font-size: 14px;
  color: #606266;
}

.total b {
  color: var(--jd-red);
  font-size: 16px;
}

.total-amount {
  font-size: 22px;
  font-weight: 700;
  color: var(--jd-red);
}
</style>
