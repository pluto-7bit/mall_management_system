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
 *   <tr><td>{@code parentId}</td><td>★ 前端拿它把扁平列表分成两级，见下面「为什么返回扁平数组」</td></tr>
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
     * 查询所有<b>可见</b>的分类，按 sort 升序。
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
     * <p>分类这里<b>没有分歧</b>：两个端要的都是「可见的分类，按 sort 排」，
     * 一模一样，一个字段都不差。没有分歧的地方共享，不叫耦合，叫复用。
     *
     * <p><b>判断标准：如果共享的代价是「加一个开关参数」，
     * 那就别共享；如果两份需求完全一致，那就该共享。</b>
     *
     * <h3>★★ 里程碑 16：有了层级，这个接口为什么仍然返回【扁平数组】</h3>
     *
     * <p>管理端的 {@code /api/admin/categories} 这一轮改成了返回一棵树，
     * 而这个接口<b>故意不改</b>，只多了一个 {@code parentId} 字段。
     * 这不是「用户端还没做完」的临时状态，而是一个想清楚了的决定，
     * 理由是具体的 —— 它现在有三个使用者，其中两个<b>不该</b>跟着改：
     *
     * <pre>
     *   App.vue 顶部导航      v-for 遍历这个数组
     *   Home.vue 的 banner   categoryStore.list.find(x =&gt; x.name === b.categoryName)   ← 按【名字】找
     *   Home.vue 的筛选说明   categories.find(x =&gt; x.id === categoryId)                 ← 按【id】找
     * </pre>
     *
     * <p><b>给它们一棵树，后两处会静默失效。</b>
     * {@code find} 在嵌套结构上找不到就是 {@code undefined} ——
     * banner 悄悄退回首页、筛选说明悄悄不显示，<b>没有任何一层报错</b>。
     * 而加一个字段，三个使用者一个都不用动。
     *
     * <p>★ 而且前端<b>根本不需要建树</b>：它只要两级，两级用两个 filter 就够了。
     * <pre>
     *   roots        = list.filter(c =&gt; !c.parentId)
     *   childrenOf(id) = list.filter(c =&gt; c.parentId === id)
     * </pre>
     * 这是「深度上限两级」这个决定的<b>第二次红利</b>
     * （第一次是：两级让「环」这条推理两步就能穷尽，见 {@code CategoryServiceImpl} 的类注释）。
     * 「不做三级」这个限制不是纯成本，它换来了两处真实存在的简化。
     *
     * <p><b>两个出口形状不同，是这一轮最容易觉得别扭的地方</b>，
     * 所以把判据写下来：<b>形状由「谁在用」决定，不由「数据长什么样」决定。</b>
     * 管理端要展示层级关系本身（表格要折叠、编辑要选父），所以给它树；
     * 用户端要的是「给这部分内容分组」，两级的扁平数组更贴近它真正做的事。
     */
    @GetMapping
    public Result<List<Category>> list() {
        return Result.success(categoryService.listEnabled());
    }
}
