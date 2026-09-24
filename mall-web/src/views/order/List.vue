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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getOrderList, shipOrder } from '@/api/order'
import {
  ORDER_STATUS,
  ORDER_STATUS_OPTIONS,
  orderStatusLabel,
  orderStatusTagType,
  payMethodLabel,
} from '@/utils/orderStatus'

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
 * ★ 每行一个 loading 标记，用【对象】按订单号索引。
 *
 * <p>不能用一个 {@code const shipping = ref(false)}：那只允许
 * 「同时只有一个发货在进行」。管理员完全可能连着发好几个单 ——
 * 用单个 ref 的话，点第二行会把第一行的 loading 抢走，
 * 第一行的按钮提前恢复可点（他以为没点上，再点一次 → 1002），
 * 而第二行看起来卡住了。
 *
 * <p><b>「一行一个」的状态就该按行存。</b>
 */
const shipping = ref({})

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
 * 发货。
 *
 * <p>⚠️ <b>不可逆</b>：本项目没有「取消发货」接口。
 * 所以二次确认不是走过场，它是这个操作唯一的后悔机会。
 *
 * <p>★ 二次确认的 cancel reject 必须 catch 掉（照
 * {@code product/List.vue:139} 的写法）—— 不 catch 的话，
 * 用户每点一次「取消」控制台就报一个未捕获的 Promise 异常。
 * 这种噪声会淹没真正的错误。
 *
 * <p>★★ {@code catch} 里<b>无条件重新拉一次数据</b> ——
 * 这一点和 mall-web 其它页面不同，理由值得写清楚：
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
async function handleShip(row) {
  try {
    await ElMessageBox.confirm(
      `确定要为订单 ${row.orderNo} 发货吗？发货后不可撤销，`
        + '用户端会看到「已发货」并可以确认收货。',
      '发货确认',
      { type: 'warning', confirmButtonText: '确定发货', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户点了取消
  }

  shipping.value[row.orderNo] = true
  try {
    // ★ 接口返回的是发货【之后】的订单 —— 直接拿来替换列表里那一行，
    //   不用重查整页。这是后端刻意的设计（所有状态迁移接口都返回完整订单）。
    const updated = await shipOrder(row.orderNo)
    const idx = tableData.value.findIndex((o) => o.orderNo === row.orderNo)
    if (idx >= 0) {
      tableData.value[idx] = updated
    }
    ElMessage.success('已发货')

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
    // ★ 无条件重查，理由见方法头注释
    loadData()
  } finally {
    // ★ 用 delete 而不是赋 false：让这个 key 消失，
    //   对象里不留一堆 false
    delete shipping.value[row.orderNo]
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
              :loading="shipping[row.orderNo]"
              @click="handleShip(row)"
            >
              发货
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
</style>
