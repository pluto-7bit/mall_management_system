-- ============================================================================
--  迁移脚本 14：分类多级 —— 【只加一列】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 15 为止，category 是一张【完全扁平】的表：
--  6 行分类，彼此之间没有任何关系，product.category_id 指向其中之一。
--  于是「休闲零食」和「手机数码」在模型里是同一个层级的概念，
--  而真实的商品目录是「手机数码 → 手机壳」「服饰鞋包 → 男装 → 衬衫」这样的树。
--
--  这一轮加一列 parent_id，让分类能挂父分类；
--  「筛父分类时包含它下面所有后代」这件事随之成立。
--
--  【★ 为什么是 NOT NULL DEFAULT 0，而不是允许 NULL？】
--  这一条直接从里程碑 13 的 spec_json 继承过来，那里踩过一次：
--    「MySQL 把多个 NULL 当成互不相等」
--  —— 对一个「可以为空的父」来说，这个特性会直接长出事实错误：
--    SELECT ... WHERE parent_id IS NULL 和 WHERE parent_id = 0
--    会在几个文件之间分岔，而两者都能「看起来正常工作」，直到某一天
--    「这个分类到底算不算一级分类」在两个页面上的答案不一样。
--
--  用 0 表示「没有父」，就是把「没有父」当成一个【确定的值】而不是【缺席】。
--  ⚠️ 注意这和同一轮 migration-14b 里 market_price / cost_price 的选择
--     【故意相反】—— 那两列要 DEFAULT NULL。理由在 14b 的头部：
--     「0 是一个值，NULL 是缺席，这两种东西不该共享一个编码」。
--     判断标准是：这个 0 会不会被当成一个【真实存在的数据】去比较和计算。
--     parent_id = 0 永远不会被拿去 join 一个真实分类（id 从 1 开始）；
--     而 market_price = 0 会被拿去和售价比大小。
--
--  ★ 这个 DEFAULT 0 还带来一个白拿的好处，值得单独说：
--    **已有的 6 行分类一个字都不用改。** 它们本来都是一级分类，
--    0 就是它们的真值 —— 所以本脚本里【没有任何 UPDATE】。
--    同理，mall.sql 的种子区（INSERT INTO category (name, sort, status)）
--    也一个字不用改，DEFAULT 会把它兜住。
--    这就是里程碑 13 立下的那条结论的第二次应验：
--      **「加列」不影响任何现有代码，「收紧约束」才影响。**
--
--  【★★ 深度上限两级，但这条规则【不在这个脚本里】，也不该在数据库里】
--  规则是：parent_id 要么是 0，要么指向一个「自己也是 0」的分类。只有一级和二级。
--
--  为什么不许更深：**前端只画两级**（顶部导航 → 左侧栏 / 商品表单的分类选择器）。
--  如果数据库允许三级，商家在后台建了「手机 → 手机壳 → 硅胶壳」，
--  用户在商城页只能看到前两级，**第三级凭空消失，而且没有任何一层会报错**。
--  ——一条会报错的规则，胜过一片会消失的分类。
--  所以三级会被 Service 拒绝（错误码 1009），而不是被静默截断。
--
--  ⚠️ 但对这个脚本要诚实：**数据库拦不住三级。**
--     手工 INSERT 一个 parent 自己还有 parent 的行，这个脚本的 DDL 照样接受。
--     「最多两级」是一条【业务规则】，它住在 CategoryServiceImpl 里，
--     由 sql/test-category.py 断言。下面是几条查询，用来在迁移后
--     核对【规则确实守住了】（正常情况下它们必须全部返回空）。
--
--  【为什么加 idx_parent？这张表才 6 行，索引没有性能意义】
--  不装糊涂：**6 行数据上加索引确实什么都换不来。**
--  加它的理由是「按父查子」是这张表【唯一】的读法（组装树、算后代集合都靠它），
--  而这张表的 idx_sort 也是同一个性质的东西（排序值上的索引，同样换不来什么）。
--  也就是说：这是为了表达「这张表怎么被访问」，不是为了今天的速度。
--  （★ 对照里程碑 13 的 uk_product_spec —— 那个索引是【为了正确性】必须有的，
--     和这个纯声明性的索引不是一回事，注释里不该混为一谈。）
--
--  【为什么不加外键？】
--  全库零外键是既定约定（见 README）。代价是「父分类被删了、子分类还指着它」
--  这种脏状态数据库不管 —— 所以 Service 里必须有一条规则拦住它
--  （有子分类时拒绝删除，错误码 1011）。下面有一条查询专门核对没有游离的 parent_id。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是「DROP 掉所有表再重建」的初始化脚本，库里已经有你自己录入的商品、
--  真实订单和评价，跑它会全部清空。约定（从里程碑 8 起）：
--    - mall.sql          → 全量脚本，改它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 增量脚本，改它是为了「已有数据的库能跟上」
--  两份都要改（本脚本对应 mall.sql 里 category 的建表段）。
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW COLUMNS FROM mall.category LIKE 'parent_id';
--    SHOW CREATE TABLE mall.category;
--
--  ⚠️ 这个脚本故意【不用】ADD COLUMN IF NOT EXISTS：
--     重复跑说明有人搞错了状况，当场报错让人看一眼，比静默跳过更安全。
--     （这是 migration-11 / 12 立下的先例。）
--
--  ★ 这个脚本是【只加不删】的，所以它没有 13b 那种「不可回滚的最后一步」。
--     跑完它，后端一行代码都不用改 —— 功能完全不变，
--     因为没有任何现有 SQL 会碰到 parent_id 这一列。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. 加列 + 加索引（一条 ALTER，改 schema 一次到位）
-- ---------------------------------------------------------------------------
--
-- AFTER id：把 parent_id 放在 id 后面而不是表尾。这不是洁癖 ——
-- 分类是层级数据，id 和 parent_id 是同一个概念的两半，
-- 中间隔着 name/sort/status 会让每次 SHOW CREATE TABLE 都要来回找。
-- （migration-13 把 spec_schema 放在 description 后面，用的是同一条判据：
--  按【读的人会怎么理解这张表】排，而不是按【什么时候加的】排。）
ALTER TABLE category
    ADD COLUMN parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '上级分类 id；0 = 一级分类（刻意不用 NULL，见 migration-14 头部）' AFTER id,
    ADD KEY idx_parent (parent_id);


