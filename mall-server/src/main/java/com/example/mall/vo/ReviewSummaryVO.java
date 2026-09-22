package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 某个商品的评价聚合信息 —— 详情页顶上那块「4.7 分 / 共 128 条评价 / 星级分布」。
 *
 * <h3>★★ 零评价时它不是 null，是一个「一行零值」的对象</h3>
 *
 * <p>查询是这么写的（{@code ProductReviewMapper.xml}）：
 * <pre>
 *   SELECT COUNT(*) AS total, COALESCE(AVG(rating), 0) AS avgRating, ...
 *   FROM product_review WHERE product_id = #{productId}
 * </pre>
 * <b>不带 {@code GROUP BY} 的聚合函数永远返回且只返回一行</b> ——
 * 即使一条匹配的行都没有。所以「这个商品一条评价都没有」
 * 会得到一个 {@code total=0, avgRating=0, count5..count1 全为 0} 的对象，
 * <b>Service 里不需要判空，前端也不需要判空。</b>
 *
 * <p>★ 这一点值得和 {@code selectShopById} 对照着记：
 * 同一个 Mapper 里，<b>「查不到就是 null」和「查不到是一行零值」
 * 是两种完全不同的形状</b>，取决于那条 SQL 是不是聚合查询。
 * 写聚合查询的时候，判空的代码是<b>多余的</b>；
 * 写普通查询的时候，忘了判空就是 NPE。<b>形状由 SQL 决定，不由习惯决定。</b>
 *
 * <h4>★★ 为什么 {@code avgRating} 和那几个 {@code countN} 全都包了 {@code COALESCE}？</h4>
 *
 * <p>因为<b>在零行上，三个聚合函数的返回值只有一个是「合理」的</b>：
 * <pre>
 *   COUNT(*)                    → 0      ← 合理，这个语义本来就是 0
 *   AVG(rating)                 → NULL   ← 让人意外
 *   SUM(CASE WHEN ... THEN 1 ELSE 0 END) → NULL   ← 更意外，明明是「没有 5 星」即 0
 * </pre>
 * 产生这个差别的道理其实很直白：{@code COUNT} 数的是「有几行」（0 行就是 0），
 * 而 {@code AVG} / {@code SUM} 算的是「那些行上的值」——
 * <b>一行都没有时，它们连一个值都没见过，所以给不出 0，只能给 NULL。</b>
 *
 * <p>不包 COALESCE 的后果不是「值不对」，而是<b>更糟的一种</b>：
 * 前端拿到的是 {@code "avgRating": null}，而全局 Jackson 配了
 * {@code default-property-inclusion: non_null} —— <b>这个键会整个从 JSON 里消失</b>，
 * 前端读到 {@code undefined}，模板里渲染出 `undefined 分`。
 * 没有报错、没有日志，只有一个奇怪的字。
 *
 * <p>★ 所以这里五个字段全都包了 COALESCE，<b>尽管「零评价时前端不会去读分布」
 * 这个假设今天成立</b>。理由：那是一个<b>关于前端代码的假设</b>，
 * 而不是一个关于数据的事实 —— 前端改一行就可能把它推翻，
 * 而推翻之后表现出来的症状（键消失）和真实原因（聚合函数返回 NULL）
 * 相距极远。**在 SQL 里多写几个字，把「永远是数字」这件事变成数据本身的保证，
 * 比依赖调用方的小心便宜得多。**
 *
 * <h3>★ 为什么 {@code avgRating} 是 BigDecimal 而不是 double</h3>
 *
 * <p>本项目对「会参与计算的小数」一律用 {@code BigDecimal}
 * （见 {@code Product.price}、{@code Order.totalAmount}）——
 * {@code double} 的二进制浮点表示会让 4.7 变成 4.699999999999999。
 * 星级平均分虽然只是个显示值，但让「金额用 BigDecimal、评分用 double」
 * 这种分裂存在，下一个人就得每次想一下「这个字段为什么不一样」。
 *
 * <h3>★★ 这里【不做】四舍五入</h3>
 *
 * <p>{@code AVG(rating)} 的原值是 4 位小数（{@code 4.6667}）。
 * SQL 里<b>不</b>写 {@code ROUND(..., 1)}，Service 里也不 round ——
 * 四舍五入到 1 位是<b>展示层的事</b>，和「金额只用 {@code .toFixed(2)} 格式化」
 * 是同一条规矩。
 *
 * <p>理由不是洁癖：<b>在 SQL 里丢掉精度之后，前端就再也算不了别的了。</b>
 * 而前端将来很可能想「同时显示 4.7 和 93% 好评率」——
 * 后者需要原始值。★ 反过来也成立：前端要显示 4.7 时自己 {@code toFixed(1)}
 * 只花一行代码，而服务端提前 round 掉的精度是<b>补不回来的</b>。
 *
 * <h3>★ 命名是 {@code count5}…{@code count1}，不是「五星数」这种中文键</h3>
 *
 * <p>因为前端要画 5 行分布条，循环写起来是
 * {@code summary[`count${star}`]} —— <b>键名可推算</b>才有这个写法。
 * 如果用 {@code fiveStarCount} 之类的名字，前端就得写一个 5 分支的映射表，
 * 而那个表在加第 6 种状态时会被漏改。
 */
@Data
public class ReviewSummaryVO {

    /** 评价总条数。零评价时为 0（{@code COUNT} 在零行上返回 0，不是 NULL） */
    private long total;

    /**
     * 平均分，零评价时为 0（靠 SQL 里的 {@code COALESCE} 保证）。
     *
     * <p>⚠️ <b>不四舍五入</b>，原样给到 4 位小数 —— 理由见类注释。
     */
    private BigDecimal avgRating;

    /** 5 星有几条。★ 键名可推算（{@code count${star}}），前端才能循环画分布条 */
    private Integer count5;

    private Integer count4;

    private Integer count3;

    private Integer count2;

    private Integer count1;
}
