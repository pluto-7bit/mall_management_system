package com.example.mall.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 一维规格定义：规格名 + 这一维的全部取值，例如「颜色 = [黑, 白]」。
 *
 * <p>它是 {@code product.spec_schema} 的元素类型，也就是
 * <b>「这件商品有哪些规格可选」的声明</b>；
 * 而 {@link SpecItem} 是 {@code product_sku.spec_json} 的元素类型，
 * 也就是「这一行 SKU 具体是哪个组合」。前者是<b>全集</b>，后者是<b>一个点</b>。
 *
 * <h3>★ 为什么 {@code values} 的顺序是有意义的（不能排序）？</h3>
 *
 * <p>它决定管理端编辑器和商城端选择器里<b>选项的排列顺序</b>。
 * 管理员把「256G」拖到「128G」前面再保存，下次打开必须还是这个顺序。
 *
 * <p>这正是 {@code product} 表上非要留着 {@code spec_schema} 这一列的原因：
 * <b>规格值的显示顺序除了这一列没有别的地方可存</b>。
 * SKU 行的 id 是插入顺序，从它们身上推导不出「管理员想要什么顺序」——
 * 推导出来的顺序会纹丝不动，是个彻底的静默失败。
 *
 * <p>⚠️ 注意这和 {@code spec_json} 的处理<b>恰好相反</b>：
 * {@code SpecJson.canonical()} 会把规格项按名字<b>排序</b>，
 * 因为那个字符串要拿去撞唯一索引，它的任务是「同一组规格必须是同一个字符串」。
 * 一个要排序、一个不能排序，两个字段长得很像但用途完全不同。
 *
 * <h3>★ 为什么也在 {@code common/}？</h3>
 *
 * <p>和 {@link SpecItem} 同一条理由：它同时是
 * <b>存储格式</b>（{@code spec_schema} 列）、<b>入参</b>（商品表单的规格定义）、
 * <b>出参</b>（编辑器回填）三处共用的东西。
 */
public record SpecGroup(String name, List<String> values) {

    /**
     * 防御性拷贝：留下的是一个新列表，调用方之后改它不会影响这里。
     *
     * <p>⚠️ {@code values} 为 null 时<b>保持 null</b>，不兜成空列表 ——
     * 「请求体里没写这个字段」和「写了但一个值都没有」是两件事，
     * 都要交给 Service 报成 400 并说清楚。
     * 在这里悄悄变成空列表，就等于把一个用户输入错误伪装成合法请求。
     *
     * <p>用 {@code new ArrayList<>(values)} 而不是 {@code List.copyOf}：
     * 后者遇到 null 元素会当场抛 NPE，于是 {@code "values": ["黑", null]}
     * 这种请求会变成 HTTP 500（「服务器坏了」），而它其实是个 400（「你写错了」）。
     */
    public SpecGroup {
        values = values == null ? null : new ArrayList<>(values);
    }
}
