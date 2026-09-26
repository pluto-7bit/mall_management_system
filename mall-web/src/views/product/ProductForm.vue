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

/** 规格的几条上限。和后端 BusinessRules 里那几个常量是同一组数 —— 见下面 SKU 区的注释 */
const MAX_DIMENSIONS = 3
const MAX_VALUES_PER_DIM = 10
const MAX_SKUS = 60
const MAX_SPEC_NAME_LENGTH = 10
const MAX_SPEC_VALUE_LENGTH = 20

/**
 * 给规格定义/组合行发本地唯一 key 的计数器。
 *
 * <p>★ <b>不是随机数，不是内容，就是一个自增的序号。</b>
 * 这个「和内容无关的稳定 key」是里程碑 15 里最容易被写错的一处细节，
 * 理由见 {@code specSchema} 的注释。
 */
let keySeq = 0
function nextKey() {
  return ++keySeq
}

/**
 * 表单数据。用 reactive 而不是 ref，这样模板里可以直接写 form.name
 *
 * <h3>★★ 加一组字段必须改【六处】，漏一处就是一个安静的 bug</h3>
 *
 * <p>这个 form 是<b>逐字段列举</b>的（不是 {...detail}），
 * 而 {@code resetForm()}、编辑回填的 {@code Object.assign}、
 * {@code handleSubmit()} 里的请求体也各自列举了一遍。所以任何一组字段
 * （图集、规格）都要在六个地方同时出现：
 * <pre>
 *   1. 这里的初值
 *   2. resetForm()              ← 漏了：从「编辑 A」切到「新增」时会带着 A 的规格
 *   3. watch(visible) 的回填     ← 漏了：form.skus 一直是 []，
 *                                  于是【每编辑一次商品就把它现有的规格清空一次】
 *   4. handleSubmit() 的请求体    ← 漏了：字段根本没发出去，后端拿不到规格
 *   5. 模板里的 el-form-item
 *   6. 后端的 ProductSaveDTO / Product / mapper XML
 * </pre>
 *
 * <p>前四个 bug <b>都不会报错</b>，症状要等到用户下次打开那个商品才看得见 ——
 * 保存时提示「修改成功」，规格和价格却没了。
 *
 * <p>⚠️ 这条注释原来是「改三处」（里程碑 11 记的），现在是<b>六处</b>。
 * 多出来的第 4 处是里程碑 15 带来的新情况：<b>form 不再等于请求体了</b>
 * （见下面 {@code _k} / {@code vi} 的说明），所以提交时要显式挑字段。
 * 而第 6 处提醒的是：一个字段从前端输入框到数据库列，中间隔着<b>三个</b>后端文件。
 *
 * <p>只要这个 form 还是逐字段列举的，加字段就永远要改这六处。
 * 真正的解法是「声明式表单」（用一份 schema 生成 form / rules / 模板），
 * 那是另一个规模的改造，明确推迟。
 */
