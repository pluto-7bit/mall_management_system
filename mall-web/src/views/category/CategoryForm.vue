<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCategoryDetail, getCategoryTree, createCategory, updateCategory } from '@/api/category'

/**
 * 分类新增/编辑弹窗。
 *
 * <p>和 ProductForm.vue 是同一个套路：
 *   - categoryId 为 null → 新增模式
 *   - categoryId 有值     → 编辑模式，先查详情回填
 *
 * <p>字段比商品少很多，所以看起来会简单不少。
 * 如果觉得「怎么又写一遍弹窗代码」，说明你的直觉是对的 ——
 * 弹窗的开合逻辑、提交逻辑确实在重复。
 * 真实项目里这种重复通常用一个「通用表单弹窗」组件来消除，
 * 但那需要抽象出「字段配置」的数据结构，对现在的你来说
 * 收益还抵不上增加的复杂度。<b>先重复，等第三个、第四个再用同样的方式抽。</b>
 * （和 PageQueryDTO 的判断标准一致：两个可以忍，三个就该动手了。）
 *
 * <h3>★★ 里程碑 16：多了一个「上级分类」选择器，它有两个坑</h3>
 *
 * <h3>坑一：数据必须【现查一次】，绝不能复用列表页那份</h3>
 *
 * <p>列表页的树可能正被搜索裁剪着（筛"手机"时，页面上只剩手机那一条路径）。
 * 拿它当父分类选项，用户搜过一次之后再打开编辑，
 * 就会<b>少掉一大批可选的父分类</b>——而他会以为那些分类不存在。
 *
 * <p>理由和「编辑商品要查详情、不要复用列表行」完全一样，
 * `CategoryAdminController.detail` 的注释里论证过同一条。
 * <b>列表页的数据是"给某一次查看用的"，不是"给别的地方引用的"。</b>
 *
 * <h3>坑二：能选的只有一级分类，其余的要【显示出来但禁用】</h3>
 *
 * <p>后端规则 4 说「父分类自己必须是根」——二级分类不能当父，
 * 否则就是三级。所以把二级分类做成<b>禁用项</b>而不是<b>不显示</b>：
 *
 * <pre>
 *   不显示  → 用户找不到「手机壳」这个分类，以为数据丢了
 *   禁用项  → 用户看得到它、点不动，旁边一行说明告诉他为什么
 * </pre>
 *
 * <p>★ 而且「哪些能选」这件事<b>不在这里判断</b> ——
 * 代码只是照着树的层级标 disabled，规则本身只有后端那一处定义者。
 * （能选却选了会被后端 1009 拒掉，前端这层只是让它不至于发生。）
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  categoryId: { type: Number, default: null },
})

const emit = defineEmits(['update:modelValue', 'success'])

/** 实现 v-model：把 props.modelValue + emit 包装成可读写的 visible */
const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const isEdit = computed(() => props.categoryId != null)

const formRef = ref(null)
const submitting = ref(false)

const form = reactive({
  name: '',
  sort: 0,
  status: 1,
  /**
   * 上级分类 id。0 = 一级分类。
   *
   * ★ 这个字段<b>永远是数字，不会是 null</b>：
   *   后端 Service 的 normalizeParentId 会把 null 归一成 0，
   *   而 0 是一个真实存在的取值（"没有父"），不是一个缺席的标记。
   *   所以新增时初值是 0，回填时兜底也是 0 —— 不要写成 null，
   *   那会让 el-tree-select 显示成 placeholder，用户以为没选过。
   */
  parentId: 0,
})

/** 上级分类的可选项（本次打开弹窗时现查的全量树） */
const parentTree = ref([])
const treeLoading = ref(false)

/**
 * 正在编辑的这个分类，在树里有没有子分类。
 *
 * ★ 直接查树，不额外发请求 —— 树本来就已经在本地的。
 *
 * ★★ 它决定选择器是否<b>整个禁用</b>，因为后端规则 5 是：
 *    「有子分类的分类永远是根，不许再挂到别人下面」。
 *    所以这种情况下用户能做的只有一件事 —— 什么都不改（保持 0）。
 *    与其让他选一个、提交、然后被 1009 拒掉，
 *    不如一开始就不给选，并说清楚原因。
 */
