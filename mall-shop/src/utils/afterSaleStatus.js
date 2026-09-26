/**
 * 售后状态 / 售后原因 / 售后类型的展示字典（用户端）。★ 里程碑 17 新增。
 *
 * <h3>★ 这个文件为什么必须存在 —— 和 {@code orderStatus.js} 是同一条理由</h3>
 *
 * <p>后端<b>故意不返回中文</b>：码（{@code status: 0}）用来判断做什么，
 * 中文标签由前端自己的字典管。完整论证写在 {@code orderStatus.js} 的类注释里
 * （含里程碑 7 在 {@code Register.vue} 踩的那次「拿给人看的文字去做程序判断」），
 * 这里不重复展开。
 *
 * <h3>★★ 为什么不和 {@code orderStatus.js} 合并成一个文件</h3>
 *
 * <p>因为它们是<b>两张不同的码表</b>，来自两个不同的 Java 类
 * （{@code OrderStatus} / {@code AfterSaleStatus}），而且它们会<b>同时出现在一个页面上</b>：
 * 订单页的每一行明细上贴着售后状态标签，卡片头上贴着订单状态标签。
 * 合并成一个 {@code status.js} 的后果不是「省了一个文件」，
 * 而是<b>「订单状态」和「售后状态」这两个词在代码里失去边界</b> ——
 * 而它们恰好是本轮最容易搞混的一对（看下面那一节）。
 *
 * <h3>★★ 售后状态是【行级】的，订单状态是【订单级】的 —— 本轮的中心张力</h3>
 *
 * <p>一张「已完成」的订单里，可能<b>只有一件</b>商品退了款。所以：
 * <pre>
 *   订单状态（{@code ORDER_STATUS}）      → 贴在这张订单的卡片上
 *   售后状态（{@code AFTER_SALE_STATUS}） → 贴在<b>那一条明细行</b>上
 * </pre>
 * <b>它们不是同一个东西的两个表示，而是两个层次的事实</b>，
 * 所以千万不要为了「显示得整齐」把两者合成一个标签 ——
 * 那会让一张部分退款的订单看起来像是整单都退完了。
 *
 * <p>（这个不对称在第 17 轮里出现了四次：库存归还的两条路径、
 * {@code orders.status = 5} 的聚合、评价资格、运费的「全退才退」。
 * 每一次的解法都一样：<b>行级事实存在行上，聚合只在唯一一处做。</b>）
 *
 * <h3>★★ 「已退款」在两张表里都是 3 还是都是终态，但【颜色不同】</h3>
 *
 * <p>这是个容易被「统一一下」改坏的地方，提前说清楚：
 * <pre>
 *   售后状态 3 REFUNDED「退款完成」 → success 绿
 *        这一张售后单<b>办成了</b>，对用户来说是件事办完了，好事。
 *   订单状态 5 REFUNDED「已退款」   → info 灰
 *        这一<b>整单</b>结束了、不需要用户做任何事，和「已取消」同类，
 *        没什么可高兴的。
 * </pre>
 * <b>看着像不一致，其实是两个不同的问题：一个是「这次申请的结果」，
 * 一个是「这笔订单的收场」。</b>不要为了看起来整齐把它们改成一样。
 */

/**
 * 售后单状态码。★ 抄自
 * {@code com.example.mall.common.AfterSaleStatus}，对应关系别改错。
 *
 * <p>⚠️ 和 {@code ORDER_STATUS} 同一条纪律：<b>取值定下来就不能再改</b>
 * （已经写进历史数据了）。所以这个字典只会往后追加。
 *
 * <p>★ 六个状态里只有<b>一个会归还库存</b>：{@code REFUNDED}。
 * 这不是巧合，是刻意的 —— 「还库存」这个动作只挂在「状态变成 3」那一条 SQL 上，
 * 所以它天然只可能发生一次。前端这边不需要知道这条规则，
 * 只需要知道<b>只有 {@code REFUNDED} 是「钱已经退了」</b>。
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
 * <p>⚠️ 遇到不认识的状态码返回<b>「未知状态」</b>而不是抛异常 ——
 * 理由同 {@code orderStatusLabel}：让页面正常显示、只是那一个标签写得笼统，
 * 比整个页面白屏好得多。<b>未知的值要降级，不要崩溃。</b>
 */
