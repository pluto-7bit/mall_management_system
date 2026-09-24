-- ============================================================================
--  迁移脚本 13b：商品多规格（SKU）—— 第二步：【删列 + 收紧】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--  ⚠️⚠️ 这个脚本【不可回滚】。跑之前先把库备份出来。
--
--  【这个脚本在解决什么问题？】
--  migration-13 把价格和库存从 product 搬到了 product_sku，但【故意没删】
--  product.price / product.stock —— 因为那时 Java 侧一行代码都还没改，
--  删了列，任何一处漏改都会变成 Unknown column 的 500，而那时已经退不回去。
--
--  阶段 2~5 之间，那两列以「派生汇总」的身份活着：每次建商品/改商品，
--  Service 都会把 MIN(sku.price) / SUM(sku.stock) 写回去。
--  也就是说，**同一份数据有两个写入者**。
--
--  ★★ 现在删掉它们。这不是「清理遗留」，这是这一轮要的东西本身：
--
--     一个字段只能有一个定义者。留一份「汇总库存」就立刻产生第二个写入者，
--     而库存在事务里被并发扣减（本项目有 20 线程抢库存的测试），
--     汇总必然漂移 —— 而且漂移了【没有任何一层会报错】。
--
--     这件事有实测证据，不是推理。阶段 5 跑全量测试时库里真的出现了
--     一条对不上的数据：商品 683（就是「卫龙辣条」那件种子商品），
--       product.stock = 29   而   product_sku.stock = 30
--     来源是阶段 4 之前下的那一单：老的 decreaseStock 只扣了 product.stock，
--     而阶段 1 回填出来的那条默认 SKU 行停在 30。
--     两个数不同、页面照常显示、接口全部 200。
--     这是这份迁移脚本存在的【全部理由】。
--     （完整记录见 README 的「汇总库存一定会漂移」那一节。）
--
--  【为什么现在是安全的？】
--  阶段 2~5 已经把每一条链路都切到 SKU 上了，并且：
--    ① sql/test-sku.py 的 A 组有一条【静态断言】：mapper XML 里
--       product.price / product.stock 零命中。它是这个脚本的许可证 ——
--       它证明没有任何一条 SQL 还在读那两列。
--    ② Java 侧连 entity.Product 上的 price/stock 字段都删了，
--       编译期就不可能再有人写 product.setPrice(...)。
--  两条都是「先证明、再删」的顺序，不能反过来。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是「DROP 掉所有表再重建」的初始化脚本，库里已经有
--  自己录入的商品、真实订单和评价，跑它会全部清空。
--  约定（从里程碑 8 起）：两份都要改 ——
--    - mall.sql          → 改它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 改它是为了「已有数据的库能跟上」
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW COLUMNS FROM mall.product LIKE 'price';      -- 应报 Empty set
--    SHOW COLUMNS FROM mall.product LIKE 'stock';      -- 应报 Empty set
--    SHOW COLUMNS FROM mall.order_item LIKE 'sku_spec';-- Default 应为 NULL
--
--  ⚠️ 这个脚本做的是 DROP COLUMN。MySQL 8 没有
--     DROP COLUMN IF EXISTS，但这里【也不需要】——
--     重复跑会报 1091 Can't DROP ...，当场报错让人看一眼，
--     比静默跳过更安全（migration-11/12/13 立下的先例）。
--
--  ⚠️⚠️ 跑之前先停后端。理由不是「DDL 会锁表」——
--     是因为此刻磁盘上还有一份【旧 class】：如果后端在迁移之后、
--     重启之前处理一个请求，而那个请求走的是旧代码里的
--     `SELECT p.price`，用户会看到一个 500。停机窗口只有几十秒，
--     但停与不停的区别是「要么没问题，要么有一个未知的窗口」。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 0. 前置检查：确认 13 已经跑过、且此刻两列确实还在
-- ---------------------------------------------------------------------------
-- ★ 先确认自己在正确的状态上。迁移脚本最怕的不是报错，
--   是在【意想不到的库】上"成功"。这三条是身份检查。
SELECT '=== 0. 前置检查 ===' AS msg;

-- 必须存在（13b 要删的就是它）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product'
  AND COLUMN_NAME IN ('price', 'stock')
ORDER BY ORDINAL_POSITION;

-- 必须存在（13 建的）
SELECT COUNT(*) AS product_sku表应存在 FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku';

-- ★★ 备份提醒。下面这条【必须返回 0 行】，否则先停下来备份。
--    判据是「有没有真实订单」—— 商品可以重建，订单不能。
SELECT '⚠️⚠️⚠️ 库里有真实订单，跑下面的语句之前确认已经 mysqldump 过' AS 警告
FROM orders
HAVING COUNT(*) > 0;


-- ---------------------------------------------------------------------------
-- 1. 删掉 product 上那两列
-- ---------------------------------------------------------------------------
SELECT '=== 1. 删除 product.price / product.stock ===' AS msg;

