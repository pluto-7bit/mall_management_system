package com.example.mall.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户端商品列表里的一项（VO）。
 *
 * <p><b>★ 它比管理端的 {@link ProductVO} 少了四个字段，每一个都是刻意少的。</b>
 *
 * <table border="1">
 *   <tr><th>字段</th><th>为什么用户端不给</th></tr>
 *   <tr>
 *     <td>{@code description}</td>
 *     <td><b>性能</b>。商品描述可能是几百上千字的富文本，
 *         列表页一次要显示 20 个商品，全带上就是几百 KB 的无效传输。
 *         详情页才需要它 —— 见 {@link ShopProductDetailVO}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code status}</td>
 *     <td><b>没意义</b>。用户端只查上架商品，这个值恒等于 1，
 *         返回它等于每次都告诉前端「这批数据都是 1」。
 *         更重要的是：一旦这个字段存在，前端就有人会写
 *         {@code if (item.status === 0)} 这样的代码，
 *         而那个分支永远不会执行 —— 一年后有人看到会困惑半天</td>
 *   </tr>
 *   <tr>
 *     <td>{@code createTime}</td>
 *     <td><b>不需要</b>。列表上不显示「上架时间」。</td>
 *   </tr>
 *   <tr>
 *     <td>{@code updateTime}</td>
 *     <td><b>更不能给</b>。运营改一下库存，这个时间就变了。
 *         暴露它等于让人推断出「这个商品最近被改动过」——
 *         属于经营信息泄漏，而且几乎没人在做安全评审时会注意到这种字段</td>
 *   </tr>
 * </table>
 *
 * <p><b>这就是 VO 的真正价值：它不是「实体去掉密码」，
 * 而是「这一个接口恰好需要哪些字段」。</b>
 * 管理端和用户端查的是同一张 {@code product} 表，
 * 但它们的 VO 不一样 —— 因为看的人不一样、要做的事不一样。
 *
 * <p>反过来，把 {@code ProductVO} 一路复用到底会怎样？
 * 会得到一个「谁都能用」的万能类，字段只增不减，
 * 因为没人敢删 —— 删了不知道哪个页面会白屏。
 * 这种类在项目里长到 50 个字段是常态。
 */
@Data
public class ShopProductVO {

    private Long id;

    private Long categoryId;

    /** join 出来的分类名，用户端要点分类标签筛选，所以要显示 */
    private String categoryName;

    private String name;

    // ★★ 里程碑 15 阶段 6：price / stock 两个字段【已从这里删除】。
    //
    //   删掉 stock 这件事值得单独说一句：它和下面那个 totalStock
    //   **看起来是同一个东西，其实不是**。
    //     stock（已删）  → 一行 product 上的库存，SKU 化之后不再表示库存
    //     totalStock     → SUM(product_sku.stock)，所有规格的合计
    //   阶段 2~5 期间两者恰好相等，所以前端读错哪一个都"看不出来"。
    //   前端测试那批 `不能写成 row.price` 的注释保护的就是这件事。
    //
    //   ⚠️ 而 totalStock 也不能拿来做「能不能买」的判断 ——
    //     它是跨规格的合计，一件商品可能合计 10 件而你要的那个规格是 0 件。
    //     精确判断按 skuId 的库存做（见 ShopProductDetailVO.skus），
    //     真正的能不能买仍然由下单时的行锁决定（里程碑 8）。

    /** 封面图 URL。为 null 时前端显示占位图 */
    private String cover;

    // ======================================================================
    //  ★ 里程碑 15 阶段 3 新增的四个字段。
    //    它们回答的是首页商品卡片上那三个问题：
    //      「多少钱？」「还有没有货？」「点这个按钮会加购什么？」
    // ======================================================================

    /**
     * 起售价 —— 这件商品所有规格里最便宜的那个（{@code MIN(product_sku.price)}）。
     *
     * <p><b>为什么首页必须显示「起」而不是一个确切的价格？</b>
     * 因为一件商品有多个规格时它<b>没有单一的价格</b>。
     * 卡片上写 5999，用户点进去发现 256G 是 7999 ——
     * 那是一种廉价但真实的误导。前端在 {@code skuCount > 1} 时
     * 会在数字后面跟一个「起」字。
     *
     * <p>⚠️ 它和 {@code sort=price_asc} 的排序依据<b>必须是同一个值</b>，
     * 否则会出现「按价格从低到高排出来的是 [100, 80, 50]」——
     * 一个看起来像分页或排序坏了的 bug。两边都取 {@code MIN(price)}
     * 天然一致，<b>改动价格取法时最容易破坏这一点</b>
     * （这正是 {@code shopOrderBy} 从 {@code p.price} 改成
     * {@code a.min_price} 的原因，两个改动必须一起做）。
     */
    private BigDecimal minPrice;

    /**
     * 最高价 —— 所有规格里最贵的那个（{@code MAX(product_sku.price)}），
     * ★ 里程碑 16 新增。
     *
     * <p>首页卡片靠 {@code minPrice} 和它一起显示价格<b>区间</b>：
     * {@code ¥4999 ~ ¥6999}。两者相等时（单规格商品）只显示一个数 ——
     * <b>「起」这个字因此可以退休了</b>：它表达不了区间，
     * 而「4999 起」在只有两档 4999 / 6999 时信息量太低。
     *
     * <p>⚠️ 同样<b>不 COALESCE</b>：没有 SKU 的商品是 null，
     * 和 {@link #minPrice} 保持一致（理由见 {@code ProductMapper.xml} 里
     * {@code skuAggregate} 上面那段）。
     */
    private BigDecimal maxPrice;

