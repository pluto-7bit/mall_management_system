<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCartStore } from '@/stores/cart'
import { useCategoryStore } from '@/stores/category'
import { useUserStore } from '@/stores/user'
import { readCategoryId } from '@/utils/query'

/**
 * 用户端的外壳布局：工具条 + 头部 + 分类导航 + 内容区 + 页脚。
 *
 * <h3>★ 和管理端 App.vue 的区别：没有「公开页面不套外壳」的分支</h3>
 *
 * <p>管理端的登录页是整屏的（一个居中的卡片），因为它是「进入后台」
 * 的仪式感动作。而电商的登录/注册页<b>照样带着顶部导航</b> ——
 * 顾客随时可以点「首页」回去继续逛，不被困在登录页里。
 * 这不是偷懒，是电商的通行做法：降低任何让人「必须完成某事」的压迫感。
 *
 * <h3>★ 三行头部的分工（模仿京东）</h3>
 * <pre>
 *   工具条 30px 灰底   「你好，请登录 / 免费注册」＋ 我的订单 / 收货地址
 *   主头部 92px 白底   红 logo ＋ 居中的大搜索框 ＋ 我的购物车（带角标）
 *   导航条 40px        「全部商品分类」＋ 首页 ＋ 各分类，底部一条红线
 * </pre>
 *
 * <p>原来这三块都挤在一行 60px 里（一个文字 logo、一个「首页」链接、
 * 购物车、登录入口）。拆成三行不是为了「更像京东」而已 ——
 * 搜索框和分类导航是电商最重要的两个入口，挤在一行里根本放不小。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()
const categoryStore = useCategoryStore()

/**
 * ★ 只要「有没有登录」变了，就重新拉一次购物车件数。
 *
 * <p>为什么用 {@code watch} 而不是在登录/退出的地方各调一次 refresh()？
 *
 * <p>因为登录状态的变化入口<b>不止一个</b>：登录页、注册页（注册后自动登录）、
 * 退出登录、以及 request.js 拦截器在 401 时清 token（这个是
 * {@code window.location.href} 整页跳转，会重新挂载整个应用）。
 * 在每个入口都记得调一次 refresh，迟早会漏一个。
 *
 * <p><b>监听「状态」而不是「动作」，就不会漏。</b>
 * 状态只有一个，动作有一堆 —— 这是响应式编程最实用的一条经验。
 *
 * <p>{@code immediate: true} 让它在应用启动时也跑一次，
 * 这样刷新页面后角标会立刻恢复，不用等用户先点别的东西。
 */
watch(
  () => userStore.isLoggedIn,
  (loggedIn) => {
    cartStore.refresh(loggedIn)
  },
  { immediate: true },
)

/**
 * 下拉菜单的统一入口。
 *
 * <p>★ 这里必须用一个函数按 command 分派，不能把「退出登录」直接绑到
 * {@code @command} 上。
 *
 * <p>因为 el-dropdown 的 {@code @command} 会在<b>点任意一项</b>时触发，
 * 并把那一项的 command 值传进来。如果菜单里有两个选项，
 * 却只绑了一个「退出登录」的处理函数，那用户点「我的订单」
 * 也会弹出「确定要退出登录吗？」—— 一个相当尴尬的 bug，
 * 而且因为它"看起来能跑"，很容易漏掉。
 */
function handleCommand(command) {
  if (command === 'orders') {
    router.push('/orders')
  } else if (command === 'addresses') {
    router.push('/addresses')
  } else if (command === 'logout') {
    handleLogout()
  }
}

const handleLogout = async () => {
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
  // 退出后回首页 —— 而不是留在当前页。
  // 如果用户当时正在「我的订单」页，退出后那个页面对他已经没有意义了
  router.replace('/')
}

// ---------------------------------------------------------------------------
// 头部搜索框
// ---------------------------------------------------------------------------

/**
 * 头部搜索框的内容。
 *
 * <p>★ 它<b>故意不监听</b> {@code route.query.keyword} 做回填。
 *
 * <p>看着像少了点功能，其实是刻意的：这个输入框是<b>全局入口</b>，
 * 不是首页筛选状态的镜像。如果加了 watcher，用户从搜索结果里点进
 * 一个商品详情页时，URL 上的 keyword 没了，输入框就会<b>被清空</b>——
 * 而用户按浏览器后退回去，还得重新打字。留着内容反而符合直觉：
 * 「我刚才搜的词还在，可以改一改再搜」。
 */