-- ★ 一次 ALTER 删两列，不写两条语句。
--   理由是原子性：只写成一条的话，中途失败不会有「删了一列、另一列还在」
--   这种更难判断的中间态。
--
-- ★ 不写 IF EXISTS 的理由见头部：重复跑应当当场报错。
--
-- ⚠️ 为什么删列要放在【最后】，而不是 13 里一起做？
--   加列不影响任何现有代码；**删列必然影响**。
--   这两件事的时机必须分开：加列可以立刻做（做错了也只是多一列），
--   删列必须等所有读它的代码都没了才做（做错了一次就是 500）。
--   阶段 1 那次「sku_spec 提前 DROP DEFAULT → 每次下单 500」
--   是同一个教训的另一个实例（见 13 的 2.5 节）。
ALTER TABLE product
    DROP COLUMN price,
    DROP COLUMN stock;


-- ---------------------------------------------------------------------------
-- 2. 改 product 的表注释
-- ---------------------------------------------------------------------------
SELECT '=== 2. 表注释改成「价格与库存的唯一真源是 product_sku」 ===' AS msg;

-- ★ 注释不参与任何逻辑判断 —— 这正是它危险的地方：
--   它说假话不会有任何一层报错，只会有下一个人照着它写出错的代码。
--   最坏的情况是有人看到「商品表有价格」就写了 `UPDATE product SET price=...`，
--   而那时这列已经不存在：那还算好的（当场 Unknown column）；
--   更坏的是他把 price 加回来。
--
-- ⚠️ 表注释的写法：MySQL 里改注释必须把【整张表的所有选项重写一遍】，
--   所以下面这行和 mall.sql 里 product 的建表收尾【必须一字不差】。
--   ALTER 时漏掉 CHARSET/ENGINE 不会报错，只会把表悄悄改成默认值。
ALTER TABLE product
    COMMENT = '商品表（价格与库存【不】在这张表上，唯一真源是 product_sku）';


-- ---------------------------------------------------------------------------
-- 3. 收紧 order_item.sku_spec：去掉 DEFAULT ''
-- ---------------------------------------------------------------------------
SELECT '=== 3. order_item.sku_spec 去掉 DEFAULT ===' AS msg;

-- ★ 为什么现在可以收紧了：
--   13 跑完那一刻，Java 侧的 batchInsert 列清单里【没有】sku_spec，
--   所以一个 NOT NULL 且无默认值的列被 INSERT 漏掉 → 严格模式 ERROR 1364
--   → 每一次下单都 500。那时这个默认值是必须留着的（13 的 2.5 节实测踩过）。
--
--   现在每一条写 order_item 的路径都显式填了它的值
--   （OrderServiceImpl 里 sku_spec = SpecJson.text(...)，共两处：
--     正常下单 + 立即购买），所以默认值可以从"必需品"变成"陷阱"。
--
-- ★ 为什么留着它是错的：
--   留着默认值，将来某个【忘了赋值】的下单路径会静默写进空串，
--   然后在订单页显示成「这个商品没规格」—— 用户看到的是错的信息，
--   而没有任何一层报错。去掉它，那种路径会当场 SQL 错。
--
-- ★ 注意这里【不删数据】：历史行里那 5 条 '' 保持原样。
--   它们是 sku_id IS NULL 的那批（阶段 1 回填不出真值的孤儿明细），
--   当时那个商品确实没有规格，'' 就是真值，不是造假。
--   去掉 DEFAULT 只影响【将来】的 INSERT，不碰已有的行。
--   （★ 「加约束」和「改数据」是两件事，这里只做前者。）
--
-- ⚠️ 同样要把整列定义重写一遍。下面这行必须和 mall.sql 逐字一致 ——
--   连注释都【一个字不改】：这次只去掉 DEFAULT，别顺手改文风，
--   否则「两份都要改」的约定就变成了「两份各写各的」。
ALTER TABLE order_item
    MODIFY COLUMN sku_spec VARCHAR(255)    NOT NULL
        COMMENT '规格文本快照，形如「颜色:黑 / 内存:128G」；空串 = 无规格（默认 SKU）';
-- ★ 为什么是快照而不是外键式引用：商品的规格可以被商家改掉
--   （把「128G」下架），而**【已经买过的人看到的必须还是他当时买的那一档】**。
--   订单是历史事实，不该随商品一起变。这和 product_name / price 的快照
--   是同一个理由 —— 里程碑 8 就定下了这条。


-- ---------------------------------------------------------------------------
-- 4. 归一化 product.spec_schema 的 NULL
-- ---------------------------------------------------------------------------
SELECT '=== 4. spec_schema：NULL → [] ===' AS msg;

