package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表/详情的返回对象（VO）。
 *
 * <p>比 {@link com.example.mall.entity.Product} 多了一个
 * {@code categoryName} 字段。这就是 Entity 和 VO 必须分开的直接原因：
 *
 * <p>{@code product} 表里只有 {@code category_id}，没有分类名。
 * 但前端表格里要显示「手机数码」而不是「1」，
 * 所以查询时用 {@code LEFT JOIN category} 把名字带出来。
 * 这个字段在数据库里不存在，Entity 装不下，只能放在 VO 里。
 *
 * <p><b>另一种做法</b>是前端拿到 categoryId 后再调一次接口查分类列表，
 * 自己在内存里对应。但那样就是典型的 N+1 问题：查一次商品要再发一次请求，
 * 列表长了会明显变慢。一次 join 查完更划算 —— 这也是 SQL 该干的活。
 *
 * <p>⚠️ <b>★ 这个类里【没有】商品图集（{@code images}），这是刻意的</b>，
 * 不是漏了 —— 里程碑 11 加图集时专门讨论过这件事：
 *
 * <ul>
 *   <li>{@code ProductVO} 现在被<b>多处共用</b>：管理端列表、管理端详情
 *       （走子类 {@link AdminProductDetailVO}）、用户端的一部分接口。
 *       往一个被多处引用的 VO 里加字段，是「伤到别人的最经典方式」——
 *       加的人只看了自己那一处，而字段会跟着 JSON 出现在所有引用它的地方。</li>
 *   <li>更要紧的是<b>有读者才加字段</b>：图集的读者是<b>编辑弹窗和商品详情页</b>，
 *       不是列表表格。放进这里，管理端每翻一页就要多查一遍图集
 *       （要么 N+1，要么给列表 SQL 加 join），而列表根本不显示它。</li>
 * </ul>
 *
 * <p>所以两个端的详情各自加在自己的<b>详情 VO</b> 上：
 * {@link AdminProductDetailVO}（管理端）和 {@link ShopProductDetailVO}（用户端），
 * 两个列表 VO 一个字段都不加。
 *
 * <p>⚠️ 另外，{@code cover}（封面图）和图集是<b>两块独立的东西</b>，
 * 不是「图集的第一张」：{@code cover} 从建表起就是运营手填的一个字符串
 * （可以是外链、可以是老的 {@code /images/*.svg}），
 * 而图集是上传产生的、受「只接受 {@code /uploads/} 前缀」的约束。
 * 前端有一个「设为封面」按钮在两者之间做<b>显式</b>的单向同步，
 * 但数据库层面它们没有任何自动关联。
 */
@Data
public class ProductVO {

    private Long id;

    private Long categoryId;

    /** ★ join 出来的分类名称，数据库 product 表里没有这一列 */
    private String categoryName;

    private String name;

    private BigDecimal price;

    private Integer stock;

    private String cover;

    private String description;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
