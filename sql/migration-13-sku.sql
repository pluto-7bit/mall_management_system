-- ============================================================================
--  迁移脚本 13：商品多规格（SKU）—— 第一步：【只加不删】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 12 为止，「一件商品」= 一行 product，价格和库存直接挂在那行上。
--  这意味着【一件商品只能有一个价格、一个库存】——
--  「同一件 T 恤，黑色 M 码 99 元、白色 L 码 109 元」这件事
--  在这个模型里根本无法表达。
--
--  这个脚本把价格和库存从 product 挪到一张新表 product_sku 上，
--  并加一条铁律：**每一件商品都至少有一条 SKU。**
--  没有规格的商品（比如「卫龙辣条」）也有一条 SKU —— 它的 spec_json 是 '[]'，
--  也就是一条「默认 SKU」。**代码里只有一条路径**，不需要到处判断
--  「这件商品有没有规格」。
--
--  ★ 这是本项目里最大的一次数据模型改动，它和前面每一轮的关系是：
--    里程碑 7（购物车）、8（下单）、9（支付/取消）的每一条链路，
--    都在【按商品 id 定位价格和库存】。这一轮之后它们全部改成按 SKU id 定位。
--
--  【为什么拆成 13 和 13b 两个脚本？】
--    因为 product.price / product.stock 是这一轮【唯一的回滚预案】。
--      - migration-13-sku.sql（就是本脚本）  → 只加不删，后端此刻完全不受影响
--      - migration-13b-sku-drop-columns.sql  → 删掉那两列，必须等 Java 侧全部切完、
--                                              全部测试绿了之后再跑
--    如果一次就把列删了，Java 那边任何一处漏改都会变成 Unknown column，
--    而那时已经【退不回去了】。分成两步，中途随时可以停。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是「DROP 掉所有表再重建」的初始化脚本。
--  库里已经有自己录入的商品、真实订单和评价，跑它会全部清空。
--  约定（从里程碑 8 起）：schema 变更走增量迁移，
--    - mall.sql          → 全量脚本，改它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 增量脚本，改它是为了「已有数据的库能跟上」
--  两份都要改。
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW CREATE TABLE mall.product_sku;
--    SHOW COLUMNS FROM mall.product LIKE 'spec_schema';
--    SHOW COLUMNS FROM mall.order_item LIKE 'sku%';
--  能查到就说明跑过了。
--
--  ⚠️ 这个脚本是【新建表 + ALTER 加列】。MySQL 8 有 CREATE TABLE IF NOT EXISTS
--     和 ADD COLUMN IF NOT EXISTS，但这里【故意不用】——
--     重复跑说明有人搞错了状况，当场报错让人看一眼，比静默跳过更安全。
--     （这是 migration-11 / 12 立下的先例。）
--
--  【★★★ uk_product_spec 这个唯一索引是这张表存在的理由，不是附带的优化】
--    「同一件商品不能有两个一模一样的规格组合」这件事，
--    **只有数据库能真的保证** —— Service 里那句「先查有没有重复」挡不住并发：
--    两个请求可以【同时】查到「没有重复」，然后两个都往下走。
--    这和 uk_order_item（里程碑 12）、uk_member_idempotency（里程碑 8）
--    是同一个手法：**把「不可能发生」交给数据库。**
--
--    ⚠️ 但这个索引能不能拦住，完全取决于【写入端拼出来的字符串是否一致】：
--      - utf8mb4_0900_ai_ci 是 **NO PAD** 的（和老的 utf8mb4_general_ci 不同）：
--        '黑' 和 '黑 ' 是两个不同的字符串，索引拦不住。
--      - 维度顺序不同也是两个不同的字符串：
--        [颜色:黑, 内存:128G] ≠ [内存:128G, 颜色:黑]
--    所以「规范化」（维度按定义顺序排、去掉首尾空白）不是锦上添花，
--    它是这个唯一索引【唯一的前提】。规范化由 SpecJson 一个类负责，
--    测试里有一条专门提交「顺序颠倒的同一组合」，看最终是不是仍然只有 4 行。
--
--  【★ 为什么 spec_json 是 NOT NULL，而且「无规格」必须是恰好 '[]'】
--    MySQL 的唯一索引【把多个 NULL 当成互不相等】。
--    所以只要允许 spec_json 为 NULL，同一件商品就能插出【两条】「默认 SKU」，
--    而后果是静默的：MIN(price) 会取到更低的那条，前端规格选择器永远匹配不上。
--    '' 也不行 —— 那是「有人忘了赋值」的形态，和「这件商品真的没有规格」
--    是两件事，不该长得一样。
--
--  【★ 为什么规格定义（spec_schema）存在 product 上，而不是从 SKU 行推导？】
--    因为**规格值的显示顺序除了这一列没有别的地方可存**。
--    SKU 行的 id 是插入顺序；管理员把「256G」拖到 128G 前面再保存，
--    从 SKU 行推导出来的顺序纹丝不动 —— 一个彻底的静默失败
--    （拖了没反应，没有任何报错）。
--    而「删了重建来体现新顺序」又不行：order_item.sku_id 指着这些行的 id。
--    ★ 代价是这一列和 SKU 行可能分叉，用一条 Service 规则堵住：
--      **skus.length 必须等于各维值数之积**（不许漏行）。
--      漏行的后果同样静默：商家声明的「白色」没有对应的 SKU 行，
--      下次打开编辑器时那个值会直接消失。
--
--  【★ 为什么 order_item.sku_id 可空，而 sku_spec 不可以】
--    判据还是那一条：**这一行刚插入时有值吗？**
--      - 新下的订单一定有 sku_id → 本该 NOT NULL。
--      - 但【历史行】没有（那时候还没有 SKU 这个概念），而且这个项目里
--        真实存在「商品被硬删导致 order_item 成孤儿」的行
--        （里程碑 8 的注释里记着真的漏过 32 行）。
--        给这些行硬填一个值就是【造假】—— 和「给 pay_time 一个默认值
--        等于把没付过款记成已付款」是同一类错误，只是换了个列。
--        NULL 是诚实的，而且它【已经有代码路径】：
--        increaseSkuStock(null, qty) → WHERE id = NULL → 影响 0 行
--        → 走现有的「warn 但不抛」分支（本来就在处理「商品被硬删」）。
--      - 能对上的历史行会回填成【该商品的默认 SKU】（见下面 2.4 的论证）。
--
--    sku_spec 则是 NOT NULL，且【本阶段必须带着 DEFAULT ''】：
--    历史行那时候确实没有规格，空串就是它们的真值（所以不算造假）；
--    而更要紧的是 —— 本阶段 Java 侧一行都没改，OrderItemMapper 的插入语句里
--    没有这一列，一个 NOT NULL 且无默认值的列被漏掉会【让每一次下单都 500】。
--    ⚠️ 这一点是实测出来的，不是推理的，详见 2.5。
--    「去掉默认值」属于 migration-13b —— 对的方向，但必须等 Java 侧改完。
--
--  【跑之前先备份】
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -u root -p123456 ^
--        --default-character-set=utf8mb4 mall > backup-mall-<时间戳>.sql
--    （本项目已有 12 份先例，命名 backup-mall-YYYYMMDD-HHMMSS.sql）
--
--  执行：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 ^
--        --default-character-set=utf8mb4 mall < migration-13-sku.sql
--
--  这一轮之后全库有 11 张表（原 10 张 + product_sku），仍然一个外键都没有。
-- ============================================================================


