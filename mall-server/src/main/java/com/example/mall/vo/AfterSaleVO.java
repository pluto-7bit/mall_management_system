package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后单（返回给前端的结构）。★ 里程碑 17 新增。
 *
 * <h3>★ 为什么用户端和管理端共用这一个 VO（而不是各写一个）？</h3>
 *
 * <p>因为两端的售后信息<b>完全一样</b>：一笔售后单就是这些字段，
 * 没有「管理端才能看的东西」需要藏起来 —— 对比 {@code OrderVO}
 * 里面有收货人姓名电话住址（那是纯私人信息，管理端多看的是「这单是谁的」）。
 *
 * <p>而管理端多出来的那点东西（这单是谁提交的）由一个字段装得下，
 * 所以 {@code AdminAfterSaleVO extends AfterSaleVO} 加两个会员字段就够了 ——
 * 和 {@code AdminOrderVO} 完全同一个手法。
 *
 * <h3>★ 它没有 {@code statusText} / {@code typeText}</h3>
 *
 * <p>理由和 {@code OrderVO} 一字不差：<b>码给前端（用于判断），
 * 中文给日志（用于人看）。</b>后端一旦返回展示文案，
 * 就会诱导前端写 {@code if (statusText === '待审核')}，
 * 然后后端某天改一个字，前端静默失效（里程碑 7 在
 * {@code Register.vue} 里踩过一次）。
 *
 * <h3>⚠️⚠️ 这个 VO 里一半的字段「有时候会整个从 JSON 里消失」</h3>
 *
 * <p>全局 Jackson 配了 {@code default-property-inclusion: non_null}。
 * 这个类里 null 的字段有一大堆，而且<b>null 和「空」在这里含义不同</b>：
 * <pre>
 *   refundAmount 为 null   →  钱还没退（★ 是「缺席」，不是「退了 0 元」）
 *   refundAmount 为 0.00   →  退了 0 元。★ 但本项目不会产生这个值，
 *                             因为退款额至少是那一行的货款
 *   refundFreight 为 0     →  这次退款不含运费（★ 是真实值，不会是 null）
 * </pre>
 * 所以前端判断必须<b>逐字段按语义写</b>，不能统一用 {@code === null}：
 * <pre>
 *   v-if="!a.refundTime"            ✅ 「还没退」—— 键可能不在，falsy 判断安全
 *   v-if="a.refundFreight > 0"      ✅ 「退了运费」—— 这一列 NOT NULL DEFAULT 0
 *   v-if="a.refundFreight === null" ❌ 永远 false，这一列不会有 null
 * </pre>
 * <b>最要命的一条是 {@code refundAmount}：后端没给</b>（null 且键消失）
 * <b>和前端漏读了</b>看起来一模一样。
 * 这就是为什么 {@code sql/test-frontend-contract.py} 里有一条专门检查
 * 「已退款的售后必定带 refundAmount / refundTime / refundMethod」——
 * 那种组合是<b>不变量</b>，不是「碰巧有值」。
 */
@Data
public class AfterSaleVO {

    private Long id;

    /** 售后单号（前缀 {@code AS}）。★ 前端显示、客服查询用的那个 */
    private String afterSaleNo;

    /**
     * 对应的订单号（快照，不是 join 出来的）。
     *
     * <p>★ 它让售后列表<b>不需要 join {@code orders}</b> ——
     * 少一个 LEFT JOIN 就少一份「订单被硬删了售后单跟着消失」的陷阱。
     */
    private String orderNo;

    /** 被申请的订单明细 id。前端用它把自己那一行和售后单对上是同一条 */
    private Long orderItemId;

    /** 类型：1=仅退款 2=退货退款。★ 前端拿它决定「要不要显示寄回物流那一栏」 */
    private Integer type;

    /** 状态：0=待审核 1=待买家寄回 2=待卖家收货 3=退款完成 4=已拒绝 5=已撤销。★ 唯一该用来判断的字段 */
    private Integer status;

