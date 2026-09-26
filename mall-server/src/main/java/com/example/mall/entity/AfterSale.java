package com.example.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后单实体，对应表 {@code after_sale}。★ 里程碑 17 新增。
 *
 * <h3>★★ 一张售后单只对<b>一条</b>订单明细</h3>
 *
 * <p>这是本轮最重要的一条模型决定，所以放在最前面。
 * 它直接导致了下面三个「没有」：
 * <pre>
 *   没有 after_sale_item 明细表      → 「一条明细有没有活跃售后」只有一个定义者
 *   没有「按行分摊的运费」           → 运费退多少有唯一答案（全退才退）
 *   没有主单状态的汇总              → 主单状态不会变成明细状态的聚合
 * </pre>
 *
 * <p><b>为什么不做「一张售后单管多行」？</b>三条独立的理由：
 * <ol>
 *   <li><b>状态机会变成汇总。</b>一张单里有 3 行，一行被拒、两行通过，
 *       这张单是什么状态？这个问题的答案只能是「算出来的」，
 *       而算出来的东西就有漂移的机会。</li>
 *   <li><b>「这一行还有没有活跃售后」会有两个定义者</b>——
 *       主单的状态 + 明细行的状态。而它们分岔的症状是
 *       「主单被拒了、明细令牌忘了释放 → 那一行永远申请不了售后」，
 *       全程没有任何一层会报错。</li>
 *   <li><b>金额会没有唯一答案。</b>「运费退多少」需要一条分摊规则
 *       （按金额？按件数？按重量？），而按金额分摊会引入除法，
 *       除不尽时<b>所有行的退款额之和可能比运费多一分或少一分</b>，
 *       且只在特定金额组合下出现。</li>
 * </ol>
 * 这和 README 里「跨规格的成本汇总没有唯一答案」是同一个形态。
 *
 * <p><b>那「整单退」怎么支持？</b>它是体验问题，不是模型问题：
 * 申请接口的 body 收 {@code orderItemIds: [...]}，
 * Service 在<b>一个事务里建 N 张售后单</b>（全成或全败）。
 * 逐行审批反而更接近真实 —— 真实客服就是一单一单点的。
 *
 * <h3>★ 为什么表里存了 {@code order_no} 这个快照？</h3>
 *
 * <p>因为售后列表<b>不 join {@code orders}</b>。要 join 就得用 LEFT JOIN
 * （订单被硬删了售后单不该跟着消失），而每多一个 LEFT JOIN 就多一个
 * 「行数变多 / 字段变 null」的陷阱。售后单自己要显示的东西
 * （「这张单对应哪笔订单」）全部由快照提供就够了 ——
 * 和 {@code order_item.product_name}、{@code orders.receiver_address}
 * 是同一个思路：<b>快照字段不该因为来源表消失而消失。</b>
 */
@Data
public class AfterSale {

    /** 主键（自增） */
    private Long id;

    /**
     * 售后单号（给用户看、给客服查的那个）。前缀 {@code AS}，22 位。
     *
     * <p>见 {@code AfterSaleNoGenerator} —— 它和订单号是<b>两个号码空间</b>，
     * 因为一个号属于哪个域应该是读一眼就知道的。
     */
    private String afterSaleNo;

    /** 订单 id。★ T8（推订单终态）靠它找「这笔订单还有没有别的明细没退完」 */
    private Long orderId;

    /** 订单号快照。人看、人搜，售后列表不为它 join {@code orders} */
    private String orderNo;

    /**
     * 被申请的订单明细 id。★ <b>{@code uk_order_item_active} 的一半</b>。
     *
     * <p>「一条明细最多一张进行中的售后单」这条不变量，
     * 完全由 {@code (order_item_id, active_token)} 上的唯一索引守着，
     * 不靠 Service 里的先查后写 —— 理由见 {@link #activeToken}。
     */
    private Long orderItemId;

