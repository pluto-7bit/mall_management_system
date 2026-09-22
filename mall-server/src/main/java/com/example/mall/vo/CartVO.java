package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 整个购物车。
 *
 * <p>为什么要有这个「外层包装」，而不是直接返回 {@code List<CartItemVO>}？
 *
 * <p>因为购物车需要展示的不只是商品列表，还有两个<b>汇总数字</b>：
 * 共几件商品、合计多少钱。这两个数的归属很微妙：
 *
 * <pre>
 *   前端自己算：items.reduce((s, i) => s + i.subtotal, 0)
 *   后端返回：  totalAmount: 1234.56
 * </pre>
 *
 * <p>哪种对？<b>如果是「展示」就用前端算，如果是「要拿去用的」就必须后端算。</b>
 *
 * <p>这里的 {@code totalAmount} 只用来显示「合计：¥xxx」，
 * 理论上让前端 reduce 一下也行。但绕过前端直接下单时，
 * 金额必须由后端重新算 —— 所以后端反正要算一遍，
 * 那就顺便返回给前端，省得两边各算一次还可能算出不同结果
 * （前端浮点数相加会有精度问题，{@code 0.1 + 0.2 = 0.30000000000000004}）。
 *
 * <p><b>★ 一句话记住：金额永远用 BigDecimal 在后端算，永远别相信前端传来的金额。</b>
 * 里程碑 8 下单时会重点讲这个。
 */
@Data
public class CartVO {

    /** 购物车里的商品条目，包含失效商品（由每个条目的 available 字段标记） */
    private List<CartItemVO> items;

    /**
     * 商品总件数 = 所有条目的数量之和。
     *
     * <p>用来在顶部导航栏的购物车图标上显示角标（比如「购物车(3)」）。
     *
     * <p>注意是<b>件数</b>不是<b>行数</b>：加 3 瓶水和 2 包纸，
     * 应该显示 5 而不是 2。这个区别在实现时很容易搞混 ——
     * {@code items.size()} 是行数，{@code sum(quantity)} 才是件数。
     */
    private Integer totalQuantity;

    /**
     * 合计金额。
     *
     * <p><b>只算「还能买」的商品</b>（available = true）。
     * 失效商品不计入 —— 不然用户会看到一个包含下架商品的天价合计，
     * 然后结算时金额对不上，非常困惑。
     *
     * <p>购物车为空、或者全是失效商品时是 {@code 0.00} 而不是 null，
     * 让前端少写一个判空分支。
     */
    private BigDecimal totalAmount;
}
