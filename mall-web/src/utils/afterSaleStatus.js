/**
 * 售后状态 / 售后原因 / 售后类型的展示字典（管理端）。★ 里程碑 17 新增。
 *
 * <h3>★★ 这份文件和 mall-shop 那一份是【故意重复】的</h3>
 *
 * <p>{@code mall-web} 和 {@code mall-shop} 是两个各自独立的 Vite 工程，
 * 之间没有任何代码共享机制。完整的论证写在
 * {@code mall-web/src/utils/orderStatus.js} 的类注释里，这里不重复展开。
 *
 * <p>★ 只要重复，就必须分类 —— <b>展示文案的重复可以接受
 * （「3 在后台显示成『退款完成』」），业务规则的重复绝对不能
 * （「3 这个状态说明钱已经退了」）。</b>下面这些函数只做前者：
 * 把码变好看。<b>它们绝不回答「这张单能不能同意」</b> ——
 * 那个判断在服务端。
 *
 * <h3>★ 两端的文案有两处刻意不同</h3>
 *
 * <p>因为「看的人此刻该做什么」不同：
 * <pre>
 *   状态 2 用户端「待卖家收货」  管理端「待卖家收货」  —— 文案相同
 *   状态 3 用户端「退款成功」    管理端「退款完成」
 *   状态 1 用户端「待寄回」      管理端「待买家寄回」
 * </pre>
 * 用户端的「待寄回」是在对<b>用户</b>说话（你要去寄）；管理端的「待买家寄回」
 * 是管理员在<b>看别人</b>（买家还没寄）。<b>同一件事，两个视角，
 * 所以文案本来就该不一样。</b>不要为了"两边一致"改成一样的 ——
 * 那才是真正的不一致。颜色更是如此（见 {@code afterSaleStatusTagType}）。
 */

/**
 * 售后单状态码。★ 抄自
 * {@code com.example.mall.common.AfterSaleStatus}，对应关系别改错。
 *
 * <p>⚠️ 取值定下来就不能再改（已经写进历史数据了），只会往后追加。
 *
 * <p>★★ {@code status = 3} 是<b>跨模块</b>的字面量：它不只是「这张售后单办完了」，
 * 还是「订单级终态」和「这条明细不能评价」两处判断的依据。
 * {@code AfterSaleStatus} 的类注释里有一份完整清单，重编号时最容易漏的就是它们。
 */
export const AFTER_SALE_STATUS = {
  APPLIED: 0,
  WAITING_RETURN: 1,
  WAITING_RECEIVE: 2,
  REFUNDED: 3,
  REJECTED: 4,
  CANCELLED: 5,
}

/**
 * 售后状态码 → 界面文案。
 *
 * <p>⚠️ 不认识的状态码返回「未知状态」而不是抛异常 ——
 * 后端加了新状态而前端没跟上时，让页面正常显示比白屏好。
 * <b>未知的值要降级，不要崩溃。</b>
 */
export function afterSaleStatusLabel(status) {
  switch (status) {
    case AFTER_SALE_STATUS.APPLIED:
      return '待审核'
    case AFTER_SALE_STATUS.WAITING_RETURN:
      return '待买家寄回'
    case AFTER_SALE_STATUS.WAITING_RECEIVE:
      return '待卖家收货'
    case AFTER_SALE_STATUS.REFUNDED:
      return '退款完成'
    case AFTER_SALE_STATUS.REJECTED:
      return '已拒绝'
    case AFTER_SALE_STATUS.CANCELLED:
      return '已撤销'
    default:
      return '未知状态'
  }
}

/**
 * 售后状态码 → Element Plus {@code el-tag} 的 {@code type}。
 *
 * <p>★ 配色按「<b>要不要管理员动手</b>」配，和 {@code orderStatusTagType} 同一条原则。
 * 这一点在管理端尤其重要，因为<b>这个列表就是管理员的工作队列</b>：
 * <pre>
 *   待审核      → warning 橙。【要管理员行动】：同意还是拒绝，这一单在等他的判断。
 *                          这是整个列表里唯一的「新活儿」
 *   待买家寄回  → info    灰。管理员无事可做 —— 等买家把货寄出来。
 *                          不是"不重要"，是"现在轮不到你"
 *   待卖家收货  → warning 橙。【要管理员行动】：货到了，确认收到才能退款。
 *                          ★ 这一步卡住 = 用户的钱卡住，所以它和「待审核」同色
 *   退款完成    → success 绿。终态，办成了
 *   已拒绝      → info    灰。终态，不需要管理员再做什么
 *   已撤销      → info    灰。终态，用户自己撤的
 * </pre>
 * <b>注意「待审核」和「待卖家收货」都是橙的、而「待买家寄回」是灰的</b>——
 * 三者的"紧急程度"看起来差不多，但<b>颜色回答的不是紧急，是"该不该你动手"</b>。
 *
 * <p>⚠️ 这里和 {@code mall-shop} 那份<b>有两个颜色不一样</b>
 * （待审核：前台蓝、后台橙；退款完成 vs 退款成功的 success 倒是相同的）。
 * 这是刻意的：前台「待审核」意味着用户<b>什么都不用做</b>（蓝），
 * 后台「待审核」意味着管理员<b>必须做点什么</b>（橙）。
 * <b>同一份数据在两端「需要谁行动」的答案本来就不同。</b>
 */
