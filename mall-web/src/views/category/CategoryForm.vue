<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCategoryDetail, createCategory, updateCategory } from '@/api/category'

/**
 * 分类新增/编辑弹窗。
 *
 * <p>和 ProductForm.vue 是同一个套路：
 *   - categoryId 为 null → 新增模式
 *   - categoryId 有值     → 编辑模式，先查详情回填
 *
 * <p>字段比商品少很多，只有三个，所以看起来会简单不少。
 * 如果觉得「怎么又写一遍弹窗代码」，说明你的直觉是对的 ——
 * 弹窗的开合逻辑、提交逻辑确实在重复。
 * 真实项目里这种重复通常用一个「通用表单弹窗」组件来消除，
 * 但那需要抽象出「字段配置」的数据结构，对现在的你来说
 * 收益还抵不上增加的复杂度。<b>先重复，等第三个、第四个再用同样的方式抽。</b>
 * （和 PageQueryDTO 的判断标准一致：两个可以忍，三个就该动手了。）
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
})

/**
 * 校验规则。
 *
 * <p>注意这几个字段的 trigger 都不一样，这是刻意的：
 *   - name 是文本输入 → 'blur'（移开焦点时校验）
 *   - sort 是数字输入框 → 'change' + 'blur' 都行，这里用 'blur'
 *   - status 是单选框，永远有值，不需要校验
 *
 * <p>规则的原则：前端只做「能立刻发现的问题」，
 * 比如必填没填、长度超了。真正决定能不能存的校验在后端 ——
 * 比如「分类名不能重复」需要查数据库，前端做不到，
 * 所以那个规则只在后端，前端等接口返回错误提示就行。
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
  // 清掉上一次遗留的校验红字
  formRef.value?.clearValidate()
}

/**
 * 监听弹窗打开，加载数据。
 *
 * <p>这里比商品表单简单：没有「加载分类下拉框」这一步，
 * 所以 watch 里只剩「编辑就查详情，新增就重置」。
 */
watch(visible, async (open) => {
  if (!open) return

  if (isEdit.value) {
    try {
      const detail = await getCategoryDetail(props.categoryId)
      // 用 Object.assign 逐个覆盖，而不是 form = detail ——
      // reactive 对象不能整个替换，那样会丢掉响应式
      Object.assign(form, {
        name: detail.name,
        sort: detail.sort,
        status: detail.status,
      })
    } catch {
      // 查详情失败（比如分类被别处删了），直接关掉弹窗，
      // 否则用户面对一个空表单去点确定，会莫名其妙地「新增」出一个分类
      visible.value = false
    }
  } else {
    resetForm()
  }
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
    // 拦截器已经弹了「分类名称「xxx」已存在」，用户改个名字再提交即可
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
