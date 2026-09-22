package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户端商品详情（VO）。
 *
 * <p>和列表 VO（{@link ShopProductVO}）相比多了三个字段：
 * {@code description}（商品描述）、{@code images}（图集，
 * 里程碑 11 加的）和 {@code reviewSummary}（评价聚合，里程碑 12 加的）。
 *
 * <p><b>★ 为什么不直接用一个 VO 把 description 也带上，省一个类？</b>
 *
 * <p>因为这两个接口的调用频率差了<b>两个数量级</b>：
 * <pre>
 *   列表页：一次请求返回 20 个商品，用户每翻一页就查一次
 *   详情页：一次请求返回 1 个商品，用户点进去才查
 * </pre>
 * 把描述放进列表，等于每次翻页都多传 20 份用户根本没看的富文本。
 * 商品描述长的能到几 KB，20 个就是几十上百 KB ——
 * 在手机上这是实打实的流量和等待时间。
 *
 * <p><b>判断「该不该为一个场景单独建 VO」的标准，不是字段差几个，
 * 而是这两个场景的数据量级和调用频率差多少。</b>
 * 差一个字段但频率差 100 倍，就该拆。
 *
 * <p>顺带一提，{@code status} / {@code updateTime} 这些字段和列表 VO 一样
 * 是不给的，理由见 {@link ShopProductVO} 的类注释。
 */
@Data
public class ShopProductDetailVO {

    private Long id;

    private Long categoryId;

    private String categoryName;

    private String name;

    private BigDecimal price;

    /**
     * 库存。
     *
     * <p>详情页要显示「仅剩 5 件」来制造紧迫感，
     * 也要在售罄时把「加入购物车」按钮置灰。
     * 但同样记住：这<b>只是展示</b>，真正的库存判断在下单时做。
     */
    private Integer stock;

    private String cover;

    /** 商品描述。只有详情页需要，列表页不带 */
    private String description;

    /**
     * 商品图集，按展示顺序排列（★ 里程碑 11 新增）。
     *
     * <p>详情页把它渲染成「主图 + 下方缩略图条」。
     *
     * <p><b>★★ 这个字段在 {@code selectShopById} 那条共用的 SQL 里【一个字都没加】。</b>
     * 它是 Service 查出商品<b>之后</b>再单独查一次、set 进来的。这一点值得展开说，
     * 因为它体现了「给共用的查询加字段」这件事的正确做法：
     *
     * <p>{@code selectShopById}（{@code ProductMapper.xml}）不只是详情页在用 ——
     * {@code CartServiceImpl.requireAvailableProduct} 也调它，
     * 而那边<b>只读 {@code stock}</b>，被「加入购物车」和「改购物车数量」
     * 两个接口调用。那两处代码旁边本来就有一句注释说
     * 「购物车其实用不到 description，多查一列有点浪费」——
     * 那还只是<b>多一列</b>，认了。
     *
     * <p>图集不一样：如果靠 MyBatis 的 {@code <collection>} 塞进那条 SQL，
     * 就变成<b>每次加购物车都多一条查 product_image 的 SQL</b>。
     * 而且 {@code resultType} 本来就装不下 {@code List<String>}，
     * 要加就得把 statement 从 {@code resultType} 改成 {@code resultMap}，
     * 改动面还会扩大。
     *
     * <p>★ 结论：<b>给一个 VO 加字段，代价不取决于这个字段本身，
     * 而取决于「读它的那条 SQL 还有谁在用」。</b>
     * 这里的解法是把「加载」放在真正需要它的那一个方法里
     * （{@code ShopProductServiceImpl.detail}），
     * 而不是塞进那条被复用的查询里 —— 于是这个字段<b>加得完全没有副作用</b>。
     *
     * <p><b>共用的查询是加字段时最容易伤到别人的地方。</b>
     *
     * <p>⚠️ 商品没有图时是<b>空列表</b>，不是 null（Service 保证）。
     * 前端因此可以直接写 {@code product.images.length}。
     * <b>而且前端的展示逻辑必须有一条「图集为空就退回 {@code cover}」的兜底</b> ——
     * 库里 40 多件商品一件图集都没有，它们靠 {@code cover} 显示了很久，
     * 不能因为加了新功能就让它们变成没图的商品。
     */
    private List<String> images;

    /**
     * 评价聚合：平均分 + 星级分布 + 总条数（★ 里程碑 12 新增）。
     *
     * <p>详情页把它渲染成顶部那块「4.7 分 / 共 128 条评价 / 五档分布条」。
     * 结构见 {@link ReviewSummaryVO}。
     *
     * <p><b>★★ 它和 {@code images} 走的是【同一条路】：不碰
     * {@code selectShopById}，由 {@code ShopProductServiceImpl.detail}
     * 事后查一次、set 进来。</b>理由和里程碑 11 加 {@code images} 时
     * 一字不差 —— 那条 SQL 被 {@code CartServiceImpl.requireAvailableProduct}
     * 复用（「加入购物车」和「改数量」两个接口都在走），
     * 而那边只需要 {@code stock}。给它加东西，等于让每次加购物车都变慢。
     *
     * <p>★ 值得单独点出来的是：<b>这是第二次遇到同一个问题了，
     * 而两次的解法完全一样。</b>第一次（{@code images}）花了很长一段注释
     * 论证「给一个 VO 加字段，代价取决于读它的那条 SQL 还有谁在用」——
     * 那套论证【原样】适用于这次，所以这次只需要指出「同上」，
     * 不需要重新推一遍。<b>这就是把理由写下来的复利。</b>
     *
     * <p>⚠️ <b>它永远非 null</b>（零评价时是一个各字段为 0 的对象）——
     * 因为 {@code selectSummary} 是不带 {@code GROUP BY} 的聚合查询，
     * 永远返回一行。所以前端可以直接写 {@code product.reviewSummary.total}，
     * 不用先判空。<b>这一点和 {@code images} 不同</b>（那个虽然也是空列表
     * 但毕竟是个集合）—— 两者的「安全默认值」都不是 null，但原因不同：
     * {@code images} 是 Service 归一化的，这个是被 SQL 的形状保证的。
     *
     * <p>⚠️ {@link ShopProductVO}（列表）<b>没有</b>这个字段，而且【不该】加：
     * 列表页不显示评分。判据还是那一条 —— 有读者才加字段。
     */
    private ReviewSummaryVO reviewSummary;
}
