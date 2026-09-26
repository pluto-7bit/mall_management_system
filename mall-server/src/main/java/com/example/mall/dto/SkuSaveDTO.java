package com.example.mall.dto;

import com.example.mall.common.SpecItem;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新增/修改商品时，逐个规格组合填的那一行：<b>规格 + 价格（含划线价/成本价）+ 库存</b>。
 *
 * <p>它是 {@code ProductSaveDTO.skus} 的元素类型。一件没有规格的商品，
 * {@code skus} 就是<b>恰好一条</b> {@code specs} 为空列表的「默认 SKU」。
 *
 * <h3>★ 它和 {@link com.example.mall.vo.SkuVO} 长得几乎一样，为什么不合并？</h3>
 *
 * <p>因为<b>字段集合真的不同</b>：这个类没有 {@code id}，
 * 而 {@code SkuVO} 有。这不是「差一点」，是两类东西：
 *
 * <ul>
 *   <li>{@code SkuSaveDTO} 描述的是「<b>管理员想要什么</b>」——
 *       他填的是价格和库存，他不该也不能指定数据库主键</li>
 *   <li>{@code SkuVO} 描述的是「<b>库里现在有什么</b>」——
 *       包括那行的 id，前端要拿它去匹配订单明细、去定位购物车行</li>
 * </ul>
 *
 * <p>⚠️ 合并它们会立刻制造一个安全洞：让请求体里的 {@code id}
 * 一路映射到 Service，于是「改这一行」变成客户端说了算。
 * 这正是 Entity/DTO/VO 分开那条「参数白名单」原则要防的东西。
 *
 * <h3>★ 这里为什么只有「协议层」的检查？</h3>
 *
 * <p>{@code @NotNull} / {@code @DecimalMin} / {@code @Min} 是「这个请求本身
 * 合不合理」；而「规格组合全不全、有没有重复、叉乘对不对」
 * 是<b>业务规则</b>，一律在 {@code ProductServiceImpl.replaceSkus} 里判
 * —— 那是本项目从里程碑 7 起就定下的分工（见 {@code BusinessRules} 的类注释）。
 */
@Data
public class SkuSaveDTO {

    /**
     * 这一行的规格组合。
     *
     * <p>无规格的商品传<b>空列表</b>（{@code []}），不传 null。
     *
     * <p>⚠️ 元素类型直接复用 {@link SpecItem} 这个协议无关的记录类，
     * 而不是再建一个 {@code SpecItemDTO}：它和存储格式、
     * 和响应体用的是<b>同一个东西</b>，复制一份只会制造
     * 「哪天加了字段、三处漏一处」的机会。
     */
    @NotNull(message = "规格组合不能为空（无规格请传空数组）")
    private List<SpecItem> specs;

    /**
     * 这个规格的售价。
     *
     * <p>下界与 {@code ProductSaveDTO} 原来那条价格规则一致：0.01。
     * 上限交给数据库的 {@code DECIMAL(10,2)} —— 超出会是一个明确的 SQL 错，
     * 而不是被悄悄截断。
     */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    /**
     * 划线价（原价/市场价），★ 里程碑 16 新增。<b>可空</b>：不填就是「商家没设」。
     *
     * <p>★ <b>它没有 {@code @DecimalMin}，这是刻意的，不是漏了。</b>
     * 唯一的规则是「填了就必须<b>大于</b>售价」，而那是一条<b>业务规则</b>
     * （它要比较同一行里的两个字段，还要看另一个字段的校验结果），
     * 所以按本类的分工写在 {@code ProductServiceImpl.planSkus} 里。
     *
     * <p>⚠️ 负数也由那条规则顺带拦住，<b>不需要在这里再加一条</b>：
     * 售价的下界是 {@code 0.01}，所以任何负数都过不了「必须大于售价」。
     * 再加一条 {@code @DecimalMin} 就是同一个事实的第二个定义 ——
     * 而两份实现迟早会分岔（改了这边忘了那边，用户会为同一个错误
     * 拿到两个不同的错误码，{@code BusinessRules} 的类注释里记着那次踩坑）。
     *
     * <p>⚠️ 上界同样交给数据库的 {@code DECIMAL(10,2)}，和 {@code price} 一致 ——
     * 实测：超出范围时接口返回的是 500「系统繁忙」，而不是 400。
     * <b>这是本项目既有的、有意的选择</b>（见 {@link #price} 的注释），
     * 本轮没有顺手改它：给价格加一条上界是「收紧约束」，
     * 会影响现有行为，该单独一轮做。
     */
    private BigDecimal marketPrice;

    /**
     * 成本价（进货价），★ 里程碑 16 新增。<b>可空</b>：不填就是「商家没填」。
     *
     * <p>★ 这里的 {@code @DecimalMin("0.00")}（<b>含</b> 0）是一条
     * <b>协议层</b>规则：负的进货价没有任何含义，而且会让「毛利率」
     * 算出一个荒谬的数。它不依赖任何别的字段，所以属于本类。
     *
     * <p>★★ 但<b>「成本价高于售价」（亏本卖）是被允许的</b> ——
     * Service 里<b>没有</b>对应的校验，这是刻意的：
     * 亏本清仓是真实存在的生意状态，系统只该在管理端把它标红，
     * 不该拒绝保存。这和 {@link #marketPrice} 那条「必须大于售价」
     * 形成对照：<b>一个是填错了，一个是真实的生意</b>。
     */
    @DecimalMin(value = "0.00", message = "成本价不能为负数")
    private BigDecimal costPrice;

    /**
     * 这个规格的库存。
     *
     * <p>⚠️ 这里的 {@code @Min(0)} 只挡「手滑写了负数」。
     * 真正的上界 {@code BusinessRules.MAX_SKU_STOCK} 在 Service 里判 ——
     * 它不是一个「协议层」的数字，而是一条业务规则，理由是
     * <b>{@code product.stock} 是各 SKU 库存之和，那条汇总语句不能溢出 INT</b>。
     */
    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;
}
