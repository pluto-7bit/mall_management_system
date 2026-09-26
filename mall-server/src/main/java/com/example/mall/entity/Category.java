package com.example.mall.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类实体 —— 对应 {@code category} 表。
 *
 * <p><b>Entity 的字段应该和表结构一一对应</b>，不多也不少：
 * 表里没有的字段（比如 join 出来的「该分类下商品数」）不该出现在这里，
 * 那属于 VO 的职责。
 *
 * <p>本项目里 Category 既是 Entity 也是返回给前端的对象 ——
 * 因为它的字段恰好都可以公开。这是允许的，见
 * {@link com.example.mall.service.impl.CategoryServiceImpl} 里的说明。
 */
@Data
public class Category {

    private Long id;

    /**
     * 上级分类 id；<b>0 = 一级分类</b>（★ 里程碑 16 起）。
     *
     * <p>★ 注意这里是 {@code Long} 而不是 {@code long}，因为它是<b>可空</b>的：
     * 「新增分类时没传 parentId」是一件真实存在的事，要在 Service 里
     * 归一成 0。用基本类型的话它就默认成 0 了 —— 看起来一样，
     * 但「没传」和「传了 0」在<b>修改</b>场景下的含义完全不同
     * （一个是「别动这一列」，一个是「把它改成一级分类」），
     * 而基本类型会让这个区别消失得无声无息。
     *
     * <p>★★ <b>为什么「没有父」用 0 而不是 NULL？</b>
     * 这条从里程碑 15 的 {@code spec_json} 直接继承过来：
     * <b>MySQL 把多个 NULL 当成互不相等</b>，所以一个「可以为空的父」
     * 迟早会长出「两个根」这种自相矛盾的状态，而且
     * {@code WHERE parent_id IS NULL} 和 {@code WHERE parent_id = 0}
     * 两种写法会在几个文件之间分岔，两边都「看起来正常工作」。
     * 见 {@code sql/migration-14-category-tree.sql} 头部。
     *
     * <p>⚠️ 这和同一轮 {@code product_sku.market_price / cost_price} 的
     * {@code DEFAULT NULL} <b>故意相反</b>，判据写在 14b 的头部：
     * 「0 是一个值，NULL 是缺席，这两种东西不该共享一个编码」。
     * 这里选 0，是因为 {@code parent_id = 0} 永远不会被拿去 join 一个真实分类
     * （id 从 1 开始）；而 {@code market_price = 0} 会被拿去跟售价比大小。
     */
    private Long parentId;

    private String name;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 状态：1=启用 0=禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