const keywordInput = ref('')

/** 当前是不是首页 —— 决定搜索要不要带上现有的分类筛选 */
const onHome = computed(() => route.path === '/')

/**
 * ★ 提交搜索 = 写 URL。就这一件事。
 *
 * <p>这个函数里没有「搜索结果」，也<b>不存</b>「当前搜的是什么」。
 * 它只负责把 URL 改掉，剩下的事由 Home.vue 的
 * {@code watch(() => route.query)} 接管自动重查。
 *
 * <p>这就是「URL 是唯一真相来源」的好处：写的人和读的人之间
 * 不需要任何直接通信，URL 本身就是那个中介。
 */
function submitHeaderSearch() {
  const kw = keywordInput.value.trim()
  // ★ 在首页搜索时【合并】而不是覆盖现有条件：
  //   用户在「手机数码」分类里搜耳机，期望是「在这个分类里搜」，
  //   而不是把分类悄悄丢掉。
  //   不在首页时 base 是空的 —— 导航到 / 会让 Home 重新取 route.query，
  //   带上商品详情页的 query（比如 ?from=xxx）毫无意义。
  const base = onHome.value ? route.query : {}
  const query = { ...base }
  // ★ pageNum 必须丢掉：结果集整个变了，停在第 5 页毫无意义。
  //   （这一条在分页时代是必须的，改成无限滚动之后更关键 ——
  //     留着它会让新搜索直接加载 5 页数据。）
  delete query.pageNum
  if (kw) {
    query.keyword = kw
  } else {
    // 空搜索 = 清掉关键词，而不是留下一个 ?keyword=
    // 两者的区别：?keyword= 会让 hasFilter 判定为「有筛选」，
    // 「清空筛选」按钮会莫名出现，点它却什么都不会变
    delete query.keyword
  }
  router.push({ path: '/', query })
}

// ---------------------------------------------------------------------------
// 分类导航
// ---------------------------------------------------------------------------

/**
 * 当前高亮的分类 id。和 Home.vue 用的是同一个函数，
 * 所以两边对「选中了哪个分类」的理解必然一致。
 */
const activeCategoryId = computed(() => readCategoryId(route.query))

/**
 * ★ 导航链接的 active 必须【手算】，不能用 router-link 自动加的 class。
 *
 * <p>原因：vue-router 的 active 是<b>按匹配到的 route record 算的，
 * 根本不看 query</b>。而下面这一堆链接的差别【只在 query】：
 * <pre>
 *   to="/"                    ┐
 *   to="/?categoryId=1"       ├─ 全都解析到同一条 record（Home）
 *   to="/?categoryId=2"       ┘
 * </pre>
 *
 * <p>结果是在首页上<b>每一个链接会同时点亮</b>。
 * {@code exact-active-class} 也救不了 —— 它比的还是 path。
 *
 * <p>所以下面所有链接都手写 {@code :class="{ active: ... }"}，
 * 并且 CSS 里<b>绝不能</b>留 {@code .nav-link.router-link-active} 那条规则
 * （原来有一条，删掉了）—— 留着它所有链接还是会一起亮。
 */
function isCategoryActive(id) {
  return onHome.value && activeCategoryId.value === id
}

/** 「全部商品」在首页且没有分类条件时高亮 */
const isAllActive = computed(() => onHome.value && activeCategoryId.value === null)

/**
 * 应用启动时恢复用户信息 + 拉一次分类。
 *
 * <p>store 里的 member 初始是 null，而 token 是从 localStorage 读回来的。
 * 所以刷新页面后的状态是「有 token，但不知道是谁」——
 * 导航栏上的用户名会显示成空。这里用 token 换一次用户信息补上。
 *
 * <p>加 {@code !userStore.member} 的判断是为了避免重复请求。
 *
 * <p>★ 注意这里的失败处理和管理端<b>不一样</b>：
 * 管理端失败了可以不管（反正会被弹去登录页），
 * 但用户端有大量游客场景 —— 如果 token 失效了，
 * 这里吞掉异常，用户会停留在「导航栏显示未登录，但 localStorage 里
 * 还留着一个过期 token」的状态。不过 request.js 的拦截器
 * 会在收到 401 时把 token 清掉，所以这里确实只需要吞掉异常。
 */
