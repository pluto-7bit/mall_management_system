/**
 * URL query 的解析工具。
 *
 * <h3>★ 为什么这个函数必须【只有一份】？</h3>
 *
 * <p>它原来写在 {@code Home.vue} 里（只被那一处用到，放那儿是对的）。
 * 但现在 {@code App.vue} 的导航条也要回答「当前哪个分类是选中的」——
 * 于是同一个问题有了两个实现者。
 *
 * <p>如果两边各写一份、或者一边写 {@code Number()} 一边写
 * {@code parseInt()}，就会出现这种局面：
 *
 * <pre>
 *   URL 是 ?categoryId=abc
 *   App     认为「没有选中任何分类」  → 一个都不高亮
 *   Home    认为「categoryId 无效，显示全部」 → 显示全部商品
 *   详情页面包屑 认为「这个商品没有分类」 → 那一级渲染成纯文字
 * </pre>
 *
 * <p>看起来对得上，但换成 {@code ?categoryId=1.5} 就分叉了：
 * {@code Number("1.5")} 是 1.5（不是正整数，被拒），
 * {@code parseInt("1.5")} 是 1（被接受）——
 * 结果是<b>导航点亮了「手机数码」，网格却按 categoryId=1 查了手机数码</b>？
 * 还是没查？取决于哪一边赢。这种「两处判断不一致」的 bug
 * <b>不会报错</b>，只会偶尔对不上，是排查成本最高的一类。
 *
 * <p>所以：<b>同一个语义判断只能有一个实现</b>。
 * 这是把它提上来的唯一理由，不是为了「复用」。
 */

/**
 * 把 URL 里的字符串转成正整数。
 *
 * <p>为什么需要这个？因为 URL 参数<b>永远是字符串</b>，
 * 而且用户可以随手写成 {@code ?categoryId=abc} 或 {@code ?categoryId=-5}。
 * 不转换的话，这些脏值会被原样发给后端 ——
 * 后端确实会兜住（返回 HTTP 400 或走默认值），
 * 但用户会看到一条莫名其妙的报错提示。
 *
 * <p>在前端就把脏值消化成默认值，用户体验更好。
 * <b>这不是安全措施</b>（用户可以绕过页面直接调接口），
 * 只是体验优化。后端那一层永远不能省。
 *
 * <p>★ 顺带说一句：这个函数原来还负责解析 {@code pageNum} / {@code pageSize}，
 * 首页改成无限滚动之后那两个参数已经不在 URL 里了（见 Home.vue 开头的说明），
 * 所以现在的唯一调用者是下面的 {@code readCategoryId}。
 * 没有再抽一层「反正只有一处用」的理由 —— 留着它是因为
 * 「把 URL 字符串转成合法正整数」这件事本身就该有一个名字。
 *
 * @param value    URL 里的原始值（字符串，可能是 undefined）
 * @param fallback 解析失败时的返回值
 */
export function toPositiveInt(value, fallback) {
  const n = Number.parseInt(value, 10)
  return Number.isFinite(n) && n > 0 ? n : fallback
}

/**
 * 读「当前选中的分类 id」。
 *
 * <p>App 的导航条和 Home 的网格都调这一个函数，
 * 所以两边对「选中了哪个分类」的理解<b>必然一致</b>。
 * 见文件开头那段。
 *
 * @returns {number|null} 有效时是正整数，没选或值非法时是 null
 */
export function readCategoryId(query) {
  return toPositiveInt(query?.categoryId, null)
}