const selfNode = computed(() => {
  if (props.categoryId == null) return null
  const find = (nodes) => {
    for (const n of nodes || []) {
      if (n.id === props.categoryId) return n
      const hit = find(n.children)
      if (hit) return hit
    }
    return null
  }
  return find(parentTree.value)
})

const selfHasChildren = computed(() => (selfNode.value?.children?.length ?? 0) > 0)

/**
 * 把接口给的树映射成 el-tree-select 要的选项。
 *
 * @param nodes 一级分类数组
 * @param level 1 = 一级分类（可选），2 = 二级分类（禁用）
 */
function toOptions(nodes, level) {
  return (nodes || []).map((n) => {
    const node = {
      id: n.id,
      name: n.name,
      // 一级分类可选；二级分类禁用（规则 4：父分类必须自己也是根）。
      // ★ 还要排掉【自己】：把自己选成自己的上级，后端会返回 1009。
      //   编辑一个一级分类时，它自己就在这份选项里。
      disabled: level >= 2 || n.id === props.categoryId,
    }
    // ★ 有子节点才加 children 这个键。
    //   写成 children: [] 也不算错（el-tree 认空数组是叶子），
    //   但和接口返回的形状保持一致更省心 —— 后端叶子节点也是【没有这个键】。
    if (n.children?.length) {
      node.children = toOptions(n.children, level + 1)
    }
    return node
  })
}

/**
 * 选择器的数据：一个「无（一级分类）」哨兵 + 各一级分类（各自带子分类）。
 *
 * ★ 哨兵是【平级的第一个节点】，不是把所有分类包在它下面的假根 ——
 *   包成假根的话，用户每次打开下拉框面对的是一个「无（一级分类）」
 *   要再展开一层才能看到真正的分类，而它明明只是个"置空"选项。
 */
const parentOptions = computed(() => [
  { id: 0, name: '无（一级分类）', disabled: false },
  ...toOptions(parentTree.value, 1),
])

/**
 * 校验规则。
 *
 * <p>注意这几个字段的 trigger 都不一样，这是刻意的：
 *   - name 是文本输入 → 'blur'（移开焦点时校验）
 *   - sort 是数字输入框 → 'change' + 'blur' 都行，这里用 'blur'
 *   - status 是单选框，永远有值，不需要校验
 *   - parentId <b>没有前端校验</b>：它的三条规则（不能是自己、
 *     上级必须存在且启用、上级必须也是根）<b>每一条都要看数据库里的别的行</b>，
 *     前端就是做不到。这类校验只该在后端有一份定义，前端等错误提示。
 *
 * <p>规则的原则：前端只做「能立刻发现的问题」，
 * 比如必填没填、长度超了。
 */
