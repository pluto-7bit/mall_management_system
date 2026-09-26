-- ============================================================================
--  迁移脚本 17b：订单运费 —— 【只加一列】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--  ⚠️ 必须在 migration-17-after-sale.sql 之后跑（顺序无所谓，但两个都要跑）。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 16 为止，一笔订单的金额只有 total_amount 一个数，
--  它在 doCreate 里等于「各明细小计之和」—— 也就是【商品小计】。
--  于是「运费」这个概念在整个项目里根本不存在：
--  下单不收费、结算页不显示、订单页也没有这一行。
--
--  真实的商城要收运费（本项目按用户拍板的规则：固定运费 + 满额包邮），
--  而运费一旦存在，就带来一个必须当场处理的问题 —— 见下面第二节。
--
-- ============================================================================
--  ★ 为什么是 NOT NULL DEFAULT 0.00，而不是 DEFAULT NULL
-- ============================================================================
--
--  直接用 migration-14 / 14b 立下的那条判据：
--
--    「这个 0 会不会被当成一个【真实存在的数据】去比较和计算？」
--
--    parent_id = 0      不会被拿去 join 一个真实分类   → DEFAULT 0 （migration-14）
--    market_price = 0   会被拿去和售价比大小           → DEFAULT NULL（migration-14b）
--    freight_amount = 0 会被拿去算 total = 小计 + 运费  → DEFAULT 0.00 ✅
--
--  最后一条推得细一点：满额包邮时运费【就是 0】，而这个 0 会参与
--  「实付 = 商品小计 + 运费」这个等式。它是真实值，不是缺席。
--  ★ 对照一下同一轮 after_sale.refund_amount 用的是 DEFAULT NULL：
--    那里「还没算出来」是缺席（0 会被当成「退了 0 元」拿去求和）。
--    两列同在里程碑 17、选择却相反，判据是同一条 —— 这正是它好用的证明。
--
--  ── 白拿的好处（和 migration-14 的 parent_id 完全同构）──
--
--  ★ 已有的历史订单本来就没收运费，0.00 就是它们的真值
--    → 本脚本里【没有任何 UPDATE】。
--  ★ mall.sql 的种子区也一个字不用改（而且种子区本来就没有订单）。
--  ★ 这是里程碑 13 那条结论的【第三次】应验：
--      「加列」不影响任何现有代码；「收紧约束」才影响。
--
-- ============================================================================
--  ★★ 但它带来一件【不属于加列】的事：total_amount 的语义变了
-- ============================================================================
--
--  这是本轮唯一一处「老字段的含义被改了」，必须正面写下来，
--  因为它和加列不一样 —— 加列是老代码读不到新列（无感），
--  而改语义是老代码读到【同一个列、不同的意思】。
--
--  决定：total_amount 从「各明细小计之和」变成「实付金额」
--        （= 明细小计之和 + 运费），并且【不给「商品小计」加列】。
--
--  于是「商品小计」有了两个算法：
--      减法：total_amount - freight_amount
--      加法：SUM(order_item.subtotal)
--  ★★ 这是同一事实的两份实现，而且它们分岔时【不会有任何一层报错】。
--     分岔的两种症状都长一样：合计 ¥109、明细加 ¥99、运费 ¥0 ——
--     用户看到运费是 0，却发现自己多付了 10 元。
--     只有用户会发现。
--
--  出口只有一条：把两个方向摆在一起比。见本文件末尾第 3 节那条断言
--  （它同时也守住了「后端忘了写 freight_amount」—— 那一列有 DEFAULT 0.00，
--    INSERT 漏了它照样成功，运费永远是 0，而 total_amount 里含了运费）。
--
--  ── 被否掉的三个替代方案 ──
--
--  ✗ total_amount 保持「商品小计」，实付在 Java/VO 里现算 total + freight
--    「实付」是一个被到处使用的概念（列表显示、退款上限、对账），
--    每个读它的地方都要记得加运费。【某处忘了加，页面上的应付金额就少 10 元，
--    不报错。】而且 total_amount 这个名字会变成谎话 —— 它不再「总」了。
--
--  ✗ 再加一列 goods_amount
--    三个数之间有两个等式要守（goods + freight = total、goods = SUM(subtotal)），
--    每一个都只能靠断言。★ 少一个列就少一条会漂移的边。
--
--  ✗ 不加列，展示时按【当前】运费规则重算
--    ★★ 这是最危险的一个，因为它的后果最具体：运营把门槛从 99 降到 59 之后，
--    一笔【已经付过 10 元运费】的历史订单，订单页会显示
--        商品 ¥89.00   运费 ¥0.00   合计 ¥99.00
--    —— 运费 0、合计却是 99，页面上两个数字自相矛盾。
--    而 refund_amount ≤ total_amount 那条不变量也会跟着错。
--    ★ 全程没有任何一层会报错。
--    这属于「历史事实被今天的规则改写」，和 orders.receiver_address
--    （收货地址快照）、order_item.price（成交价快照）完全同类 ——
--    而且更严重，因为它是【涉及金钱】的历史事实。
--    ⚠️ 所以 freight_amount 是【下单时算好、写进 orders 的快照】，
--       展示时读它，永不重算。
--
-- ============================================================================
--  ★ 为什么是 AFTER total_amount
-- ============================================================================
--
--  照 migration-14 的位置论证：按「读的人会怎么理解这张表」排。
--  total_amount 和 freight_amount 是同一个概念的两半
--  （实付 = 商品小计 + 运费，而商品小计在别处），中间隔着十几列
--  会让每次 SHOW CREATE TABLE 都要来回找。
--
-- ============================================================================

