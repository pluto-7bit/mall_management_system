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
 *      │                │                │                   │
 *      ├──用户主动取消──> 4 已取消        │                   │
 *      └──超时自动取消──> 4 已取消        │                   │
 *                       │                │                   │
 *                       └────────────────┴───────────────────┘
 *                                        │
 *                          全部明细都售后退款完成（T8）
 *                                        ▼
 *                                   5 已退款
 * </pre>
 *
 * <p>⚠️ 注意「已取消」<b>只能从「待付款」来</b>（两条边都是从 0 出发的）。
 * 已经付过款的订单不能直接取消，得走退款流程。
 *
 * <p>★ <b>里程碑 18 补充：「2 已发货 → 3 已完成」这条边【有两个行动者】</b> ——
 * 买家点「确认收货」，<b>或者</b>管理员在物流轨迹里录一条「已签收」。
 * 上面这张图只画了一条箭头，是因为<b>边只有一条</b>：
 * 它的条件是同一个 {@code status = 2}。行动者有两个，见下面那张边表。
 *
 * <p>★ <b>里程碑 17 起「退款流程」就是「已退款」这一条边</b> ——
 * 上面那句「那是另一条业务线，本项目不实现（见里程碑 9 的说明）」
 * 在本轮兑现了。里程碑 9 的那句注释可以从这里划掉了。
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
 * <p>★★ <b>五条边，五个条件 —— 现在每一条都是齐的</b>（里程碑 17 补齐后）：
 * <pre>
 *   边                    方法                                      条件
 *   待付款 → 已付款        OrderMapper.markPaid                WHERE status = 0
 *   待付款 → 已取消        OrderMapper.markCancelled           WHERE status = 0
 *   已付款 → 已发货        OrderAdminMapper.markShipped        WHERE status = 1
 *   已发货 → 已完成        OrderMapper.markCompleted           WHERE status = 2   ← 买家确认收货
 *   已发货 → 已完成        OrderAdminMapper.markCompletedByNo  WHERE status = 2   ← ★ 里程碑 18：录「已签收」
 *   * → 已退款             售后 T8                               WHERE status IN (1,2,3)
 *                                                                AND 明细全部有已退款售后单
 * </pre>
 *
 * <p>★★ <b>里程碑 18：一条边第一次有了【两个行动者、两条 SQL】。</b>
 * 第 4 行和第 5 行是<b>同一条边</b>（同一个 {@code status = 2} 闸门、
 * 同一个 {@code SET status = 3, complete_time = NOW()}），
 * 差别只在「谁在写」：买家自己（身份来自 JWT，写进 {@code WHERE member_id}）
 * 和管理员（操作别人的订单，没有 {@code memberId} 可写）。
 *
 * <p><b>为什么不合并成一条 SQL：</b>用户端那条带 {@code member_id}，
 * 管理员那条不能带 —— 硬传一个「订单的会员 id」等于<b>把会员身份从订单里
 * 反推出来再当成鉴权条件</b>，那正是 {@code OrderAdminMapper} 类注释
 * 说明过的那个例外（管理端靠 {@code AdminAuthInterceptor} 拦路径，
 * 不靠 {@code WHERE} 里的会员条件）。
 *
 * <p>★ <b>而这个形状项目里早就有先例，就在「已取消」那条边上</b>：
 * {@code OrderMapper.markCancelled}（用户主动取消）和
 * {@code OrderTimeoutMapper}（定时任务超时取消）也是同一条边、两个行动者、两条 SQL。
 * 里程碑 9 立的规矩照样适用：<b>两条 SQL 都必须带 {@code status = ?}</b>。
 *
 * <p>⚠️ <b>所以改「已完成」这条边时，两条都要改</b> ——
 * 只找到用户端那条是这个文件存在的主要理由。
 *
 * <p>上面那张流转图在里程碑 9 之前就画全了，但当时只有前两条边有代码 ——
 * {@code SHIPPED} / {@code COMPLETED} 两个常量和它们的文案是一对
 * <b>纯死代码</b>。里程碑 10 加了后两条边，这张图才第一次成真。
 *
 * <p>⚠️ 所以<b>改这张图之前要先确认那几条 {@code WHERE} 也跟着改了</b>。
 * 图只是文档，真正拦住非法迁移的是 SQL ——
 * 两者不一致的时候，图是错的那一份，而它看起来最权威。
 *
 * <h3>★★★ 里程碑 17：「已退款」这一条边和上面四条有两个根本差别</h3>
 *
 * <p><b>① 上面的边由「人做了什么」触发，这一条由「事实成立了」触发。</b>
 * 其他的边都能顺手点出来（付款、发货、收货），而「已退款」没有按钮 ——
 * 它是售后模块算出来的结论。
 *
 * <p><b>② 上面的条件是一个 {@code status = ?}，这一条是一个 NOT EXISTS 子查询。</b>
 * 因为触发它的条件（「是不是全部明细都退完了」）是<b>行级事实的聚合</b> ——
 * 而本轮的中心张力就是这个：「按行的售后」对「按订单的状态」。
 *
 * <p>★★ <b>为什么必须有它（而不是让这种订单停在「已付款」）</b>：
 * {@code markShipped} 的条件是 {@code WHERE status = 1}。
 * 一张全部明细都退完款的订单如果还停在 1，
 * <b>管理员点一下「发货」就把已经退过款的东西发出去了 —— 钱货两空，
 * 而且没有任何一层会报错。</b>
 * 所以这一条边<b>同时是 {@code markShipped} 和 {@code markCompleted} 的闸门</b>。
 * 它是本轮里「多一个状态值」赚到位置的唯一理由，而这个理由足够硬。
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
     * 已退款：这一笔订单的<b>全部明细</b>都已经售后退款完成。★ 里程碑 17 新增。
     *
     * <p><b>它是一个终态，而且它是唯一一个「由事实算出来」而不是
     * 「由人的动作点出来」的终态</b> —— 见类注释里那两条根本差别。
     *
     * <h4>★ 怎样才算「全部明细都退款完成」</h4>
     *
     * <p>每一条 {@code order_item} 都至少有一张 {@code status = 3}
     * （退款完成）的售后单。<b>「至少一张」而不是「恰好一张」</b>：
     * 被拒一次、再申请一次、再成功，是很正常的历史，
     * 而那时候这一行确实退过款了。
     *
     * <h4>★★ 它是怎么被写进去的：唯一一处、单调、可重算</h4>
     *
     * <p>只有一个写入者 —— {@code AfterSaleMapper.markOrderRefundedIfAllRefunded}
     * （售后 T8），在「退款成功」的那个事务里、还完库存之后被调用。
     * 那个 UPDATE 的 WHERE 是
     * <pre>
     *   o.status IN (1, 2, 3)     -- 只从「还在流程里」的状态推到终态
     *   AND NOT EXISTS (还有哪条明细没有已退款的售后单)
     * </pre>
     *
     * <p>★ 为什么「每次重算」不违反「不加汇总列」那条铁律：
     * <b>漂移的定义是「两个写入者给出不同的答案」。</b>
     * 这里只有一个写入者、一个方向 —— 售后状态只会往 3 走、不会回头，
     * 所以「全退完了」一旦成立就永久成立，重复执行任意次结果相同。
     * <b>它可以被重算，因为它不会抖动。</b>
     * （对比 {@code product.stock} 那种汇总：它有无数个写入者，所以必然漂移。）
     *
     * <h4>★ 为什么不用一个 {@code refunded TINYINT} 布尔列</h4>
     *
     * <p>那会多出一个可能和 {@code status} 矛盾的字段（「已退款」在这个
     * 状态机里本来就是互斥终态）。同理也不加
     * {@code refunded_item_count} / {@code all_refunded} —— 计数器必然漂移
     * （里程碑 16 实测过：{@code product.stock} = 29 而 {@code SUM(sku.stock)} = 30）。
     */
    public static final int REFUNDED = 5;

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
            case REFUNDED -> "已退款";
            default -> "未知状态(" + status + ")";
        };
    }
}