    /**
     * 申请会员 id。<b>安全边界</b>，从订单推导出来，<b>绝不由客户端提供</b>。
     *
     * <p>用户端的撤销（T7）和填寄回（T5）都会把它写进 WHERE ——
     * 没有它，任何人拿别人的售后单号就能撤掉别人的申请。
     */
    private Long memberId;

    /** 售后类型：1=仅退款 2=退货退款。取值见 {@code AfterSaleType} */
    private Integer type;

    /** 状态：0=待审核 1=待买家寄回 2=待卖家收货 3=退款完成 4=已拒绝 5=已撤销 */
    private Integer status;

    /** 申请原因码。取值见 {@code AfterSaleReason}。★ 是码不是自由文本，见那个类 */
    private Integer reason;

    /** 用户补充说明，可选 */
    private String description;

    /**
     * 实退金额 = 那一行的货款（{@code order_item.subtotal}）
     * + 整单退时一并退还的运费。★ <b>退款成功那一刻才写</b>，申请时是 null。
     *
     * <h4>★★ 为什么申请时不写？直觉的做法是「申请时就冻结金额」</h4>
     *
     * <p>因为用户希望看到「预计退 ¥99」。问题是<b>运费退不退取决于之后发生的事</b>——
     * 是不是全部明细都退完了。提前冻结金额就意味着退款时还要
     * {@code SET refund_amount = refund_amount + freight}，
     * <b>那就是两个写入者</b>，而这个字段的漂移直接等于退错钱。
     *
     * <p>所以它只在 {@code markRefunded}（T2/T6）那一条 UPDATE 里写一次，
     * 和 {@code refundTime} / {@code refundMethod} / {@code refundFreight}
     * <b>同一次写入</b>——这就是 §6.3(b) 那条原子性断言的由来。
     *
     * <p>「申请时看到的金额」由前端直接显示 {@code it.subtotal}
     * （那个值本来就在明细里）。★ 这是本项目<b>唯一</b>一处
     * 前端显示金额而不经过后端算的地方，所以文案必须诚实
     * （见 {@code Orders.vue} 的售后对话框）。
     *
     * <h4>★ 为什么是 {@code DEFAULT NULL} 而不是 {@code DEFAULT 0.00}</h4>
     *
     * <p>用的是 {@code migration-14b} 立下的判据：<b>这个 0 会不会被当成
     * 一个真实存在的数据拿去比较和计算？</b>
     * <pre>
     *   freight_amount = 0   会被拿去算 total = 明细 + 运费，而满额包邮下的 0 是合法真值 → DEFAULT 0.00
     *   refund_amount = 0    会被当成「退了 0 元」拿去求和，而「还没算出来」是【缺席】不是值 → DEFAULT NULL
     * </pre>
     */
    private BigDecimal refundAmount;

    /**
     * 其中属于运费的部分。★ <b>{@code NOT NULL DEFAULT 0.00}</b>，和上面那个相反。
     *
     * <p>因为这里的 0 是<b>真实值</b>：「这次退款不含运费」。部分退就是 0，
     * 而「退款完成但运费 0 元」是一个完全正常、需要被表达的状态。
     *
     * <p>它和 {@code refundAmount} 的分工：{@code refundAmount} 是总额
     * （用户看到的数），{@code refundFreight} 是其中运费的占比
     * （对账时「这个月退了多少运费」需要的数）。
     * <b>两个列说的不是同一件事的两种表示，是总额和它的一个组成部分。</b>
     */
    private BigDecimal refundFreight;

    /**
     * 退款去向 = 该订单 {@code pay_method} 的快照（ALIPAY / WECHAT / BANK）。
     *
     * <p>按判据 ③（有读者才加列）走一遍：售后列表要显示「已原路退回至微信」，
     * 而售后列表<b>不带 {@code orders} 的 join</b> → 有读者 → <b>加</b>。
     *
     * <p>★ 它是快照，不是「现在去查订单的付款方式」——
     * 理由和 {@code freight_amount} 一样：历史事实不该被今天的规则改写。
     */
    private String refundMethod;

