<script setup>
/**
 * 售后管理（管理端）。★ 里程碑 17 新增。
 *
 * <p>路由 {@code /after-sale}（★ 连字符，不是 {@code /afterSale} ——
 * 和 {@code /order} / {@code /product} 一样用短横线，URL 里的大小写不值得赌）。
 *
 * <h3>它是后台标准列表模板的第 4 个使用者</h3>
 *
 * <p>结构照 {@code views/order/List.vue} 那套四段式：
 * 搜索条件区 → 操作区 → 表格 → 分页器。那两条纪律照抄，不另起一套：
 * <ul>
 *   <li><b>查询条件用 {@code reactive} 本地状态，不进 URL</b></li>
 *   <li><b>搜索时必须把页码重置回第 1 页</b></li>
 * </ul>
 *
 * <h3>★★ 这一页的「操作」列和订单页有一个根本区别：三个动作的【可逆性】不同</h3>
 *
 * <pre>
 *   同意(仅退款)   →  钱当场退、库存当场还   → 不可逆 → 【必须】二次确认
 *   同意(退货退款) →  只是同意，钱和货都没动  → 可逆（还能拒绝） → 不确认
 *   拒绝           →  单子作废，用户可以再申请 → 可逆 → 不确认
 *   确认收到       →  钱当场退、库存当场还   → 不可逆 → 【必须】二次确认
 * </pre>
 *
 * <p>★ 判据是 {@code mall-shop} 的 {@code Pay.vue} 立下的那条：
 * <b>二次确认是「这个操作唯一的后悔机会」，只该出现在真正没有后悔机会的地方。</b>
 * 到处都弹确认的后果是<b>管理员学会了闭着眼点确定</b> ——
 * 那时候确认框就彻底失效了，包括最需要它的那一处。
 *
 * <h3>★ 为什么这一页的「同意」按钮文字要跟着类型变</h3>
 *
 * <p>仅退款 → 「同意并退款」，退货退款 → 「同意退货」。
 * 都叫「同意」的话，管理员会分不清自己刚刚做了什么 ——
 * 而这两种「做了什么」在钱和货上的差别是根本性的
 * （一个当场退钱，一个只是等买家寄回）。
 *
 * <h3>⚠️ 失败后无条件重查，理由同 {@code order/List.vue}</h3>
 *
 * <p>售后操作失败的真实原因<b>通常是「状态已经变了」</b>：
 * 另一个管理员抢先处理了。这时候页面上那一行还显示着旧状态，
 * 不重查的话管理员会对着一个错的状态反复点、反复失败。
 * 管理端的 {@code request.js} 不往 error 上挂 {@code err.code}，
 * 所以分不清「1002」和「网络断了」—— 既然分不清就一律重查。
 * 完整论证见 {@code order/List.vue} 里 {@code handleShip} 的注释。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveAfterSale,
  listAfterSales,
  receiveAfterSale,
  rejectAfterSale,
} from '@/api/afterSale'
import {
  AFTER_SALE_STATUS,
  AFTER_SALE_STATUS_OPTIONS,
  AFTER_SALE_TYPES,
  afterSaleReasonLabel,
  afterSaleStatusLabel,
  afterSaleStatusTagType,
  afterSaleTypeLabel,
} from '@/utils/afterSaleStatus'
// ★ payMethodLabel 在 orderStatus.js，不在 afterSaleStatus.js ——
//   退款去向是「支付方式」那张码表的事，不是售后状态这张。
import { payMethodLabel } from '@/utils/orderStatus'

// ---------------------------------------------------------------------------
// 列表数据
// ---------------------------------------------------------------------------

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

/**
 * 查询条件。
 *
 * <p>★ 四个筛选字段的语义不同，别混：
 * <pre>
 *   afterSaleNo   精确匹配（客服拿一个单号来问，要一步查到）
 *   memberKeyword 模糊匹配（管理员记得的是「姓张的那个」）
 *   type          精确匹配（1=仅退款 2=退货退款）
 *   status        null = 全部，而 0 是【真实状态】「待审核」
 * </pre>
 *
 * <p>⚠️ {@code status} 初始值必须是 {@code null} 而不是 0 ——
 * 写 0 的话页面一打开就只在查待审核的，而管理员看到「共 2 条」
 * 会以为系统里只有 2 张售后单。这个坑很隐蔽，因为它「看起来能跑」。
 *
 * <p>⚠️ {@code type} 同理，初始值也有 {@code null}（全部类型）。
 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  afterSaleNo: '',
  memberKeyword: '',
  status: null,
  type: null,
})

/**
 * ★ 每行一个 loading 标记，用【对象】按售后单号索引。
 *
 * <p>理由同 {@code order/List.vue}：不能用一个
 * {@code const acting = ref(false)} —— 那只允许「同时只有一个操作在进行」。
 * 管理员完全可能连着处理好几张单，用单个 ref 的话点第二行会把
 * 第一行的 loading 抢走，第一行的按钮提前恢复可点（他以为没点上，再点一次 → 1002）。
 *
 * <p><b>「一行一个」的状态就该按行存。</b>
 */
