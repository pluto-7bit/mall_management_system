<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { submitReview, uploadReviewImage } from '@/api/review'

/**
 * 发表评价弹窗。
 *
 * <h3>★ 形状照 {@code AddressFormDialog.vue}：v-model 控制显示 + 事件通知父组件</h3>
 *
 * <p>{@code modelValue} 决定显示，做完事 {@code emit('success')} 让父组件去刷新。
 * <b>子组件只管「事情做完了」，「接下来该干什么」由父组件决定</b> ——
 * 这里只有一个调用点（{@code Orders.vue}），所以看起来可以直接调
 * {@code load()} 了事；但那条规矩不是为了省代码，是为了让
 * 「谁能刷新列表」只有一个答案。多一个调用点时就知道值了。
 *
 * <h3>★★ 这个弹窗只有一次机会 —— 界面必须替用户记住这件事</h3>
 *
 * <p>评价「一次定终身」（不能改也不能删）。后端在
 * {@code product_review.order_item_id} 上有唯一索引，第二次提交会被拒，
 * 但它拒的是<b>另一个请求</b>，拒不了「用户手抖点错了」。
 *
 * <p>所以这里做了三件事，每一件都是为了那个目的：
 * <ol>
 *   <li>星级、文字、图片<b>全在同一屏上</b>，提交前一眼能看全</li>
 *   <li>提交按钮 {@code :loading} —— 不让一次点击变成两个请求</li>
 *   <li>上传中（{@code uploading}）时提交按钮也禁用 ——
 *       否则会提交一个「图片还没传完」的评价，而它<b>永远补不上</b></li>
 * </ol>
 * 第三条是最容易漏的：用户传了图、图还在路上，他等不及就点了提交，
 * 于是库里留下一条没有图的评价，而他再也没法改。
 *
 * <h3>★ 晒图没有上移/下移 —— 这是有理由的删减，不是漏做</h3>
 *
 * <p>商品图集（管理端 {@code ProductForm.vue}）有上移/下移按钮，
 * 因为那个顺序<b>有读者</b>：详情页的缩略图条按它排，而且运营会改了又改。
 *
 * <p>而评价一旦提交就不可修改，<b>没有任何界面能让用户调整晒图顺序</b> ——
 * 「顺序」这件事在这里没有读者。展示顺序 = 上传顺序 = 插入顺序。
 * 所以这里只有缩略图 + 一个「×」，比图集那块少一整排按钮。
 *
 * <p>★ 后端的 {@code product_review_image} 表也相应<b>没有 {@code sort_no} 列</b>
 * —— 两边的删减是同一个判断的两半。
 */

/** 晒图上限。★ 和后端 {@code ReviewSaveDTO} 的 @Size(max = 3) 是同一个数 */
const MAX_IMAGES = 3

const props = defineProps({
  /** 是否显示。配合 v-model 使用 */
  modelValue: { type: Boolean, default: false },
  /**
   * 要评价的订单明细。
   *
   * <p>⚠️ <b>需要的是「明细」不是「订单」</b>：
   * 一张订单里的两件商品要<b>分别</b>评价，所以这个对象必须带
   * {@code id}（明细 id，提交时当 {@code orderItemId}）。
   *
   * <p>⚠️ 字段名是 {@code id} 而不是 {@code orderItemId} ——
   * 它是订单明细对象自己的主键，叫什么由 {@code OrderItemVO} 决定。
   * {@code orderItemId} 是<b>评价接口</b>对外的叫法，
   * 那个名字在 {@code submit()} 里才出现（一个改名，一眼可见）。
   */
  orderItem: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'success'])

const formRef = ref(null)
const submitting = ref(false)

/**
 * 上传中的图片数量，而不是一个布尔值。
 *
 * <p>★ 理由和 {@code ProductForm.vue} 里一模一样：
 * 用户可以连着选两张图，两个上传<b>同时在跑</b>。
 * 用布尔值的话，第一个上传结束就把标志清成 false ——
 * 此时第二张还在路上，而提交按钮已经可点了。
 * <b>计数才是最朴素的正确答案。</b>
 */
const uploadingCount = ref(0)
const uploading = computed(() => uploadingCount.value > 0)

const form = reactive({
  rating: 0,
  content: '',
  images: [],
})

