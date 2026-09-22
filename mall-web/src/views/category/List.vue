<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCategoryPage, deleteCategory } from '@/api/category'
import CategoryForm from './CategoryForm.vue'

/**
 * 分类列表页。
 *
 * <p>结构和商品列表页完全一样，从上到下四部分：
 *   1. 搜索条件区
 *   2. 操作按钮区
 *   3. 数据表格区
 *   4. 分页器
 *
 * <p>建议你对照着商品列表页看一遍 —— 会发现除了「字段少几个」之外，
 * 骨架是一模一样的。这就是分层的价值：
 * <b>学会一个列表页，后面的订单列表、会员列表都是照着套</b>。
 * 真正需要动脑的只有「这个模块比别人多了什么特殊规则」。
 *
 * <p>分类比商品特殊的地方只有一处：<b>删除可能被拒绝</b>
 * （分类下还有商品时不让删），见 handleDelete 的注释。
 */

const loading = ref(false)
const tableData = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  name: '',
  status: null,
})

const formVisible = ref(false)
const editingId = ref(null)

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadData() {
  loading.value = true
  try {
    const res = await getCategoryPage(query)
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

// ---------------------------------------------------------------------------
// 搜索 / 分页
// ---------------------------------------------------------------------------

function handleSearch() {
  // ★ 搜索时必须把页码重置回第 1 页，否则会「明明有数据却显示暂无数据」
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.name = ''
  query.status = null
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
 * 删除分类。
 *
 * <p><b>这个删除比商品删除多一种失败情况</b>：分类下还挂着商品时，
 * 后端会拒绝删除并返回「该分类下还有 N 个商品，请先移走或删除这些商品」。
 *
 * <p>前端这里怎么处理？答案是<b>什么都不用特殊处理</b>。
 * 响应拦截器已经把后端的 message 弹出来了，用户看到了明确的原因，
 * 这里的 catch 只要保证「不刷新列表、不弹成功提示」就够了。
 *
 * <p>这正是统一异常处理的价值：后端抛一个 BusinessException，
 * 前端不用写任何 if-else 判断错误类型，提示就自动到位了。
 * 想象一下如果后端返回的是「500 服务器错误」，
 * 用户完全不知道自己做错了什么 —— 那才需要前端做各种特殊处理来补救。
 */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除分类「${row.name}」吗？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户点了取消
  }

  try {
    await deleteCategory(row.id)
    ElMessage.success('删除成功')

    // 边界情况：当前页只剩这一条时，删完这页就空了，应该自动往前翻一页
    if (tableData.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    loadData()
  } catch {
    // 已统一提示。可能是「分类下有商品」，也可能是网络问题，
    // 两种情况都不该刷新列表（删失败了，列表没变）
  }
}

function handleFormSuccess() {
  loadData()
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <div class="page">
    <!-- ============ 1. 搜索条件区 ============ -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="分类名称">
          <el-input
            v-model="query.name"
            placeholder="输入名称模糊搜索"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
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
          <span>新增分类</span>
        </el-button>
        <span class="total-tip">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="tableData" border stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />

        <el-table-column prop="name" label="分类名称" min-width="180" show-overflow-tooltip />

        <el-table-column prop="sort" label="排序" width="90" align="center">
          <!--
            排序值越小越靠前。这里直接显示数字不加修饰，
            因为「小的是前面」这个规则本身不直观，
            加了颜色或图标反而容易让人以为是别的东西。
            真要做得更友好，可以在别的分类排序旁边加个「排序第 N 位」的文案
          -->
          <template #default="{ row }">{{ row.sort }}</template>
        </el-table-column>

        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
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

        <template #empty>
          <el-empty description="暂无分类数据" :image-size="80" />
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

    <CategoryForm v-model="formVisible" :category-id="editingId" @success="handleFormSuccess" />
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

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
