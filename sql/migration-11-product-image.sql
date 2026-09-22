-- ============================================================================
--  迁移脚本 11：新建 product_image 商品图集表
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  product.cover 是一列 VARCHAR(255)，从建表那天起就在，
--  但它从来不是一个「上传」的产物 —— 它是运营在管理后台【手填的一个字符串】。
--  这些手填的路径（/images/phone-03.svg 之类）指向的其实是
--  mall-shop/public/images/ 下的 47 个 SVG，由【用户端的 Vite dev server】提供。
--  后端从来没有提供过静态资源（WebMvcConfig 里只有拦截器，没有 addResourceHandlers）。
--
--  那个设计在当时是对的（gen-shop-assets.py 里专门写了一段解释
--  「为什么后端一行都不用改」），但它有一个天花板：
--  【图片必须是随工程走的静态文件】。运营想给商品换一张真实的图，
--  只能自己去改前端源码目录，或者找一张已经在网上的图贴链接。
--
--  这个脚本补上其中【数据】的那一半：商品可以有一组图，并且这一组图有顺序。
--  另一半（文件落盘、静态资源映射、上传接口）在 Java 代码里。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是「DROP 掉所有表再重建」的初始化脚本。
--  库里已经有自己录入的 5 件商品和其他数据，跑它会全部清空。
--  约定（从里程碑 8 起）：schema 变更走增量迁移，
--    - mall.sql          → 全量脚本，改它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 增量脚本，改它是为了「已有数据的库能跟上」
--  两份都要改。
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW CREATE TABLE mall.product_image;
--  能查到就说明跑过了。
--
--  ⚠️ 这个脚本和前面几个不同：它是【新建表】而不是 ALTER 加列。
--     MySQL 8 有 CREATE TABLE IF NOT EXISTS，所以重复执行不会报错，
--     而是静默地什么都不做（只会打一条 Note）。
--     这里【故意不用】IF NOT EXISTS —— 重复跑说明有人搞错了状况，
--     当场报错让人看一眼，比静默跳过更安全。
--
--  【★★ 为什么故意不加外键？】
--    这是全库一致的约定（7 张表一个外键都没有，这张表是第 8 张）。
--    理由和 order_item 完全一样，一句话：
--      **加外键之后，「删商品」会变成一条可能失败的语句，
--        而数据库约束拦下来的错误只能翻译成一句「系统繁忙」。**
--
--    展开说：
--      - 外键拦的是「这个商品还有图集」，但这个信息应用层【已经知道】，
--        它完全可以在删之前先查出图集、给出「请先删除该商品的图片」这种有用的提示。
--        有外键的话，那句 100 字的提示会退化成一个 SQL 约束异常。
--      - 而且外键把「删除的顺序」变成一个隐式的、看不见的规则。
--        没有外键时，顺序写在 Java 代码里（ProductServiceImpl.delete），
--        读代码的人必须显式地想一遍「先删哪个」—— 这个思考是好事，不是负担。
--
--    ⚠️ 代价必须说清楚：数据库不替你拦，你自己就得更小心。
--       **删商品的顺序必须是：先删图集行，再删商品行。** 反了的话中途失败
--       会留下孤儿图集行，而没有任何东西会报错。测试脚本里有一条
--       LEFT JOIN 找孤儿的断言就是为这件事准备的。
--
--  【为什么 sort_no 这一列可以有，而上一轮的 cancel_type 被拒了？】
--    判断标准一直没有变过，一句话：**有读者才加列。**
--      - sort_no：用户端详情页要按这个顺序展示图集，管理端有「上移 / 下移」
--        两个按钮去改它，而且下次打开还要原样看到 → 有读者，加。
--      - cancel_type（里程碑 9）：没有任何代码或界面会去分支判断它 → 无读者，不加。
--    同一条标准，两个相反的结论，区别只在「有没有人真的会去读它」。
--
--  【为什么不给 url 加唯一索引？】
--    同一张图（同一个 /uploads/... 路径）被两个商品引用是【允许】的 ——
--    图集是「引用」，不是「所有权」。而且真加了唯一索引，反而是把
--    「刷新一次页面重复提交」这种前端问题变成一个数据库报错。
--
--  【要不要给 product_id 加索引？加。】
--    这里和 migration-10 那次「不加索引」的结论不同，因为查询形态不同：
--      - migration-10 的新查询是「按 member_id + status 分页」，
--        而 idx_member_id / idx_status 已经存在 → 不用加。
--      - 这张表的【唯一】查询形态就是 WHERE product_id = ?（详情页每次都查），
--        而新表一个索引都没有 → 必须加。
--    判据不是「表变大了要加索引」，而是「有没有一条查询会用到它」。
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 mall < migration-11-product-image.sql
-- ============================================================================

SET NAMES utf8mb4;
USE mall;

-- ---------------------------------------------------------------------------
-- 1. 改动前的样子（记下来，方便和改完后对照）
-- ---------------------------------------------------------------------------
SELECT '改动前' AS msg;

SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall'
ORDER BY TABLE_NAME;


-- ---------------------------------------------------------------------------
-- 2. 建表
-- ---------------------------------------------------------------------------
CREATE TABLE product_image (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id  BIGINT UNSIGNED NOT NULL                COMMENT '所属商品 id',
    url         VARCHAR(255)    NOT NULL                COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png',
    sort_no     INT             NOT NULL DEFAULT 0      COMMENT '展示顺序，从 0 开始',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品图集';

-- 关于两个字段长度的说明：
--   url 用 VARCHAR(255) 是跟着 product.cover 来的（它也是 255）。
--   /uploads/2026/09/<36 位 UUID>.png 大约 45 个字符，255 有大量余量。
--   这里刻意【不】写 TEXT —— 一个明确的长度上限本身就是一条校验，
--   将来有人想往这个列塞一整段 base64 图片数据时，会当场失败而不是悄悄存进去。


-- ---------------------------------------------------------------------------
-- 3. 检查迁移结果
-- ---------------------------------------------------------------------------
SELECT '迁移完成，下面是验证结果' AS msg;

SHOW CREATE TABLE product_image;

-- ★ 核对整库表数量：应该从 7 张变成 8 张
SELECT COUNT(*) AS 表数量 FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'mall';

-- 新表是空的（图集只能由上传产生，没有任何种子数据）
SELECT COUNT(*) AS 图集行数 FROM product_image;

-- ★ 核对「没有外键」这条约定：下面这条查询必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';