/**
 * 合法的订单状态码。★ 抄自 {@code com.example.mall.common.OrderStatus}。
 *
 * <p>放在这里而不是从 {@code utils/orderStatus.js} import：
 * 那个文件的 {@code ORDER_STATUS} 是「状态码 → 文案」字典的一部分，
 * 而这里要的是「哪些值是合法的」。两者的<b>内容</b>必须一致
 * （都是后端那个枚举的抄本），但它们的<b>用途</b>不同 ——
 * 这里用于<b>校验</b>，那里用于<b>显示</b>。
 * 做校验时依赖一个显示字典，是让「改文案」和「改规则」纠缠在一起。
 *
 * <p>⚠️ ★ 里程碑 17 追加了 {@code 5 = REFUNDED}（已退款）。
 * <b>这一行曾经漏掉过它，而那不是一个小疏忽</b> ——
 * 漏掉的后果是 {@code /orders?status=5} 被静默地消化成「全部」：
 * 校验列表里没有 5，于是 {@code readOrderStatus} 返回 null，
 * 页面显示「全部订单」，<b>不报错、不警告</b>。
 * （这一轮所有「同一份码表的手抄本」都栽在同一个坑上，
 * 所以现在有一条断言专门量它们两两一致 ——
 * 见 {@code sql/test-frontend-format.py} 的规则 6。）
 *
 * <p>★ 上面说「两个文件的<b>内容</b>必须一致」，而 {@code Orders.vue} 的
 * {@code TABS} 刻意只是它的一个<b>子集</b>（没有「已取消」「已退款」两个 Tab）。
 * <b>「哪些值合法」和「给用户摆哪几个 Tab」是两个问题，
 * 后者是前者的子集 —— 这个区别在 TABS 那段注释里有完整论证。</b>
 * 不要把两份清单改成一样：那要么让 URL 拒绝一个合法状态，
 * 要么逼着页面多出两个没人点的 Tab。
 */
const VALID_ORDER_STATUS = [0, 1, 2, 3, 4, 5]

/**
 * 读「当前选中的订单状态」。
 *
 * <h3>★★ 为什么不复用上面的 {@code toPositiveInt}？</h3>
 *
 * <p>因为<b>这是两个不同的语义，不是同一个语义的两种宽松度</b>。
 *
 * <p>{@code toPositiveInt} 的判据是 {@code n > 0}。对「分类 id / 商品 id」
 * 来说这个判据是<b>正确</b>的 —— id 从 1 开始，不可能为 0。
 * 但订单状态里 <b>{@code 0} 是一个真实且重要的取值：「待付款」</b>。
 *
 * <p>用它来读状态会出什么事？看「我的订单」页的 Tab：
 * <pre>
 *   URL 是 /orders?status=0
 *   toPositiveInt("0", null)  →  n > 0 不成立  →  返回 null
 *   页面认为「没有筛选」  →  「全部」Tab 高亮
 *   ⇒ 【「待付款」这个 Tab 永远选不中】，而且不报任何错
 * </pre>
 * 而「待付款」恰恰是用户最常看的那个 Tab —— 他就是来付钱的。
 *
 * <h3>★ 所以正确的做法是【并排加一个函数】，而不是把 toPositiveInt 放宽</h3>
 *
 * <p>把 {@code n > 0} 改成 {@code n >= 0} 看起来更省事，但那会让
 * <b>「分类 id = 0」「商品 id = 0」从「非法，退回默认」变成「合法，去查一下」</b>——
 * 于是 {@code /?categoryId=0} 会发出一个注定查不到东西的请求，
 * 而以前它会被干净地消化成「没选分类」。<b>修一个 bug 引入另一个 bug，
 * 而且新的那个更难发现</b>（0 号分类不存在，所以表现只是"列表空了"）。
 *
 * <p>这个文件开头那段讲的是「同一个语义判断只能有一个实现」。
 * 这里是它的<b>反向应用</b>：<b>当两个判断长得像但语义不同时，
 * 应该是两个函数，而不是把其中一个改得去迁就另一个。</b>
 *
 * <p>⚠️ 注释里写清这层关系是必需的 —— 否则下一个人看到两个长得几乎
 * 一样的函数，第一反应就是「这俩重复了，合并掉」。
 * 而他合并的方向大概率是把 {@code > 0} 放宽，正好制造上面那个 bug。
 *
 * @param {object} query 路由的 query 对象
 * @returns {number|null} 合法状态码（含 0）；没传或值非法时是 null（= 全部）
 */
export function readOrderStatus(query) {
  const raw = query?.status
  // ⚠️ 这里不能用 toPositiveInt，理由见上。
  //   先 Number() 而不是 parseInt()：parseInt("1.5") 会得到 1（被接受），
  //   而 Number("1.5") 是 1.5（被 includes 拒绝）——
  //   和文件开头那个 "1.5" 的例子是同一个判断。
  const n = Number(raw)
  if (raw === undefined || raw === null || raw === '') {
    return null
  }
  return VALID_ORDER_STATUS.includes(n) ? n : null
}
