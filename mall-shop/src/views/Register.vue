<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 用户端注册页。
 *
 * <p>★ 这个页面里最值得看的是<b>校验规则的两层设计</b>：
 * <pre>
 *   前端校验（这里的 rules）  → 为了【体验】：即时反馈，不用等一次网络往返
 *   后端校验（DTO 上的注解）  → 为了【正确】：真正拦住脏数据的是它
 * </pre>
 *
 * <p>两层的规则要尽量一致（这里的前端规则就是照抄
 * {@code MemberRegisterDTO} 的注解写的），但<b>绝对不能只有前端</b>。
 *
 * <p>原因很简单：前端校验跑在用户的浏览器里，用户想绕过的话，
 * 打开 F12 或者直接用 Postman 调接口就行了。
 * <b>凡是前端做的校验，都只是为了让正常用户少犯错，
 * 挡不住任何一个想作恶的人。</b>
 *
 * <p>同理，下面「两次密码是否一致」的检查也<b>只需要在前端做</b> ——
 * 它纯粹是给"手滑打错"用的，服务端根本不关心这个字段，
 * 所以它压根不会出现在请求体里（见 handleSubmit）。
 * 把 confirmPassword 传给后端是个常见的多余做法。
 */

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const usernameInputRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  phone: '',
})

/**
 * 确认密码的自定义校验器。
 *
 * <p>Element Plus（底层是 async-validator）的写法：
 * 校验通过调 {@code callback()}，不通过调
 * {@code callback(new Error('提示'))}。
 *
 * <p>注意这里是<b>函数</b>而不是正则 —— 因为它要比较两个字段的值，
 * 规则本身依赖运行时状态，没法写成静态的 pattern。
 */
function validateConfirmPassword(rule, value, callback) {
  if (!value) {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    {
      // ★ 这条正则和后端 MemberRegisterDTO 上的 @Pattern 完全一致。
      //   保持一致的原因不是「规范」，而是「避免用户白填一遍表单」：
      //   如果前端宽松、后端严格，用户会填完点提交，
      //   等一次网络往返之后才被告知格式不对 —— 很挫败。
      //   前端严格一点，用户在输入框失焦的瞬间就知道了。
      pattern: /^[a-zA-Z0-9_]{4,20}$/,
      message: '账号只能是 4-20 位的字母、数字或下划线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 100, message: '密码长度需在 6-100 位之间', trigger: 'blur' },
  ],
  confirmPassword: [
    // 用自定义校验器，所以要写 trigger
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
  nickname: [
    { max: 50, message: '昵称不能超过 50 个字符', trigger: 'blur' },
  ],
  phone: [
    {
      // 和后端一样写成「允许空 + 手机号格式」。
      // 不写 required 时，空字符串仍然会走 pattern 校验，
      // 所以这条正则必须容忍空值 —— 和 MemberRegisterDTO 里的
      // ^$|^1[3-9]\d{9}$ 是同一个理由
      pattern: /^$|^1[3-9]\d{9}$/,
      message: '手机号格式不正确',
      trigger: 'blur',
    },
  ],
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    /**
     * ★ 注意这里<b>手动挑字段</b>，而不是直接传 {@code form}。
     *
     * <p>因为 form 里有 {@code confirmPassword}，而后端的
     * {@code MemberRegisterDTO} 根本没有这个字段。
     *
     * <p>多传一个字段会怎样？Spring 的 Jackson 默认会
     * <b>忽略不认识的字段</b>，所以它不会报错 —— 这既是好事也是坏事：
     * 好在于前后端可以各自演进；坏在于<b>打错字段名不会有任何提示</b>。
     * 比如把 {@code nickname} 拼成 {@code nickName}，
     * 后端不会报错，只是那个值被静默丢弃，用户会发现"昵称怎么没存上"，
     * 而你要花很久才能想到是这个原因。
     *
     * <p>所以这里显式列出要传的字段：<b>让"这一次请求发了什么"
     * 在代码里一眼可见</b>，而不是靠读表单结构去推断。
     */
    await userStore.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || undefined,
      phone: form.phone || undefined,
    })

    ElMessage.success('注册成功，已自动登录')
    router.replace('/')
  } catch (e) {
    /**
     * ★ 这里对被占用的账号做<b>差别处理</b>：把光标移回账号输入框。
     *
     * <p>这就是后端为什么要单独给「账号已被占用」一个错误码
     * （{@code USERNAME_TAKEN = 1007}）而不是复用「名称重复」（1005）——
     * 因为前端需要针对它做<b>不同的动作</b>，
     * 而不只是弹一句提示。
     *
     * <p><b>★ 这里以前是 {@code e.message.includes('已被注册')}，刚刚改掉的。</b>
     * 那种写法的毛病是：它依赖后端那句提示文案的具体措辞。
     * 后端哪天把「该账号已被注册，换一个试试」改成「用户名已存在」，
     * 这个聚焦行为就会<b>静默失效</b> —— 不报错、不崩溃，
     * 只是那个贴心的小动作不见了，而且没人会发现。
     *
     * <p><b>凡是靠字符串匹配一段人类可读文案来判断程序逻辑的地方，
     * 都是一个「等文案改了就悄悄坏掉」的定时炸弹。</b>
     * 文案是给人看的，可以随时润色；错误码是给程序用的，是接口契约的一部分。
     * 要判断，就判断错误码 —— 这也是后端 {@code GlobalExceptionHandler}
     * 里「用 fe.getCodes() 而不是 getDefaultMessage()」的同一条理由。
     *
     * <p>需要它的前提是请求拦截器把 code 一起抛出来（{@code err.code}），
     * 这一点已经改了 —— 见 {@code src/api/request.js}。
     *
     * <p>⚠️ 这里的 1007 是个魔法数字。更好的做法是建一个
     * {@code src/constants/resultCode.js} 统一放这些码。
     * 现在只有一个地方用到，先留着字面量 + 这个注释；
     * 等第二处需要时再抽 —— 和 {@code CartQuantityDTO} 注释里
     * 「判断标准是改动频率」是同一条取舍。
     */
    if (e?.code === 1007) {
      usernameInputRef.value?.focus()
      usernameInputRef.value?.select?.()
    }
    // 其余错误的提示已由请求拦截器弹出
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  usernameInputRef.value?.focus()
})
</script>

