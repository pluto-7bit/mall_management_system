package com.example.mall.service;

import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.dto.CategorySaveDTO;
import com.example.mall.entity.Category;
import com.example.mall.vo.CategoryTreeVO;

import java.util.List;

/**
 * 商品分类业务接口。
 *
 * <p><b>为什么 Service 要写成接口 + 实现类两个文件？</b>
 *
 * <p>这是新手最容易疑惑的地方 —— 明明只有一个实现，接口看起来是多余的。
 * 说几个实际的理由，也说清楚什么情况下可以不这么写：
 *
 * <p><b>支持这么做的理由</b>：
 * <ol>
 *   <li>Controller 依赖的是<b>接口</b>，不是具体实现。将来要加缓存版实现
 *       （{@code CachedCategoryServiceImpl}）时，Controller 一行都不用改</li>
 *   <li>Spring AOP 默认用 JDK 动态代理，而 JDK 动态代理<b>只能代理接口</b>。
 *       事务、缓存这些注解都是靠 AOP 生效的。虽然 Spring Boot 默认改用
 *       CGLIB 后这个限制不存在了，但接口风格是长期形成的约定</li>
 *   <li>接口本身就是一份「这个模块能做什么」的清单，
 *       看接口文件就能了解全貌，不用翻实现细节</li>
 * </ol>
 *
 * <p><b>什么时候可以不要接口</b>：项目很小、确定不会换实现时，
 * 直接写一个 {@code @Service} 类也完全可以。这不是「错」，是权衡。
 * 本项目为了演示标准分层写法，统一保留接口。
 *
 * <h3>★ 里程碑 16 起：分类有了层级，于是多了两条必须说清楚的规则</h3>
 *
 * <p>这两条都不是「多了个功能」那么简单，它们定义了<b>这个模块的边界在哪</b>：
 * <ul>
 *   <li>{@link #listEnabled()} —— 语义从「status = 1 的分类」变成了
 *       「status = 1 <b>且祖先链上的每一个也都启用</b>的分类」</li>
 *   <li>{@link #selfAndDescendantIds(Long)} —— 「一个分类要筛哪些 id」
 *       这个问题的<b>唯一定义者</b>，管理端和用户端都走它</li>
 * </ul>
 */
public interface CategoryService {

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 查询所有<b>在商城页可见</b>的分类，按 sort 升序（下拉框和导航用）。
     *
     * <p>「可见」= 自己启用，<b>并且祖先链上的每一个也都启用</b>。
     *
     * <p>为什么后半个条件不能省：一个启用的二级分类，如果它的父分类被禁用了，
     * 它既不是根（{@code parentId != 0}）、也不出现在任何根的子树里 ——
     * 于是它<b>从导航里凭空消失</b>，而它自己那一行还写着「启用」。
     * 这是一个没人会想到去查的 bug：分类明明启用着，就是看不见。
     *
     * <p>★ 这条规则和 {@link #treeAll(CategoryQueryDTO)} 的过滤是<b>两件不同的事</b>，
     * 不要合并：那边是「用户搜了什么」，这边是「商城页允许看见什么」。
     * 一个禁用的父分类在管理端的树里<b>必须</b>出现（否则改不回来），
     * 在商城页则必须消失。
     */
    List<Category> listEnabled();

    /**
     * 查询分类树（管理页用）。
     *
     * <p>返回的是<b>森林</b>（{@code List}，不是单个根），因为一级分类有多个。
     *
     * <p>筛选语义：<b>命中的节点 + 它的整棵子树 + 它的祖先链</b>。
     * 祖先本身不需要命中，这样命中结果始终挂在一个看得见的位置上。
     * 完整说明见 {@link CategoryQueryDTO}。
     *
     * <p>★ 这个方法<b>没有分页</b>，而且这是本轮唯一一处「收紧」的接口 ——
     * {@code GET /api/admin/categories} 的返回类型从
     * {@code PageResult<Category>} 变成了 {@code List<CategoryTreeVO>}。
     * 理由见 {@link CategoryQueryDTO}（分页和树互相矛盾）。
     * 调用方（前端和测试脚本）必须跟着改，这是刻意的：
     * 旧形状在树上是<b>错</b>的，让它编译不过比让它静默返回错的东西好。
     */
    List<CategoryTreeVO> treeAll(CategoryQueryDTO query);

    /**
     * 根据 id 查分类详情，不存在时抛 BusinessException。
     *
     * <p>返回的是 {@link Category}（不是树节点）—— 编辑弹窗要回填的是
     * 「这一行自己的字段」，它不需要子树。
     */
    Category getById(Long id);

    /**
     * 算出一个分类<b>自己 + 所有后代</b>的 id 集合（★ 里程碑 16 起）。
     *
     * <p>这是「筛父分类时含后代」这句话在代码里的<b>唯一定义者</b>。
     * 管理端商品列表和用户端商品列表都调它 —— 如果只有一端含后代，
     * 同一个筛选条件在两个端上会得出不同的件数，
     * 而运营会先怀疑后台统计错了，不会先怀疑这是两个不同的规则。
     *
     * <h3>★★ 集合必须从「全部分类」算，绝不能从「启用的分类」算</h3>
     *
     * <p>这是本轮最容易写错、而且症状最静默的一处：
     * 如果用启用列表去算后代，一个<b>被禁用的子分类</b>下的商品，
     * 会从<b>启用的父分类</b>的筛选结果里静默消失。
     * 点开「手机数码」少了二十件，挑选的人只会以为「那些没货了」。
     *
     * <p>判据：<b>分类禁用只该管住导航，不该把商品从父分类里藏起来。</b>
     * 为此刻意不走任何带 {@code status = 1} 的查询
     * （{@code CategoryMapper.selectIdAndParent} 只查两列，没有过滤）。
     *
     * @param categoryId 要筛的分类 id
     * @return 自己 + 所有后代；<b>分类不存在时返回空集合</b>
     */
    List<Long> selfAndDescendantIds(Long categoryId);

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 新增分类。
     *
     * @return 新分类的 id
     */
    Long create(CategorySaveDTO dto);

    /**
     * 修改分类。
     *
     * @throws com.example.mall.common.BusinessException 分类不存在、新名称和别的分类重名（1005）、
     *                                                   层级不合法（1009）、上级分类无效（1010）
     */
    void update(Long id, CategorySaveDTO dto);

    /**
     * 删除分类。
     *
     * @throws com.example.mall.common.BusinessException 分类不存在、分类下还挂着商品（1004）、
     *                                                   分类下还有子分类（1011）
     */
    void delete(Long id);
}
