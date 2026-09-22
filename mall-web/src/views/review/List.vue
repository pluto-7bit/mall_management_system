<script setup>
/**
 * 商品评价管理（管理端）。
 *
 * <p>路由 {@code /review}。
 *
 * <h3>它是后台标准列表模板的第 4 个使用者</h3>
 *
 * <p>结构照 {@code views/product/List.vue} 那套四段式
 * （搜索条件区 → 操作区 → 表格 → 分页器），最近一次的抄写对象是
 * {@code views/order/List.vue}。两条已经立过的纪律照抄，不另起一套：
 * <ul>
 *   <li><b>查询条件用 {@code reactive} 本地状态，不进 URL</b>
 *       （管理端没有「把地址发给别人」这个需求，
 *        和 {@code mall-shop} 刻意相反，理由见 {@code order/List.vue} 类注释）</li>
 *   <li><b>搜索时必须把页码重置回第 1 页</b></li>
 * </ul>
 *
 * <h3>★ 但这一页有一个别的列表页都没有的处境：它能删，却【不能改】</h3>
 *
 * <p>评价是「一次定终身」的 —— 这是用户在里程碑 12 确认过的设计。
 * 管理端也只能删，不能改。
 *
 * <p>这不是「还没做」，是<b>正确的边界</b>。评价的价值全在于
 * 「它是买家自己写的」；管理员能编辑内容的话，它就变成了
 * 一份署名是买家、实际由卖家写的文本。
 * <b>要推翻一条评价，正确的动作是删掉它（让它消失），
 * 而不是改掉它（让它说谎）。</b>
 *
 * <h3>★★ 删除的两个后果，必须让点按钮的人知道</h3>
 *
 * <ol>
 *   <li><b>那个会员可以重新评价这条明细了。</b>
 *       后端 {@code product_review.order_item_id} 上有唯一索引，
 *       删掉这一行就释放了槽位 —— 会员回到「我的订单」，
 *       会看到按钮从「已评价」变回「评价」。</li>
 *   <li><b>磁盘上的晒图文件不会被删</b>，会成为 {@code uploads/} 里的
 *       孤儿文件（本项目已知的、写出来的取舍，删商品也一样）。</li>
 * </ol>
 *
 * <p>所以删除的二次确认文案里要带上第一条 —— 它是管理员
 * 唯一一个会看到这个后果的地方（{@code api/review.js} 里也写了一遍，
 * 那是给读代码的人看的，这里这句是给点按钮的人看的）。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteReview, getReviewList } from '@/api/review'

// ---------------------------------------------------------------------------
// 列表数据
// ---------------------------------------------------------------------------

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

/**
 * 查询条件。
 *
 * <p>★ 两个都是<b>模糊匹配</b>，和订单列表那边刻意不同
 * （那边的 {@code orderNo} 是精确匹配）—— 判据是
 * 「管理员手里有什么」：订单号是唯一标识，而商品名和会员名
 * 他手里只有一个记不全的印象。
 *
 * <p>★ 两个条件可以同时用，是 AND 关系。
 *
 * <p>⚠️ 这里<b>没有</b>「星级」和「时间范围」筛选 ——
 * 用户确认的设计里管理端只有「按商品/会员筛选 + 删除」。
 * 加筛选条件是很容易的，但每一个都要在服务端
 * {@code adminCondition} 里多一个 if，而<b>没有人来问的筛选条件
 * 只会让那个 where 越来越长、越来越难读</b>。
 * （真需要的时候再加，那时候加也只需要改两个地方。）
 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  productKeyword: '',
  memberKeyword: '',
})

/**
 * ★ 每行一个 loading 标记，用【对象】按评价 id 索引。
 *
 * <p>不能用一个布尔 ref：管理员完全可能连着删好几条。
 * 用单个 ref 的话，点第二行会把第一行的 loading 抢走，
 * 第一行的按钮提前恢复可点（他以为没点上，再点一次，
 * 于是拿到一句「评价不存在」），而第二行看起来卡住了。
 *
 * <p><b>「一行一个」的状态就该按行存。</b>
 * （同一句话在 {@code order/List.vue} 的 {@code shipping} 上写过一遍，
 * 这里换了索引的键而已。）
 */
