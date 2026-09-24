package com.example.mall.dto;

import com.example.mall.common.SpecGroup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

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
 *
 * <h3>★ 里程碑 15：{@code price} 和 {@code stock} 从这里【搬走了】</h3>
 *
 * <p>它们现在在 {@link #skus} 里，一条 SKU 一份。
 *
 * <h4>为什么不是「两个都留」？</h4>
 *
 * <p>因为「一个字段只能有一个定义者」。如果 {@code price} 既能在顶层传、
 * 又能在 SKU 里传，那么「到底听谁的」就必须由某段代码来回答，
 * 而那段代码的规则会散进 Service、前端表单、测试脚本三处。
 * 更糟的是<b>漏改的症状是静默的</b>：
 *
 * <pre>
 *   ProductForm.vue 忘了删掉顶层的价格输入框
 *   → Spring Boot 默认忽略请求体里多出来的字段
 *   → 用户填了价格、点保存、提示「保存成功」，价格没变
 *   → HTTP 200，没有任何一层报错
 * </pre>
 *
 * <p>这正是本轮「最容易改错的地方」清单里的第 1 条。
 * 把顶层那两个字段<b>删掉</b>，那条路就走不通了 ——
 * 旧的前端代码传上来会被「参数白名单」直接丢掉，
 * 而新的必填校验 {@link #skus} 会当场报 400。
 *
 * <h4>⚠️ 但 {@code product} 表上那两个同名列【还在】（阶段 2~5 期间）</h4>
 *
 * <p>它们降级成了「SKU 的派生汇总」：{@code MIN(sku.price)} 和
 * {@code SUM(sku.stock)}，由 Service 在同一个事务里算出来写进去。
 * 那两列是<b>回滚预案</b>，删它们要等到阶段 6。
 * 所以「DTO 里没有它」和「表里没有它」是两件事，别把第 6 阶段要做的事提前做了。
 */
@Data
public class ProductSaveDTO {

    @NotNull(message = "分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 100, message = "商品名称不能超过 100 个字符")
    private String name;

    /**
     * 这件商品有哪些规格 —— 规格名 + 每一维的全部取值（★ 里程碑 15 新增）。
     *
     * <p>无规格的商品必须传<b>空数组</b> {@code []}，不能省略、不能传 null。
     *
     * <p>⚠️ 空数组是<b>要求</b>而不是「随便」：它和 {@link #skus}
     * 必须互相说圆 —— 空 {@code specSchema} 配 <b>恰好一条</b>
     * {@code specs} 为空的 SKU，而「有规格」则要求
     * {@code skus} 的条数等于各维取值数之<b>积</b>（不许漏行）。
     * 这条叉乘规则只在 {@code ProductServiceImpl} 里判一次。
     *
     * <h4>★ 为什么它是必填，而不是像 {@code images} 那样「null = 不改」？</h4>
     *
     * <p>因为 {@code images} 的 tri-state 是为了<b>向后兼容</b>：
     * 里程碑 11 之前写的测试脚本不传它，按「null = 清空」会静默抹掉图集。
     * 而 {@code specSchema} 和 {@link #skus} 是<b>价格和库存的唯一来源</b> ——
     * 少了它们，这次保存就<b>没有价格可写</b>。
     * 一个「不知道该卖多少钱」的请求不该被当成合法请求放过去，
     * 它应该当场 400 并说清楚缺什么。
     *
     * <p>⚠️ 元素类型直接复用 {@link SpecGroup} 这个协议无关的记录类，
     * 而不是再建一个 {@code SpecGroupDTO}：它同时也是存储格式
     * （{@code product.spec_schema} 列）和响应体的元素类型，
     * 三处用的是<b>同一个东西</b>。复制一份只会制造
     * 「哪天加了字段、三处漏一处」的机会 ——
     * 那正是这个项目在 {@code ProductVO} 的注释里反复警告的事。
     */
    @NotNull(message = "规格定义不能为空（无规格请传空数组）")
    private List<SpecGroup> specSchema;

    /**
     * 逐个规格组合的价格与库存（★ 里程碑 15 新增）。
     *
     * <p>无规格的商品传<b>恰好一条</b>，它的 {@code specs} 是空数组。
     * 有规格的商品，条数必须等于各维取值数的乘积 ——
     * 商家在 {@link #specSchema} 里声明了「白色」，却忘了填
     * 「白色」那一行的价格，是必须当场拒绝的：
     * 放过去的话，下次打开编辑器「白色」这个值会直接消失，
     * 商家会以为系统吃掉了他的配置（本轮「最容易改错」清单第 7 条）。
     *
     * <p>⚠️ 用字段级 {@code @Valid} 而不是 {@code List<@Valid SkuSaveDTO>}：
     * 两种写法在 Hibernate Validator 里都能级联校验元素，
     * 但字段级这种是更常见、更不会被人看漏的写法，
     * 而「看漏」正是这一轮要防的东西。
     *
     * <p>⚠️ 这里只校验「非空」和每个 SKU 自己的协议层约束。
     * <b>维度上限、每维取值上限、组合数上限、叉乘是否吻合、
     * 规格名/值是否重复</b>，全部由 {@code ProductServiceImpl} 判断 ——
     * 那是本项目从里程碑 7 起就定下的分工：
     * <b>DTO 只做协议层的合理性检查，业务上限一律由 Service 判断</b>，
     * 否则同一个业务限制会有两个出处，用户还会拿到两个不同的错误码
     * （见 {@code BusinessRules} 的类注释）。
     */
    @NotEmpty(message = "至少要有一个规格组合（无规格商品传一条 specs 为空的）")
    @Valid
    private List<SkuSaveDTO> skus;

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
     * <p>⚠️ 注意 {@link #skus} 走的是<b>相反</b>的一条路（必填、整集合替换）：
     * 因为图集「不改」是有意义的，而「这次保存不带价格」没有意义。
     * 两个字段长得像，判断标准不同，结论就不同 ——
     * 这不是不一致，是<b>两次分开的判断各自得到了它该有的答案</b>。
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