const acting = ref({})

/**
 * ★ 类型筛选下拉。
 *
 * <p>{@code label} 用函数算出来，<b>不写字面量</b> ——
 * 这里已经有 {@code afterSaleTypeLabel} 这个唯一来源，
 * 再抄一份文案就是「同一事实两份实现」。
 * 值仍然是 {@code AFTER_SALE_TYPES} 里的，不是新编的。
 */
const TYPE_FILTER_OPTIONS = [
  { value: null, label: '全部类型' },
  { value: AFTER_SALE_TYPES.ONLY_REFUND, label: afterSaleTypeLabel(AFTER_SALE_TYPES.ONLY_REFUND) },
  { value: AFTER_SALE_TYPES.RETURN_REFUND, label: afterSaleTypeLabel(AFTER_SALE_TYPES.RETURN_REFUND) },
]

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadData() {
  loading.value = true
  try {
    const res = await listAfterSales(query)
    tableData.value = res.list || []
    total.value = res.total || 0
  } catch {
    // 错误提示已在响应拦截器里统一处理。
    // ★ 失败要把列表清空：留着上一次的数据会让管理员以为
    //   看到的表格是当前筛选条件的结果 —— 那比空白更危险。
    tableData.value = []
    total.value = 0
  } finally {
    // 用 finally 保证无论成功失败都关掉转圈，否则一报错页面就一直转
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 搜索 / 分页
// ---------------------------------------------------------------------------

function handleSearch() {
  // ★ 搜索时必须把页码重置回第 1 页，否则在第 5 页搜一个只有 2 条结果的
  //   条件会查到空，管理员以为没搜到（理由见 order/List.vue）
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.afterSaleNo = ''
  query.memberKeyword = ''
  query.status = null // ★ 是 null（全部），不是 0
  query.type = null
  query.pageNum = 1
  loadData()
}

function handlePageChange(page) {
  query.pageNum = page
  loadData()
}

function handleSizeChange(size) {
  query.pageSize = size
  query.pageNum = 1
  loadData()
}

// ---------------------------------------------------------------------------
// 三个动作
// ---------------------------------------------------------------------------

/** 统一的「二次确认」封装。返回 true 表示用户可以继续 */
async function confirmAction(message, title, confirmText) {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
    })
    return true
  } catch {
    // ★ 必须 catch 掉：不 catch 的话用户每点一次「取消」
    //   控制台就报一个未捕获的 Promise 异常，淹没真正的错误。
    //   照 product/List.vue 的写法。
    return false
  }
}

/**
 * 同意。
 *
 * <p>★★ <b>只有「仅退款」需要二次确认，因为只有它当场动钱。</b>
 * 退货退款的「同意」只是把单子推到「待买家寄回」——
 * 一分钱没退、一件库存没还，而且还能拒绝（反悔）。
 * 给它弹一个「确定吗」，是在消耗管理员对确认框的注意力。
 *
 * <p>⚠️ 两条路径调的都是同一个接口 {@code approve} ——
 * <b>走哪条边是服务端按 {@code type} 决定的，不是前端选的。</b>
 * 前端分支只是为了把话说对。
 */
