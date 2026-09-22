package com.example.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体 —— 与数据库 {@code orders} 表一一对应。
 *
 * <h3>★ 这个 Entity 里最需要理解的是「快照」</h3>
 *
 * <p>{@code receiverName / receiverPhone / receiverAddress} 这三个字段
 * 看起来和 {@code member_address} 表重复了，为什么还要存一份？
 *
 * <p>因为<b>订单是历史事实，不是对当前状态的引用。</b>
 *
 * <p>设想这样一个场景：
 * <pre>
 *   1 月 1 日  张三下单，填的收货地址是「深圳市南山区科技园路 1 号」，订单里记 address_id = 5
 *   1 月 5 日  张三搬家了，把地址 5 改成了「北京市朝阳区望京 SOHO」
 *   1 月 6 日  商家去查 1 月 1 日那笔订单该发到哪 →
 * </pre>
 * 如果订单只存 {@code address_id}，那么 1 月 6 日发出去的货会被送到北京 ——
 * <b>因为用户改了一次地址，历史订单被"重写"了。</b>
 * 而这笔订单在 1 月 1 日生成时，事实就是「寄到深圳」。
 *
 * <p>更要命的是地址还能被<b>删除</b>。删掉之后 {@code address_id} 就指向了
 * 一个不存在的行，订单的收货信息直接变成空 —— 而订单是不能出错的。
 *
 * <p>所以：<b>凡是「下单这一刻定下来、之后永远不该变」的信息，
 * 都要在下单时抄一份存进订单里。</b>这叫快照。
 * 同理还有 {@code order_item} 里的 {@code productName} 和 {@code price} ——
 * 商品明天涨价了，今天这笔订单的金额不能跟着变。
 *
 * <p><b>★ 那为什么还留着 {@code address_id}？</b>
 * 因为它有用：客诉时能回答「这条地址当时的原始记录是什么样」，
 * 以及做「常用地址统计」时能关联回去。但它只是<b>线索</b>，
 * 不是订单的事实来源 —— 事实来源是上面那三个快照字段。
 * 也正因为如此，它<b>故意不加外键</b>：加了外键，地址就删不掉了，
 * 而这恰恰是快照要避免的耦合。
 *
 * <p><b>提炼成一条规则：快照表和来源表之间不该有强引用。</b>
 * 强引用意味着「来源变了/没了，快照也受影响」——
 * 那快照就不叫快照了。
 */
@Data
public class Order {

    private Long id;

    /**
     * 订单号（业务编号，给外部用）。
     *
     * <p>和自增 id 的区别、以及为什么需要它，见 {@code OrderNoGenerator}。
     */
    private String orderNo;

    private Long memberId;

    // ---- 收货信息快照（下单时从地址簿抄一份，之后不再跟着地址变）----

    private String receiverName;

    private String receiverPhone;

    /** 收货地址全文（{@code region + " " + detail} 拼好的） */
    private String receiverAddress;

    /** 来源地址 id，仅作追溯用，故意不加外键 */
    private Long addressId;

    /**
     * 订单总金额。
     *
     * <p>同样是快照：由下单时的各商品单价 × 数量算出，之后永远不变。
     *
     * <p><b>★ 存这个「算出来的值」不算冗余。</b>
     * 因为「订单总额 = 各明细小计之和」这条规则，在商品改价之后就不成立了 ——
     * 明细里存的是当时的价格。如果每次查订单都去把明细加一遍，
     * 遇到金额有争议（比如将来加了优惠券、运费）时，
     * 就没法回答「当时算出来是多少」。
     * <b>能重算的值，如果它需要被"定格"，就该存下来。</b>
     */
    private BigDecimal totalAmount;

    /** 状态，取值见 {@code OrderStatus} */
    private Integer status;

    /**
     * 支付时间，未支付时为 null。
     *
     * <p><b>★ 这五个「生命周期」字段都可空，和上面三个收货快照（非空）正好相反。</b>
     * 判断标准是同一句话：<b>「这一行刚插入时，这个字段有值吗？」</b>
     * 收货人下单那一刻就必须有 → 非空；支付时间下单那一刻还没有「付款」这回事
     * → 只能为空。给它一个默认值等于把「没付过款」记成「已付款」，
     * 那是数据造假。
     *
     * <p>为什么不用 status 一个字段表达「付没付」就够了？
     * 因为 status 只说<b>现在是什么状态</b>，说不出<b>什么时候变成这个状态的</b>。
     * 「这笔订单是 3 秒内付的款，还是卡在最后 1 分钟才付的」
     * 是个真实的业务问题（风控、对账、客诉都要问），
     * 而这个信息只能靠时间戳回答。
     *
     * <p>★ 五个状态里只有「待付款」没有自己的时刻，这不是漏了 ——
     * 待付款是订单的<b>初始</b>状态，它的时刻就是 {@code createTime}。
     */
    private LocalDateTime payTime;

    /** 取消时间（用户主动取消或超时自动取消），未取消时为 null */
    private LocalDateTime cancelTime;

    /**
     * 支付方式，取值见 {@code PayMethod}（ALIPAY / WECHAT / BANK），未支付时为 null。
     *
     * <p>存的是<b>码</b>不是中文，理由和 {@code OrderStatus} 一样：
     * 码给程序判断，中文随时可以改，中文一变历史数据就对不上了。
     */
    private String payMethod;

    /**
     * 发货时间（里程碑 10 加的），未发货时为 null。
     *
     * <p>★ 为什么加这两列，而里程碑 9 又拒绝了 {@code cancel_type} 列？
     * 标准是同一条：<b>有读者才加列。</b>
     * 「已发货 2026-09-22」要显示在用户端和管理端两个页面上 → 有读者；
     * {@code cancel_type}（谁取消的）没有任何代码会去分支判断它 → 不加。
     * 同一条标准，两个相反的结论，区别只在有没有人真的会去读它。
     */
    private LocalDateTime shipTime;

    /** 完成时间（买家确认收货），未确认时为 null */
    private LocalDateTime completeTime;

    /**
     * 幂等键，防重复提交。
     *
     * <p>唯一性是<b>按会员</b>的（{@code uk_member_idempotency}），
     * 不是全局的 —— 理由见 {@code migration-08b-idempotency-scope.sql}。
     */
    private String idempotencyKey;

    /** 用户填的备注，可以为空 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
