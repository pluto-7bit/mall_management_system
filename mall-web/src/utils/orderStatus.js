/**
 * 订单状态 / 支付方式的展示字典（管理端）。
 *
 * <h3>★★ 先说清楚：这份文件和 mall-shop 那一份是【故意重复】的</h3>
 *
 * <p>{@code mall-web} 和 {@code mall-shop} 是<b>两个各自独立的 Vite 工程</b>，
 * 各有各的 {@code package.json}、各打各的包，它们之间<b>没有任何代码共享机制</b>
 * （没有 monorepo、没有私有 npm 包）。所以「抽一份公共的」在这里
 * 不是一个选项 —— 那需要先引入一整套工程化设施，
 * 而为了一百来行的展示字典搭那套东西，成本远大于收益。
 *
 * <p>★ 但「重复」这个词在这里必须分类，因为<b>有一类重复是可以接受的，
 * 另一类绝对不能</b>：
 *
 * <pre>
 *   展示文案的重复（可以接受 —— 就是本文件）：
 *     「2 这个数字在界面上显示成『已发货』」
 *     后端根本不管界面显示什么字，所以不存在"两份会不一致"的问题 ——
 *     改文案只需要改前端，后端一个字都不用动。
 *
 *   业务规则的重复（危险，不能接受 —— 本文件【没有】）：
 *     「2 这个状态允许被发货」
 *     这是规则，后端也在判断（`OrderAdminMapper.markShipped` 的
 *     `WHERE status = 1`）。抄到前端就意味着两份，而后端才是权威 ——
 *     前端认为"能发货"但后端拒绝，用户会看到「点得动但点了报错」，
 *     比「点不动」更糟。
 * </pre>
 *
 * <p>所以下面这些函数的职责被严格限制在<b>「把码变好看」</b>：
 * 输入一个状态码，输出一个词、输出一个颜色。
 * <b>它们绝不回答「这个状态能不能发货」</b> ——
 * 那个判断在服务端，前端只负责把服务端算出来的状态显示出来。
 *
 * <p>⚠️ 由此推出一条对订单管理页的直接后果：
 * 那一页上「发货」按钮的显示条件<b>看起来</b>像是在判断规则
 * （{@code row.status === 1} 才显示），但它做的是
 * <b>「已付款的订单才显示这个按钮」，是显示什么，不是允许什么</b>。
 * 真正的闸门是 SQL 里的 {@code WHERE status = 1} ——
 * 就算这里写错了，用户最多看到一个不该出现的按钮，
 * 点了也会拿到 1002。这个区分要在心里划清楚。
 *
 * <h3>为什么后端不直接返回中文？</h3>
 *
 * <p>因为那样前端会很自然地写成 {@code if (order.statusText === '待付款')}，
 * 然后后端某天把文案改成「待支付」，前端就<b>静默地失效</b>了 ——
 * 按钮消失，没有任何报错。里程碑 7 在 {@code Register.vue} 里踩过一次
 * （当时拿错误提示文字判断错误类型），教训是同一个：
 * <b>不要拿给人看的文字去做程序判断。</b>
 *
 * <p>（完整的论证写在 {@code mall-shop/src/utils/orderStatus.js} 里，
 * 两边是同一份道理，这里不重复展开。）
 */

/**
 * 订单状态码。★ 抄自
 * {@code com.example.mall.common.OrderStatus}，对应关系别改错。
 *
 * <p>⚠️ 后端有一条纪律：<b>状态的取值定下来之后就不能再改了</b>
 * （状态写进了数据库，改了就等于把所有历史数据的含义改变）。
 * 所以这个字典<b>只会往后追加</b>，不会重排 —— 前端的对应关系很稳。
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
 * 这意味着后端加了新状态而前端还没跟上 —— 让页面正常显示、
 * 只是那一个标签写得笼统，比整个页面白屏好得多。
 * <b>未知的值要降级，不要崩溃。</b>
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
 * <p>★ 配色按「<b>要不要管理员动手</b>」配，不是按「这个状态好不好」：
 * <pre>
 *   待付款 → info    灰。和后台基本无关 —— 用户还没付钱，管理员无事可做
 *   已付款 → warning 橙。【需要管理员行动】：这一单在等他发货
 *   已发货 → primary 蓝。在途，不需要任何人做事
 *   已完成 → success 绿。终态，好事
 *   已取消 → info    灰。终态，不需要管理员做任何事
 * </pre>
 *
 * <p>⚠️ 这里和 {@code mall-shop} 那份<b>有两个颜色不一样</b>
 * （待付款：前台是 danger 红、后台是 info 灰），这是<b>刻意的</b> ——
 * 同一份数据在两个端上「需要谁行动」的答案本来就不同：
 * 前台待付款是"你该去付钱了"（红），后台待付款是"跟你没关系"（灰）。
 * <b>颜色服务于"看的人此刻该做什么"，所以看的人不同，颜色就该不同。</b>
 * 不要为了"两边一致"去改成一样的 —— 那才是真正的不一致。
 */
export function orderStatusTagType(status) {
  switch (status) {
    case ORDER_STATUS.PENDING_PAY:
      return 'info'
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
 * <p>和状态同理：后端存的是码（{@code ALIPAY}），中文由前端负责。
 * 数据库里存「支付宝」的话，哪天想改成「支付宝支付」，
 * 历史数据就对不上了。
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
 * 管理端状态筛选下拉的选项。
 *
 * <p>★ 和展示字典放在同一个文件里，理由和 {@code mall-shop} 的
 * {@code PAY_METHODS} 一样：让「加一个状态」只需要改一个地方。
 *
 * <p>★ 这里<b>把五个状态全摆上了，包括「已取消」</b> ——
 * 这一点和用户端（{@code mall-shop} 的「我的订单」）不同，
 * 那边刻意<b>没有</b>给「已取消」单独的 Tab，理由写在 Orders.vue 里：
 * 顾客不会专门去找一笔自己取消掉的订单。
 *
 * <p>管理端为什么要给？因为管理员查已取消的订单是<b>有真实用途</b>的：
 * 核对一笔被超时取消的订单释放了多少库存、排查用户投诉
 * 「我的单怎么没了」。这些是顾客不会做、而运营真的会做的事。
 * <b>同一个筛选条件在两个端该不该出现，取决于"谁会来用它做什么"，
 * 而不是"这个状态存不存在"。</b>
 *
 * <p>⚠️ 「全部」的 value 是 {@code null}，必须放在第一个 ——
 * 这样 {@code el-select} 的初始值取 null 时显示的就是「全部状态」。
 * 不要用 -1 之类的哨兵值表示「全部」：哨兵值迟早会和真实取值撞车。
 */
export const ORDER_STATUS_OPTIONS = [
  { value: null, label: '全部状态' },
  { value: ORDER_STATUS.PENDING_PAY, label: '待付款' },
  { value: ORDER_STATUS.PAID, label: '已付款' },
  { value: ORDER_STATUS.SHIPPED, label: '已发货' },
  { value: ORDER_STATUS.COMPLETED, label: '已完成' },
  { value: ORDER_STATUS.CANCELLED, label: '已取消' },
]
