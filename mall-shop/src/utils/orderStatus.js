/**
 * 订单状态 / 支付方式的展示字典。
 *
 * <h3>★ 为什么这个文件必须存在？</h3>
 *
 * <p>因为后端<b>故意不返回中文状态</b>。这是 {@code OrderVO} 类注释里
 * 专门解释过的决定，理由值得在这里再抄一遍：
 * <pre>
 *   后端给 code（status: 0）  → 前端用它判断「做什么」
 *   前端自己维护标签字典      → { 0: '待付款', 1: '已付款', ... } 用来显示
 * </pre>
 * 如果后端返回 {@code statusText: "待付款"}，前端会很自然地写成
 * {@code if (order.statusText === '待付款')}，
 * 然后后端某天把文案改成「待支付」，前端就<b>静默地失效</b>了 ——
 * 按钮消失，没有任何报错。
 * 里程碑 7 在 {@code Register.vue} 里踩过一次（当时用错误提示文字判断错误类型），
 * 教训是同一个：<b>不要拿给人看的文字去做程序判断。</b>
 *
 * <h3>★★ 这是「展示文案的重复」，不是「业务规则的重复」</h3>
 *
 * <p>这个区别非常关键，它决定了这份映射<b>允不允许存在</b>。
 *
 * <pre>
 *   展示文案的重复（可以接受）：
 *     「0 这个数字在界面上显示成『待付款』」
 *     —— 后端根本不管界面显示什么字，所以不存在「两份不一致」的问题。
 *        改文案只需要改前端，后端一个字都不用动。
 *
 *   业务规则的重复（危险，不能接受）：
 *     「0 这个状态允许被取消」
 *     —— 这是规则，后端也在判断。抄到前端就意味着两份。
 *        而后端才是权威 —— 前端判断"能取消"但后端拒绝，用户会看到
 *        「点得动但点了报错」，比「点不动」更糟。
 * </pre>
 *
 * <p>所以下面这两个函数的职责被严格限制在<b>「把码变好看」</b>：
 * 输入一个状态码，输出一个词、输出一个颜色。
 * <b>它们绝不回答「这个状态能不能支付」「能不能取消」</b> ——
 * 那是服务端的事，前端只负责把服务端算出来的状态显示出来。
 *
 * <p>收银台页（{@code Pay.vue}）里确实有 {@code v-if="status === 0"} 这样的写法，
 * 但它做的是「待付款就显示倒计时」，是<b>显示什么</b>，不是<b>允许什么</b>。
 * 这个区分要在心里划清楚。
 */

/**
 * 订单状态码。★ 抄自
 * {@code com.example.mall.common.OrderStatus}，对应关系别改错。
 *
 * <p>⚠️ 后端的注释里有一条纪律：<b>状态的取值定下来之后就不能再改了</b>
 * （因为状态会写进数据库，改了就等于把所有历史数据的含义改变了）。
 * 所以这个字典<b>只会往后追加</b>，不会重排 —— 前端的对应关系很稳。
 * 这一点比 {@code MAX_QUANTITY_PER_ITEM} 那种"可能被运营改动"的常量安全得多。
 */
export const ORDER_STATUS = {
  PENDING_PAY: 0,
  PAID: 1,
  SHIPPED: 2,
  COMPLETED: 3,
  CANCELLED: 4,
}

/**
 * 状态码 → 界面文案。
 *
 * <p>⚠️ 遇到不认识的状态码时返回<b>「未知状态」</b>而不是抛异常。
 * 因为这意味着后端加了新状态（比如里程碑 10 之后的某一步）
 * 而前端还没跟上 —— 这时候让页面正常显示、只是那一个标签写得笼统，
 * 比整个页面白屏好得多。<b>未知的值要降级，不要崩溃。</b>
 */
export function orderStatusLabel(status) {
  switch (status) {
    case ORDER_STATUS.PENDING_PAY:
      return '待付款'
    case ORDER_STATUS.PAID:
      return '已付款'
    case ORDER_STATUS.SHIPPED:
      return '已发货'
    case ORDER_STATUS.COMPLETED:
      return '已完成'
    case ORDER_STATUS.CANCELLED:
      return '已取消'
    default:
      return '未知状态'
  }
}

/**
 * 状态码 → Element Plus {@code el-tag} 的 {@code type}。
 *
 * <p>取值必须是 EP 认识的：{@code success / warning / info / danger / primary}。
 * 传别的值 EP 不报错，只是标签会退化成默认灰色 —— 静默地不对。
 *
 * <p>配色不是随手选的，是按「用户此刻的心情」配的：
 * <pre>
 *   待付款 → danger  红。要用户去做点什么（付钱），而且有时间压力
 *   已付款 → warning 橙。事情在推进，但还没完 —— 提醒用户还要等发货
 *   已发货 → primary 蓝。正常途中，不需要用户做任何事
 *   已完成 → success 绿。终态，好事
 *   已取消 → info    灰。终态，但没什么可高兴的，也不需要用户做什么
 * </pre>
 * <b>颜色传达的是「要不要用户行动」，不是「这个状态好不好」。</b>
 * 所以「已取消」是灰色而不是红色 —— 它不是错误，只是一件已经结束的事。
 */
export function orderStatusTagType(status) {
  switch (status) {
    case ORDER_STATUS.PENDING_PAY:
      return 'danger'
    case ORDER_STATUS.PAID:
      return 'warning'
    case ORDER_STATUS.SHIPPED:
      return 'primary'
    case ORDER_STATUS.COMPLETED:
      return 'success'
    case ORDER_STATUS.CANCELLED:
      return 'info'
    default:
      return 'info'
  }
}

/**
 * 支付方式码 → 界面文案。★ 抄自
 * {@code com.example.mall.common.PayMethod}。
 *
 * <p>和状态一样：后端存的是码（{@code ALIPAY}），中文由前端负责。
 * 数据库里的 {@code pay_method} 列存「支付宝」的话，
 * 哪天想改成「支付宝支付」，历史数据就对不上了 ——
 * 所以后端坚持存码，展示的事留给这里。
 */
export function payMethodLabel(method) {
  switch (method) {
    case 'ALIPAY':
      return '支付宝'
    case 'WECHAT':
      return '微信支付'
    case 'BANK':
      return '银行卡'
    default:
      // 可能是 null（未支付），也可能是后端加了新的支付方式
      return method || '—'
  }
}

/**
 * 收银台上可选的支付方式。
 *
 * <p>★ 这里的三个值和 {@code PayMethod.ALL} 是对应的，
 * 但<b>它们不是「规则」，只是「界面上摆哪几个按钮」</b> ——
 * 真正判断合不合法的仍然是后端的 {@code PayMethod.isValid()}。
 *
 * <p>所以即使这份清单过时了（后端加了一种支付方式而前端没加），
 * 后果只是「用户在前端选不到那种方式」，而不是「选了一种却失败」。
 * 这是抄常量时想要的退路方向：<b>抄错只会少一点功能，不会产生错误的行为。</b>
 *
 * <p>把文案和图标也放在这里，是为了让「加一种支付方式」只需要改这一个数组。
 */
export const PAY_METHODS = [
  { value: 'ALIPAY', label: '支付宝', icon: 'Wallet' },
  { value: 'WECHAT', label: '微信支付', icon: 'ChatDotRound' },
  { value: 'BANK', label: '银行卡', icon: 'CreditCard' },
]
