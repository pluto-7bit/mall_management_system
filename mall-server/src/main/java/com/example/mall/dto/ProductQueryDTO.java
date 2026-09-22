package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品列表的查询条件（DTO）。
 *
 * <p>前端请求 {@code GET /api/admin/products?pageNum=1&pageSize=10&name=手机&status=1}
 * 时，Spring MVC 会把这些查询参数按名字自动填进这个类的同名字段里。
 * 不需要自己写 {@code request.getParameter("name")}。
 *
 * <p><b>这个类本身不声明 pageNum/pageSize</b>，它们继承自
 * {@link PageQueryDTO}。这样做的收益在改动时才体现出来：
 * 哪天要把每页上限从 100 改成 50，只要改基类一个地方。
 *
 * <p><b>为什么要写 {@code @EqualsAndHashCode(callSuper = true)}？</b>
 * Lombok 的 {@code @Data} 会自动生成 {@code equals()} 和 {@code hashCode()}，
 * 但它<b>看不到父类的字段</b> —— 默认只比较子类自己声明的字段。
 * 结果就是「两个 pageNum 不同、其他字段相同的对象」会被判定为相等。
 * DTO 一般不会被放进 Set 或做比较，所以影响不大，
 * 但不写这个注解 Lombok 会<b>给出编译警告</b>，
 * 写上是明确表态「我知道有父类字段，请一起比较」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductQueryDTO extends PageQueryDTO {

    /** 商品名称，模糊搜索。为 null 或空串时不参与筛选 */
    private String name;

    /** 分类 id，为 null 时不参与筛选 */
    private Long categoryId;

    /** 状态：1=上架 0=下架，为 null 时不参与筛选 */
    private Integer status;

    /**
     * {@inheritDoc}
     *
     * <p>覆盖父类方法，先处理分页字段，再处理商品特有的 {@code name}。
     * 空字符串要转成 null，这样 XML 里的 {@code <if test="name != null">} 判断才准确。
     */
    @Override
    public void normalize() {
        super.normalize();
        if (name != null && name.isBlank()) {
            name = null;
        }
    }
}
