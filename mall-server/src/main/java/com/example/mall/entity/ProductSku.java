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

    /**
     * 划线价（原价/市场价），★ 里程碑 16 新增。{@code null} 表示商家没设。
     *
     * <p>★ <b>它是「缺席」而不是「0」</b>：数据库那列是 {@code DEFAULT NULL}，
     * 不是 {@code NOT NULL DEFAULT 0} —— 判据（以及它为什么和
     * {@code category.parent_id} 的选择故意相反）见 {@code migration-14b} 头部。
     * 简单说：{@code parent_id = 0} 只是一个「没有父」的标记，
     * 而 {@code market_price = 0} 会被拿去和售价比大小。
     *
     * <p>⚠️ <b>它不需要「大于 0」这条校验</b>：保存时有一条更强的规则
     * 「{@code marketPrice} 必须大于 {@code price}」，而 {@code price} 本身
     * 有 {@code >= 0.01} 的下界，所以这条已经把它盖住了。
     * 再加一条 {@code @DecimalMin} 就是同一个事实的第二个定义。
     *
     * <p>★ 展示规则是「{@code marketPrice > price} 时才画删除线」，
     * 那条判断在<b>前端</b>（后端只给数、不给 {@code showDiscount} 布尔位）。
     * ⚠️ 代价是「填错的划线价会被静默吃掉」，所以保存时有一条 400 拦住它 ——
     * 而且那条校验必须按<b>入库时的舍入</b>比大小，见 {@code ProductServiceImpl.planSkus}。
     */
    private BigDecimal marketPrice;

    /**
     * 成本价（进货价），★ 里程碑 16 新增。{@code null} 表示商家没填。
     *
     * <p>★★ <b>它是本项目唯一一条「列存在、但绝不能出用户端」的字段。</b>
     * 边界不在 SQL（{@code ProductSkuMapper} 的三个查询都照常查它），
     * 而在 VO 的继承树上：
     * <pre>
     *   SkuVO          公共字段（用户端也看得到）—— 【没有成本】
     *    ├─ ShopSkuVO  用户端出口（/api/shop/skus/{id}、购物车）
     *    └─ AdminSkuVO 管理端出口 —— 在这里加 costPrice / grossMargin
     * </pre>
     *
     * <p>⚠️ 所以<b>任何时候都不要把这个字段（或它的两个派生量）挪到 {@link com.example.mall.vo.SkuVO} 上</b>：
     * {@code ShopSkuVO extends SkuVO}，挪上去就等于让
     * {@code /api/shop/skus/{id}} <b>匿名</b>泄漏成本价 ——
     * 一行改动、零编译错误、不用登录就能读到。
     * 守着这条的是 {@code sql/test-price.py} 的 E 组（<b>双向</b>：
     * 用户端一个都不许有、管理端必须有）。
     *
     * <p>★ 唯一的业务校验是 {@code >= 0}（一条协议层规则，写在 {@code SkuSaveDTO} 上）。
     * <b>「成本价高于售价」（亏本卖）是被允许的</b> —— 那是真实存在的生意状态，
     * 只该在管理端标红，不该被拒绝。
     */
    private BigDecimal costPrice;

    /** 库存数量。扣减走 {@code decreaseSkuStock} 的条件 UPDATE，不先查后判 */
    private Integer stock;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
