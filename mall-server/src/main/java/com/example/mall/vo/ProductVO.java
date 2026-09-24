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

    // ==========================================================================
    // ★★ 里程碑 15 阶段 6：price / stock 两个字段【已从这里删除】。
    //
    //   它们从阶段 2 起就已经降级成「派生汇总」，留着只是为了两件事：
    //     ① 回滚预案（阶段 2~5 期间还有旧代码在读 product 的那两列）
    //     ② test-frontend-contract.py 的 require_keys 把这两个键钉在响应里
    //   两件事都在阶段 6 一起收掉了。
    //
    //   ★ 值得记一笔的是②：**契约测试既是「防改动」的，也是「挡改动」的。**
    //     它把两个键钉死，于是「删掉这两个字段」这件事必须
    //     先改测试、再改代码 —— 顺序是对的（先想清楚契约变成什么样），
    //     但它在整个阶段 2~5 期间都是「不动这两个字段」的一个理由。
    //     一个断言的价值在于它拦住了什么；**代价是它也拦住了你。**
    //
    //   ⚠️ 现在价格只有一个来源：{@link #minPrice}。前端读不到别的东西，
    //     所以「改动价格取法时两边不一致」这类 bug 在这个 VO 上不会再发生。
    // ==========================================================================

    /**
     * 起售价 —— 这件商品所有规格里最便宜的那个价格（★ 里程碑 15 新增）。
     *
     * <p><b>为什么价格要显示「起」？</b>因为一件商品有多个规格时
     * 它<b>没有单一的价格</b>。列表上写 5999 会让用户以为每一档都是 5999，
     * 点进去发现 256G 是 7999 —— 那是一种廉价但真实的欺骗。
     * 前端在 {@code skuCount > 1} 时会在这个数字后面跟一个「起」字。
     *
     * <p>⚠️ 它是 {@code MIN}，所以和 {@code sort=price_asc} 的排序依据
     * <b>必须是同一个值</b>，否则会出现「用户看到起售价 4999，
     * 但排序按另一个数排」—— 一个看起来像分页坏了的 bug。
     * 两边都取 {@code MIN(price)} 天然一致，改动价格取法时最容易破坏这一点。
     */
    private BigDecimal minPrice;

    /**
     * 这件商品所有规格的库存合计（★ 里程碑 15 新增）。
     *
     * <p>⚠️ <b>它不能用来判断「这个商品能不能买」</b>。
     * 真正要判断的是<b>用户选中的那个规格</b>有没有货 ——
     * 总库存 5 而「白色」那一档恰好是 0 是很常见的。
     * 拿合计去控制加购按钮，会让用户能点、能提交、然后失败。
     *
     * <p>它的正经用途是管理端列表的「缺货」标签：
     * {@code totalStock === 0} 才代表这件商品真的完全没货了。
     */
    private Integer totalStock;

    /**
     * 这件商品有几个规格（★ 里程碑 15 新增）。
     *
     * <p>是 {@code COUNT(*)}，所以一件没有任何 SKU 的商品是 <b>0</b>
     * 而不是 null —— 「0 个规格」是个真答案，不是缺失的数据。
     *
     * <p>前端拿它做三件事：{@code > 1} 时价格后面跟「起」；
     * 商城首页用它决定按钮是「加入购物车」还是「选规格」；
     * 编辑页用它判断要不要显示规格矩阵。
     */
    private Integer skuCount;

    private String cover;

    private String description;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
