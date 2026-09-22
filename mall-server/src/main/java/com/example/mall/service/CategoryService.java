package com.example.mall.service;

import com.example.mall.common.PageResult;
import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.dto.CategorySaveDTO;
import com.example.mall.entity.Category;

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
 */
public interface CategoryService {

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /** 查询所有启用的分类，按 sort 升序（下拉框用） */
    List<Category> listEnabled();

    /** 分页查询分类列表（管理页用） */
    PageResult<Category> page(CategoryQueryDTO query);

    /** 根据 id 查分类详情，不存在时抛 BusinessException */
    Category getById(Long id);

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
     * @throws com.example.mall.common.BusinessException 分类不存在，或新名称和别的分类重名
     */
    void update(Long id, CategorySaveDTO dto);

    /**
     * 删除分类。
     *
     * @throws com.example.mall.common.BusinessException 分类不存在，或分类下还挂着商品
     */
    void delete(Long id);
}
