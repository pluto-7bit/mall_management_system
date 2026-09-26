package com.example.mall.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

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

    /**
     * 分类 id，为 null 时不参与筛选（等于「全部分类」）。
     *
     * <p>★ 这是<b>用户传进来的那个值</b>，不是最终用来筛 SQL 的值 ——
     * 里程碑 16 起，筛「手机数码」要连同它的子分类一起筛，
     * 所以真正进 SQL 的是下面的 {@link #categoryIds}。
     */
    private Long categoryId;

    /**
     * ★ 真正进 SQL 的筛选条件：{@code categoryId} 自己 + 它<b>所有后代</b>的 id
     * （里程碑 16 起）。XML 里用的是这个字段，不是 {@code categoryId}。
     *
     * <p><b>为什么它长在 DTO 上，而不是当额外参数传给 Mapper？</b>
     * 因为 {@code queryCondition} 那段 {@code <sql>} 被列表查询和总数查询共用，
     * 两者都只接一个 DTO 参数。多塞一个参数进去，就要给所有条件
     * （{@code name}/{@code keyword}/{@code status}/{@code sort} 的 OGNL 判断）
     * 全部加上前缀，改动面远大于收益。
     *
     * <h3>★★ 两个必须写清楚的约定</h3>
     *
     * <p><b>1. 它的值由 Service 填，而且【无条件覆盖】。</b>
     * 见两个 {@code ProductServiceImpl.page} 的开头。
     * 这不是礼貌性的建议，而是它<b>不是</b>一个旁路的原因：
     * 前端就算在 URL 上直接传 {@code ?categoryIds=1,2}，
     * 也会被 Service 用 {@code selfAndDescendantIds()} 算出来的结果整个盖掉。
     * <b>派生字段必须每次重算，否则它就成了一条绕过规则的入口。</b>
     * （{@code PageQueryDTO.offset} 是同一个模式：它也是由 {@code pageNum}
     * 算出来的，传了也不作数。）
     *
     * <p><b>2. 只用 {@code categoryId} 而忘了填它 = 筛选被静默忽略，
     * 接口照常 200，返回【全部商品】。</b>
     * 这是本轮最容易漏的一处，所以：
     * <ul>
     *   <li>两个 page 方法里这一步都是无条件的，不写在 {@code if} 里面</li>
     *   <li>{@code sql/test-category.py} 有一条断言专门盯它 ——
     *       用一个<b>不存在的分类 id</b> 去筛，必须返回空页。
     *       如果这一步被漏掉，那个请求会返回全部 100 件商品，
     *       断言当场翻红。<b>「不存在的分类返回空页」不只是保住旧行为，
     *       它同时是这条派生逻辑的哨兵。</b></li>
     * </ul>
     */
    private List<Long> categoryIds;

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
