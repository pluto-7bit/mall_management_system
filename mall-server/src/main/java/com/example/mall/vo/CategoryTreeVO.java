package com.example.mall.vo;

import com.example.mall.entity.Category;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 分类树节点（管理端分类列表用，★ 里程碑 16 起）。
 *
 * <p>形状就是「一个分类 + 它的子分类」：
 * <pre>
 *   [ { id:1, name:"手机数码", parentId:0, children:[ { id:7, name:"手机壳", parentId:1 } ] },
 *     { id:2, name:"休闲零食", parentId:0 } ]        ← 叶子节点【没有】children 这个键
 * </pre>
 *
 * <h3>★ 为什么用 extends Category，而不是「组合一个 category 字段」</h3>
 *
 * <p>这条先例在项目里已经有三处：{@code AdminProductDetailVO extends ProductVO}、
 * {@code ShopProductDetailVO}、{@code ShopSkuVO extends SkuVO}。
 * 选它的理由是前端：树节点要平铺成表格的一行，前端写的是
 * {@code row.name} / {@code row.status}，而不是 {@code row.category.name}。
 * 组合会让每个单元格的取值路径都变长一层，而且是<b>纯噪音</b> ——
 * 它没有带来任何「这两个东西是分开的」这个信息。
 *
 * <h3>★★ 代价：加在父类 Category 上的字段会同时流向两个出口</h3>
 *
 * <p>这是继承在这里<b>唯一</b>的坑，写下来，因为下一个加字段的人一定会踩：
 * <pre>
 *   Category（父类）
 *    ├─ CategoryTreeVO      管理端分类树（这个类）
 *    └─ 直接返回 List&lt;Category&gt; 的扁平出口：/api/admin/categories/options
 *                                              /api/shop/categories
 * </pre>
 * 也就是给 {@code Category} 加一个字段，<b>三个接口的响应会一起变</b>，
 * 而其中两个可能根本不需要这个字段。这通常没问题（字段多一个不影响老前端），
 * 但如果加的是<b>敏感或不该公开</b>的字段，那它就不止多一个出口 ——
 * 它同时多了一个<b>匿名</b>出口（{@code /api/shop/categories} 不需要登录）。
 *
 * <p>★ 里程碑 16 的价格体系那边就是这个坑的实弹：
 * {@code ShopSkuVO extends SkuVO}，所以「把成本价加到 SkuVO 上」这一行改动
 * 会让 {@code /api/shop/skus/{id}} 匿名泄漏成本价，零编译错误。见 README 的已知取舍。
 *
 * <h3>children 为 null 而不是空数组</h3>
 *
 * <p>叶子节点不写 children，靠的是 {@code application.yml} 里的
 * {@code default-property-inclusion: non_null} —— 值为 null 的字段整个不出现在 JSON 里。
 *
 * <p>这不是省事，是为了让「有没有子树」这件事在响应里只有一个表示法：
 * 如果叶子是 {@code children: []}，那前端就要同时考虑 {@code undefined}
 * 和 {@code []} 两种「没有子节点」，而这两种在 JS 里恰好又都是假值 ——
 * 看起来无害，但 {@code .length} 一个报错一个不报。
 * 另外 Element Plus 的 {@code tree-props} 对「键不存在」和「空数组」
 * 都渲染成叶子节点，所以二者在功能上没有区别，那只保留一种即可。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CategoryTreeVO extends Category {

    /**
     * 子分类，已按 {@code sort ASC, id ASC} 排好序。
     *
     * <p><b>null = 叶子节点</b>（这个键不会出现在 JSON 里）。
     *
     * <p>★ 排序<b>在后端做</b>，前端不要自己再排一遍：
     * 「兄弟之间的顺序」是一条业务规则（分页时代它写在 SQL 的 ORDER BY 里），
     * 规则只能有一个定义者。前端如果自己按 id 排一次，
     * 就会出现「管理端树的顺序和商品表单下拉框的顺序不一样」这种对不上的状态。
     */
    private List<CategoryTreeVO> children;
}
