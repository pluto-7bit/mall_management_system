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
 * <p>★ 里程碑 15 之后，抄的对象多了一个：{@code skuSpec} 是从
 * {@code product_sku.spec_json} 转成文本抄过来的。价格的真源也换了 ——
 * {@code price} 现在快照的是 {@code product_sku.price}，
 * 而不再是 {@code product.price}（那一列阶段 6 就删了）。
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

    /**
     * SKU id —— <b>「买的是哪个规格」</b>，里程碑 15 加的唯一线索。
     *
     * <p>⚠️ <b>它【允许是 null】，而且这不是数据没填好：</b>
     * <pre>
     *   1. 里程碑 13 之前下的历史订单 —— 那时还没有 product_sku 这张表，
     *      迁移脚本只能按「该商品的默认 SKU」回填，回填不到的就是 NULL
     *   2. 商品已被硬删的孤儿明细 —— 没有外键，商品能带着 SKU 一起消失
     *      （mall.sql 里那段注释记着「漏过 32 行」）
     * </pre>
     * 这两种情况下<b>硬填一个值就是造假</b>（会指向一条别人的 SKU 行）。
     * NULL 是诚实的，而且它已经有代码路径：{@code increaseSkuStock(null, qty)}
     * → 影响 0 行 → 走「warn 不抛」那条分支。
     *
     * <p>用法上和 {@link #productId} 一样，只是<b>线索</b>，不是显示的依据。
     */
    private Long skuId;

    /** 商品名称（下单时快照） */
    private String productName;

    /**
     * 规格文本快照，形如 {@code "颜色:黑 / 内存:128G"}；无规格的商品是空串。
     *
     * <p><b>★ 为什么存的是【文本】而不是 {@code spec_json}？</b>
     *
     * <p>{@code order_item} 的三条查询（用户端订单列表、管理端订单列表、
     * 按订单查明细）都要显示这一行买的是什么规格。如果存 JSON，
     * 每条查询都得在 Java 里解析一遍，或者把 {@code spec_schema}
     * 一起 JOIN 进来重新拼 —— 而 {@code spec_schema} 是<b>会变的</b>：
     * 商家今天把「内存」改叫「存储」，历史订单的规格描述就跟着变了。
     *
     * <p>存文本则和 {@link #productName} 是同一个道理：
     * <b>这是快照，显不显示、显示成什么样，下单那一刻就定死了。</b>
     *
     * <p>⚠️ 它是 {@code NOT NULL DEFAULT ''}，而「去掉默认值」这件事
     * 被刻意推迟到了里程碑 15 阶段 6（{@code migration-13b}）——
     * 理由在 {@code sql/migration-13-sku.sql} 里：阶段 1 跑迁移时
     * Java 侧一行没改，列清单里没有这一列，DROP DEFAULT 会让
     * <b>每一次下单都 ERROR 1364</b>。加列不影响任何现有代码，收紧约束必然影响。
     */
    private String skuSpec;

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
