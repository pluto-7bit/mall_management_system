<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCategoryTree, deleteCategory } from '@/api/category'
import CategoryForm from './CategoryForm.vue'

/**
 * 分类列表页。
 *
 * <h3>★★ 里程碑 16：这张表从「分页列表」变成了「树」</h3>
 *
 * <p>原来的骨架和商品列表页一模一样：搜索 / 按钮 / 表格 / 分页器，四段。
 * 现在<b>第四段整个删掉了</b>，第三段换成了树模式。这不是"顺手做了个优化"，
 * 是因为<b>分页和树是互相矛盾的</b>：
 *
 * <pre>
 *   第 1 页里出现了一个子分类，而它的父分类排在第 2 页
 *     → 这一页上它就是一个【孤儿节点】
 *     → 要么前端把它当根渲染（层级错了）
 *     → 要么它从页面上消失（更难查）
 * </pre>
 *
 * <p>「按根分页、每根带全子树」可以做对，但那样 `total` 就变成了
 * <b>根的数量</b>，而「共 N 条」这句话会开始骗人 ——
 * 用户数了数屏幕上有 12 个分类，页面告诉他「共 6 条」。
 * <b>一个回答不了的问题不应该有一个假答案</b>，所以分页整个去掉。
 * 分类是几十条量级的数据，一次全读进来没有代价。
 *
 * <h3>⚠️ 搜索之后树会变得「稀疏」，这是设计</h3>
 *
 * <p>筛「手机」时，命中的子分类会带上它的<b>祖先链</b>一起返回
 * （祖先本身不需要命中），命中的父分类会带上<b>整棵子树</b>。
 * 所以搜一个只命中一个子分类的词，你会看到「父分类 → 那一个子分类」这样
 * 一条细长的路径，而不是孤零零一行。
 * <b>能看出"它挂在哪"比"省掉两行"重要</b>——后者只会让人以为分类建错了。
 *
 * <p>分类比商品特殊的地方还是那一处：<b>删除可能被拒绝</b>，
 * 而且现在有<b>两种</b>拒绝（还有子分类 / 还有商品），见 handleDelete。
 */

const loading = ref(false)
const tableData = ref([])

/** 筛选条件。★ 没有 pageNum / pageSize 了 —— 后端已经不接受分页参数（传了会被静默忽略） */
const query = reactive({
  name: '',
  status: null,
})

const formVisible = ref(false)
const editingId = ref(null)

/**
 * 「当前显示 N 条」。
 *
 * <p>⚠️ 这个数字是<b>在返回的树上递归数出来的</b>，不是后端给的 total ——
 * 接口现在返回的就是整个森林，没有 total 这个字段。
 *
 * <p>★ 注意它的含义和「全库有多少个分类」<b>不一样</b>：
 * 搜索时它是「命中 + 祖先 + 子树」的节点数。
 * 所以文案写的是「当前显示」而不是「共」—— 「共」会让人以为那是总数。
 * 要看总数就把搜索条件清空，那时两者才相等。
 */
