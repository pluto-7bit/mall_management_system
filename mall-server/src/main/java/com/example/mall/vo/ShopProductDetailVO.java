package com.example.mall.vo;

import com.example.mall.common.SpecGroup;
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

    // ★★ 里程碑 15 阶段 6：price / stock 两个字段【已从这里删除】。
    //
    //   它们是这张 VO 上最容易被误用的两个字段，因为**详情页恰好是
    //   「需要知道库存」的那个页面**。删掉之后，详情页的库存有两个来源，
    //   两个都不是它们：
    //     - {@link #minPrice}      → 起售价（这个留着，详情页要显示）
    //     - {@link #skus}          → 每个规格各自的 price / stock ★ 权威来源
    //     - 商品级「售罄」= 所有规格都没货，由前端从 skus 算出来
    //   ★ 特别注意最后一条：**「商品售罄」和「这个规格缺货」是两种状态，
    //     不能混成一句话。** 详情页的按钮文案就靠这个区分
    //     （「请选择规格」/「该规格暂时缺货」），见 ProductDetail.vue。

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

    // ======================================================================
    //  ★ 里程碑 15 阶段 3：规格。详情页的核心交互就是在这里发生的。
    // ======================================================================

    /**
     * 规格定义：这件商品有哪些规格维度、每一维有哪些值，<b>以及值的排列顺序</b>。
     *
     * <p>详情页拿它把规格选择器渲染成几排按钮：
     * <pre>
     *   颜色  [黑] [白]
     *   尺码  [S] [M]
     * </pre>
     *
     * <p>⚠️ <b>数组顺序就是显示顺序，不能排序</b> ——
     * 那是管理员在编辑器里拖出来的顺序，见 {@link SpecGroup} 的类注释。
     *
     * <p>⚠️ 无规格的商品是<b>空列表</b>，不是 null。前端据此决定
     * 「不渲染规格选择器、直接显示那唯一一个 SKU 的价格和库存」。
     */
    private List<SpecGroup> specSchema;

    /**
     * 全部规格，<b>包括缺货的</b>。
     *
     * <p>★ 为什么叫 {@code skus} 而不是 {@code specs}？
     * 因为 {@link SkuVO#getSpecs()} 已经占用了「specs」这个词，
     * 而它指的是<b>一个 SKU 内部的规格项列表</b>（{@code [{name,value}]}）。
     * 两个东西差一层，名字必须分得开，否则一定会有人把
     * {@code product.skus} 写成 {@code product.specs} 然后拿到一个 undefined。
     *
     * <h3>★★ 缺货的规格【必须】在里面，这是刻意的</h3>
     *
     * <p>管理端的 {@code selectByProductId} 有同样的约定，理由是通用的：
     * 被过滤掉的规格看起来就像「这个规格压根不存在」——
     * 用户找不到「白色」，只会以为商品配置有问题，
     * 而正确的表达是<b>把它标灰、写一句「该规格暂时缺货」</b>。
     * <b>「不存在」和「不可买」是两种状态，不能靠删数据来表达后者。</b>
     *
     * <h3>★ 它和 {@code minPrice} / {@code skuCount} 的关系</h3>
     *
     * <p>那两个字段是<b>从这一个列表里算出来的</b>（Service 在 Java 里算，
     * 不是 SQL 聚合）—— 因为要渲染这个列表就必须先把整批 SKU 查出来，
     * 顺便 MIN 一下是零成本的，而再去数据库聚合一次就多一次往返。
     *
     * <p>⚠️ <b>这也意味着它们三个永远不会分叉</b>：同一份数据、
     * 同一次计算。如果哪天有人把 minPrice 改回由 SQL 提供，
     * 就会出现「选择器上是 4 档、起售价却按 3 档算」这种
     * 只在某个商品上出现的诡异现象。
     */
    private List<SkuVO> skus;

    /**
     * 起售价 —— 所有规格里最便宜的那个价格（{@code min(skus[].price)}）。
     *
     * <p>详情页在<b>用户还没选规格</b>时显示它，并且跟一个「起」字；
     * 用户选了规格之后改显示所选 SKU 的确切价格。
     *
     * <p>⚠️ 它和 {@link ShopProductVO#getMinPrice()} 是同一个值的两种来源
     * （那边是 SQL 聚合，这边是 Java 计算），两者必须一致 ——
     * 不一致的现象是「首页写 ¥99 起，点进去变成 ¥120 起」。
     */
    private BigDecimal minPrice;

    /**
     * 最高价 —— 所有规格里最贵的那个价格（{@code max(skus[].price)}），
     * ★ 里程碑 16 新增。
     *
     * <p>详情页在用户还没选规格时显示价格<b>区间</b>：
     * {@code ¥4999 ~ ¥6999}（相等时只显示一个数）。
     * 和 {@link #minPrice} 一样是<b>从 {@link #skus} 里算出来的</b>，
     * 不是 SQL 聚合 —— 上面那段「它们三个永远不会分叉」同样适用于它。
     *
     * <p>★ 用户选中了一个 SKU 之后，价格改显示<b>那一个规格</b>的
     * {@code price}，同时画它的 {@code marketPrice} 删除线。
     * ⚠️ <b>划线价是「所选 SKU 的」，不是商品级的</b> ——
     * 商品级的那个字段在 {@link ShopProductVO} 上（列表卡片用），
     * 详情页这里<b>刻意没有</b>：多规格商品的原价没有唯一答案，
     * 而详情页恰恰是用户能问出「哪个规格」这个问题的地方，
     * 所以答案要从选中的那一行上取。
     */
    private BigDecimal maxPrice;

    /**
     * 这件商品有几个规格（{@code skus.size()}）。
     *
     * <p>前端拿它决定价格后面跟不跟「起」字。
     *
     * <p>★ 详情页<b>刻意没有</b> {@code totalStock}（列表那边有）：
     * 那是个跨规格的合计数，在详情页上唯一的用法是「已售罄」，
     * 而详情页要显示的是<b>所选规格</b>的库存 ——
     * 多一个字段就多一个被误用的机会。判据还是那一条：<b>有读者才加字段。</b>
     */
    private Integer skuCount;
}