-- ★ 阶段 1 给 product 加 spec_schema 时没写默认值，于是那 100 行全是 NULL。
--   代码一直把 NULL 当成「无规格」处理（SpecJson.schemaOf），所以
--   功能上一直是好的 —— 但**同一个含义有两种表示**是这类表的长期负担：
--   将来任何一条 `WHERE spec_schema = '[]'` 的查询都会漏掉 NULL 那批，
--   而它不会报错，只会少返回行。
--
-- ★ 归一 = '[]'，不是 ''。因为 '[]' 是能被 JSON.parse 成空数组的合法值，
--   而 '' 不是合法 JSON —— 一个「空字符串」会让解析路径分岔成第二条。
--   能让两种表示变成一种的时候，就别留着两种。
UPDATE product SET spec_schema = '[]' WHERE spec_schema IS NULL;

-- ★ 归一之后要【把列注释也改掉】。原来的注释写的是「NULL = 无规格」，
--   那是归一之前的真话 —— 语句和数据都变了而注释没变，就成了假话。
--   ⚠️ 注释不参与逻辑判断，所以它错了不会有任何一层报错，
--      只会有下一个人照着它写出 `WHERE spec_schema IS NULL`。
--   ⚠️ 这一行必须和 mall.sql 里的列定义逐字一致（同上，MODIFY 要写全）。
ALTER TABLE product
    MODIFY COLUMN spec_schema VARCHAR(500) DEFAULT NULL
        COMMENT '规格定义 JSON，形如 [{"name":"颜色","values":["黑","白"]}]，值的顺序就是前端展示顺序；[] = 无规格（该商品只有一条默认 SKU）';

-- 现在必须返回 0
SELECT COUNT(*) AS 仍为NULL的spec_schema FROM product WHERE spec_schema IS NULL;


-- ---------------------------------------------------------------------------
-- 5. 顺手修两处列注释漂移（与 SKU 无关）
-- ---------------------------------------------------------------------------
SELECT '=== 5. 修 column comment 漂移 ===' AS msg;

-- ★ 这两处是阶段 1 对照 mall.sql 与线上库时发现的：线上库的注释比
--   mall.sql 里少半句。它们不参与任何逻辑，但**注释漂移是"这个库
--   和 mall.sql 已经不是同一份 schema"的第一个信号** ——
--   发现它就该顺手归零，否则下次真出现结构性差异时，你会以为
--   "又是注释那点小事"。
--
-- 处理手法沿用里程碑 12：用 MODIFY COLUMN 把整列重写一遍。
-- ⚠️ MODIFY COLUMN 必须把类型、NULL、默认值、注释【全部写全】，
--   漏掉的部分会被重置成默认值。所以下面两行逐字抄自 mall.sql。
ALTER TABLE category
    MODIFY COLUMN name VARCHAR(50) NOT NULL COMMENT '分类名称（唯一）';

ALTER TABLE orders
    MODIFY COLUMN idempotency_key VARCHAR(64) NOT NULL
        COMMENT '幂等键（防重复提交，作用域是同会员内唯一）';


-- ---------------------------------------------------------------------------
-- 6. 检查迁移结果
-- ---------------------------------------------------------------------------
SELECT '=== 6. 迁移完成，下面是验证结果 ===' AS msg;

SHOW CREATE TABLE product;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product'
ORDER BY ORDINAL_POSITION;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_item'
ORDER BY ORDINAL_POSITION;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME IN ('category', 'orders')
  AND COLUMN_NAME IN ('name', 'idempotency_key');

-- ★★ 本轮的不变量，这一条必须返回 0 行：
--    **product 表上没有价格，也没有库存。**
SELECT COLUMN_NAME AS 不该存在的列
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product'
  AND COLUMN_NAME IN ('price', 'stock');

-- ★ 收紧的落地断言：sku_spec 必须 NOT NULL 且【DEFAULT 为 NULL】
--   （information_schema 里「没有默认值」和「默认值是 NULL」长得一样，
--     所以判据是 IS_NULLABLE = 'NO' 且 COLUMN_DEFAULT IS NULL）
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'sku_spec';

-- ★ 表注释必须已经把「真源」说清楚（含 product_sku 四个字）
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME IN ('product', 'product_sku');

-- ★ 核对整库表数量：仍然是 11 张（这个脚本不建表也不删表）
SELECT COUNT(*) AS 表数量 FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'mall';

-- ★★ 核对「没有外键」这条约定：下面这条查询必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';

-- ★★ 数据一条都没少 —— 删列不该动任何一行数据
SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS SKU数 FROM product_sku;
SELECT COUNT(*) AS 会员数 FROM member;
SELECT COUNT(*) AS 订单数 FROM orders;
SELECT COUNT(*) AS 明细数 FROM order_item;
SELECT COUNT(*) AS 评价数 FROM product_review;

-- ★ 每件商品仍然至少有一条 SKU（不变量的另一半）
SELECT COUNT(*) AS 没有SKU的商品数
FROM product p LEFT JOIN product_sku s ON s.product_id = p.id
WHERE s.id IS NULL;

SELECT '=== 13b 完成。product 上已经没有价格，也没有库存了。===' AS msg;
SELECT '=== 下一步：重启后端 → 跑 sql 目录下全部 15 个测试脚本 → 全绿。===' AS msg;
