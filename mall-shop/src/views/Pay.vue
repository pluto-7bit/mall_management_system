<script setup>
/**
 * 收银台。
 *
 * <p>路由 {@code /pay/:orderNo}，需要登录。
 *
 * <h3>★ 为什么是一个独立页面，而不是在订单列表上弹个按钮？</h3>
 *
 * <p>因为「付款」在真实的电商里是一个<b>有自己完整流程</b>的动作：
 * 确认金额、选支付方式、可能还有倒计时和风险提示。把它做成一个弹窗，
 * 用户会看不清自己在为什么付钱。
 *
 * <p>更实际的一个理由：<b>独立的 URL 是可以被刷新、被收藏、被客服发给用户的。</b>
 * 「你打开这个链接就能付款」是真实客服场景里天天发生的事，
 * 弹窗做不到这一点。
 *
 * <h3>★ 这个页面的五种状态</h3>
 *
 * <p>它本质上是一个<b>状态机渲染器</b>：拿到订单之后，按 {@code status}
 * 分成五块互斥的界面（待付款 / 已付款 / 已发货 / 已完成 / 已取消）。
 * 这个结构是刻意的 ——
 * <b>不要让「能不能支付」的判断散落在模板各处</b>，
 * 那样加一个状态就要改好几处。用一个 {@code v-if / v-else-if} 链把它们摊开，
 * 每个状态的界面长什么样一眼可见。
 *
 * <p>★ 里程碑 10 之前这里只有前三块，后两个状态用一个
 * 「该状态的详情页将在后续实现」的占位兜着 —— 也就是说
 * {@code OrderStatus} 里的 {@code SHIPPED} / {@code COMPLETED}
 * <b>是两个只有常量、没有界面的状态</b>。现在它们有界面了，
 * 那两行占位文案也就变成了假话，所以一起改掉。
 * （「注释说的是假话比没有注释更糟」—— 占位文案属于同一类东西。）
 *
 * <p>⚠️ 而且模板里的 {@code status === 0} 只决定<b>显示什么</b>，
 * 不决定<b>允许什么</b>。真正的闸门在服务端 ——
 * 所以即使这里判断错了，用户最多是看到一个不该显示的按钮，
 * 点了也会被后端拒绝（1002），不会真的发生不该发生的事。
 * <b>前端的判断永远只是"体验"，不是"安全"。</b>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cancelOrder, getOrder, payOrder } from '@/api/order'
import { formatAmount } from '@/utils/format'
import {
  ORDER_STATUS,
  PAY_METHODS,
  orderStatusLabel,
  orderStatusTagType,
  payMethodLabel,
} from '@/utils/orderStatus'

const route = useRoute()
const router = useRouter()

const orderNo = route.params.orderNo

const order = ref(null)
const loading = ref(true)
/** 订单不存在（含「不属于你」）—— 这是一种正常结果，不是故障，所以单独一个状态 */
const notFound = ref(false)
/** 真的出故障了（网络 / 500），和上面那个严格区分开 */
const loadFailed = ref(false)

const payMethod = ref('ALIPAY')
const paying = ref(false)
const cancelling = ref(false)

/**
 * 倒计时剩余秒数。
 *
 * <p>{@code null} 表示「没有倒计时这回事」（已付款、已取消，或者后端没给 payDeadline）。
 */
const remainSeconds = ref(null)
let timer = null

const status = computed(() => order.value?.status)

/**
 * 把后端给的时间串解析成 Date。
 *
 * <p>⚠️ <b>必须把空格换成 'T'。</b>
 * 后端返回的格式是 {@code "2026-09-22 20:43:55"}（JacksonConfig 配的），
 * 而 {@code new Date("2026-09-22 20:43:55")} 这个写法
 * <b>不是 ECMAScript 标准格式</b>：Chrome 会当成本地时间解析，
 * 但 Safari / 部分 Firefox 会直接返回 Invalid Date。
 * 换成 ISO 的 {@code "2026-09-22T20:43:55"}（不带时区）之后，
 * 所有浏览器都按<b>本地时间</b>解析，行为一致。
 *
 * <p>这个坑的隐蔽之处在于：<b>用 Chrome 开发时完全正常</b>，
 * 上到别的浏览器才发现倒计时是 NaN。
 */
