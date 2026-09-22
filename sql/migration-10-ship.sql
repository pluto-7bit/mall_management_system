-- ============================================================================
--  迁移脚本 10：orders 加发货时间 / 完成时间
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  migration-08 建了订单表，migration-09 补上了支付这条链路的三个列。
--  但 OrderStatus 里的 SHIPPED(2) / COMPLETED(3) 两个常量，
--  中文文案和状态流转图都早就写好了，**却没有任何一行代码写过它们** ——
--  和里程碑 9 开始时 PAID 的处境一模一样。
--
--  里程碑 10 把「管理端发货 + 用户端确认收货」接上，这两个状态终于会被真实写入，
--  于是「什么时候发的货」「什么时候完成的」也终于有地方存了。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是「DROP 掉所有表再重建」的初始化脚本。
--  库里已经有自己录入的商品和会员，跑它会全部清空。
--  约定（从里程碑 8 起）：schema 变更走增量迁移，
--    - mall.sql          → 全量脚本，改它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 增量脚本，改它是为了「已有数据的库能跟上」
--  两份都要改。
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW COLUMNS FROM mall.orders LIKE 'ship_time';
--  能查到就说明跑过了。
--
--  ⚠️ MySQL 8 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS
--     （那是 MariaDB 的扩展），所以这个脚本重复执行会报
--     "Duplicate column name" 而中断。这是可以接受的 ——
--     迁移脚本本来就只该跑一次，报错比静默跳过更安全。
--
--  【为什么这两列都可以为 NULL？】
--    判断标准和 migration-09 那三个支付列完全一样，一句问话就能定：
--      **这个列在「这一行刚插入时」有值吗？**
--      订单插进来的那一刻还没有「发货」这回事 → 只能 NULL。
--      给默认值 CURRENT_TIMESTAMP 等于把「还没发货」记成「已经发了」，那是数据造假。
--
--  【为什么加这两列，而里程碑 9 又拒绝了 cancel_type 列？】
--    ★ 一句话：**有读者才加列。**
--      - ship_time / complete_time：里程碑 10 的两个新页面要显示
--        「已发货 2026-09-22」「已完成 2026-09-22」→ 有读者，加。
--      - cancel_type：没有任何代码或界面会去分支判断它 → 无读者，不加。
--    同一条标准，两个相反的结论，区别只在「有没有人真的会去读它」。
--
--  【为什么列的位置不是追加到末尾，而是插在 pay_method 后面？】
--    ★ 因为 migration-09 刻意保持了一个性质：
--      **迁移链跑出来的列序** 和 **mall.sql 建表语句里的列序** 逐列一致。
--      这样「跑过迁移的老库」和「新建的库」SHOW CREATE TABLE 出来是一模一样的，
--      两个文件可以互相对照 —— 发现不一致时就说明有一边漏改了。
--      所以 mall.sql 里也必须把这两列插在 pay_method 和 idempotency_key 之间，
--      而不是排在 update_time 后面。
--    顺带把 5 个「生命周期时刻」排成连续的一段：
--      pay_time / cancel_time / pay_method / ship_time / complete_time，
--      紧跟在它们描述的那个 status 后面。
--
--  【要不要加索引？不加。】
--    新增的查询形态是「按 member_id + status 分页」和「按 status 分页」，
--    而 idx_member_id / idx_status / idx_create_time 在 migration-08 建表时就有了。
--    ⚠️ 而且现在表里只有个位数行 —— 这个规模下 EXPLAIN 报什么都是噪音，
--    **它的结论没有统计效力，不能当加索引的依据**。
--
--    另一件事要单独说：管理端按会员名模糊搜索走的是 LIKE '%x%'，
--    用不上 member 表的 uk_username，那是一次全表扫。这是**刻意接受的**
--    （和项目里其余 LIKE 搜索一致）。把它和上面那句分开写，
--    是因为这两件事的规模假设不同，不能用一个理由盖过去。
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 mall < migration-10-ship.sql
-- ============================================================================

SET NAMES utf8mb4;
USE mall;

-- ---------------------------------------------------------------------------
-- 1. 改动前的样子（记下来，方便和改完后对照）
-- ---------------------------------------------------------------------------
SELECT '改动前' AS msg;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
ORDER BY ORDINAL_POSITION;


-- ---------------------------------------------------------------------------
-- 2. 加两列
-- ---------------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN ship_time     DATETIME DEFAULT NULL COMMENT '发货时间，未发货为 NULL' AFTER pay_method,
    ADD COLUMN complete_time DATETIME DEFAULT NULL COMMENT '完成时间，未确认为 NULL' AFTER ship_time;


-- ---------------------------------------------------------------------------
-- 3. 检查迁移结果
-- ---------------------------------------------------------------------------
SELECT '迁移完成，下面是验证结果' AS msg;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
  AND COLUMN_NAME IN ('ship_time', 'complete_time');

-- 已有订单应该都是两个 NULL（它们本来就还没发过货 / 没被确认过）
SELECT order_no, status, ship_time, complete_time
FROM orders
ORDER BY id DESC
LIMIT 5;

-- ★ 核对列序：这两列应该在 pay_method 之后、idempotency_key 之前，
--   和 mall.sql 建表语句里的顺序一致
SELECT ORDINAL_POSITION, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
ORDER BY ORDINAL_POSITION;
