package com.example.mall.vo;

import lombok.Data;

/**
 * 「这条订单明细能不能被评价」—— 一次查询的结果。
 *
 * <h3>⚠️ 它不是接口的返回对象，这个类永远不会被任何 Controller 返回</h3>
 *
 * <p>本项目里 {@code vo} 包放的一直是「返回给前端的结构」，这里是<b>唯一一个例外</b>，
 * 所以必须写清楚，否则下一个人会以为它是个接口响应。
 *
 * <p>为什么还是放在 {@code vo} 而不是跟着实体走：<b>它是 Mapper 的复杂查询结果</b>，
 * 而本项目的约定是「Mapper 查出来的非实体对象都放 {@code vo}」
 * （{@code AdminOrderVO} / {@code CartItemVO} / {@code ShopProductVO} 都是这个位置）。
 * 把唯一一个例外挪到别处，反而会让人找不到它。
 *
 * <h3>★★ 它为什么是【一个】查询，而不是「先查明细、再查状态」两次查询</h3>
 *
 * <p>看 {@code ProductReviewMapper.selectOrderItemForReview} 的 SQL：
 * <pre>
 *   SELECT oi.id, oi.product_id, oi.product_name, o.status
 *   FROM order_item oi
 *   JOIN orders o ON o.id = oi.order_id
 *   WHERE oi.id = #{orderItemId}
 *     AND o.member_id = #{memberId}
 * </pre>
 * 一次查询同时拿到「这条明细是什么」和「它属于的那笔订单是什么状态」。
 *
 * <p>拆成两次的话，<b>第二次查询的一瞬间，订单状态可能已经变了</b>（TOCTOU）——
 * 于是「查的时候是已完成、插入的时候其实已被取消」这种窗口就存在了。
 * 一次查完，至少资格判断是基于同一个快照的。
 * （这和 {@code OrderAdminMapper.selectAdminByOrderNo} 的注释里
 * 「为什么不拆成两次查询」是同一个理由。）
 *
 * <h3>★★ 为什么 {@code orderStatus} 在这里、而 {@code memberId} 在 SQL 里</h3>
 *
 * <p>这是本轮最重要的一条设计判断，判据是<b>「判错了会不会变成越权」</b>：
 * <ul>
 *   <li>{@code o.member_id = #{memberId}} 写在 SQL 的 {@code WHERE} 里 ——
 *       判错就等于「能评价别人的订单」，<b>是越权</b>。
 *       所以它必须是最内层、和查询绑死的那一层，<b>永远不能靠 Java 里的 if 兜</b>。
 *       这也是「会员 id 一律用独立 {@code @Param}、绝不做成会被 Spring MVC
 *       自动绑定的 DTO 字段」那条约定的同一个理由。</li>
 *   <li>{@code orderStatus} 拿回 Java 里判 —— 判错只是少了一句友好提示，
 *       <b>不会越权</b>。而放在 Java 里的收益是：<b>分得清两种拒绝</b>——
 *       「这条明细不是你的」（查回来是 null）和「还没确认收货」（状态不是 3）。
 *       两者塞进一条 SQL 的话，都会变成「查不到」，而那个错误信息是不诚实的。</li>
 * </ul>
 * <b>★ 「把条件尽量写进 SQL」这条规则只适用于并发闸门</b> ——
 * 那里的错误信息可以很粗（正常路径根本不会撞上，见 {@code markPaid} 等条件更新）。
 * 而这里两条分支<b>都是用户会正常走到的</b>，错误信息必须说人话。
 *
 * <h3>★ 为什么 {@code JOIN orders} 是 INNER，而管理端订单列表用的是 LEFT JOIN</h3>
 *
 * <p><b>同一个关键字，两个相反的选择，区别在「这一行是用来展示的，还是用来授权的」：</b>
 * <pre>
 *   OrderAdminMapper（LEFT JOIN）→ 用来【展示】。
 *                                  会员记录被硬删了，订单不该从列表里消失。
 *   本类（JOIN，即 INNER）        → 用来【授权】。
 *                                  order_item 指向一张不存在的订单时，
 *                                  我们不应该放行评价 —— 宁可拒绝。
 * </pre>
 * 授权场景下，<b>「查不到」和「不该放行」是同义词</b>，
 * 所以这里不但不介意 INNER JOIN 会滤掉行，甚至<b>依赖</b>它。
 */
@Data
public class ReviewEligibleVO {

    /** 订单明细 id（{@code order_item.id}）—— 就是客户端提交的那个 */
    private Long orderItemId;

    /**
     * 商品 id。
     *
     * <p>★ 落库时 {@code product_review.product_id} <b>取的是这个值</b>，
     * 不是请求体里的值 —— 请求体里根本没有这个字段。
     */
    private Long productId;

    /** 商品名（下单时的快照）。留在这里是为了出错时能给出「请先确认收货再评价 iPhone」这种提示 */
    private String productName;

    /**
     * 这条明细所属订单的状态。取值见 {@code OrderStatus}：
     * 0=待付款 1=已付款 2=已发货 3=已完成 4=已取消
     *
     * <p>★ 只有 {@code 3}（已完成）才允许评价。判断在 Service 里，理由见类注释。
     */
    private Integer orderStatus;

    /**
     * 这一条明细是不是<b>已经退过款</b>（★ 里程碑 17 起）。
     *
     * <h3>★★ 它是【行级】事实，和上面那个【订单级】的状态是两个层次</h3>
     *
     * <p>这是第 17 轮那个中心张力（「按行的售后」对「按订单的状态」）
     * 第四次出现的地方，前三次是：库存归还的两条路径、{@code orders.status = 5}
     * 的聚合、运费的「全退才退」。
     *
     * <p>它带来的具体后果：一张「已完成」的订单里，可能<b>只有一件</b>退了款。
     * 光看 {@code orderStatus = 3} 是看不出这个差别的 ——
     * 而那件退过款的东西显然不该还能评价（东西已经退回去了，评什么）。
     *
     * <h3>★★ 为什么是包装类型 {@code Boolean} 而不是 {@code boolean}</h3>
     *
     * <p>SQL 那一列是 {@code EXISTS(...) AS refunded}，它会回一个 0/1。
     * 用 {@code boolean} 的话，这一列<b>万一</b>回一个 NULL
     * （比如将来有人把它改成 {@code LEFT JOIN} 出来的可空列，
     * 或者换个驱动/映射方式），<b>拆箱会直接 NPE</b> ——
     * 一个「这条明细能不能评价」的判断抛 NPE，排查起来要绕一大圈。
     *
     * <p>★ 所以调用方必须写 {@code Boolean.TRUE.equals(item.getRefunded())}。
     * 那行代码读起来啰嗦，但它表达的是「null 和 false 同样处理」——
     * 而在这个场景里 <b>null 就该按「没退过款」放行</b>：
     * 拿不准的时候，让用户能评价，比让他不能评价更安全
     * （不能评价是用户看得见的功能缺失，而多一条评价只是内容问题）。
     *
     * <h3>★ 它【只挡新增评价】，绝不删已有评价</h3>
     *
     * <p>先评价后退款是正常的事，那条评价<b>留在原地</b>。
     * 评价是用户当时的真实看法，退款不改变「他收到过这件商品并写了评价」
     * 这个历史事实。删掉的结果更坏：<b>商品页的评分会因为一笔退款而悄悄变化</b>，
     * 用户和运营都无从解释。
     */
    private Boolean refunded;
}
