package com.example.mall.common;

/**
 * 售后单状态常量。★ 里程碑 17 新增。
 *
 * <p>和 {@code OrderStatus} 同一个形状：{@code public static final int} +
 * 一个只给日志用的 {@code text(int)}。不写魔法数字的理由见那个类。
 *
 * <h3>★ 状态的流转（这张图就是文档，真正的闸门在 SQL 的 WHERE 里）</h3>
 *
 * <pre>
 *                       ┌── 拒绝(T4) ──────────────┐
 *                       │                          ▼
 *   0 待审核 ──同意(仅退款, T2)──────────────────► 3 退款完成
 *      │  │                                         ▲
 *      │  └──同意(退货退款, T3)──► 1 待买家寄回      │
 *      │                            │              │
 *      │                            │ 买家填寄回(T5)│
 *      │                            ▼              │
 *      │                          2 待卖家收货     │
 *      │                            │              │
 *      │                            └─确认收到(T6)─┘
 *      │
 *      ├── 撤销(T7) ──► 5 已撤销
 *      │
 *      └── 拒绝(T4) ──► 4 已拒绝（★ 从 0 或 1 都能拒）
 * </pre>
 *
 * <p>⚠️ <b>改这张图之前要先确认那几条 WHERE 也跟着改了</b>。
 * 图只是文档，真正拦住非法迁移的是 {@code AfterSaleMapper.xml} 里的条件更新 ——
 * 两者不一致的时候，图是错的那一份，而它看起来最权威。
 * （这句话是从 {@code OrderStatus} 的类注释里抄来的，因为它是同一个毛病。）
 *
 * <h3>★ 为什么不写 {@code isClosed(s) { return s >= 3; }}</h3>
 *
 * <p>因为那是「按数字大小当语义」。现在看着没问题（3/4/5 确实是终态），
 * 但<b>明天加一个 {@code 6 = 退款失败}</code>（非终态，要人工介入重试）
 * 这行代码就静默崩了</b> —— 它会把一个还在流转的状态判成已关闭，
 * 于是「已关闭才释放 {@code active_token}」那条规则跟着错，
 * 用户再也申请不了售后，而且不报错。
 *
 * <p>所以判断「是不是终态」只走下面那个显式 {@code switch}（{@link #isClosed(int)}），
 * <b>加状态的时候编译器会逼着你回来补一支</b>（枚举式的 switch 没有 default
 * 就会编译不过 —— 这里为了容错留了 default，代价是要靠人来核对，
 * 所以这段注释就是那个「人」）。
 *
 * <h3>★★ 跨模块的字面量清单：{@code status = 3} 出现在哪几处</h3>
 *
 * <p>「已退款」不只是这一个状态机里的事，它还是<b>另外两处判断的依据</b>。
 * 重编号（或者说，将来如果要插入一个新的终态编号）时最容易漏的就是它们 ——
 * 因为它们不在这个文件里，甚至不在这个模块里：
 *
 * <table border="1">
 *   <caption>{@code status = 3} 的全部出现位置</caption>
 *   <tr><th>位置</th><th>在问什么</th></tr>
 *   <tr><td>{@code AfterSaleMapper.markRefunded}（T2/T6）</td>
 *       <td>抢一条边到 3。★ 同时把 {@code active_token} 放掉</td></tr>
 *   <tr><td>{@code AfterSaleMapper.markOrderRefundedIfAllRefunded}（T8）</td>
 *       <td>「还有没有哪条明细没退完」—— <b>订单终态的唯一写入者</b></td></tr>
 *   <tr><td>{@code ProductReviewMapper.selectOrderItemForReview}（§7 评价资格）</td>
 *       <td>这条明细退过款没有 → 退过就不许评价</td></tr>
 *   <tr><td>{@code OrderItemMapper} 的两条查询（明细行的售后标记）</td>
 *       <td>同一件事的<b>第二份实现</b>，给订单页那一行显示「已退款」用</td></tr>
 *   <tr><td>{@code sql/test-after-sale.py} 的各条断言</td>
 *       <td>多个方向的不变量检查</td></tr>
 * </table>
 *
 * <p><b>★ 第 3 条和第 4 条是同一个事实的两份实现</b>（「这条 order_item
 * 有没有一张已退款的售后单」）。这是本项目的常规取舍：两个不同的查询
 * 返回两种不同的 VO，共用一段 SQL 片段的代价比各写一遍大。
 * 代价是<b>它们可能分岔</b>，所以配了一条断言把两边的答案对着比 ——
 * 见 {@code sql/test-after-sale.py} 的 I 组。
 */