function parseTime(s) {
  return new Date(String(s).replace(' ', 'T'))
}

/** 秒数 → "12:34" 或 "1:02:03" */
function formatRemain(sec) {
  if (sec == null || sec < 0) return '00:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

/**
 * 倒计时有没有走完。
 *
 * <p>走完之后要禁用支付按钮并提示「系统将自动取消」。
 * 但<b>这只是显示层的判断</b> —— 就算这里的时钟不准，
 * 用户点了支付也照样会被服务端拒绝。
 */
const expired = computed(() => remainSeconds.value !== null && remainSeconds.value <= 0)

const remainText = computed(() => formatRemain(remainSeconds.value))

/**
 * 启动倒计时。
 *
 * <p>用 {@code payDeadline}（服务端算好的绝对时刻）和<b>本机当前时间</b>比。
 * 所以用户改系统时间会看到错误的倒计时 —— 但没关系，那只是显示。
 */
function startCountdown() {
  stopCountdown()

  const deadline = order.value?.payDeadline
  if (!deadline) {
    remainSeconds.value = null
    return
  }

  const tick = () => {
    const diff = Math.floor((parseTime(deadline).getTime() - Date.now()) / 1000)
    remainSeconds.value = Math.max(diff, 0)

    if (diff <= 0) {
      // ★ 归零时把定时器停掉，别让它每秒空转。
      stopCountdown()
      // ★★ 再拉一次真实状态。
      //   因为定时扫描最多晚 10 秒（见 application.yml 的扫描间隔），
      //   此刻库里可能还是「待付款」，但也可能已经被扫掉了。
      //   前端不该去猜，问一次服务端最准。
      //   ⚠️ 但【不要】在这里无限重试 —— 扫不到就是还没到，
      //     用户刷新页面自然会看到最新状态。
      load({ silent: true })
    }
  }

  tick()
  timer = setInterval(tick, 1000)
}

/**
 * 停掉倒计时。
 *
 * <p>★★ 必须在 {@code onBeforeUnmount} 里调用。
 * 不清的话，用户跳到别的页面之后那个 setInterval 还在跑，
 * 每秒对着一个<b>已经卸载的组件</b>改状态 ——
 * 轻则白费 CPU，重则触发 Vue 的警告、甚至在已经销毁的组件上
 * 触发一次网络请求。
 *
 * <p>这个项目里已经有同类先例（{@code Home.vue} 里的轮播定时器）。
 * <b>凡是 setInterval，就必须有一个配对的 clearInterval，
 * 而且它要挂在「组件离开」这个事件上，不是挂在某个业务分支里。</b>
 */
function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/**
 * 加载订单。
 *
 * @param {object} [opts]
 * @param {boolean} [opts.silent] 静默刷新：不显示 loading、失败不改状态
 */
async function load(opts = {}) {
  if (!opts.silent) {
    loading.value = true
    notFound.value = false
    loadFailed.value = false
  }

  try {
    order.value = await getOrder(orderNo)
    startCountdown()
  } catch (err) {
    // ★★ 这里必须把「订单不存在」和「真的出故障了」分开。
    //
    //   1003 = 订单不存在，【包括订单是别人的】——
    //   后端故意用同一个码，不泄露「这个订单号存在，只是不属于你」。
    //   对用户来说这两种情况本来也没区别：他看不到这一单。
    //   所以给一个友好的空状态就够了。
    //
    //   其他情况（网络断了、500）才是故障，要给「重新加载」按钮。
    //
    //   ⚠️ 不能只写一个 catch 然后统一提示「加载失败」——
    //     那样用户拿到的是一句没有信息量的话，
    //     而「你打开的这个链接不对/过期了」和「服务器挂了」
    //     需要用户做的事完全不同。
    //   （这正是 request.js 拦截器为什么不把 err.code 丢掉的原因，
    //     见那里关于 ProductDetail.vue 那段注释。）
    if (err?.code === 1003) {
      notFound.value = true
      order.value = null
      stopCountdown()
    } else if (!opts.silent) {
      loadFailed.value = true
    }
    // 业务错误的提示语已经由 request.js 的统一拦截器弹过了，这里不再弹一次
  } finally {
    if (!opts.silent) loading.value = false
  }
}

/** 支付 */
async function handlePay() {
  paying.value = true
  try {
    // ★ 接口返回的是支付【之后】的订单，直接拿来刷新页面 ——
    //   不用再查一次。这是后端刻意的设计（三个接口都返回 OrderVO）。
    order.value = await payOrder(orderNo, payMethod.value)
    stopCountdown()
    ElMessage.success('支付成功')
  } catch {
    // 提示已由拦截器弹出。
    // ★ 但要重新拉一次状态：失败的原因可能是「被别人先付了」
    //   或者「已经超时被系统取消了」—— 这两种情况下
    //   界面上还显示着可支付的样子就是错的。问服务端要真相。
    load({ silent: true })
  } finally {
    paying.value = false
  }
}

/** 取消订单 */
async function handleCancel() {
  try {
    await ElMessageBox.confirm(
      '取消后订单不可恢复，占用的库存会释放。确定取消吗？',
      '取消订单',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '再想想' },
    )
  } catch {
    return // 用户点了「再想想」
  }

  cancelling.value = true
  try {
    order.value = await cancelOrder(orderNo)
    stopCountdown()
    ElMessage.success('订单已取消')
  } catch {
    load({ silent: true })
  } finally {
    cancelling.value = false
  }
}

