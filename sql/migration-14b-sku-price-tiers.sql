-- ============================================================================
--  迁移脚本 14b：SKU 价格体系 —— 【只加两列】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--  ⚠️ 先跑 migration-14-category-tree.sql，再跑这个。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 15 为止，一行 SKU 只有一个价格：售价 price。
--  真实商品的价格从来不是一个数：
--
--    划线价（原价/市场价）  —— 「¥4999  ~~¥6999~~」里被划掉的那个
--    成本价（进货价）       —— 只给管理端算毛利用
--
--  这一轮加这两列，价格体系才有「每 SKU 各自一套」的含义：
--  同一件 T 恤黑色 M 码和白色 L 码的进价、原价本来就不一样。
--
--  【★★ 为什么是 DEFAULT NULL，而同一个 parent_id 却是 NOT NULL DEFAULT 0？】
--  这两列和 migration-14 的 parent_id【故意相反】，判据是一句话：
--
--    ★ 这个 0 会不会被当成一个【真实存在的数据】拿去比较和计算？
--
--      parent_id = 0   → 永远不会 join 到一个真实分类（id 从 1 开始），
--                        它只是一个「没有父」的确定标记。
--                        ⇒ 用 0 是对的，因为它需要成为一个【可以比较的值】。
--
--      market_price = 0 → 会被拿去和售价比大小（划线价该不该显示）。
--                        而「商家没设划线价」是一件【缺席】的事，
--                        不是「原价 0 元」。
--                        ⇒ 用 NULL。
--
--  用 0 表示「没设」会立刻长出一个假问题：「划线价 0 元算不算打折？」
--  侥幸的是 0 > price 不成立、所以不显示 —— 但那是【靠巧合对了】，
--  不是靠规则。哪天展示规则改成「marketPrice != null 就画删除线」，
--  库里那 100 行「0 元原价」会立刻全部变成划线价。
--
--  ★ 一句话：**0 是一个值，NULL 是「不存在」，这两种东西不该共享一个编码。**
--    （这条判据和 migration-14 头部是【同一条】，只是这次答案落在另一边 ——
--     两次都写下来，是因为「用哪个」不该靠记性。）
--
--  ⚠️ 而且 NULL 在这里还有一个机械上的好处：MySQL 的唯一索引把多个 NULL
--     当成互不相等，所以「没设划线价」这件事有多少行都不冲突。
--     这和 product_sku.spec_json 【必须】NOT NULL 的理由正好相反
--     （那边要的就是「不许有两个 NULL 默认 SKU」）—— 同一个 MySQL 特性，
--     在两个地方一个是要利用的、一个是要躲开的。判据是：
--     ★ 这个字段参不参与唯一性判断。
--
--  【为什么 market_price 要在保存时校验「必须大于 price」】（校验在 Java 里，不在这个脚本里）
--  展示规则是「只有 market_price > price 才画删除线」。
--  于是【一个填错的划线价（比售价还低、或者等于售价）会被静默吃掉】：
--  商家以为自己设了划线价，商城页什么都不显示，两端都不报错。
--  把「填了就必须有意义」做成一条 400，这个静默失败就变成当场拒绝。
--
--  ⚠️ 而这条校验【必须和入库时的舍入用同一套算法】，否则它自己会变成新的静默失败：
--     DECIMAL(10,2) 会把 100.004 存成 100.00（实测：1.005 存进去是 1.01，
--     MySQL 的 DECIMAL 是四舍五入、不是截断）。
--     如果拿【未经舍入的原值】去比大小，那么 market_price = 100.004 / price = 100.00
--     会通过校验，存进去却变成 100.00 —— 和售价相等，删除线不画。
--     ⇒ 校验必须先把两个数都 setScale(2, HALF_UP) 再比。见 SkuSaveDTO / ProductServiceImpl。
--
--  【为什么 cost_price 不设同样的限制？】
--  因为 cost_price >= price 是【亏本卖】，那是真实存在的生意状态 ——
--  它只该在管理端被标红提醒，不该被拒绝。数据库和接口都不拦它。
--  ⚠️ 但 cost_price < 0 要拦：负的进货价没有任何含义，
--     而且它会让「毛利率」算出一个大于 100% 的数。
--
--  【★★ 为什么不给现有 100 条 SKU 回填一个数字？】
--  因为那是【发明数据】。
--
--    · 给用户手工录入的 5 件商品（iPhone duo / 床单 / 卫龙辣条 /
--      iPhone 18 pro 256G / 联想拯救者Y9000P）编一个成本价，
--      是把一个商家从没说过的数字写进他的账本。
--    · 给另外 95 件生成的商品编一个成本价，是给「成本」这个字段
--      灌入一个它从来没有过的含义 —— 从此没人知道那一列里
--      哪些是真数据、哪些是当初为了填满而编的。
--
--  所以本脚本【没有任何 UPDATE】。迁移完成时这 100 行两列全是 NULL，
--  这是【正确的结果】，不是「还没做完」。
--  ★ 代价是商城页现在看不到划线价 —— 想眼见为实就跑 tools/fixture-price.py
--    （造一件夹具商品、验完 --cleanup 删掉），不要在真数据上造效果。
--
--  【为什么不加索引、不加 CHECK？】
--  索引：这两列不参与任何 WHERE / JOIN / ORDER BY。
--  它们只被 SELECT 出来、然后在 Java 里做减法。加索引是零收益。
--
--  CHECK：理论上可以写 CHECK (market_price IS NULL OR market_price > price)
--  把上面那条展示规则钉进数据库。不这么做的理由是本轮的分工：
--  「价格是不是合理」是一条【业务规则】，它和「商品名最长多少字」同一类，
--  一律在 Service 里判、由 sql/test-price.py 断言。
--  ⚠️ 而且 MySQL 8 的 CHECK 约束在「先有数据再加约束」时会直接失败，
--     回填之前加不上去 —— 也就是说它会把「列是否可加」和一个业务规则捆在一起，
--     这正是「加列不影响现有代码、收紧约束才影响」那条结论说的东西。
--
--  【★ 边界不在 SQL，在 VO —— 这是本脚本最要紧的一句】
--  成本价是【只给管理端】的字段。所以：
--
--    product_sku.cost_price   ← 一个真实的列，SQL 该查就查（ProductSkuMapper 三个查询都带它）
--          ↓
--    SkuVO          公共字段：id / specs / specText / price / marketPrice / stock
--     ├─ ShopSkuVO   （用户端）—— 到此为止，没有成本
--     └─ AdminSkuVO  （管理端）—— 加 costPrice / grossMargin / grossMarginPercent
--
--  ⚠️ 所以下面这个脚本跑完之后，仓库里会短暂存在一个「SQL 查了成本价、
--     但没有任何接口吐出去」的状态 —— 这是【设计】的中间态，不是泄漏。
--     ★ 危险的是反过来：谁把 costPrice 加到父类 SkuVO 上，
--       /api/shop/skus/{id} 立刻【匿名】泄漏成本价 ——
--       一行改动、零编译错误、无需登录就能读到。
--       守着它的是 sql/test-price.py 的 E 组（双向：用户端不许有、管理端必须有）。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是「DROP 掉所有表再重建」的初始化脚本，库里有真实订单和评价，
--  跑它会全部清空。约定（从里程碑 8 起）：
--    - mall.sql          → 全量脚本，改它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 增量脚本，改它是为了「已有数据的库能跟上」
--  两份都要改（本脚本对应 mall.sql 里 product_sku 的建表段）。
--
--  ★ 而且这次 mall.sql 的【种子区一个字都不用改】：
--    两列的 DEFAULT NULL 会把现有的
--    INSERT INTO product_sku (product_id, spec_json, price, stock) 原样兜住。
--    ⇒ 全新装库的划线价/成本价也全是 NULL，和线上库完全一致（零分歧）。
--    ⇒ sql/gen-shop-assets.py 和 sql/sync-mall-seed.py 都【不要动】，
--      它们的两条自检依然成立。
--    这是「加列不影响任何现有代码」这条结论的第三次应验。
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW COLUMNS FROM mall.product_sku LIKE 'market_price';
--    SHOW CREATE TABLE mall.product_sku;
--
--  ⚠️ 这个脚本故意【不用】ADD COLUMN IF NOT EXISTS：
--     重复跑说明有人搞错了状况，当场报错让人看一眼，比静默跳过更安全。
--     （这是 migration-11 / 12 / 14 立下的先例。）
--
--  ★ 这个脚本是【只加不删】的，没有 13b 那种「不可回滚的最后一步」。
--     但它跑完之后，后端【必须】跟着改（不像 14 那样可以放着不管）：
--     不改的话，保存商品时那两列会被当成「没传」而写 NULL ——
--     接口 200，商家填的划线价静默丢失。见第 4 阶段。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. 加两列（一条 ALTER，改 schema 一次到位）
-- ---------------------------------------------------------------------------
--
-- AFTER price / AFTER market_price：让三列价格连在一起、且按
-- 「售价 → 划线价 → 成本价」排（读的人的理解顺序，不是加列的先后顺序）。
-- 判据和 migration-14 把 parent_id 放在 id 后面是同一条。
--
-- ★ 别忘了 UPDATE 时间戳：ALTER TABLE 是 DDL，MySQL 会自己重建表，
--   但 update_time 有 ON UPDATE CURRENT_TIMESTAMP，DDL 不会触发它 ——
--   所以这 100 行的 update_time 保持原值，这是对的
--   （改的是表结构，不是业务数据）。
ALTER TABLE product_sku
    ADD COLUMN market_price DECIMAL(10, 2) DEFAULT NULL
        COMMENT '划线价（原价/市场价）。NULL = 商家没设，不是 0（见 migration-14b 头部）' AFTER price,
    ADD COLUMN cost_price   DECIMAL(10, 2) DEFAULT NULL
        COMMENT '成本价（进货价）。★ 只在管理端出现，绝不允许进入任何 /api/shop/** 的响应' AFTER market_price;