async function handleApprove(row) {
  const isOnlyRefund = row.type === AFTER_SALE_TYPES.ONLY_REFUND

  if (isOnlyRefund) {
    const amount = formatAmount(refundPreviewOf(row))
    const ok = await confirmAction(
      `确定同意这张仅退款申请吗？\n\n同意后将【立即退款 ¥${amount}】并归还库存，`
        + '此操作不可撤销。',
      '同意并退款',
      '确定退款',
    )
    if (!ok) return
  }

  acting.value[row.afterSaleNo] = true
  try {
    const updated = await approveAfterSale(row.afterSaleNo)
    replaceRow(row.afterSaleNo, updated)
    ElMessage.success(isOnlyRefund ? '已同意并退款' : '已同意退货，等待买家寄回')
  } catch {
    // ★ 无条件重查，理由见文件头注释
    loadData()
  } finally {
    delete acting.value[row.afterSaleNo]
  }
}

/**
 * 拒绝。
 *
 * <p>⚠️ <b>必须先让管理员填理由，再提交。</b>
 * 后端 {@code rejectReason} 是 {@code @NotBlank} 的 ——
 * 点一下直接提交的话，管理员会收到一条参数校验错误，
 * 然后以为按钮坏了。
 *
 * <p>★ 用 {@code ElMessageBox.prompt} 而不是自己写一个 dialog：
 * 这是一句话输入加一个校验，EP 的 prompt 支持 {@code inputValidator}，
 * 而且它自己处理了「点取消」的分支。为一个字段写一个 dialog
 * 得不偿失。<b>能用现成组件的交互，不要手搓。</b>
 */
async function handleReject(row) {
  let reason
  try {
    const res = await ElMessageBox.prompt(
      `拒绝后这张售后单会关闭，买家可以重新申请。请填写拒绝理由：`,
      `拒绝售后 ${row.afterSaleNo}`,
      {
        confirmButtonText: '确定拒绝',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例如：商品已使用，不支持无理由退款',
        // ★ 前端校验只是体验优化（把「填了吗」当场告诉管理员），
        //   真正的 @NotBlank 在服务端。前端挡不住直接调接口的人。
        inputValidator: (v) => (v && v.trim() ? true : '拒绝理由不能为空'),
      },
    )
    reason = res.value
  } catch {
    return // 用户点了取消
  }

  acting.value[row.afterSaleNo] = true
  try {
    const updated = await rejectAfterSale(row.afterSaleNo, reason.trim())
    replaceRow(row.afterSaleNo, updated)
    ElMessage.success('已拒绝')
  } catch {
    loadData()
  } finally {
    delete acting.value[row.afterSaleNo]
  }
}

/**
 * 确认收到退货 → 退款 + 归还库存。
 *
 * <p>★★ <b>这是整条链路上最不可逆的一步</b>，所以二次确认是必需的，
 * 而且文案必须说清「退款」—— 按钮叫「确认收到」很容易被当成
 * 一个单纯的物流记录动作，实际上它是<b>付款动作</b>。
 *
 * <p>⚠️ 只有「待卖家收货」显示它。这条约束在服务端的
 * {@code WHERE status = 2} 里守着 —— 前端不显示按钮只是体验，
 * 真正的闸门在 SQL。
 */