function goShopping() {
  router.push('/')
}

/*
 * ★ 里程碑 10：本地的 formatAmount 已经删掉，改成从 utils/format.js import。
 *   里程碑 14 又把购物车和结算页那两份也收了 —— mall-shop 只剩那一份实现。
 *   当初为什么只改到这里、那笔账后来是怎么还的，完整写在 utils/format.js 的开头。
 */

onMounted(() => load())

// ★★ 组件离开时一定要清定时器，理由见 stopCountdown 的注释
onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="page-container pay-page">
    <h1 class="page-title">收银台</h1>

    <div v-loading="loading" class="pay-wrap">
      <!-- ============ 订单不存在（含不属于你）============ -->
      <el-result
        v-if="notFound"
        icon="warning"
        title="订单不存在"
        sub-title="链接可能已经失效，或者这笔订单不属于当前账号"
      >
        <template #extra>
          <el-button type="primary" @click="goShopping">去逛逛</el-button>
        </template>
      </el-result>

      <!-- ============ 加载失败（真故障）============ -->
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

      <template v-else-if="order">
        <!-- ============ 订单信息（三种状态都要显示）============ -->
        <el-card shadow="never" class="info-card">
          <div class="info-head">
            <span class="info-label">订单号</span>
            <span class="mono">{{ order.orderNo }}</span>
            <el-tag :type="orderStatusTagType(order.status)" size="small">
              {{ orderStatusLabel(order.status) }}
            </el-tag>
          </div>

          <div class="info-amount">
            <span class="info-label">应付金额</span>
            <span class="amount">¥{{ formatAmount(order.totalAmount) }}</span>
          </div>

          <!--
            ★ 里程碑 17：把运费单独列出来。

            ★ 为什么判断写 freightAmount > 0 而不是 freightAmount === null？
              因为 freight_amount 在数据库里是 NOT NULL DEFAULT 0.00，
              满额包邮的订单拿到的是【0】，不是「字段消失」——
              === null 会让这一行永远不显示（判断恒为 false）。
              （本项目里「字段消失」是另一套机制：non_null 把值为 null 的 key
                整个删掉，那是 payDeadline / shipTime 那种可空字段的写法。
                ★ 同一份代码里有两种「没有」，判断方式不能串。）

            ★ 为什么这一行写「其中运费」而不是在上方再加一行「商品合计」？
              因为「商品合计」= totalAmount − freightAmount 是一次【减法】，
              而「各明细小计之和」是【加法】—— 那是同一事实的两份实现，
              分岔时不会报错（后端已经有一条断言专门守着它们，见
              sql/test-after-sale.py）。前端再算一遍等于把那个坑搬到第三处。
              这一页的权威数字只有 total_amount 一个，其余靠这句文案说清楚关系。
          -->
          <div v-if="order.freightAmount > 0" class="info-amount freight-line">
            <span class="info-label">其中运费</span>
            <span class="freight-amount">¥{{ formatAmount(order.freightAmount) }}</span>
          </div>

          <div class="info-sub">
            收货：{{ order.receiverName }} {{ order.receiverPhone }}
          </div>
          <div class="info-sub muted">{{ order.receiverAddress }}</div>

          <!-- 明细。★ 收银台必须让用户看清这笔钱花在了什么上 -->
          <ul class="item-list">
            <li v-for="(it, i) in order.items" :key="i" class="item-row">
              <span class="item-name">{{ it.productName }}</span>
              <!--
                ★ 规格文本（{@code sku_spec} 的快照）。里程碑 15 阶段 5 补上。

                ★ 为什么收银台【也】要显示它：和 {@code Orders.vue} 同一个理由 ——
                  下单时同一件商品可能占了两行明细（「黑色」和「白色」），
                  只显示商品名的话这两行长得一模一样，用户看着
                  ¥9.90 × 1 和 ¥39.80 × 2 两行，<b>没法确定哪一行是自己选的哪个规格</b>，
                  而这一页正是他按下「立即支付」之前最后能核对的地方。

                ⚠️ 位置和 Orders.vue 不同：这里放在 {@code .item-name} 的<b>外面</b>。
                  因为这一页的 .item-name 是 nowrap + ellipsis 的，
                  嵌进去的话长商品名会把规格一起截掉 —— 而规格恰恰是这一行
                  唯一能和相邻那行区分开的信息。**截断必须截在次要信息上。**

                ⚠️ 判断用宽松真值：{@code sku_spec} 是 NOT NULL DEFAULT ''，
                  无规格商品和历史订单都是空串（显示空串等于不显示）。
                  同一个对象里 {@code it.skuId} 却是【可能整个 key 消失】的 ——
                  两者的 null 规则不同，别顺手写成 === null。
              -->
              <span v-if="it.skuSpec" class="item-spec">{{ it.skuSpec }}</span>
              <span class="item-qty">× {{ it.quantity }}</span>
              <span class="item-sub">¥{{ formatAmount(it.subtotal) }}</span>
            </li>
          </ul>
        </el-card>

        <!-- ============ 待付款：倒计时 + 选支付方式 + 两个按钮 ============ -->
        <el-card v-if="status === ORDER_STATUS.PENDING_PAY" shadow="never" class="pay-card">
          <!--
            ★ 倒计时。用 el-alert 而不是普通文字：
              它是一个【有时效性的提醒】，值得被视觉上突出。
              expired 之后换成 warning 类型，颜色从"注意"变成"警告"。
          -->
          <el-alert
            v-if="remainSeconds !== null"
            :type="expired ? 'warning' : 'info'"
            :closable="false"
            show-icon
            class="countdown"
          >
            <template #title>
              <template v-if="expired">
                订单已超时，系统将自动取消并释放库存
              </template>
              <template v-else>
                请在 <b class="mono">{{ remainText }}</b> 内完成支付，超时订单将自动取消
              </template>
            </template>
          </el-alert>

          <div class="pay-methods">
            <div class="section-label">选择支付方式</div>
            <!--
              ★ 用 el-radio-group 而不是三个 el-button：
                这是「三选一」，radio 的语义和键盘可达性都是对的。
                用按钮做单选，键盘用户和读屏用户就用不了。
            -->
            <el-radio-group v-model="payMethod" class="method-group">
              <el-radio
                v-for="m in PAY_METHODS"
                :key="m.value"
                :value="m.value"
                border
                class="method-item"
              >
                <el-icon class="method-icon"><component :is="m.icon" /></el-icon>
                {{ m.label }}
              </el-radio>
            </el-radio-group>
          </div>

          <div class="pay-actions">
            <!--
              ★ 超时后【禁用】而不是隐藏支付按钮。
                和「已付款不显示取消按钮」是相反的两种处理，理由不同：
                  - 已付款：取消这件事对这个订单【不存在】，没有按钮可显示
                  - 已超时：支付这件事【仍然存在于用户的意图里】，
                    他只是错过了时间。禁用一个还在原位的按钮 +
                    上面的提示语，比按钮凭空消失更容易理解
            -->
            <el-button
              type="primary"
              size="large"
              class="submit-btn"
              :loading="paying"
              :disabled="expired"
              @click="handlePay"
            >
              立即支付
            </el-button>
            <el-button size="large" :loading="cancelling" @click="handleCancel">
              取消订单
            </el-button>
          </div>

          <p class="mock-hint">
            <!-- ★ 必须如实说明这是一个模拟的收银台，否则用户会以为真的扣了钱 -->
            这是模拟支付，不会真实扣款。点「立即支付」后订单会变成「已付款」，
            你可以随时取消未付款的订单。
          </p>
        </el-card>

        <!-- ============ 已付款 ============ -->
        <el-result
          v-else-if="status === ORDER_STATUS.PAID"
          icon="success"
          title="支付成功"
          :sub-title="`支付方式：${payMethodLabel(order.payMethod)}　支付时间：${order.payTime || '—'}`"
        >
          <template #extra>
            <el-button type="primary" @click="goShopping">继续购物</el-button>
          </template>
        </el-result>

        <!-- ============ 已取消 ============ -->
        <!--
          ★ 把「谁取消的」说清楚。
            超时自动取消和用户主动取消在数据库里是同一个状态（都是 4），
            因为之后能做的事完全一样。但界面文案不该含糊 ——
            用户有权知道「我没点过取消，它怎么没了」。
            判断方式：取消时间和支付时限比一下（这就是当初不加
            cancel_type 列时说的"可推导"）。
        -->
        <el-result
          v-else-if="status === ORDER_STATUS.CANCELLED"
          icon="info"
          title="订单已取消"
          :sub-title="`取消时间：${order.cancelTime || '—'}`"
        >
          <template #extra>
            <el-button type="primary" @click="goShopping">继续购物</el-button>
          </template>
        </el-result>

        <!-- ============ 已发货 ============ -->
        <!--
          ★ 这一档要说清两件事：货已经在路上了（用户接下来只有等），
            以及【用户该去哪儿做「确认收货」】——
            收银台本身不提供这个操作。

          ⚠️ 这是一个刻意的分工，不是遗漏：
            「确认收货」属于「我的订单」页，因为那是用户管理自己订单的地方；
            收银台只负责「和这一笔钱有关的事」。
            在这里再放一个确认收货按钮，就会出现两个可以做同一件事的地方，
            而后端返回的状态刷新逻辑要跟着维护两份。
            多一个入口看起来是"更方便"，实际是多一处会不一致的地方。
        -->
        <el-result
          v-else-if="status === ORDER_STATUS.SHIPPED"
          icon="success"
          title="卖家已发货"
          :sub-title="`发货时间：${order.shipTime || '—'}　请收到货后到「我的订单」确认收货`"
        >
          <template #extra>
            <el-button type="primary" @click="router.push('/orders')">去我的订单</el-button>
          </template>
        </el-result>

        <!-- ============ 已完成 ============ -->
        <!--
          ★ 已完成 = 用户确认过收货了。这是订单的【终态】，
            后面不会再有变化，所以界面只陈述事实、不给任何操作入口 ——
            连「去我的订单」都可以不给（列表里已有它，没什么可做的）。
            这里给「继续购物」，因为一个走完流程的用户下一件事就是买东西。
        -->
        <el-result
          v-else-if="status === ORDER_STATUS.COMPLETED"
          icon="success"
          title="交易已完成"
          :sub-title="`完成时间：${order.completeTime || '—'}`"
        >
          <template #extra>
            <el-button type="primary" @click="goShopping">继续购物</el-button>
          </template>
        </el-result>

        <!-- ============ 兜底 ============ -->
        <!--
          ★ 这个 v-else 现在【不是】给任何已知状态留的位。
            上面五个状态已经穷尽了 OrderStatus 的全部取值，
            所以正常情况下它一次都不会走到。

            留着它是因为：后端将来加了新状态（比如「退款中」）时，
            老版本的前端会走到这里 —— 显示一个只有标题的空页面，
            总比显示一片空白或者干脆白屏要好。
            ★ 注意它的文案【不再编造这个状态是什么】：
              标题直接用 orderStatusLabel(order.status)，而它对不认识的码
              退回的是「未知状态」这种【承认自己不知道】的说法。
              猜错了比不猜更糟 —— 用户会按一个错误的理解去行动。
        -->
        <el-result
          v-else
          icon="info"
          :title="orderStatusLabel(order.status)"
          sub-title="这一单暂时没有更多可操作的内容"
        >
          <template #extra>
            <el-button type="primary" @click="goShopping">继续购物</el-button>
          </template>
        </el-result>
      </template>
    </div>
  </div>