    /** 申请原因码。★ 前端拿它查自己的展示字典，不要拿后端中文去比 */
    private Integer reason;

    /** 用户填的补充说明。可选，可能为 null（键会消失） */
    private String description;

    // ======================================================================
    //  退款三件套 + 运费部分（★ 四个字段在同一次 UPDATE 里一起出现）
    // ======================================================================

    /**
     * 实退金额 = 这一行的货款 + 整单退时的运费。<b>退款成功之后才有值。</b>
     *
     * <p>⚠️ <b>申请中 / 被拒 / 已撤销的售后单没有这个字段</b>（null → 键消失）。
     * 这不是「漏给了」，是刻意的：金额要到退款那一刻才能算准，
     * 因为「运费退不退」取决于之后是不是全部明细都退完了。
     * 详见 {@code AfterSale.refundAmount} 的完整论证。
     *
     * <p>★ 那申请中的单，前端显示「预计退多少」用什么？用
     * {@link #subtotal}（那一行的货款快照）—— 它本来就在明细里。
     * 这是本项目<b>唯一</b>一处前端显示金额而不经过后端算的地方，
     * 所以文案必须诚实。
     */
    private BigDecimal refundAmount;

    /**
     * 其中属于运费的部分。★ <b>0 是真实值，不是缺席</b>（列是 NOT NULL DEFAULT 0）。
     *
     * <p>前端判「这次退了运费吗」要写 {@code refundFreight > 0}，
     * ★ <b>不能</b>写 {@code refundFreight == null}。
     */
    private BigDecimal refundFreight;

    /**
     * 退款去向 = 订单支付方式的快照（ALIPAY / WECHAT / BANK）。
     *
     * <p>前端拿它查自己的字典，显示成「已原路退回至微信」。
     */
    private String refundMethod;

    /** 退款时间。<b>和上面三个同一次写入</b>（§6.3(b) 的原子性断言守着这件事） */
    private LocalDateTime refundTime;

    // ======================================================================
    //  拒绝 / 退货物流
    // ======================================================================

    /** 管理员拒绝的理由。只有 {@code status = 4} 才有值 */
    private String rejectReason;

    /** 买家寄回的快递公司。只有填过寄回信息才有值 */
    private String returnCompany;

    /** 买家寄回的快递单号。同上 */
    private String returnTracking;

    /** 买家填寄回信息的时间 */
    private LocalDateTime returnTime;

    /** 管理员确认收到退货的时间。只有 {@code status ∈ {3}} 且是退货退款才有值 */
    private LocalDateTime receiveTime;

    private LocalDateTime createTime;

    // ======================================================================
    //  明细快照（★ 来自 LEFT JOIN order_item，见 AfterSaleMapper.xml）
    // ======================================================================

    /**
     * 商品名快照。★ 让售后列表能显示「退的是哪件东西」。
     *
     * <p>它来自 {@code order_item}（join 主键，一对一无重复风险），
     * 而不是 {@code product} —— 商品可能已经被删了，而订单明细是快照，
     * 永远在。这正是 {@code OrderItemVO} 类注释里
     * 「不要为了显示图片去 join product」那条纪律的正面应用。
     */
    private String productName;

    /** 规格文本快照，形如 {@code "颜色:黑 / 内存:128G"}；无规格的商品是空串 */
    private String skuSpec;

    /** 下单时的单价快照 */
    private BigDecimal price;

    private Integer quantity;

    /**
     * 那一行的小计快照（= price × quantity）。
     *
     * <p>★★ <b>申请中的售后单，前端显示「预计退 ¥xx」用的就是它。</b>
     * 它是不可变的历史快照，和退款时刻算出来的 {@code refundAmount}
     * 在没有整单退的情况下<b>必然相等</b> —— 而「整单退时
     * {@code refundAmount} = 它 + 运费」这条关系，
     * 由 {@code sql/test-after-sale.py} 的 F 组断言守着。
     */
    private BigDecimal subtotal;
}
