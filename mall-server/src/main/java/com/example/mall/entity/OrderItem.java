package com.example.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细实体 —— 与数据库 {@code order_item} 表一一对应。
 *
 * <p>一行 = 订单里的一种商品。一笔订单有几行，取决于用户买了几种商品。
 *
 * <h3>★ 为什么订单要拆成「主表 + 明细表」两张表？</h3>
 *
 * <p>因为一张订单里的商品数量是<b>不固定</b>的。如果不拆表，
 * 就得在 orders 表里预留 {@code product1_id, product2_id, ... product10_id}
 * 这样的列 —— 那会有三个问题：
 * <pre>
 *   1. 买 11 种商品怎么办？（列不够）
 *   2. 大部分订单只买一两种，剩下 8 列全是 NULL（浪费且难查）
 *   3. 「查询买了某商品的所有订单」要写 10 个 OR（无法建索引）
 * </pre>
 * 拆成「一对多」之后，三个问题一起解决了。
 *
 * <p><b>这是数据库设计里的一条基本判断：当一个字段会「重复出现」
 * 且次数不定时，它就不属于这张表，应该独立成一张子表。</b>
 * 对比 {@code member_address}：一个会员有多个地址，同样的道理。
 *
 * <h3>★ 这张表也是快照</h3>
 *
 * <p>{@code productName} 和 {@code price} 都是从 {@code product} 表抄过来的。
 * 理由和 {@code Order.receiverAddress} 完全一样：
 * 商品明天改名或涨价，不能影响今天这笔订单。
 *
 * <p>而且这里比收货地址还多一层必要性：
 * <b>商品是可以被删除的。</b>如果明细只存 {@code product_id}，
 * 商品一旦被删，这条明细就成了「一笔不知道买了什么的订单」。
 *
 * <p>所以 {@code product_id} 在这里的作用也只是「去查原始商品」的线索，
 * <b>订单里显示什么，以本表的快照字段为准。</b>
 */
@Data
public class OrderItem {

    private Long id;

    private Long orderId;

    /** 商品 id，仅作追溯用 */
    private Long productId;

    /** 商品名称（下单时快照） */
    private String productName;

    /** 单价（下单时快照） */
    private BigDecimal price;

    private Integer quantity;

    /**
     * 小计 = {@code price × quantity}。
     *
     * <p><b>★ 这个值也是存下来的，同样是冗余。</b>
     * 理由是金额的<b>可审计性</b>：将来如果要加优惠、抹零之类的规则，
     * 「当时这一行算出来是多少」必须有个确定的记录，
     * 而不是每次用新的规则去重算。
     *
     * <p>存冗余字段的代价是「两个地方可能不一致」，
     * 所以要不要存，判断标准是：
     * <b>这个值重算一遍得到的答案，和当时算出来的答案会不会不一样？</b>
     * 会不一样（或者需要额外的信息才能算），就存。
     * 「小计 = 单价 × 数量」虽然简单，但它属于「已经开出去的发票」——
     * 属于历史事实那一类。
     */
    private BigDecimal subtotal;

    private LocalDateTime createTime;
}
