package com.example.mall.common;

/**
 * 订单状态常量。
 *
 * <p>和 {@code ResultCode} 同一个理由：不写魔法数字。
 * {@code order.setStatus(0)} 读代码时得回去查 0 是什么意思，
 * {@code order.setStatus(OrderStatus.PENDING_PAY)} 就不用。
 *
 * <h3>★ 状态的取值定下来之后，就不要再改了</h3>
 *
 * <p>因为状态是<b>会写进数据库</b>的。今天把 0 定义成「待付款」，
 * 明天想改成「已付款」，那么线上所有 {@code status = 0} 的老订单
 * 含义就全变了 —— 而它们看起来完全正常，不会报任何错。
 *
 * <p>所以真实的项目里加状态要<b>往后追加</b>（5、6、7……），
 * 而不是重排已有数字。废弃的状态也不删，留着并在注释里标明
 * 「已废弃」，因为老数据还在用它。
 * <b>能被历史数据引用的编号，就不再是你的了。</b>
 *
 * <h3>状态流转</h3>
 *
 * <pre>
 *   0 待付款 ──付款──> 1 已付款 ──发货──> 2 已发货 ──确认收货──> 3 已完成
 *      │
 *      ├──用户主动取消──> 4 已取消
 *      └──超过支付时限，系统自动取消──> 4 已取消
 * </pre>
 *
 * <p>⚠️ 注意「已取消」<b>只能从「待付款」来</b>（两条边都是从 0 出发的）。
 * 已经付过款的订单不能直接取消，得走退款流程 ——
 * 那是另一条业务线，本项目不实现（见里程碑 9 的说明）。
 *
 * <p><b>★ 两条进入「已取消」的边，走的是同一段代码。</b>
 * 用户主动取消调 {@code OrderServiceImpl.cancel}，
 * 超时自动取消调 {@code cancelTimeoutOrders}，
 * 而后者是逐条复用前者的。所以这里只有一个 {@code CANCELLED}，
 * 没有「用户取消」和「超时取消」两个状态 ——
 * <b>状态的划分标准是「接下来的行为会不会不同」，不是「原因是不是不同」。</b>
 * 这两种取消之后能做和不能做的事完全一样（都不能付款、都归还了库存），
 * 所以它们是同一个状态。真要知道某笔单是怎么取消的，
 * {@code cancel_time - create_time} 和支付时限比一下就清楚了。
 *
 * <p><b>★ 状态流转的每一条边，都必须在 Service 里显式判断，不能靠前端。</b>
 * 因为前端只是个界面，绕过它直接调接口是很容易的事。
 * 「已发货的订单还能不能取消」这类问题，答案必须在服务端。
 *
 * <p>更准确地说：这些边是被<b>条件更新里的 {@code WHERE status = ?}</b>
 * <b>在 SQL 里</b>守住兜住的。Service 里的判断只负责把「为什么不行」
 * 翻译成一句人话，<b>真正的闸门在 SQL 的条件更新里</b>。
 *
 * <p>★★ <b>四条边，四个条件 —— 现在每一条都是齐的</b>（里程碑 10 补齐后）：
 * <pre>
 *   边                    方法                            条件
 *   待付款 → 已付款        OrderMapper.markPaid            WHERE status = 0
 *   待付款 → 已取消        OrderMapper.markCancelled       WHERE status = 0
 *   已付款 → 已发货        OrderAdminMapper.markShipped    WHERE status = 1
 *   已发货 → 已确认收货    OrderMapper.markCompleted       WHERE status = 2
 * </pre>
 * 上面那张流转图在里程碑 9 之前就画全了，但当时只有前两条边有代码 ——
 * {@code SHIPPED} / {@code COMPLETED} 两个常量和它们的文案是一对
 * <b>纯死代码</b>。里程碑 10 加了后两条边，这张图才第一次成真。
 *
 * <p>⚠️ 所以<b>改这张图之前要先确认那四个 {@code WHERE} 也跟着改了</b>。
 * 图只是文档，真正拦住非法迁移的是 SQL ——
 * 两者不一致的时候，图是错的那一份，而它看起来最权威。
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    /** 待付款：订单已生成，还没付钱 */
    public static final int PENDING_PAY = 0;

    /** 已付款：钱收到了，等发货 */
    public static final int PAID = 1;

    /** 已发货：卖家发出了，等买家确认收货 */
    public static final int SHIPPED = 2;

    /** 已完成：买家确认收货，交易结束 */
    public static final int COMPLETED = 3;

    /** 已取消：用户主动取消，<b>或</b>超时未付款被系统自动取消（都只能从待付款来） */
    public static final int CANCELLED = 4;

    /**
     * 把状态码转成中文，给日志用。
     *
     * <p><b>为什么不给前端用？</b>因为前端需要的是「按状态做不同的事」，
     * 那就必须拿到<b>状态码本身</b>去判断，而不是一句中文。
     * 如果前端拿中文来比较（{@code if (statusText === '待付款')}），
     * 那么后端哪天改一个字（「待支付」），前端就静默地失效了 ——
     * 这正是里程碑 7 里 {@code Register.vue} 踩过的坑（用 message 文本判断错误类型）。
     *
     * <p>所以：<b>状态码给前端（用于判断），中文给日志（用于人看），
     * 两者不要混用。</b>
     */
    public static String text(int status) {
        return switch (status) {
            case PENDING_PAY -> "待付款";
            case PAID -> "已付款";
            case SHIPPED -> "已发货";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            default -> "未知状态(" + status + ")";
        };
    }
}
