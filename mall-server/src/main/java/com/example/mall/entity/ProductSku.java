package com.example.mall.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 实体 —— 与数据库 {@code product_sku} 表<b>一一对应</b>。
 *
 * <h3>★ 这张表是「价格和库存的唯一真源」</h3>
 *
 * <p>里程碑 15 之前，{@code price} 和 {@code stock} 直接挂在 {@code product} 上，
 * 于是「一件商品有多个规格」这件事<b>在这个模型里根本无法表达</b>：
 * 一件商品只能有一个价格、一个库存。
 *
 * <p>现在的分工是：
 * <pre>
 *   product      管「这是什么商品」     —— 名字、分类、封面、描述、上下架
 *   product_sku  管「卖哪个规格、多少钱、还剩几件」  —— 价格与库存的唯一真源
 * </pre>
 *
 * <p>⚠️ {@code product} 上那两个同名列在阶段 2~5 期间<b>还在</b>，
 * 但它们已经降级成「SKU 的派生汇总」（{@code MIN(price)} / {@code SUM(stock)}），
 * 由 {@code ProductServiceImpl} 在同一个事务里算出来写进去，
 * 只为了让还没改完的旧代码能跑。它们的存在是<b>回滚预案</b>，
 * 删它们要等到阶段 6（{@code migration-13b}）。
 *
 * <h3>★ 无规格的商品也有一条 SKU</h3>
 *
 * <p>「统一模型」这个决定的意思是：<b>每件商品都有 SKU</b>。
 * 没有规格的商品就有一条 {@code spec_json = "[]"} 的「默认 SKU」，
 * 它的 price/stock 就是这件商品的价格和库存。
 *
 * <p>这样做的价值不在于省事，而在于<b>读写两端都只有一条代码路径</b>：
 * 购物车、订单、库存扣减不需要「有规格走这边、没规格走那边」的分支 ——
 * 那种分支正是「只测过一边」的 bug 的产地。
 */
@Data
public class ProductSku {

    private Long id;

    /** 所属商品 id */
    private Long productId;

    /**
     * 规格组合的规范化 JSON，如 {@code [{"name":"颜色","value":"黑"}]}。
     *
     * <p>⚠️ <b>无规格时必须是恰好 {@code "[]"}，不能是 {@code ''}，更不能是 NULL。</b>
     * 这条不是洁癖，是 {@code uk_product_spec (product_id, spec_json)}
     * 这条唯一索引能生效的前提 —— MySQL 把多个 NULL 当成互不相等，
     * 允许 NULL 就是允许同一件商品插出两条「默认 SKU」。
     *
     * <p>⚠️ 它的值必须由 {@code SpecJson.canonical()} 产出，不要手工拼字符串。
     * 理由见 {@code SpecJson} 的类注释。
     */
    private String specJson;

    /**
     * 售价（元）。
     *
     * <p>金额一律 BigDecimal，不用 double —— 理由见 {@code Product.price} 的注释。
     */
    private BigDecimal price;

    /** 库存数量。扣减走 {@code decreaseSkuStock} 的条件 UPDATE，不先查后判 */
    private Integer stock;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