export function afterSaleStatusLabel(status) {
  switch (status) {
    case AFTER_SALE_STATUS.APPLIED:
      return '待审核'
    case AFTER_SALE_STATUS.WAITING_RETURN:
      return '待寄回'
    case AFTER_SALE_STATUS.WAITING_RECEIVE:
      return '待卖家收货'
    case AFTER_SALE_STATUS.REFUNDED:
      return '退款成功'
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
 * <p>★ 配色按「<b>要不要用户动手</b>」配，和 {@code orderStatusTagType} 同一条原则：
 * <pre>
 *   待审核      → primary 蓝。在途，等对方处理，用户什么都不用做
 *   待寄回      → danger  红。★★ 六个状态里【唯一】需要用户去做点什么的一个
 *                          （把货寄出去），而且有时间压力 —— 拖着不寄，
 *                          退款就一直不到账。红色用在这里是名副其实的
 *   待卖家收货  → primary 蓝。在途，用户能做的都做完了
 *   退款成功    → success 绿。终态，好事
 *   已拒绝      → warning 橙。终态，但★【不是灰的】——
 *                          拒绝会放开这一行的申请资格，用户<b>还能再申请一次</b>
 *                          （换一个原因、或者直接找客服）。有下一步可做 → 橙不灰
 *   已撤销      → info    灰。终态，自己撤的，事情到此为止
 * </pre>
 * <b>颜色传达的是「要不要用户行动」，不是「这个状态好不好」</b>——
 * 所以「已拒绝」比「已撤销」更"亮"，即使被拒绝听起来更糟。
 *
 * <p>⚠️ 取值必须是 EP 认识的：{@code success / warning / info / danger / primary}。
 * 传别的值 EP 不报错，只是标签退化成默认灰色 —— 静默地不对。
 */
export function afterSaleStatusTagType(status) {
  switch (status) {
    case AFTER_SALE_STATUS.APPLIED:
      return 'primary'
    case AFTER_SALE_STATUS.WAITING_RETURN:
      return 'danger'
    case AFTER_SALE_STATUS.WAITING_RECEIVE:
      return 'primary'
    case AFTER_SALE_STATUS.REFUNDED:
      return 'success'
    case AFTER_SALE_STATUS.REJECTED:
      return 'warning'
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
 * <p>★ 用户在下拉框里只能选到这几个值 —— <b>这个数组就是那个下拉框的唯一来源</b>。
 * 为什么要给用户一个下拉框而不是让他写一句话：自由文本第一个月就会出现
 * 「质量」「質量」「qualité」三行，运营永远做不出「按原因退货」的统计。
 * 用户的补充说明另有 {@code description} 字段（可选，255 字），两者都要。
 *
 * <p>⚠️ <b>校验仍然在服务端做</b>（{@code AfterSaleReason.isValid}）——
 * 下拉框管的是「用户能不能选到别的」，管不了「有没有人绕过页面直接调接口」。
 *
 * <p>★ 抄这份字典的退路，和 {@code PAY_METHODS} 那条一样：
 * 万一这里过时了（后端加了新原因而前端没加），后果只是
 * <b>「用户选不到那种原因」</b>，而不是「选了一种却提交失败」。
 * <b>抄错只会少一点功能，不会产生错误的行为。</b>
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
 * <p>⚠️ {@code null} 是<b>正常输入</b>（历史数据、或后端将来放宽了必填），
 * 返回破折号而不是「未知原因」—— 不能对着一件没填的事说它"未知"。
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
 * <p>★★ 这两个值<b>不是展示文案，是规则</b> —— 它们决定售后单走哪条状态机：
 * <pre>
 *   仅退款   →  同意即退款，货还在仓库里，一次结清
 *   退货退款 →  同意只是同意，要等用户寄回、卖家收到，才退款
 * </pre>
 * 所以「这张单能不能选仅退款」<b>不由前端决定</b>：申请页会根据订单状态
 * 只放出一个可选项，而<b>真正拦住非法组合的是服务端</b>。
 * 这里这份字典只负责把码显示成「仅退款」三个字。
 */
export const AFTER_SALE_TYPES = {
  ONLY_REFUND: 1,
  RETURN_REFUND: 2,
}

/**
 * 售后类型码 → 界面文案。
 *
 * <p>⚠️ {@code null} 是正常输入（列表还没加载完），返回破折号。
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
 * 「我的售后」页状态筛选下拉的选项。
 *
 * <p>★★ 和订单页的 {@code TABS} <b>刻意不同</b>：那边只有四个 Tab、
 * 刻意不给「已取消」单独的 Tab（没有人会专门来找一笔自己取消掉的订单）。
 * 这里<b>六个状态一个不缺</b>。
 *
 * <p>为什么这里可以、那边不行？判据是<b>「谁会来用它做什么」</b>：
 * 「被拒绝」「已撤销」对顾客来说是<b>他真正关心的事</b> ——
 * 「我那笔退款到底成没成」是他会专门回来查的问题。
 * 而「我上个月自己取消掉的那笔单」不是。
 * <b>同一个筛选条件该不该出现，取决于它替谁回答了什么问题。</b>
 *
 * <p>⚠️ 「全部」的 value 是 {@code null}，必须放第一个 ——
 * {@code el-select} 初始值取 null 时显示的就是「全部状态」。
 * 不要用 -1 之类的哨兵值：哨兵值迟早会和真实取值撞车。
 *
 * <p>★ {@code sql/test-frontend-format.py} 的规则 6 会检查这个数组的取值集合
 * <b>正好等于</b>后端的六个状态 —— 少一项就红。因为漏一项的后果是
 * 「用户永远筛不出那一种」，而它<b>不报错</b>。
 */
export const AFTER_SALE_STATUS_OPTIONS = [
  { value: null, label: '全部状态' },
  { value: AFTER_SALE_STATUS.APPLIED, label: '待审核' },
  { value: AFTER_SALE_STATUS.WAITING_RETURN, label: '待寄回' },
  { value: AFTER_SALE_STATUS.WAITING_RECEIVE, label: '待卖家收货' },
  { value: AFTER_SALE_STATUS.REFUNDED, label: '退款成功' },
  { value: AFTER_SALE_STATUS.REJECTED, label: '已拒绝' },
  { value: AFTER_SALE_STATUS.CANCELLED, label: '已撤销' },
]

/**
 * 申请售后时可选的类型（带一句说明，让用户知道选哪个）。
 *
 * <p>★ 这里给的是<b>两个都摆上</b>，配一句「什么时候用哪个」的说明；
 * 具体哪几个能选由申请页按订单状态过滤（未发货 → 只能仅退款），
 * 而<b>最终把关的是服务端</b>。前端过滤只是体验优化 ——
 * 让用户看到他能选的，而不是让他选了再被拒。
 */
export const AFTER_SALE_TYPE_CHOICES = [
  {
    value: AFTER_SALE_TYPES.ONLY_REFUND,
    label: '仅退款',
    hint: '不想要了，货还没发出（或不需要寄回）',
  },
  {
    value: AFTER_SALE_TYPES.RETURN_REFUND,
    label: '退货退款',
    hint: '已经收到货，需要把商品寄回给商家',
  },
]
