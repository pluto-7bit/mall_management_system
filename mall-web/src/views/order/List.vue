<script setup>
/**
 * 订单管理（管理端）。
 *
 * <p>路由 {@code /order}（★ 单数，不是用户端的 {@code /orders}）。
 *
 * <h3>它是后台标准列表模板的第 3 个使用者</h3>
 *
 * <p>结构照 {@code views/product/List.vue} 那套四段式：
 * 搜索条件区 → 操作区 → 表格 → 分页器。
 * 那两条已经立过的纪律照抄，不另起一套：
 * <ul>
 *   <li><b>查询条件用 {@code reactive} 本地状态，不进 URL</b></li>
 *   <li><b>搜索时必须把页码重置回第 1 页</b></li>
 * </ul>
 *
 * <h3>★ 但这一页有三件事在前两个列表页里没有</h3>
 *
 * <ol>
 *   <li><b>每行一个异步操作（发货）。</b>
 *       商品页的编辑/删除要么是弹窗、要么快得看不出延迟，
 *       所以那边没有 per-row loading 可以抄。<b>这里是第一个。</b></li>
 *   <li><b>展开行（{@code type="expand"}）。</b>项目里第一次用。</li>
 *   <li><b>失败后要重新拉数据。</b>见下面 {@code handleShip} 里的说明 ——
 *       这一条和 mall-web 其它页面「失败就失败，不重查」的写法不同。</li>
 * </ol>
 *
 * <h3>★ 查询条件为什么【不】放 URL（和用户端刻意相反）</h3>
 *
 * <p>{@code mall-shop} 的「我的订单」页把 {@code status} / {@code pageNum}
 * 都放在 URL 里（{@code /orders?status=1&pageNum=2}），因为这个工程需要
 * 「把地址发给别人」。管理端<b>没有这个需求</b>：没有管理员会把
 * 「商品管理第 3 页、只看下架」这个地址发给同事。
 *
 * <p>所以这里跟随 {@code mall-web} 自己的惯例（商品、分类列表都是
 * 本地 {@code reactive}），<b>不要把 {@code mall-shop} 那套
 * {@code updateQuery} 模式搬过来</b> —— 在一个只有一处使用者的地方
 * 引入一套 URL 同步层，是纯粹的不一致。
 * <b>两个工程各自内部一致，比两个工程长得一样重要。</b>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addLogisticsTrace,
  deleteLogisticsTrace,
  getOrderList,
  getOrderLogistics,
  shipOrder,
} from '@/api/order'
import {
  ORDER_STATUS,
  ORDER_STATUS_OPTIONS,
  orderStatusLabel,
  orderStatusTagType,
  payMethodLabel,
} from '@/utils/orderStatus'
import {
  LOGISTICS_STATUS,
  LOGISTICS_STATUS_OPTIONS,
  logisticsStatusLabel,
  logisticsStatusTagType,
} from '@/utils/logisticsStatus'

// ---------------------------------------------------------------------------
// 列表数据
// ---------------------------------------------------------------------------

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

/**
 * 查询条件。
 *
 * <p>★ 三个筛选字段的语义不同，别混：
 * <pre>
 *   orderNo       精确匹配（订单号是唯一标识）
 *   memberKeyword 模糊匹配（管理员记得的是「张三那单」）
 *   status        null = 全部，而 0 是【真实状态】「待付款」
 * </pre>
 *
 * <p>⚠️ {@code status} 初始值必须是 {@code null} 而不是 0 ——
 * 写 0 的话页面一打开就只在查待付款的订单，而管理员看到「共 3 条」
 * 会以为系统里只有 3 单。这个坑很隐蔽，因为它「看起来能跑」。
 * （和 {@code mall-shop} 那边 {@code updateQuery} 要小心别把 0 当空值删掉，
 * 是同一个数字的两面。）
 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  orderNo: '',
  memberKeyword: '',
  status: null,
})

/**
 * ★★ 里程碑 18：这里原来有一个 {@code const shipping = ref({})} ——
 * 按订单号索引的「每行一个 loading」，理由是「一行一个的状态就该按行存」。
 *
 * <p>那个理由仍然对，但<b>它的前提没了</b>：发货从「点一下就发」变成了
 * 「点开对话框 → 填承运商和单号 → 提交」。异步操作整个搬进了对话框，
 * 而<b>对话框同时只能开一个</b> —— 于是 loading 自然属于那个对话框
 * （{@code shipSubmitting}），不再需要按行存。
 *
 * <p>★ 删掉它而不是留着「以防万一」：一个零读者的 ref 会让人以为
 * 发货还是按行进行的，从而去找那个根本不存在的 per-row loading。
 */


// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadData() {
  loading.value = true
  try {
    const res = await getOrderList(query)
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
// 搜索
// ---------------------------------------------------------------------------

function handleSearch() {
  // ★ 关键：搜索时必须把页码重置回第 1 页。
  //   否则：你在第 5 页，然后搜一个只有 2 条结果的关键词，
  //   后端按 pageNum=5 去查，一条都查不到，页面显示「暂无数据」，
  //   管理员会以为没搜到，其实数据在第 1 页。
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.orderNo = ''
  query.memberKeyword = ''
  query.status = null // ★ 是 null（全部），不是 0
  query.pageNum = 1
  loadData()
}

// ---------------------------------------------------------------------------
// 分页
// ---------------------------------------------------------------------------

function handlePageChange(page) {
  query.pageNum = page
  loadData()
}

function handleSizeChange(size) {
  query.pageSize = size
  // 改每页条数后当前页码可能已经超出总页数，同样回第 1 页
  query.pageNum = 1
  loadData()
}

// ---------------------------------------------------------------------------
// 发货
// ---------------------------------------------------------------------------

/**
 * ★★ 里程碑 18：发货从「一次纯确认」变成了「一张表单」。
 *
 * <p>原来这里是一个 {@code ElMessageBox.confirm}（纯确认，无表单），
 * 发完货任何地方都查不到这一单是怎么发出去的 —— 系统知道
 * 「货发出去了」，但不知道「货是怎么出去的」。
 *
 * <p>★ <b>没有在对话框外面再叠一层 {@code ElMessageBox.confirm}</b>。
 * 对话框里的「确定发货」按钮<b>本身就是那次确认</b> ——
 * 叠两层会让「发货后不可撤销」这句话失去分量（点两次「确定」
 * 之后人就不再读那两段文字了）。
 *
 * <p>★ <b>没有快递公司的下拉，是自由文本</b>。
 * 给了下拉等于承诺「这里能选的都能用」，而运营遇到不在列表里的
 * 快递公司时<b>不知道该不该手填</b>。真要做得更好，那是
 * 「带搜索的可输入下拉」（{@code el-select} + filterable + allow-create），
 * 而那需要一份「全国快递公司」清单的维护者 —— 本轮不做。
 *
 * <p>★★ 提交失败后<b>无条件重新拉一次数据</b> —— 这一点和 mall-web
 * 其它页面不同，理由值得写清楚：
 *
 * <p>发货失败的<b>真实原因通常是「状态已经变了」</b>：
 * 另一个管理员抢先发了货、或者这单已经被用户取消/超时取消了。
 * 这时候页面上那一行还显示着「已付款 + 发货按钮」的样子，
 * 但它已经不对了 —— <b>不重查的话，管理员会对着一个错的状态
 * 反复点、反复失败，然后来报 bug。</b>
 * 问服务端要真相，比在前端猜便宜得多。
 *
 * <p>⚠️ 那为什么不能像 {@code mall-shop} 的 {@code Orders.vue} 那样，
 * 「只对某个业务码重查」？因为 <b>mall-web 的 {@code request.js}
 * 不会往 error 上挂 {@code err.code}</b>（它自己的注释明确指出了这一点，
 * 并点名 {@code mall-shop} 那份才是正确做法）。
 * 所以管理端分不清「1002 状态不允许发货」和「网络断了」——
 * 既然分不清，就一律重查：代价是一次多余的请求，
 * 换来的是「不管什么原因失败，界面都不会停在一个错的状态上」。
 *
 * <p>★ 本轮的答案是<b>不动 {@code request.js}</b>：
 * 为了这一个页面去改全工程共用的拦截器，风险方向不对
 * （改坏了每个页面都会受影响）。哪天真要升级它，
 * 它自己的注释要一起改。
 */
const shipVisible = ref(false)
const shipFormRef = ref(null)
const shipSubmitting = ref(false)
const shipForm = reactive({
  orderNo: '',
  logisticsCompany: '',
  trackingNo: '',
})

/**
 * 校验规则。
 *
 * ⚠️ 这里的 50 / 64 <b>和后端 {@code OrderShipDTO} 的 {@code @Size}
 * 是同一个数</b>，抄两遍是刻意的：前端这一份负责「别让他提交」，
 * 后端那一份负责「提交了也不认」。<b>只有后端那一份是闸门</b> ——
 * 前端这份漏了或者写错了，最坏结果是管理员看到一次服务端报错，
 * 而不是脏数据进库。
 */
const shipRules = {
  logisticsCompany: [
    { required: true, message: '请填写快递公司', trigger: 'blur' },
    { max: 50, message: '快递公司最多 50 个字', trigger: 'blur' },
  ],
  trackingNo: [
    { required: true, message: '请填写快递单号', trigger: 'blur' },
    { max: 64, message: '快递单号最多 64 个字', trigger: 'blur' },
  ],
}

function openShip(row) {
  shipForm.orderNo = row.orderNo
  // ★ 每次打开都清空 —— 不清的话，上一次填的运单号会留在框里，
  //   而管理员很可能直接点「确定发货」，把上一单的单号发给这一单。
  shipForm.logisticsCompany = ''
  shipForm.trackingNo = ''
  shipVisible.value = true
}

/** 对话框渲染完成后清掉校验红字（上一次打开留下的） */
function onShipOpen() {
  shipFormRef.value?.clearValidate()
}

async function submitShip() {
  try {
    await shipFormRef.value.validate()
  } catch {
    return // 校验没过，红字已经显示在字段下面了
  }

  shipSubmitting.value = true
  try {
    // ★ 接口返回的是发货【之后】的订单 —— 直接拿来替换列表里那一行，
    //   不用重查整页。这是后端刻意的设计（所有状态迁移接口都返回完整订单）。
    const updated = await shipOrder(shipForm.orderNo, {
      logisticsCompany: shipForm.logisticsCompany,
      trackingNo: shipForm.trackingNo,
    })
    const idx = tableData.value.findIndex((o) => o.orderNo === shipForm.orderNo)
    if (idx >= 0) {
      tableData.value[idx] = updated
    }
    ElMessage.success('已发货')
    shipVisible.value = false

    // ★ 注意这里【没有】翻页回退。
    //   发货只是把状态从「已付款」改成「已发货」，那一行<b>还在列表里</b>
    //   （除非管理员正好按「已付款」筛着 —— 那种情况下它会从当前筛选里消失，
    //   页数少一条。但这里不做回退，因为：
    //     1. 默认是「全部状态」，绝大多数时候行不会消失
    //     2. 就算消失了，管理员看到的是一页少了一条，而不是一片空白
    //        （对比商品删除：删掉最后一条会看到空页，那个必须回退）
    //   不做「看起来更完整」的补偿逻辑，是因为它在这个场景里
    //   带来的复杂度大于它解决的问题。
  } catch {
    // ★ 无条件重查（对话框不关，让管理员改完再试）。理由见上面那段。
    loadData()
  } finally {
    shipSubmitting.value = false
  }
}

// ---------------------------------------------------------------------------
// 物流（里程碑 18）
// ---------------------------------------------------------------------------

/**
 * 物流对话框的状态。
 *
 * <p>★ {@code logisticsData} 里装的是<b>服务端返回的整个 VO</b>
 * （承运商 + 单号 + 发货时间 + 轨迹），而不是从列表那一行抄一份 ——
 * 列表行里<b>没有轨迹</b>（那是刻意的：列表不嵌轨迹数组，
 * 否则每翻一页都要把所有订单的轨迹全查一遍）。
 *
 * <p>★ {@code traces} 的初始值是 {@code { traces: [] }} 而不是 {@code null}：
 * 对话框一打开就开始渲染时间线，{@code v-for} 撞上 null 会白屏。
 * （服务端也保证这个字段永远是数组，两边一致。）
 */
const logisticsVisible = ref(false)
const logisticsLoading = ref(false)
const logisticsOrderNo = ref('')
const logisticsData = ref({ traces: [] })

const traceFormRef = ref(null)
const traceSubmitting = ref(false)
const traceForm = reactive({
  status: null,
  description: '',
  traceTime: '',
})

const traceRules = {
  status: [{ required: true, message: '请选择节点状态', trigger: 'change' }],
  description: [
    { required: true, message: '请填写这一节点的说明', trigger: 'blur' },
    { max: 255, message: '说明最多 255 个字', trigger: 'blur' },
  ],
  traceTime: [{ required: true, message: '请选择时间', trigger: 'change' }],
}

/**
 * 当前选中的节点是不是「已签收」。
 *
 * <p>★ 用它驱动那句红色警示的 {@code v-if}，以及提交后要不要重查列表。
 * <b>判断依据是码，不是「说明里有没有那三个字」</b> ——
 * 后者会在有人写「已签收失败，改约明天」的那天静默出错。
 */
const willComplete = computed(() => traceForm.status === LOGISTICS_STATUS.SIGNED)

/**
 * 「现在」的 'YYYY-MM-DD HH:mm:ss'。
 *
 * <p>★ 只在<b>打开对话框时</b>算一次，作为新增节点的默认值 ——
 * 因为 el-date-picker 绑的是一个字符串（{@code value-format}），
 * 而它需要一个初始值，否则框里是空的、管理员得手点一下。
 *
 * <p>⚠️ 这只是【默认值】，不是兜底：管理员可以改成过去的时刻
 * （<b>补录是这个功能的默认用法</b> —— 白天忙，晚上一次性补录），
 * 服务端也不做「不许填过去」的限制。理由见
 * {@code api/order.js} 的 {@code addLogisticsTrace}。
 */
function nowText() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function resetTraceForm() {
  traceForm.status = null
  traceForm.description = ''
  traceForm.traceTime = nowText()
}

/**
 * 打开物流对话框。
 *
 * <p>★ <b>先显示 loading 再发请求</b>（而不是等请求回来才打开）——
 * 否则从点击到弹窗出现之间有一段空白，管理员会以为按钮没生效。
 * 弹窗立刻出现 + 转圈，是「已经收到你的点击了」的即时反馈。
 */
async function openLogistics(row) {
  logisticsOrderNo.value = row.orderNo
  logisticsData.value = { traces: [] }
  resetTraceForm()
  logisticsVisible.value = true
  logisticsLoading.value = true
  try {
    logisticsData.value = await getOrderLogistics(row.orderNo)
  } catch {
    // 提示已在响应拦截器里统一处理。★ 这里【不关】对话框 ——
    // 关掉的话管理员只会看到一个弹窗闪过去，不知道发生了什么。
  } finally {
    logisticsLoading.value = false
  }
}

function onLogisticsOpen() {
  traceFormRef.value?.clearValidate()
}

/** 新增一个轨迹节点。★ 录「已签收」会连带把订单推到「已完成」 */
async function submitTrace() {
  try {
    await traceFormRef.value.validate()
  } catch {
    return
  }

  traceSubmitting.value = true
  try {
    // ★ 服务端返回的是【录完之后】的完整物流 —— 拿它整个替换本地数据，
    //   不用再查一次。这也是「前端不自己往数组里 push」的原因：
    //   服务端按 (trace_time DESC, id DESC) 排好了序，前端 push 出来的
    //   顺序只对「时间一直往前」这一种情况成立，补录一条就要错。
    logisticsData.value = await addLogisticsTrace(logisticsOrderNo.value, {
      status: traceForm.status,
      description: traceForm.description,
      traceTime: traceForm.traceTime,
    })

    // ★ 只有「已签收」会改订单状态，所以只有它需要重查列表 ——
    //   对话框背后那一行还写着「已发货」，不重查的话管理员看到的
    //   是一个过期的状态。其它节点不动订单，重查是白花一次请求、
    //   而且会把表格的展开状态弄丢。
    if (willComplete.value) {
      // ⚠️ 这里【不能】写「订单已标记为已完成」：服务端那条 SQL 的
      //   WHERE status = 2 可能没匹配到（这单本来就已完成过），
      //   那种情况下什么也没发生，而前端的断言会是一句假话。
      ElMessage.success('已记录「已签收」')
      loadData()
    } else {
      ElMessage.success('已记录')
    }
    resetTraceForm()
    traceFormRef.value?.clearValidate()
  } catch {
    // 失败时【不】重查：这一条没写进去，订单状态也不可能变
  } finally {
    traceSubmitting.value = false
  }
}

/**
 * 删除一个录错的节点。
 *
 * <p>⚠️ <b>删掉一条「已签收」不会把订单退回「已发货」</b> ——
 * 这是决定不是 bug（订单状态机没有反向边）。
 * 所以这里的二次确认文案不能写「删除后订单会恢复」，
 * 那句话会让管理员以为自己可以「撤销」一次签收。
 *
 * <p>★ 轨迹只允许新增和删除，<b>没有修改</b>：一条被改过的轨迹
 * 不是轨迹。改错的做法是删了重录。
 */
async function removeTrace(trace) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${logisticsStatusLabel(trace.status)}　${trace.traceTime}」这条记录吗？`
        + '删除后不可恢复。订单状态不会因此回退。',
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户点了取消 —— cancel 的 reject 必须 catch 掉，
    // 否则每点一次「取消」控制台就报一个未捕获的 Promise 异常（照 product/List.vue:139）
  }

  try {
    logisticsData.value = await deleteLogisticsTrace(logisticsOrderNo.value, trace.id)
    ElMessage.success('已删除')
  } catch {
    // 失败不重查：本地数据没变，界面和服务端仍然一致
  }
}

/**
 * 金额显示：两位小数。
 *
 * <p>★ 这不是"好看一点"，是必需。后端 {@code BigDecimal} 的 {@code 228.70}
 * 序列化成 JSON 数字之后变成 {@code 228.7} —— <b>尾随的 0 在 JSON 里不存在</b>。
 * 不格式化的话同一笔钱在不同地方长得不一样，用户会以为算错了。
 *
 * <p>⚠️ 这是本工程里的【第一份】—— {@code mall-web} 没有
 * {@code mall-shop} 那个 {@code utils/format.js}，也没有 {@code theme.css}，
 * 两个工程之间没有代码共享机制（见 {@code utils/orderStatus.js} 开头
 * 关于「刻意重复」的说明）。
 * 商品列表页（{@code product/List.vue}）里那一份是<b>内联在模板里</b>的
 * {@code Number(row.price).toFixed(2)}，属于同类东西但形态不同 ——
 * 等这里也出现第四处时再考虑提取，现在不动它。
 * <b>顺手改掉别人依赖的东西，是协作里最招人烦的一类改动。</b>
 *
 * <p>★ 顺带说清边界：<b>前端算的钱永远是显示，不是真相。</b>
 * 真正的金额是后端用 BigDecimal 算好、存在 {@code DECIMAL(10,2)} 里的，
 * 这里只是把那个数原样印出来。所以哪怕格式化错了，
 * 也不会让任何一笔钱算错。
 */
function formatAmount(v) {
  return Number(v || 0).toFixed(2)
}

// 页面第一次打开时加载数据
onMounted(loadData)
</script>

<template>
  <div class="page">
    <!-- ============ 1. 搜索条件区 ============ -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="订单号">
          <!--
            ★ 精确匹配。提示语必须说清「要完整单号」——
              如果用户以为这里是模糊搜索，输一半搜不到，
              他会觉得是系统坏了
          -->
          <el-input
            v-model="query.orderNo"
            placeholder="完整订单号"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="会员">
          <!--
            ★ 模糊匹配。这一栏解决的是「张三那单」这种查询 ——
              管理员记不住订单号，但记得住人
          -->
          <el-input
            v-model="query.memberKeyword"
            placeholder="用户名或昵称"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="状态">
          <!--
            ⚠️ 「全部状态」的 value 是 null（见 ORDER_STATUS_OPTIONS）——
              不要用 -1 之类的哨兵值表示「全部」，哨兵值迟早和真实取值撞车
          -->
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option
              v-for="opt in ORDER_STATUS_OPTIONS"
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
          ★ 这里【没有】「新增订单」按钮 —— 订单是用户在用户端下的，
            管理端不能凭空造一单。少了它是正确的，不是漏了。
        -->
        <span class="total-tip">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe style="width: 100%">
        <!--
          ★ 展开行（项目里第一次用）。
            type="expand" 会在每行最左边加一个箭头，展开后渲染 #default 插槽。
            这里用来放「一笔订单的全部细节」：商品明细、收货信息、备注。

          ★ 为什么用展开行而不是做一个「订单详情」页？
            因为管理端看订单的典型动作是【扫一屏，找哪单该处理】——
            跳详情页会打断这个节奏（点进去、看完、再点回来，
            列表的滚动位置和筛选条件都丢了）。
            展开行把细节放在原地，扫一眼就合上。
            （代价是明细多的时候表格会跳一下，但订单明细一般只有几行。）
        -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-wrap">
              <!-- 商品明细 -->
              <div class="expand-block">
                <div class="block-title">商品明细</div>
                <el-table :data="row.items || []" size="small" border class="items-table">
                  <el-table-column prop="productName" label="商品名称" min-width="200" />
                  <el-table-column label="单价" width="110" align="right">
                    <template #default="{ row: it }">¥{{ formatAmount(it.price) }}</template>
                  </el-table-column>
                  <el-table-column prop="quantity" label="数量" width="80" align="center" />
                  <el-table-column label="小计" width="110" align="right">
                    <template #default="{ row: it }">
                      <span class="price">¥{{ formatAmount(it.subtotal) }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <!--
                  ⚠️ 明细为空是【数据层面可能发生的】（order_item 故意没有外键），
                    拼一个空表格上去用户会以为界面坏了，所以明说一句
                -->
                <div v-if="!row.items || row.items.length === 0" class="block-empty">
                  这笔订单没有商品明细
                </div>
              </div>

              <!-- 收货信息 -->
              <div class="expand-block">
                <div class="block-title">收货信息</div>
                <div class="info-line">
                  {{ row.receiverName }}　{{ row.receiverPhone }}
                </div>
                <div class="info-line muted">{{ row.receiverAddress }}</div>
              </div>

              <!-- 其它信息。★ 备注单独一块，因为它是唯一「用户自己写的」字段，
                   也是最容易被漏看的 -->
              <div class="expand-block">
                <div class="block-title">其它</div>
                <div class="info-line muted">
                  备注：{{ row.remark || '（无）' }}
                </div>
                <div class="info-line muted">
                  支付方式：{{ payMethodLabel(row.payMethod) }}
                </div>
                <!--
                  ★ 里程碑 18：单号放在展开行里，【不】在列表上加一列。
                    判据（16 轮立的第 ③ 条：有读者才加）问的是
                    「谁在什么时候读它」：运营读单号是在【处理某一单】
                    的时候（复制给用户、去快递官网查），不是在扫列表的时候。
                    加成一列会让本来就很宽的表更宽，收益接近零。

                  ⚠️ v-if 只能用假值判断，【不能写 === null】：
                     后端配了 non_null，没发货时这两个 key 会被整个从 JSON 里删掉，
                     对消失的 key 取属性得到的是 undefined，`=== null` 恒为 false。
                     同一个坑本项目已经踩过 5 次。
                -->
                <div v-if="row.trackingNo" class="info-line muted">
                  物流：{{ row.logisticsCompany }}　{{ row.trackingNo }}
                </div>
                <div class="info-line muted">
                  取消时间：{{ row.cancelTime || '—' }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="orderNo" label="订单号" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.orderNo }}</span>
          </template>
        </el-table-column>

        <!--
          ★ 会员列。这一列是管理端【独有】的 ——
            用户端那边看到的所有订单都是自己的，
            显示「这是谁的」没有意义（是废话）。
        -->
        <el-table-column label="会员" width="130">
          <template #default="{ row }">
            <div class="member">
              <span>{{ row.memberNickname || row.memberUsername || '—' }}</span>
              <span class="muted small">{{ row.memberUsername }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">
            <span class="price">¥{{ formatAmount(row.totalAmount) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="商品" width="80" align="center">
          <template #default="{ row }">
            <!-- ★ 「共 N 件」是件数之和，不是明细行数（一单 3 种商品各 2 件 = 6 件） -->
            {{ (row.items || []).reduce((s, it) => s + (it.quantity || 0), 0) }} 件
          </template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="orderStatusTagType(row.status)" size="small">
              {{ orderStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="下单时间" width="170" align="center" />

        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <!--
              ★★ 只有「已付款」显示发货按钮 —— 【不显示禁用按钮，而是不显示】。

                这条规矩是 mall-shop 的 Pay.vue 定下的
                （那边有两类相反的处理：已付款不显示取消按钮，
                 已超时显示但禁用），判断标准是
                「这件事还存在于用户的意图里吗」：
                  能发货吗？不能 —— 而且这个状态不会再变回来了。所以没有意义。
                  能付款吗？能，只是错过了时间。所以按钮该在，只是不能点。

                ★ 那这里和前端的展示判断会不会和规则冲突？
                  不会。这只是「显示什么」，不是「允许什么」。
                  真正的闸门是后端 markShipped 的 WHERE status = 1 ——
                  这里就算写错，管理员最多看到一个不该出现的按钮，
                  点了也会拿到 1002。
            -->
            <el-button
              v-if="row.status === ORDER_STATUS.PAID"
              type="primary"
              link
              size="small"
              @click="openShip(row)"
            >
              发货
            </el-button>

            <!--
              ★★ 里程碑 18：新增「物流」按钮，和「发货」互斥显示
                 （所以操作列宽度 100 不用改 —— 两个按钮永远只有
                 一个可能出现）。

              ★ 这个 v-if / v-else-if 的结构恰好说明了一件事：
                订单状态机是【单向】的，所以「能发货」和「能看物流」
                 永远不可能同时为真。用 v-else-if 而不是两个独立的 v-if，
                 就是为了让这一点在模板上一眼可见。

              ★ 为什么「已完成」也要显示（不只是「已发货」）：
                货收到之后恰恰是最需要看物流的时候（「到底哪天送到的」
                决定了售后 7 天窗口还剩几天）。
            -->
            <el-button
              v-else-if="row.status === ORDER_STATUS.SHIPPED
                || row.status === ORDER_STATUS.COMPLETED"
              type="primary"
              link
              size="small"
              @click="openLogistics(row)"
            >
              物流
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无订单数据" :image-size="80" />
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

    <!--
      ============ 5. 发货对话框 ============

      ★★ 里程碑 18 新增。原来是 ElMessageBox.confirm（纯确认，无表单），
        现在变成一张表单 —— 因为「货是怎么发出去的」本来就该记下来。

      ⚠️ 对话框里的「确定发货」按钮【本身就是那次确认】，
         外面【没有】再叠一层 ElMessageBox.confirm。
         叠两层会让「发货后不可撤销」这句话失去分量
         （连点两次「确定」之后，人就不再读那两段文字了）。

      ⚠️ destroy-on-close 是刻意的：对话框一关就把里面的 DOM 丢掉。
         留着的话，表单校验的红字会跟着实例一起活到下一次打开，
         而 openShip 只清了字段值、清不掉那个「校验失败」的标记。
         （onShipOpen 里的 clearValidate 是第二道保险 ——
          两个都留着，因为它们的失效方式不一样。）
    -->
    <el-dialog v-model="shipVisible" title="发货" width="480" destroy-on-close @open="onShipOpen">
      <el-form ref="shipFormRef" :model="shipForm" :rules="shipRules" label-width="88px">
        <el-form-item label="订单号">
          <span class="mono">{{ shipForm.orderNo }}</span>
        </el-form-item>

        <el-form-item label="快递公司" prop="logisticsCompany">
          <!--
            ★ 自由文本，不是下拉 —— 理由见 openShip 上面那段注释。
              placeholder 用「如：顺丰」给一个例子，但不暗示「只能填这些」。
          -->
          <el-input
            v-model="shipForm.logisticsCompany"
            maxlength="50"
            show-word-limit
            placeholder="如：顺丰"
          />
        </el-form-item>

        <el-form-item label="快递单号" prop="trackingNo">
          <el-input
            v-model="shipForm.trackingNo"
            maxlength="64"
            show-word-limit
            placeholder="如：SF1234567890"
          />
        </el-form-item>

        <!-- ★ 红色警示。它是这个操作唯一的后悔机会，所以不折叠、不藏进 tooltip -->
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>发货后不可撤销</template>
          用户端会看到「已发货」并可以确认收货。本项目没有「取消发货」。
        </el-alert>
      </el-form>

      <template #footer>
        <el-button @click="shipVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipSubmitting" @click="submitShip">
          确定发货
        </el-button>
      </template>
    </el-dialog>

    <!--
      ============ 6. 物流对话框 ============

      ★★ 里程碑 18 新增。三段结构，每一段的信息来源不同，不要混：
        上段【只读快照】—— 承运商 / 单号 / 发货时间，它们是 orders 上的列；
        中段【录入表单】—— 往 order_logistics 里加一条；
        下段【时间线】  —— 已录入的节点，最新在上。

      ⚠️ 上段那三个字段是【订单级】的事实，不是节点级的 ——
         它们不会因为录了新节点而变化。单包裹场景下这是对的
         （多包裹是另一个业务，README 已声明不做）。
    -->
    <el-dialog
      v-model="logisticsVisible"
      :title="`物流 · ${logisticsOrderNo}`"
      width="600"
      destroy-on-close
      @open="onLogisticsOpen"
    >
      <div v-loading="logisticsLoading">
        <!-- ---- 上段：只读快照 ---- -->
        <!--
          ⚠️ 三个 v-if 都用假值判断，【不能写 === null】（non_null 会删掉 key）。
             未发货的订单能打开这个对话框吗？界面上不能（按钮是 v-else-if 的），
             但接口是允许的 —— 所以这里必须能承受「三个字段都没有」，
             而不是假定它们一定在。
        -->
        <div class="logi-head">
          <div class="info-line">
            <span class="muted">承运商：</span>{{ logisticsData.logisticsCompany || '—' }}
          </div>
          <div class="info-line">
            <span class="muted">单号：</span>
            <span class="mono">{{ logisticsData.trackingNo || '—' }}</span>
          </div>
          <div class="info-line">
            <span class="muted">发货时间：</span>{{ logisticsData.shipTime || '—' }}
          </div>
        </div>

        <el-divider />

        <!-- ---- 中段：新增节点 ---- -->
        <el-form
          ref="traceFormRef"
          :model="traceForm"
          :rules="traceRules"
          label-width="72px"
          class="trace-form"
        >
          <el-form-item label="节点" prop="status">
            <!--
              ★ 选项来自 LOGISTICS_STATUS_OPTIONS（没有「全部 / 不限」那一项 ——
                这个下拉是「必须选一个」）。
              ⚠️ 少一项的后果特别安静：那个状态【永远录不进去】，
                管理员在下拉里找不到它，而页面、接口、控制台都不报错。
                sql/test-frontend-format.py 的规则 6 用 must_equal=True
                把它和后端码表逐项钉死。
            -->
            <el-select v-model="traceForm.status" placeholder="请选择" style="width: 160px">
              <el-option
                v-for="opt in LOGISTICS_STATUS_OPTIONS"
                :key="opt.value"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="时间" prop="traceTime">
            <!--
              ★ value-format 让它绑一个字符串（'YYYY-MM-DD HH:mm:ss'），
                而不是 Date 对象 —— 后者要自己格式化，而格式化就是第二个定义者。
              ★ type="datetime" 允许选【过去】的时刻：补录是这个功能的默认用法。
            -->
            <el-date-picker
              v-model="traceForm.traceTime"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="这一节点发生的时刻"
              style="width: 220px"
            />
          </el-form-item>

          <el-form-item label="说明" prop="description">
            <el-input
              v-model="traceForm.description"
              maxlength="255"
              show-word-limit
              placeholder="如：快件已到达【杭州转运中心】"
            />
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="traceSubmitting" @click="submitTrace">
              添加节点
            </el-button>
          </el-form-item>
        </el-form>

        <!--
          ★★ 录「已签收」时紧跟着显示这句 —— 把副作用摆在操作者眼前，
             【在提交之前】。这是本轮唯一一个「录一条数据会改另一张表」
             的地方，而它是不可撤销的（删掉节点不会退回订单状态）。
          ★ 判断依据是码（willComplete），不是说明里的中文。
        -->
        <el-alert v-if="willComplete" type="error" :closable="false" show-icon class="logi-warn">
          <template #title>这条会把订单标记为「已完成」，且不可撤销</template>
          删除这条记录<b>不会</b>把订单退回「已发货」。
        </el-alert>

        <el-divider />

        <!-- ---- 下段：时间线（最新在上） ---- -->
        <div class="block-title">物流轨迹</div>

        <!--
          ⚠️ 空轨迹是这个功能的【正常初始状态】（刚发的货，快递还没揽收），
             所以这里必须有一句明确的话，而不是一片空白。
        -->
        <div v-if="!logisticsData.traces || logisticsData.traces.length === 0" class="block-empty">
          还没有物流轨迹。快递有进展时，在这里手工补录。
        </div>

        <!--
          ★ 顺序完全按服务端给的（trace_time DESC, id DESC），前端【不排】——
            重排就是第二个定义者，而且很容易写成「按 id 排」，
            那样补录一条昨天的节点会让时间线倒过来。
        -->
        <div v-else class="timeline">
          <div v-for="t in logisticsData.traces" :key="t.id" class="trace-item">
            <div class="trace-main">
              <el-tag :type="logisticsStatusTagType(t.status)" size="small">
                {{ logisticsStatusLabel(t.status) }}
              </el-tag>
              <span class="trace-time mono">{{ t.traceTime }}</span>
              <el-button type="danger" link size="small" @click="removeTrace(t)">删除</el-button>
            </div>
            <div class="trace-desc">{{ t.description }}</div>
            <!--
              ★ 录入时间只在「和发生时间不是同一天」时才显示 ——
                补录的场景下这两个时间差得远，值得看；
                当天录的情况下它们是同一件事，显示两遍只是噪声。
              ⚠️ 比较的是日期前 10 位，不是整串（秒级的差异没有意义）。
            -->
            <div
              v-if="t.createTime && t.createTime.slice(0, 10) !== t.traceTime.slice(0, 10)"
              class="trace-desc muted small"
            >
              录入于 {{ t.createTime }}
            </div>
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

/* 展开行。★ 缩进要留够，否则展开的内容看起来和表格行是同一层 */
.expand-wrap {
  display: flex;
  gap: 32px;
  flex-wrap: wrap;
  padding: 8px 16px 12px 48px;
}

.expand-block {
  min-width: 220px;
}

.block-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}

.items-table {
  max-width: 620px;
}

.block-empty {
  margin-top: 8px;
  color: #909399;
  font-size: 13px;
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

/* ---- 物流对话框（里程碑 18） ---- */

/* 只读快照区。★ 加一点底色，让「这一段不能改」在视觉上就成立 */
.logi-head {
  padding: 8px 12px;
  border-radius: 4px;
  background: #f5f7fa;
}

.logi-warn {
  margin-bottom: 12px;
}

/* ★ 表单压在分割线和时间线之间，压一点下边距，
   否则它的最后一行和分割线贴在一起 */
.trace-form {
  margin-bottom: 4px;
}

.trace-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.timeline {
  max-height: 260px;
  overflow-y: auto;
}

/* ★ 时间线每一项左边那条竖线：它是「这是一串按时间排的事件」的唯一视觉线索 */
.trace-item {
  padding: 8px 0 8px 12px;
  border-left: 2px solid #ebeef5;
}

.trace-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ★ margin-left: auto 把「删除」推到最右 —— 它是个破坏性操作，不该挨着标签 */
.trace-main .el-button {
  margin-left: auto;
}

.trace-time {
  color: #909399;
  font-size: 12px;
}

.trace-desc {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}
</style>