export function afterSaleStatusTagType(status) {
  switch (status) {
    case AFTER_SALE_STATUS.APPLIED:
      return 'warning'
    case AFTER_SALE_STATUS.WAITING_RETURN:
      return 'info'
    case AFTER_SALE_STATUS.WAITING_RECEIVE:
      return 'warning'
    case AFTER_SALE_STATUS.REFUNDED:
      return 'success'
    case AFTER_SALE_STATUS.REJECTED:
      return 'info'
    case AFTER_SALE_STATUS.CANCELLED:
      return 'info'
    default:
      return 'info'
  }
}

/**
 * 售后申请原因码。★ 抄自
 * {@code com.example.mall.common.AfterSaleReason}。
 *
 * <p>★ 后端那个类里也有一份 {@code text(int)}，但它<b>只进日志</b>，
 * 管理端页面上的文案由这份字典负责。两处不一致的后果是
 * 「日志和页面读起来不一样」，<b>不会产生任何错误行为</b> ——
 * 这正是「展示文案的重复可以接受」那条分界线所在。
 *
 * <p>★ 这里的退路方向和 {@code PAY_METHODS} 一致：万一过时了，
 * 后果只是「原因那一格显示成『未知原因』」而不是「显示出错的原因」。
 */
export const AFTER_SALE_REASONS = {
  DISLIKE: 1,
  QUALITY: 2,
  NOT_AS_DESCRIBED: 3,
  MISSING: 4,
  LOGISTICS: 5,
  OTHER: 6,
}

/**
 * 售后原因码 → 界面文案。
 *
 * <p>⚠️ {@code null} 是正常输入，返回破折号而不是「未知原因」——
 * 不能对着一件没填的事说它"未知"。
 */
export function afterSaleReasonLabel(reason) {
  switch (reason) {
    case AFTER_SALE_REASONS.DISLIKE:
      return '不喜欢/不想要'
    case AFTER_SALE_REASONS.QUALITY:
      return '商品质量问题'
    case AFTER_SALE_REASONS.NOT_AS_DESCRIBED:
      return '商品与描述不符'
    case AFTER_SALE_REASONS.MISSING:
      return '少件/漏发'
    case AFTER_SALE_REASONS.LOGISTICS:
      return '物流问题'
    case AFTER_SALE_REASONS.OTHER:
      return '其他'
    default:
      // 可能是 null（没填），也可能是后端加了新原因
      return reason ? '未知原因' : '—'
  }
}

/**
 * 售后类型码。★ 抄自
 * {@code com.example.mall.common.AfterSaleType}。
 *
 * <p>★★ 这两个值决定<b>状态机走哪条边</b>，所以后端把它写进了
 * {@code markRefunded} 的 {@code WHERE} 里（{@code AND type = 1}）——
 * 少写那个条件，对一张「退货退款」的单点「同意」就会<b>货没回来钱先退了</b>。
 *
 * <p>★ 它对管理端的直接影响：<b>这两类单的「同意」按钮文字不该一样</b>。
 * 仅退款 → 「同意并退款」；退货退款 → 「同意退货」。
 * 点完的结果也完全不同（一个直接退款，一个只是等买家寄回）。
 * 把它们都叫「同意」，管理员就分不清自己刚刚做了什么。
 */
export const AFTER_SALE_TYPES = {
  ONLY_REFUND: 1,
  RETURN_REFUND: 2,
}

/**
 * 售后类型码 → 界面文案。
 *
 * <p>⚠️ {@code null} 是正常输入，返回破折号。
 */
export function afterSaleTypeLabel(type) {
  switch (type) {
    case AFTER_SALE_TYPES.ONLY_REFUND:
      return '仅退款'
    case AFTER_SALE_TYPES.RETURN_REFUND:
      return '退货退款'
    default:
      return type ? '未知类型' : '—'
  }
}

/**
 * 售后管理列表状态筛选下拉的选项。
 *
 * <p>★ 和订单管理页的 {@code ORDER_STATUS_OPTIONS} 同一个形状：
 * <b>六个状态一个不缺</b>，因为管理员查每一种都有真实用途 ——
 * 查「待审核」是干活、查「已拒绝」是复核用户的申诉、
 * 查「退款完成」是对账。
 *
 * <p>⚠️ 「全部」的 value 是 {@code null}，必须放第一个 ——
 * {@code el-select} 初始值取 null 时显示的就是「全部状态」。
 * 不要用 -1 之类的哨兵值：哨兵值迟早会和真实取值撞车。
 *
 * <p>★ {@code sql/test-frontend-format.py} 的规则 6 会检查这个数组的取值集合
 * <b>正好等于</b>后端的六个状态 —— 少一项就红。因为漏一项的后果是
 * 「运营永远筛不出那一种」，而它<b>页面不报错、代码不报错、控制台干净</b>。
 */
export const AFTER_SALE_STATUS_OPTIONS = [
  { value: null, label: '全部状态' },
  { value: AFTER_SALE_STATUS.APPLIED, label: '待审核' },
  { value: AFTER_SALE_STATUS.WAITING_RETURN, label: '待买家寄回' },
  { value: AFTER_SALE_STATUS.WAITING_RECEIVE, label: '待卖家收货' },
  { value: AFTER_SALE_STATUS.REFUNDED, label: '退款完成' },
  { value: AFTER_SALE_STATUS.REJECTED, label: '已拒绝' },
  { value: AFTER_SALE_STATUS.CANCELLED, label: '已撤销' },
]
