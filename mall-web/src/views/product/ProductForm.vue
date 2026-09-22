<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getProductDetail, createProduct, updateProduct, uploadImage } from '@/api/product'
import { getCategoryOptions } from '@/api/category'

/**
 * 商品新增/编辑弹窗。
 *
 * 新增和编辑共用一个组件，靠 productId 区分：
 *   - productId 为 null → 新增模式，表单空白
 *   - productId 有值     → 编辑模式，先查详情回填表单
 *
 * 为什么不拆成两个组件？因为两者的表单字段、校验规则、布局完全一样，
 * 只有「提交时调哪个接口」这一点差别。拆开会导致改一个字段要改两个文件，
 * 很容易改漏。
 */

const props = defineProps({
  // 控制弹窗显示隐藏。用 modelValue 是为了能配合 v-model 使用
  modelValue: { type: Boolean, default: false },
  // 要编辑的商品 id。null 表示新增
  productId: { type: Number, default: null },
})

const emit = defineEmits(['update:modelValue', 'success'])

/**
 * 实现 v-model 的双向绑定。
 *
 * Vue 3 的 v-model 本质是 :modelValue + @update:modelValue 的语法糖。
 * 用 computed 的 get/set 把它转成可读写的 visible，
 * 模板里就能直接写 v-model="visible"，而不必到处写 emit。
 */
const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const isEdit = computed(() => props.productId != null)

const formRef = ref(null)
const submitting = ref(false)
const categories = ref([])

/** 图集上限。和后端 ProductSaveDTO 上的 @Size(max = 5) 是同一个数 */
const MAX_IMAGES = 5

/**
 * 表单数据。用 reactive 而不是 ref，这样模板里可以直接写 form.name
 *
 * <h3>★★ 里程碑 11：新增字段必须改【三处】，漏一处就是一个安静的 bug</h3>
 *
 * <p>这个 form 是<b>逐字段列举</b>的（不是 {...detail}），
 * 而且 {@code resetForm()} 和编辑回填的 {@code Object.assign} 也各自列举了一遍。
 * 所以 {@code images} 要在三个地方同时出现：
 * <pre>
 *   1. 这里的初值
 *   2. resetForm()              ← 漏了：从「编辑 A」切到「新增」时会带着 A 的图集
 *   3. watch(visible) 的回填     ← 漏了：form.images 一直是 []，
 *                                  于是【每编辑一次商品就把它的图集清空一次】
 * </pre>
 *
 * <p>后两个 bug <b>都不会报错</b>，而且症状要等到用户下次打开那个商品才看得见 ——
 * 保存时提示「修改成功」，图却没了。
 *
 * <p>⚠️ 这不是新问题，是<b>同一个坑的第二次出现</b>：上面
 * {@code resetForm()} 的注释里已经写过「不用 resetFields()，
 * 因为它恢复到挂载那一刻的值」—— 那次踩的也是「字段列表要手工维护」这件事。
 * 只要这个 form 还是逐字段列举的，加字段就永远要改三处。
 */
const form = reactive({
  categoryId: null,
  name: '',
  price: null,
  stock: 0,
  cover: '',
  description: '',
  status: 1,
  // 商品图集，按展示顺序。★ 永远是数组，永远不是 null —— 见 handleSubmit 的注释
  images: [],
})

/**
 * 校验规则。
 *
 * 注意 required 的 trigger 区别：
 *   - 'blur'   输入框失去焦点时校验（适合文本输入）
 *   - 'change' 值变化时立即校验（适合下拉框、开关这类点选控件）
 * 选错了会导致「选了下拉框但提示还在」这种别扭的体验。
 */
