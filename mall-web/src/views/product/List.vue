<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProductList, deleteProduct } from '@/api/product'
import { getCategoryOptions } from '@/api/category'
import ProductForm from './ProductForm.vue'

/**
 * 商品列表页。
 *
 * 一个标准的后台列表页由四部分组成，从上到下：
 *   1. 搜索条件区（筛选数据）
 *   2. 操作按钮区（新增等）
 *   3. 数据表格区
 *   4. 分页器
 * 后面分类、订单、会员的列表页都是这个结构，学会一个就都会了。
 */

// ---------------------------------------------------------------------------
// 列表数据
// ---------------------------------------------------------------------------
const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const categories = ref([])

/**
 * 查询条件。
 *
 * 这里用 reactive 而不是 ref，因为它是多个字段组成的对象。
 * 如果用 ref 就得写成 query.value.name，多一层 value 很啰嗦。
 *
 * 经验法则：基本类型用 ref，对象/数组用 reactive。
 */
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  name: '',
  categoryId: null,
  status: null,
})

// ---------------------------------------------------------------------------
// 弹窗控制
// ---------------------------------------------------------------------------
const formVisible = ref(false)
const editingId = ref(null)

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadData() {
  loading.value = true
  try {
    const res = await getProductList(query)
    tableData.value = res.list
    total.value = res.total
  } catch {
    // 错误提示已在响应拦截器里统一处理
    tableData.value = []
    total.value = 0
  } finally {
    // 用 finally 保证无论成功失败都要关掉 loading 转圈，
    // 否则接口一报错页面就一直转，用户以为卡死了
    loading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await getCategoryOptions()
  } catch {
    /* 已统一提示 */
  }
}

// ---------------------------------------------------------------------------
// 搜索
// ---------------------------------------------------------------------------

function handleSearch() {
  // ★ 关键：搜索时必须把页码重置回第 1 页
  //
  // 否则会有这个 bug：你在第 5 页，然后搜索一个只有 2 条结果的关键词，
  // 后端按 pageNum=5 去查，一条都查不到，页面显示「暂无数据」，
  // 用户会以为没搜到，其实数据是有的，只是在第 1 页。
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.name = ''
  query.categoryId = null
  query.status = null
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
  // 改每页条数后，当前页码可能已经超出总页数了，同样要回到第 1 页
  query.pageNum = 1
  loadData()
}

// ---------------------------------------------------------------------------
// 新增 / 编辑 / 删除
// ---------------------------------------------------------------------------

function handleCreate() {
  editingId.value = null
  formVisible.value = true
}

function handleEdit(row) {
  editingId.value = row.id
  formVisible.value = true
}

/**
 * 删除商品。
 *
 * 删除是不可逆操作，必须先让用户确认。
 * ElMessageBox.confirm 返回 Promise：
 *   - 点确定 → resolve
 *   - 点取消 → reject，抛出一个 'cancel' 字符串
 * 所以取消的 reject 必须 catch 掉，否则控制台会报未捕获的 Promise 异常。
 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除商品「${row.name}」吗？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户点了取消
  }

  try {
    await deleteProduct(row.id)
    ElMessage.success('删除成功')

    // 边界情况：如果当前页只有这一条数据，删完后这一页就空了，
    // 应该自动往前翻一页，否则用户会看到一片空白还以为出错了
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    loadData()
  } catch {
    /* 已统一提示 */
  }
}

/** 表单提交成功后的回调 */
function handleFormSuccess() {
  loadData()
}

// onMounted：页面第一次打开时加载数据
onMounted(() => {
  loadCategories()
  loadData()
})
</script>

