-- ============================================================================
--  迁移脚本 18：物流 —— 【加两列 + 建一张表】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--  ⚠️ 跑之前先 mysqldump 备份（照例）。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 17 为止，订单的发货这一步是个【空壳】：
--  OrderAdminMapper.markShipped 只写 status = 2, ship_time = NOW()，
--  没有任何快递信息。于是：
--    · 用户端 Orders.vue 只能显示「发货于 2026-09-20 10:00」——
--      货在哪、单号多少，一个字都没有；
--    · 客服拿不到单号，只能让用户去问商家；
--    · 管理端发完货之后，【任何地方都查不到这一单是怎么发出去的】。
--
--  一句话：这个系统知道「货发出去了」，但不知道「货是怎么出去的、现在到哪了」。
--
-- ============================================================================
--  第一部分：orders 加两列
-- ============================================================================
--
--  ★ 为什么两列都 DEFAULT NULL，而不是 DEFAULT ''
--
--  判据照 after_sale.return_company（里程碑 17，同一件事只是方向相反）：
--
--    「还没发货」是【缺席】，不是值。
--
--  '' 会被 `if (company)` 这类判断当成「有值」→ 用户端订单卡片上
--  显示一行空白（有承运商那一行，但里面什么都没有），而代码不报错。
--  而 NULL 是唯一能表达「这件事还没发生」的取值。
--
--  ── 白拿的好处（和 parent_id / market_price / freight_amount 完全同构）──
--
--  ★ 现有的已发货订单本来就没有单号，NULL 就是它们的真值
--    → 本脚本里【没有任何 UPDATE】。
--  ★ mall.sql 的种子区也一个字不用改（种子区本来就没有订单）。
--  ★ 这是里程碑 13 那条结论的【第四次】应验：
--      「加列」不影响任何现有代码；「收紧约束」才影响。
--
--  ★ 长度对齐既有先例：VARCHAR(50) / VARCHAR(64)
--    和 after_sale.return_company (50) / return_tracking (64) 完全一致 ——
--    同样是「快递公司 + 单号」，只是方向相反（那边是买家寄回）。
--    不重新发明长度：两处不一样长的话，下一个加类似字段的人
--    要在两个数之间猜一个。
--
--  ★ 为什么是 AFTER ship_time
--
--  照 migration-14 / 17b 的位置论证：按「读的人会怎么理解这张表」排。
--  logistics_company / tracking_no 和 ship_time 是同一件事的三半
--  （什么时刻发的、用什么发的、单号多少），中间隔着十几列会让每次
--  SHOW CREATE TABLE 都要来回找。
--  ⚠️ 【不是追加到末尾】—— 列序必须和迁移链一致，否则
--     sql/test-schema-sync.py 的逐列比对会错位。这是里程碑 10 的教训
--     （migration-10-ship.sql 里为 ship_time 踩过一次）。
--
-- ============================================================================
--  第二部分：order_logistics（物流轨迹节点）
-- ============================================================================
--
--  ★★ 这张表记的是【外部世界发生过的事】，不是系统内部的状态。
--     这个区别决定了本轮的几乎每一个设计，所以写在建表之前：
--
--     轨迹可以独立于订单状态存在（有轨迹 ≠ 有状态变化）；
--     订单状态绝不能从轨迹里【猜】出来。
--
--     反面教材：按 description 里有没有「已签收」三个字去判断订单该不该完成。
--     它一定会在有人写下「已签收失败，改约明天」的那天静默出错。
--     ★ 所以：节点状态必须是【码】（status TINYINT），
--       而承运商/单号必须是【自由文本】（VARCHAR）——
--       同一批字段里两个相反的决定，判据是同一条：
--       「谁读它、读它来做什么」。
--         · 节点状态：前端要按它画图标和颜色；它还是「自动完成订单」的触发条件
--           → 必须是一个可判定的封闭集合 → 码
--         · 承运商名：是外部世界的名字，无法穷举，没有任何人按它做分支
--           → 自由文本（论证逐字同 AfterSaleReturnDTO 的注释）
--
--  ★ 表名 order_logistics，不是 logistics_trace
--    和 order_item 同前缀、同「订单的子记录」语义。
--    而 logistics_company / tracking_no 留在 orders 上，不进这张表 ——
--    那是「这一单怎么发出去的」，是【订单级】事实（单包裹）。
--    放进轨迹表意味着每条节点抄一份承运商，改一次要改 N 行。
--
--  ★★ 索引是 (order_id, trace_time, id)，不是 (order_id, id)
--
--    因为【补录是这个功能的默认用法】，不是边缘情况：
--    管理员白天忙，晚上把一天的节点一次性补进去 —— 于是同一张订单的
--    trace_time 顺序和 id 顺序【相反】。
--    只按 id 排的症状：补录之后时间线倒过来（今天的「已揽收」显示在
--    昨天的「运输中」上面），而页面、代码、控制台【全都正常】。
--
--    ⚠️ 而查询里的次级键 `, id DESC` 也是必需的、不是装饰：
--       两个节点被填了【完全相同】的 trace_time 时（比如「已揽收」和
--       「运输中」都填 09:00），单靠 trace_time 排不出先后，MySQL 会返回
--       【不确定的顺序】—— 它每次查询都可能不一样，断言会随机红，
--       然后被人用 DISTINCT 掩盖。同「同秒创建的两张售后单要取 MAX(id)」。
--
--  ★ 为什么不存 order_no 快照（而 after_sale 存了）
--
--    判据③「有读者才加列」：轨迹【只在查某一单的物流时被读】，
--    那时候手里已经有 order_id 了（接口路径就是 /orders/{orderNo}/logistics，
--    先按单号查到订单，再按 id 查轨迹）。
--    零读者的列不加。after_sale 存 order_no 是因为管理端售后列表
--    【按单号搜】且刻意不 join orders —— 那个读者在这里不存在。
--
--  ★ 不加外键 —— 全库零外键是既定约定（见 README）。
--  ★ 不加 logistics_company / tracking_no / order_no / 操作人 / 更新时间。
--    前三者见上；「操作人」是管理端零审计的既定取舍的一部分（管理端
--    目前不记录任何操作人），单独给物流加一个会开一个只在这里成立的先例。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. orders 加两列
-- ---------------------------------------------------------------------------

