<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createAddress, updateAddress } from '@/api/address'

/**
 * 收货地址表单弹窗（新增 / 修改共用）。
 *
 * <h3>★ 为什么抽成组件，而不是在两边各写一遍？</h3>
 *
 * <p>这个表单有两个使用场景：
 * <pre>
 *   Addresses.vue（地址管理页）  ——  用户主动维护地址簿
 *   Checkout.vue（确认订单页）   ——  结算到一半发现没有地址，就地新增
 * </pre>
 *
 * <p>两处的表单<b>长得一样、校验规则一样、提交逻辑一样</b>，
 * 唯一不同的是提交成功后谁去刷新什么。这正是抽取的判据：
 * <b>「不同点能不能被收敛成一个参数」</b>。这里能（一个 {@code saved} 事件）。
 *
 * <p>⚠️ 反过来说：如果两边的字段集合不一样（管理端要填会员 id、
 * 用户端不用），或者校验规则不一样，那就<b>不该抽</b>——
 * 硬抽的结果是组件里塞满 {@code if (isAdmin)} 分支，
 * 最后比两份独立的代码更难改。这和 {@code OrderBaseDTO}
 * 里「订单的两个 DTO 为什么要继承」是同一个判断。
 *
 * <h3>★ 校验规则为什么和后台写的一模一样？</h3>
 *
 * <p>因为<b>这些规则本来就是同一份规则</b>，只是被执行了两次：
 * <pre>
 *   前端这一遍  →  让用户少一次"提交了才知道不行"的挫败（体验）
 *   后端那一遍  →  真正的保证，因为请求可以绕过页面直接发（安全）
 * </pre>
 *
 * <p>所以这里的规则是照着 {@code AddressSaveDTO} 抄的，<b>不是"顺便也校验一下"</b>。
 * 抄的时候要连同 {@code @Size} 的上限一起抄 —— 只校验"非空"
 * 而不管长度，用户会在填完一长串之后才被后端拒绝。
 *
 * <p>⚠️ 但要注意一个本质区别：前端的 maxlength 和 rules
 * <b>拦不住任何人</b>。真正的防线在后端，这里只是体验。
 * 任何时候都不要因为"前端已经校验过了"就在后端省掉校验。
 */

