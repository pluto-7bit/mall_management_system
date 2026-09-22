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
