<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 根组件 —— 整个后台的外壳布局。
 *
 * <h3>两种渲染模式</h3>
 * <pre>
 *   登录页（meta.public = true）→ 整屏显示，不套外壳
 *   其他所有页面               → 顶部导航 + 内容区
 * </pre>
 *
 * 靠 meta.public 区分，而不是靠「路径是不是等于 /login」硬编码。
 * 好处是以后再加「注册页」「忘记密码页」这类公开页面时，
 * 只要在路由表里标上 meta.public，这个文件一行都不用改。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 当前页面是不是公开页面（登录页），是的话不套外壳 */
const isPublicPage = computed(() => route.meta?.public === true)

/**
 * 当前高亮的菜单项。
 *
 * el-menu 需要知道哪个菜单是「当前选中」的。用路由的 path 来判断最准确 ——
 * 注意这里用 route.path 而不是 route.name：
 * 因为菜单项的 index 就是路径，两者能直接对上，不用再维护一份映射关系。
 */
const activeMenu = computed(() => route.path)

/**
 * 处理退出登录。
 *
 * 整个过程没有调用任何接口 —— JWT 是无状态的，
 * 服务端没有「登录状态」可以清除，把本地 token 删掉就完事了。
 * 详见 stores/user.js 里 logout() 的注释。
 */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户点了取消
  }

  userStore.logout()
  ElMessage.success('已退出登录')
  // replace 而不是 push：退出后不该能按「后退」回到刚才的页面
  router.replace('/login')
}

/**
 * 应用启动时恢复用户信息。
 *
 * ★ 为什么需要这一步？
 *
 * store 里的 admin 初始是 null，而 token 是从 localStorage 读回来的。
 * 所以刷新页面后的状态是「有 token，但不知道是谁」——
 * 导航栏上的用户名会显示成空。
 *
 * 这里用 token 换一次用户信息补上。
 * 顺带也起到了「验证 token 是否还有效」的作用：
 * 如果 token 已过期，这个请求会返回 401，
 * 由请求拦截器自动清 token 并跳登录页。
 *
 * 加 `!userStore.admin` 的判断是为了避免重复请求 ——
 * 组件在同一个会话里可能被重新挂载，但用户信息已经有了就没必要再问。
 */
onMounted(async () => {
  if (userStore.isLoggedIn && !userStore.admin) {
    try {
      await userStore.loadCurrentAdmin()
    } catch {
      // 失败不用管：401 会被拦截器处理成跳登录页，
      // 其他错误（比如网络抖动）拦截器也已经弹过提示了
    }
  }
})
</script>

<template>
  <!--
    公开页面（登录页）：整屏渲染，不套外壳
    注意两个分支各有一个 <router-view>，这是允许的 ——
    同一时刻只会渲染其中一个
  -->
  <router-view v-if="isPublicPage" />

  <el-container v-else class="app-layout">
    <el-header class="app-header">
      <div class="brand">商城管理系统</div>

      <el-menu
        :default-active="activeMenu"
        mode="horizontal"
        router
        class="app-menu"
        :ellipsis="false"
      >
        <!--
          给 el-menu 加 router 属性后，点击菜单项会自动做路由跳转，
          不用自己写 @select 去 router.push。
          菜单项的 index 就是跳转的目标路径
        -->
        <el-menu-item index="/home">首页</el-menu-item>
        <el-menu-item index="/product">商品管理</el-menu-item>
        <el-menu-item index="/category">分类管理</el-menu-item>
        <!--
          ★ 里程碑 10 加的。⚠️ index 的 "/order" 必须和
            router/index.js 里那条路由的 path 逐字相同 ——
            它同时承担两个职责：点击时跳去哪个路径、以及
            上面 activeMenu（= route.path）比对上之后高亮哪一个。
            写错一个字母就是「点了 404 + 菜单永远不高亮」。
        -->
        <el-menu-item index="/order">订单管理</el-menu-item>
        <!--
          ★ 里程碑 17 加的。⚠️ 同样的硬要求：
            index 的 "/after-sale" 必须和 router/index.js 里那条 path 逐字相同。
            ★ 它是【连字符】写法，不要"顺手"改成 /afterSale ——
              两个文件里都得一起改，而漏改一个的症状
              就是「点了 404 + 菜单永远不高亮」。
          ★ 放在订单管理正下方，因为售后单属于订单 ——
            但它们是两条不同的链路（售后有自己的状态机和管理员的动作），
            所以是【两个菜单】，不是订单页里的一个 Tab。
        -->
        <el-menu-item index="/after-sale">售后管理</el-menu-item>
        <!--
          ★ 里程碑 12 加的。⚠️ 同样的硬要求：
            index 的 "/review" 必须和 router/index.js 里那条 path 逐字相同。
            （菜单是硬编码的这一件事，见上面那段注释 ——
             「为什么不用 routes 循环渲染菜单」是一个合理的疑问，
              但那样做要在路由上再加一层「哪几条该出现在菜单里」的标记，
              而本项目只有 5 条，多一层机制不划算。）
        -->
        <el-menu-item index="/review">商品评价</el-menu-item>
      </el-menu>

      <!--
        右侧的用户区。
        用 el-dropdown 把「退出登录」收进下拉菜单里，
        是因为以后还会有「修改密码」「个人资料」等入口，
        平铺在导航栏上会越来越挤
      -->
      <el-dropdown class="user-area" @command="handleLogout">
        <span class="user-trigger">
          <el-icon><UserFilled /></el-icon>
          <span class="user-name">{{ userStore.displayName || '未登录' }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>

        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">
              <el-icon><SwitchButton /></el-icon>
              <span>退出登录</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>

    <el-main class="app-main">
      <!-- 路由匹配到的页面组件渲染在这里 -->
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 32px;
  background-color: #fff;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 24px;
}

.brand {
  font-size: 18px;
  font-weight: 600;
  color: #409eff;
  letter-spacing: 1px;
  white-space: nowrap;
}

/* 让菜单占满剩余宽度，并把底部默认的边框去掉（外层 header 已经有边框了） */
.app-menu {
  flex: 1;
  border-bottom: none;
}

.user-area {
  flex-shrink: 0;
}

/* el-dropdown 默认渲染成 inline 元素，用 flex 让图标和文字对齐 */
.user-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #606266;
  font-size: 14px;
  outline: none;
}

.user-trigger:hover {
  color: #409eff;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-main {
  background-color: #f5f7fa;
  padding: 16px;
}
</style>
