/**
 * 物流轨迹节点状态的展示字典。★ 里程碑 18 新增。
 *
 * 和 orderStatus.js / afterSaleStatus.js 同一个形状、同一个理由：
 * 码是业务规则（后端说了算），文案是展示（前端自己拿），
 * 两者不要混 —— 后端返回展示文案就等于把展示逻辑拽到服务端，
 * 两边各一份且必然不一致。
 *
 * <h3>★ 这个文件里只有【展示】，没有判断</h3>
 *
 * 唯一一处「判断」是 logisticsStatusTagType 的分支 ——
 * 而它判断的也是「画成什么颜色」，不是「该不该做某件事」。
 * ★ 特别地：「已签收会把订单推到已完成」这件事【一个字都不在这里】——
 * 那是后端 LogisticsServiceImpl.addTrace 的规则，
 * 前端写一份等于给它造了第二个定义者（而且它一定会在某次改动后分岔）。
 * 这里只负责把后端算好的结果显示出来。
 */

/**
 * 节点状态码。取值和 mall-server 的 LogisticsStatus.java 一一对应。
 *
 * ⚠️ 加/改码之前先读 LogisticsStatus 的类注释：
 * 那些数字会写进数据库，重排会让线上老节点静默改变含义。
 * ★ 改完这里要同步改 mall-shop/src/utils/logisticsStatus.js（另一个工程，另抄一份），
 * 以及 mall-server 的 LogisticsStatus.java —— 三处的一致性由
 * sql/test-frontend-format.py 的规则 5 双向守着。
 */
export const LOGISTICS_STATUS = {
  PICKED_UP: 1,
  IN_TRANSIT: 2,
  DELIVERING: 3,
  SIGNED: 4,
  EXCEPTION: 5,
}

/**
 * 节点：快递公司收到了包裹。
 *
 * ⚠️ 看不清时返回「未知状态」而不是抛异常 ——
 * 那意味着后端加了新码而前端没跟上，让那一个标签笼统一点，
 * 比整个物流时间线白屏好得多。同 orderStatusLabel。
 */
export function logisticsStatusLabel(status) {
  switch (status) {
    case LOGISTICS_STATUS.PICKED_UP:
      return '已揽收'
    case LOGISTICS_STATUS.IN_TRANSIT:
      return '运输中'
    case LOGISTICS_STATUS.DELIVERING:
      return '派送中'
    case LOGISTICS_STATUS.SIGNED:
      return '已签收'
    case LOGISTICS_STATUS.EXCEPTION:
      return '异常'
    default:
      return '未知状态'
  }
}

/**
 * 节点状态 → el-tag 的 type，用来画颜色。
 *
 * ★ 分支是显式 switch，不是「按数字大小分级」——
 * 理由同后端 LogisticsStatus 的类注释：
 * EXCEPTION = 5 恰好证伪「越大越接近终态」那套
 * （异常不是终态，快件还能继续派送）。
 *
 * ★ 已签收是唯一一个 success —— 它和订单的「已完成」是同一件事的两面。
 * 颜色上对齐这一点，用户扫一眼时间线就知道到底送到没有。
 */
export function logisticsStatusTagType(status) {
  switch (status) {
    case LOGISTICS_STATUS.PICKED_UP:
      return 'info'
    case LOGISTICS_STATUS.IN_TRANSIT:
      return 'primary'
    case LOGISTICS_STATUS.DELIVERING:
      return 'warning'
    case LOGISTICS_STATUS.SIGNED:
      return 'success'
    case LOGISTICS_STATUS.EXCEPTION:
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 管理端「新增节点」下拉的选项。
 *
 * ⚠️ 这里【没有】「全部 / 不限」那一项 —— 那些是筛选下拉才有的，
 * 而这个下拉是「必须选一个」，加一个空值项等于允许提交空状态。
 * （对比 ORDER_STATUS_OPTIONS 的第一项 { value: null }，那个是筛选。）
 *
 * ★★ 少一项的后果特别安静：那个状态【永远录不进去】——
 * 管理员在下拉里找不到它，而页面、接口、控制台没有一层会报错。
 * sql/test-frontend-format.py 的规则 6 用 must_equal=True 钉着这一份，
 * 所以它必须和后端码表逐项相等。
 */
export const LOGISTICS_STATUS_OPTIONS = [
  { value: LOGISTICS_STATUS.PICKED_UP, label: '已揽收' },
  { value: LOGISTICS_STATUS.IN_TRANSIT, label: '运输中' },
  { value: LOGISTICS_STATUS.DELIVERING, label: '派送中' },
  { value: LOGISTICS_STATUS.SIGNED, label: '已签收' },
  { value: LOGISTICS_STATUS.EXCEPTION, label: '异常' },
]