<template>
  <div class="page">
    <!-- ============ 1. 搜索条件区 ============ -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="商品名称">
          <!--
            @keyup.enter 让用户按回车直接搜索，不用特地去点按钮。
            小细节，但很影响使用手感
          -->
          <el-input
            v-model="query.name"
            placeholder="输入名称模糊搜索"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="分类">
          <el-select
            v-model="query.categoryId"
            placeholder="全部分类"
            clearable
            style="width: 160px"
          >
            <el-option
              v-for="item in categories"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px">
            <el-option label="上架" :value="1" />
            <el-option label="下架" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ============ 2. 操作按钮区 + 3. 表格区 ============ -->
    <el-card shadow="never" class="table-card">
      <div class="toolbar">
        <el-button type="primary" @click="handleCreate">
          <el-icon><Plus /></el-icon>
          <span>新增商品</span>
        </el-button>
        <span class="total-tip">共 {{ total }} 条</span>
      </div>

      <!--
        v-loading 是 Element Plus 的指令，值为 true 时给表格盖一层遮罩 + 转圈。
        它比手写一个 loading 组件省事得多
      -->
      <el-table v-loading="loading" :data="tableData" border stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />

        <!--
          封面缩略图（里程碑 11 新增）。

          ★ 后端零改动：这一列用的是【早就存在的】cover 字段，
            不是新的图集 images。图集只在详情接口里返回
            （见 AdminProductDetailVO 的注释 —— 列表带着它就是白查）。

          ★ 用 el-image 而不是 <img>，理由是管理端比用户端更容易踩到
            加载失败：老的种子图（/images/*.svg）指向 mall-shop/public/，
            在 mall-web 下【必然 404】。<img> 会让整列露出一排碎图图标，
            el-image 的 #error 插槽能让它安静地退回一个灰色块。
            用户端有现成的 ProductImage.vue 做同样的事，但两个工程
            独立构建、不共享代码 —— 这里只需要一个缩略图，
            不为了「对称」把那个组件复制过来。
        -->
        <el-table-column label="封面" width="80" align="center">
          <template #default="{ row }">
            <el-image
              v-if="row.cover"
              :src="row.cover"
              fit="cover"
              class="row-cover"
              :preview-src-list="[row.cover]"
              preview-teleported
            >
              <template #error>
                <div class="row-cover-placeholder">无图</div>
              </template>
            </el-image>
            <div v-else class="row-cover-placeholder">无图</div>
          </template>
        </el-table-column>

        <el-table-column prop="name" label="商品名称" min-width="180" show-overflow-tooltip />

        <el-table-column prop="categoryName" label="分类" width="110" align="center" />

        <!--
          价格（里程碑 15 改造）。

          ★ 数据源从 row.price 换成了 row.minPrice ——
            价格不再挂在这件商品上，而是挂在每一行 SKU 上，
            列表显示的是【起售价】= MIN(sku.price)，由后端 join 出来。

          ★ 多规格时跟一个「起」字。不跟的话会有一个安静的误导：
            运营看到「¥4999」，以为这就是售价，而它其实是 6 个规格里最便宜那个。

          ⚠️ 不能写成 row.price —— 阶段 2~5 期间后端还有一个同名的字段
            （派生汇总，回滚预案），它会取到一个和这里【不同】的数字，
            而且两个都不报错。这是本轮最容易被后人改回去的一处。
          ★ 阶段 6 之后 row.price 已经彻底不存在了（列都删了），
            所以写错会当场渲染成一片空白 —— 从「静默的错」变成了
            「一眼看得见的错」。**这就是删列值不值得做的判据之一。**
        -->
        <el-table-column prop="minPrice" label="价格" width="130" align="right">
          <!--
            用插槽自定义单元格显示。 #default="{ row }" 解构出当前行的数据。
            这种写法可以在单元格里做任意格式化，比 prop 直接显示灵活得多
          -->
          <template #default="{ row }">
            <span class="price">
              ¥{{ Number(row.minPrice).toFixed(2) }}<i v-if="row.skuCount > 1" class="from">起</i>
            </span>
          </template>
        </el-table-column>

        <!--
          库存（里程碑 15 改造）。

          ★ 数据源从 row.stock 换成了 row.totalStock —— SUM(sku.stock)，
            而且后端对它做了 COALESCE(..., 0)（skuCount 是 COUNT(*)，
            所以它天然是 0 而不是 null）。

          ⚠️ 这里显示的是【所有规格加起来】的件数。它作为「大概还有没有货」
            的信号是够用的，但它【不是】任何一个规格的真实库存 ——
            4 个规格各 10 件显示 40，而用户其实一个规格最多只能买 10 件。
            用户端必须按所选规格显示库存，不能拿这个数（见 ProductDetail.vue）。
        -->
        <el-table-column prop="totalStock" label="库存" width="110" align="center">
          <template #default="{ row }">
            <!-- 总库存为 0 时标红，让运营一眼看到需要补货的商品 -->
            <el-tag v-if="row.totalStock === 0" type="danger" size="small">缺货</el-tag>
            <span v-else>
              {{ row.totalStock }}
              <i v-if="row.skuCount > 1" class="from">{{ row.skuCount }} 规格</i>
            </span>
          </template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="创建时间" width="170" align="center" />

        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>

        <!-- 数据为空时显示的内容。没有它，空表格就是一片空白，用户不知道是没数据还是出错了 -->
        <template #empty>
          <el-empty description="暂无商品数据" :image-size="80" />
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

    <!-- 新增/编辑弹窗。v-model 控制显示，@success 时刷新列表 -->
    <ProductForm v-model="formVisible" :product-id="editingId" @success="handleFormSuccess" />
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

.price {
  color: #f56c6c;
  font-weight: 600;
}

/* 「起」和「N 规格」这两个小尾巴（里程碑 15）。
   ★ 用 <i> 而不是 <span> 是为了【不被 price 的 font-weight: 600 继承】——
     i 默认是斜体，这里显式 normal 覆盖掉；同时字号调小、颜色变灰，
     让它是价格旁边的注释而不是价格的一部分。
   ⚠️ 不改成 <b> 之类的：那样看起来像「起」也是金额的一部分。 */
.from {
  font-style: normal;
  font-weight: 400;
  font-size: 11px;
  color: #909399;
  margin-left: 2px;
}

/* ---------------- 里程碑 11：封面缩略图 ---------------- */

/* 缩略图必须显式给死宽高。
   ⚠️ 不给的话 el-image 会按图片原始尺寸撑开，一行商品的高度
   会随着封面图的尺寸跳来跳去 —— 表格看起来像坏了。 */
.row-cover {
  width: 46px;
  height: 46px;
  display: block;
  margin: 0 auto;
  border-radius: 3px;
}

.row-cover-placeholder {
  width: 46px;
  height: 46px;
  margin: 0 auto;
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