ALTER TABLE orders
    ADD COLUMN freight_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00
        COMMENT '本单实际收取的运费（下单时的快照，永不重算）' AFTER total_amount;

-- ★ 顺手把 total_amount 的【注释】也改掉 —— 因为它说的已经不是实话了。
--
--   上面那一节论证了「total_amount 的语义变了」，而它的列注释里
--   还写着「订单总金额（快照）」这种含糊的说法。留着它，
--   下一个读代码的人会按那句注释去理解这个数（「总金额」= 商品小计？含运费？）。
--
--   ★ 只改注释，不动任何数据 —— 这是一条【元数据】变更，MySQL 8 走
--     ALGORITHM=INSTANT，不重建表、不锁表、不需要 UPDATE。
--   ★ 必须和 mall.sql 里那一列的注释【逐字一致】，否则
--     sql/test-schema-sync.py 会报「注释不一致」—— 那正是它该报的。
--   ⚠️ MODIFY COLUMN 要写全整列定义（类型 + NOT NULL + 注释），
--      漏写 NOT NULL 会顺手把列改成可空 —— 本条语句里它是全的。
ALTER TABLE orders
    MODIFY COLUMN total_amount DECIMAL(10, 2) NOT NULL
        COMMENT '订单实付金额 = 明细小计之和 + 运费（下单时快照）';


-- ---------------------------------------------------------------------------
-- 1. 验证：列建出来了，位置对，默认值对
-- ---------------------------------------------------------------------------

SHOW CREATE TABLE orders;

-- ★★ 核对列的位置和「NOT NULL + 默认 0.00」。
--    可空那一列必须是 NO —— 如果是 YES，说明有人把 DEFAULT 0.00
--    写成了 DEFAULT NULL（那会让 total = 小计 + 运费 变成 NULL，全盘皆错）。
-- ★ 连注释一起看：total_amount 的注释应该已经变成
--   「订单实付金额 = 明细小计之和 + 运费（下单时快照）」，
--   而且 freight_amount 紧跟在它后面。
SELECT ORDINAL_POSITION AS 位置, COLUMN_NAME, COLUMN_TYPE,
       IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders' AND COLUMN_NAME IN ('total_amount', 'freight_amount')
ORDER BY ORDINAL_POSITION;


-- ---------------------------------------------------------------------------
-- 2. 验证：迁移没有发明数据（这一条是「加列不影响现有数据」的证据）
-- ---------------------------------------------------------------------------

-- ★★ 必须返回 0 行。5 笔真实订单跑之前没交过运费，跑之后也不该有。
--    如果这里出现非 0，说明本脚本（或它的某个版本）写了 UPDATE ——
--    那就是在给历史订单【编造】一笔它们从来没有收过的运费。
SELECT order_no, total_amount, freight_amount FROM orders WHERE freight_amount <> 0;


-- ---------------------------------------------------------------------------
-- 3. 验证：那条「减法 vs 加法」的断言现在就成立
-- ---------------------------------------------------------------------------

-- ★★ 订单实付 = 明细小计之和 + 运费。必须返回空。
--    这条断言在本阶段必须【本来就绿】—— 因为 freight_amount 全是 0，
--    而 total_amount 是 16 轮以前算出来的「小计之和」。
--    ★ 它真正开始有价值是在阶段 2：那时候 doCreate 会把运费加进 total_amount，
--      而 OrderMapper.insert 的列清单必须同时带上 freight_amount。
--      漏了那一步的症状：运费永远是 0，而 total_amount 含了运费 ——
--      INSERT 照样成功、不报错、不警告，只有这条断言能发现。
--    （用 LEFT JOIN 而不是 INNER JOIN：这样「有订单没明细」的孤儿状态
--      也会被算进来，而不是被 join 静默丢掉。）
SELECT o.order_no, o.total_amount, o.freight_amount,
       COALESCE(SUM(i.subtotal), 0) AS 明细合计
FROM orders o
    LEFT JOIN order_item i ON i.order_id = o.id
GROUP BY o.id
HAVING o.total_amount <> COALESCE(SUM(i.subtotal), 0) + o.freight_amount;


-- ---------------------------------------------------------------------------
-- 4. 验证：用户的订单数据没被动过
-- ---------------------------------------------------------------------------

SELECT COUNT(*) AS 订单数 FROM orders;
SELECT COUNT(*) AS 订单明细数 FROM order_item;

-- ★ 核对 5 笔真实订单的金额一个数都没变
SELECT id, order_no, status, total_amount, create_time FROM orders ORDER BY id;

-- ★ 核对「没有外键」这条约定：必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';

SELECT '=== 17b 完成。后端此刻完全不受影响（Java 里还没人读 freight_amount）。===' AS msg;
SELECT '=== 下一步：把 after_sale 建表段和 orders 这一列同步进 mall.sql，然后跑 test-schema-sync.py。===' AS msg;
