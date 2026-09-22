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
}
