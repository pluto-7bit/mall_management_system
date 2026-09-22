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

    private String name;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 状态：1=启用 0=禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
