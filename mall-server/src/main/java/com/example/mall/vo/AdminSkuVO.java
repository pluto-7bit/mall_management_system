package com.example.mall.vo;

import com.example.mall.common.BusinessRules;
import com.example.mall.common.SpecGroup;
import com.example.mall.entity.ProductSku;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 管理端「一行 SKU」（VO）—— <b>比 {@link SkuVO} 多成本三件套</b>。
 *
 * <h3>★★ 这个类存在的唯一理由是【安全边界】，不是「管理端想多看点东西」</h3>
 *
 * <p>成本价（进货价）是一条真实的商业机密：知道它的任何一个人都能算出
 * 这门生意的毛利。而 {@code /api/shop/skus/{id}} 是<b>匿名</b>接口
 * （加购、立即购买都要走它），所以成本价绝不能出现在那条链路上。
 *
 * <p>做法是把它加在<b>子类</b>上，让两个出口从继承树上就分开：
 * <pre>
 *   SkuVO（公共字段：id / specs / specText / price / marketPrice / stock）
 *    ├─ ShopSkuVO   用户端出口 —— 到此为止，没有成本
 *    └─ AdminSkuVO  管理端出口 —— 这个类，多三个字段
 * </pre>
 *
 * <p>⚠️ <b>反过来做（把 costPrice 加到父类 {@code SkuVO} 上）是一行改动、
 * 零编译错误、不用登录就能读到成本价。</b>README 里那句预言说的就是这件事：
 * 「那 {@code /api/shop/skus/**} 凭什么匿名？……哪天有人往这个响应里加了
 * 详情接口没有的字段（成本价、真实进货量），这条理由就作废了。」
 * 里程碑 16 就是那一天 —— 应对方式是把新字段加在子类上，
 * 上面那句理由因此继续成立。
 *
 * <p>守着这条边界的是 {@code sql/test-price.py} 的 E 组，而且它是<b>双向</b>的：
 * 用户端 JSON 里键名含 {@code cost} / {@code margin} 的一个都不许有，
 * <b>同时</b>管理端必须有。少了后半句，那个扫描在「成本字段根本没接上」时
 * 也会全绿 —— 一个空转的检查比没有检查更危险。
 *
 * <h3>★ 为什么毛利是【算出来的字段】，而不是让前端减一下？</h3>
 *
 * <p>两条理由，都独立成立：
 * <ol>
 *   <li><b>JS 里不能减钱。</b>{@code 100 - 70} 在浮点下会变成
 *       {@code 29.999999999999996}，页面上就是「毛利率 42.857142857142854」。
 *       金额要有 {@code BigDecimal} 那样的十进制语义，JS 没有。</li>
 *   <li><b>「毛利 = 售价 - 成本」这条规则只能有一个定义者。</b>
 *       让每个前端各减一次，就是同一件事的两份实现 ——
 *       而它们迟早会分岔（一个算了折扣、一个没算，两个页面上的毛利率不一样，
 *       谁都不知道哪个对）。</li>
 * </ol>
 *
 * <p>另外「毛利率是除以售价还是除以成本」这种问题，只要算的地方超过一处，
 * 就一定会在某一处答错。
 *
 * <h3>★ 为什么字段名带单位（{@code grossMarginPercent} 而不是 {@code grossMarginRate}）</h3>
 *
 * <p>因为值 {@code 30.00} 表示 <b>30%</b>，不是 0.3。叫 {@code ...Rate} 的话，
 * 下一个人读到这里一定要花五分钟确认它到底是哪个 —— 而他会去猜，
 * 因为两个名字都说得通。名字里带上 {@code Percent}，这个问题是零成本的。
 * （这条和 {@code CategoryTreeVO} 那边「一个字段只能有一个定义者」
 * 是同一种「让下一个人不用猜」的努力。）
 *
 * <h3>★ 为什么没有 {@code ofAll()}？</h3>
 *
 * <p>技术上写不出来：{@code SkuVO.ofAll} 是<b>静态</b>方法，
 * 子类声明同签名的静态方法叫做<b>隐藏</b>，而隐藏要求返回类型是
 * 「可替代的」——{@code List<AdminSkuVO>} 不是 {@code List<SkuVO>} 的子类型，
 * 那样写是<b>编译错误</b>。（{@code of} 能隐藏是因为
 * {@code AdminSkuVO} 本身是 {@code SkuVO} 的子类型。）
 *
 * <p>★ 所以批量转换由调用方循环 {@link #of} 完成 ——
 * 这不是权宜之计，而是<b>本项目的既有做法</b>：
 * 用户端的 {@code ShopSkuServiceImpl} 也是自己循环调 {@code ShopSkuVO.of}
 * （那个类同样没有 {@code ofAll}）。两处形状一致，读的人不用学第二套。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminSkuVO extends SkuVO {

    /** 100，用来把「毛利 / 售价」这个比值变成百分数。定义一次，避免 100 散落在算式里 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 毛利率的小数位数。
     *
     * <p>★ <b>刻意【不】复用 {@code BusinessRules.MONEY_SCALE}</b> ——
     * 那两个 2 是<b>两件不同的事</b>，只是数值恰好相等：
     * <pre>
     *   MONEY_SCALE   金额的位数，由数据库的 DECIMAL(10,2) 决定，
     *                 而且它是【承重的】：校验和入库必须舍入到同一格
     *   PERCENT_SCALE 比率的显示位数，纯粹是「给个人看多少位合适」
     * </pre>
     * 万一哪天金额要改成 4 位小数（比如加了税率），比率需要跟着变成 4 位吗？
     * 不需要。共用一个常量就把这两个决定捆在一起了 ——
     * 而<b>它们的关系是巧合，不是规则</b>。
     */
    private static final int PERCENT_SCALE = 2;

    /**
     * 成本价（进货价）。{@code null} = 商家没填。
     *
     * <p>⚠️ <b>它是「有没有填过成本」这个问题的唯一判据</b>，
     * 前端判断「未设置」必须看它，<b>不能</b>看毛利：
     * 成本价恰好等于售价时毛利是 {@code 0.00}，而 {@code !0} 为真 ——
     * 用毛利判断会把「毛利 0.00」显示成「未设置」。
     * <b>本项目「前端一律用宽松真值判断」那条约定在这里失效</b>，
     * 因为 0 是一个合法的毛利值。
     */
    private BigDecimal costPrice;

    /**
     * 毛利 = 售价 - 成本价。
     *
     * <p>⚠️ <b>可以为负</b>：成本价高于售价（亏本卖）是真实存在的生意状态，
     * 校验不拦它，只该在管理端标红。
     *
     * <p>★ 和 {@code costPrice} 同生共死：没填成本价时它是 {@code null}，
     * 而 {@code non_null} 的 Jackson 配置会让这个 key 从 JSON 里<b>整个消失</b>
     * （不是出现一个 null）—— 前端据此渲染成「未设置」。
     */
    private BigDecimal grossMargin;

    /**
     * 毛利率（百分数）。值 {@code 30.00} 表示 <b>30%</b>，不是 0.3 —— 见类注释。
     *
     * <p>算法：{@code (售价 - 成本价) / 售价 × 100}，两位小数、四舍五入。
     * 除以的是<b>售价</b>不是成本价（两种口径都有人用，所以更要说清楚是哪种）。
     *
     * <p>⚠️ 同样可以为负（亏本）。
     */
    private BigDecimal grossMarginPercent;

    /**
     * 把一行 SKU 渲染成管理端的 VO。
     *
     * <p>★ 它先调 {@link SkuVO#fill} 完成父类那六个公共字段，
     * 再补上成本三件套 —— 和 {@code ShopSkuVO.of} 完全同一个形状。
     * <b>自己抄一遍父类字段是绝对不行的</b>：那样父类将来加字段，
     * 这里会静默漏掉，症状是同一个字段在管理端是 null、用户端正常。
     *
     * <p>⚠️ <b>「忘了补成本三件套」是这个类最可能的 bug 形态</b>：
     * 字段恒为 null → {@code non_null} 让它们整个从响应里消失 →
     * <b>接口 200、页面少一列</b>，没有任何一层报错。
     * {@code SkuVO.fill} 的 javadoc 预警过同一个坑。
     * 守着它的是 {@code sql/test-price.py} 的 D 组和 E 组。
     *
     * @param sku    数据库里的一行（{@code cost_price} 可以为 null）
     * @param schema 这件商品的规格定义，可为 null。只影响 {@code specText} 的维度顺序
     */
    public static AdminSkuVO of(ProductSku sku, List<SpecGroup> schema) {
        AdminSkuVO vo = SkuVO.fill(new AdminSkuVO(), sku, schema);
        BigDecimal cost = sku.getCostPrice();
        vo.setCostPrice(cost);

        // ★ 没填成本价 → 三个字段一起留空。
        //   「毛利」在没有成本的时候不是一个「算不出来」的问题，
        //   而是一个【没有意义】的问题 —— 这时候给 0 或给 null 都行，
        //   但给 null 才能让前端把这一格渲染成「未设置」而不是「毛利 0」。
        if (cost == null) {
            return vo;
        }

        BigDecimal price = sku.getPrice();
        BigDecimal margin = price.subtract(cost)
                .setScale(BusinessRules.MONEY_SCALE, RoundingMode.HALF_UP);
        vo.setGrossMargin(margin);

        // ⚠️ 除零守卫。校验保证了 price >= 0.01，所以正常路径上永远不会命中 ——
        //    但「校验是别人写的，除零是崩溃」，这句在 replaceSkus 那边也写过一次。
        //    price 为 null 同理（数据库那列是 NOT NULL，这里是纯粹的防御）。
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            return vo;
        }
        vo.setGrossMarginPercent(
                margin.multiply(HUNDRED).divide(price, PERCENT_SCALE, RoundingMode.HALF_UP));
        return vo;
    }
}
