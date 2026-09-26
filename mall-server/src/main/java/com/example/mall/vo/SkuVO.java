package com.example.mall.vo;

import com.example.mall.common.SpecGroup;
import com.example.mall.common.SpecItem;
import com.example.mall.entity.ProductSku;
import com.example.mall.util.SpecJson;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一行 SKU 对外返回的样子（VO）。
 *
 * <h3>★ 为什么有两个「说同一件事」的字段：{@code specs} 和 {@code specText}？</h3>
 *
 * <p>因为它们服务的是两种完全不同的读者：
 *
 * <pre>
 *   specs    →  【代码】读。商城端拿它逐项比较，判断用户选中的是哪一个 SKU；
 *               管理端拿它回填规格矩阵编辑器
 *   specText →  【人】读。购物车行、订单明细行上显示的那句「颜色:黑 / 内存:128G」
 * </pre>
 *
 * <p>⚠️ 关键的一条：<b>前端匹配 SKU 绝不能靠拼字符串</b>。
 * 把 {@code specs} 拼成 {@code "颜色=黑&内存=128G"} 当钥匙，
 * 「先点颜色再点内存」和「先点内存再点颜色」会拼出两个不同的 key，
 * 于是永远匹配不到同一个 SKU —— 按钮一直灰着，没有任何报错，
 * 用户只觉得「这商品坏了」。逐项比较天然与顺序无关。
 * 那是里程碑 15 的「最容易改错的地方」清单里的一条。
 *
 * <p>两者的关系是「同一份数据的两种渲染」，都由 Service 从
 * {@code product_sku.spec_json} 算出来，不会分叉。
 *
 * <h3>★ 为什么不加 {@code specJson} 字段？</h3>
 *
 * <p>因为没有读者。前端拿 {@code specs} 就够做匹配和回填了；
 * 把规范化后的 JSON 字符串也吐出去，只会诱使某个前端同学拿它当 key ——
 * 那正是上面那段警告的事情。给不出去，就不会被误用。
 */
@Data
public class SkuVO {

    /** SKU 主键。★ 加购、下单、改数量都传它 —— 里程碑 15 之后它取代了 productId */
    private Long id;

    /**
     * 规格组合。无规格的商品（默认 SKU）是<b>空列表</b>，不是 null。
     *
     * <p>空列表和 null 的区别在这里很重要：前端据此区分
     * 「这个商品没有规格」和「后端忘了返回规格」——
     * 后者会让规格选择器渲染成一片空白而没有任何错误提示。
     */
    private List<SpecItem> specs;

    /** 渲染好的一句话，无规格时是空串。见类注释：这个字段是给【人】看的 */
    private String specText;

    private BigDecimal price;

    /**
     * 划线价（原价/市场价），★ 里程碑 16 新增。{@code null} = 商家没设。
     *
     * <p>★ <b>它加在父类上是刻意的：两端都该看到划线价。</b>
     * 用户端要画删除线，管理端要在规格矩阵里回填商家填过的值。
     *
     * <p>⚠️ 展示规则「只有 {@code marketPrice > price} 时才画删除线」在<b>前端</b>：
     * 后端只给数，不给 {@code showDiscount} 这种布尔位 ——
     * 和 {@code skuCount > 1} 时前端自己加一个「起」字是同一种分工。
     * 保存时有一条 400 拦「填了却不大于售价」的输入（见 {@code ProductServiceImpl}）。
     *
     * <p>⚠️ <b>{@code null} 和「0」是两件事。</b>数据库那列是 {@code DEFAULT NULL}，
     * 而且 {@code non_null} 的 Jackson 配置会让这个 key 在没设时<b>整个消失</b>，
     * 前端判断用宽松真值（{@code !sku.marketPrice}）即可 ——
     * 和 {@code defaultSkuId} 是同一套写法。
     */
    private BigDecimal marketPrice;

    private Integer stock;

    /**
     * ★★★ 这里【没有】也不许有 {@code costPrice}（及其派生量毛利）。
     *
     * <p>本类是两个出口共同的父类：
     * <pre>
     *   SkuVO（就是这里）
     *    ├─ ShopSkuVO   用户端出口：/api/shop/skus/{id}、购物车、立即购买
     *    └─ AdminSkuVO  管理端出口：商品详情 + SKU 编辑矩阵
     * </pre>
     *
     * <p>成本价只该出现在管理端，所以它在 {@link AdminSkuVO} 上。
     * ⚠️ <b>把 {@code costPrice} 加到这一层，等于让用户端接口匿名泄漏成本价</b> ——
     * 一行改动、零编译错误、无需登录就能读到，而且
     * {@code GET /api/shop/skus/{id}} 本来就是公开接口（加购要走它）。
     *
     * <p>这不是假想的风险，README 里早就写着这一天会来：
     * 「那 {@code /api/shop/skus/**} 凭什么匿名？……哪天有人往这个响应里
     * 加了详情接口没有的字段（成本价、真实进货量），这条理由就作废了。」
     * 里程碑 16 就是那一天 —— 而应对方式是把新字段加在<b>子类</b>上，
     * 让上面那句理由继续成立。
     *
     * <p>守着这条约定的是 {@code sql/test-price.py} 的 E 组，它是<b>双向</b>的：
     * 用户端 JSON 里键名含 {@code cost} / {@code margin} 的一个都不许有，
     * 同时管理端<b>必须</b>有 —— 少了后半句，那个扫描在
     * 「成本字段根本没接上」时也会全绿。
     */

