package com.example.mall.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类新增/修改的请求参数。
 *
 * <p>和 {@link ProductSaveDTO} 一样：<b>没有 id 字段</b>
 * （新增时由数据库自增，修改时从 URL 取），
 * <b>也没有 createTime/updateTime</b>（由数据库自动维护）。
 * 前端就算传了这些字段也会被忽略 —— 这就是「参数白名单」。
 */
@Data
public class CategorySaveDTO {

    /**
     * 上级分类 id；<b>0 或不传都表示「一级分类」</b>（★ 里程碑 16 起）。
     *
     * <p><b>为什么可空？</b> 因为「新增一个一级分类」是最常见的操作，
     * 前端不该被迫传一个 {@code parentId=0} 才能建根分类。
     * 空值在 Service 里归一成 0 —— 这个归一<b>只有一处</b>，
     * 见 {@code CategoryServiceImpl#normalizeParentId}。
     *
     * <p>⚠️ 但「不传」和「传了 0」在<b>修改</b>场景下是同一件事，
     * 都表示「把它变成一级分类」（PUT 是全量替换）。这不是随口定的：
     * {@code parent_id} 列是 {@code NOT NULL DEFAULT 0}，
     * 「没有父」在这张表里本来就是一个<b>确定的值</b>，不是一种缺席。
     *
     * <p><b>这里不写 {@code @Min(0)} 之类的注解</b>：负数、指向自己的 id、
     * 指向一个有上级的分类 —— 这些都不是「参数格式不对」，
     * 而是<b>业务规则不允许</b>（1009/1010），必须由 Service 判断，
     * 因为服务端要知道「另一个分类长什么样」才能回答。
     * 校验注解只能看单个字段，看不了库里的数据。
     */
    private Long parentId;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过 50 个字符")
    private String name;

    /**
     * 排序值，越小越靠前。
     *
     * <p>这里用 {@code @Min(0)} 而不是 {@code @DecimalMin}，
     * 因为 sort 是整数，不是金额。选错注解的后果是校验不生效。
     *
     * <p>允许 0：排序值本身没有业务含义，只是给运营调顺序用的，
     * 0 是完全合法的值。
     */
    @NotNull(message = "排序值不能为空")
    @Min(value = 0, message = "排序值不能为负数")
    private Integer sort;

    /** 1=启用 0=禁用。不传时 Service 里默认按启用处理 */
    private Integer status;
}