const props = defineProps({
  /** 是否显示。配合 v-model 使用 */
  modelValue: { type: Boolean, default: false },
  /**
   * 要编辑的地址。<b>null 表示新增。</b>
   * 注意是「有值就是编辑」，不另设一个 mode 参数 ——
   * 两个参数表达同一件事，就一定会有对不上的时候。
   */
  address: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const submitting = ref(false)

const isEdit = () => !!props.address?.id

const form = reactive({
  receiver: '',
  phone: '',
  region: '',
  detail: '',
  isDefault: false,
})

const rules = {
  receiver: [
    { required: true, message: '请填写收货人', trigger: 'blur' },
    { max: 50, message: '收货人姓名不能超过 50 个字符', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请填写联系电话', trigger: 'blur' },
    {
      // 和后端 AddressSaveDTO 上的 @Pattern 一字不差
      pattern: /^1[3-9]\d{9}$/,
      message: '手机号格式不正确',
      trigger: 'blur',
    },
  ],
  region: [
    { required: true, message: '请填写所在地区', trigger: 'blur' },
    { max: 100, message: '所在地区不能超过 100 个字符', trigger: 'blur' },
  ],
  detail: [
    { required: true, message: '请填写详细地址', trigger: 'blur' },
    { max: 255, message: '详细地址不能超过 255 个字符', trigger: 'blur' },
  ],
}

/**
 * 打开弹窗时把数据灌进表单。
 *
 * <p>★ 监听 {@code modelValue} 而不是 {@code address}：
 * 因为"再次打开新增弹窗"时 {@code address} 一直是 null，
 * 值没变，watch 不会触发，表单里就会残留上一次填的内容。
 * <b>以"什么时候该重置"为准去选监听的变量</b>，
 * 而不是以"数据变了没"为准。
 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      return
    }
    const a = props.address
    form.receiver = a?.receiver ?? ''
    form.phone = a?.phone ?? ''
    form.region = a?.region ?? ''
    form.detail = a?.detail ?? ''
    // ⚠️ 后端存的是 Integer（0/1），不是 boolean。
    //   这里转成布尔给复选框用，提交时再转回 0/1。
    //   中间这一层转换必须做，否则 0 会让复选框显示成"未选中"
    //   （在 JS 里 0 是假值，看起来恰好对），
    //   但 1 传进去是数字不是布尔，el-checkbox 的行为就不确定了。
    form.isDefault = a?.isDefault === 1
    // 清掉上一次留下的校验红字，否则新开的弹窗一进来就是红的
    formRef.value?.clearValidate()
  },
)

async function submit() {
  // validate() 校验不过会 reject，用 catch 吃掉 —— 红字已经显示在字段下面了
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    // ★ 提交的 body 里【没有 id、没有 memberId、没有时间字段】。
    //   id 在 URL 里（编辑时），memberId 由后端从 JWT 取，
    //   时间由数据库维护。详见 AddressSaveDTO 的注释。
    const body = {
      receiver: form.receiver.trim(),
      phone: form.phone.trim(),
      region: form.region.trim(),
      detail: form.detail.trim(),
      // 后端是 Integer，显式转成 1/0 而不是传 true/false
      isDefault: form.isDefault ? 1 : 0,
    }

    if (isEdit()) {
      // ★ 这是全量替换：上面五个字段全都会覆盖过去。
      //   所以表单必须把【全部字段】都装好再提交 ——
      //   好在表单本来就是从完整地址对象灌进去的。
      //   详见 api/address.js 里 updateAddress 的注释
      await updateAddress(props.address.id, body)
      ElMessage.success('地址已更新')
    } else {
      await createAddress(body)
      ElMessage.success('地址已添加')
    }

    close()
    // ★ 让父组件去刷新。
    //
    //   为什么不在这个组件里直接调 listAddresses()？
    //   因为它不知道父组件需要什么 —— 地址管理页要重拉列表，
    //   确认订单页除了重拉列表还要顺手选中新增的这条。
    //   **子组件只管"事情做完了"，"接下来该干什么"由父组件决定。**
    emit('saved')
  } catch {
    // 错误提示已由 request.js 统一弹出。
    // ⚠️ 这里失败【不关弹窗】—— 用户填的内容要留着，
    //   让他能改掉出问题的地方再提交，而不是从头填一遍
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
    :title="isEdit() ? '修改收货地址' : '新增收货地址'"
    width="520px"
    :close-on-click-modal="false"
    @update:model-value="close"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="90px"
      @submit.prevent
    >
      <el-form-item label="收货人" prop="receiver">
        <el-input v-model="form.receiver" maxlength="50" placeholder="请填写收货人姓名" />
      </el-form-item>

      <el-form-item label="联系电话" prop="phone">
        <el-input v-model="form.phone" maxlength="11" placeholder="11 位手机号" />
      </el-form-item>

      <el-form-item label="所在地区" prop="region">
        <!--
          ⚠️ 这里本来应该是省市区三级联动。本项目的 region 就是一个
          普通文本框（见 mall.sql 里 member_address 表的注释）——
          简化了，但保留了「region 和 detail 分成两个字段」这一点，
          因为真实系统里这两块的处理方式确实不同
          （region 可以用结构化数据做筛选，detail 永远只能当文本）
        -->
        <el-input v-model="form.region" maxlength="100" placeholder="例如：广东省深圳市南山区" />
      </el-form-item>

      <el-form-item label="详细地址" prop="detail">
        <el-input
          v-model="form.detail"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          placeholder="街道、门牌号、楼层、房间号等"
        />
      </el-form-item>

      <el-form-item label="">
        <!--
          ⚠️ 注意这个复选框在【修改】时的含义：
          取消勾选 = 把这条地址的 isDefault 改成 0。
          如果它是唯一的默认地址，取消掉之后就没有默认地址了 ——
          后端允许这种状态（getDefault 会返回 null），
          确认订单页会提示用户"还没有默认地址，请选一个"。
        -->
        <el-checkbox v-model="form.isDefault">设为默认地址</el-checkbox>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ isEdit() ? '保存' : '添加' }}
      </el-button>
    </template>
  </el-dialog>
</template>