</template>

<style scoped>
.pay-page {
  padding-top: 20px;
  padding-bottom: 40px;
}

.page-title {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 400;
  color: #333;
}

.pay-wrap {
  min-height: 300px;
}

/* 圆角交给 --el-card-border-radius（见 theme.css），这里不重复写 */
.info-card,
.pay-card {
  margin-bottom: 16px;
}

.info-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.info-label {
  color: #999;
  font-size: 12px;
}

.mono {
  font-family: Consolas, Menlo, monospace;
}

.info-amount {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 14px;
}

/*
 * 金额。★ 用 --jd-red 而不是 EP 的 danger 色 ——
 * 它们在本项目里其实是同一个值（theme.css 里把 danger 也设成了 #e1251b），
 * 但语义不同：这里是「价格」，不是「危险」。
 * 语义色和品牌色恰好同值是一个巧合，不该在代码里把它当成同一件事。
 */
.amount {
  color: var(--jd-red);
  font-size: 24px;
  font-weight: 700;
}

/*
 * ★ 里程碑 17：运费那一行。
 * 比 .amount 明显小、也明显不红 —— 它不是用户要付的数，
 * 只是「他要付的那个数由什么组成」的一句注解。
 * 做成和 .amount 一样大，会让「应付 109」和「运费 10」看起来像两笔钱。
 */