const rules = {
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 50, message: '分类名称不能超过 50 个字符', trigger: 'blur' },
  ],
  sort: [
    { required: true, message: '请输入排序值', trigger: 'blur' },
    {
      // 自定义校验：排序值不能为负。
      // 后端的 @Min(0) 也能挡住，但等接口返回要慢一拍，
      // 用户点了确定才看到红字，体验不如立刻提示
      validator: (rule, value, callback) => {
        if (value === null || value === undefined || value < 0) {
          callback(new Error('排序值不能为负数'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

function resetForm() {
  form.name = ''
  form.sort = 0
  form.status = 1
  // ★ 新增时默认建【一级分类】。
  //   看着像废话，但它是一个明确的选择：默认挂到某个分类下面
  //   会让「新分类去哪了」变成一个需要找的问题。
  form.parentId = 0
  // 清掉上一次遗留的校验红字
  formRef.value?.clearValidate()
}

/**
 * 监听弹窗打开，加载数据。
 *
 * <p>两件事，顺序无关但都必须做：
 *   1. 现查一次全量分类树（给上级选择器用）
 *   2. 编辑就查详情、新增就重置
 *
 * <p>★ 为什么第 1 步<b>不 await</b>：
 *   它和查详情是两个互不依赖的请求，等第一个回来再发第二个纯属浪费。
 *   选择器自己有 loading 态，树回来之前它只是选项少一点。
 */
watch(visible, async (open) => {
  if (!open) return

  parentTree.value = []
  treeLoading.value = true
  // 新建一个 promise 变量而不是 await 它，是为了让下面的详情请求
  // 不必排队 —— 两个请求并发发出去
  const treePromise = getCategoryTree()
    .then((data) => {
      parentTree.value = data ?? []
    })
    .catch(() => {
      // 失败时保持空数组：选择器里只剩「无（一级分类）」。
      //
      // ★ 这里刻意【不把弹窗关掉】。上级分类拉不到时，用户至少还能改名字、
      //   改排序、改状态 —— 那些都不需要选项。关掉弹窗等于因为一个
      //   附属功能失败而让整个编辑功能不可用。
      //   ⚠️ 代价是编辑一个子分类时，选择器会显示它的 id 而不是分类名
      //     （el-tree-select 找不到对应的 label 时就这样）。
      //     丑，但不会把数据改错：parentId 还是那个值，提交回去等于没改。
      parentTree.value = []
    })
    .finally(() => {
      treeLoading.value = false
    })

  if (isEdit.value) {
    try {
      const detail = await getCategoryDetail(props.categoryId)
      // 用 Object.assign 逐个覆盖，而不是 form = detail ——
      // reactive 对象不能整个替换，那样会丢掉响应式
      Object.assign(form, {
        name: detail.name,
        sort: detail.sort,
        status: detail.status,
        // ★ 这一行不能漏。漏了的后果不是报错，而是
        //   form.parentId 保持上一次的值（新增时是 0）→
        //   提交时把一个子分类静默提升成一级分类。
        //   和 ProductForm 的「加一组字段必须改六处」是同一类坑：
        //   回填处漏一个字段，症状是"改了别的字段顺手把没碰的字段改掉了"。
        parentId: detail.parentId ?? 0,
      })
    } catch {
      // 查详情失败（比如分类被别处删了），直接关掉弹窗，
      // 否则用户面对一个空表单去点确定，会莫名其妙地「新增」出一个分类
      visible.value = false
    }
  } else {
    resetForm()
  }

  // 树请求不阻塞详情请求，但组件卸载前要给它一个落地的地方，
  // 否则失败会变成 unhandled rejection
  await treePromise
})

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return // 校验没通过，页面上的红字会告诉用户哪里错了
  }

  submitting.value = true
  try {
    if (isEdit.value) {
      await updateCategory(props.categoryId, form)
      ElMessage.success('修改成功')
    } else {
      await createCategory(form)
      ElMessage.success('新增成功')
    }
    emit('success')
    visible.value = false
  } catch {
    // 失败时弹窗保持打开。
    // 分类名重复（code 1005）会走到这里 ——
    // 拦截器已经弹了「分类名称「xxx」已存在」，用户改个名字再提交即可。
    // 层级规则（1009 / 1010）也走这里，提示同样由拦截器弹出。
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑分类' : '新增分类'"
    width="480px"
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="分类名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入分类名称" maxlength="50" show-word-limit />
      </el-form-item>

      <el-form-item label="上级分类">
        <!--
          ★★ 这里【没有 prop="parentId"】是刻意的，不是漏了。
             el-form-item 带 prop 就意味着要有一条校验规则，
             而上级分类的三条规则全都要查数据库里的别的行，
             前端做不到。写成 prop 然后留空 rules，只会让下一个人
             以为"这里应该有个校验，谁忘了写"。
        -->
        <el-tree-select
          v-model="form.parentId"
          :data="parentOptions"
          :props="{ label: 'name', value: 'id', children: 'children', disabled: 'disabled' }"
          node-key="id"
          check-strictly
          default-expand-all
          :loading="treeLoading"
          :disabled="selfHasChildren"
          :render-after-expand="false"
          popper-class="category-parent-popper"
          placeholder="无（一级分类）"
          style="width: 100%"
        />
      </el-form-item>

      <!--
        ★ 分类最多两级，所以「上级分类」下面永远该有一句说明 ——
          否则用户会以为是自己没找到建三级的地方。
      -->
      <el-form-item v-if="selfHasChildren">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="该分类下还有子分类，不能再挂到别的分类下面。"
          description="分类最多两级：如果它挂到别人下面，它的子分类就变成三级了。要改，请先把它下面的子分类删掉或提升为一级分类。"
        />
      </el-form-item>
      <el-form-item v-else>
        <span class="tip">分类最多两级：只有一级分类能当上级。</span>
      </el-form-item>

      <el-form-item label="排序" prop="sort">
        <el-input-number v-model="form.sort" :min="0" :precision="0" style="width: 160px" />
        <span class="tip">数字越小越靠前</span>
      </el-form-item>

      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">启用</el-radio>
          <el-radio :value="0">禁用</el-radio>
        </el-radio-group>
      </el-form-item>

      <!--
        用 el-alert 把「禁用之后会怎样」讲清楚。
        这种说明文字看起来啰嗦，但能省掉运营的一堆疑问 ——
        「我禁用了分类，为什么商品还在卖？」这种问题会被反复问
      -->
      <el-form-item v-if="form.status === 0">
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="禁用后，该分类不会出现在商品表单的下拉框里，但已有的商品不受影响。"
          description="★ 如果它有子分类，整条分支都会从商城的分类导航里消失（子分类自己也一起）。"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
</style>

<!--
  ============================================================================
  ★★ 这一块【故意没有 scoped】—— 全项目唯一一处。

  它修的是 Element Plus 的一个缺口：**禁用的树节点有行为、没有样式。**

  实测（不是猜的，是读 element-plus/es/components/tree/src/tree-node.mjs 看出来的）：
  <pre>
    _ctx.ns.is("focusable", !_ctx.node.disabled)   ← 只有这一个和 disabled 有关的类
    "aria-disabled": _ctx.node.disabled            ← 真正的标记在这里
  </pre>
  而 element-plus/dist/index.css 里**根本没有 .el-tree-node[aria-disabled] 这条规则**。
  于是后果是：
  <pre>
    一个二级分类显示得和一级分类一模一样（同样的颜色、同样的 pointer 光标）
    用户点它 → 什么都不会发生（行为是对的）
    ⇒ 看起来能选、点了没反应，比「根本不显示它」更糟
  </pre>

  ★ 为什么不能用 scoped + :deep()：
    下拉框被 teleport 到 body 下面，那些节点是 Element Plus 自己渲染的，
    **不带本组件的 scope 属性**，也不在任何一个带 scope 属性的祖先下面。
    scoped 和 :deep() 都命中不了。（:deep() 要求有一个"本组件的元素"当祖先。）
    ⚠️ 这一点和 App.vue 里那个 `.nav-sub` 不一样 —— 那个是【我们模板里的元素】，
       被 teleport 之后 scope 属性跟着节点走，所以那边 scoped 是有效的。

  ★ 选择器用 `[aria-disabled="true"]` 而不是某个类名：
    因为 EP 确实没有给类名，aria 属性是这里唯一稳定的钩子。
    ⚠️ 它是一个**内部实现细节**。哪天 EP 换了渲染方式，这两条规则会静默失效
       （禁用项又变得看起来能选），而【不会有任何东西报错】。
       同理，升级 Element Plus 之后值得顺手看一眼这里。
-->
<style>
/* 下拉框的类名由 popper-class 指定，只可能命中这一个组件，
   所以这几条规则不会漏到别人的下拉框上去 */
.category-parent-popper .el-tree-node[aria-disabled='true'] > .el-tree-node__content {
  color: var(--el-text-color-placeholder);
  cursor: not-allowed;
}

/* ★ 禁用项也不该有 hover 高亮 —— EP 默认给所有 __content 加了 hover 底色，
   留着它会让禁用项在鼠标划过时"活过来"一下 */
.category-parent-popper .el-tree-node[aria-disabled='true'] > .el-tree-node__content:hover {
  background-color: transparent;
}
</style>
