package com.example.mall.vo;

import com.example.mall.common.SpecGroup;
import com.example.mall.entity.ProductSku;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户端「直接被 id 寻址的一行 SKU」（VO）。
 *
 * <h3>★ 它比父类多出来的四个字段，回答的是同一个问题</h3>
 *
 * <p>{@link SkuVO} 描述的是「一行 SKU」，它假设读者<b>已经站在某件商品上</b>了 ——
 * 管理端详情、用户端详情都是先有商品、再列规格，所以商品名、
 * 封面、分类这些上下文父类一个都不给，因为读者手里已经有了。
 *
 * <p>而本类服务的场景恰好相反：<b>调用方手里只有一个 skuId，没有商品</b>：
 * <pre>
 *   GET /api/shop/skus/204          → 立即购买：URL 上只有 skuId 和数量
 *   ShopSkuService.listAvailable()  → 购物车：Redis 里存的就是一堆 skuId
 * </pre>
 *
 * <p>这两个读者都必须把「这一行属于哪件商品、叫什么、长什么样」
 * 一并拿到，否则就要为每一个 skuId 再发一次查商品的请求 —— 那就是 N+1。
 * 所以这四个字段不是「顺手多给点」，而是<b>这个 VO 唯一的存在理由</b>。
 *
 * <h3>★ 这四个字段是 Service 拼上去的，不是 SQL join 出来的</h3>
 *
 * <p>{@code ShopSkuServiceImpl} 先按 skuId 查 SKU 行，再拿它们去
 * {@code ProductMapper.selectShopByIds} 查商品，然后在 Java 里合成这个 VO。
 * <b>两条 SQL，不是 N+1。</b>
 *
 * <p>为什么不写成一条 JOIN？因为那个 JOIN 的 {@code WHERE} 里会带上
 * {@code p.status = 1}，于是「用户端只能看下架商品之外的东西」
 * 这条<b>安全规则就有了第二处定义</b>（第一处在 ProductMapper 的 shop* 那一组）。
 * 两处定义迟早会分叉，而分叉的表现是「购物车里显示得出来、
 * 一点结算就说商品不存在」。
 *
 * <p>★ 拼接的副产品是「不可买」这件事也顺带算出来了：
 * <b>查不到商品的那些 SKU 根本不会变成这个 VO</b>，
 * 所以 {@code listAvailable} 的差集天然就是购物车的「失效商品」。
 *
 * <h3>★ 为什么不是 {@code SkuVO} 加四个字段，省一个类？</h3>
 *
 * <p>因为 {@link SkuVO} 被<b>商品详情</b>用着，而那边一件商品返回 4 行 SKU，
 * 加上这四个字段就是同一个商品名、同一张封面、同一个分类名
 * <b>重复 4 遍</b>。这正是里程碑 11 加 {@code images} 时论证过的那件事：
 * <b>给一个 VO 加字段，代价取决于读它的那条 SQL 还有谁在用。</b>
 *
 * <p>所以这里走的是 {@code AdminProductDetailVO extends ProductVO}
 * 那条先例（子类加场景专有的字段），只是这次是<b>用户端</b>拿到了子类 ——
 * 谁需要谁去继承，「管理端子类」不是规则，「按读者拆」才是。
 *
 * <h3>★ 为什么不带 {@code specSchema}？</h3>
 *
 * <p>因为它的读者是 Service 内部（{@code SpecJson.text} 要靠它重排显示顺序），
 * 而从前端拿不到任何用处 —— 前端要渲染规格选择器时走的是商品详情接口，
 * 那里有 {@code specSchema}。见 {@link SkuVO} 类注释里
 * 「为什么不加 specJson 字段」那段：<b>给不出去，就不会被误用。</b>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ShopSkuVO extends SkuVO {

    /**
     * 这一行属于哪件商品。
     *
     * <p>★ 立即购买那条路要用它：结算页显示完这一行之后，
     * 「再看看这件商品」的链接需要商品 id。
     */
    private Long productId;

    /** 商品名。购物车行、结算行上显示的那句话的主语 */
    private String productName;

    /** 商品封面图。购物车是「一张小图 + 一句话」的列表，没有图会很难看 */
    private String cover;

    /** 分类名，纯展示。父类的场景（商品详情）里它是商品级的，那边由商品 VO 给 */
    private String categoryName;

    /**
     * 把「一行 SKU + 它所属的商品」拼成一个 VO。
     *
     * <p>★ 它调 {@link SkuVO#fill} 完成父类那五个字段，而不是自己抄一遍 ——
     * 抄一遍的话，父类将来加字段这里就会静默漏掉。
     * 完整的理由写在 {@code SkuVO.fill} 的 javadoc 里。
     *
     * <p>★ 这也是本类<b>唯一</b>的构造入口。两个 public 方法都走它，
     * 于是「哪个字段来自哪张表」只有一处定义。
     */
    public static ShopSkuVO of(ProductSku sku, ShopProductVO product, List<SpecGroup> schema) {
        ShopSkuVO vo = SkuVO.fill(new ShopSkuVO(), sku, schema);
        vo.setProductId(product.getId());
        vo.setProductName(product.getName());
        vo.setCover(product.getCover());
        vo.setCategoryName(product.getCategoryName());
        return vo;
    }
}
