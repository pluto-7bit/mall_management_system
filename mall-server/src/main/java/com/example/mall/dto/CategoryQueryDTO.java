package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分类列表的查询条件。
 *
 * <p>分页字段继承自 {@link PageQueryDTO}，这里只声明分类特有的筛选字段。
 *
 * <p><b>为什么不叫 CategoryPageQueryDTO？</b> 命名上「Query」已经表达了
 * 「查询条件」的意思，加 Page 反而啰嗦。项目里统一用 {@code XxxQueryDTO}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CategoryQueryDTO extends PageQueryDTO {

    /** 分类名称，模糊搜索。为 null 或空串时不参与筛选 */
    private String name;

    /** 状态：1=启用 0=禁用，为 null 时不参与筛选 */
    private Integer status;

    /**
     * {@inheritDoc}
     *
     * <p>这里演示了子类如何扩展基类的规范化逻辑：
     * <b>先调 super 处理分页字段，再处理自己新增的字段</b>。
     * 顺序反了不会报错，但逻辑上会变得难以推理。
     *
     * <p>空字符串要转成 null，因为 XML 里写的是
     * {@code <if test="name != null">} —— 空串不是 null，会进去拼出一个
     * {@code LIKE '%%'}，虽然结果碰巧一样，但白白多扫一遍索引。
     */
    @Override
    public void normalize() {
        super.normalize();
        if (name != null && name.isBlank()) {
            name = null;
        }
    }
}
