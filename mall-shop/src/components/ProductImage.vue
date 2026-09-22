<script setup>
import { computed, ref, watch } from 'vue'

/**
 * 商品图片。带「加载失败退回占位图」的兜底。
 *
 * <h3>★ 这个组件存在的【主要理由】不是复用，是 @error</h3>
 *
 * <p>一开始三个地方各写了一份一模一样的占位逻辑
 * （{@code Home.vue} / {@code Cart.vue} / {@code ProductDetail.vue}
 * 各有一个 {@code PLACEHOLDER_IMG} 或 {@code coverOf}），
 * 删掉重复的两份确实更干净 —— 但那是<b>顺带的好处</b>。
 *
 * <p>（这里原本写着那三处的行号。删掉它们的同时行号就失效了，
 * 所以改成只提文件名 —— 记行号的注释会在下一次改动时变成谎话。）
 *
 * <p>真正的理由是：{@code src} <b>永远是一个「可能会失效的地址」</b>，
 * 而 {@code <img>} 拿到一个打不开的地址时<b>什么都不说</b> ——
 * 它只会露出浏览器那个「碎图」图标。
 *
 * <p>而那个图标会让<b>整页</b>看起来是坏的：用户不会想「这张图挂了」，
 * 用户会想「这网站坏了」。页面上只要有一个碎图，可信度就没了。
 *
 * <p>有了 {@code @error}，最差也只是退回一张灰色的「暂无图片」，
 * 页面永远是完整的。这是给生产环境用的安全网。
 *
 * <h3>★ 里程碑 11 之后，「地址会失效」这件事的理由变强了，而不是变弱了</h3>
 *
 * <p>这段注释原来写的是「{@code cover} 是运营在管理后台手填的一个字符串
 * （{@code ProductForm.vue} 里就是个普通文本框，没有任何校验）」。
 * 那个说法现在<b>只对了一半</b>：里程碑 11 之后，封面图和图集都可以
 * 从上传按钮来，地址是服务端生成的，形状是可控的。
 *
 * <p>但<b>论证一个字都没变，反而多了一层</b>：
 * <pre>
 *   上传来的图 —— 文件在磁盘上，可能被手工删掉、可能因为换了启动目录
 *                （mall.upload.dir 是相对路径）而找不到，见下
 *   手填的 cover —— 仍然可以是任意外链、老路径，本来就是「填错的概率很大」
 *   图集里的每一张 —— 和上面两类一样是「可能会挂的 URL」
 * </pre>
 * 所以从「一个可能挂掉的地址」变成「一堆可能挂掉的地址」——
 * {@code @error} 这条兜底比之前更需要。
 *
 * <p>⚠️ 「{@code /uploads/**} 取不到文件时前端会收到一段 HTML」也值得一提：
 * 两个前端的 vite proxy 如果没配 {@code /uploads}，
 * Vite 的 SPA fallback 会返回 <b>200 + text/html</b>，
 * {@code <img>} 解码失败 → 触发 {@code @error} → 这里安静地退回占位图。
 * <b>没有 404、没有报错、控制台干干净净</b>，
 * 排查的时候会一直去怀疑后端有没有存下文件。
 * 这条已经写进两个 {@code vite.config.js} 的注释里了。
 *
 * <h3>★ 根元素必须还是 {@code <img>}</h3>
 *
 * <p>几个调用点都是用 CSS <b>直接选中 {@code img}</b> 标签的（★ 每次都回来补一行）：
 * <pre>
 *   .product-cover img   { width: 100%; height: 100%; object-fit: cover; }   Home
 *   .row-cover img       { … }                                              Cart
 *   .detail-cover img    { 380×380 }                                        ProductDetail 里程碑 11
 *   .thumb-item img      { 60×60 }                                          ProductDetail 里程碑 11
 *   .review-images img   { 小缩略图 }                                        ProductDetail 里程碑 12
 * </pre>
 * ★ <b>这份清单从今天起有【五条】了，而它每加一条，就多一次
 * 「这个组件的渲染结果被 CSS 摆布」的机会。</b>
 * 注意最后两条是<b>同一个页面</b>（商品详情页）里的 ——
 * 一个页面里同一个组件被三种尺寸使用，这本身就是「选择器作用范围」
 * 这件事开始吃紧的信号。
 * <b>什么时候该停止这种「各自写 CSS」的做法</b>？见下面里程碑 11 那一段的推论。
 *
 * <p>所以「没有图的时候改渲染一个 {@code <div>}」是<b>不行的</b>：
 * 那几条规则会静默失效（选择器匹配不到任何元素，不报错），
 * 占位块的高度塌成 0，商品卡片集体变成一条线。
 * 这就是「抽了个组件，结果三个地方的样式全坏了」的经典翻车方式。
 *
 * <p>正确做法是<b>永远只渲染一个 {@code <img>}</b>，
 * 把 {@code src} 换成一张内联的 data-URI 占位图。
 * 元素类型不变，那几条 CSS 就一行都不用动。
 *
 * <h4>★ 里程碑 11 的第四次：详情页的缩略图条</h4>
 *
 * <p>{@code ProductDetail.vue} 加了「主图 + 缩略图条」之后，
 * 那个页面里会<b>同时存在两类尺寸完全不同的本组件实例</b>：
 * 一张主图（380×380）和一排缩略图（约 60×60）。
 *
 * <p>⚠️ 这是一个新的坑，值得记下来：{@code .detail-cover img} 是
 * <b>后代选择器</b>，它会命中里面<b>每一个</b> {@code <img>}。
 * 如果缩略图条不小心放进了 {@code .detail-cover} 里面，
 * 每张缩略图都会变成 380×380 并被裁切 —— 整条缩略图变成一坨大图。
 *
 * <p>解法是<b>把主图和缩略图分开成两个容器</b>，
 * 让「380×380」那条规则只作用在主图的容器上，
 * 缩略图另写自己的尺寸（见 {@code ProductDetail.vue} 的 {@code .detail-gallery} /
 * {@code .thumb-strip}）。这次没有踩坑，但它离踩到只有一步：
 * <b>凡是给这个组件写「直接选中 img」的 CSS，就必须先确认
 * 那个选择器的作用范围里到底有几个本组件。</b>
 */