    /** 退款时间。和 {@code refundAmount} 同一次写入 */
    private LocalDateTime refundTime;

    /** 管理员拒绝的理由（自由文本，<b>这个字段自由文本是对的</b>：它不用于统计） */
    private String rejectReason;

    /** 买家寄回的快递公司（自由文本，同上） */
    private String returnCompany;

    /** 买家寄回的快递单号（自由文本） */
    private String returnTracking;

    /** 买家填寄回信息的时间 */
    private LocalDateTime returnTime;

    /** 管理员确认收到退货的时间 */
    private LocalDateTime receiveTime;

    /**
     * ★★ 唯一性令牌。这是本项目<b>唯一</b>一处「同一事实两个表示」，请读完再改。
     *
     * <h4>它要解决什么问题</h4>
     *
     * <p>「一条订单明细不能被重复申请售后」听起来一个唯一索引就够了，
     * 但仔细看，要禁止的其实是「<b>两张进行中的单</b>」——
     * 被拒绝之后用户应该可以重新申请。而 MySQL <b>没有部分唯一索引</b>
     * （{@code UNIQUE ... WHERE status < 3} 这种写法不存在），
     * 「只在活跃期内唯一」用普通唯一索引表达不出来。
     *
     * <h4>解法：把「活跃」编码成一个能被唯一索引看见的值</h4>
     *
     * <pre>
     *   进行中 (status ∈ {0,1,2})  →  active_token = 0
     *   已关闭 (status ∈ {3,4,5})  →  active_token = 本行自己的 id
     * </pre>
     *
     * <p>同一条 {@code order_item_id} 下，令牌集合是
     * {@code {0} ∪ {每张已关闭单各自的 id}}，<b>全部互不相等</b>，
     * 于是 {@code uk_order_item_active (order_item_id, active_token)} 给出的保证正好是：
     * <pre>
     *   ★ 一条订单明细，最多只能有一张「进行中」的售后单；已关闭的历史单不限张数。
     * </pre>
     *
     * <p>而且「关闭」和「释放令牌」是<b>同一条 UPDATE 的两个列</b>
     * （见 {@code AfterSaleMapper} 的 T2 / T4 / T6 / T7），
     * 结构上不存在「忘了释放」这个错误。
     *
     * <h4>★★ 必须承认的妥协</h4>
     *
     * <p>{@code active_token} 和 {@code status} 说的是同一件事（关没关）。
     * 按判据 ①（定义者唯一），这该被消灭；它没有被消灭，
     * 是因为<b>数据库能力逼出来的</b>，不是设计。
     * 所以配了<b>两条</b>断言，各抓一个漂移方向：
     * <pre>
     *   status IN (0,1,2) 而 active_token &lt;&gt; 0  →  该占用没占用（同一行会有两张进行中的单）
     *   status IN (3,4,5) 而 active_token = 0   →  该释放没释放（那一行永远申请不了售后）
     * </pre>
     *
     * <h4>★ 试过但走不通的升级：STORED 生成列</h4>
     *
     * <p>理想方案是把它做成
     * {@code GENERATED ALWAYS AS (IF(status < 3, 0, id)) STORED}，
     * 让分岔在结构上不可能（两台钟变一台钟）。
     * <b>MySQL 拒绝了：{@code ERROR 3109 (HY000): Generated column 'active_token'
     * cannot refer to auto-increment column.}</b>
     * —— 生成列不允许引用 {@code AUTO_INCREMENT} 列。
     * 这个结论是阶段 1 <b>实验验证过的</b>（不是推测），
     * 原文记在 {@code sql/migration-17-after-sale.sql} 里，免得下一个人再试一遍。
     */
    private Long activeToken;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