const form = reactive({
  categoryId: null,
  name: '',
  cover: '',
  description: '',
  status: 1,
  // 商品图集，按展示顺序。★ 永远是数组，永远不是 null —— 见 handleSubmit 的注释
  images: [],

  /**
   * 规格定义。每一项是一维：{@code { _k, name, values: [{ _k, text }] }}。
   *
   * <p>无规格的商品是空数组 —— <b>这仍然是一件合法的、有 SKU 的商品</b>，
   * 它只有一条「默认 SKU」。
   *
   * <p>★ <b>{@code _k} 是这个文件里最容易被想当然的一个字段。</b>
   * 它是 v-for 的 :key，必须是一个<b>和内容无关的稳定标识</b>。
   * 为什么不能用 {@code :key="vi"}（数组下标）或 {@code :key="value.text"}`：
   * <pre>
   *   取值：[黑, 白, 灰]
   *   用户删掉中间那个「白」→ 数组变成 [黑, 灰]
   *   用下标当 key：下标 1 那一行的输入框【还是同一个 DOM】，
   *                 里面显示的内容从「白」变成了「灰」——
   *                 用户看到的是「我删了白，结果白还在、灰变成了灰灰」
   *   用内容当 key：内容一变 key 就变，Vue 只能重建元素
   * </pre>
   * 前者是「串位」，后者是每次输入都重建输入框、<b>光标会跳到末尾</b>。
   * 所以用一个自增序号：它不认识内容，也就不会被内容的变化影响。
   *
   * <p>⚠️ 图集那边用 {@code :key="img + index"} 是<b>安全</b>的，
   * 因为图片不会被编辑（只能上移下移删除）。<b>这里会被逐字编辑</b>，
   * 是同一件事在两个场景下的不同答案 —— 不是图集写错了。
   */
  specSchema: [],

  /**
   * 规格组合行。每一行 {@code { _k, vi, price, marketPrice, costPrice, stock }}。
   *
   * <p>★★ <b>{@code vi} 存的是「每一维取第几个值」的下标数组，不是文字。</b>
   * 这是这个编辑器里最重要的一个决定，理由是：
   * <pre>
   *   若存文字 [{name:'颜色',value:'黑'},...]：
   *     商家把规格名「颜色」改成「颜色/」→ 12 行组合的 key 全变了
   *     → 【12 行填好的价格全被清空】，而这是保存之前、没有任何提示
   *
   *   存在下标 [0,1]：
   *     改名字、改取值文字 → 下标一个都没动 → 价格原封不动
   *     显示用的是「拿下标去 specSchema 里查」，所以文字改动会立刻反映到每一行
   * </pre>
   * 一个字段（价格）能不能在无关的编辑里活下来，取决于它旁边挂的 key 是不是稳定的。
   *
   * <p>⚠️ 代价是「删掉中间一个取值」会让后面所有行的下标错位，
   * 所以那个操作不能只调 splice —— 见 {@code removeValue()}，
   * 它要把受影响的行一起挪。
   *
   * <p>★ 里程碑 16 加的 {@code marketPrice} / {@code costPrice} 和 price
   * <b>挂在同一行、跟着同一个 {@code vi} 走</b> —— 它们必须是同一个规格的属性。
   * ⚠️ 所以 {@code rebuildSkus()} 认领旧行时这两列也要一起认领：
   * 漏一个字段的症状是「改一下规格名，成本价全没了」，而且保存前没有任何提示。
   */
  skus: [],
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
  // ★ 里程碑 15：price / stock 两条规则【删掉了】，连同下面模板里那两个输入框。
  //
  //   ⚠️ 只删输入框、忘了删这里的话，会得到一条【永远校验不过】的规则
  //   （form.price 已经不存在了，rules 里却还要求它必填），
  //   症状是点「确定」什么都不发生、红字也不出现 —— 因为 prop 已经没有了，
  //   el-form 根本不知道该把这条规则的红字显示在哪个格子上。
  //   双向都要删干净。
  //
  //   价格和库存现在挂在每一行 SKU 上，校验挪到了 handleSubmit 里的
  //   checkSkusFilled()（那是「体验」层，真正的拦截在后端 Service）。
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
  form.cover = ''
  form.description = ''
  form.status = 1
  // ★ 六处之2（见 form 的注释）。漏了这一行，图集会从上一个商品带过来
  form.images = []
  // ★ 新增商品的规格是【空的、但不是没有】—— 见 ensureDefaultSku()
  form.specSchema = []
  form.skus = []
  ensureDefaultSku()
  // 清掉上一次遗留的校验红字，否则重新打开弹窗还留着「请输入商品名称」
  formRef.value?.clearValidate()
}

/**
 * 保证「无规格」这件商品也有一条 SKU 行。
 *
 * <p>★ 这不是为了界面上好看，是后端的硬要求：{@code ProductSaveDTO.skus}
 * 上有 {@code @NotEmpty}，因为 <b>SKU 是价格和库存的唯一真源</b> ——
 * 一条 SKU 都没有的商品没有价格，那不是一个「简单商品」，
 * 那是一件卖不了的商品。
 *
 * <p>所以「无规格商品」在界面上的样子是：规格定义那块空着，
 * 下面的明细表里有<b>恰好一行</b>、规格列显示「默认」、填上价格和库存。
 * 这一行对应后端 {@code spec_json = '[]'} 的默认 SKU。
 *
 * <p>⚠️ 它只在「一行都没有」时才补，不会去动已有的行 ——
 * 否则用户在明细表里删光了行、还没来得及填，一切换焦点就被凭空塞回一行。
 */
function ensureDefaultSku() {
  if (form.skus.length === 0) {
    form.skus.push({
      _k: nextKey(), vi: [], price: null,
      marketPrice: null, costPrice: null, stock: null,
    })
  }
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
 * 分类选项的显示文案。
 *
 * <h3>★★ 里程碑 16：一级缩进，用来区分二级分类</h3>
 *
 * <p>商品可以挂在<b>任何一级</b>分类下（一级或二级都行 ——
 * 后端只对分类之间的层级有规则，对商品挂在哪一层没有）。
 * 所以下拉框里必须能看出"这个分类是二级的"，
 * 否则「手机数码」和它下面的「手机壳」并排显示，看起来是同一个层级的东西。
 *
 * <p>`'　└ '` 里那个字符是<b>全角空格 U+3000</b>，不是普通空格。
 * 普通空格在 HTML 里会被折叠掉（连续多个只显示成一个），
 * 缩进会看起来若有若无；全角空格不会被折叠。
 * 这种"用一个字符解决"的土办法比上 CSS 简单得多，而且下拉选项里
 * 也用不了 CSS 伪元素。
 *
 * <p>⚠️ <b>不要"顺手"把 `getCategoryOptions()` 改成返回树。</b>
 *    `el-select` 拿到嵌套数据会把每一个根渲染成一个<b>空白选项</b>——
 *    下拉框还在、还能点、就是一个分类名都不显示，
 *    而控制台一片安静。完整的理由写在 `api/category.js` 的注释里。
 *    缩进是在【扁平】数据上拼字符串做出来的，这正是不动数据形状的原因。
 *
 * <p>判据用 `parentId` 而不是"有没有 children"：后者在扁平数组上
 * 永远是不存在的（接口返回的每一项都没有 children 这个键）。
 */
function categoryLabel(c) {
  return (c.parentId ? '　└ ' : '') + c.name
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
      // ★ 规格定义要先转成界面形态，因为下面算 skus 的下标要用它。
      //   接口返回的 specSchema 是 [{ name, values: ['黑','白'] }]（后端 SpecGroup），
      //   界面用的是 [{ _k, name, values: [{ _k, text }] }] ——
      //   多出来的两个 _k 是 v-for 的 key，见 form 的注释。
      const schema = (detail.specSchema ?? []).map((group) => ({
        _k: nextKey(),
        name: group.name ?? '',
        values: (group.values ?? []).map((text) => ({ _k: nextKey(), text })),
      }))

      // 用 Object.assign 逐个覆盖，而不是 form = detail
      // —— reactive 对象不能整个替换，那样会丢掉响应式
      Object.assign(form, {
        categoryId: detail.categoryId,
        name: detail.name,
        cover: detail.cover ?? '',
        description: detail.description ?? '',
        status: detail.status,
        // ★ 六处之3（见 form 的注释）。
        //   ?? [] 兜的是「接口没返回这个字段」—— 后端配了 non_null，
        //   理论上空图集返回的是 []，但这里多兜一层不亏。
        //
        //   ⚠️ 必须复制一份（[...]），不能直接 detail.images ——
        //   那样 form.images 和接口返回的对象就是【同一个数组】，
        //   用户在弹窗里点上移/下移会直接改掉 detail 对象；
        //   虽然这里 detail 是临时变量、改坏了也无所谓，
        //   但「表单编辑的是自己的一份副本」是更安全的心智模型。
        images: [...(detail.images ?? [])],
        specSchema: schema,
        // ★ 把后端回来的 skus 翻译成界面形态：specs 文字 → 下标数组。
        //   这个翻译【不是可选的】：后端返回的 specs 是按规格名排过序的
        //   （SpecJson.canonical 为了去重做的排序），
        //   而界面要的是「第 0 维取第几个值」—— 两者顺序不一样，
        //   直接把 specs 当下标用会把「黑色/128G」读成「128G/黑色」。
        skus: (detail.skus ?? []).map((sku) => ({
          _k: nextKey(),
          vi: viOf(schema, sku.specs),
          price: sku.price,
          // ★ 里程碑 16：回填这两列。
          //   ⚠️ ?? null 不能省：多规格商品没设划线价时那个 key 从 JSON 里
          //   整个消失，读出来是 undefined，而 el-input-number 拿到
          //   undefined 会显示成空 —— 看起来像「没设」，保存时却会把
          //   undefined 原样发回去。显式归一成 null，让「没设」在
          //   数据里也只有一个表示。
          marketPrice: sku.marketPrice ?? null,
          costPrice: sku.costPrice ?? null,
          stock: sku.stock,
        })),
      })
      // 老数据兜底：万一这件商品一条 SKU 都没有（阶段 1 的迁移理论上
      // 给 100 件都补了，但接口是可以被绕过的），界面不能是一片空白 ——
      // 那会让商家以为「这商品没规格」，然后保存时被后端一句
      // 「至少要有一个规格组合」顶回来，而他不知道该在哪填。
      ensureDefaultSku()
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

// ======================================================================
// 里程碑 15：规格矩阵编辑器
//
// 三组数据的形状（都在 form 上，见 form 的注释）：
//   specSchema  规格定义   [{ _k, name, values: [{ _k, text }] }]   ← 商家填的
//   skus        组合明细   [{ _k, vi: [0, 1], price, stock }]        ← 叉乘出来的
//   vi          下标数组   「第 0 维取第 0 个值、第 1 维取第 1 个值」
//
// ★ 界面上的一切都是「vi → 去 specSchema 里查文字」推出来的，
//   组合行自己【不存文字】。这样改规格名、改取值文字都不需要重建任何东西。
// ======================================================================

/** 叉乘出来的组合数 = 各维取值数之积。无规格时是 1（那条默认 SKU） */
const combinationCount = computed(() => {
  if (!form.specSchema.length) return 1
  return form.specSchema.reduce((n, group) => n * group.values.length, 1)
})

const tooManyCombinations = computed(() => combinationCount.value > MAX_SKUS)

/**
 * 把后端回来的 {@code specs} 文字翻译成下标数组。
 *
 * <p>★ <b>按规格名逐项查，不能按下标对位。</b>
 * 后端返回的 {@code specs} 是 {@code SpecJson.canonical()} 排过序的
 * （它为了让唯一索引认得出「颜色:黑,内存:128G」和「内存:128G,颜色:黑」是同一个组合，
 * 按名字排了序），而 {@code specSchema} 是商家定义的顺序 ——
 * 两者经常不一样，按下标对位会把「黑色 / 128G」读成「128G / 黑色」。
 *
 * @returns {number[]} 每一维的下标；认不出来时是 -1
 */
function viOf(schema, specs) {
  const byName = new Map((specs ?? []).filter(Boolean).map((s) => [s.name, s.value]))
  return schema.map((group) => {
    const text = byName.get(group.name)
    // ★ 认不出来时返回 -1，而【不是】悄悄退回 0。
    //   退 0 的话界面会显示一个看起来完全正常的组合，商家一保存
    //   就把它真的写进库 —— 数据在谁都没发觉的情况下变了样。
    //   -1 会让那一格显示「(未知)」，并且 checkSkusFilled() 会拒绝提交。
    return group.values.findIndex((v) => v.text === text)
  })
}

/** 叉乘出全部组合，每个是「每一维取第几个值」的下标数组 */
function cartesianVi() {
  // 无规格商品：恰好一条组合，下标是空数组 —— 它就是默认 SKU
  if (!form.specSchema.length) return [[]]
  let combos = [[]]
  for (const group of form.specSchema) {
    const next = []
    for (const prefix of combos) {
      for (let i = 0; i < group.values.length; i++) {
        next.push([...prefix, i])
      }
    }
    combos = next
  }
  return combos
}

/**
 * 按当前的规格定义重新叉乘一遍明细行，<b>并把已经填好的价格和库存认领回来</b>。
 *
 * <p>★★ 这个函数存在的唯一理由是<a>「改一个规格值不能把填好的 12 行价格清空」</a>。
 * 认领用的钥匙是 {@code vi.join(',')} —— <b>纯下标，不含任何文字</b>，
 * 所以只要下标没变，价格就一定回得来。
 *
 * <p>⚠️ 它是「重建」而不是「增量修改」，所以调用它的地方必须<b>先把老行的 vi
 * 调整到位</b>，否则认领会落空、价格会丢。加一维要 push(0)、删一维要 splice(d,1) ——
 * 见 addDimension / removeDimension。这两处是唯一需要小心的地方，
 * 也是为什么它们各自只有一行注释却很重要。
 *
 * <p>★ 认领不到的新组合，价格和库存留 {@code null} 而<b>不是 0</b>：
 * 0 是一个合法的库存（卖光了），留 0 就等于「悄悄替商家填了一个卖掉的值」，
 * 而 null 会在界面上显示成空、被 {@code checkSkusFilled()} 拦住并指出是第几行。
 */
function rebuildSkus() {
  const old = new Map(form.skus.map((sku) => [sku.vi.join(','), sku]))
  form.skus = cartesianVi().map((vi) => {
    const prev = old.get(vi.join(','))
    return {
      _k: nextKey(),
      vi,
      price: prev?.price ?? null,
      // ★ 里程碑 16 的两个新字段【必须一起认领】。
      //   漏掉的症状是「改一下规格名，成本价全没了」—— 静默，而且
      //   在点保存之前页面上什么都看不出来（那两列只是变空了）。
      //   这一条和三处之N 那批「多处回填」是同一类坑，但它在 rebuild 里，
      //   更容易被漏：新加字段的人通常只去改 resetForm 和回填那两处。
      marketPrice: prev?.marketPrice ?? null,
      costPrice: prev?.costPrice ?? null,
      stock: prev?.stock ?? null,
    }
  })
}

/** 一行组合 → 要提交给后端的 specs。无规格时是空数组（后端认成默认 SKU） */
function specsOf(sku) {
  return form.specSchema.map((group, i) => ({
    name: group.name.trim(),
    value: (group.values[sku.vi[i]]?.text ?? '').trim(),
  }))
}

/** 一行组合显示用的文字。★ 「默认」是给无规格商品那唯一一行用的 */
function specTextOf(sku) {
  const parts = specsOf(sku)
    .filter((item) => item.name && item.value)
    .map((item) => `${item.name}:${item.value}`)
  return parts.length ? parts.join(' / ') : '默认'
}

/** 加一个规格维度 */
function addDimension() {
  if (form.specSchema.length >= MAX_DIMENSIONS) {
    ElMessage.warning(`最多 ${MAX_DIMENSIONS} 个规格维度`)
    return
  }
  // ★ 新取值默认是【空文字】，不是「规格值1」这种占位符。
  //   占位符会被当成真值保存进库 —— 商家没注意就多了一个叫「规格值1」的规格。
  //   空值会被 checkSkusFilled() 拦住，那才是它该有的下场。
  form.specSchema.push({ _k: nextKey(), name: '', values: [{ _k: nextKey(), text: '' }] })
  // ★ 新维度加在【末尾】，所以老行的 vi 前面几维位置不变，
  //   只需要给每一行补一个「新维度取第 0 个值」。
  //   漏了这一步，老行的 vi 长度和 cartesianVi() 对不上，认领全部落空 —— 价格全清空。
  for (const sku of form.skus) sku.vi.push(0)
  rebuildSkus()
}

/** 删掉第 d 维 */
function removeDimension(d) {
  form.specSchema.splice(d, 1)
  for (const sku of form.skus) sku.vi.splice(d, 1)
  rebuildSkus()
}

/** 给第 d 维加一个取值 */
function addValue(d) {
  const group = form.specSchema[d]
  if (group.values.length >= MAX_VALUES_PER_DIM) {
    ElMessage.warning(`一个规格最多 ${MAX_VALUES_PER_DIM} 个取值`)
    return
  }
  group.values.push({ _k: nextKey(), text: '' })
  // 新值加在末尾，老行的 vi 一个都没变，重建就能全部认领回来
  rebuildSkus()
}

/**
 * 删掉第 d 维的第 j 个取值。
 *
 * <p>★ <b>这里不能只调一次 splice。</b>
 * 删掉中间那个取值之后，排在它后面的每一行的下标都往前挪了一格；
 * 不跟着挪的话，{@code [0,2]} 那两行会去认领原本属于 {@code [0,1]} 的价格 ——
 * <b>「灰色」的价格跑到「白色」身上，而且是静默的</b>。
 *
 * <p>所以：选中的就是被删那个值的行直接丢掉，排在后面的行下标减一。
 */
function removeValue(d, j) {
  const group = form.specSchema[d]
  if (group.values.length <= 1) {
    ElMessage.warning('每一维至少要有一个取值；整维不要了请点「删除这一维」')
    return
  }
  const kept = []
  for (const sku of form.skus) {
    if (sku.vi[d] === j) continue // 这一维选的就是被删掉的值 → 这个组合不存在了
    if (sku.vi[d] > j) sku.vi[d] -= 1 // 排在它后面的往前挪一格
    kept.push(sku)
  }
  group.values.splice(j, 1)
  form.skus = kept
}

/**
 * 提交前的自查。
 *
 * <p>★ 这一层管的是<b>「体验」而不是「安全」</b>——
 * 和 {@code beforeUpload}、模板上的 {@code :min="0.01"} 是同一个位置的东西。
 * 真正的拦截在后端 Service（叉乘、上限、不漏行、不重复都在那儿判一次）。
 * 这里存在的价值只有一个：让商家知道<b>是哪一行</b>没填。
 * 后端只会说「规格组合和规格定义对不上」，它看不见界面上的行号。
 *
 * @returns {boolean} false 表示不用提交了
 */
function checkSkusFilled() {
  // 无规格的商品也要有那唯一一行默认 SKU
  ensureDefaultSku()

  const noName = form.specSchema.find((group) => !group.name.trim())
  if (noName) {
    ElMessage.warning('有规格还没有填名字')
    return false
  }
  const emptyValue = form.specSchema.find((group) => group.values.some((v) => !v.text.trim()))
  if (emptyValue) {
    ElMessage.warning(`规格「${emptyValue.name.trim()}」里还有没填的取值`)
    return false
  }
  if (tooManyCombinations.value) {
    ElMessage.warning(
      `规格组合有 ${combinationCount.value} 种，超过上限 ${MAX_SKUS}，请减少规格维度或取值`,
    )
    return false
  }
  // 下标为 -1 = 后端回来的组合和规格定义对不上（见 viOf）。这种情况不该被保存
  if (form.skus.some((sku) => sku.vi.some((i) => i < 0))) {
    ElMessage.warning('规格组合和规格定义对不上，请重新调整规格后再保存')
    return false
  }

  // ★ 下面两条是「指出第几行」的。留空的价格/库存如果直接发出去，
  //   后端会返回 400「价格不能为空」—— 那句话是对的，但商家不知道说的是哪一行。
  const noPrice = form.skus.findIndex((sku) => sku.price === null || sku.price === undefined)
  if (noPrice >= 0) {
    ElMessage.warning(`第 ${noPrice + 1} 行还没有填价格`)
    return false
  }
  const noStock = form.skus.findIndex((sku) => sku.stock === null || sku.stock === undefined)
  if (noStock >= 0) {
    ElMessage.warning(`第 ${noStock + 1} 行还没有填库存`)
    return false
  }
  // 价格必须 ≥ 0.01。模板上的 :min="0.01" 管「不让点小箭头调下去」，
  // 但输入框里是可以直接敲 0 的 —— 所以这里要再判一次
  if (form.skus.some((sku) => Number(sku.price) < 0.01)) {
    ElMessage.warning('价格必须大于 0')
    return false
  }

  // ★★ 里程碑 16：划线价必须【严格高于】售价，成本价不能是负数。
  //
  //   ⚠️ 前端这两条【不是】为了安全 —— 后端 planSkus 会拦（400），
  //   而且它才是唯一说了算的那一处。这里判的价值是**指出第几行**：
  //   后端那句「划线价必须高于售价……（颜色:黑 / 尺码:S）」已经到了
  //   能做到的程度，但商家在 12 行的表格里还是要自己找一眼。
  //   这个「指出第几行」的分工，和上面 noPrice / noStock 两条完全一致。
  //
  //   ★ 判据必须和展示规则一致：[严格大于]，不是 [大于等于]。
  //     允许相等的话，商城页那条「marketPrice > price 才画删除线」
  //     就永远不会成立 —— 商家以为自己设了，页面上什么都不显示。
  const badMarket = form.skus.findIndex(
    (sku) => sku.marketPrice !== null && sku.marketPrice !== undefined
      && Number(sku.marketPrice) <= Number(sku.price),
  )
  if (badMarket >= 0) {
    ElMessage.warning(`第 ${badMarket + 1} 行的划线价必须高于售价，否则商城页不会显示它`)
    return false
  }
  const badCost = form.skus.findIndex(
    (sku) => sku.costPrice !== null && sku.costPrice !== undefined
      && Number(sku.costPrice) < 0,
  )
  if (badCost >= 0) {
    ElMessage.warning(`第 ${badCost + 1} 行的成本价不能为负数`)
    return false
  }
  return true
}

// ======================================================================
// 里程碑 16：毛利率预览
// ======================================================================

/** 保留两位小数的四舍五入。
 *
 *  <p>⚠️ 加 {@code Number.EPSILON} 是为了对付 {@code 1.005} 这类数：
 *  JS 里 {@code 1.005 * 100} 是 {@code 100.49999999999999}，
 *  直接 Math.round 会得到 100（也就是 1.00），差了一分钱。
 */
function round2(v) {
  return Math.round((v + Number.EPSILON) * 100) / 100
}

/**
 * 这一行「已经填了成本价」吗。
 *
 * <h3>★★ 「未设置」必须看 costPrice，不能看毛利是不是 0 —— 这是本项目
 * 「前端一律用宽松真值判断」那条约定的【例外】</h3>
 *
 * <p>因为成本价等于售价时毛利是 <b>0</b>，而 {@code !0} 为 <b>true</b>。
 * 写成 {@code v-if="row.grossMargin"} 的话，表格会把
 * 「毛利 0.00」（这个规格不赚钱）显示成「未设置」（这件商品没填成本）——
 * 两件完全不同的事，而且都是商家要立刻看到的事之一。
 *
 * <p>★ 里程碑 15 那条约定之所以是「宽松真值」，前提是那些字段
 * （{@code defaultSkuId}、{@code marketPrice}）<b>没有 0 这个合法值</b>。
 * <b>约定的边界就是「0 是不是一个合法的答案」</b> ——
 * 这里它是，所以这里不适用。
 */
function hasCost(row) {
  return row.costPrice !== null && row.costPrice !== undefined
}

/**
 * 毛利率的<b>预览</b>：按当前填的售价和成本价现算。没填成本价返回 null。
 *
 * <h3>★★ 这里确实把 AdminSkuVO 的公式抄了第二遍，而这是刻意的</h3>
 *
 * <p>本项目有一条反复引用的判断：<b>同一个事实有两份实现，就一定会分岔。</b>
 * 而 {@code AdminSkuVO.of()} 已经把「毛利 = 售价 - 成本、毛利率 = 毛利/售价×100、
 * 两位小数」算过一遍了。那为什么这里还要算？
 *
 * <p>因为<b>它们算的不是同一个时刻的数</b>：
 * <pre>
 *   AdminSkuVO.grossMarginPercent  → 库里【已经存着】的那个价格算出来的
 *   这个函数                        → 输入框里【还没保存】的那两个数算出来的
 * </pre>
 * 商家把成本价从 70 改成 90 的那一刻，后端那一份还没变（要保存之后才变），
 * 而这一列如果不跟着动，页面上就会写着一个<span>和旁边输入框对不上的</span>毛利率 ——
 * <b>一个显示出来的数旁边摆着它的两个输入，却说不是它们算的，那才是真的谎话。</b>
 * 所以在「实时反馈」和「一份实现」之间，这里选了前者，并且把代价写在这里。
 *
 * <p>★ 代价具体是什么：两边的舍入必须一样，否则会出现
 * 「预览 30.00%、保存后 30.01%」这种一次保存就变的数字。
 * 所以下面刻意复刻了后端的算法：<b>先算毛利并舍入到两位，再除以售价</b>
 * （不是先除再舍入）—— 顺序不一样，结果会差一分钱。
 * 后端那份在 {@code AdminSkuVO.of()}，改一处必须两处都改。
 *
 * <p>⚠️ 除零：售价是 0 时返回 null 而不是 Infinity。表单校验挡住了
 * {@code price < 0.01}，但那和「除的时候它不为 0」是两件事。
 *
 * @returns {{text: string, margin: string, loss: boolean}|null}
 *          {@code loss} 表示亏本卖（成本 > 售价）—— 那是真实的生意状态，
 *          不该被藏起来，所以模板里标红而不是显示成「-」。
 *          {@code margin} 是绝对毛利（元），只用在 title 上 —— 百分比是
 *          给「这门生意赚不赚」看的，绝对额是给「这一单赚多少」看的。
 */
function marginPercentOf(row) {
  if (!hasCost(row)) {
    return null
  }
  const price = Number(row.price)
  const cost = Number(row.costPrice)
  if (!(price > 0) || Number.isNaN(price) || Number.isNaN(cost)) {
    return null
  }
  // ★ 先算毛利、舍入，再除 —— 和后端 AdminSkuVO.of() 的顺序完全一致
  const margin = round2(price - cost)
  const percent = round2((margin * 100) / price)
  return { text: `${percent.toFixed(2)}%`, margin: margin.toFixed(2), loss: margin < 0 }
}

/*
 * ★★ 模板里【直接调】marginPercentOf(row)，一行调了三次（判空 / 取文字 / 判亏损）。
 *
 *   这不是偷懒，是【试过另一种写法并且被它咬了一口】之后的选择。
 *   本来的写法是一个 computed 缓存：
 *
 *       const marginPreview = computed(() => {
 *         const map = new Map()
 *         for (const sku of form.skus) map.set(sku._k, marginPercentOf(sku))
 *         return map
 *       })
 *       模板：v-if="marginPreview[row._k]"
 *
 *   ⚠️ 它【编译得过、也跑得起来、控制台一片安静】，但**每一行都显示「未设置」**。
 *      原因是 Map 的下标访问：`map[3]` 走的是普通属性查找，
 *      而 Map 的键不挂在对象属性上 —— 它永远是 undefined，
 *      于是每一行都掉进 v-else 分支。要用得写 `map.get(row._k)`。
 *
 *   ★ 这个坑值得记下来，因为它和本轮那两个静默 bug 是同一个形状：
 *     **错的是「取不到」，而「取不到」在界面上长得和「本来就没有」一模一样**——
 *     毛利率列显示「未设置」，而那正是这一列的正常状态之一。
 *     一个永远不会出现「未设置」以外的值的分支，不会有人去怀疑它。
 *
 *   所以改回直调：多算两次纯函数（≤60 行、每行一次减法和一次除法），
 *   换掉一整类「取不到」的可能。★ 这也和这个模板里 specTextOf(row) /
 *   categoryLabel(c) 的既有写法一致。
 */

/** 提交表单 */
async function handleSubmit() {
  // validate() 返回 Promise：校验通过 resolve，不通过 reject。
  // 所以这里必须 try-catch，否则校验失败会产生一个未捕获的 Promise 异常
  try {
    await formRef.value.validate()
  } catch {
    return // 校验没通过，直接返回，让页面上的红字提示告诉用户哪里错了
  }

  // ★ 规格和价格的自查。放在 submitting 之前 —— 校验不过就不该进入「提交中」状态
  if (!checkSkusFilled()) return

  submitting.value = true
  try {
    // ★★★ 里程碑 15：提交的【不再是 form 本身】了。
    //
    //   以前可以直接 createProduct(form)，因为 form 的字段和接口字段一一对应。
    //   现在不是：form.specSchema 每一项多了 _k，
    //   form.skus 每一行多了 _k 和 vi —— 三个都是【纯界面】的东西，
    //   后端既没有这些字段，也不该有（vi 是本地下标，换个商品的规格定义就毫无意义）。
    //
    //   ⚠️ 多传字段【不会报错】：Spring Boot 默认忽略请求体里不认识的字段。
    //   所以「忘了挑字段」这个 bug 是无声的 —— 请求发出去、返回 200、提示保存成功，
    //   而多出来的 _k/vi 去哪了没有任何人知道，也永远不会有人发现。
    //   必须显式构造，一个界面专用字段都不能漏出去。
    const payload = {
      categoryId: form.categoryId,
      name: form.name,
      cover: form.cover,
      description: form.description,
      status: form.status,
      // ★ images 传的永远是数组（哪怕是空的），永远不会是 null。
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
      images: form.images,
      // 规格定义：去掉 _k，只留名字和取值
      specSchema: form.specSchema.map((group) => ({
        name: group.name.trim(),
        values: group.values.map((v) => v.text.trim()),
      })),
      // 组合明细：把 vi 翻译回文字。
      //
      // ⚠️ 这里【没有】像图集那样保留三态语义 —— specSchema 和 skus 是
      //    「一定有值」的，不是「null 表示不改」。理由：价格和库存只存在于
      //    SKU 上，所以「这次保存不带价格」这句话没有意义。DTO 上的
      //    @NotNull / @NotEmpty 也是同一个意思。
      //
      // ★ 无规格的商品：specSchema 是 []、skus 是恰好一条 specs 为 [] 的行，
      //   后端拿它当默认 SKU（spec_json = '[]'）。
      skus: form.skus.map((sku) => ({
        specs: specsOf(sku),
        price: sku.price,
        // ★ 里程碑 16：这一行的划线价和成本价。
        //
        //   ⚠️ 「没填」在这里【必须】原样送 null，不能省成 0，也不能
        //   干脆不传这个键：
        //     - 送 0 → 后端存下 0，于是「划线价 0 元」，而 0 > price
        //       不成立所以商城页不显示 —— 靠巧合对了，但库里多了一个
        //       商家从没填过的 0；毛利率那边更糟，成本 0 意味着 100% 毛利。
        //     - 不传这个键 → Spring 反序列化成 null，和后端语义
        //       （null = 没设）恰好一致，但那是【碰巧】对的；
        //       而 updatePriceCostStock 是全量覆盖，一旦哪天改成
        //       「传了才更新」，这一处的行为就变了。显式写出来。
        marketPrice: sku.marketPrice,
        costPrice: sku.costPrice,
        stock: sku.stock,
      })),
    }

    if (isEdit.value) {
      await updateProduct(props.productId, payload)
      ElMessage.success('修改成功')
    } else {
      await createProduct(payload)
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
    width="780px"
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="商品分类" prop="categoryId">
        <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
          <el-option
            v-for="item in categories"
            :key="item.id"
            :label="categoryLabel(item)"
            :value="item.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="商品名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入商品名称" maxlength="100" show-word-limit />
      </el-form-item>

      <!--
        规格定义（里程碑 15 新增）。

        ★ 这里【替换掉了】原来的「价格」和「库存」两个 el-form-item ——
          价格和库存搬到了下面每一行 SKU 上，商品的起售价和总库存
          由后端从 SKU 汇总出来，不再由人填。

        ⚠️ 只删这两个输入框、忘了删 rules 里那两条规则，
          会得到一条永远校验不过的幽灵规则（见 rules 的注释）。

        形状：一维一行，每行是「规格名 + 若干个取值」。
        整个界面上看到的顺序就是数组顺序，后端按这个顺序存 spec_schema。
      -->
      <el-form-item label="规格">
        <div class="spec-block">
          <div v-for="(group, d) in form.specSchema" :key="group._k" class="spec-row">
            <el-input
              v-model="group.name"
              class="spec-name"
              :maxlength="MAX_SPEC_NAME_LENGTH"
              placeholder="规格名，如 颜色"
            />

            <div class="spec-values">
              <!--
                ★ :key 用的是 value._k，不是下标也不是文字 —— 见 form 里
                  specSchema 的注释。这里用下标或文字，删掉中间一个取值时
                  输入框会「串位」（光标在原位，显示的值变成下一个）。
              -->
              <div v-for="(value, j) in group.values" :key="value._k" class="spec-value">
                <el-input
                  v-model="value.text"
                  :maxlength="MAX_SPEC_VALUE_LENGTH"
                  placeholder="取值，如 黑"
                />
                <el-button size="small" text type="danger" title="删除这个取值"
                           @click="removeValue(d, j)">×</el-button>
              </div>
              <el-button size="small" text
                         :disabled="group.values.length >= MAX_VALUES_PER_DIM"
                         @click="addValue(d)">+ 加取值</el-button>
            </div>

            <el-button size="small" text type="danger"
                       @click="removeDimension(d)">删除这一维</el-button>
          </div>

          <el-button v-if="form.specSchema.length < MAX_DIMENSIONS" size="small"
                     @click="addDimension">+ 添加规格维度</el-button>

          <div class="hint">
            <template v-if="form.specSchema.length">
              当前 {{ form.specSchema.length }} 维，叉乘出
              <b :class="{ danger: tooManyCombinations }">{{ combinationCount }}</b>
              种组合（上限 {{ MAX_SKUS }}）
            </template>
            <template v-else>
              不填规格就是「无规格商品」—— 下面会自动有一条默认配置，价格和库存填在它上面
            </template>
          </div>
        </div>
      </el-form-item>

      <!--
        规格明细表（里程碑 15 新增）。每一行是一个规格组合，价格和库存填在这里。

        ★ 这一整张表的数据都是【算出来的】：规格列的文字由 vi 去规格定义里查，
          增删维度/取值时由 rebuildSkus() 重新叉乘并认领已填的价格。
          它不是一个可以自由增删行的表格 —— 行数由规格定义决定，
          这也是「不许漏行」这条后端规则在界面上的样子。
      -->
      <el-form-item label="规格明细">
        <div class="sku-block">
          <el-table :data="form.skus" size="small" border>
            <el-table-column label="规格" min-width="120">
              <template #default="{ row }">{{ specTextOf(row) }}</template>
            </el-table-column>

            <el-table-column label="价格（元）" width="112">
              <template #default="{ row }">
                <!--
                  :min="0.01" / :precision="2" 只是 UI 层限制，用户可以绕过页面
                  直接调接口。真正的校验在后端的 @DecimalMin。
                  前端校验管体验，后端校验管安全，两者都要有。
                  （checkSkusFilled() 里还判了一次 —— 因为输入框里可以直接敲 0，
                    :min 只挡小箭头）
                -->
                <el-input-number v-model="row.price" :min="0.01" :precision="2"
                                 :step="10" :controls="false" style="width: 100%" />
              </template>
            </el-table-column>

            <!--
              划线价（里程碑 16 新增）。填了就显示在商城页的售价旁边，划一道删除线。

              ★ 三列的宽度是【一起算出来的】，不是各填一个「看着差不多」的数：
                弹窗 780px − 左右内边距 40px − 表单 label 90px ≈ 650px 可用。
                规格 120(最小) + 价格 112 + 划线价 112 + 成本价 112 + 毛利率 80 + 库存 90
                = 626px，刚好留一点余量。
                ⚠️ 少给一列就会挤出横向滚动条 —— 而这一列恰好是「第 5 列」，
                   商家的显示器越窄越容易撞上，属于「我这里好好的」。

              ★ 用 :min="0.01" 而不是 0：0 是一个**合法的成本价**（赠送品、清库存），
                但 0 元的划线价没有任何含义 —— 「原价 0 元」不构成一个折扣。

              ⚠️ 这里【不】做「必须高于售价」的输入框级拦截（没有 :min 联动），
                因为那会让商家根本输不进 80 —— 而他可能就是要先输 80、再改售价。
                拦截放在点「确定」那一步（checkSkusFilled），那里能说清楚是哪一行。
            -->
            <el-table-column label="划线价" width="112">
              <template #default="{ row }">
                <el-input-number v-model="row.marketPrice" :min="0.01" :precision="2"
                                 :controls="false" style="width: 100%"
                                 placeholder="不填则不显示" />
              </template>
            </el-table-column>

            <!--
              成本价（里程碑 16 新增）。★ 这一列【只在管理端存在】——
              它不在任何 /api/shop/** 的响应里，用户端从页面到接口都看不到它。
              守住这条边界的是 sql/test-price.py 的 E 组（双向扫描）。

              ★ :min="0" 而不是 :min="0.01"：成本 0 是真实的（赠品、清库存），
                而售价 0 不是（那是白送，且毛利率要除以它）。
                「一条规则该不该拦住某个值」的判据是【这个值在业务上有没有意义】，
                不是「它看起来是不是太极端」。
            -->
            <el-table-column label="成本价" width="112">
              <template #default="{ row }">
                <el-input-number v-model="row.costPrice" :min="0" :precision="2"
                                 :controls="false" style="width: 100%"
                                 placeholder="不填则不记" />
              </template>
            </el-table-column>

            <!--
              毛利率（里程碑 16 新增）。★ 只读，不能编辑 —— 它是售价和成本价的
              计算结果，不是一个独立的输入。做成输入框就会立刻出现
              「填了毛利率但售价和成本对不上它」这种自相矛盾的行。

              ★★ 「未设置」的判断看 costPrice，不看毛利是不是 0。
                见 hasCost() 上面那段注释 —— 这是本项目
                「前端一律用宽松真值判断」那条约定【唯一】的例外。

              ★ 亏本卖（成本 > 售价）标红而不是显示成「-」：它是真实的
                生意状态（清库存、引流款），商家最需要看到的就是它，
                藏起来等于让他在亏钱的时候看不见。

              ⚠️ 这一列显示的是【预览】（旁边那两个框现算的），
                不是后端返回的 grossMarginPercent —— 理由见 marginPercentOf()。
            -->
            <el-table-column label="毛利率" width="80" align="center">
              <template #default="{ row }">
                <span v-if="marginPercentOf(row)" class="margin"
                      :class="{ 'margin-loss': marginPercentOf(row).loss }"
                      :title="`毛利 ${marginPercentOf(row).margin} 元`">
                  {{ marginPercentOf(row).text }}
                </span>
                <span v-else class="margin-unset">未设置</span>
              </template>
            </el-table-column>

            <el-table-column label="库存" width="90">
              <template #default="{ row }">
                <el-input-number v-model="row.stock" :min="0" :precision="0"
                                 :controls="false" style="width: 100%" />
              </template>
            </el-table-column>
          </el-table>

          <!--
            ⚠️ 这是本轮【已知的取舍】，不是没想到：
               SKU 的保存是「全量覆盖」，不是「增量 ±N」。所以运营打开编辑页时
               库存是 10、期间卖掉了 3 件、再点保存 —— 库存会回到 10，凭空多出 3 件。
               真正的修法是「库存调整走增量」，明确推迟（见 README 的已知取舍）。
               在这一行提示里说清楚，比让运营自己撞上去强。
          -->
          <div class="hint warn">
            ⚠️ 保存会把这里的库存<b>原样覆盖</b>到数据库。如果打开本页之后卖出了商品，
            请先关掉重开再改，否则会把库存改回去。
          </div>
        </div>
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
        这是【刻意不修】的：修它要么往 mall-web/public/ 复制 105 个文件，
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

/* ---------------- 里程碑 15：规格定义 ---------------- */

.spec-block {
  width: 100%;
}

/* 一维一行：规格名 + 取值们 + 删除按钮。
   ★ 用 flex-wrap 而不是固定分栏 —— 取值多了会自动换行，
     不像表格那样把规格名挤成一列窄条 */
.spec-row {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
}

.spec-name {
  width: 120px;
  flex: none;
}

/* ★ 取值区是这里唯一会长高的部分（一行放不下就换行），
   所以它吃掉所有剩余宽度，规格名和删除按钮保持固定 */
.spec-values {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  flex: 1;
  min-width: 200px;
}

/* 一个取值 = 输入框 + 一个 ×。
   ★ 输入框宽度必须固定，否则每输入一个字宽度就变一点，整行都在抖 */
.spec-value {
  display: flex;
  align-items: center;
  gap: 0;
}

.spec-value .el-input {
  width: 110px;
}

.spec-value :deep(.el-button) {
  padding: 2px 4px;
}

.hint {
  color: #909399;
  font-size: 12px;
  margin-top: 6px;
  line-height: 1.6;
}

/* 组合数超上限时把它标红。★ 只标红不拦输入 ——
   拦在「点确定」那一步（checkSkusFilled），因为商家可能是
   先加维度再删取值，中途短暂超限是正常的编辑过程 */
.danger {
  color: #f56c6c;
}

/* ---------------- 里程碑 15：规格明细表 ---------------- */

.sku-block {
  width: 100%;
}

.hint.warn {
  color: #e6a23c;
}

/* ---------------- 里程碑 16：毛利率列 ---------------- */

/* 毛利率是【算出来的】，用等宽体显示。
   ★ font-variant-numeric: tabular-nums 让每个数字占一样宽 ——
     30.00% 和 8.00% 位数不同，不用等宽体的话这一列的数字会左右跳，
     12 行一起看起来像在抖。金额列（价格/划线价/成本价）不需要这个，
     因为它们是输入框，不参与这种逐行比对。 */
.margin {
  font-variant-numeric: tabular-nums;
  color: #67c23a;
  font-size: 12px;
}

/* 成本 > 售价 = 亏本卖。★ 标红，不隐藏、不显示成「-」——
   这是真实的生意状态，商家最需要在这一列看到的就是它。 */
.margin-loss {
  color: #f56c6c;
}

/* 「未设置」。⚠️ 走这条分支的【只有】costPrice 是 null，
   不是「毛利为 0」—— 见 hasCost() 的注释。 */
.margin-unset {
  color: #c0c4cc;
  font-size: 12px;
}
</style>