const rules = {
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  name: [
    { required: true, message: '请输入商品名称', trigger: 'blur' },
    { max: 100, message: '商品名称不能超过 100 个字符', trigger: 'blur' },
  ],
  price: [
    { required: true, message: '请输入价格', trigger: 'blur' },
    {
      // 自定义校验函数：校验价格必须大于 0
      validator: (rule, value, callback) => {
        if (value === null || value === undefined || value <= 0) {
          callback(new Error('价格必须大于 0'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
}

/**
 * 把表单恢复到初始状态。
 *
 * 这里不用 el-form 的 resetFields()，因为它是「恢复到组件挂载那一刻的值」，
 * 而我们的表单在编辑模式下会被接口数据填充，resetFields 反而会
 * 恢复到上一次编辑的内容，不是真的清空。手动重置更可控。
 */
function resetForm() {
  form.categoryId = null
  form.name = ''
  form.price = null
  form.stock = 0
  form.cover = ''
  form.description = ''
  form.status = 1
  // ★ 三处之一（见 form 的注释）。漏了这一行，图集会从上一个商品带过来
  form.images = []
  // 清掉上一次遗留的校验红字，否则重新打开弹窗还留着「请输入商品名称」
  formRef.value?.clearValidate()
}

/** 加载分类下拉框数据 */
async function loadCategories() {
  try {
    categories.value = await getCategoryOptions()
  } catch {
    // 错误提示拦截器已经统一处理了，这里不用重复弹
  }
}

/**
 * 监听弹窗打开。
 *
 * 每次打开都重新拉数据，而不是只在 mounted 时拉一次 ——
 * 因为分类可能在别的地方被改过，每次打开都取最新的更可靠。
 * 分类数据量很小，这点开销可以接受。
 */
watch(visible, async (open) => {
  if (!open) return

  await loadCategories()

  if (isEdit.value) {
    try {
      const detail = await getProductDetail(props.productId)
      // 用 Object.assign 逐个覆盖，而不是 form = detail
      // —— reactive 对象不能整个替换，那样会丢掉响应式
      Object.assign(form, {
        categoryId: detail.categoryId,
        name: detail.name,
        price: detail.price,
        stock: detail.stock,
        cover: detail.cover ?? '',
        description: detail.description ?? '',
        status: detail.status,
        // ★ 三处之三（见 form 的注释）。
        //   ?? [] 兜的是「接口没返回这个字段」—— 后端配了 non_null，
        //   理论上空图集返回的是 []，但这里多兜一层不亏。
        //
        //   ⚠️ 必须复制一份（[...]），不能直接 detail.images ——
        //   那样 form.images 和接口返回的对象就是【同一个数组】，
        //   用户在弹窗里点上移/下移会直接改掉 detail 对象；
        //   虽然这里 detail 是临时变量、改坏了也无所谓，
        //   但「表单编辑的是自己的一份副本」是更安全的心智模型。
        images: [...(detail.images ?? [])],
      })
    } catch {
      visible.value = false
    }
  } else {
    resetForm()
  }
})

// ======================================================================
// 里程碑 11：图片上传与图集编辑
// ======================================================================

/** 正在上传的张数。多个上传可以并行，所以用计数而不是布尔值 */
const uploadingCount = ref(0)
const uploading = computed(() => uploadingCount.value > 0)

/**
 * 上传前的预检：类型 + 大小。
 *
 * <p>★ <b>这只是给用户的即时反馈，不是安全措施。</b>
 * 用户可以完全绕过这个页面直接调接口 —— 把 .exe 改名成 .png 传上来，
 * 这里拦不住，也没打算拦。
 *
 * <p>真正的校验在后端的两层：
 * <ol>
 *   <li>{@code FileStorageServiceImpl.detectImageExtension} 读文件头
 *       （魔数）判断真实格式 —— 文件名和 Content-Type 都是客户端说了算的，
 *       只有文件内容不是</li>
 *   <li>{@code application.yml} 里的 {@code multipart.max-file-size: 2MB}</li>
 * </ol>
 *
 * <p>和商品价格的 {@code :min="0.01"} 是同一个道理（那段注释已经写过一次）：
 * <b>前端校验管体验，后端校验管安全，两者都要有。</b>
 *
 * <p>⚠️ 大小这里写 2MB，和后端那个 2MB 是同一个数 —— 但它<b>不是</b>
 * 后端那条规则的定义处，只是它的一个副本。写在这里的价值是：
 * 用户选了一个 5MB 的图，立刻就知道不行，而不用等整个文件传完
 * 才收到一句「图片太大了」。
 *
 * @returns {boolean} false 表示取消这次上传
 */
function beforeUpload(file) {
  const okTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
  if (!okTypes.includes(file.type)) {
    ElMessage.error('只能上传 PNG / JPG / GIF / WEBP 格式的图片')
    return false
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('图片太大了，最大 2MB')
    return false
  }
  return true
}

/**
 * 真正执行上传。给 el-upload 的 :http-request 用。
 *
 * <h3>★★ 为什么必须用 :http-request 覆盖，绝不能用 el-upload 的 action</h3>
 *
 * <p>{@code action} 是让 el-upload <b>自己发一个 XHR</b>，绕开了
 * 我们整个 {@code request.js}。而 token 是在 request.js 的
 * <b>请求拦截器</b>里注入的 —— 那个 XHR 不带 {@code Authorization} 头，
 * 后端会返回 401。
 *
 * <p>用 el-upload 的 {@code :headers} 手动补一个 token 也能work，
 * 但那是<b>第二处 token 注入点</b>：将来 token 换了存储位置、
 * 或者改成刷新机制，这里必然漏改，而且失败方式是「上传突然 401」，
 * 和 token 本身看起来没什么关系。
 *
 * <p><b>让上传走 request.js，token 注入就永远只有一处。</b>
 *
 * <h3>★ 成功 / 失败分别由谁提示</h3>
 *
 * <p>失败不用在这里弹 —— request.js 的响应拦截器已经统一弹过了。
 * 这里再弹一句就是两条重复的错误提示。
 */
async function handleUpload(option) {
  uploadingCount.value++
  try {
    const url = await uploadImage(option.file)
    // 推进图集末尾。用户可以再用「←」把它挪到想要的位置
    form.images.push(url)
    ElMessage.success('上传成功')
  } catch {
    // 拦截器已经提示过了，这里只需要保证异常不会继续往外冒
    // （不 catch 的话 el-upload 会把它当成上传失败再走一遍 onError）
  } finally {
    uploadingCount.value--
  }
}

/** 删除图集里的一张（只改本地数组，点「确定」时才提交） */
function removeImage(index) {
  form.images.splice(index, 1)
}

/**
 * 把第 index 张往左（delta = -1）或往右（delta = 1）挪一位。
 *
 * <p>★ <b>上移/下移只改这个本地数组，不发任何请求。</b>
 * 顺序是点「确定」时跟着整个数组提交的，后端按数组下标写 sort_no。
 * 所以后端<b>没有</b>、也不需要任何独立的排序接口 ——
 * 「下标即顺序」这个约定同时简化了两边。
 *
 * <p>用 splice 换位而不是 [a,b] = [b,a]：后者在 Vue 3 的响应式数组上
 * 也能用，但 splice 的语义更清楚，而且不会产生临时解构。
 */
function moveImage(index, delta) {
  const target = index + delta
  // 边界检查。模板里对应的按钮已经 disabled 了，但函数自己也要守住 ——
  // 按钮的 disabled 是 UI 层的事，函数不该依赖它
  if (target < 0 || target >= form.images.length) return
  const [item] = form.images.splice(index, 1)
  form.images.splice(target, 0, item)
}

/**
 * 把某张图设为封面。
 *
 * <p>★ 这个按钮是<b>刻意保留的显式动作</b>，不做自动同步。
 *
 * <p>封面（{@code cover}）和图集（{@code images}）在这个设计里是
 * <b>两块独立的东西</b>：封面是一列老字段，可以是外链、可以是老路径，
 * 用户也明确要求保留那个可编辑的 URL 文本框；图集是新表。
 *
 * <p>所以「图集里加了图就自动改封面」看起来方便，实际是个坏主意 ——
 * 运营会失去对封面的控制权，而且改动封面变成了一件「我什么都没点，
 * 它自己变了」的事。宁可多一个按钮。
 *
 * <p>它做的事只有一句赋值。
 */
function setCover(url) {
  form.cover = url
  ElMessage.success('已设为封面')
}

/** 提交表单 */
async function handleSubmit() {
  // validate() 返回 Promise：校验通过 resolve，不通过 reject。
  // 所以这里必须 try-catch，否则校验失败会产生一个未捕获的 Promise 异常
  try {
    await formRef.value.validate()
  } catch {
    return // 校验没通过，直接返回，让页面上的红字提示告诉用户哪里错了
  }

  submitting.value = true
  try {
    // ★ 提交的是 form 这个 reactive 对象本身，其中 form.images
    //   【永远是一个数组】（哪怕是空的），永远不会是 null。
    //
    //   这是刻意的，而且正好绕开了后端那个三态语义最容易踩的地方：
    //     后端：null = 不改图集，[] = 清空图集
    //     这里：永远传 []，也就是永远明确告诉后端「图集就应该是这些」
    //
    //   ⚠️ 为什么这样就安全：编辑时 form.images 是从接口回填的真实图集，
    //      它【已经】准确地表达了用户想要的最终状态。所以「总是提交」
    //      既不会误清空（数组里有内容），也不会漏清空（用户删光了就是 []）。
    //
    //   反过来说，如果这里写成「images 为空就不传这个字段」，
    //   用户删光所有图再保存就会【删不掉】—— 一次也不报错的静默失败。
    if (isEdit.value) {
      await updateProduct(props.productId, form)
      ElMessage.success('修改成功')
    } else {
      await createProduct(form)
      ElMessage.success('新增成功')
    }
    // 通知父组件：成功了，该刷新列表了
    emit('success')
    visible.value = false
  } catch {
    // 失败时弹窗保持打开，让用户可以改完再提交，不用重新填一遍
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑商品' : '新增商品'"
    width="640px"
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="商品分类" prop="categoryId">
        <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
          <el-option
            v-for="item in categories"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="商品名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入商品名称" maxlength="100" show-word-limit />
      </el-form-item>

      <el-form-item label="价格" prop="price">
        <!--
          el-input-number 的 :min="0.01" 和 :precision="2" 只是 UI 层限制，
          用户可以绕过页面直接调接口。真正的校验在后端的 @DecimalMin。
          前端校验管体验，后端校验管安全，两者都要有
        -->
        <el-input-number
          v-model="form.price"
          :min="0.01"
          :precision="2"
          :step="10"
          style="width: 200px"
        />
        <span class="unit">元</span>
      </el-form-item>

      <el-form-item label="库存" prop="stock">
        <el-input-number v-model="form.stock" :min="0" :precision="0" style="width: 200px" />
        <span class="unit">件</span>
      </el-form-item>

      <!--
        封面图（里程碑 11 改造）。

        原样保留那个文本输入框 —— 老数据的 cover 全是手填的路径
        （/images/phone-03.svg 之类），这个框是它们唯一的维护入口。
        上传按钮是【新增】的能力，不是替换。

        ⚠️ 已知的开发环境特有现象：老的种子图（/images/*.svg）指向
        mall-shop/public/ 目录，在 mall-web 下【必然 404】——
        管理端的 public/ 里只有 favicon.svg。
        所以下面这个预览只对新上传的图有效，点开老商品会看到占位块。
        这是【刻意不修】的：修它要么往 mall-web/public/ 复制 47 个文件，
        要么配一条跨工程代理（生产环境根本没有 5174）。
        生产环境 /images 和 /uploads 由同一个 nginx 托管，不存在这个问题。
      -->
      <el-form-item label="封面图">
        <div class="cover-block">
          <div class="cover-preview">
            <!--
              用 el-image 而不是 <img>：老种子图在管理端会 404，
              <img> 会露出浏览器的碎图图标，而 #error 插槽能让它
              安静地退回一个占位块。
              （用户端有一个现成的 ProductImage.vue 做同样的事，
               但两个工程是独立构建、不共享代码；这里只需要一个缩略图，
               el-image 是现成的，不为了「对称」去复制一个组件过来。）
            -->
            <el-image v-if="form.cover" :src="form.cover" fit="cover" class="cover-img">
              <template #error>
                <div class="img-placeholder">加载失败</div>
              </template>
            </el-image>
            <div v-else class="img-placeholder">无封面</div>
          </div>

          <div class="cover-actions">
            <el-upload
              :show-file-list="false"
              :before-upload="beforeUpload"
              :http-request="handleUpload"
              accept="image/png,image/jpeg,image/gif,image/webp"
            >
              <el-button :loading="uploading" size="small">
                {{ uploading ? '上传中…' : '上传封面图' }}
              </el-button>
            </el-upload>
            <el-button v-if="form.cover" size="small" text @click="form.cover = ''">
              移除
            </el-button>
            <span class="hint">上传后会自动填进下面的输入框，也可以直接手填外链</span>
          </div>

          <el-input v-model="form.cover" placeholder="图片 URL，可以直接填，也可以上传后自动填入" />
        </div>
      </el-form-item>

      <!--
        商品图集（里程碑 11 新增）。

        ★ 顺序就是 form.images 这个数组的顺序 —— 界面上看到的第 1 张，
          保存后就是 sort_no = 0 的那张。上移/下移只是换数组里两个元素的
          位置，不发请求（见 moveImage 的注释）。
      -->
      <el-form-item label="商品图集">
        <div class="gallery-block">
          <div v-if="form.images.length" class="gallery-strip">
            <div v-for="(img, index) in form.images" :key="img + index" class="gallery-item">
              <el-image :src="img" fit="cover" class="gallery-img">
                <template #error>
                  <div class="img-placeholder small">失败</div>
                </template>
              </el-image>

              <div class="gallery-index">{{ index + 1 }}</div>

              <div class="gallery-actions">
                <!--
                  ⚠️ 第一张的「←」和最后一张的「→」要 disabled。
                  这里同时写了 disabled 和 moveImage 里的边界检查 ——
                  两处都要有：disabled 管的是「不让点」，
                  函数里的检查管的是「点了也不会坏」。
                -->
                <el-button size="small" text :disabled="index === 0"
                           title="上移" @click="moveImage(index, -1)">←</el-button>
                <el-button size="small" text :disabled="index === form.images.length - 1"
                           title="下移" @click="moveImage(index, 1)">→</el-button>
                <el-button size="small" text title="设为封面"
                           @click="setCover(img)">设为封面</el-button>
                <el-button size="small" text type="danger" title="删除"
                           @click="removeImage(index)">×</el-button>
              </div>
            </div>
          </div>

          <div v-else class="gallery-empty">还没有图片</div>

          <el-upload
            :show-file-list="false"
            :before-upload="beforeUpload"
            :http-request="handleUpload"
            :disabled="form.images.length >= MAX_IMAGES"
            accept="image/png,image/jpeg,image/gif,image/webp"
          >
            <el-button :loading="uploading" size="small"
                       :disabled="form.images.length >= MAX_IMAGES">
              添加图片
            </el-button>
          </el-upload>
          <span class="hint">
            最多 {{ MAX_IMAGES }} 张，当前 {{ form.images.length }} 张；
            第一张通常就是最想展示的那张
          </span>
        </div>
      </el-form-item>

      <el-form-item label="商品描述">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="请输入商品描述"
        />
      </el-form-item>

      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio :value="1">上架</el-radio>
          <el-radio :value="0">下架</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.unit {
  margin-left: 8px;
  color: #909399;
}

/* ---------------- 里程碑 11：封面图 ---------------- */

.cover-block {
  width: 100%;
}

.cover-preview {
  width: 100px;
  height: 100px;
  margin-bottom: 8px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}

.cover-img {
  width: 100%;
  height: 100%;
  display: block;
}

/* 加载失败 / 没有封面时的占位块。
   高度撑满父容器，这样图片挂了版式也不会塌 */
.img-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  color: #c0c4cc;
  font-size: 12px;
}

.img-placeholder.small {
  font-size: 11px;
}

.cover-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}

.hint {
  color: #909399;
  font-size: 12px;
}

/* ---------------- 里程碑 11：商品图集 ---------------- */

.gallery-block {
  width: 100%;
}

.gallery-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

/* ★ 每个图集项的宽度是固定的 —— 下面那排按钮（← → 设为封面 ×）
   加起来比 80px 宽，所以这里用 96px 并把按钮换行。
   如果宽度不够，el-button 会自己缩成两行，看起来像坏了。 */
.gallery-item {
  width: 96px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 4px;
  position: relative;
}

.gallery-img {
  width: 86px;
  height: 86px;
  display: block;
}

/* 左上角的序号。★ 这个数字是理解「顺序」的唯一线索 ——
   没有它，用户只能靠肉眼比对缩略图来判断上移有没有生效 */
.gallery-index {
  position: absolute;
  top: 6px;
  left: 6px;
  min-width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
  border-radius: 8px;
  padding: 0 4px;
}

.gallery-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 0;
}

/* el-button 默认有左右 margin，四个挤在 96px 里会溢出，压掉 */
.gallery-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.gallery-actions :deep(.el-button) {
  padding: 2px 4px;
  height: 22px;
  font-size: 12px;
}

.gallery-empty {
  color: #c0c4cc;
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
