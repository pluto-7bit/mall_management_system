package com.example.mall.controller.admin;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.ProductQueryDTO;
import com.example.mall.dto.ProductSaveDTO;
import com.example.mall.service.ProductService;
import com.example.mall.vo.AdminProductDetailVO;
import com.example.mall.vo.ProductVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品管理接口（<b>管理端</b>）。
 *
 * <p><b>关于 {@code controller/admin} 和 {@code controller/shop} 的分包</b>
 *
 * <p>这个项目只有一个 Spring Boot 应用，但有两类使用者：
 * <ul>
 *   <li><b>管理端</b>（本项目 mall-web）：运营用来管商品、管订单，路径前缀 {@code /api/admin}</li>
 *   <li><b>用户端</b>（本项目 mall-shop）：顾客用来逛商品、下单，路径前缀 {@code /api/shop}</li>
 * </ul>
 *
 * <p>分成两个包而不是混在一起，是因为这两类接口的<b>权限、数据范围、
 * 返回字段都不一样</b>：
 * <pre>
 *   GET /api/admin/products   → 能看到下架商品、能看到库存和成本
 *   GET /api/shop/products    → 只能看到上架商品，库存可能只显示"有货/无货"
 * </pre>
 * 如果混在一个 Controller 里，会变成一堆 {@code if (isAdmin)} 分支，
 * 而且加权限拦截器时没法按路径一刀切。
 *
 * <p><b>为什么不拆成两个应用？</b>
 * 拆开就得处理服务间调用、分布式事务、两个部署单元，是「微服务」要解决的问题。
 * 学习阶段先把单体做好 —— 单体的边界划分清楚了，
 * 将来真要拆的时候是「搬家」，而不是「重写」。
 *
 * <p><b>Controller 这一层应该非常「薄」</b>：接收请求 → 调用 Service → 包装返回值。
 * 如果你在 Controller 里看到了 {@code if} 判断、循环、数据库调用，
 * 那说明业务逻辑放错地方了。
 *
 * <p>判断标准很简单：<b>Controller 里不该出现任何业务规则的判断</b>。
 * 比如「库存不足要报错」是业务规则，属于 Service；
 * 而「参数不能为空」是格式校验，可以放在这里。
 */
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductAdminController {

    private final ProductService productService;

    /**
     * 分页查询商品列表。
     *
     * <p>{@code GET /api/admin/products?pageNum=1&pageSize=10&name=手机&status=1}
     *
     * <p>查询参数直接映射到 DTO 的同名字段上，不需要逐个写
     * {@code @RequestParam}。Spring MVC 会自动做类型转换
     * （字符串 "1" → Integer 1）。名称对不上或用不上时再写注解。
     */
    @GetMapping
    public Result<PageResult<ProductVO>> page(ProductQueryDTO query) {
        return Result.success(productService.page(query));
    }

    /**
     * 查询商品详情。
     *
     * <p>{@code GET /api/admin/products/5}
     *
     * <p>{@code @PathVariable} 把 URL 里 {@code {id}} 位置的值绑到方法参数上。
     * 路径变量比查询参数更适合表达「定位某个资源」的语义
     * （对比 {@code GET /api/admin/products?id=5}）。
     *
     * <p>★ 里程碑 11：返回类型从 {@link ProductVO} 换成了它的子类
     * {@link AdminProductDetailVO}（多一个 {@code images}），<b>方法体一个字没改</b>。
     * 这是「Controller 要薄」的一个小回报 —— 加一个字段不需要碰它。
     *
     * <p>⚠️ 注意它和上面 {@code page} 的返回类型<b>不再相同</b>了，
     * 而这是刻意的：列表不带图集（列表页不显示它，带上就是白查），
     * 详情带。见 {@code ProductVO} 和 {@code AdminProductDetailVO} 的类注释。
     */
    @GetMapping("/{id}")
    public Result<AdminProductDetailVO> detail(@PathVariable Long id) {
        return Result.success(productService.getById(id));
    }

    /**
     * 新增商品。
     *
     * <p>{@code POST /api/admin/products}，请求体是 JSON。
     *
     * <p><b>{@code @Valid} 是关键</b>：没有它，DTO 字段上的
     * {@code @NotBlank}、{@code @Min} 这些校验注解<b>完全不会生效</b>，
     * 形同虚设。加上之后，校验不通过时 Spring 会抛
     * {@code MethodArgumentNotValidException}，
     * 由全局异常处理器转成友好提示返回给前端。
     *
     * <p>这也是新手很常踩的坑：注解都写对了，就是忘了加 {@code @Valid}。
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody ProductSaveDTO dto) {
        // Service 返回新生成的 id，前端拿到后可以跳转到详情页
        return Result.success(productService.create(dto));
    }

    /**
     * 修改商品。
     *
     * <p>{@code PUT /api/admin/products/5}
     *
     * <p>id 放在 URL 里而不是请求体里，是为了让语义更清晰：
     * 「要改的是 5 号商品」。同时避免请求体里的 id 和 URL 里的 id 不一致
     * 这种让人困惑的情况。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductSaveDTO dto) {
        productService.update(id, dto);
        // 修改成功没有数据要返回，用无参的 success()
        return Result.success();
    }

    /**
     * 删除商品。
     *
     * <p>{@code DELETE /api/admin/products/5}
     *
     * <p>注意这里没有用 {@code @DeleteMapping("/{id}")} 之外的额外校验 ——
     * 「商品不存在」这种判断属于业务规则，放在 Service 里，
     * 由它抛 BusinessException，全局异常处理器统一转成响应。
     * Controller 不需要写任何 try-catch。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success();
    }
}
