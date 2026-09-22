-- ============================================================================
--  迁移脚本 08：收货地址簿 + 订单收货快照
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【为什么不直接跑 mall.sql？】
--  mall.sql 是一份「DROP 掉所有表再重建」的初始化脚本，
--  它的定位是「把数据库恢复成刚建好的样子」。
--  你库里已经有自己录入的商品和会员了，跑它会把那些数据全部清空。
--
--  所以从里程碑 8 起，schema 的变更走【增量迁移】：
--    - mall.sql          → 全量脚本，更新它是为了「新人拿到项目能一键建库」
--    - migration-XX.sql  → 增量脚本，更新它是为了「已有数据的库能跟上」
--  两份都要改，这是维护 schema 的常规做法。
--  （真实项目里这件事由 Flyway / Liquibase 这类工具自动管，
--    它会记录哪些迁移已经跑过。这里手工做，但你得知道两份脚本的关系。）
--
--  【怎么确认这个脚本已经跑过了？】
--    SHOW TABLES FROM mall LIKE 'member_address';
--    SHOW COLUMNS FROM mall.orders LIKE 'idempotency_key';
--  都能查到就说明跑过了。
--
--  ⚠️ MySQL 8 的 ALTER TABLE 不支持 ADD COLUMN IF NOT EXISTS
--     （那是 MariaDB 的扩展），所以这个脚本重复执行会报
--     "Duplicate column name" 而中断。这是可以接受的 ——
--     迁移脚本本来就只该跑一次，报错比静默跳过更安全。
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < migration-08-order.sql
-- ============================================================================

SET NAMES utf8mb4;
USE mall;

-- ---------------------------------------------------------------------------
-- 1. 新建收货地址簿
--    详细的设计说明见 mall.sql 里 member_address 那一段
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS member_address (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    member_id   BIGINT UNSIGNED NOT NULL                COMMENT '所属会员 id',
    receiver    VARCHAR(50)     NOT NULL                COMMENT '收货人姓名',
    phone       VARCHAR(20)     NOT NULL                COMMENT '联系电话',
    region      VARCHAR(100)    NOT NULL                COMMENT '所在地区（省市区）',
    detail      VARCHAR(255)    NOT NULL                COMMENT '详细地址（街道门牌）',
    is_default  TINYINT         NOT NULL DEFAULT 0      COMMENT '是否默认地址：1=是 0=否',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_member_id (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会员收货地址表';


-- ---------------------------------------------------------------------------
-- 2. orders 加收货快照列和幂等键
--
--    ★ 加【非空且无默认值】的列，是有前提的：表必须是空的。
--      如果 orders 表里已经有数据，这条 ALTER 会直接失败
--      （因为已有的行不知道这些列该填什么）。
--
--      那时候的正确做法是分三步：
--        ① 先加成可空（或给一个临时默认值）
--        ② 写一条 UPDATE 把已有行回填
--        ③ 再 ALTER 改成 NOT NULL
--      这个「加列三步走」在真实项目里很常见 ——
--      生产库的表永远是有数据的，不能假设它是空的。
--
--      这里 orders 表确实是空的，所以一步到位。
--      加非空且不给默认值，是为了让「代码忘了传收货信息」在
--      INSERT 时就立刻报错，而不是悄悄写进一行空的收货地址。
--      **约束越早报错越好，不要给一个看起来能用的默认值。**
-- ---------------------------------------------------------------------------
ALTER TABLE orders
    ADD COLUMN receiver_name    VARCHAR(50)     NOT NULL                 COMMENT '收货人姓名（下单时快照）' AFTER member_id,
    ADD COLUMN receiver_phone   VARCHAR(20)     NOT NULL                 COMMENT '收货电话（下单时快照）' AFTER receiver_name,
    ADD COLUMN receiver_address VARCHAR(255)    NOT NULL                 COMMENT '收货地址全文（下单时快照）' AFTER receiver_phone,
    ADD COLUMN address_id       BIGINT UNSIGNED DEFAULT NULL             COMMENT '来源地址 id，仅作追溯用，故意不加外键' AFTER receiver_address,
    ADD COLUMN idempotency_key  VARCHAR(64)     NOT NULL                 COMMENT '幂等键（防重复提交）' AFTER status,
    ADD UNIQUE KEY uk_idempotency_key (idempotency_key);


-- ---------------------------------------------------------------------------
-- 3. 检查迁移结果
-- ---------------------------------------------------------------------------
SELECT '迁移完成，下面是验证结果' AS msg;

SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME IN ('member_address', 'orders');

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
  AND COLUMN_NAME IN ('receiver_name', 'receiver_phone', 'receiver_address',
                      'address_id', 'idempotency_key');

SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
  AND INDEX_NAME = 'uk_idempotency_key';