const shownCount = computed(() => {
  const walk = (nodes) => (nodes || []).reduce((n, node) => n + 1 + walk(node.children), 0)
  return walk(tableData.value)
})

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadData() {
  loading.value = true
  try {
    const res = await getCategoryTree(query)
    // ★ 拿到的是【数组】（森林），不是 { list, total, ... }。
    //   里程碑 16 之前这里写的是 res.list —— 接口形状一换，
    //   那个表达式会得到 undefined，表格空白，而控制台一片安静。
    //   `?? []` 是给「后端返回 null」兜底，不是给形状变化兜底：
    //   形状真的变了它救不了你，能救你的只有这张表本身可见。
    tableData.value = res ?? []
  } catch {
    // 错误提示已在响应拦截器里统一处理
    tableData.value = []
  } finally {
    // 用 finally 保证无论成功失败都要关掉 loading 转圈，
    // 否则接口一报错页面就一直转，用户以为卡死了
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 搜索
// ---------------------------------------------------------------------------

function handleSearch() {
  // ★ 原来这里有一句 query.pageNum = 1（搜完要回第 1 页）。
  //   分页没了，这句话也跟着没了 —— 这不是"顺手删掉的"，
  //   而是「没有页码这个概念」的自然结果。
  loadData()
}

function handleReset() {
  query.name = ''
  query.status = null
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
 * <p><b>这个删除比商品删除多两种失败情况</b>：
 * <ul>
 *   <li>分类下还挂着<b>商品</b> →「该分类下还有 N 个商品，请先移走或删除这些商品」</li>
 *   <li>分类下还有<b>子分类</b> →「该分类下还有 N 个子分类，请先删除这些子分类」</li>
 * </ul>
 *
 * <p>后端先查子分类、后查商品，所以两种都占着时会先提示子分类那条 ——
 * 那是用户真正该先做的事。
 *
 * <p>前端这里怎么处理？答案是<b>什么都不用特殊处理</b>。
 * 响应拦截器已经把后端的 message 弹出来了，用户看到了明确的原因，
 * 这里的 catch 只要保证「不刷新列表、不弹成功提示」就够了。
 *
 * <p>这正是统一异常处理的价值：后端抛一个 BusinessException，
 * 前端不用写任何 if-else 判断错误类型，提示就自动到位了。
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

    // ★ 原来这里有一段「当前页只剩这一条时自动往前翻一页」的边界处理。
    //   它跟着分页器一起删掉了 —— 树没有页可翻。
    //   整个森林一次全在页面上，删掉谁都只是那一行消失。
    loadData()
  } catch {
    // 已统一提示。可能是「还有子分类」或「还有商品」，也可能是网络问题，
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
        <span class="total-tip">当前显示 {{ shownCount }} 条</span>
      </div>

      <!--
        ★★ 树模式靠三个属性，缺一不可：

          row-key="id"                          告诉表格"哪一列是主键"。
                                                树的行需要稳定的身份来记住展开状态，
                                                没有它展开/收起会错乱
          :tree-props="{ children: 'children' }" 告诉表格"子节点挂在哪个字段下"
          default-expand-all                     默认全展开。

        ⚠️ 关于 default-expand-all：它只在【首次渲染】时生效。
           搜索之后重新 load，展开状态是保留在表格内部的 ——
           所以搜索不会把树收起来，这一点是想要的（搜索结果本来就是稀疏的，
           收起只会让人以为没搜到东西）。
      -->
      <el-table
        v-loading="loading"
        :data="tableData"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
        border
        stripe
        style="width: 100%"
      >
        <!--
          ★★ 树模式下【第一个列必须是分类名称】—— 这是从一次实际的
             排版事故里改过来的。

             el-table 的树缩进和展开箭头【只画在第一列】上。
             原来 ID 是第一个列（width 70），子分类行拿到缩进之后，
             那 70px 里塞不下「缩进 + 287」，于是 287 被挤到第二行，
             看起来像一个排版 bug。
             把 name 换到第一位之后，缩进和箭头落在宽度足够的名称列上，
             ID 列只是被整体右移，不参与树的绘制。

          ⚠️ 所以：**这张表的列顺序不能"顺手换回 ID 在前"**。
             换了不会报错，只会让每一行的 ID 折行。
        -->
        <el-table-column prop="name" label="分类名称" min-width="180" show-overflow-tooltip />

        <el-table-column prop="id" label="ID" width="70" align="center" />

        <el-table-column prop="parentId" label="上级" width="90" align="center">
          <!--
            ★ 0 显示成「—」而不是「0」。
              parentId = 0 表示「没有父」，是【一级分类】的意思 ——
              直接印一个 0 出来，读的人会以为那是个真实存在的分类 id
              （尤其是它旁边就有一列真的 id，1 号分类就在那儿）。
          -->
          <template #default="{ row }">
            <span v-if="row.parentId === 0" class="muted">—</span>
            <span v-else>{{ row.parentId }}</span>
          </template>
        </el-table-column>

        <el-table-column prop="sort" label="排序" width="90" align="center">
          <!--
            排序值越小越靠前。这里直接显示数字不加修饰，
            因为「小的是前面」这个规则本身不直观，
            加了颜色或图标反而容易让人以为是别的东西。
            ★ 同级的子分类之间也按这个值排序，而且【先父后子】——
              排序在 SQL 里做完（ORDER BY sort ASC, id ASC），
              前端不再排一遍。两处都排就会出现「页面上的顺序和接口给的不一样」。
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

      <!-- ★ 这里原来有一个 el-pagination。它和分页参数、和后端的分页
           SQL、和「删完一条要翻页」的边界处理，一起在里程碑 16 删掉了。
           留下的这一行说明是【故意的】，不是忘了写。 -->
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

.muted {
  color: #c0c4cc;
}
</style>
