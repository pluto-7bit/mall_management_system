package com.example.mall.common;

/**
 * 订单来源 —— 这笔订单是从哪条路来的。
 *
 * <p>只有两个取值，对应两个下单入口：
 * <pre>
 *   CART     购物车结算   POST /api/shop/orders
 *   BUY_NOW  立即购买     POST /api/shop/orders/buy-now
 * </pre>
 *
 * <h3>★ 为什么要有这个东西？两个接口各写各的不行吗？</h3>
 *
 * <p>因为这两条路的<b>差异其实很小，而共同点很多</b>：
 *
 * <table border="1">
 *   <tr><th>步骤</th><th>购物车结算</th><th>立即购买</th></tr>
 *   <tr><td>校验地址归属</td><td>一样</td><td>一样</td></tr>
 *   <tr><td>查商品、取快照</td><td>一样</td><td>一样</td></tr>
 *   <tr><td>算金额</td><td>一样</td><td>一样</td></tr>
 *   <tr><td>扣库存（防超卖）</td><td>一样</td><td>一样</td></tr>
 *   <tr><td>写订单 + 明细</td><td>一样</td><td>一样</td></tr>
 *   <tr><td>幂等处理</td><td>一样</td><td>一样</td></tr>
 *   <tr><td><b>数量从哪来</b></td><td>读 Redis 购物车</td><td>DTO 里传的</td></tr>
 *   <tr><td><b>下单后要不要清购物车</b></td><td>要（清掉已结算的那几件）</td><td>不要（压根没碰过购物车）</td></tr>
 * </table>
 *
 * <p><b>★ 只有最后两行不同。</b>如果写成两个方法，那前面六步
 * 就要复制两份 —— 而「扣库存防超卖」这段最容易写错、
 * 最需要保证只有一份（哪份改了另一份没改，就可能出现超卖漏洞）。
 *
 * <h3>所以结构是：一个内部方法 + 两个薄薄的入口</h3>
 *
 * <pre>
 *   ShopOrderController
 *     ├─ POST /orders          ─→ OrderService.createFromCart(dto)
 *     └─ POST /orders/buy-now  ─→ OrderService.createByBuyNow(dto)
 *                                      │
 *                                      └─→ 都在这里转成
 *                                          create(source, 商品行, ...)   ← 真正干活的，只有一份
 * </pre>
 *
 * <p>所以这个枚举的作用是：<b>把那 2 行差异显式地传给那个唯一的方法，
 * 让方法内部能根据它决定「数量从哪来、要不要清购物车」。</b>
 *
 * <p><b>这是「重复代码」处理里很常见的一个模式：
 * 不要急着抽公共方法，先看清楚差异有几处。
 * 差异只有一两处时，把它做成参数，主体合一 ——
 * 这比「两个方法长得像但各改各的」安全得多。</b>
 *
 * <p>⚠️ 反过来说：如果哪天两条路的差异变成了五六处，
 * 就该考虑拆开了 —— 硬凑的抽象比重复更糟。
 * <b>抽象的成本是「以后每个改动都要考虑所有分支」，差异一多就付不起了。</b>
 */
public enum OrderSource {

    /** 购物车结算：数量来自 Redis，下单成功后要清掉已结算的商品 */
    CART("购物车结算"),

    /** 立即购买：数量来自请求参数，不碰购物车 */
    BUY_NOW("立即购买");

    private final String text;

    OrderSource(String text) {
        this.text = text;
    }

    /** 给日志用 */
    public String getText() {
        return text;
    }
}
