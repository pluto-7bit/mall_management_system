package com.example.mall.controller.shop;

import com.example.mall.common.Result;
import com.example.mall.entity.Category;
import com.example.mall.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类接口（<b>用户端</b>）。
 *
 * <p>给商品列表页的「分类筛选」用，游客也能访问。
 *
 * <h3>★ 注意这里直接返回了实体 {@link Category}，没有建 VO</h3>
 *
 * <p>这在前面几个接口里是被明确反对的做法 —— 商品那边我们专门建了
 * {@code ShopProductVO} 和 {@code ShopProductDetailVO}，
 * 就是为了不把 {@code status}、{@code updateTime} 这些字段暴露出去。
 * 为什么到分类这里就变了？
 *
 * <p><b>因为判断标准不是「规范要求建 VO」，而是「有没有不该给前端看的东西」。</b>
 *
 * <p>逐字段看一遍 {@code Category}：
 * <table border="1">
 *   <tr><th>字段</th><th>暴露的后果</th></tr>
 *   <tr><td>{@code id}</td><td>前端筛选必须要</td></tr>
 *   <tr><td>{@code name}</td><td>就是用来显示的</td></tr>
 *   <tr><td>{@code sort}</td><td>无意义但不敏感</td></tr>
 *   <tr><td>{@code status}</td><td>恒等于 1（这个查询只返回启用的），没有信息量</td></tr>
 *   <tr><td>{@code createTime}/{@code updateTime}</td><td>分类的创建时间，不是敏感数据</td></tr>
 * </table>
 *
 * <p>结论：没什么可藏的。这时候硬造一个只有 id+name 的 VO，
 * 收益是零，成本是每加一个分类字段就要同步改 VO 和映射代码。
 *
 * <p><b>对比一下商品为什么必须建 VO：</b>
 * 因为 {@code Product} 里有 {@code status}，而用户端只能看到上架商品 ——
 * 一旦返回实体，前端就拿到了「这个商品其实存在但下架了」这个信息。
 * 那是实打实的信息泄漏。
 *
 * <p>所以「要不要 VO」这件事，<b>每一处都要单独判断</b>，
 * 判断依据是数据本身，而不是「项目规范说要用 VO」。
 * 机械地在所有地方都建 VO（或者都不建），都是没想清楚。
 *
 * <p>（顺带一提，管理端的 {@code CategoryAdminController} 也是直接返回实体的 ——
 * 因为对管理员来说这些字段本来就都能看。两个端这次恰好用了同一个类型，
 * 但这是巧合，不是共享。）
 */
@RestController
@RequestMapping("/api/shop/categories")
@RequiredArgsConstructor
public class ShopCategoryController {

    private final CategoryService categoryService;

    /**
     * 查询所有启用的分类，按 sort 升序。
     *
     * <p>{@code GET /api/shop/categories}
     *
     * <p>直接复用了 {@link CategoryService#listEnabled()} ——
     * 这个方法管理端下拉框也在用。为什么这次可以复用，
     * 而商品那边却要另建一个 Service？
     *
     * <p>因为商品的规则<b>有分歧</b>（管理端要看全部、用户端只能看上架），
     * 而复用意味着这个分歧必须靠参数来传递 —— 参数是会被传错的。
     *
     * <p>分类这里<b>没有分歧</b>：两个端要的都是「启用的分类，按 sort 排」，
     * 一模一样，一个字段都不差。没有分歧的地方共享，不叫耦合，叫复用。
     *
     * <p><b>判断标准：如果共享的代价是「加一个开关参数」，
     * 那就别共享；如果两份需求完全一致，那就该共享。</b>
     */
    @GetMapping
    public Result<List<Category>> list() {
        return Result.success(categoryService.listEnabled());
    }
}