onMounted(async () => {
  // 分类导航条要用分类列表。和 Home.vue 的调用是同一个 store 方法，
  // 所以两边最多只会发一个请求（单飞，见 stores/category.js）。
  categoryStore.load()

  if (userStore.isLoggedIn && !userStore.member) {
    try {
      await userStore.loadCurrentMember()
    } catch {
      // 401 已被拦截器处理（清 token + 跳登录页），其他错误也已弹过提示
    }
  }
})
</script>

<template>
  <div class="shop-layout">
    <!-- ===================== 工具条 ===================== -->
    <div class="utility-bar">
      <div class="page-container utility-inner">
        <!--
          左边是一句问候，不是数据。
          京东这里显示「配送至：北京」—— 那需要一份全局的收货地址，
          而现在地址是每个会员自己的（member_address 表），
          游客根本没有。为了凑版式编一个假城市，就是在编数据。
        -->
        <span class="utility-greeting">欢迎来到商城</span>

        <div class="utility-links">
          <!--
            已登录 / 未登录，是两种完全不同的界面。
            这就是为什么登录状态必须放在 Pinia 里：
            它同时被导航栏、商品详情页的「加入购物车」按钮、
            下单页的收货信息等多个地方读取
          -->
          <template v-if="userStore.isLoggedIn">
            <!-- 已登录时「我的订单 / 收货地址」收进下拉菜单里，
                 不再平铺在工具条上 —— 京东也是这么做的，
                 平铺会让工具条在登录后变得更长 -->
            <el-dropdown @command="handleCommand">
              <span class="utility-link">
                <span class="user-name">{{ userStore.displayName || '会员' }}</span>
                <el-icon><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="orders">
                    <el-icon><List /></el-icon>
                    <span>我的订单</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="addresses">
                    <el-icon><Location /></el-icon>
                    <span>收货地址</span>
                  </el-dropdown-item>
                  <el-dropdown-item command="logout" divided>
                    <el-icon><SwitchButton /></el-icon>
                    <span>退出登录</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>

          <template v-else>
            <span class="utility-link">你好，请登录</span>
            <router-link to="/login" class="utility-link accent">登录</router-link>
            <router-link to="/register" class="utility-link accent">免费注册</router-link>
          </template>
        </div>
      </div>
    </div>

    <!-- ===================== 主头部 ===================== -->
    <header class="main-header">
      <div class="page-container header-inner">
        <!--
          品牌 logo 点击回首页。用 router-link 而不是 <a href="/">，
          因为后者会触发整页刷新，SPA 状态全丢
        -->
        <router-link to="/" class="brand">
          <img class="brand-logo" src="/images/logo.svg" alt="" />
          <span class="brand-name">商城</span>
        </router-link>

        <!--
          ★ 搜索框故意用原生 <input> 而不是 el-input。
          el-input 的 append 插槽渲染出来是「灰底圆角按钮」，
          要改成京东那种「方角 + 红色实心按钮」得写 :deep() 去撬
          Element Plus 的内部结构（.el-input-group__append）。
          本项目全库只有 3 处 :deep()，每一处都是不得已。
          而原生 input 没有任何需要撬的东西 —— 它本来就是我们想要的样子。
        -->
        <div class="search-box">
          <input
            v-model="keywordInput"
            class="search-input"
            type="text"
            placeholder="搜索商品名称"
            @keyup.enter="submitHeaderSearch"
          />
          <button class="search-btn" @click="submitHeaderSearch">搜索</button>
        </div>

        <!--
          购物车入口 + 数量角标。
          角标数据来自 cartStore，而不是每个页面各自查一次 ——
          导航栏在每个页面都在，如果各页面自己拉，
          切一次页面就是一次多余的请求
        -->
        <router-link to="/cart" class="cart-link">
          <el-badge
            :value="cartStore.count"
            :hidden="cartStore.count === 0"
            :max="99"
          >
            <el-icon :size="20"><ShoppingCart /></el-icon>
          </el-badge>
          <span>我的购物车</span>
        </router-link>
      </div>
    </header>

    <!-- ===================== 分类导航条 ===================== -->
    <nav class="nav-bar">
      <div class="page-container nav-inner">
        <!--
          「全部商品分类」暂时只是回首页的链接。
          京东这里是一个悬浮的大抽屉（鼠标移上去展开全部二级分类），
          本项目不做 —— 那是这一轮唯一需要新增共享状态的京东特性，
          而且我们只有一级分类，抽屉里展开也是同一份列表。
          将来要做，形状是 el-popover 读 categoryStore.list
          然后 router.push({ path: '/', query: { categoryId } })，
          仍然是同一条写入路径。
        -->
        <router-link
          to="/"
          class="nav-cat"
          :class="{ active: isAllActive }"
        >
          全部商品分类
        </router-link>

        <div class="nav-links">
          <router-link
            to="/"
            class="nav-link"
            :class="{ active: isAllActive }"
          >
            首页
          </router-link>
          <!--
            ★ 注意每个链接都手写 :class，不能用 vue-router 自动加的
              router-link-active —— 这些链接的差别只有 query，
              而 active 是按 route record 算的，会一起点亮。
              详见上面 isCategoryActive 的注释。
          -->
          <router-link
            v-for="c in categoryStore.list"
            :key="c.id"
            :to="{ path: '/', query: { categoryId: c.id } }"
            class="nav-link"
            :class="{ active: isCategoryActive(c.id) }"
          >
            {{ c.name }}
          </router-link>
        </div>
      </div>
    </nav>

    <main class="shop-main">
      <router-view />
    </main>

    <footer class="shop-footer">
      <!--
        ★ 只写这四条 —— 不编造京东页脚那五列链接。
        电商页脚那几列（关于我们 / 联系我们 / 商家入驻 ……）
        在这个项目里全是死链，点进去 404。与其摆一堆点不动的字，
        不如把「服务承诺」写清楚，它是真实成立的产品说明。
      -->
      <div class="page-container">
        <ul class="promise-list">
          <li>正品行货 · 诚信经营</li>
          <li>极速配送 · 次日可达</li>
          <li>无忧退换 · 七天无理由</li>
          <li>专业客服 · 全程护航</li>
        </ul>
        <div class="copyright">
          学习项目 · Spring Boot 3 + Vue 3 前后端分离实战
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.shop-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* ============================ 工具条 ============================ */
.utility-bar {
  background-color: var(--jd-bg);
  border-bottom: 1px solid var(--jd-border);
  /* 12px 是京东那种「密集排版」的来源 —— 显式写在这里，
     而不是去调 body 的 font-size。
     改全局字号会连带缩小所有 el-button / el-input 的文案，
     而 EP 到处继承 font-size，收不回来 */
  font-size: 12px;
  color: #666;
  line-height: 30px;
}