const props = defineProps({
  // 空串和 null 都退回占位图 —— 后端字段为空时这两种都可能出现
  // （Jackson 配了 non_null，字段为 null 时【整个 key 都不在】，
  //   所以调用点传进来的通常是 undefined，这里一起兜住）
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  // 占位图里「暂无图片」的字号。调用点按容器大小传：
  // 首页卡片 16、购物车缩略图 12、详情页大图 18。
  // 不传也能用，只是字号不一定合适。
  size: { type: Number, default: 14 },
})

/**
 * ★ 记的是【失败的 src 是哪一个】，不是一个 failed 布尔值。
 *
 * <p>为什么不能用布尔值：同一个 {@code <img>} 在列表里会被复用 ——
 * 从商品 A 翻到商品 B 时，Vue 可能复用同一个组件实例、只改 {@code src}。
 * 如果 A 加载失败过、把 {@code failed = true} 留下了，
 * 那么 B 的图<b>永远不会尝试加载</b>，直接显示占位图。
 *
 * <p>存 URL 就能表达「这个地址失败过，换个地址就重新试」。
 *
 * <p>⚠️ 光存 URL 还不够，还必须监听 {@code src} 变化并清空 ——
 * 见下面那个 {@code watch}。这是「状态跟着组件实例走、没跟着数据走」
 * 的一类 bug，在列表页极其常见，而且看起来像「这张图坏了」，
 * 排查的时候会一直去查图片文件本身。
 */
const failedSrc = ref('')

watch(
  () => props.src,
  () => {
    failedSrc.value = ''
  },
)

const showFallback = computed(() => !props.src || failedSrc.value === props.src)

/**
 * 占位图用内联 data-URI，不引一个 /placeholder.png 文件。
 *
 * <p>理由和原来四个地方各写一份时是一样的：少一个静态文件、少一次请求。
 * 更要紧的是，<b>用一个文件做兜底是自我递归的</b>——
 * 万一那个占位图文件本身 404 了（打包漏了、路径写错了），
 * 兜底就没了兜底，页面上又出现碎图。
 * data-URI 没有「加载失败」这个状态。
 *
 * <p>⚠️ data-URI 里的 SVG 必须 {@code encodeURIComponent} 转义，
 * 否则 {@code #}（颜色值）会被当成 URL 的 fragment 分隔符，
 * 后面的内容全部丢掉 —— 表现为占位图变成一片空白或纯黑。
 * 这个坑原代码已经踩过并写在注释里，这里保持一致。
 */
const placeholder = computed(
  () =>
    'data:image/svg+xml;charset=utf-8,' +
    encodeURIComponent(
      `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="300">
         <rect width="100%" height="100%" fill="#f4f4f4"/>
         <text x="50%" y="50%" font-size="${props.size}" fill="#ccc"
               text-anchor="middle" dominant-baseline="middle">暂无图片</text>
       </svg>`,
    ),
)
</script>

<template>
  <!--
    ★ @error 是上面那一大段注释的全部落点。
      :alt 给一个默认值而不是空串：空 alt 会让图片在无障碍朗读里
      【完全消失】，而「商品图挂了」恰恰是用户需要被告知的信息。
  -->
  <img
    class="product-image"
    :src="showFallback ? placeholder : src"
    :alt="alt || '商品图片'"
    @error="failedSrc = src"
  />
</template>