/**
 * 星级旁边那句人话。
 *
 * <p>★ 为什么不直接用 {@code el-rate} 自带的 {@code :texts}？
 * 因为那是「每一颗星各自一句话」（一颗星显示"很差"，两颗星显示"较差"…），
 * 而这里要的是「当前选中几颗星」这一类东西的<b>一句总述</b>。
 * 两者不冲突，但只做后者更省 —— 而且 {@code texts} 在没选中时
 * 什么都不显示，用户不知道这一栏是要干什么的。
 *
 * <p>数组下标是 {@code rating - 1}，所以 {@code rating = 0} 时要单独处理 ——
 * 不处理的话会读到 {@code TEXTS[-1]}，值是 {@code undefined}，
 * 界面上会出现一个空白（而 Vue 不会报错）。
 */
const RATING_TEXTS = ['很差', '一般', '还行', '满意', '非常满意']
const ratingText = computed(() =>
  form.rating >= 1 ? RATING_TEXTS[form.rating - 1] : '请点击星星评分',
)

const rules = {
  rating: [
    {
      // ★ 不能用 { required: true }：在 JS 里 0 是假值，
      //   async-validator 对数字类型的 required 检查会放 0 过关，
      //   于是「一颗星都没点」也能提交，后端再回一句
      //   「评分最低 1 星」—— 用户白跑一趟。
      //   自己判 value >= 1 才是最直接的写法。
      validator: (_rule, value, callback) =>
        value >= 1 ? callback() : callback(new Error('请选择评分')),
      trigger: 'change',
    },
  ],
  content: [
    { required: true, message: '请填写评价内容', trigger: 'blur' },
    { max: 500, message: '评价内容最多 500 字', trigger: 'blur' },
  ],
}

/**
 * 打开弹窗时重置表单。
 *
 * <p>★ 监听 {@code modelValue} 而不是 {@code orderItem} ——
 * 理由和 {@code AddressFormDialog} 里那段一字不差：
 * 「再次打开同一个明细的弹窗」时 {@code orderItem} 的值没变，
 * watch 不触发，表单里就残留着上一次填的内容。
 * <b>以「什么时候该重置」为准去选监听的变量。</b>
 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    form.rating = 0
    form.content = ''
    form.images = []
    // 清掉上一次留下的红字，否则新开的弹窗一进来就是红的
    formRef.value?.clearValidate()
  },
)

/**
 * 上传前的预检：类型 + 大小。
 *
 * <p>★ <b>这只是给用户的即时反馈，不是安全措施。</b>
 * 用户可以完全绕过这个页面直接调接口。
 *
 * <p>真正的校验在后端，而且会员侧的上传接口
 * （{@code POST /api/shop/images}）<b>和管理端共用同一套规则</b> ——
 * 魔数判断、2MB 上限都在 {@code FileStorageServiceImpl} 里，
 * 那个端点只有两行代码。
 * <b>所以「会员能传 exe、管理员不能」这种事不可能发生
 * ——不是因为我们小心，是因为那份规则只有一份。</b>
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
 * 真正执行上传。给 el-upload 的 {@code :http-request} 用。
 *
 * <h3>★★ 必须用 :http-request 覆盖，绝不能用 el-upload 的 action</h3>
 *
 * <p>{@code action} 会让 el-upload <b>自己发一个 XHR</b>，绕开整个
 * {@code request.js}。而 token 是在 request.js 的请求拦截器里注入的 ——
 * 那个 XHR 不带 {@code Authorization} 头，后端直接 401。
 *
 * <p>用 {@code :headers} 手动补一个 token 也能通，但那是
 * <b>第二处 token 注入点</b>：将来 token 换了存储位置，这里必然漏改，
 * 而失败方式是「上传突然 401」，和 token 本身看起来毫无关系。
 *
 * <p>失败不用在这里弹提示 —— {@code request.js} 的响应拦截器
 * 已经统一弹过了，再弹一句就是两条重复的错误。
 */
async function handleUpload(option) {
  uploadingCount.value++
  try {
    const url = await uploadReviewImage(option.file)
    form.images.push(url)
  } catch {
    // 拦截器已经提示过了。这里只需要保证异常不会继续往外冒
    //（不 catch 的话 el-upload 会把它当成上传失败，再走一遍 onError）
  } finally {
    uploadingCount.value--
  }
}

/** 删掉一张晒图。★ 只改本地数组，点「提交」时才跟着一起发出去 */
function removeImage(index) {
  form.images.splice(index, 1)
}

