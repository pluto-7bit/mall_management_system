package com.example.mall.controller.admin;

import com.example.mall.common.Result;
import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.dto.CategorySaveDTO;
import com.example.mall.entity.Category;
import com.example.mall.service.CategoryService;
import com.example.mall.vo.CategoryTreeVO;
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
 *   GET /api/admin/categories          整棵树 + 条件筛选 → 分类管理页面的表格用
 *   GET /api/admin/categories/options  全量 + 只查启用   → 商品表单的下拉框用
 * </pre>
 *
 * <p>为什么不做成一个？因为两者的需求是冲突的：
 * <ul>
 *   <li>管理页要看到<b>禁用</b>的分类（否则禁用了就再也改不回来），
 *       而且要看到<b>层级</b>（父子关系是这个页面唯一的新东西）</li>
 *   <li>下拉框<b>只能看到启用</b>的分类（禁用的分类不该再挂新商品），
 *       而且必须是<b>扁平</b>的 —— 商品表单用的是 {@code el-select}，
 *       它拿到嵌套数据会把每个根渲染成一个<b>空白选项</b>：
 *       下拉框还在、还能点、就是不显示分类名</li>
 * </ul>
 *
 * <p>如果硬塞进一个接口，就要加 {@code ?tree=true&onlyEnabled=true} 这类参数，
 * 调用方很容易用错。拆成两个语义明确的路径反而更清晰 ——
 * <b>这不是重复，是两种不同的查询视图</b>。
 *
 * <p>★ 里程碑 16 把第一条从「分页的扁平列表」改成了「树」，于是
 * {@code page(...)} 变成了 {@code tree(...)}、返回的 {@code PageResult}
 * 变成了 {@code List}。这是本轮<b>唯一一处破坏性改动</b>，
 * 为什么必须破坏、以及为什么不能留着一个带分页字段的兼容版，
 * 写在 {@link CategoryQueryDTO} 的 javadoc 里。
 * 一句话：<b>分页和树互相矛盾，而这个接口的旧形状在树上是错的。</b>
 */
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class CategoryAdminController {

    private final CategoryService categoryService;

    /**
     * 查询分类树（管理页表格用）。
     *
     * <p>{@code GET /api/admin/categories?name=数码&status=1}
     *
     * <p>返回的是<b>森林</b>（一级分类有多个），每个节点用
     * {@code children} 携带它的子分类，已按 {@code sort ASC, id ASC} 排好序。
     *
     * <p><b>★ 前端拿到的是一个嵌套结构，而不是一个扁平数组 —— 这一点必须写清楚</b>，
     * 因为它决定了前端怎么写：Element Plus 的表格树模式需要
     * {@code row-key="id"} + {@code :tree-props="{ children: 'children' }"}，
     * 少了这两个属性，表格不会报错，它只是<b>把所有子分类当成不存在的行</b>：
     * 页面上只剩 6 个一级分类，而库里明明有 8 个。
     *
     * <p><b>筛选的语义和普通表格不一样</b>：命中的节点 + 它的整棵子树 + 它的祖先链。
     * 祖先本身不需要命中，这样命中的结果始终挂在一个看得见的位置上。
     * 所以搜「手机壳」时，结果里会<b>同时出现</b>它的父分类「手机数码」——
     * 即使「手机数码」这四个字里没有「手机壳」。这不是 bug，见 {@link CategoryQueryDTO}。
     *
     * <p>★ <b>没有分页参数了。</b> 传了 {@code pageNum} / {@code pageSize}
     * 也不会报错（Spring MVC 找不到对应的 setter，会静默忽略），
     * 接口照常返回全量树。这个「静默忽略」是有意接受的，
     * 但它必须被测试钉住 —— {@code sql/test-category.py} 里有一条专门断言它。
     */
    @GetMapping
    public Result<List<CategoryTreeVO>> tree(CategoryQueryDTO query) {
        return Result.success(categoryService.treeAll(query));
    }

    /**
     * 查询所有启用的分类（下拉框用）。
     *
     * <p>{@code GET /api/admin/categories/options}
     *
     * <p>分类是下拉框数据，量小且变动不频繁，所以不分页，一次全返回。
     *
     * <p>★ <b>这个接口仍然是扁平的，将来也不要改成树。</b>
     * 它多了一个字段 {@code parentId}（里程碑 16），前端靠它自己缩进出一级关系：
     * {@code label = (c.parentId ? '　└ ' : '') + c.name}。
     * 这比返回嵌套结构再让 {@code el-select} 去理解它简单得多，也安全得多。
     *
     * <p>「只查启用」的语义在里程碑 16 变强了：现在是
     * 「启用<b>且祖先链上的每一个也都启用</b>」。
     * 少了后半个条件，一个「父分类被禁用、自己还启用」的分类会出现在下拉框里，
     * 于是新商品能被挂进一串在商城页看不见的分类下面 —— 商品跟着消失。
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
     *
     * <p>★ 里程碑 16 给这条理由加了一个更硬的例子：
     * 列表页那份树可能正被搜索条件裁剪着（搜「手机壳」时它只剩两个节点），
     * 而编辑弹窗的「上级分类」选择器需要的是<b>全部</b>分类。
     * 拿被裁剪过的数据去填选择器，用户会以为那些分类不存在 ——
     * 和「开着弹窗数据被改了」是同一类问题的两种形态：
     * <b>手里那份数据不是全量的，但用它的地方以为它是。</b>
     */
    @GetMapping("/{id}")
    public Result<Category> detail(@PathVariable Long id) {
        return Result.success(categoryService.getById(id));
    }

    /**
     * 新增分类。
     *
     * <p>{@code POST /api/admin/categories}
     *
     * <p>请求体里 {@code parentId} 可以不传（表示一级分类），
     * 传了就要通过 Service 里的四条层级规则（1009 / 1010）。
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CategorySaveDTO dto) {
        return Result.success(categoryService.create(dto));
    }

    /**
     * 修改分类。
     *
     * <p>{@code PUT /api/admin/categories/5}
     *
     * <p>★ <b>PUT 是全量替换：不传 {@code parentId} 就等于「把它变成一级分类」。</b>
     * 这不是随口定的 —— {@code parent_id} 是 {@code NOT NULL DEFAULT 0}，
     * 「没有父」在这张表里是一个确定的值，不是一种缺席。
     * 所以前端的表单<b>必须</b>带上这个字段，哪怕用户没动它。
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
     * <p>这个接口会失败，而且有<b>两个</b>理由：
     * <ul>
     *   <li>分类下还挂着商品（1004）</li>
     *   <li>分类下还有子分类（1011，★ 里程碑 16 新增）</li>
     * </ul>
     * 但 Controller 不用管，Service 会抛 BusinessException，
     * 全局异常处理器统一转成响应。这就是分层的好处：
     * <b>业务规则只在一个地方，Controller 完全不需要知道有这条规则</b>。
     * 上面那段列表里两条规则，这个文件的代码一行都没变 —— 这就是那个好处。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
