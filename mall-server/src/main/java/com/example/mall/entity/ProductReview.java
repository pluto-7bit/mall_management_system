package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品评价实体 —— 与数据库 {@code product_review} 表一一对应。
 *
 * <p>一行 = 某个会员对某个订单明细的一次评价。
 *
 * <h3>★★ 这张表真正的内容只有三样东西</h3>
 *
 * <p>剥掉主键和时间，{@code product_review} 只存了：
 * <pre>
 *   order_item_id  → 评的是哪一条明细（★ 唯一，见下）
 *   rating         → 打了几星
 *   content        → 说了什么
 * </pre>
 * {@code product_id} / {@code member_id} 这两列是<b>从 {@code order_item_id} 推出来的</b>，
 * 是刻意的冗余。理由见 {@code sql/migration-12-review.sql} 头部那段论证，
 * 一句话：<b>源头（order_item）不可变，所以冗余没有代价</b>，
 * 而这两列都有确定的、高频的读者（详情页聚合 / 管理端筛选）。
 *
 * <h3>★★★ {@code orderItemId} 上有一个 UNIQUE 索引 —— 这是本轮的承重墙</h3>
 *
 * <p>用户对评价的选择是<b>「不能改也不能删，一次定终身」</b>，
 * 而「一条明细只能有一条评价」这件事<b>只有数据库能真的保证</b>：
 * {@code ProductReviewServiceImpl.create} 里那句「先查有没有评过」<b>挡不住并发</b> ——
 * 两个请求可以同时查到「没评过」，然后两个都往下走。
 *
 * <p>这和里程碑 8 的下单幂等键 {@code uk_member_idempotency (member_id, idempotency_key)}
 * 是同一个手法：<b>把「不可能发生」交给数据库。</b>
 * 代价是一个索引，收益是这个不变量<b>永远</b>成立 ——
 * 不管将来谁写了什么代码、绕过哪一层、并发有多高。
 *
 * <p>⚠️ 所以这个实体里的 {@code orderItemId} <b>不是</b>一个普通的关联字段，
 * 它同时是「这条评价的身份」。任何想「改一下 orderItemId」的念头都是错的。
 *
 * <h3>★ 为什么评价没有 update 语义，因此也没有 updateTime</h3>
 *
 * <p>「一次定终身」意味着这张表<b>只有 INSERT 和 DELETE</b>，
 * 没有 UPDATE。没有 UPDATE 就不需要 {@code update_time} ——
 * 一个永远等于 {@code create_time} 的列是纯噪音。
 *
 * <p>这也是它和 {@code product} / {@code orders} 的对照：
 * 那两张表都有 {@code update_time}，因为它们真的会被修改。
 * <b>列的有无应该跟着「这个对象会不会变」走，而不是跟着「别的表都有一列」走。</b>
 *
 * <h3>★ 为什么晒图在另一张表，而不是 {@code image1/2/3} 三个列</h3>
 *
 * <p>两个理由，任何一条都足够：
 * <ol>
 *   <li><b>列版本把「最多 3 张」焊死进了表结构。</b>
 *       业务哪天说「改成 5 张」，那就是一次 schema 变更 ——
 *       而这件事本来和表结构毫无关系。</li>
 *   <li>本项目的既有约定是<b>不存分隔字符串</b>（见 {@link OrderItem} 那段
 *       「为什么明细是独立一张表」）。多张图不是「一个值」，是一组值。</li>
 * </ol>
 *
 * <p>所以形状直接照 {@code product} + {@code product_image} 来：
 * 一张主表 + 一张不定长的子表。{@link ProductReviewImage}。
 *
 * <h3>★ 这张表和 product / member 之间没有外键</h3>
 *
 * <p>全库一致的约定（10 张表一个 FOREIGN KEY 都没有），详细理由写在
 * {@code sql/mall.sql} 的「全库约定」注释里。一句话：
 * <b>加外键之后「删商品」会变成一条可能失败的语句，
 * 而数据库约束拦下来的错误只能翻译成一句「系统繁忙」。</b>
 *
 * <p>⚠️ 代价是<b>删商品时的顺序必须自己守</b>，而且这次是<b>四级</b>：
 * <pre>
 *   晒图 → 评价 → 图集 → 商品
 * </pre>
 * 一层都不能反。见 {@link com.example.mall.service.impl.ProductServiceImpl#delete}。
 */
@Data
public class ProductReview {

    private Long id;

    /**
     * 被评价的订单明细 id —— <b>★ 数据库上有 UNIQUE 索引</b>。
     *
     * <p>见类注释。它不是普通的关联字段，它是这条评价的身份。
     */
    private Long orderItemId;

    /**
     * 商品 id。<b>服务端从订单明细推导出来的，不是客户端传的。</b>
     *
     * <p>⚠️ 这一点是<b>安全边界</b>而不是风格：请求体 {@code ReviewSaveDTO} 里
     * 根本没有这个字段。如果让客户端报 {@code productId}，
     * 他就可以「评价 A 商品的订单，把评价挂到 B 商品上」。
     * <b>客户端报什么商品一概不信。</b>
     */
    private Long productId;

    /**
     * 评价会员 id。<b>从 JWT 里取的，同样不是客户端传的。</b>
     *
     * <p>⚠️ 它和 {@code OrderMapper} 那条「会员 id 一律用独立 {@code @Param}、
     * 绝不做成会被 Spring MVC 自动绑定的 DTO 字段」的约定是同一条：
     * 一个能被请求参数绑定的 {@code memberId}，就一定能被改。
     */
    private Long memberId;

    /**
     * 评分：1~5 星。
     *
     * <p>★ 数据库的 {@code rating} 是 TINYINT，没有 CHECK 约束 ——
     * 越界的拦截在 DTO 的 {@code @Min(1) @Max(5)} 上。
     * 为什么不加 CHECK：{@code uk_order_item} 已经开了「让数据库兜底」的先例，
     * 但两者的区别在于<b>「这件事是不是并发问题」</b>。
     * 「一条明细只能评一次」并发挡不住，所以必须靠索引；
     * 「rating 在 1~5 之间」不是并发问题，校验注解就够了，
     * 而且校验失败还能给出「评分最低 1 星」这种说得清的错误信息。
     */
    private Integer rating;

    /** 评价内容，最多 500 字（{@code @Size(max = 500)} 和列的宽度对齐） */
    private String content;

    /**
     * 评价时间。
     *
     * <p>★ 由数据库的 {@code DEFAULT CURRENT_TIMESTAMP} 填，不由 Java 填 ——
     * 理由和 {@code orders} 的 {@code create_time} 一样：
     * 多台应用服务器时钟不一致时，「评价时间早于下单时间」这种诡异数据
     * 一旦写进去就再也解释不清了。<b>时间一律交给数据库的时钟。</b>
     */
    private LocalDateTime createTime;
}