ALTER TABLE orders
    ADD COLUMN logistics_company VARCHAR(50) DEFAULT NULL
        COMMENT '承运商（快递公司）。下单时未知，发货时填；历史订单为 NULL' AFTER ship_time,
    ADD COLUMN tracking_no       VARCHAR(64) DEFAULT NULL
        COMMENT '快递单号。同 logistics_company' AFTER logistics_company;


-- ---------------------------------------------------------------------------
-- 2. 新建 order_logistics
-- ---------------------------------------------------------------------------

CREATE TABLE `order_logistics` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属订单 id（故意不加外键）',
    `status`      TINYINT         NOT NULL                COMMENT '节点状态码：1=已揽收 2=运输中 3=派送中 4=已签收 5=异常',
    `description` VARCHAR(255)    NOT NULL                COMMENT '这一节点的说明（管理员手写，如「快件已到达【杭州转运中心】」）',
    `trace_time`  DATETIME        NOT NULL                COMMENT '★ 这一节点【发生】的时刻（管理员可填过去的时刻 = 补录）',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_trace` (`order_id`, `trace_time`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '订单物流轨迹（管理员手工录入，不是快递公司推送）';


-- ---------------------------------------------------------------------------
-- 3. 验证：列和表都建出来了，位置对，可空性对
-- ---------------------------------------------------------------------------

SHOW CREATE TABLE orders;
SHOW CREATE TABLE order_logistics;

-- ★★ 核对列的位置和可空性。两列都必须是 YES（可空）——
--    如果是 NO，说明有人把它们写成了 NOT NULL，那会让【所有历史已发货订单】
--    在这一步就建不出来（或者被填上一个默认值 = 编数据）。
-- ★ 顺带确认它们紧跟在 ship_time 后面（位置连续）。
SELECT ORDINAL_POSITION AS 位置, COLUMN_NAME, COLUMN_TYPE,
       IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders'
  AND COLUMN_NAME IN ('ship_time', 'logistics_company', 'tracking_no', 'complete_time')
ORDER BY ORDINAL_POSITION;

-- ★ 轨迹表的完整列清单（要比对 mall.sql 里那一段）
SELECT ORDINAL_POSITION AS 位置, COLUMN_NAME, COLUMN_TYPE,
       IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_logistics'
ORDER BY ORDINAL_POSITION;

-- ★ 索引必须只有 PRIMARY 和 idx_order_trace 两个
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_logistics'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;


-- ---------------------------------------------------------------------------
-- 4. 验证：迁移没有发明数据（这一条是「加列不影响现有数据」的证据）
-- ---------------------------------------------------------------------------

-- ★★ 必须返回 0 行。5 笔真实订单跑之前没有单号，跑之后也不该有。
--    如果这里出现非 0，说明本脚本（或它的某个版本）写了 UPDATE ——
--    那就是在给历史订单【编造】一个它从来没有过的快递单号。
SELECT order_no, ship_time, logistics_company, tracking_no
FROM orders
WHERE logistics_company IS NOT NULL OR tracking_no IS NOT NULL;

-- ★ 新表必须是空的
SELECT COUNT(*) AS 轨迹节点数 FROM order_logistics;


-- ---------------------------------------------------------------------------
-- 5. 验证：用户的订单数据没被动过
-- ---------------------------------------------------------------------------

SELECT COUNT(*) AS 订单数 FROM orders;
SELECT COUNT(*) AS 订单明细数 FROM order_item;
SELECT COUNT(*) AS 售后单数 FROM after_sale;
SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS SKU数 FROM product_sku;

-- ★ 核对 5 笔真实订单一个数都没变
SELECT id, order_no, status, total_amount, ship_time FROM orders ORDER BY id;

-- ★ 核对「没有外键」这条约定：必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';

SELECT '=== 18 完成。后端此刻完全不受影响（Java 里还没人读这三样）。===' AS msg;
SELECT '=== 下一步：把 orders 这两列和 order_logistics 建表段同步进 mall.sql，然后跑 test-schema-sync.py。===' AS msg;
