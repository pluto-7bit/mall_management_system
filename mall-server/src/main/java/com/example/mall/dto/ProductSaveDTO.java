package com.example.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品新增/修改的请求参数（DTO）。
 *
 * <p><b>注意这里没有 id 字段</b>：新增时 id 由数据库自增生成，
 * 修改时 id 从 URL 路径里取（{@code PUT /api/products/5}）。
 * 如果放在请求体里，前端就能传一个 id 去改别人的数据，是不必要的风险。
 *
 * <p><b>注意这里也没有 status 之外的系统字段</b>：{@code createTime}、
 * {@code updateTime} 由数据库的 {@code DEFAULT CURRENT_TIMESTAMP} 自动维护，
 * 不该让前端指定。DTO 里不放这些字段，前端就算传了也会被忽略
 * —— 这就是前面说的「参数白名单」。
 *
 * <p><b>校验注解说明</b>：这些注解本身不会生效，必须配合 Controller 参数上的
 * {@code @Valid} 才会触发。校验失败时 Spring 抛出
 * {@code MethodArgumentNotValidException}，由
 * {@link com.example.mall.common.GlobalExceptionHandler} 统一转成友好提示。
 */
@Data
public class ProductSaveDTO {

    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称不能超过 100 个字符")
    private String name;

    /**
     * {@code @DecimalMin("0.01")} 表示必须大于等于 0.01。
     * 不能允许价格为 0 或负数 —— 白送商品通常是 bug 而不是需求。
     *
     * <p>注意这里不能用 {@code @Min}，那个是给整数用的。
     */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @Size(max = 255, message = "封面图地址不能超过 255 个字符")
    private String cover;

    @Size(max = 500, message = "商品描述不能超过 500 个字符")
    private String description;

    /** 1=上架 0=下架。不传时 Service 里默认按上架处理 */
    private Integer status;

    /**
     * 商品图集，按展示顺序排列（★ 里程碑 11 新增）。
     *
     * <h4>★★ {@code null} 和 {@code []} 是【两个完全不同的意思】</h4>
     *
     * <pre>
     *   null    → 不改图集（请求方根本不关心这件事）
     *   []      → 清空图集
     *   [...]   → 全删重插，按数组下标写 sort_no
     * </pre>
     *
     * <p>为什么不做成「null 也当清空」这种更简单的语义？
     * 因为<b>向后兼容不是加分项，是不把事情弄坏</b>：
     * 里程碑 11 之前写的那些测试脚本（{@code test-shop-product.py} 等）
     * 建商品时压根不传这个字段 —— 按「null = 清空」的语义，
     * 它们会变成每次建商品都把图集抹一遍，
     * 而且是在一个和它们要测的东西毫无关系的地方静默地抹。
     *
     * <p>这也是 REST 里「PATCH 语义」的常见做法：
     * <b>字段缺席 = 不修改，字段存在 = 按值修改（哪怕值是空的）。</b>
     * 用 Java 表达这件事，就是「包装类型默认 null」这个特性 ——
     * 如果是 {@code List} 之外的基本类型（比如 {@code int}），
     * 根本表达不出「缺席」这个状态，那就必须再加一个 boolean 标志位。
     *
     * <h4>★ 校验注解：为什么 {@code @Size} 管的是「几个」而不是「多长」</h4>
     *
     * <p>{@code @Size(max = 5)} 写在 {@code List} 上，约束的是<b>元素的个数</b>。
     * 想约束<b>单个元素的长度</b>要写成 {@code List<@Size(max = 255) String>} ——
     * 注解加在泛型参数上（这叫「容器元素约束」，Hibernate Validator 支持）。
     *
     * <p>⚠️ 这个区别值得单独记一句，因为<b>写错了不会有任何报错</b>：
     * 写成 {@code @Size(max = 255) private List<String> images}
     * 看起来完全像是在限制长度，实际上是在说「最多 255 张图」，
     * 校验静默地不生效。所以这里两种都写清楚，
     * 并且 Service 里还会再判一次 —— 见
     * {@link com.example.mall.service.impl.ProductServiceImpl}。
     */
    @Size(max = 5, message = "商品图集最多 5 张")
    private List<@Size(max = 255, message = "图片地址不能超过 255 个字符") String> images;
}
