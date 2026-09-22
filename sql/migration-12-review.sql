-- ============================================================================
--  迁移脚本 12：新建 product_review 商品评价表 + product_review_image 评价晒图表
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 11 为止，这个系统已经能走完一整条交易闭环：
--  看商品 → 加购 → 下单 → 付款 → 发货 → 确认收货。
--  但闭环走完之后【什么都没有留下】—— 一个会员买过什么、用得怎么样，
--  系统一概不知道；商品详情页永远只有一个孤零零的描述。
--
--  这个脚本补上交易闭环的最后一块【数据】：确认收货之后，
--  买家能给商品打星、写评价、晒最多 3 张图；所有人都能在详情页看到这些评价。
--
--  ★ 它和前 11 个里程碑最不一样的地方：
--    前 11 轮功能的输入都来自「用户正在做的这件事」（填个表单、点个按钮）。
--    而**评价的输入不在评价这个动作里 —— 它在订单里**。
--    「这个人能不能评这个商品」不是他填了什么，而是他从订单域推导出来的一个资格。
--    所以这一轮真正的知识点不是 CRUD，是【从另一个领域推导出的业务规则】。
--    那一半在 Java 代码里（ProductReviewServiceImpl），这个脚本只负责把
--    「一个订单明细只能有一条评价」这条不变量落到数据库上。
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
--    SHOW CREATE TABLE mall.product_review;
--    SHOW CREATE TABLE mall.product_review_image;
--  能查到就说明跑过了。
--
--  ⚠️ 这个脚本是【新建表】而不是 ALTER 加列（和 migration-11 同类）。
--     MySQL 8 有 CREATE TABLE IF NOT EXISTS，所以重复执行不会报错，
--     而是静默地什么都不做（只会打一条 Note）。
--     这里【故意不用】IF NOT EXISTS —— 重复跑说明有人搞错了状况，
--     当场报错让人看一眼，比静默跳过更安全。
--
--  【★★★ uk_order_item 这个唯一索引是这张表存在的理由，不是附带的优化】
--    用户对评价的选择是「不能改也不能删，一次定终身」。而
--    「一个订单明细只能有一条评价」这件事，**只有数据库能真的保证**：
--    Service 里那句「先查有没有评过」挡不住并发 ——
--    两个请求可以【同时】查到「没评过」，然后两个都往下走。
--
--    这和里程碑 8 的下单幂等键 uk_member_idempotency (member_id, idempotency_key)
--    是同一个手法：**把「不可能发生」交给数据库。**
--    代价是一个索引，收益是这个不变量【永远】成立 ——
--    不管将来谁写了什么代码、绕过哪一层、并发有多高。
--
--    ⚠️ 代价也要说清楚：管理端删除评价是【物理删除】，删掉之后这个槽位就
--       空出来了，那个会员可以重新评价这条明细。这个语义是合理的
--       （被删掉的违规评价不该永久剥夺他重写的权利），但它是「删除」这个
--       动作的一个真实后果 —— 测试脚本里有一条用例专门锁住它。
--
--  【★ 为什么 product_id / member_id 是冗余列？（它们能从 order_item_id 推出来）】
--    判断标准一直是那一条：**有读者才加列。**
--      - product_id：商品详情页的评价列表和星级聚合，每个访客每次打开都要用。
--      - member_id ：用户端列表要 LEFT JOIN member 拿昵称；管理端「按会员筛选」
--        写成 member_id IN (SELECT id FROM member WHERE ...)（订单管理端的现成范式）。
--    两列都有确定的、高频的读者，所以加。
--
--    ★ 而冗余唯一的危险是「两个地方会不一致」，在这个场景里【它不存在】：
--      order_item 行一旦写下就【永不修改】—— 它的 product_id 是
--      「去查原始商品的线索」，不是会被改的快照字段。
--      所以从它推导出来的这两列也永不改变。
--      **源头不可变时，冗余没有代价。**
--
--  【★ 为什么 product_review_image 没有 sort_no？（product_image 有）】
--    这是和 migration-11 刻意的一处【对照】，判据还是那一条：有没有读者。
--      - product_image.sort_no：图集有上移/下移两个按钮，顺序是
--        「用户能改、下次打开还要原样看到」的东西 → 有读者，加。
--      - 评价晒图：评价一旦提交就不可修改，**没有任何界面能让用户调整顺序**
--        → 「顺序」这件事没有读者，不加。
--    展示顺序 = 上传顺序 = 插入顺序，ORDER BY id 就够了
--    （id 是唯一的，天然满足「ORDER BY 必须以唯一列结尾」那条规矩）。
--    **同一个形态的东西第二次出现时，该复用的复用、该分岔的分岔，
--      理由都要重新问一遍，不能因为「上次加了这次也加」。**
--
--  【为什么 rating 不加 CHECK (rating BETWEEN 1 AND 5)？】
--    uk_order_item 已经开了「让数据库兜底」的先例，为什么这里不照做？
--    区别在**这两件事是不是并发问题**：
--      - 「一条明细只能评一次」：并发挡不住 → 必须靠唯一索引。
--      - 「rating 在 1~5 之间」：不是并发问题 → DTO 上的 @Min(1) @Max(5) 就够了，
--        校验失败还能给出「评分最低 1 星」这种说得清的错误信息。
--    而且全库 10 张表现在一个 CHECK 都没有（orders.status 也是 TINYINT 无 CHECK），
--    破例要先有理由。
--
--  【★★ 为什么故意不加外键？】
--    这是全库一致的约定（这张表之前有 8 张，这两张是第 9、10 张）。
--    理由和 order_item / product_image 完全一样，一句话：
--      **加外键之后，「删商品」会变成一条可能失败的语句，
--        而数据库约束拦下来的错误只能翻译成一句「系统繁忙」。**
--
--    ⚠️ 代价必须说清楚：数据库不替你拦，删商品时的顺序就【必须自己守】：
--        晒图 → 评价 → 图集 → 商品
--      四级，一层都不能反（ProductServiceImpl.delete）。反了的话中途失败
--      会留下孤儿行，而没有任何东西会报错。测试脚本里两条 LEFT JOIN
--      找孤儿的断言就是为这件事准备的。
--
--  【要不要加索引？加哪几个？】
--    判据不是「表变大了要加索引」，而是「有没有一条查询会用到它」。
--      - uk_order_item (order_item_id)：唯一索引，见上面。写评价时必查。
--      - idx_product (product_id, create_time)：详情页每一次打开都要
--        「按商品分页、按时间倒序」，这是一个典型的复合索引场景 ——
--        第二个列直接让 ORDER BY 免排序。
--      - idx_member (member_id)：管理端「按会员筛选」会用到。
--      - product_review_image.idx_review_id (review_id)：唯一查询形态就是
--        WHERE review_id IN (...)，必加。
--
--  【为什么不给这两张表写种子数据？】
--    评价是【用户产生的】，全新装出来的库一条评价都不该有 ——
--    和 product_image「图集只能由上传产生」是同一条理由。
--
--    ⚠️ 另外还有一个副作用要躲开：sync-mall-seed.py 的自检里有一句
--       count("INSERT INTO product")，它是**子串匹配**，而这两张新表的名字
--       都【以 product 开头】—— 它们的插入语句会包含那个子串，
--       从而触发一句和真实原因毫无关系的「product 的 INSERT 不止一处」。
--       mall.sql 里已经记录过这个假报警，并且警告过
--       「那条注释本身也不能把那个字符串原样写出来」。
--       不写种子数据，这个坑就根本绕过去了。
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 mall < migration-12-review.sql
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
CREATE TABLE product_review (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_item_id BIGINT UNSIGNED NOT NULL                COMMENT '被评价的订单明细 id（一条明细只能评一次）',
    product_id    BIGINT UNSIGNED NOT NULL                COMMENT '商品 id（从订单明细推导，不是客户端传的）',
    member_id     BIGINT UNSIGNED NOT NULL                COMMENT '评价会员 id（从订单推导，用于昵称 join 和管理端筛选）',
    rating        TINYINT         NOT NULL                COMMENT '评分：1~5 星',
    content       VARCHAR(500)    NOT NULL                COMMENT '评价内容',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item (order_item_id),
    KEY idx_product (product_id, create_time),
    KEY idx_member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品评价';

CREATE TABLE product_review_image (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    review_id   BIGINT UNSIGNED NOT NULL                COMMENT '所属评价 id',
    url         VARCHAR(255)    NOT NULL                COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_review_id (review_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评价晒图';

-- ★★ 顺手改掉两处【列注释里的假话】，它们从里程碑 11 起就是错的：
--    写的是 /uploads/2026/09/22/<uuid>.png，而实际的分目录格式是
--    yyyy/MM（见 FileStorageServiceImpl 的 DIR_FORMAT），没有「日」这一段。
--
--    这条错误例子被抄了 6 遍（两个 Controller、两个 entity、
--    FileStorageService、mall-shop 的 api/review.js），里程碑 12
--    核对上传路径时才第一次发现 —— 因为在这之前没有任何人
--    拿注释里的例子去比对真实路径。
--
--    ⚠️ 值得记下来的是【为什么改源码还不够】：DDL 里的 COMMENT
--    是另一种注释，它被【存进了库里】。改了 .sql 文件只影响
--    以后新建的库；已经建好的库（比如这个）里的列注释
--    仍然是旧的那句，SHOW FULL COLUMNS 和 mysqldump 都会把它导出来。
--    ★ 「注释」一旦落到数据里，它就有了两个副本 —— 两处都要改。
ALTER TABLE product_image
    MODIFY COLUMN url VARCHAR(255) NOT NULL
    COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png';

ALTER TABLE product_review_image
    MODIFY COLUMN url VARCHAR(255) NOT NULL
    COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png';

-- 关于几个字段长度的说明：
--   url 用 VARCHAR(255) 是跟着 product_image.url / product.cover 来的（都是 255）。
--   /uploads/2026/09/<36 位 UUID>.png 大约 45 个字符，255 有大量余量。
--   和 migration-11 一样刻意【不】写 TEXT —— 一个明确的长度上限本身就是一条校验。
--
--   content 用 VARCHAR(500)：DTO 上的 @Size(max = 500) 和它对齐。
--   超过 500 字的评价在这个场景里没有存在的必要，而且 VARCHAR(500) 在
--   utf8mb4 下最多占 2000 字节，仍然在 InnoDB 的行内存储范围内
--   （超过 768 字节的变长列会有一部分溢出到溢出页，但不会退化成 TEXT 的行为）。
--
--   rating 用 TINYINT：值域是 1~5，TINYINT（-128~127）绰绰有余。
--   不用 ENUM('1','2','3','4','5') —— 那会把「星级」这个数值变成字符串，
--   而 AVG(rating) 正是这个功能最核心的查询之一。


-- ---------------------------------------------------------------------------
-- 3. 检查迁移结果
-- ---------------------------------------------------------------------------
SELECT '迁移完成，下面是验证结果' AS msg;

SHOW CREATE TABLE product_review;
SHOW CREATE TABLE product_review_image;

-- ★ 核对整库表数量：应该从 8 张变成 10 张
SELECT COUNT(*) AS 表数量 FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'mall';

-- ★★ 核对本轮的核心不变量：uk_order_item 必须出现在 Key 那一行里。
--    这个索引没了，整个功能的设计前提就没了（见头部那段论证）。
SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_review'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- 两张新表都是空的（评价是用户产生的，没有任何种子数据）
SELECT COUNT(*) AS 评价行数 FROM product_review;
SELECT COUNT(*) AS 晒图行数 FROM product_review_image;

-- ★ 核对「没有外键」这条约定：下面这条查询必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';

-- ★ 核对用户的原有数据没被动过（评价表加进来不影响任何现有表）
SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS 会员数 FROM member;
SELECT COUNT(*) AS 订单数 FROM orders;