async function submit() {
  // validate() 校验不过会 reject，用 catch 吃掉 —— 红字已经显示在字段下面了
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    await submitReview({
      // ★ 一个改名，一眼可见：订单明细的 id 在这里变成 orderItemId。
      //   接口要的是「哪一条明细」，不是「哪一个订单」。
      orderItemId: props.orderItem.id,
      rating: form.rating,
      content: form.content.trim(),
      // ★ 空数组和 null 在【这个接口】里是同一个意思（都是「没晒图」），
      //   所以直接传数组就行，不需要先判断有没有。
      images: form.images,
    })
    ElMessage.success('评价发表成功')
    close()
    emit('success')
  } catch {
    // ★ 失败【不关弹窗】，理由同 AddressFormDialog：
    //   用户填的内容要留着，让他能改掉出问题的地方再提交。
    //
    // ⚠️ 已知的一个小瑕疵，写出来免得下一个人以为漏了：
    //   如果失败原因是「这条已经评价过了 / 还没确认收货」，
    //   说明页面上那个「评价」按钮是【过期状态】（比如在另一个标签页里
    //   已经评过）。这时弹窗留着也没有意义，用户关掉之后
    //   页面上的按钮仍然是旧的，得手动刷新。
    //   要做对需要再定义一个「让父组件强制刷新」的事件 ——
    //   为一条走不到的路径（正常操作顺序下撞不上）加一个事件不划算。
    //   用户看到的提示是清楚的，刷新一下就好。
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="发表评价"
    width="540px"
    :close-on-click-modal="false"
    @update:model-value="close"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="80px"
      @submit.prevent
    >
      <!--
        ⚠️ 商品名只是【展示】，不参与提交。
        提交的是 orderItem.id —— 商品由服务端从那条明细里查出来。
        如果这里把 productName 也提交上去，就等于给了客户端
        一个可以乱填「我评的是哪个商品」的口子。
      -->
      <el-form-item label="商品">
        <span class="review-product">{{ orderItem?.productName }}</span>
      </el-form-item>

      <el-form-item label="评分" prop="rating">
        <div class="rating-row">
          <el-rate v-model="form.rating" />
          <span class="rating-text">{{ ratingText }}</span>
        </div>
      </el-form-item>

      <el-form-item label="评价" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="说说这件商品怎么样，给其他人一个参考"
        />
      </el-form-item>

      <el-form-item label="晒图">
        <div class="shot-area">
          <div v-for="(url, i) in form.images" :key="url" class="shot-item">
            <!--
              ★ 这里用自己的 <img> 和【自己的选择器】。
              ⚠️ 别把选择器写成裸的 img —— 见 ProductImage.vue 类注释里
              那份「会命中后代 img 的选择器」清单，它已经到五条了。
              这个对话框是第六处，所以用 .shot-item img 圈住。
            -->
            <img :src="url" alt="晒图" />
            <button type="button" class="shot-remove" @click="removeImage(i)">
              ×
            </button>
          </div>

          <el-upload
            v-if="form.images.length < MAX_IMAGES"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :http-request="handleUpload"
            accept="image/png,image/jpeg,image/gif,image/webp"
          >
            <el-button size="small" :loading="uploading">
              {{ uploading ? '上传中…' : `添加晒图（${form.images.length}/${MAX_IMAGES}）` }}
            </el-button>
          </el-upload>
        </div>
      </el-form-item>

      <el-form-item label="">
        <!--
          ★ 这句话必须显式写出来。
          「不能改也不能删」如果只存在于后端的唯一索引里，
          用户会在点下提交之后才知道 —— 而那时已经晚了。
          ★ 这里【不做】二次确认弹窗：那是给「有破坏性后果」的动作用的
          （比如删除、确认收货）。发表评价的后果是"不能改"，
          不是"会造成损失"，提醒一句就够了。
        -->
        <span class="once-note">评价提交后不能修改，也不能删除，请确认后再提交</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <!--
        ★ :disabled="uploading" 是承重的，不是保险起见 ——
          用户传了图、图还在路上就点提交的话，会提交一条
          「图片还没传完」的评价，而它【永远补不上】。
      -->
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="uploading"
        @click="submit"
      >
        提交评价
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.review-product {
  color: #303133;
}

.rating-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rating-text {
  color: #909399;
  font-size: 13px;
}

.shot-area {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.shot-item {
  position: relative;
  width: 76px;
  height: 76px;
}

/* ★ 只作用于晒图容器里的 img（见模板里那段警告） */
.shot-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  display: block;
}

.shot-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  line-height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: #f56c6c;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.once-note {
  color: #e6a23c;
  font-size: 12px;
}
</style>
