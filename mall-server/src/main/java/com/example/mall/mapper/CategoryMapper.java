package com.example.mall.mapper;

import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.entity.Category;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品分类数据访问接口。
 *
 * <p>方法体写在 {@code src/main/resources/mapper/CategoryMapper.xml}。
 * 接口方法和 XML 里的标签按<b>方法名</b>一一对应。
 *
 * <p><b>本层唯一的职责是「读写数据库」</b>，规则是：
 * <ul>
 *   <li>可以：拼 SQL、传参、映射结果</li>
 *   <li>不可以：写业务判断、开事务、调用别的 Service</li>
 * </ul>
 * 注意这里说的是「不写业务判断」，<b>不是「不能有 WHERE 条件」</b>。
 * {@code WHERE status = 1} 是数据过滤，属于查询本身的一部分；
 * 而 {@code if (分类下还有商品) 就拒绝删除} 是业务规则，属于 Service。
 * 两者的区别在于「这条判断是不是在描述一门生意」。
 */
public interface CategoryMapper {

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 查询所有启用的分类，按 sort 升序。
     *
     * <p>只查启用的（{@code status = 1}）：分类被禁用后，
     * 不应该再出现在新增商品的下拉框里。
     *
     * <p>分类数量通常很少（几十个），所以不做分页，一次全查出来即可。
     * 不是什么列表都必须分页 —— 数据量小的时候全量查更简单也更快。
     */
    List<Category> selectEnabledList();

    /**
     * 分页查询分类列表（管理页用）。
     *
     * <p>和 {@link #selectEnabledList()} 的区别：这个会返回禁用的分类，
     * 因为管理页必须能看到并修改它们。
     */
    List<Category> selectPage(CategoryQueryDTO query);

    /**
     * 查询满足条件的总记录数，供分页器使用。
     *
     * <p><b>为什么要单独查一次？</b> 因为分页需要知道总数才能算出总页数，
     * 而 {@code LIMIT} 只返回当前页的数据，不告诉你总共多少条。
     * 所以列表接口标准做法是两条 SQL：一条查数据，一条查总数。
     *
     * <p>两条 SQL 的 {@code WHERE} 条件必须完全一致，否则会出现
     * 「翻到第 3 页却是空的」这种诡异现象。所以 XML 里用
     * {@code <sql>} + {@code <include>} 把条件抽成一份共用。
     */
    long countByQuery(CategoryQueryDTO query);

    /**
     * 根据 id 查分类。
     *
     * @return 查不到返回 null，由 Service 决定怎么处理
     */
    Category selectById(@Param("id") Long id);

    /**
     * 根据名称精确查分类，用于「分类名不能重复」的业务校验。
     *
     * <p>这里用<b>精确匹配</b>（{@code =}）而不是模糊匹配（{@code LIKE}）：
     * 做重复性校验必须是精确的，否则「手机」和「手机壳」会被判成重名。
     *
     * @return 查不到返回 null
     */
    Category selectByName(@Param("name") String name);

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 新增分类。
     *
     * <p>插入成功后自增主键会<b>回填</b>到入参对象的 id 字段上
     * （靠 XML 里的 {@code useGeneratedKeys="true"}），
     * 所以调用完就能直接 {@code category.getId()}。
     *
     * @return 影响行数，正常为 1
     */
    int insert(Category category);

    /**
     * 根据 id 更新分类，只更新非 null 的字段。
     *
     * @return 影响行数。返回 0 说明 id 不存在
     */
    int updateById(Category category);

    /**
     * 根据 id 删除分类。
     *
     * <p><b>这个方法自己不做任何「分类下有没有商品」的检查</b> ——
     * 那是业务规则，由 Service 在调用它之前判断。
     * Mapper 只管执行「删这一行」这个动作。
     *
     * @return 影响行数。返回 0 说明 id 不存在
     */
    int deleteById(@Param("id") Long id);
}
