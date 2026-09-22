package com.example.mall.controller.admin;

import com.example.mall.common.PageResult;
import com.example.mall.common.Result;
import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.dto.CategorySaveDTO;
import com.example.mall.entity.Category;
import com.example.mall.service.CategoryService;
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

import java.util.List;

/**
 * 分类管理接口（<b>管理端</b>）。
 *
 * <p>这里有个值得注意的地方：<b>同一个资源有两套查询接口</b>。
 * <pre>
 *   GET /api/admin/categories          分页 + 条件筛选 → 分类管理页面的表格用
 *   GET /api/admin/categories/options  全量 + 只查启用 → 商品表单的下拉框用
 * </pre>
 *
 * <p>为什么不做成一个？因为两者的需求是冲突的：
 * <ul>
 *   <li>管理页要看到<b>禁用</b>的分类（否则禁用了就再也改不回来），
 *       而且要分页（分类多了不能一次全拉）</li>
 *   <li>下拉框<b>只能看到启用</b>的分类（禁用的分类不该再挂新商品），
 *       而且不能分页（下拉框要能选到全部）</li>
 * </ul>
 *
 * <p>如果硬塞进一个接口，就要加 {@code ?onlyEnabled=true&noPage=true}
 * 这类参数，调用方很容易用错。拆成两个语义明确的路径反而更清晰 ——
 * <b>这不是重复，是两种不同的查询视图</b>。
 */
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class CategoryAdminController {

    private final CategoryService categoryService;

    /**
     * 分页查询分类列表（管理页表格用）。
     *
     * <p>{@code GET /api/admin/categories?pageNum=1&pageSize=10&name=数码&status=1}
     */
    @GetMapping
    public Result<PageResult<Category>> page(CategoryQueryDTO query) {
        return Result.success(categoryService.page(query));
    }

    /**
     * 查询所有启用的分类（下拉框用）。
     *
     * <p>{@code GET /api/admin/categories/options}
     *
     * <p>分类是下拉框数据，量小且变动不频繁，所以不分页，一次全返回。
     *
     * <p><b>注意路径顺序陷阱</b>：这个映射必须能和 {@code /{id}} 区分开。
     * Spring MVC 的路径匹配会优先选「更具体」的映射，
     * 所以 {@code /options} 不会被 {@code /{id}} 抢走。
     * 但如果你把 {@code /{id}} 写成 {@code /{name}} 这种更模糊的形式，
     * 就可能出现 {@code /options} 被当成 id 的诡异情况。
     * <b>给「固定路径」和「路径变量」做映射时，固定路径优先是安全的</b>，
     * 但把固定路径写在前面会让意图更明显。
     */
    @GetMapping("/options")
    public Result<List<Category>> options() {
        return Result.success(categoryService.listEnabled());
    }

    /**
     * 查询分类详情。
     *
     * <p>{@code GET /api/admin/categories/5}
     *
     * <p>编辑弹窗打开时要用它回填表单。虽然列表里已经有全部字段了，
     * 但直接拿列表行数据回填有个隐患：用户可能开着弹窗的同时，
     * 数据在别处被改了。查一次详情能保证拿到的是最新值。
     */
    @GetMapping("/{id}")
    public Result<Category> detail(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    /**
     * 新增分类。
     *
     * <p>{@code POST /api/admin/categories}
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CategorySaveDTO dto) {
        return Result.success(categoryService.create(dto));
    }

    /**
     * 修改分类。
     *
     * <p>{@code PUT /api/admin/categories/5}
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveDTO dto) {
        categoryService.update(id, dto);
        return Result.success();
    }

    /**
     * 删除分类。
     *
     * <p>{@code DELETE /api/admin/categories/5}
     *
     * <p>这个接口会失败 —— 如果分类下还挂着商品的话。
     * 但 Controller 不用管，Service 会抛 BusinessException，
     * 全局异常处理器统一转成响应。这就是分层的好处：
     * <b>业务规则只在一个地方，Controller 完全不需要知道有这条规则</b>。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