public final class AfterSaleStatus {

    private AfterSaleStatus() {
    }

    /** 待审核：买家提交了申请，等管理员处理。★ 进行中 */
    public static final int APPLIED = 0;

    /** 待买家寄回：管理员同意了退货退款，等买家把货寄出来。★ 进行中 */
    public static final int WAITING_RETURN = 1;

    /** 待卖家收货：买家填了寄回单号，等管理员确认收到。★ 进行中 */
    public static final int WAITING_RECEIVE = 2;

    /** 退款完成：钱退了。★★ 终态，而且是<b>唯一</b>一个会归还库存的状态 */
    public static final int REFUNDED = 3;

    /** 已拒绝：管理员驳回了申请。★ 终态 */
    public static final int REJECTED = 4;

    /** 已撤销：买家在审核前自己撤回了。★ 终态 */
    public static final int CANCELLED = 5;

    /**
     * 这个状态是不是终态（不走回头路）。
     *
     * <p>★ 显式 switch，理由见类注释 —— <b>绝不写 {@code status >= 3}</b>。
     *
     * <p>它同时定义了另一件事：<b>终态 = 这一行的 {@code active_token}
     * 已经被放开（≠ 0）</b>。也就是说，
     * <pre>
     *   isClosed(status) == false  ⟺  active_token == 0
     * </pre>
     * 这两个说法在本项目里是同一件事的<b>两个表示</b>——
     * 数据库没有部分唯一索引，所以「进行中」必须编码成一个能被唯一索引
     * 看见的值（见 {@code migration-17-after-sale.sql} 里那段完整论证）。
     * 按判据 ①，配了两条断言守着它：
     * <pre>
     *   SELECT COUNT(*) FROM after_sale WHERE status IN (0,1,2) AND active_token &lt;&gt; 0;  -- 必须 0
     *   SELECT COUNT(*) FROM after_sale WHERE status IN (3,4,5) AND active_token = 0;   -- 必须 0
     * </pre>
     * ⚠️ 这两条 SQL 里的 {@code (0,1,2)} / {@code (3,4,5)} 是上面这个 switch 的
     * <b>手抄版</b>，加状态时要一起改。这是本轮唯一一处「同一事实两个表示」的
     * 连带代价 —— 它值得被明说，因为它没有编译期保护。
     */
    public static boolean isClosed(int status) {
        return switch (status) {
            case APPLIED, WAITING_RETURN, WAITING_RECEIVE -> false;
            case REFUNDED, REJECTED, CANCELLED -> true;
            // ★ 未知状态当成「未关闭」是刻意的降级方向：
            //   判成未关闭，最坏后果是「这一行暂时申请不了第二次售后」（用户多等一次人工处理）；
            //   判成已关闭，最坏后果是「同一行上出现两张进行中的单，库存被还两次」。
            //   **降级要降在「用户还能继续用」的那一侧** —— 但这里两边都不是好结果，
            //   所以选「不制造超卖」的那一侧。
            default -> false;
        };
    }

    /**
     * 把状态码转成中文，给日志用（以及给错误信息拼一句话）。
     *
     * <p>★ 不给前端做展示用。理由和 {@code OrderStatus.text()} 完全一样：
     * 前端要拿状态码去判断「做什么」，展示文案由前端自己的字典管。
     */
    public static String text(int status) {
        return switch (status) {
            case APPLIED -> "待审核";
            case WAITING_RETURN -> "待买家寄回";
            case WAITING_RECEIVE -> "待卖家收货";
            case REFUNDED -> "退款完成";
            case REJECTED -> "已拒绝";
            case CANCELLED -> "已撤销";
            default -> "未知状态(" + status + ")";
        };
    }
}
