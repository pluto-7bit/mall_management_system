-- ============================================================================
--  迁移脚本 09：orders 加支付时间 / 取消时间 / 支付方式
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  migration-08 把订单建出来了，但订单表里只有 status 一个状态列，
--  没有任何记录「什么时候付的款」「什么时候取消的」的列。
--
--  同时 OrderStatus 里 PAID / SHIPPED / COMPLETED / CANCELLED 四个常量
--  早就写好了、中文文案写好了、状态流转图也画好了 —— 但没有一行代码写过它们。
--  里程碑 9 把「模拟支付 + 取消订单 + 超时自动取消」接上，
--  状态终于会被真实写入，于是这三个列也终于有了数据要存。
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
--    SHOW COLUMNS FROM mall.orders LIKE 'pay_time';
--  能查到就说明跑过了。
--
--  ⚠️ MySQL 8 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS
--     （那是 MariaDB 的扩展），所以这个脚本重复执行会报
--     "Duplicate column name" 而中断。这是可以接受的 ——
--     迁移脚本本来就只该跑一次，报错比静默跳过更安全。
--
--  【为什么这三列都可以为 NULL？】
--    和 migration-08 那三个收货快照列（NOT NULL）正好相反，这里是 DEFAULT NULL。
--    判断标准是同一句话：**这个列在「这一行刚插入时」有值吗？**
--      - 收货人：下单的那一刻就必须有 → NOT NULL，让「代码忘了传」在 INSERT 时炸掉
--      - 支付时间：下单那一刻【还没有】付款这回事 → 只能 NULL，
--        给它一个默认值（比如 CURRENT_TIMESTAMP）等于把「没付过款」记成「已付款」，
--        那是数据造假，比空着危险得多
--    同理 cancel_time 只有取消过才有值。
--
--  【为什么记 pay_method，却不记 cancel_type？】
--    pay_method：用户在收银台上真的做了这个选择（支付宝/微信/银行卡），
--      不记下来那个选择在库里什么都不留，纯粹是装饰。
--    cancel_type：不外乎「用户主动取消」和「超时自动取消」，但**没有任何代码
--      或界面会去分支判断它**。加一个没有读者的列，比让它可推导更糟 ——
--      真要知道是不是超时取消，cancel_time - create_time 和超时阈值比一下就清楚了。
--    ★ 一句话：**有读者才加列。**
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 mall < migration-09-payment.sql
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
-- 2. 加三列
--
--    ★ 放在 status 后面，跟状态挨着：这三个列在语义上都是「状态的附属信息」，
--      读表的时候 status=1 紧挨着 pay_time=... 一眼就能看懂。
--      mall.sql 里的建表语句用的是同一个顺序，两份脚本要能互相对照。
--
--    ★ pay_method 给 16 位：取值是 ALIPAY / WECHAT / BANK 三个短码，
--      16 位绰绰有余。存【码】不存中文，理由和 OrderStatus 一样 ——
--      码给程序判断，中文随时可以改，中文一变历史数据就对不上了。
-- ---------------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN pay_time    DATETIME    DEFAULT NULL COMMENT '支付时间，未支付为 NULL' AFTER status,
    ADD COLUMN cancel_time DATETIME    DEFAULT NULL COMMENT '取消时间，未取消为 NULL' AFTER pay_time,
    ADD COLUMN pay_method  VARCHAR(16) DEFAULT NULL COMMENT '支付方式：ALIPAY/WECHAT/BANK，未支付为 NULL' AFTER cancel_time;


-- ---------------------------------------------------------------------------
-- 3. 检查迁移结果
--
--    ★ 这里没有加索引，是刻意的：超时扫描的 WHERE 是
--      `status = 0 AND create_time <= ?`，而 idx_status 和 idx_create_time
--      在 migration-08 建表时就已经有了。MySQL 会挑其中一个走，
--      订单量小的时候足够了。【先别急着加联合索引】——
--      加索引要有 EXPLAIN 做依据，不能凭感觉。
-- ---------------------------------------------------------------------------
SELECT '迁移完成，下面是验证结果' AS msg;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
  AND COLUMN_NAME IN ('pay_time', 'cancel_time', 'pay_method');

-- 已有订单应该都是三个 NULL（它们本来就还没付过款 / 没被取消过）
SELECT order_no, status, pay_time, cancel_time, pay_method
FROM orders
ORDER BY id DESC
LIMIT 5;