    /**
     * 划线价（原价/市场价），★ 里程碑 16 新增。<b>只在单规格商品上有值。</b>
     *
     * <h3>★★ 为什么多规格商品上它整个消失（而不是「挑一个」）</h3>
     *
     * <p>因为它没有唯一答案。{@code MIN(price)}（起售价）和
     * {@code MIN(market_price)}（原价里最小的那个）<b>可以来自两个不同的 SKU 行</b>：
     * <pre>
     *   黑色 售价 ¥4999 / 原价 未设
     *   白色 售价 ¥6999 / 原价 ¥8999
     *   → 卡片如果并排显示 MIN(price) 和 MIN(market_price)
     *     就成了「¥4999 ~~¥8999~~」—— 一个【不存在的折扣】
     * </pre>
     * 8999 是白色那一档的原价，4999 是黑色那一档的售价，
     * 这两个数字从来不属于同一个规格。<b>那是凭空造出来的促销信息。</b>
     *
     * <p>所以 SQL 里套了 {@code CASE WHEN sku_count = 1} 这把锁
     * （和 {@link #defaultSkuId} 是同一把锁、同一条判据）。
     * <b>多规格商品想看划线价，去详情页看</b> —— 那里用户选中了一个 SKU，
     * 那一行的原价是唯一确定的（{@code ShopSkuVO.marketPrice} 逐行带出）。
     *
     * <h3>★ 前端契约</h3>
     *
     * <p>⚠️ 多规格时这个 key 从 JSON 里<b>整个消失</b>（{@code non_null}），
     * 所以判断用宽松真值：{@code p.marketPrice}，不要写成「等于 null」。
     *
     * <p>⚠️ 「要不要画删除线」由前端判断：<b>{@code marketPrice > price} 才画</b>。
     * 后端只给数、不给 {@code showDiscount} 布尔位 —— 和
     * {@code skuCount > 1} 时前端自己加「起」字是同一种分工。
     */
    private BigDecimal marketPrice;

    /**
     * 所有规格的库存合计（{@code SUM(product_sku.stock)}）。
     *
     * <p>⚠️ <b>它的用途只有一个：判断「这件商品是不是彻底没货了」。</b>
     * 首页的按钮在它等于 0 时显示「已售罄」。
     *
     * <p>⚠️ <b>绝不能拿它当某一档规格的库存去显示</b>。
     * 4 个规格各 10 件会显示 40，而用户其实一个规格最多只能买 10 件。
     * 用户端详情页必须按<b>所选规格</b>显示库存 ——
     * 所以详情 VO 里刻意<b>没有</b>这个字段，见 {@link ShopProductDetailVO}。
     */
    private Integer totalStock;

    /**
     * 这件商品有几个规格（{@code COUNT(*)}）。
     *
     * <p>首页靠它做两个决定：
     * <pre>
     *   == 1  → 按钮是「加入购物车」，直接加 {@link #defaultSkuId}
     *   &gt;  1  → 按钮是「选规格」，点了跳详情页
     * </pre>
     * 以及价格后面要不要跟一个「起」字。
     *
     * <p>它是 {@code COUNT(*)}，所以一件没有任何 SKU 的商品是 <b>0</b>
     * 而不是 null ——「0 个规格」是个真答案，不是缺失的数据。
     * ⚠️ 而 {@code == 0} 的商品既没有价格也不能买，前端要能处理这一档
     * （虽然按里程碑 15 的验收标准，库里不该存在这种商品）。
     */
    private Integer skuCount;

    /**
     * 这件商品<b>唯一的那个 SKU 的 id</b>，仅当 {@link #skuCount} == 1 时有值。
     *
     * <h3>★★ 多规格时它是 null，而且那个 key 会从 JSON 里整个消失</h3>
     *
     * <p>{@code application.yml} 里配了
     * {@code spring.jackson.default-property-inclusion: non_null}，
     * 所以 null 字段不参与序列化。前端判断要用 <b>falsy</b>
     * （{@code if (!p.defaultSkuId)}），不要写成「等于 null」——
     * 那个字段根本不在返回的对象里。
     *
     * <h3>★ 为什么不干脆给一个「随便挑的默认规格」？</h3>
     *
     * <p>因为那会是一个<b>看起来像答案的假答案</b>。用户点「加入购物车」，
     * 系统替他选了一个他根本没选的规格 —— 而且很可能是最贵的那档 ——
     * 而页面上没有任何地方说过这件事。等他去结算时才发现买错了。
     *
     * <p><b>一个回答不了的问题不应该有一个假答案。</b>
     * 给 null，前端就会老老实实把按钮改成「选规格」把他送去详情页 ——
     * 那才是这个场景下正确的交互。
     *
     * <p>★ 这个字段的来历值得记一笔：<b>它是「统一模型」这个决定的直接红利。</b>
     * 如果无规格商品是一堆特例（没有 SKU 行、价格直接挂在 product 上），
     * 首页加购就要写两套逻辑；现在它们和单规格商品走的是同一条路 ——
     * 库里 100 件商品里 90 多件都是默认 SKU，所以这一条覆盖了绝大多数情况。
     */
    private Long defaultSkuId;
}
