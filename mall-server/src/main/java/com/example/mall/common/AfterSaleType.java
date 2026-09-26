package com.example.mall.common;

/**
 * 售后类型常量。★ 里程碑 17 新增。
 *
 * <h3>为什么类型也做成码，而不是一个 {@code isReturn} 布尔？</h3>
 *
 * <p>因为「两种类型」和「是不是退货」不是同一个问题。
 * 布尔只有两个值，看起来刚好够用，但它把<b>第三种类型</b>从设计里
 * 提前排除掉了（换货、维修、仅退货都属于「要寄回但不退钱」或
 * 「退钱但要寄回」的形状，塞不进一个布尔）。
 *
 * <p>更实际的理由：这个值会写进 {@code after_sale.type}，
 * 而<b>写进数据库的编号是会被历史数据引用的</b> ——
 * 和 {@code OrderStatus} 那条「取值定下来就不要再改了」是同一句话。
 * 今天把 1 定义成「仅退款」，明天想让 1 表示「退货退款」，
 * 线上所有老售后单的含义就全变了，而且看起来完全正常。
 *
 * <h3>★ 它决定的是「哪一条 SQL 边」</h3>
 *
 * <p>这两种类型的区别不是展示文案，而是<b>状态机走哪条边</b>：
 * <pre>
 *   仅退款   →  同意即退款（0 → 3），货还在仓库里，<b>这一条边就能还库存</b>
 *   退货退款 →  同意只是同意（0 → 1），要等买家寄回、卖家收到（2 → 3）才退款还库存
 * </pre>
 * 所以这个值会被写进 {@code AfterSaleMapper.markRefunded} 的 {@code WHERE} 里。
 * <b>少写那个条件，对一张「退货退款」的单点「同意」就会货没回来钱先退了。</b>
 */
public final class AfterSaleType {

    private AfterSaleType() {
    }

    /**
     * 仅退款：适用于<b>还没发货</b>的订单（货在仓库里，退钱不用退货）。
     *
     * <p>它只可能出现在 {@code orders.status = 1}（已付款未发货）上，
     * 因为只有那时候货还在商家手上。
     */
    public static final int ONLY_REFUND = 1;

    /**
     * 退货退款：适用于<b>已发货 / 已完成</b>的订单（货在买家手里，得寄回来）。
     *
     * <p>状态机比仅退款多两步：同意 → 待买家寄回 → 待卖家收货 → 退款。
     * 为什么不能合成一步，见 {@code AfterSaleStatus} 的类注释 ——
     * 那是本轮最贵的一个决定。
     */
    public static final int RETURN_REFUND = 2;

    /**
     * 把类型码转成中文，给日志用。
     *
     * <p>★ 和 {@code OrderStatus.text()} 同一条纪律：
     * <b>码给前端（用于判断），中文给日志（用于人看）。</b>
     * 前端要显示「仅退款」这三个字的话，自己维护一个字典 ——
     * 后端返回展示文案就等于把展示逻辑拽到服务端，两边各一份且会不一致。
     */
    public static String text(int type) {
        return switch (type) {
            case ONLY_REFUND -> "仅退款";
            case RETURN_REFUND -> "退货退款";
            default -> "未知类型(" + type + ")";
        };
    }

    /**
     * 这个类型码是不是一个定义过的值。
     *
     * <p>★ 申请接口的 {@code type} 来自请求体，是<b>客户端说了算</b>的，
     * 所以必须校验。不校验的后果很安静：传一个 {@code type = 99} 进来，
     * 落库成功，然后 {@code markRefunded} 的 {@code type = 1} 和
     * {@code markApprovedForReturn} 的 {@code type = 2} 都匹配不上 ——
     * <b>这张售后单永远同意不了，而且没有任何一层会报错。</b>
     */
    public static boolean isValid(Integer type) {
        return type != null && (type == ONLY_REFUND || type == RETURN_REFUND);
    }
}
