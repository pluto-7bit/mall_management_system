package com.example.mall.mapper;

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
 *
 * <h3>★ 里程碑 16 起：这张表的所有读法都收敛成了「全量读 + 内存处理」</h3>
 *
 * <p>分类树这件事让「分页查询」和「只查启用」两个方法同时消失了，
 * 值得说清楚它们不是被优化掉的，而是<b>它们的语义已经不成立了</b>：
 *
 * <ul>
 *   <li>{@code selectPage} / {@code countByQuery} —— 分页和树互相矛盾，
 *       详见 {@link com.example.mall.dto.CategoryQueryDTO} 的说明。
 *       ★ 顺带消掉了一个更隐蔽的隐患：这两条 SQL 的 WHERE 条件
 *       是共用一份 {@code <sql>} 片段来保证一致的，
 *       而<b>「同一个查询条件写两遍就是两条会分岔的规则」</b>。
 *       分页一取消，第二条 SQL 就失去了存在理由。</li>
 *   <li>{@code selectEnabledList}（{@code WHERE status = 1}）——
 *       这是本里程碑<b>最值得记一笔</b>的一次删除。
 *       新规则是「启用的、<b>且祖先链上的每一个也启用</b>」，
 *       它严格强于 {@code status = 1}。留着旧方法就会有两个
 *       「什么算一个可见分类」的定义，而它们会在
 *       「父分类被禁用、子分类还启用」的数据上给出不同的答案 ——
 *       一边说这个子分类可见，另一边说不可见。
 *       <b>同一个事实有两份实现，就一定会分岔。</b>
 *       所以删掉 SQL 里的那份，只留 Java 里的这一份。</li>
 * </ul>
 *
 * <p>这张表的数据量是「几个到几十个」，全量读进来的代价可以忽略。
 * <b>不是什么列表都必须分页</b> —— 数据量小的时候全量查更简单也更不容易错。
 */
public interface CategoryMapper {

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 查出<b>全部</b>分类（含禁用），按 {@code sort ASC, id ASC} 排序。
     *
     * <p>这是本接口唯一一条「把整张表读出来」的查询，被四个地方共用：
     * 组树（{@code treeAll}）、算可见分类（{@code listEnabled}）、
     * 以及算「自己 + 后代 id 集合」时的辅助查找。
     *
     * <p><b>为什么不做 status 过滤？</b> 因为三个用法的过滤条件都不一样
     * （组树要按用户给的 name/status 筛；可见分类要比祖先链；
     * 算后代<b>必须一个都不筛</b>）。把 WHERE 写在这里，
     * 就等于在一处替三个调用方做了决定，而且做错的后果是静默的。
     * 见 {@code CategoryServiceImpl} 里对「算后代不能用启用列表」的长注释。
     *
     * <p>★ 排序必须在这里定，因为「兄弟之间的顺序」是业务规则，
     * 而内存里组树时我们是<b>按读出来的顺序</b>依次挂到父节点上的 ——
     * SQL 排好了，Java 一行排序代码都不用写（也不用担心排序器和 SQL 不一致）。
     */
    List<Category> selectAll();

    /**
     * 只查 {@code id} 和 {@code parent_id} 两列，用来算「自己 + 所有后代」。
     *
     * <p><b>为什么要单独开一个方法，不直接复用 {@link #selectAll()}？</b>
     *
     * <p>因为它们回答的是两个不同的问题：
     * {@code selectAll} 回答「库里有哪些分类（用来展示/筛选）」，
     * 这个方法回答「这棵树的形状是什么样」。
     * 现在两条 SQL 的身体恰好一样，但它们的<b>约束</b>不一样 ——
     * 将来 {@code selectAll} 完全可能加上一个条件
     * （比如分类将来有了逻辑删除，或者要按租户过滤），
     * 那一刻，如果算后代的方法复用了它，<b>被过滤掉的那些分类的子孙
     * 会从筛选结果里静默消失</b>：
     * 用户点开父分类，少了几件商品，
     * 没有任何一层报错，也没有任何人会想到「少掉的是因为一个 WHERE」。
     *
     * <p>★ 这和 {@code CategoryServiceImpl} 里「跨表也要找对负责表的 Mapper」
     * 是同一条判据的另一种形态：<b>不要因为两条查询今天长得一样，
     * 就把两个不同的语义绑在一起。</b> 绑上的那一刻不会出错，
     * 出错的是以后某一次单边的修改。
     *
     * <p>返回的 {@link Category} 对象<b>只有 id 和 parentId 有值</b>，
     * 别的字段都是 null。这是刻意的：它不是一个「分类」，
     * 它是树的一条边。调用方不该拿它去渲染任何东西。
     */
    List<Category> selectIdAndParent();

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
     * 数一个分类下面有几个子分类（★ 里程碑 16 起）。
     *
     * <p>被两处用到，而且都是<b>「不许做某件事」的理由</b>：
     * <ul>
     *   <li>{@code delete} —— 有子分类就不许删（1011），否则子分类的
     *       {@code parent_id} 会指向一个不存在的 id，从导航里静默消失</li>
     *   <li>{@code update} —— 有子分类就不许挂到别人下面（1009），
     *       否则它的子分类会变成三级</li>
     * </ul>
     *
     * <p><b>为什么单独开一个 COUNT，不用 {@code selectAll()} 在内存里数？</b>
     * 因为这条查询回答的是「有没有」和「有几个」，
     * 它不需要知道子分类<b>叫什么、排第几</b>。
     * 报错信息里要带数量（「还有 3 个子分类」），所以 {@code COUNT} 正好，
     * 而把整张表读出来只为了数三个数，是让一件小事依赖一堆无关的东西。
     *
     * <p>★ 这也是本项目里唯一的 {@code COUNT} —— 它长在 CategoryMapper 上，
     * 因为它数的<b>还是 category 表</b>。
     * （对照 {@code ProductMapper.countByCategoryId}：那个数的是 product 表，
     * 所以它在 ProductMapper 上。「找对负责表」判的是数据住在哪，不是业务属于谁。）
     */
    int countByParentId(@Param("parentId") Long parentId);

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
     * <p><b>这个方法自己不做任何「分类下有没有商品/子分类」的检查</b> ——
     * 那是业务规则，由 Service 在调用它之前判断。
     * Mapper 只管执行「删这一行」这个动作。
     *
     * @return 影响行数。返回 0 说明 id 不存在
     */
    int deleteById(@Param("id") Long id);
}