async function handleReceive(row) {
  const amount = formatAmount(refundPreviewOf(row))
  const ok = await confirmAction(
    `确定已收到买家寄回的商品吗？\n\n确认后将【立即退款 ¥${amount}】并归还库存，`
      + '此操作不可撤销。',
    '确认收到退货',
    '确认收到并退款',
  )
  if (!ok) return

  acting.value[row.afterSaleNo] = true
  try {
    const updated = await receiveAfterSale(row.afterSaleNo)
    replaceRow(row.afterSaleNo, updated)
    ElMessage.success('已确认收到，退款完成')
  } catch {
    loadData()
  } finally {
    delete acting.value[row.afterSaleNo]
  }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 用接口返回的新对象替换列表里那一行（所有状态迁移接口都返回完整售后单） */
function replaceRow(afterSaleNo, updated) {
  const idx = tableData.value.findIndex((a) => a.afterSaleNo === afterSaleNo)
  if (idx >= 0) {
    tableData.value[idx] = updated
  }
}

/**
 * 二次确认里那个「预计退款多少」。
 *
 * <p>★★ <b>这是纯展示，而且它一定会算错——那是故意的。</b>
 *
 * <p>真实的退款金额由服务端算，而它<b>取决于退款那一刻</b>的状态：
 * 运费退不退，要看那时候是不是「所有明细都退完了」。
 * 管理员看到这个对话框的时刻，这件事还没定（别的明细可能正在处理中）。
 * 所以这里能把话说准的只有「货款」这一部分。
 *
 * <p>⚠️ 所以文案里写的是「退款 ¥{@code subtitle}」——
 * <b>不算上运费</b>。真实的退款金额以服务端返回的
 * {@code refundAmount} 为准，列表刷新后那一格显示的就是真值。
 * ★ 宁可提示里<b>少报一个数</b>，也不能多报 ——
 * 多报的后果是「说好退 109，到账 99」，那是投诉。
 */
function refundPreviewOf(row) {
  return row.subtotal
}

/**
 * 金额显示：两位小数。
 *
 * <p>★ 后端 {@code BigDecimal} 的 {@code 228.70} 序列化成 JSON 数字之后
 * 变成 {@code 228.7} —— 尾随的 0 在 JSON 里不存在。不格式化的话
 * 同一笔钱在不同地方长得不一样，管理员会以为算错了。
 *
 * <p>⚠️ 这是 {@code mall-web} 里的【第二份】同功能函数
 * （第一份在 {@code order/List.vue}）。两个工程之间没有代码共享机制，
 * 同一工程内也没到「第四处」—— 照 {@code order/List.vue} 里那段
 * 「等第四处时再提取」的判断，这里先不动它。
 * <b>顺手改掉别人依赖的东西，是协作里最招人烦的一类改动。</b>
 */
function formatAmount(v) {
  return Number(v || 0).toFixed(2)
}

/** 这一行现在能不能同意 */
function canApprove(row) {
  return row.status === AFTER_SALE_STATUS.APPLIED
}

/**
 * 能不能拒绝。
 *
 * <p>★ <b>两个状态可以拒</b>：待审核，以及待买家寄回（管理员同意后反悔）。
 * 但<b>不能拒绝「待卖家收货」</b> —— 那时候货已经在路上了，
 * 唯一正确的动作是「确认收到」。
 *
 * <p>⚠️ 前端这里只是决定显不显示按钮。对应的 SQL 条件是
 * {@code WHERE status IN (0, 1)} —— 如果这里写错，
 * 管理员最多看到一个不该出现的按钮，点了会拿到 1002。
 */
function canReject(row) {
  return row.status === AFTER_SALE_STATUS.APPLIED
    || row.status === AFTER_SALE_STATUS.WAITING_RETURN
}

/** 只有「待卖家收货」能确认收到 */
function canReceive(row) {
  return row.status === AFTER_SALE_STATUS.WAITING_RECEIVE
}

onMounted(loadData)
</script>

<template>
  <div class="page">
    <!-- ============ 1. 搜索条件区 ============ -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="售后单号">
          <!--
            ★ 精确匹配。提示语必须说清「要完整单号」——
              客服拿一个号来问，输一半搜不到会以为系统坏了
          -->
          <el-input
            v-model="query.afterSaleNo"
            placeholder="完整售后单号"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="会员">
          <!-- ★ 模糊匹配：这一栏解决「姓张那个」这种查询 -->
          <el-input
            v-model="query.memberKeyword"
            placeholder="用户名或昵称"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="类型">
          <el-select v-model="query.type" placeholder="全部类型" clearable style="width: 130px">
            <el-option
              v-for="opt in TYPE_FILTER_OPTIONS"
              :key="String(opt.value)"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <!--
            ⚠️ 「全部状态」的 value 是 null（见 AFTER_SALE_STATUS_OPTIONS）——
              不要用 -1 之类的哨兵值表示「全部」，哨兵值迟早和真实取值撞车
          -->
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px">
            <el-option
              v-for="opt in AFTER_SALE_STATUS_OPTIONS"
              :key="String(opt.value)"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ============ 2. 操作区 + 3. 表格区 ============ -->
    <el-card shadow="never" class="table-card">
      <div class="toolbar">
        <!--
          ★ 这里【没有】「新增售后单」按钮 —— 售后单只能由用户申请产生，
            管理端不能凭空造一张。少了它是正确的，不是漏了。
        -->
        <span class="total-tip">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe style="width: 100%">
        <!--
          ★ 展开行：把「这一张单的全部细节」放在原地。
            理由同 order/List.vue —— 管理员的典型动作是【扫一屏，找哪张该处理】，
            跳详情页会打断这个节奏（列表的滚动位置和筛选条件都丢了）。
        -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-wrap">
              <!-- 申请信息 -->
              <div class="expand-block">
                <div class="block-title">申请信息</div>
                <div class="info-line">
                  类型：{{ afterSaleTypeLabel(row.type) }}
                  ／ 原因：{{ afterSaleReasonLabel(row.reason) }}
                </div>
                <!-- ★ 补充说明是用户自己写的，单独一行；没写也要明说一句，
                     否则那一块看起来像界面坏了 -->
                <div class="info-line muted">
                  补充说明：{{ row.description || '（未填写）' }}
                </div>
                <div class="info-line muted">申请时间：{{ row.createTime || '—' }}</div>
                <!-- ★ 拒绝理由只在被拒绝时才有值（non_null 会让这个键消失）-->
                <div v-if="row.rejectReason" class="info-line danger-text">
                  拒绝理由：{{ row.rejectReason }}
                </div>
              </div>

              <!-- 商品信息 -->
              <div class="expand-block">
                <div class="block-title">申请退款的商品</div>
                <div class="info-line">{{ row.productName }}</div>
                <div class="info-line muted">
                  {{ row.skuSpec || '无规格' }}
                </div>
                <div class="info-line muted">
                  ¥{{ formatAmount(row.price) }} × {{ row.quantity }}
                  = <span class="price">¥{{ formatAmount(row.subtotal) }}</span>
                </div>
              </div>

              <!-- 退款信息 -->
              <div class="expand-block">
                <div class="block-title">退款信息</div>
                <!--
                  ⚠️ refundAmount 在退款发生之前是 null，而 non_null 会让
                    这个键整个消失 —— 所以判断用 !row.refundAmount
                    而不是 === null。这个坑本轮咬过第三次。
                -->
                <div class="info-line">
                  退款金额：
                  <span v-if="row.refundAmount !== undefined && row.refundAmount !== null" class="price">
                    ¥{{ formatAmount(row.refundAmount) }}
                  </span>
                  <span v-else class="muted">尚未退款</span>
                </div>
                <!--
                  ★ refundFreight 是 NOT NULL DEFAULT 0.00 —— 0 是【真实值】
                    （不分摊运费），所以这里必须判 > 0，不能用 falsy 判断。
                    这和 refundAmount 的判法恰好相反，别记混。
                -->
                <div v-if="row.refundFreight > 0" class="info-line muted">
                  其中运费：¥{{ formatAmount(row.refundFreight) }}
                </div>
                <div class="info-line muted">
                  退款去向：{{ payMethodLabel(row.refundMethod) }}
                </div>
                <div class="info-line muted">退款时间：{{ row.refundTime || '—' }}</div>
              </div>

              <!-- 退货物流。★ 只有退货退款才有，仅退款这一块整块不出现 -->
              <div v-if="row.type === AFTER_SALE_TYPES.RETURN_REFUND" class="expand-block">
                <div class="block-title">退货物流</div>
                <div class="info-line">
                  {{ row.returnCompany || '—' }}　{{ row.returnTracking || '' }}
                </div>
                <div class="info-line muted">买家填单时间：{{ row.returnTime || '—' }}</div>
                <div class="info-line muted">确认收到时间：{{ row.receiveTime || '—' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="afterSaleNo" label="售后单号" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.afterSaleNo }}</span>
          </template>
        </el-table-column>

        <!-- ★ 订单号列：方便管理员跳到那笔订单去核对（本轮不做跳转，只展示） -->
        <el-table-column label="订单号" width="200">
          <template #default="{ row }">
            <span class="mono muted">{{ row.orderNo }}</span>
          </template>
        </el-table-column>

        <!-- ★ 会员列是管理端【独有】的 —— 用户端看到的每张单都是自己的 -->
        <el-table-column label="会员" width="130">
          <template #default="{ row }">
            <div class="member">
              <span>{{ row.memberNickname || row.memberUsername || '—' }}</span>
              <span class="muted small">{{ row.memberUsername }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            {{ afterSaleTypeLabel(row.type) }}
          </template>
        </el-table-column>

        <el-table-column label="退款金额" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.refundAmount !== undefined && row.refundAmount !== null" class="price">
              ¥{{ formatAmount(row.refundAmount) }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="afterSaleStatusTagType(row.status)" size="small">
              {{ afterSaleStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="申请时间" width="170" align="center" />

        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <!--
              ★★ 三个动作都是「不显示」而不是「显示成禁用」——
                和 order/List.vue 的发货按钮同一条规矩。
                判断标准是「这件事还存在于管理员的意图里吗」：
                  能同意吗？不能 —— 而且这个状态不会再变回来了。所以没有意义。
                ⚠️ 这只是「显示什么」，不是「允许什么」；
                   真正的闸门是后端那几条条件 UPDATE 的 WHERE。
            -->
            <el-button
              v-if="canApprove(row)"
              type="primary"
              link
              size="small"
              :loading="acting[row.afterSaleNo]"
              @click="handleApprove(row)"
            >
              <!-- ★ 文案跟着类型变，理由见文件头注释 -->
              {{ row.type === AFTER_SALE_TYPES.ONLY_REFUND ? '同意并退款' : '同意退货' }}
            </el-button>

            <el-button
              v-if="canReject(row)"
              type="danger"
              link
              size="small"
              :loading="acting[row.afterSaleNo]"
              @click="handleReject(row)"
            >
              拒绝
            </el-button>

            <el-button
              v-if="canReceive(row)"
              type="primary"
              link
              size="small"
              :loading="acting[row.afterSaleNo]"
              @click="handleReceive(row)"
            >
              确认收到
            </el-button>

            <!-- 终态：没有任何动作。明说一句，免得看起来像按钮没渲染出来 -->
            <span v-if="!canApprove(row) && !canReject(row) && !canReceive(row)" class="muted small">
              已处理
            </span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无售后数据" :image-size="80" />
        </template>
      </el-table>

      <!-- ============ 4. 分页器 ============ -->
      <div class="pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[5, 10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card :deep(.el-form-item) {
  margin-bottom: 0;
}

.table-card :deep(.el-card__body) {
  padding-top: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.total-tip {
  color: #909399;
  font-size: 13px;
}

.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}

.member {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.muted {
  color: #909399;
}

.small {
  font-size: 12px;
}

.price {
  color: #f56c6c;
  font-weight: 600;
}

.danger-text {
  color: #f56c6c;
}

/* 展开行。★ 缩进要留够，否则展开的内容看起来和表格行是同一层 */
.expand-wrap {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
  padding: 8px 16px 12px 48px;
}

.expand-block {
  min-width: 200px;
}

.block-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}

.info-line {
  font-size: 13px;
  line-height: 1.8;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