SET NAMES utf8mb4;
USE mall;

-- ---------------------------------------------------------------------------
-- 1. 改动前：先看一眼现状，出问题时才知道原来是什么样
-- ---------------------------------------------------------------------------
SELECT '=== 1. 改动前 ===' AS msg;

SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS 订单明细行数 FROM order_item;

SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall'
ORDER BY TABLE_NAME;


-- ---------------------------------------------------------------------------
-- 2. DDL
-- ---------------------------------------------------------------------------

-- 2.1 商品 SKU 表 —— 价格与库存的【唯一真源】
--
--  ★ 为什么不建 idx_product_id：uk_product_spec 的最左前缀就是 product_id，
--    再单独建一个只会增加写入代价。按主键查/改走的是 PRIMARY KEY。
--
--  ★ 为什么不跟 product_image 一样带 sort_no：SKU 的展示顺序由 spec_schema
--    的维度顺序决定（见头部那段论证），不需要每个 SKU 自己再排一次。
--    而「管理员能手动调整 SKU 行的顺序」这个需求不存在。
CREATE TABLE product_sku (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id  BIGINT UNSIGNED NOT NULL                COMMENT '所属商品 id',
    spec_json   VARCHAR(500)    NOT NULL                COMMENT '规格组合 JSON，形如 [{"name":"颜色","value":"黑"},{"name":"内存","value":"128G"}]，按规格定义顺序排列、无多余空白（规范化由 SpecJson 保证，因为 uk_product_spec 靠它才拦得住重复）；无规格的商品只有一条 spec_json = [] 的默认 SKU',
    price       DECIMAL(10, 2)  NOT NULL                COMMENT '售价（元）—— 价格与库存的唯一真源',
    stock       INT             NOT NULL DEFAULT 0      COMMENT '库存数量',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_spec (product_id, spec_json)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品 SKU 表（价格与库存的唯一真源）';

-- 关于 spec_json 的长度：
--   VARCHAR(500) 在 utf8mb4 下最多占 2000 字节，加上 2 字节长度前缀是 2002 字节。
--   InnoDB 的索引键前缀上限是 3072 字节（行格式 Dynamic + 本库排序规则
--   utf8mb4_0900_ai_ci，两者都已实测确认），所以 uk_product_spec 建得起来。
--   留 500 的余量：3 个维度 × 每维 20 字的值，约 3 × 40 = 120 字符，
--   中文按 1 字符算（不是按字节），余量充足。
--   ★ 不写 TEXT —— TEXT 上建唯一索引必须指定前缀长度，而前缀索引
--     只能保证「前 N 个字符不重复」，正是这个索引最不该有的性质。


-- 2.2 product 加规格定义列
--
--  NULL = 这件商品没有规格（只有一条默认 SKU）。
--  这里用 NULL 而不用 '[]' 是有意的：spec_schema 上【没有】唯一索引，
--  所以 nullable 不会带来 2.1 里说的那个问题；
--  而「没有规格定义」和「有一个空的规格定义」确实是两件事，
--  前者是绝大多数商品（100 件里的 96 件），后者不可能出现。
ALTER TABLE product
    ADD COLUMN spec_schema VARCHAR(500) DEFAULT NULL
        COMMENT '规格定义 JSON，形如 [{"name":"颜色","values":["黑","白"]}]，值的顺序就是前端展示顺序；NULL = 无规格（该商品只有一条默认 SKU）'
        AFTER description;

-- 2.3 订单明细：加上「归还库存要去哪一行」和「当时买的是什么规格」
--
--  ★ 列序：sku_id 紧挨 product_id（两者都是【引用】：指向哪个商品、哪个规格），
--    sku_spec 紧挨 product_name（两者都是【快照】：当时它叫什么、当时什么规格）。
--    「引用」和「快照」分成两组，读 SHOW CREATE TABLE 时一眼能看出区别。
--    ⚠️ mall.sql 里必须保持同样的列序（列序不一致 = 两份 schema 悄悄分叉）。
--
--  ★ 为什么还要存 sku_spec 这个文本快照，而不是只存 sku_id 去 join？
--    和 product_name/price 是同一个理由：**订单是快照，不是视图。**
--    SKU 行会被改价、会被删除、商品会被改规格；而三个月后用户打开
--    「我的订单」，看到的必须是【他当时买的那件东西】，
--    不是「现在那个 sku_id 恰好指向的东西」—— 后者可能已经不存在了。
--    这正是里程碑 8 写下 price/product_name 快照时要解决的那件事，
--    规格只是第三个需要快照的字段。
ALTER TABLE order_item
    ADD COLUMN sku_id BIGINT UNSIGNED DEFAULT NULL
        COMMENT 'SKU id（取消订单时按它归还库存）。NULL = 里程碑 13 之前下的单，或商品已被硬删、查不到当时指向哪个 SKU'
        AFTER product_id,
    ADD COLUMN sku_spec VARCHAR(255) NOT NULL DEFAULT ''
        COMMENT '规格文本快照，形如「颜色:黑 / 内存:128G」；空串 = 无规格（默认 SKU）'
        AFTER product_name;

-- 2.4 回填：每件商品一条默认 SKU，价格和库存照抄 product
--
--  ⚠️ 必须在【本脚本】里跑，不能挪到 13b 之后 —— 那时候 product 上
--     已经没有 price/stock 可抄了。
--  ⚠️ 用 SELECT 而不是写死 100 行：别人的库里多几件商品也能跑对。
--     这也是为什么 mall.sql 的种子区反过来要写显式值（那里不能引用
--     product.price/stock，因为它必须能在删列前后都跑得通）。
INSERT INTO product_sku (product_id, spec_json, price, stock)
SELECT id, '[]', price, stock FROM product;

-- 回填历史订单明细：能对上默认 SKU 的就对上
--
--  ★ 为什么是「默认 SKU」而不是「随便一条」：这些订单下单时扣的就是
--    product.stock，而默认 SKU 的 stock 正是从它抄过来的（就在上面那条）。
--    所以取消这条历史订单时，库存能还到【正确的那一行】。
--    随便指一条，或者填 0，库存就静默丢失了。
--  ★ 商品已经被硬删的历史行对不上，留在 NULL 上 —— 这是诚实的「不知道」，
--    而不是「第 0 号 SKU」。运行时会走 warn 分支（和商品被硬删时同一个分支）。
UPDATE order_item oi
    JOIN product_sku s ON s.product_id = oi.product_id AND s.spec_json = '[]'
SET oi.sku_id = s.id
WHERE oi.sku_id IS NULL;

-- 2.5 sku_spec 的 DEFAULT '' —— 【故意留着，本阶段不能去掉】
--
--  ★ 上面 ADD COLUMN 时那个 DEFAULT '' 一半是 MySQL 强制的：
--    给【有数据的表】加一个 NOT NULL 列必须有默认值，用它填已有的行。
--    而 '' 恰好就是历史行的真值（那时候还没有规格），所以它不构成造假。
--
--  ★★ 另一半是【这一刻必须留着的】，理由是这个脚本跑完之后
--     Java 侧【一行都还没改】——而 OrderItemMapper.batchInsert 的列清单里
--     没有 sku_spec（它此刻还不知道有这个列）：
--
--         INSERT INTO order_item (order_id, product_id, product_name, price, quantity, subtotal)
--
--     一个 NOT NULL 且无默认值的列被 INSERT 漏掉，MySQL 严格模式下报
--         ERROR 1364: Field 'sku_spec' doesn't have a default value
--     也就是【每一次下单都会 500】。
--     ⚠️ 这不是推理出来的：本脚本第一版就在这里写了 DROP DEFAULT，
--        跑完全量测试，test-order.py 的「购物车结算」当场 HTTP 500 / 系统繁忙。
--        那一条测出来的就是这个 ERROR 1364。
--
--  ★ 所以「去掉默认值」这件事是【对的方向、错的时机】，它属于 migration-13b：
--    要等到 Java 侧每一条写 order_item 的路径都填了 sku_spec 之后。
--    那个理由仍然成立，只是要在 13b 里论证：
--      留着默认值，将来某个【忘了赋值】的下单路径会静默写进空串，
--      然后在订单页显示成「这个商品没规格」—— 用户看到的是错的信息，
--      而没有任何一层报错。去掉它，那种路径会当场报一条 SQL 错。
--    这和 orders 表里「给 pay_time 一个默认值等于把没付过款记成已付款」
--    是同一个判断的两种朝向：**默认值只在「它恰好就是真值」时才是对的。**
--
--  ★ 这条也是「13 只加不删」这个拆分本身的第二个用处：
--    第一版之所以会踩到它，正是因为我把「加列」和「收紧约束」混在了一步里。
--    加列不影响任何现有代码；收紧约束【必然】影响 —— 所以它必须
--    和 Java 侧的改动同时发生，不能提前。
SELECT 'sku_spec 的 DEFAULT 暂时保留（见 2.5 的说明），13b 才去掉' AS msg;


-- ---------------------------------------------------------------------------
-- 3. 检查迁移结果
-- ---------------------------------------------------------------------------
SELECT '=== 3. 迁移完成，下面是验证结果 ===' AS msg;

SHOW CREATE TABLE product_sku;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product'
ORDER BY ORDINAL_POSITION;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_item'
ORDER BY ORDINAL_POSITION;

-- ★ 核对整库表数量：应该从 10 张变成 11 张
SELECT COUNT(*) AS 表数量 FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'mall';

-- ★★ 核对本轮的核心不变量：uk_product_spec 必须存在且 NON_UNIQUE = 0。
--    这个索引没了，整个功能的设计前提就没了（见头部那段论证）。
--    ⚠️ 光看「有没有这个索引」不够，必须看 NON_UNIQUE ——
--       写成一个普通 KEY 的话它长得一模一样，但什么都保证不了。
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- ★★ 核对回填：下面这条必须返回 0 行（每件商品都有 SKU）
SELECT p.id AS 没有SKU的商品
FROM product p LEFT JOIN product_sku s ON s.product_id = p.id
WHERE s.id IS NULL;

-- ★★ 对账：价格和库存必须【原样】搬过来，一行都不能对不上。
--    下面这条必须返回 0 行。
SELECT p.id AS 单价或库存对不上的商品
FROM product p
    JOIN product_sku s ON s.product_id = p.id AND s.spec_json = '[]'
WHERE p.price <> s.price OR p.stock <> s.stock;

-- ★ 每件商品恰好一条默认 SKU（多一条就说明 spec_json 没写成 '[]'，见头部论证）
SELECT COUNT(*) AS 默认SKU数 FROM product_sku WHERE spec_json = '[]';

-- 允许 > 0（孤儿明细），但这个数要【看见】—— 它是「哪些历史订单
-- 取消时还不了库存」的准确数量。
SELECT COUNT(*) AS 未回填的历史明细数 FROM order_item WHERE sku_id IS NULL;

-- ★ 核对两列的形态：sku_id 必须可空（历史行和孤儿行填不出真值），
--    sku_spec 必须 NOT NULL 且【此刻仍然带着 DEFAULT ''】——
--    这一条断言不是「顺带看看」，它是防着有人好心把 2.5 的 DROP DEFAULT
--    提前加回来的：那样下单会 500，而报错发生在运行时、离这里很远。
--    （阶段 6 跑完 migration-13b 之后，这条断言要反过来写。）
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_item'
  AND COLUMN_NAME IN ('sku_id', 'sku_spec');

-- ★ 核对「没有外键」这条约定：下面这条查询必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';

-- ★ 核对用户的原有数据没被动过
SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS 会员数 FROM member;
SELECT COUNT(*) AS 订单数 FROM orders;
SELECT COUNT(*) AS 评价数 FROM product_review;

SELECT '=== 13 完成。后端此刻完全不受影响（product.price/stock 还在）。==='
       AS msg;
SELECT '=== 下一步：切换 Java 侧 → 全量测试绿 → 才跑 migration-13b 删列。==='
       AS msg;