.utility-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.utility-links {
  display: flex;
  align-items: center;
  gap: 12px;
}

.utility-link {
  color: #666;
  text-decoration: none;
  cursor: pointer;
  outline: none;
  transition: color 0.15s;
}

.utility-link:hover {
  color: var(--jd-red);
}

/* 「登录 / 免费注册」用红色，是一行灰字里唯一的强调 */
.utility-link.accent {
  color: var(--jd-red);
}

.user-name {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  vertical-align: middle;
}

/* ============================ 主头部 ============================ */
.main-header {
  background-color: #fff;
  /* ★ 没有 position: sticky。
     三行头部加起来 162px，粘住会吃掉笔记本屏幕四分之一，
     而滚动时最该留在屏幕上的其实是商品而不是导航。
     京东自己的头部也是滚走的。
     z-index 保留 —— 下拉浮层仍然需要一个层叠上下文 */
  position: relative;
  z-index: 100;
}

.header-inner {
  display: flex;
  align-items: center;
  gap: 30px;
  height: 92px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  /* 品牌不该被压缩 —— 窗口变窄时优先让搜索框收缩 */
  flex-shrink: 0;
}

.brand-logo {
  width: 36px;
  height: 36px;
  display: block;
}

.brand-name {
  font-size: 26px;
  font-weight: 700;
  color: var(--jd-red);
  letter-spacing: 1px;
}