    /**
     * 把一行 SKU 实体渲染成 VO —— <b>全项目唯一的「{@code ProductSku} → {@code SkuVO}」转换</b>。
     *
     * <h3>★ 为什么它是一个静态工厂，而不是某个 Service 里的私有方法？</h3>
     *
     * <p>因为里程碑 15 阶段 3 之后，<b>有两个域需要做这件事</b>：
     * <pre>
     *   管理端  ProductServiceImpl.getById()     → 编辑弹窗回填规格矩阵
     *   用户端  ShopSkuServiceImpl.listByProductId() → 详情页渲染规格选择器
     * </pre>
     *
     * <p>把它们合成一个私有方法就必然要复制一份，而<b>复制出来的第二份
     * 就是将来加字段时漏掉一处的来源</b> —— 这句话在
     * {@code OrderServiceImpl.toVO} 和 {@code replaceImages} 的注释里
     * 已经写过两次，这里是第三次遇到同一个形状。
     *
     * <p>放在 VO 上而不是某个 util 里，是因为「一行数据对外长什么样」
     * 本来就是 VO 自己的事：{@code SpecJson} 负责的是「字符串怎么规范化」，
     * 而这里负责的是「哪些字段、叫什么名字」。两者的读者不同。
     *
     * <h3>★ {@code schema} 只影响 {@code specText} 里维度的显示顺序</h3>
     *
     * <p>{@code specs}（给代码读的那个）来自 {@code spec_json}，
     * 它的顺序是<b>按规格名排过序的</b>（去重需要）——
     * 所以「给人看的那句话」必须借 {@code spec_schema} 重排，
     * 否则会显示成「内存:128G / 颜色:黑」，一个管理员从没定义过的顺序。
     *
     * <p>⚠️ {@code schema} 允许为 null（那时按原顺序渲染）。但
     * <b>调用方应当尽力把它查出来</b>：能查到却传 null，
     * 症状是同一件商品的规格在管理端和用户端显示顺序不一样，
     * 而两边都不报错。
     *
     * @param sku    数据库里的一行，{@code specJson} 必须是合法 JSON
     * @param schema 这件商品的规格定义，可为 null
     */
    public static SkuVO of(ProductSku sku, List<SpecGroup> schema) {
        return fill(new SkuVO(), sku, schema);
    }

    /**
     * 把一行 SKU 的字段<b>填进一个已存在的实例</b>，并把它返回。
     *
     * <h3>★ 为什么是「填进 target」而不是「返回一个 new SkuVO」？</h3>
     *
     * <p>因为子类 {@link ShopSkuVO} 也要做<b>一模一样</b>的这一步，
     * 它只比父类多四个字段。如果这里写成「new SkuVO 然后由子类逐字段抄一遍」，
     * 那么<b>父类将来加一个字段，子类就会静默地漏掉它</b> ——
     * 症状是该字段在「购物车」里是 null，在「商品详情」里正常，
     * 而两个地方都不报错。
     *
     * <p>把 target 当参数传进来，「要填哪些字段」就<b>只有这一处</b>：
     * 父类的 {@code of} 和子类的 {@code of} 都调它，加字段只需要改这里一行。
     *
     * <p>★ 这是「同一条规则只有一个出处」这条原则在<b>字段拷贝</b>上的应用。
     * 它长得像一句样板代码，但它挡掉的是最难查的一类 bug：
     * 加字段时漏了一处，而漏掉的地方不会报错。
     *
     * @param target 要被填充的实例，可以是任意 {@link SkuVO} 子类
     * @return 同一个 target（方便写成 return 语句）
     */
    protected static <T extends SkuVO> T fill(T target, ProductSku sku, List<SpecGroup> schema) {
        target.setId(sku.getId());
        target.setSpecs(SpecJson.parse(sku.getSpecJson()));
        target.setSpecText(SpecJson.text(sku.getSpecJson(), schema));
        target.setPrice(sku.getPrice());
        target.setMarketPrice(sku.getMarketPrice());
        target.setStock(sku.getStock());
        // ⚠️ 这里【不该】出现 costPrice —— 本方法是两个出口共用的，
        //    加一行就等于给用户端加了一个字段。理由见上面那段注释。
        //    成本三件套由 AdminSkuVO.of() 在调完本方法之后自己补。
        return target;
    }

    /**
     * 批量渲染。见 {@link #of(ProductSku, List)} —— 这里只是那个 foreach 的壳。
     *
     * <p>⚠️ 返回的列表可能为空，<b>但不是 null</b>：前端据此区分
     * 「这个商品没有规格」和「后端忘了返回规格」。
     */
    public static List<SkuVO> ofAll(List<ProductSku> rows, List<SpecGroup> schema) {
        List<SkuVO> list = new ArrayList<>(rows.size());
        for (ProductSku row : rows) {
            list.add(of(row, schema));
        }
        return list;
    }
}
