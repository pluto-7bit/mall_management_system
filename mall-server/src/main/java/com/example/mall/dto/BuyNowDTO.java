package com.example.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 「立即购买」请求。
 *
 * <p>{@code POST /api/shop/orders/buy-now}
 *
 * <h3>★ 和 {@link CartOrderDTO} 唯一的区别：数量由谁提供</h3>
 *
 * <pre>
 *   购物车结算：买了哪几种 ✅ 客户端说   /  每种几件 ❌ 服务端从 Redis 读
 *   立即购买  ：买哪一个   ✅ 客户端说   /  买几件   ✅ 客户端说
 * </pre>
 *
 * <p>为什么这里数量可以由客户端传？因为<b>「立即购买」根本不经过购物车</b>，
 * 服务端没有任何地方存着这个数量。用户想买 3 件，这个意图
 * 只存在于他刚才在商品详情页填的那个数字里 —— 服务端无从得知。
 *
 * <p><b>★ 这两条路的差别，正好说明了一条判断标准：
 * 数量该不该由客户端传，取决于「服务端有没有别的真相来源」，
 * 而不是「哪个接口更规范」。</b>
 * 有可信来源就用来源（购物车），没有就让客户端说（立即购买），
 * 然后<b>无论走哪条路，都要在下单时用库存去验一遍</b>。
 *
 * <h3>★ 立即购买不碰购物车</h3>
 *
 * <p>一个很常见的误解是「立即购买 = 加进购物车再结算」。
 * 这里刻意<b>不</b>这么做，因为那会带来一个用户不想要的结果：
 * <pre>
 *   用户在商品页点了「立即购买」，买完发现购物车里多了一件同样的商品。
 * </pre>
 * 用户没打算把它加进购物车，就不该加。所以「立即购买」是
 * <b>完全绕开购物车、直接生成订单</b>的一条独立路径 ——
 * 它下单成功后也不去清理购物车（因为压根没动过）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BuyNowDTO extends OrderBaseDTO {

    /**
     * 要购买的商品 id。
     *
     * <p>只能用 {@code @NotNull}：它是数字，不是字符串。
     */
    @NotNull(message = "请选择商品")
    private Long productId;

    /**
     * 购买数量。
     *
     * <p>{@code @Min(1)}：买 0 件或负数没有意义。
     * 这一类「显然荒谬」的检查放在 DTO 里是合适的。
     *
     * <p><b>★ 那 {@code @Max(999)} 为什么不是 99？</b>
     * 这个坑在里程碑 7 已经踩过一次了，见 {@code CartQuantityDTO} 的注释：
     * 如果这里写 99，那么请求会被<b>参数绑定阶段</b>拒掉（错误码 400），
     * 而 Service 里判 99 上限的代码永远执行不到，成了死代码；
     * 而且同一个用户动作（买 100 件）会因为「有没有经过前端」而得到
     * 两个不同的错误码（400 / 1008）。
     *
     * <p>所以分工还是那一条：
     * <pre>
     *   DTO     → 协议层合理性（必须是正数、不能是 999999999 这种明显乱填的）
     *   Service → 业务规则（{@code BusinessRules.MAX_QUANTITY_PER_ITEM}、库存）
     * </pre>
     */
    @NotNull(message = "请填写购买数量")
    @Min(value = 1, message = "购买数量至少为 1")
    @Max(value = 999, message = "数量超出合理范围")
    private Integer quantity;
}