/* 搜索框占中间，max-width 限制它在大屏上不要拉得太长 */
.search-box {
  display: flex;
  flex: 1;
  max-width: 560px;
  margin: 0 auto;
}

.search-input {
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 12px;
  font-size: 14px;
  font-family: inherit;
  color: #333;
  background-color: #fff;
  /* 2px 红框是京东搜索框的标志性细节。
     只写 border 不写 outline: none 的话，聚焦时会多出一圈蓝色 outline，
     把红框盖掉一半 */
  border: 2px solid var(--jd-red);
  border-right: none;
  border-radius: 0;
  outline: none;
}

.search-input::placeholder {
  color: #999;
}

.search-btn {
  width: 82px;
  height: 36px;
  flex-shrink: 0;
  border: none;
  background-color: var(--jd-red);
  color: #fff;
  font-size: 15px;
  font-family: inherit;
  letter-spacing: 2px;
  cursor: pointer;
  /* ★ 京东的 hover 是【更深】的红，和 Element Plus「hover 变浅」的习惯相反。
     这个细节比主色本身更能决定「像不像京东」 */
  transition: background-color 0.15s;
}

.search-btn:hover {
  background-color: var(--jd-red-hover);
}

.cart-link {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  height: 36px;
  padding: 0 14px;
  border: 1px solid var(--jd-border);
  background-color: #fff;
  color: var(--jd-red);
  font-size: 14px;
  text-decoration: none;
  transition: border-color 0.15s, color 0.15s;
}

.cart-link:hover {
  border-color: var(--jd-red);
}

/* ============================ 分类导航 ============================ */
.nav-bar {
  background-color: #fff;
  /* 底部那条 2px 红线是京东导航条最显眼的特征 */
  border-bottom: 2px solid var(--jd-red);
}

.nav-inner {
  display: flex;
  align-items: stretch;
  height: 40px;
}

.nav-cat {
  display: flex;
  align-items: center;
  width: 200px;
  flex-shrink: 0;
  padding-left: 16px;
  background-color: var(--jd-red);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  text-decoration: none;
}

.nav-links {
  display: flex;
  align-items: stretch;
  gap: 2px;
  margin-left: 10px;
  /* 分类多的时候宁可横向排不下也不要挤成两行 ——
     两行会把整个头部撑高，而且第二行贴着内容区很难看 */
  overflow: hidden;
}

.nav-link {
  display: flex;
  align-items: center;
  padding: 0 16px;
  color: #333;
  font-size: 15px;
  font-weight: 700;
  text-decoration: none;
  white-space: nowrap;
  border-bottom: 2px solid transparent;
  /* 负的 margin-bottom 让下边框压住 .nav-bar 的那条红线，
     高亮时看起来是「红线加粗」而不是「多出一条线」 */
  margin-bottom: -2px;
  transition: color 0.15s;
}

.nav-link:hover {
  color: var(--jd-red);
}

.nav-link.active {
  color: var(--jd-red);
  border-bottom-color: var(--jd-red);
}

/* ★ 这里【故意没有】 .nav-link.router-link-active 这条规则。
   原来有一条，删掉了 —— vue-router 的 active 是按 route record 算的、
   不看 query，所以所有指向 "/" 的分类链接会一起点亮。
   见 isCategoryActive 的注释。 */

/* ============================ 内容区 / 页脚 ============================ */
.shop-main {
  /* flex: 1 让内容区占满剩余高度，把页脚顶到底部。
     否则内容少的时候页脚会飘在屏幕中间 */
  flex: 1;
  padding: 20px 0 40px;
}

.shop-footer {
  border-top: 1px solid var(--jd-border);
  background-color: #fff;
  padding: 24px 0;
  text-align: center;
}

.promise-list {
  display: flex;
  justify-content: center;
  gap: 40px;
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
  font-size: 14px;
  color: #333;
}

/* 每条承诺前面一个小红方块 —— 比图标轻，也比圆点像电商 */
.promise-list li::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 4px;
  margin-right: 8px;
  vertical-align: middle;
  background-color: var(--jd-red);
}

.copyright {
  font-size: 12px;
  color: #999;
}
</style>
