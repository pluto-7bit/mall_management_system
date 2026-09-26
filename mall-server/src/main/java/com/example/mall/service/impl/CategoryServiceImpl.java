package com.example.mall.service.impl;

import com.example.mall.common.BusinessException;
import com.example.mall.common.ResultCode;
import com.example.mall.dto.CategoryQueryDTO;
import com.example.mall.dto.CategorySaveDTO;
import com.example.mall.entity.Category;
import com.example.mall.mapper.CategoryMapper;
import com.example.mall.mapper.ProductMapper;
import com.example.mall.service.CategoryService;
import com.example.mall.vo.CategoryTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品分类业务实现。
 *
 * <p><b>关于直接返回 Entity 而不造 VO</b>：Category 的字段
 * （id/parentId/name/sort/status/createTime/updateTime）全都可以给前端看，
 * 没有敏感字段，也没有需要 join 的额外字段，
 * 所以扁平出口直接复用 Entity 作为返回对象是合理的。
 *
 * <p>这说明一件重要的事：<b>VO 不是必须的</b>。
 * 如果 Entity 的字段和你想返回的完全一致，硬造一个 VO 只是多一层无用代码。
 * 该分的时候分（比如 ProductVO 要装 categoryName），
 * 不该分的时候别为了「规范」而分。
 *
 * <p>★ 里程碑 16 起多了一个 {@link CategoryTreeVO}：
 * 树节点比 Entity 多一个 {@code children}，所以它<b>确实</b>需要一个 VO。
 * 「该分的时候分」的那一半在这里生效。
 *
 * <h3>★★ 分类树的三条规则（本轮唯一一处定义它们的地方）</h3>
 *
 * <p>规则是：{@code parent_id} 要么是 0，要么指向一个<b>自己也是 0</b>的分类。
 * 也就是只有一级和二级。数据库拦不住这件事（见 migration-14 头部），
 * 所以它住在下面这个 {@link #checkParent} 里：
 *
 * <table border="1">
 *   <caption>层级规则</caption>
 *   <tr><th>#</th><th>规则</th><th>违反</th><th>拦住的东西</th></tr>
 *   <tr><td>1</td><td>{@code parentId != id}</td><td>1009</td><td>自己挂自己</td></tr>
 *   <tr><td>2</td><td>上级分类存在</td><td>1010</td><td>挂到一个不存在的分类上</td></tr>
 *   <tr><td>3</td><td>上级分类<b>启用</b></td><td>1010</td><td>挂到一个商城页看不见的分支下（症状：商品从列表里消失）</td></tr>
 *   <tr><td>4</td><td>上级自己必须是<b>根</b></td><td>1009</td><td>三级</td></tr>
 *   <tr><td>5</td><td>自己有子分类时不许再挂父</td><td>1009</td><td>环，以及「我的子分类变成三级」</td></tr>
 * </table>
 *
 * <p><b>★ 为什么 1 + 4 + 5 足以杜绝环？</b> 这段推理必须写下来，
 * 否则下一个读代码的人一定会怀疑「就这五条真的够吗」：
 *
 * <pre>
 *   一个环要求存在一个「既是父又是子」的分类。
 *   由规则 5：有子分类的分类【永远是根】—— 它永远不可能是子。
 *   于是任何「子」都没有子分类；
 *   而规则 1 挡住了「自己是自己的子」这个唯一的退化环。
 *   两步就穷尽了 —— 因为它只有两级。
 * </pre>
 *
 * <p>这条推理有一个<b>可测的推论</b>：任何一条向上走祖先链的代码
 * 都必须能在有限步内终止。下面那些 {@code visited} 集合不是装饰，
 * 但也要诚实地说清楚它们在防什么 ——
 * <b>正常数据下 1 步就结束，{@code visited} 防的是手工 SQL 造出来的脏数据
 * 把请求挂死，不是在防正常流程。</b>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /**
     * 一级分类的 {@code parent_id}。
     *
     * <p>★ 抽成常量而不是到处写 {@code 0}：这个 0 是一个<b>散落在很多地方的约定</b>
     * （SQL 的 DEFAULT、这里的三处比较、前端的「无（一级分类）」选项），
     * 而它一旦和 {@code NULL} 混用就正是 migration-14 头部警告的那件事。
     * 给它一个名字，读代码的人就不用每次停下来想「这个 0 是什么」。
     */
    private static final long ROOT_PARENT_ID = 0L;

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
     *
     * <p>★ 里程碑 16 的对照：{@code countByParentId} 查的<b>还是 category 表</b>，
     * 所以它就应该、也只能长在 CategoryMapper 上。两条规则放在一起看，
     * 「找对负责表」这句话的边界才清楚：
     * <b>判据是「这一行数据住在哪张表」，不是「这段逻辑属于哪个业务」。</b>
     */
    private final ProductMapper productMapper;

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>实现上有一个刻意的选择：<b>把整张表读进来，在 Java 里过滤</b>，
     * 而不是写一条 {@code WHERE status = 1 AND 父分类也启用} 的 SQL。
     *
     * <p>为什么？因为「祖先链全启用」这件事在 SQL 里要么写成自连接
     * （{@code JOIN category p ON p.id = c.parent_id AND p.status = 1}），
     * 要么写成递归 CTE。自连接会把「两级」这个上限**硬编码进 SQL** ——
     * 而「最多两级」是一条业务规则，它的定义者必须是 {@link #checkParent} 一处。
     * 把它同时写进 SQL，就是同一件事有了两份实现。
     *
     * <p>分类只有几行到几十行，一次全读出来的代价可以忽略。
     * 这条取舍和「分类列表不再分页」是同一个判断。
     */
    @Override
    public List<Category> listEnabled() {
        List<Category> all = categoryMapper.selectAll();
        Map<Long, Category> byId = indexById(all);

        List<Category> visible = new ArrayList<>();
        for (Category c : all) {
            if (isEnabled(c) && allAncestorsEnabled(c, byId)) {
                visible.add(c);
            }
        }
        return visible;
    }

    @Override
    public List<CategoryTreeVO> treeAll(CategoryQueryDTO query) {
        query.normalize();

        List<Category> all = categoryMapper.selectAll();   // 已按 sort ASC, id ASC 排好
        Map<Long, Category> byId = indexById(all);

        // 按父分组一次，后面「保留子树」和「挂 children」都用它，避免反复扫全表
        Map<Long, List<Category>> childrenOfSource = new HashMap<>();
        for (Category c : all) {
            childrenOfSource.computeIfAbsent(parentOf(c.getParentId()), k -> new ArrayList<>()).add(c);
        }

        // ---- 第一步：算出哪些节点要出现在结果里 ----
        // 规则是「命中的节点 + 它的整棵子树 + 它的祖先链」。
        // 后两项都是为了同一个目的：让命中的东西挂在一个【看得见的位置】上，
        // 而不是变成一个凭空出现的根或者干脆消失。
        Set<Long> keep = new HashSet<>();
        for (Category c : all) {
            if (!matches(c, query)) {
                continue;
            }
            keep.add(c.getId());
            addSubtree(c.getId(), childrenOfSource, keep);
            addAncestors(c, byId, keep);
        }

        // ---- 第二步：建节点。按 all 的顺序建，兄弟之间的顺序就自然是对的 ----
        // 这里【不写任何排序代码】：顺序是 SQL 的 ORDER BY 定下的，
        // 内存里只做「照读出来的顺序挂上去」。规则只有一个定义者。
        Map<Long, CategoryTreeVO> voById = new LinkedHashMap<>();
        for (Category c : all) {
            if (keep.contains(c.getId())) {
                voById.put(c.getId(), toVO(c));
            }
        }
        if (voById.isEmpty()) {
            return List.of();   // 筛选没命中任何东西。不必再往下走
        }

        Map<Long, List<CategoryTreeVO>> childrenOf = new HashMap<>();
        for (CategoryTreeVO vo : voById.values()) {
            childrenOf.computeIfAbsent(parentOf(vo.getParentId()), k -> new ArrayList<>()).add(vo);
        }

        // ---- 第三步：从根往下挂 children ----
        // 根 = parentId 为 0，或者【父节点不在结果集里】。
        // 后者正常情况走不到（祖先链是强制保留的），
        // 唯一能造成它的是脏数据：parent_id 指向一个不存在的分类。
        // 这时把它当根显示出来，而不是让它消失 ——
        // 「一条会报错的规则胜过一片会消失的分类」，同理，
        // 【一个看得见的孤儿胜过一片消失的分类】：
        // 运营在管理端能看到这个孤零零的节点，才能发现数据坏了。
        List<CategoryTreeVO> roots = new ArrayList<>();
        for (CategoryTreeVO vo : voById.values()) {
            Long pid = parentOf(vo.getParentId());
            if (pid == ROOT_PARENT_ID || !voById.containsKey(pid)) {
                roots.add(vo);
            }
        }

        // ★ visited 是防环的，不是装饰。正常数据下每个节点恰好被访问一次
        //   （规则 4 + 5 保证了「有子分类的一定是根」，所以不可能有环）。
        //   但手工 SQL 造出的 A→B→A 会让下面这个递归无限下去，
        //   而它的症状会是【序列化 JSON 时栈溢出】—— 一个既难查、
        //   又和「分类数据坏了」看不出关系的 500。
        Set<Long> visited = new HashSet<>(roots.size());
        for (CategoryTreeVO root : roots) {
            visited.add(root.getId());
        }
        for (CategoryTreeVO root : roots) {
            attachChildren(root, childrenOf, visited);
        }

        // ★ 最后一步：留下来的节点有没有没能出现在树里的？
        //   只有环能造成这件事（环上的节点既不是根，也不是任何根的后代）。
        //   静默丢节点是本项目最忌讳的失败方式，所以这里【吵一声】：
        //   数据坏了要在日志里留下名字，而不是让页面莫名其妙少一行。
        if (visited.size() < voById.size()) {
            List<Long> lost = new ArrayList<>();
            for (Long id : voById.keySet()) {
                if (!visited.contains(id)) {
                    lost.add(id);
                }
            }
            log.warn("分类树里有 {} 个节点没能挂上（多半是 parent_id 成环或指向了已被删除的分类）：{}",
                    lost.size(), lost);
        }

        return roots;
    }

    @Override
    public Category getById(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }
        return category;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现是「一次广度优先遍历」而不是递归，理由只有一个：
     * <b>遍历的深度由数据决定，不由「最多两级」这条规则决定</b>。
     * 规则是 Service 自己定的，但库里可能有手工 SQL 写进去的三级、四级 ——
     * 那时递归会写出一个和数据库结构一样深的调用栈。
     * 队列版本对深度不敏感，而它并不比递归难写。
     *
     * <p>★ {@code seen} 同样是防环的，不是装饰。见类注释里那段推理。
     */
    @Override
    public List<Long> selfAndDescendantIds(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }

        // ★★ 这里【只能】用 selectIdAndParent，绝不能用任何带 status = 1 的查询。
        //    用启用列表算后代，会让「被禁用的子分类」下面的商品
        //    从「启用的父分类」的筛选结果里静默消失 ——
        //    点开「手机数码」少了二十件，没人会想到这和分类的启用状态有关。
        //    分类禁用只该管住导航，不该把商品从父分类里藏起来。
        List<Category> edges = categoryMapper.selectIdAndParent();

        Map<Long, List<Long>> childrenOf = new HashMap<>();
        Set<Long> known = new HashSet<>();
        for (Category edge : edges) {
            known.add(edge.getId());
            childrenOf.computeIfAbsent(parentOf(edge.getParentId()), k -> new ArrayList<>())
                    .add(edge.getId());
        }

        // 分类不存在 → 返回空集合。
        //
        // ★ 这个「空」是一句真话，不是一个错误码：「用一个不存在的分类去筛商品」
        //   本来就该筛出 0 件。它同时也把 IN () 这个语法错误挡在了门外 ——
        //   调用方看到空集合必须【短路成空页】，不能把空集合发给 SQL。
        //   两个 ProductServiceImpl 里都有这一步，而且都有注释说明。
        if (!known.contains(categoryId)) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!seen.add(current)) {
                continue;   // 已经处理过（或是个环），跳过
            }
            ids.add(current);
            for (Long child : childrenOf.getOrDefault(current, List.of())) {
                queue.add(child);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveDTO dto) {
        String name = dto.getName().trim();
        checkNameDuplicate(name, null);

        Long parentId = normalizeParentId(dto.getParentId());
        // 新增时 id 还不存在，所以规则 1（自己挂自己）无从违反 ——
        // 但传一个负数或不存在的 id 仍然会被规则 2 拒掉
        checkParent(null, parentId);

        Category category = new Category();
        category.setParentId(parentId);
        category.setName(name);
        category.setSort(dto.getSort());
        // 前端没传 status 时默认启用。
        // 这种「兜底默认值」放在 Service 而不是 DTO 里，
        // 是因为 DTO 只管「接住请求参数」，默认值的语义属于业务规则
        category.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        categoryMapper.insert(category);
        log.info("新增分类成功，id={}, name={}, parentId={}", category.getId(), name, parentId);

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

        Long parentId = normalizeParentId(dto.getParentId());

        // ★ 规则 5（先于 checkParent 执行，顺序是有意的）：
        //   一个有子分类的分类【永远是根】，不许再挂到别人下面。
        //
        //   为什么先判断它？因为两条规则同时被违反时，
        //   这一条给出的提示更接近用户真正该做的事：
        //   「先处理你的子分类」，而不是「你选的上级不合法」。
        //
        //   它拦住的是环，而且是唯一需要在这里判断的那一半：
        //   另一半（「子分类不能当父」）由 checkParent 的规则 4 挡住。
        //   完整的完备性推理在类注释里。
        if (parentId != ROOT_PARENT_ID && categoryMapper.countByParentId(id) > 0) {
            throw new BusinessException(ResultCode.CATEGORY_LEVEL_INVALID,
                    "该分类下还有子分类，不能再挂到别的分类下面 —— "
                            + "否则它的子分类就变成三级了（分类最多两级）");
        }

        checkParent(id, parentId);

        Category category = new Category();
        category.setId(id);
        category.setParentId(parentId);
        category.setName(name);
        category.setSort(dto.getSort());
        category.setStatus(dto.getStatus());

        categoryMapper.updateById(category);
        log.info("修改分类成功，id={}, name={}, parentId={}", id, name, parentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (categoryMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }

        // ★★ 检查顺序：先子分类（1011），后商品（1004）。
        //    结构性问题（还有挂在它下面的分类）优先于内容性问题（还有商品）。
        //
        //    为什么子分类这条不能省：全库零外键是既定约定（见 README），
        //    所以数据库【不会】阻止「删掉父分类、子分类还指着它」。
        //    留下的脏数据不会报错：那些子分类的 parent_id 指向一个不存在的 id，
        //    于是它们既不是根、也不在任何根的子树里 ——
        //    【从商城导航里消失，而库里还在、商品还挂着】。
        //    数据没坏、页面少东西，这是最难查的一类 bug。
        long childCount = categoryMapper.countByParentId(id);
        if (childCount > 0) {
            // 报错信息里带上具体数量，运营才知道要处理多少条（和 1004 同一种做法）
            throw new BusinessException(ResultCode.CATEGORY_HAS_CHILD,
                    "该分类下还有 " + childCount + " 个子分类，请先删除或移走这些子分类");
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
        //
        //   ★ 里程碑 16 又给「拒绝删除」加了第二条理由（上面那个 1011）。
        //     两条合起来说明了一件事：**在这张表上，「删除」这个动作
        //     要问的问题不止一个，而且每加一种引用关系就要加一个问题。**
        //     这正是「全库零外键」这条约定要付的账 —— 换来的是
        //     删除顺序和删除规则都明明白白写在代码里，而不是藏在
        //     某个 ON DELETE CASCADE 里。
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
     * 把「没传上级」归一成 0（一级分类）。
     *
     * <p>★ 归一逻辑<b>只有这一处</b>。create 和 update 都走它，
     * 所以「没传 = 一级分类」这个约定在整条链路上只有一个定义者。
     *
     * <p>为什么「不传」要等于 0 而不是「保持原样」：因为
     * {@code parent_id} 是 {@code NOT NULL DEFAULT 0}，
     * 「没有父」在这张表里本来就是一个<b>确定的值</b>，
     * 不是一种缺席（对照 {@code market_price} 那种真正可空的列）。
     * PUT 是全量替换，没传就是 0。见 {@link CategorySaveDTO#getParentId()}。
     */
    private Long normalizeParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    /**
     * 校验上级分类（规则 1~4）。
     *
     * <p>★ 规则 1~4 全部是「关于上级」的判断，所以它们住在同一个方法里；
     * 规则 5（关于自己的子分类）留在 {@code update} 里，
     * 因为它只在修改时成立（新增的分类不可能已经有子分类）。
     * 这条分界是有意的：<b>同一个方法里的判断，应该能被同一句话概括。</b>
     *
     * @param selfId   要修改的分类 id；新增时传 null
     * @param parentId 归一后的上级 id，0 表示一级分类
     */
    private void checkParent(Long selfId, Long parentId) {
        if (parentId == ROOT_PARENT_ID) {
            return;   // 一级分类：没有上级要校验，规则 2~4 全部不适用
        }

        // 规则 1：不能挂到自己下面
        if (parentId.equals(selfId)) {
            throw new BusinessException(ResultCode.CATEGORY_LEVEL_INVALID,
                    "不能把分类挂到它自己下面");
        }

        // 规则 2：上级必须存在
        Category parent = categoryMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(ResultCode.CATEGORY_PARENT_INVALID,
                    "上级分类不存在，请重新选择");
        }

        // 规则 3：上级必须启用 —— 挂到一个商城页看不见的分支下面，
        //        商品会跟着从导航里消失，而没有任何一层会报错
        if (!isEnabled(parent)) {
            throw new BusinessException(ResultCode.CATEGORY_PARENT_INVALID,
                    "上级分类「" + parent.getName() + "」已禁用，不能再往它下面挂分类");
        }

        // 规则 4：上级自己必须是根 —— 这一条挡住三级
        if (parentOf(parent.getParentId()) != ROOT_PARENT_ID) {
            throw new BusinessException(ResultCode.CATEGORY_LEVEL_INVALID,
                    "分类最多两级：「" + parent.getName() + "」自己已经有上级分类了，"
                            + "不能再往它下面加一级");
        }
    }

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
     * <p>★ 里程碑 16 的补充，而且是一个反直觉的结论：
     * <b>分类变成两级之后，这条规则【没有变，也不该变】。</b>
     * 「手机」和「手机壳」不能同时存在，哪怕它们分属不同的父分类 ——
     * 因为 {@code uk_name} 是整表唯一的，而且下游（商品表单、商城导航）
     * 到处都在用分类名做展示和查找（{@code Home.vue} 的 banner 就是按名字找的）。
     * 一个「只在同一个父下面唯一」的规则，会让这些按名字查找的地方
     * 静默地找到错的那一个。
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

    // ------------------------------------------------------------------
    // 组树用的小工具
    // ------------------------------------------------------------------

    /**
     * 把「归属哪个父」统一成一个非 null 的 {@code Long}。
     *
     * <p>为什么需要它：{@code parentId} 在 <b>Entity</b> 上是可空的
     * （新增时没传就是 null，Service 归一之后才写库），
     * 而用它做 Map 的 key 时 null 和 0 会变成两个不同的桶 ——
     * 于是「一级分类」会在 childrenOf 里被拆成 {@code null} 和 {@code 0} 两份，
     * 组出来的树掉一半，而且不报错。
     *
     * <p>这类 bug 的位置很典型：<b>它不在任何一条业务规则里，
     * 而在「同一个概念有两种表示法」这件事上。</b>
     * 所以宁可多写这个两行的方法，也不要在六个地方各自 {@code != null} 一下。
     */
    private Long parentOf(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    private boolean isEnabled(Category c) {
        return c.getStatus() != null && c.getStatus() == 1;
    }

    private Map<Long, Category> indexById(List<Category> all) {
        Map<Long, Category> byId = new HashMap<>();
        for (Category c : all) {
            byId.put(c.getId(), c);
        }
        return byId;
    }

    /**
     * Entity → 树节点。
     *
     * <p>字段是一个一个搬的，不是 {@code BeanUtils.copyProperties}。
     * 这样做的代价是「给 Category 加字段时要记得来这里补一行」——
     * 和 {@code SkuVO.fill} 是同一个坑，那里的 javadoc 写过：
     * <b>漏了就是字段恒 null，而 {@code non_null} 会让它从响应里整个消失：
     * 接口 200、页面少一列。</b>
     *
     * <p>换了 {@code BeanUtils} 就不用记这件事了，但代价更大：
     * 它会把 {@code Category} 上<b>将来新增的每一个字段</b>自动带进树里，
     * 包括那些不该给前端看的。详见 {@link CategoryTreeVO} 里
     * 「加在父类上的字段会同时流向两个出口」那段。
     * 一边是「漏一个字段」，一边是「多一个字段」，后者不会报错也不会被发现 ——
     * 所以这里选前者，并且把「漏字段」这件事交给测试断言去盯。
     */
    private CategoryTreeVO toVO(Category c) {
        CategoryTreeVO vo = new CategoryTreeVO();
        vo.setId(c.getId());
        vo.setParentId(c.getParentId());
        vo.setName(c.getName());
        vo.setSort(c.getSort());
        vo.setStatus(c.getStatus());
        vo.setCreateTime(c.getCreateTime());
        vo.setUpdateTime(c.getUpdateTime());
        return vo;
    }

    /** 这个分类是否命中筛选条件 */
    private boolean matches(Category c, CategoryQueryDTO query) {
        if (query.getName() != null
                && (c.getName() == null || !c.getName().contains(query.getName()))) {
            return false;
        }
        return query.getStatus() == null || query.getStatus().equals(c.getStatus());
    }

    /**
     * 把 id 的整棵子树加进 keep。
     *
     * <p>{@code keep.add} 的返回值顺手当了防环：已经加过的 id 返回 false，
     * 递归当场停住（脏数据成环时不会无限递归）。
     * 能这么写是因为「加过」和「访问过」在这里是同一件事。
     */
    private void addSubtree(Long id, Map<Long, List<Category>> childrenOfSource, Set<Long> keep) {
        for (Category child : childrenOfSource.getOrDefault(id, List.of())) {
            if (keep.add(child.getId())) {
                addSubtree(child.getId(), childrenOfSource, keep);
            }
        }
    }

    /**
     * 把 c 的祖先链加进 keep（命中的节点要挂在一个看得见的位置上）。
     *
     * <p>{@code visited} 防环：正常数据 1 步就结束
     * （因为最多两级），这里防的是手工 SQL 造的环。
     */
    private void addAncestors(Category c, Map<Long, Category> byId, Set<Long> keep) {
        Set<Long> visited = new HashSet<>();
        visited.add(c.getId());
        Long p = parentOf(c.getParentId());
        while (p != ROOT_PARENT_ID && visited.add(p)) {
            keep.add(p);
            Category parent = byId.get(p);
            p = parent == null ? ROOT_PARENT_ID : parentOf(parent.getParentId());
        }
    }

    /**
     * c 的祖先链是不是每一个都启用。
     *
     * <p>见 {@link #listEnabled()} 的 javadoc：这是「可见」的后半个条件，
     * 少了它，一个「父分类被禁用、自己还启用」的分类会从导航里凭空消失。
     */
    private boolean allAncestorsEnabled(Category c, Map<Long, Category> byId) {
        Set<Long> visited = new HashSet<>();
        visited.add(c.getId());
        Long p = parentOf(c.getParentId());
        while (p != ROOT_PARENT_ID) {
            if (!visited.add(p)) {
                // 环。正常数据走不到（规则 1+4+5 不允许），
                // 但脏数据可能造成死循环。
                // ★ 这里返回 false 而不是抛异常：商城页读到一个坏分类，
                //   应该让【那一个分支】从导航里消失，而不是让整个页面 500。
                //   分类是导航的一部分，为它牺牲整个首页不值得。
                log.warn("分类 {} 的祖先链上出现了环，请检查 category.parent_id", c.getId());
                return false;
            }
            Category parent = byId.get(p);
            if (parent == null) {
                return false;   // parent_id 指向一个不存在的分类
            }
            if (!isEnabled(parent)) {
                return false;
            }
            p = parentOf(parent.getParentId());
        }
        return true;
    }

    /** 从 node 往下挂 children。{@code visited} 防环，见 treeAll 里的长注释 */
    private void attachChildren(CategoryTreeVO node,
                                Map<Long, List<CategoryTreeVO>> childrenOf,
                                Set<Long> visited) {
        for (CategoryTreeVO child : childrenOf.getOrDefault(node.getId(), List.of())) {
            if (!visited.add(child.getId())) {
                continue;   // 环，跳过（treeAll 末尾会把这件事记进日志）
            }
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }
            node.getChildren().add(child);
            attachChildren(child, childrenOf, visited);
        }
    }
}
