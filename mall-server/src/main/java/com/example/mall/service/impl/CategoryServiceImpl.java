package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.PageResult;
import com.example.mall.common.ResultCode;
import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.dto.CategorySaveDTO;
import com.example.mall.entity.Category;
import com.example.mall.mapper.CategoryMapper;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.service.CategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品分类业务实现。
 *
 * <p><b>关于直接返回 Entity 而不造 VO</b>：Category 的字段
 * （id/name/sort/status/createTime/updateTime）全都可以给前端看，
 * 没有敏感字段，也没有需要 join 的额外字段，
 * 所以这里直接复用 Entity 作为返回对象是合理的。
 *
 * <p>这说明一件重要的事：<b>VO 不是必须的</b>。
 * 如果 Entity 的字段和你想返回的完全一致，硬造一个 VO 只是多一层无用代码。
 * 该分的时候分（比如 ProductVO 要装 categoryName），
 * 不该分的时候别为了「规范」而分。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    /**
     * ★ 注意这里注入了<b>另一个模块</b>的 Mapper。
     *
     * <p>为什么「分类下有没有商品」这件事要来问 ProductMapper，
     * 而不是在 CategoryMapper 里写一条 {@code SELECT COUNT(*) FROM product}？
     *
     * <p>因为规则是<b>「谁的表谁负责查」</b>：
     * {@code product} 表的查询逻辑全部集中在 ProductMapper 里，
     * 将来 product 表加了逻辑删除（{@code is_deleted}），
     * 只需要改 ProductMapper 一个文件。
     * 如果 CategoryMapper 里也散落着查 product 的 SQL，
     * 加逻辑删除时就得满项目找，漏一个就是一个 bug。
     *
     * <p>所以：<b>跨表查询也要找对「负责表」的那个 Mapper，
     * 而不是随便塞进手边任何一个 Mapper</b>。
     */
    private final ProductMapper productMapper;

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    @Override
    public List<Category> listEnabled() {
        return categoryMapper.selectEnabledList();
    }

    @Override
    public PageResult<Category> page(CategoryQueryDTO query) {
        // 先规范化分页参数，防止前端传 pageSize=999999 把整库拉走
        query.normalize();

        long total = categoryMapper.countByQuery(query);
        if (total == 0) {
            // 没有数据时直接返回空结果，少发一次查询。
            // 这个优化不是必须的，但养成习惯有好处：
            // 列表为空是很常见的场景（比如搜索一个不存在的关键词）
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        List<Category> list = categoryMapper.selectPage(query);
        return PageResult.of(list, total, query.getPageNum(), query.getPageSize());
    }

    @Override
    public Category getById(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }
        return category;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveDTO dto) {
        String name = dto.getName().trim();
        checkNameDuplicate(name, null);

        Category category = new Category();
        category.setName(name);
        category.setSort(dto.getSort());
        // 前端没传 status 时默认启用。
        // 这种「兜底默认值」放在 Service 而不是 DTO 里，
        // 是因为 DTO 只管「接住请求参数」，默认值的语义属于业务规则
        category.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        categoryMapper.insert(category);
        log.info("新增分类成功，id={}, name={}", category.getId(), name);

        // insert 之后自增主键已经回填到 category 对象上了，直接取就行
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveDTO dto) {
        // 先确认要改的分类存在。
        // 不做这一步的话，改一个不存在的 id 会「静默成功」——
        // 数据库影响 0 行，接口却返回「修改成功」，这是很糟糕的体验
        if (categoryMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }

        String name = dto.getName().trim();
        // 排除自己：把「手机数码」改成「手机数码」不该被判成重名
        checkNameDuplicate(name, id);

        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSort(dto.getSort());
        category.setStatus(dto.getStatus());

        categoryMapper.updateById(category);
        log.info("修改分类成功，id={}, name={}", id, name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (categoryMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }

        // ★ 这是本项目第一个真正的「业务规则」：
        //   分类下还挂着商品时，不允许删除。
        //
        //   为什么？商品表里的 category_id 是个必填外键。
        //   如果分类被删了，这些商品的 category_id 就指向了一个不存在的 id，
        //   术语叫「孤儿数据」。商品列表做 LEFT JOIN 时分类名会显示成空，
        //   商品编辑页面打开时分类下拉框选不中任何值 —— 一堆连锁反应。
        //
        //   两种处理方式：
        //     A. 直接拒绝删除，让运营先处理商品（本项目的选择，最安全）
        //     B. 级联删除，连带把商品一起删掉（危险，删一个分类能删掉几百个商品）
        //   B 这种「一个操作炸一大片」的设计，一定要有二次确认 + 权限控制。
        long productCount = productMapper.countByCategoryId(id);
        if (productCount > 0) {
            // 报错信息里带上具体数量，运营才知道要处理多少条。
            // 只说「该分类下有商品」的话，用户还得自己去数
            throw new BusinessException(ResultCode.CATEGORY_HAS_PRODUCT,
                    "该分类下还有 " + productCount + " 个商品，请先移走或删除这些商品");
        }

        categoryMapper.deleteById(id);
        log.info("删除分类成功，id={}", id);
    }

    // ------------------------------------------------------------------
    // 私有辅助方法
    // ------------------------------------------------------------------

    /**
     * 校验分类名是否重复。
     *
     * <p>这是「业务规则校验」，所以放在 Service，不放在 Controller 也不在 Mapper。
     *
     * <p><b>诚实地说一句：这个校验挡不住并发。</b>
     * 两个请求同时新增「手机数码」，两边都查到「不存在」，
     * 然后都插入成功，库里就有两条同名记录了。
     *
     * <p>应用层的校验解决的是「正常使用时不误操作」，给出的是精确提示
     * （「分类名称「手机数码」已存在」）。但并发场景要靠
     * <b>数据库唯一索引</b>兜底 —— 本项目已经加上：
     * <pre>
     *   UNIQUE KEY uk_name (name)     -- 见 sql/mall.sql
     * </pre>
     *
     * <p>并发时这里会「漏过去」，第二次插入被数据库拒绝，
     * 抛出 {@code DuplicateKeyException}。它由
     * {@link com.example.mall.common.GlobalExceptionHandler#handleDuplicateKey}
     * 兜住，用户看到的仍然是「该名称已存在」而不是「系统繁忙」。
     *
     * <p>两者不是二选一，而是<b>都要有</b>：
     * 应用层校验负责给出友好提示，数据库约束负责兜住正确性。
     * 这个私有方法的存在只是前者的实现，不是后者的替代品。
     *
     * @param excludeId 要排除的分类 id（修改时排除自己），新增时传 null
     */
    private void checkNameDuplicate(String name, Long excludeId) {
        Category exist = categoryMapper.selectByName(name);
        // exist != null 说明有同名分类；
        // 如果那个同名分类就是自己（修改场景），则不算重复
        if (exist != null && !exist.getId().equals(excludeId)) {
            throw new BusinessException(ResultCode.DUPLICATE_NAME,
                    "分类名称「" + name + "」已存在");
        }
    }
}
