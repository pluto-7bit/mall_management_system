package com.example.mall.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 购物车结算请求。
 *
 * <p>{@code POST /api/shop/orders}
 *
 * <h3>★ 为什么请求里只有商品 id，没有数量？</h3>
 *
 * <p>这是这个 DTO 最需要理解的地方。购物车结算时前端传的是：
 * <pre>
 *   { "productIds": [3, 7], "addressId": 1, "idempotencyKey": "..." }
 * </pre>
 * 而<b>不是</b>：
 * <pre>
 *   { "items": [ {"productId": 3, "quantity": 2}, {"productId": 7, "quantity": 1} ], ... }
 * </pre>
 *
 * <p>区别在哪？数量。
 *
 * <p>购物车存在 Redis 里，是服务端的数据。<b>服务端自己就能读，
 * 为什么还要让客户端传一遍？</b>
 * 一旦客户端能传数量，那么：
 * <pre>
 *   车里 2 件，客户端传 quantity = 1  → 少付钱多拿货
 *   车里 1 件，客户端传 quantity = 999 → 触发本不该通过的库存校验分支
 * </pre>
 * 而且更隐蔽的问题是：客户端的数量和 Redis 里的数量<b>可能不一致</b>
 * （他在另一个标签页改了数量），那以谁为准？
 *
 * <p><b>★ 判断一个字段该不该由客户端传，标准是「这件事是不是只有客户端知道」。</b>
 * <ul>
 *   <li>「买了哪几种商品」—— 只有客户端知道（他勾选了哪几个）✅ 要传</li>
 *   <li>「每种买几件」—— 服务端从 Redis 读就有，客户端不必也不能传 ❌ 不传</li>
 * </ul>
 *
 * <p>购物车里的数量只有一个真相来源：<b>Redis</b>。
 * 前端传它，就是给同一个事实造了第二个来源，两个来源迟早会打架。
 *
 * <h3>★ 那 {@code productIds} 是干什么的？</h3>
 *
 * <p>因为用户可能<b>只勾选了购物车里的部分商品</b>结算。
 * 车里 5 种商品，只买其中 2 种，是很常见的操作。
 *
 * <p>所以 {@code productIds} 表达的是<b>「要结算哪些」</b>这个选择，
 * 而不是「每种买几件」这个数据。
 *
 * <p>服务端拿到它之后做的事是：
 * <pre>
 *   1. 读 Redis 里该会员的整个购物车
 *   2. 取出 productIds 里指定的那几种（顺便校验：传的 id 真的在车里吗？）
 *   3. 数量直接用 Redis 里的值
 * </pre>
 * 也就是 <b>{@code productIds} 只是一个「过滤器」，不是一个「数据来源」。</b>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CartOrderDTO extends OrderBaseDTO {

    /**
     * 要结算的商品 id 列表。
     *
     * <p>{@code @NotEmpty}：一个都不选就点结算，应该被拦下。
     * 服务端也会再判一次（前端拦是为了体验，服务端拦才是为了正确）。
     *
     * <p>⚠️ 这里<b>不校验每个元素是不是正数</b> —— 那属于业务判断
     * （「这个 id 的商品真的存在吗、是不是在购物车里」），
     * 要查库才知道，放在 Service 里做。DTO 只保证「给了一个非空列表」。
     *
     * <p>也不要在这里加 {@code @Size(max = 50)} 之类的「一次最多结算多少种」
     * 限制：本项目没有这条业务规则，凭空加一个只会限制用户，
     * 而且那个数字将来同样会变成「哪一层说了算」的疑问。
     * <b>只加真的有意义的约束。</b>
     */
    @NotEmpty(message = "请至少选择一件商品")
    private List<Long> productIds;
}