-- ---------------------------------------------------------------------------
-- 2. 迁移完成
-- ---------------------------------------------------------------------------
SELECT '=== 迁移 14b 完成，下面是验证结果 ===' AS msg;


-- ---------------------------------------------------------------------------
-- 3. 验证：列与索引的形态
-- ---------------------------------------------------------------------------

SHOW CREATE TABLE product_sku;

-- ★ 核对列的形态：两列都必须是 IS_NULLABLE = YES、COLUMN_DEFAULT 为 NULL。
--   ⚠️ 如果这里显示 NO 或 0.00，说明有人把 DEFAULT NULL 写错了 ——
--      那会让「没设划线价」和「划定价 0 元」变成同一件事。
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku'
ORDER BY ORDINAL_POSITION;

-- ★ 核对唯一索引 uk_product_spec 还在，而且【没有被这两列污染】
--   （把 market_price 加进唯一索引会是灾难：同一件商品两个规格
--     只要划线价一样就插不进去了）
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;


-- ---------------------------------------------------------------------------
-- 4. ★★ 验证：迁移没有发明任何数据
-- ---------------------------------------------------------------------------

-- ★ 这是本脚本最重要的一条断言：两列的非空行数必须【都是 0】。
--   本脚本没有任何 UPDATE，所以这两条必须返回 0 ——
--   返回别的数字说明有人在迁移里回填了，那正是「发明数据」。
SELECT COUNT(*) AS 有划线价的行数 FROM product_sku WHERE market_price IS NOT NULL;
SELECT COUNT(*) AS 有成本价的行数 FROM product_sku WHERE cost_price   IS NOT NULL;

-- 顺便看一眼：100 行 SKU 现在长什么样（价格全在、后两列全空）
SELECT id, product_id, price, market_price, cost_price, stock
FROM product_sku ORDER BY id LIMIT 5;


-- ---------------------------------------------------------------------------
-- 5. 验证：里程碑 15 的那条不变量
-- ---------------------------------------------------------------------------

-- ★★ product 表上【没有价格列】。这是里程碑 15 立的不变量，
--    本轮加了「更多价格」之后它必须依然成立 ——
--    价格的真源【只能】是 product_sku，加几列也一样。
--    ⚠️ 这条查询必须返回 0 行。
SELECT COLUMN_NAME AS product表上不该有的价格列
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product'
  AND (COLUMN_NAME LIKE '%price%' OR COLUMN_NAME LIKE '%cost%');


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

SELECT '=== 14b 完成。★ 但后端【必须】跟着改，否则商家填的划线价会被静默写丢。===' AS msg;
SELECT '=== 下一步：第 4 阶段（实体 / mapper 四列 / SkuVO.marketPrice / AdminSkuVO / 区间聚合）。===' AS msg;