-- ---------------------------------------------------------------------------
-- 2. 迁移完成
-- ---------------------------------------------------------------------------
SELECT '=== 迁移 14 完成，下面是验证结果 ===' AS msg;


-- ---------------------------------------------------------------------------
-- 3. 验证：列与索引的形态
-- ---------------------------------------------------------------------------

SHOW CREATE TABLE category;

-- ★ 核对列的形态：IS_NULLABLE 必须是 NO，DEFAULT 必须是 0
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'category'
ORDER BY ORDINAL_POSITION;

-- ★ 核对 idx_parent 存在，且【不是】唯一索引
--   （唯一索引会禁止「两个分类挂在同一个父下」，那正好是我们要允许的）
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'category'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;


-- ---------------------------------------------------------------------------
-- 4. ★★ 验证：迁移后的数据状态
-- ---------------------------------------------------------------------------

-- 现在的 6 个分类长什么样。全部 parent_id = 0（一级分类），
-- 这是【正确的】—— 本轮不动任何分类，现成的 6 个本来就是同一层级。
SELECT id, name, parent_id, sort, status FROM category ORDER BY parent_id, sort, id;

-- ★ 非根分类数 = 0（刚迁移完，还没有人挂过父分类）
SELECT COUNT(*) AS 非根分类数 FROM category WHERE parent_id <> 0;


-- ---------------------------------------------------------------------------
-- 5. ★★ 验证：四条规则的现状（下面每条都必须返回 0 行）
-- ---------------------------------------------------------------------------
--
-- 这四条查询不是「迁移是否成功」的检查 —— 迁移只加了一列，不可能失败。
-- 它们是【把 Service 里那四条业务规则写成 SQL】，用来核对库里的数据
-- 确实符合规则。等 sql/test-category.py 开始造两级分类之后，
-- 这四条会被反复用到（那时它们返回 0 行才是对的）。

-- 规则 A：没有 parent_id 指向一个不存在的分类（游离的父）
SELECT c.id AS 父分类不存在的分类, c.name, c.parent_id
FROM category c
    LEFT JOIN category p ON p.id = c.parent_id
WHERE c.parent_id <> 0 AND p.id IS NULL;

-- 规则 B：没有三级（父分类自己还挂着父）
SELECT c.id AS 三级分类, c.name
FROM category c
    JOIN category p ON p.id = c.parent_id
WHERE c.parent_id <> 0 AND p.parent_id <> 0;

-- 规则 C：没有自己挂自己
SELECT c.id AS 挂到自己的分类, c.name
FROM category c
WHERE c.parent_id = c.id AND c.parent_id <> 0;

-- 规则 D：没有父分类被禁用而子分类却启用（那种子分类在商城页会【凭空消失】，
--         因为它既不是根、也不出现在任何根的 children 里 —— 静默失败）
SELECT c.id AS 父分类已禁用的启用分类, c.name
FROM category c
    JOIN category p ON p.id = c.parent_id
WHERE c.parent_id <> 0 AND c.status = 1 AND p.status = 0;


-- ---------------------------------------------------------------------------
-- 6. 验证：约定与用户数据
-- ---------------------------------------------------------------------------

-- ★ 核对「没有外键」这条约定：必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';

-- ★ 核对用户的原有数据没被动过。
--   本脚本没有任何 UPDATE / DELETE，所以这些数字必须和跑之前一模一样。
SELECT COUNT(*) AS 分类数 FROM category;
SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS SKU数  FROM product_sku;
SELECT COUNT(*) AS 会员数 FROM member;
SELECT COUNT(*) AS 订单数 FROM orders;
SELECT COUNT(*) AS 评价数 FROM product_review;

SELECT '=== 14 完成。后端此刻完全不受影响（没有任何 SQL 碰过 parent_id）。===' AS msg;
SELECT '=== 下一步：第 2 阶段改后端（CategoryTreeVO / 五条规则 / 含后代筛选）。===' AS msg;