.freight-line {
  margin-top: -8px;
  margin-bottom: 14px;
}

.freight-amount {
  color: #666;
  font-size: 14px;
  font-weight: 600;
}

.info-sub {
  font-size: 13px;
  line-height: 1.7;
  color: #333;
}

.muted {
  color: #999;
}

.item-list {
  margin: 14px 0 0;
  padding: 12px 0 0;
  list-style: none;
  border-top: 1px solid var(--jd-border-light);
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
  ★ 规格。flex: none 是必须的：默认值 flex-shrink: 1 会让它在商品名
    被挤的时候跟着收缩，而规格比名字重要（见上面模板里的注释）。
    写死不让它缩，宁可让本来就带省略号的商品名先短。
*/
.item-spec {
  flex: none;
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

.countdown {
  margin-bottom: 18px;
}

.section-label {
  margin-bottom: 10px;
  font-size: 13px;
  color: #666;
}

.method-group {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

/*
 * ★ 支付方式选项做成「卡片」的样子，而不是 EP 默认的小圆点。
 *
 *   这么做是为了让选中态明显 —— 用户在这一步做的选择会写进数据库
 *   （orders.pay_method），值得一个清晰的视觉反馈。
 *
 *   ⚠️ 这里用的是 :deep()，因为 el-radio--border 的边框是 EP 内部
 *      元素的样式，scoped 的 data-v 属性选择不到它。
 *      （同 Cart.vue / Addresses.vue 里的用法。）
 */
.method-group :deep(.method-item) {
  height: auto;
  padding: 12px 18px;
  margin-right: 0;
}

.method-item :deep(.el-radio__label) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.method-icon {
  font-size: 16px;
}

.pay-actions {
  display: flex;
  gap: 12px;
  margin-top: 22px;
}

/*
 * ★ .submit-btn[data-v-x] 的特异性是 (0,2,0)，
 *   而 .el-button--primary 是 (0,1,0) —— 所以这里能压过 EP 的默认样式。
 *   这是 scoped CSS 下改 EP 组件样式时最常踩的一个坑：
 *   不加自己的类名、只写 .el-button 的话，特异性不够，改了不生效。
 */
.submit-btn {
  min-width: 140px;
}

.mock-hint {
  margin: 16px 0 0;
  padding-top: 14px;
  border-top: 1px dashed var(--jd-border-light);
  font-size: 12px;
  line-height: 1.7;
  color: #999;
}
</style>
