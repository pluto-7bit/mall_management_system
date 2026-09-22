<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 登录页。
 *
 * 这个页面和别的页面有个结构上的区别：<b>它不套 App.vue 的外壳</b>
 * （没有顶部导航栏），是整屏显示的。判断逻辑在 App.vue 里。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const usernameInputRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/**
 * 登录成功后要跳回哪个页面。
 *
 * 正常情况下是从 route.query.redirect 取 —— 用户访问 /product 时
 * 被路由守卫踢到登录页，守卫会把 /product 记在 query 里，
 * 登录成功后跳回去，用户感觉不到自己「离开过」。
 *
 * ★ 但是【绝对不能无条件信任这个参数】。
 *
 * 它是从 URL 来的，攻击者可以构造这样一个链接发给用户：
 *   http://localhost:5173/login?redirect=https://钓鱼网站.com
 * 用户一看域名是对的，登录之后却被送到了钓鱼网站，
 * 而且很可能下意识在那里再输一遍密码。
 * 这类攻击叫「开放重定向」（Open Redirect）。
 *
 * 所以只接受「本站的相对路径」：必须以单个 / 开头。
 * 这样 https://evil.com（有冒号）和 //evil.com（协议相对 URL，
 * 浏览器会当成 https://evil.com）都会被拒绝。
 */
function resolveRedirect() {
  const target = route.query.redirect
  if (typeof target !== 'string') return '/home'
  // 必须是以 / 开头，但不能是 // 开头（那是协议相对 URL，会跳到外站）
  if (!target.startsWith('/') || target.startsWith('//')) return '/home'
  return target
}

async function handleSubmit() {
  // validate() 校验不通过时会 reject，必须 catch，
  // 否则控制台会出现未捕获的 Promise 异常
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    // 用 replace 而不是 push：登录页不该留在浏览器历史里。
    // 否则用户登录后按「后退」会回到登录页，体验很怪
    router.replace(resolveRedirect())
  } catch {
    // 失败提示已经由请求拦截器统一弹出（「账号或密码错误」）。
    // 这里只需要保证按钮的 loading 被关掉、页面留在原处
  } finally {
    loading.value = false
  }
}

/**
 * 页面打开时把焦点放到账号输入框上。
 *
 * 小细节，但登录页一进来就能直接打字，手感差别很明显。
 *
 * ★ 直接给 el-input 挂 ref，而不是用 el-form 的 fields[0]。
 *   el-form 确实暴露了 fields，但它是在子组件注册后才填上的，
 *   在 onMounted 这个时机不保证已经就绪 —— 靠它会时灵时不灵。
 *   el-input 自己暴露的 focus() 是稳定的，一挂载就能用。
 *
 *   这里不用 nextTick：模板 ref 在 onMounted 执行前就已经赋值了。
 */
onMounted(() => {
  usernameInputRef.value?.focus()
})
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="brand">
        <h1>商城管理系统</h1>
        <p>后台管理端</p>
      </div>

      <!--
        @submit.prevent 有两个作用：
          1. 阻止表单默认的提交行为（那会导致整页刷新）
          2. 配合 @keyup.enter 让用户按回车就能登录
        label-position="top" 让标签显示在输入框上方，
        比左侧标签更适合窄卡片布局
      -->
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
            placeholder="请输入账号"
            clearable
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <!--
            show-password 会给输入框加一个「眼睛」图标，点一下切换明文。
            加它不是为了方便，而是因为密码输错的最大原因是「看不见打错了」，
            能看一眼比反复重试有效率得多
          -->
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="loading"
          @click="handleSubmit"
        >
          {{ loading ? '登录中...' : '登 录' }}
        </el-button>
      </el-form>

      <!--
        把测试账号写在页面上，纯粹是因为这是学习项目 ——
        真实系统里这么做等于把一半的钥匙贴在门上。
        留着它是为了让你不用翻文档就能登录进来
      -->
      <div class="hint">测试账号：admin / 123456</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 渐变背景：比纯色更有层次，成本也就一行 CSS */
  background: linear-gradient(135deg, #e0eafc 0%, #cfdef3 100%);
}

.login-card {
  width: 400px;
  padding: 8px 12px;
  border-radius: 10px;
}

.brand {
  text-align: center;
  margin-bottom: 24px;
}

.brand h1 {
  margin: 0;
  font-size: 22px;
  color: #303133;
  letter-spacing: 2px;
}

.brand p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #909399;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
  letter-spacing: 4px;
}

.hint {
  margin-top: 18px;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
