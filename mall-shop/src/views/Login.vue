<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 用户端登录页。
 *
 * <p>和管理端的登录页主要差在<b>视觉与语气</b>上：
 * 后台是「进入系统」，前台是「欢迎回来」。
 * 这种差别不是装饰，它影响用户对产品的感觉 ——
 * 同一个表单，橙色和蓝色、有没有注册入口，传递的信息完全不同。
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
 * <p>★ 这里必须校验，<b>绝对不能无条件信任 URL 里的 redirect</b>。
 *
 * <p>攻击者可以构造这样一个链接发给用户：
 * <pre>
 *   http://localhost:5174/login?redirect=https://钓鱼网站.com
 * </pre>
 * 用户一看域名是对的，登录之后却被送到了钓鱼网站，
 * 而且很可能会下意识在那里再输一遍密码。
 * 这类攻击叫「开放重定向」（Open Redirect），
 * 是钓鱼攻击里非常常用的一环。
 *
 * <p>所以只接受「本站的相对路径」：必须以单个 {@code /} 开头。
 * 这样 {@code https://evil.com}（含冒号）和 {@code //evil.com}
 * （协议相对 URL，浏览器会当成 https://evil.com）都会被拒绝。
 *
 * <p>⚠️ 顺带一提：管理端的 Login.vue 里有一份<b>一模一样</b>的逻辑。
 * 这个重复是<b>可以接受的</b>，因为两个工程本来就没法共享代码。
 * 但如果你在真实项目里看到同一条安全规则被实现了三遍，
 * 那就要警惕了 —— <b>安全校验的复制粘贴是最危险的一类重复</b>，
 * 因为修漏洞时漏掉一处就等于没修。
 * 那种情况下应该把校验放进一个共享的 npm 包或工具函数里。
 */
function resolveRedirect() {
  const target = route.query.redirect
  if (typeof target !== 'string') return '/'
  if (!target.startsWith('/') || target.startsWith('//')) return '/'
  return target
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    // replace 而不是 push：登录页不该留在浏览器历史里，
    // 否则登录后按「后退」会回到登录页，体验很怪
    router.replace(resolveRedirect())
  } catch {
    // 失败提示已由请求拦截器统一弹出
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 直接挂 ref 到 el-input 上，不用 el-form 的 fields ——
  // 后者在 onMounted 时机不保证已就绪（详见 mall-web/src/views/Login.vue 的注释）
  usernameInputRef.value?.focus()
})
</script>

<template>
  <div class="page-container">
    <el-card class="login-card" shadow="never">
      <div class="head">
        <h2>欢迎回来</h2>
        <p>登录后可以加入购物车、下单</p>
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

      <div class="foot">
        还没有账号？
        <router-link to="/register">立即注册</router-link>
      </div>

      <div class="hint">测试账号：zhangsan / 123456</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-card {
  width: 400px;
  margin: 40px auto;
  /* ★ 这里【故意不写】border-radius，让它用主题里的
     --el-card-border-radius（4px）。
     写了的话 .login-card[data-v-x]（0,2,0）比 .el-card（0,1,0）更具体，
     会一直赢过主题变量 —— 而且改主题的时候完全看不出是这里在挡。
     全项目每个 el-card 同理，见 theme.css 里的说明。 */
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
  /* ★ 这两个属性【必须手写】，不能指望 theme.css 里的 --el-color-primary。
     原因：.submit-btn[data-v-x] 的特异性是 0,2,0，
     而 .el-button--primary 是 0,1,0 —— scoped 样式更具体，
     它一旦写了 background-color，全局变量就再也够不着了。
     症状是「整站都红了，唯独登录和注册这两个按钮还是橙的，hover 也是橙的」。
     换成 var() 之后，以后改品牌色只动 theme.css 一处。 */
  background-color: var(--jd-red);
  border-color: var(--jd-red);
}

.submit-btn:hover {
  /* ★ 京东的 hover 是【更深】一档的红，不是更浅。
     写成 --jd-red 的浅色系（--el-color-primary-light-3）就露馅了 */
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

.hint {
  margin-top: 12px;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
</style>