<template>
  <div class="page-container">
    <el-card class="register-card" shadow="never">
      <div class="head">
        <h2>注册新账号</h2>
        <p>注册后即可下单购物</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="账号" prop="username">
          <el-input
            ref="usernameInputRef"
            v-model="form.username"
            placeholder="4-20 位字母、数字或下划线"
            clearable
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="至少 6 位"
            show-password
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            show-password
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="昵称（选填）" prop="nickname">
          <el-input v-model="form.nickname" placeholder="不填就用账号显示" clearable />
        </el-form-item>

        <el-form-item label="手机号（选填）" prop="phone">
          <el-input v-model="form.phone" placeholder="用于接收订单通知" clearable />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="loading"
          @click="handleSubmit"
        >
          {{ loading ? '注册中...' : '注 册' }}
        </el-button>
      </el-form>

      <div class="foot">
        已有账号？
        <router-link to="/login">去登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.register-card {
  width: 440px;
  margin: 40px auto;
  /* 圆角交给 --el-card-border-radius，理由见 Login.vue 里同名的注释 */
}

.head {
  text-align: center;
  margin-bottom: 20px;
}

.head h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.head p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #909399;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
  letter-spacing: 4px;
  /* ★ 必须手写，theme.css 的 --el-color-primary 够不着 ——
     .submit-btn[data-v-x]（0,2,0）比 .el-button--primary（0,1,0）更具体。
     详见 Login.vue 里同一处的注释。 */
  background-color: var(--jd-red);
  border-color: var(--jd-red);
}

.submit-btn:hover {
  /* 京东的 hover 是【更深】的红 */
  background-color: var(--jd-red-hover);
  border-color: var(--jd-red-hover);
}

.foot {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: #606266;
}

.foot a {
  color: var(--jd-red);
  text-decoration: none;
}

.foot a:hover {
  text-decoration: underline;
}
</style>
