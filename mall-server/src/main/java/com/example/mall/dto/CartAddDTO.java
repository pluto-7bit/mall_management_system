package com.example.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 「加入购物车」的请求体。
 *
 * <p>{@code POST /api/shop/cart/items}
 * <pre>{ "productId": 5, "quantity": 2 }</pre>
 *
 * <h3>★ 注意这里<b>没有</b> price 字段，这是刻意的</h3>
 *
 * <p>如果前端把价格传上来、后端直接用，那用户改一下请求就能一块钱买 iPhone。
 * <b>凡是「金额」和「身份」，一律由服务端自己确定，绝不用客户端传来的值。</b>
 *
 * <p>这个 DTO 只有两个字段，但两个字段都必须是客户端给的 ——
 * 因为「买哪个商品、买几件」确实是客户端的意图，服务端猜不出来。
 * 而「多少钱、算在谁头上」服务端自己知道，所以不需要也不允许客户端说。
 *
 * <p><b>判断一个字段该不该由客户端传，就问：这件事是不是只有客户端知道？</b>
 */
@Data
public class CartAddDTO {

    /**
     * 商品 id。
     *
     * <p>{@code @NotNull} 是必须的 —— 不传的话 Service 里就是 null，
     * 后面查库、拼 Redis key 都会出问题。
     *
     * <p>注意这里<b>没有</b> {@code @Min(1)}。因为「id 是不是正整数」
     * 和「这个商品存不存在、有没有上架」是两件事，
     * 而后者只能查库才知道。与其在校验注解里做一半，
     * 不如统一交给 Service 的查询结果来判定 —— 反正都要查库。
     */
    @NotNull(message = "商品不能为空")
    private Long productId;

    /**
     * 要加入的数量。
     *
     * <p>{@code @Min(1)} 是常识性防御：加 0 件、加 -3 件都没有意义。
     * 特别注意<b>不能允许负数</b> —— 虽然「加 -3 件」看起来等价于「减 3 件」，
     * 但那是<b>另一个接口</b>（修改数量）该干的事。
     * 让一个接口表达两种意图，迟早会有人误用。
     *
     * <p>{@code @Max(999)} 是<b>防呆</b>，不是业务上限。
     * 它挡的是「quantity: 999999999」这种明显在乱填的请求 ——
     * 这种值会让 {@code quantity × price} 变成一个天文数字。
     * 真正的「一个商品最多买 99 件」在
     * {@code CartServiceImpl.MAX_QUANTITY_PER_ITEM}，返回业务码 1008。
     *
     * <p>为什么这里不直接写 {@code @Max(99)}？见
     * {@link CartQuantityDTO#quantity} 的注释 —— 简单说就是
     * <b>同一条业务规则写在两个地方，迟早会不一致，
     * 而且会让 Service 里的那份变成永不执行的死代码。</b>
     *
     * <p><b>★ 这里有个容易忽略的点：参数校验只能挡住单个字段的荒谬值，
     * 挡不住「合法值组合起来很荒谬」。</b>
     * 比如两次请求各加 60 件，单次都合法，合起来就是 120 件。
     * 所以字段校验之外，Service 里还得有业务层面的上限 ——
     * 而那个上限才是用户真正会撞到的那条线。
     */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 999, message = "数量超出合理范围")
    private Integer quantity;
}
