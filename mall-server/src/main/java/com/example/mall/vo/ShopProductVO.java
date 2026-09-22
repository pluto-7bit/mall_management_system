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

    private BigDecimal price;

    /**
     * 库存。
     *
     * <p>用户端<b>需要</b>这个字段，虽然它看起来像内部数据 ——
     * 因为要给顾客显示「仅剩 3 件」和「已售罄」。
     *
     * <p>但要注意：<b>不要把精确库存返回给前端做「能不能买」的判断。</b>
     * 前端算出来的「还有货」随时会过期（别人在你看页面时买走了最后一件），
     * 真正的判断必须在<b>下单时</b>由数据库用行锁完成（里程碑 8）。
     * 这个字段只用来「展示」，不用来「决策」。
     */
    private Integer stock;

    /** 封面图 URL。为 null 时前端显示占位图 */
    private String cover;
}