const deleting = ref({})

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadData() {
  loading.value = true
  try {
    const res = await getReviewList(query)
    tableData.value = res.list || []
    total.value = res.total || 0
  } catch {
    // 错误提示已在响应拦截器里统一处理。
    // ★ 失败要把列表清空：留着上一次的数据会让管理员以为
    //   看到的表格是当前筛选条件的结果 —— 那比空白更危险。
    tableData.value = []
    total.value = 0
  } finally {
    // 用 finally 保证无论成功失败都关掉转圈
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 搜索
// ---------------------------------------------------------------------------

function handleSearch() {
  // ★ 关键：搜索时必须把页码重置回第 1 页。
  //   否则：你在第 3 页，然后搜一个只有 2 条结果的关键词，
  //   后端按 pageNum=3 去查，一条都查不到，页面显示「暂无数据」，
  //   管理员会以为没搜到，其实数据在第 1 页。
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.productKeyword = ''
  query.memberKeyword = ''
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
// 删除
// ---------------------------------------------------------------------------

/**
 * 删除一条评价。
 *
 * <p>⚠️ <b>不可逆</b>，所以二次确认不是走过场。
 *
 * <p>★ 二次确认的 cancel reject 必须 catch 掉（照
 * {@code product/List.vue} 的写法）—— 不 catch 的话，
 * 用户每点一次「取消」控制台就报一个未捕获的 Promise 异常。
 *
 * <p>★ 确认文案里<b>必须带上那句「他还能重新评价」</b>：
 * 管理员点删除时心里的预期通常是「这条评价没了」，
 * 而实际上它还多了一个副作用。不告诉他，
 * 他会在几天后看到同一个人又发了一条一模一样的评价，
 * 然后来报一个「删除没生效」的假 bug。
 * <b>文案不是客气话，是把真实后果说全。</b>
 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${row.productName}」下这条评价吗？删除后不可恢复。`
        + '注意：删掉之后，这条订单明细的买家可以重新评价一次。',
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户点了取消
  }

  deleting.value[row.id] = true
  try {
    await deleteReview(row.id)
    ElMessage.success('删除成功')

    // 边界情况：如果当前页只有这一条数据，删完后这一页就空了，
    // 应该自动往前翻一页，否则用户会看到一片空白还以为出错了
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    loadData()
  } catch {
    /* 已统一提示 */
  } finally {
    // ★ 用 delete 而不是赋 false：让这个 key 消失，对象里不留一堆 false
    delete deleting.value[row.id]
  }
}

/** 星级那一列旁边要显示的数字。后端存的就是 1~5 的整数，直接印 */
function ratingOf(row) {
  return row.rating ?? 0
}

// 页面第一次打开时加载数据
onMounted(loadData)
</script>

<template>
  <div class="page">
    <!-- ============ 1. 搜索条件区 ============ -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="商品名称">
          <!--
            ★ 模糊匹配。管理员记得的是「那个 iPhone」，
              手里没有商品 id、也记不住完整名称
          -->
          <el-input
            v-model="query.productKeyword"
            placeholder="商品名称关键词"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="会员">
          <!--
            ★ 同样模糊匹配，搜的是 username 或 nickname ——
              「张三那几条」
          -->
          <el-input
            v-model="query.memberKeyword"
            placeholder="用户名或昵称"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
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
          ★ 这里【没有】「新增评价」按钮，也【没有】编辑按钮。

            评价是会员在用户端产生的（他得先下单、付款、收货），
            管理端不能替谁写一条；而「不能改」是用户确认的设计
            （见本文件类注释里那段）。少了这两个按钮是【正确的】，
            —— 一个后台列表页长得像标准的 CRUD，
            是因为 CRUD 是默认形状，不是因为这个资源真的需要增改。
        -->
        <span class="total-tip">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />

        <el-table-column
          prop="productName"
          label="商品"
          min-width="160"
          show-overflow-tooltip
        />

        <!--
          ★ 会员列。这一列是管理端【独有】的 ——
            用户端的评价列表只给昵称（连 username 都没有），
            因为商品详情页任何人都能打开。
            判据不是「这个字段敏感吗」，而是「看这个接口的人是谁」。
        -->
        <el-table-column label="会员" width="150">
          <template #default="{ row }">
            <div class="member">
              <span>{{ row.memberNickname || '—' }}</span>
              <span class="muted small">{{ row.memberUsername }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="星级" width="150" align="center">
          <template #default="{ row }">
            <div class="rating">
              <!--
                ★ disabled 是必须的：el-rate 默认是可点的。
                  不加的话，管理员在表格里随手一点就"改了星级"——
                  实际上什么都没改（本地 v-model 都没有），
                  但界面上星星真的变了，他会以为改成功了。
                  ★ 而且这里【不绑 v-model】，只传 :model-value ——
                  单向绑定让「改不了」这件事在代码上就是成立的，
                  不依赖 disabled 这个属性。
              -->
              <el-rate :model-value="ratingOf(row)" disabled />
              <span class="muted small">{{ ratingOf(row) }} 星</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          prop="content"
          label="内容"
          min-width="220"
          show-overflow-tooltip
        />

        <!--
          晒图列。

          ★ 用 el-image 而不是 <img>，理由同 product/List.vue 的封面列：
            <img> 遇到打不开的地址会露出一排碎图图标，
            el-image 的 #error 插槽能让它安静地退回一个灰块。
            （用户端有 ProductImage.vue 做同样的事，
             但两个工程独立构建、不共享代码。）

          ★ 最多 3 张，所以一行放得下 —— 不需要「+N」折叠。
            这个数字和后端 ReviewSaveDTO 的 @Size(max = 3)、
            以及用户端 ReviewFormDialog 的 MAX_IMAGES 是同一个 3。

          ⚠️ 每一张的预览列表给的是【整组】而不是自己那一张：
            管理员点开第一张之后，应该能左右翻看这件商品的全部晒图。
            只给一张的话，他得关掉、再点第二张，看三张图要点三次。
        -->
        <el-table-column label="晒图" width="180">
          <template #default="{ row }">
            <div v-if="row.images && row.images.length" class="shots">
              <el-image
                v-for="url in row.images"
                :key="url"
                :src="url"
                fit="cover"
                class="row-shot"
                :preview-src-list="row.images"
                preview-teleported
              >
                <template #error>
                  <div class="row-shot-placeholder">无图</div>
                </template>
              </el-image>
            </div>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="评价时间" width="170" align="center" />

        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <!--
              ★ 删除是这一页【唯一】的操作，而且它对每一行都成立 ——
                所以这里没有 v-if 条件。

                ⚠️ 对比订单列表：那边「发货」只在 status = 1 时出现，
                  因为发货这件事只对已付款的单有意义。
                  而「删掉一条评价」对任何一条评价都说得通
                  （违规、广告、恶意差评），所以没有条件可判。
                  **有没有 v-if，取决于这件事是不是对所有行都成立**，
                  不是取决于「别的页面写了 v-if」。
            -->
            <el-button
              type="danger"
              link
              size="small"
              :loading="deleting[row.id]"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="暂无评价数据" :image-size="80" />
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

.member {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

/* 星级 + 数字竖排 —— 表格里横向排会因为列宽不够把星星挤窄。
   el-rate 的宽度是跟着星星数量走的（5 颗 × 字号），给不够就会换行 */
.rating {
  display: flex;
  flex-direction: column;
  align-items: center;
  line-height: 1.3;
}

.muted {
  color: #909399;
}

.small {
  font-size: 12px;
}

/* ---------------- 晒图缩略图 ---------------- */

.shots {
  display: flex;
  gap: 4px;
}

/* ★ 显式给死宽高。
   ⚠️ 不给的话 el-image 会按图片原始尺寸撑开，一行评价的高度
   会随着晒图尺寸跳来跳去 —— 表格看起来像坏了。
   （和 product/List.vue 的 .row-cover 同一条理由。）
   ★ 56px 而不是更大：一列最多 3 张，3 × 56 + 2 × 4 = 176，
   正好落在这一列的 180px 里，不会换行。 */
.row-shot {
  width: 56px;
  height: 56px;
  display: block;
  border-radius: 3px;
}

.row-shot-placeholder {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  color: #c0c4cc;
  font-size: 12px;
  border-radius: 3px;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
