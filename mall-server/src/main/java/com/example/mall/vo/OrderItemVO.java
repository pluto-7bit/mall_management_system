package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细（返回给前端的结构）。
 *
 * <h3>★ 它和 {@code OrderItem} 实体几乎一模一样，为什么还要单独一个类？</h3>
 *
 * <p>因为判断「该不该建 VO」的标准是<b>「有没有不该给前端看的东西」</b>，
 * 而不是「字段是不是一样多」。
 *
 * <p>看一遍 {@code OrderItem} 的字段：
 * <pre>
 *   id           → ★ 里程碑 12 起前端要用了：提交评价时要把它当 orderItemId 传回来
 *   orderId      → 属于哪一单，见下面「★ 为什么把它加回来」
 *   productId    → 需要 ✅ 前端要靠它跳到商品详情页
 *   skuId        → ★ 里程碑 15 加的，可空；现在没有读者，留给售后退款
 *   productName  → 需要 ✅ 展示
 *   skuSpec      → 需要 ✅ 展示（里程碑 15 加的，空串表示无规格）
 *   price        → 需要 ✅ 展示
 *   quantity     → 需要 ✅ 展示
 *   subtotal     → 需要 ✅ 展示
 *   createTime   → 无害
 * </pre>
 *
 * <p>★ 上面 {@code id} 那一行原本写的是
 * 「明细行的主键，前端用不到（但留着无害，调试时有用）」——
 * <b>而那时这个字段根本不存在，两条 SQL 也没有 select 它。</b>
 * 那是一句「说的是假话的注释」，能活这么久恰恰是因为那个字段没有读者：
 * 没人读它，就没人会发现它不存在。
 *
 * <p><b>「有读者才加字段」这条规矩的反面不是「不加」，是「说它存在」。</b>
 * 一句描述了不存在的字段的注释，比没有注释更糟 ——
 * 下一个人会照着它写代码，然后拿到 {@code undefined}。
 * 里程碑 12 给它一个真身（并顺手让注释变成真的）。
 *
 * <p><b>结论是：这里其实没有一个字段需要藏。</b>
 * 那为什么还是建了 VO？因为<b>它挡住的是未来</b>：
 * {@code order_item} 表将来一定会加内部字段
 * （比如 {@code refund_status} 退款状态、{@code cost_price} 成本价 ——
 * 成本价是绝对不能给用户看的），加了之后如果直接返回 Entity，
 * 就会<b>静默泄露</b>。
 *
 * <p>有 VO 挡着，新加的内部字段默认不会出现在接口响应里，要显式加才行。
 * <b>让「泄露」需要主动做一件事，比让「不泄露」需要主动做一件事更安全。</b>
 *
 * <p>这正是 {@code ShopAddressController} 里说的「这个问题没有统一答案，
 * 但有一个统一的判断方法」的补充：<b>除了「现在有没有敏感的」，
 * 还要看「将来会不会有」。结构简单、几乎不会变的（比如 MemberAddress）
 * 可以不建 VO；而订单明细这种明确会长字段的，建了更省心。</b>
 *
 * <h3>★ 为什么 {@code orderId} 在里程碑 10 又被加回来了？</h3>
 *
 * <p>它原来是被故意省掉的，理由写在这里：「前端已经知道（它就是从这个单里展开的）」。
 * <b>那个理由对「查一笔订单的明细」成立 —— 但对「一次查一页订单的明细」不成立。</b>
 *
 * <p>里程碑 10 加了订单列表，一页 10 笔订单要一次把明细全查回来
 * （10 条 {@code selectByOrderId} 就是教科书级的 N+1）。
 * 批量查回来的是一堆混在一起的 vo，<b>必须有一个字段能看出「这条明细属于哪一单」</b> ——
 * 这个字段就是分组键，省不掉了。
 *
 * <p>而它给客户端是<b>无害的</b>：单笔查询里它本来就是冗余信息（调用方当然知道自己查的是哪一单），
 * 冗余不等于泄露 —— 这个 VO 从头到尾没有装过任何不能给用户看的东西。
 * <b>被省掉的字段要拿回来，就该把当初省掉它的理由一起改掉</b>，
 * 否则这段注释就成了「说的是假话的注释」，比没有注释更糟。
 *
 * <h3>⚠️ 两条查询（单笔 / 批量）返回的是同一种形状</h3>
 *
 * <p>所以 {@code selectByOrderId} 也会带上 {@code orderId}。
 * 让某一条少一个字段，换来的「省一点」远不如「两条查询形状一致」值钱 ——
 * 形状不一致的坑在 {@code tools/fixture-pay.py} 里记着（三个创建接口返回三种形状，
 * 取错形状只会得到一个让人摸不着头脑的 {@code TypeError}）。
 *
 * <p>★ 里程碑 12 加的 {@link #id} 和 {@link #reviewId} <b>同样遵守这一条</b>：
 * 两条 SQL 都改了、都 join 了 {@code product_review}。
 * 只改一条的话，症状是「订单列表里点不了评价、订单详情里能点」——
 * 而这两个页面的数据来自同一个 VO，看代码根本看不出差别。
 *
 * <p>⚠️ 注意这里<b>没有</b> {@code cover}（商品封面图）。
 * 订单详情页确实想显示商品图片，但那要 join {@code product} 表 ——
 * 而商品可能已经被删了，那时候图片就查不到了。
 * 如果要做，正确的方式是把 {@code cover} 也做成下单快照存进 order_item，
 * 而不是在查订单时去 join 商品表。
 * 本项目先不显示图片，属于刻意的简化。
 *
 * <h3>★★★ 里程碑 12 给这个 VO 加了 {@code reviewId} —— 这正面顶着里程碑 11 立下的规矩</h3>
 *
 * <p>里程碑 11 给 {@code ShopProductDetailVO} 加 {@code images} 时，
 * 结论是「<b>不要把字段塞进共用的查询</b>」，宁可让 Service 事后单独查一次。
 * 现在却把 {@code reviewId} 直接 join 进了这条被四个地方共用的 SQL。看起来是反着做。
 *
 * <p><b>但判据不是「这条 SQL 有几个使用者」，而是「多出来的东西是不是这个对象本身的属性」：</b>
 * <table border="1">
 *   <caption>两次加字段的对照</caption>
 *   <tr><th></th><th>里程碑 11 的 {@code images}</th><th>本轮的 {@code reviewId}</th></tr>
 *   <tr><td>那条 SQL 还有谁在用</td>
 *       <td>详情页 + <b>购物车</b>（只读 stock 的热路径）</td>
 *       <td>订单列表/详情（用户端和管理端各一处），四个都是真实读者</td></tr>
 *   <tr><td>多出来的是什么</td>
 *       <td><b>某一个页面专属的</b>富字段（图集）</td>
 *       <td><b>「订单明细」这个对象本身</b>的状态（评没评过）</td></tr>
 *   <tr><td>代价落在哪</td>
 *       <td>一个完全不需要它的高频热路径（每次加购物车都多查一列）</td>
 *       <td>四个使用者都在展示同一张明细列表，没有谁是「被牵连的」</td></tr>
 *   <tr><td>结论</td><td>拒绝，挪到 Service 里单独查</td><td>加</td></tr>
 * </table>
 *
 * <p>另外三条具体的理由：
 * <ol>
 *   <li>join 走的是 {@code product_review} 上 {@code uk_order_item} <b>唯一索引</b>，
 *       每行一次索引查找 —— <b>不是 N+1</b>，也不是全表扫。</li>
 *   <li>{@code count} 语句完全不受影响（join 只加在列表语句里）。</li>
 *   <li>本类的注释反复强调「两条查询必须形状一致」。
 *       如果改成「只在用户端那条路径上单独补一次查询」，
 *       就造出了<b>第二条装配路径</b> —— 而 {@code OrderServiceImpl.toVO}
 *       的注释专门警告过：第二条路径就是「将来加字段时漏改一处」的来源。</li>
 * </ol>
 *
 * <p><b>★ 换个场景，理由要重新问一遍，不能因为「上次拒绝了这次也拒绝」。</b>
 *
 * <p>⚠️⚠️ <b>{@code reviewId} 是 {@code Long}，没评价时是 null，
 * 而全局 Jackson 配了 {@code default-property-inclusion: non_null}
 * —— 这个键会整个从 JSON 里消失。</b>
 * 所以前端<b>不能</b>写 {@code it.reviewId === null}（它永远是 false），
 * 要写 {@code !it.reviewId}。这个坑在里程碑 11 已经咬过两次。
 */
@Data
public class OrderItemVO {

    /**
     * 明细行的主键（{@code order_item.id}）。★ 里程碑 12 加的。
     *
     * <p>前端拿它做的事只有一件：<b>提交评价时把它当 {@code orderItemId} 传回来</b>
     * （{@code POST /api/shop/reviews}）。
     *
     * <p>所以这个字段是<b>「订单」和「评价」两个域之间唯一的接口</b> ——
     * 评价接口不认识订单号，也不需要认识：一条订单明细只能评一次，
     * 所以明细 id 就是评价的天然身份。
     *
     * <p>⚠️ 在里程碑 12 之前这个字段<b>不存在</b>，而类注释里却写着它
     * 「留着无害，调试时有用」—— 详见类注释里那段「说的是假话的注释」。
     */
    private Long id;

    /**
     * 属于哪一笔订单（{@code order_item.order_id}）。
     *
     * <p>对单个订单来说它是冗余的；对<b>批量查明细</b>来说它是分组键。
     * 见类注释里「为什么 orderId 又被加回来」。
     */
    private Long orderId;

    private Long productId;

    /**
     * 买的是哪个 SKU（{@code order_item.sku_id}）。★ 里程碑 15 加的。
     *
     * <p><b>⚠️ 它可以是 null，而且前端必须容忍</b>：历史订单（里程碑 13
     * 之前下的）和商品已被硬删的孤儿明细都填不出真值，硬填就是造假。
     * 详见 {@code OrderItem.skuId} 的注释。
     *
     * <p>⚠️⚠️ 于是它和 {@link #reviewId} 一样，遇到
     * {@code default-property-inclusion: non_null} —— <b>null 时这个键整个消失</b>。
     * 前端不要写 {@code it.skuId === null}。
     *
     * <p>★ 那前端拿它做什么？<b>现在什么也不做。</b>
     * 展示规格用的是 {@link #skuSpec}（文本快照，永不为 null）。
     * 它在这里是<b>留给下一个里程碑的接口</b>：售后退款要按 SKU 维度走，
     * 而那时候必须知道退的是哪一行规格。提前加上是因为
     * 「已经加过的字段不需要第二遍 SQL 改动」，而且它和 {@code productId}
     * 一样属于「订单明细本身」的属性 —— 判据见 {@link #reviewId} 那段对照论证。
     */
    private Long skuId;

    private String productName;

    /**
     * 规格文本快照，形如 {@code "颜色:黑 / 内存:128G"}；无规格的商品是空串。
     *
     * <p>★ 里程碑 15 加的，订单页那一行商品名下显示它。
     *
     * <p><b>为什么是文本而不是 JSON</b>：理由在 {@code OrderItem.skuSpec} 里 ——
     * 它是<b>快照</b>，商家后来改了规格名也不该影响历史订单的显示。
     *
     * <p>⚠️ 它是空串（不是 null）：{@code ''} 在 JS 里是 falsy，
     * 所以前端写 {@code v-if="it.skuSpec"} 就够了，而且这个键<b>永远在</b> ——
     * 这一点和 {@link #skuId} / {@link #reviewId} 恰好相反，
     * 别把这三个字段的 null 规则记混。
     */
    private String skuSpec;

    /** 下单时的单价（快照，不是商品的当前价） */
    private BigDecimal price;

    private Integer quantity;

    /** 小计 = price × quantity */
    private BigDecimal subtotal;

    /**
     * 这条明细对应的评价 id，<b>没评价过时为 null</b>（★ 里程碑 12 加的）。
     *
     * <p>前端靠它决定那一行显示「评价」按钮还是「已评价」标签。
     *
     * <p>★★ <b>它不是 {@code order_item} 表的列，是 {@code LEFT JOIN product_review}
     * 出来的</b> —— 而 {@code product_review.order_item_id} 上有唯一索引，
     * 所以这个 join 每行最多匹配一条，不会让明细行数变多。
     * 为什么敢把它加进这条共用的 SQL，见类注释里那段完整的对照论证。
     *
     * <p>⚠️⚠️ <b>没评价时这个键会整个从 JSON 里消失，不是 null。</b>
     * 全局 Jackson 配了 {@code default-property-inclusion: non_null}，
     * null 字段直接被省略。所以前端要写
     * <pre>
     *   v-if="!it.reviewId"      ✅ 两种情况下都是 false/undefined → 都判为「没评」
     *   v-if="it.reviewId === null"  ❌ 键都不存在，怎么会 === null
     * </pre>
     * 这个坑在里程碑 11 已经咬过两次（{@code payTime} / {@code shipTime}）。
     *
     * <p>★ 顺带一提，这也是为什么它<b>不能</b>是 {@code long} 基本类型：
     * 基本类型表达不出「没有」这个状态，硬要表达就只能编一个 0 出来，
     * 而 0 是一个合法的 id 的近邻 —— 那种「用一个哨兵值表示缺失」的做法，
     * 迟早会被当成真实值用一次。
     */
    private Long reviewId;
}
