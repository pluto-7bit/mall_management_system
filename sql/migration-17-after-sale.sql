-- ============================================================================
--  迁移脚本 17：售后 —— 【新建一张表】
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--
--  【这个脚本在解决什么问题？】
--  到里程碑 16 为止，一笔订单付完钱之后的出口只有两个：发货 → 确认收货。
--  里程碑 9 的 OrderStatus 注释里早就写着这一天的缺口：
--    「已经付过款的订单不能直接取消，得走退款流程 ——
--      那是另一条业务线，本项目不实现（见里程碑 9 的说明）。」
--  这个脚本就是那条业务线的第一块砖：一张【独立】的售后单表。
--
--  【为什么是新建一张表，而不是在 orders / order_item 上加几个列？】
--  因为一笔订单里的每一行明细要【分别】退：
--  「我要退那件衣服」和「我要退那双鞋」是两件独立的事，
--  它们各自有自己的状态、自己的理由、自己的退款金额。
--  两个列塞进 order_item 会让「一行只能有一张售后单」——
--  而被拒绝之后必须能重新申请，这一条就否掉了它。
--
-- ============================================================================
--  ★★★ 一张售后单只对【一条】订单明细
-- ============================================================================
--
--  不做「主表 + 售后明细表」（一张售后单退 3 行）的理由是三条独立的：
--
--    ① 状态机会变成汇总。状态挂主单 → 不能逐行审批（可接受）；
--       状态挂明细 → 主单状态变成明细的聚合 —— 那就是漂移，
--       而且是【内生】的（主单状态是派生值）。为了一个「一次点 N 下」的
--       体验，把本轮最贵的那条铁律破在核心表上，不划算。
--
--    ② 「一条明细有没有活跃售后」会有两个定义者
--       （明细表的令牌 + 主单的状态）。症状：主单被拒了、明细的令牌忘了释放
--       → 那一行【永远申请不了售后】，而且没有任何一层会报错。
--
--    ③ 金额会没有唯一答案。一张售后单里退 3 行，「运费退多少」就要引入
--       分摊规则（按金额比例？按件数？按重量？），除不尽时
--       【所有行的退款额之和可能比运费多一分或少一分】——
--       这种差只在特定金额组合下出现，查不出来。
--       这和 README 里那条「跨规格的成本汇总没有唯一答案」是同一个形态。
--
--  ★ 那「整单退」怎么办？它是【体验问题，不是模型问题】：
--    POST /api/shop/after-sales 的 body 收 orderItemIds: [...]，
--    Service 在【一个事务里建 N 张售后单】（全成或全败）。
--    逐行审批反而更接近真实（商家可能同意退 A 不同意退 B）。
--
-- ============================================================================
--  ★★★ 「一条明细不能被重复申请」怎么守 —— 本脚本最需要说清的一处
-- ============================================================================
--
--  先说被【否掉】的两条路：
--
--  ✗ uk_order_item (order_item_id) 单列唯一
--    能禁止「一条明细有两张售后单」，但【同时禁止了「被拒绝后重新申请」】。
--    业务上不可接受，而且「唯一」的范围被答错了。
--
--  ✗ 在 Service 里「先查后写」
--    ★★ 这一条必须否掉，而且已经有血证：
--      ProductReviewServiceImpl.create 里那段 catch (DuplicateKeyException)
--      的注释写着 ——「Java 里的『先查后写』永远挡不住并发，
--      唯一索引才是真正的闸门」。
--    用户双击「提交申请」就能撞出两张单。
--
--  而 MySQL 【没有部分唯一索引】—— 写不出
--      UNIQUE (order_item_id) WHERE status < 3
--  所以「只在活跃期内唯一」这件事没法用普通唯一索引表达。
--
--  ★★ 真正的解法：把「活跃」编码成一个可以被唯一索引看见的值。
--
--      进行中 (status ∈ {0,1,2})  →  active_token = 0
--      已关闭 (status ∈ {3,4,5})  →  active_token = 本行的 id
--
--  于是对同一条 order_item_id，令牌集合是
--      {0} ∪ {这张明细历史上每一张已关闭单的 id}
--  —— 全部互不相等（id 是主键，0 不会是任何一行的 id）。
--  唯一索引因此给出了我们真正想要的那条不变量：
--
--      ★ 一条订单明细，最多只能有一张「进行中」的售后单；
--        已关闭的历史单不限张数。
--
--  而且「关闭」和「释放令牌」是【同一条 UPDATE 的两个列】
--  （见 AfterSaleMapper 的 T2/T4/T6/T7），不存在「忘了释放」这件事。
--
--  ── 被否掉的其它编码方式，两种方向都试过了 ──
--
--  ✗ 加 is_active TINYINT 进唯一索引 (order_item_id, is_active)
--    两张已关闭的单都是 is_active = 0，会【互相冲突】—— 方向反了。
--  ✗ 用可空列让 NULL 表示「进行中」
--    MySQL 把多个 NULL 当成互不相等 → 会【允许多个进行中】。
--    方向同样反了（里程碑 13 的 spec_json 那个坑就是这样来的）。
--
--  ── 必须承认的妥协 ──
--
--  ★★ active_token 和 status 说的是同一件事（关没关）。
--     这是本项目里【唯一一处「同一事实两个表示」】，而且它是被数据库能力
--     逼出来的，不是设计。所以按「定义者唯一」那条判据，必须配两条断言
--     （见本文件末尾第 5 节），它们各抓一个方向：
--        该释放没释放 → 那一行永远申请不了售后
--        该占用没占用 → 同一行能同时有两张进行中的单
--
--  ── 试过但不行的升级：STORED 生成列 ──
--
--  原本想把 active_token 做成生成列，让分岔在结构上不可能：
--      active_token BIGINT UNSIGNED
--          GENERATED ALWAYS AS (IF(status < 3, 0, id)) STORED NOT NULL
--  ★ 实测【建不出来】，MySQL 直接拒绝：
--      ERROR 3109 (HY000): Generated column 'active_token'
--                          cannot refer to auto-increment column.
--    （这是 MySQL 的硬性限制：生成列不能引用 AUTO_INCREMENT 列。）
--  所以退回「普通列 + 两条断言」—— 这就是为什么第 5 节那两条断言
--  不是「顺手加的」，它们【替代了】一个本来想由数据库结构保证的东西。
--  ⚠️ 下一个人不要再试生成列了，这条已经跑过。
--
-- ============================================================================
--  ★ 售后单号为什么单独造一个，而且必须带前缀 AS
-- ============================================================================
--
--  先例核对：orders 有 order_no + uk_order_no，理由有四条
--  （不暴露量级、不可枚举猜别人的单、稳定外部标识、客服会口头报号）。
--  逐条对售后单都成立，其中第 2 条最具体：售后单号会出现在 URL 里
--  （/api/shop/after-sales/{afterSaleNo}/cancel）。
--
--  ★★ 但最关键的一条是【不能复用 OrderNoGenerator 的号码空间】：
--    如果售后单号也长成「14 位时间 + 6 位随机」，那么
--    【一个号码既可能是订单号也可能是售后单号】——
--    客服拿一个号来问，你得先猜去哪个表查。
--    两个不同种类的标识符共用同一个号码空间，是一个会让人查错表的设计。
--    前缀 AS 就是为此存在的（AFTER SALE）。
--    位数：AS + 20 位 = 22 字符 ≤ VARCHAR(32)。
--
--  ★ 调用方必须 catch (DuplicateKeyException) 重试一次 ——
--    uk_after_sale_no 是最后一道防线，和下单是同一条纪律。
--
--  【★ 为什么不用 NULL 表示「没有」的那几个字段】
--  照 migration-14b 立下的判据：「0 是一个值，NULL 是缺席，
--  这两种东西不该共享一个编码。」判断标准是
--  「这个 0 会不会被当成一个【真实存在的数据】去比较和计算」。
--
--    refund_amount   DEFAULT NULL —— 「还没算出来」是缺席。
--                    用 0 的话会被当成「退了 0 元」拿去求和。
--    refund_freight  NOT NULL DEFAULT 0.00 —— 没退款时「属于运费的部分是 0」
--                    是一个【真实的 0】，它会被拿去和 refund_amount 相减。
--    其余时间/文本字段 DEFAULT NULL —— 缺席。
--
--  ★ 全库零外键是既定约定，所以 order_id / order_item_id / member_id
--    都是普通列（删除顺序由代码负责）。
--
-- ============================================================================

CREATE TABLE `after_sale` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `after_sale_no`   VARCHAR(32)     NOT NULL COMMENT '售后单号（前缀 AS，刻意与订单号分属两个号码空间）',
  `order_id`        BIGINT UNSIGNED NOT NULL COMMENT '订单 id（推「整单是否退完」靠它，人不用看）',
  `order_no`        VARCHAR(32)     NOT NULL COMMENT '订单号快照（人看/人搜；售后列表为此不必 join orders）',
  `order_item_id`   BIGINT UNSIGNED NOT NULL COMMENT '被申请的订单明细 id（★ uk_order_item_active 的一半）',
  `member_id`       BIGINT UNSIGNED NOT NULL COMMENT '申请会员 id（安全边界，从订单推导，绝不由客户端提供）',
  `type`            TINYINT         NOT NULL COMMENT '售后类型：1=仅退款 2=退货退款（见 AfterSaleType）',
  `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '0=待审核 1=待买家寄回 2=待卖家收货 3=退款完成 4=已拒绝 5=已撤销',
  `reason`          TINYINT         NOT NULL COMMENT '申请原因码（见 AfterSaleReason；用码不用自由文本）',
  `description`     VARCHAR(255)    DEFAULT NULL COMMENT '用户补充说明（只被原样显示，不参与任何判断）',
  `refund_amount`   DECIMAL(10, 2)  DEFAULT NULL COMMENT '实退金额，退款成功时写入 = 货款 + 退还的运费。★ 刻意 DEFAULT NULL',
  `refund_freight`  DECIMAL(10, 2)  NOT NULL DEFAULT 0.00 COMMENT '其中属于运费的部分（0 是真实值不是缺席）',
  `refund_method`   VARCHAR(16)     DEFAULT NULL COMMENT '退款去向 = 该订单 pay_method 的快照，退款成功时写入',
  `refund_time`     DATETIME        DEFAULT NULL COMMENT '退款时间，未退款为 NULL',
  `reject_reason`   VARCHAR(255)    DEFAULT NULL COMMENT '管理员拒绝的理由',
  `return_company`  VARCHAR(50)     DEFAULT NULL COMMENT '买家寄回的快递公司',
  `return_tracking` VARCHAR(64)     DEFAULT NULL COMMENT '买家寄回的快递单号',
  `return_time`     DATETIME        DEFAULT NULL COMMENT '买家填寄回信息的时间',
  `receive_time`    DATETIME        DEFAULT NULL COMMENT '管理员确认收到退货的时间',
  `active_token`    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '★ 唯一性令牌：0=进行中；关闭时置为本行 id。见文件头部',
  `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
  `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_after_sale_no` (`after_sale_no`),
  UNIQUE KEY `uk_order_item_active` (`order_item_id`, `active_token`),
  KEY `idx_member` (`member_id`, `create_time`),
  KEY `idx_order` (`order_id`),
  KEY `idx_status` (`status`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '售后单';


-- ---------------------------------------------------------------------------
-- 1. 验证：表建出来了，而且列/索引和 mall.sql 一模一样
-- ---------------------------------------------------------------------------

SHOW CREATE TABLE after_sale;

-- ★ 核对整库表数量：应该从 11 张变成 12 张
SELECT COUNT(*) AS 表数量 FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'mall';

-- ★★ 核对本轮的两把锁都在。丢任何一个，这个功能的设计前提就没了：
--      uk_order_item_active —— 「一条明细最多一张进行中的单」
--      uk_after_sale_no     —— 「单号不会重」
SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'after_sale'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;


-- ---------------------------------------------------------------------------
-- 2. 验证：新表是空的（售后是用户产生的，没有任何种子数据）
-- ---------------------------------------------------------------------------

SELECT COUNT(*) AS 售后单行数 FROM after_sale;

-- ★ 核对「没有外键」这条约定：必须返回 0 行
SELECT CONSTRAINT_NAME, TABLE_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'mall' AND CONSTRAINT_TYPE = 'FOREIGN KEY';


-- ---------------------------------------------------------------------------
-- 3. 验证：用户的数据和现有表一条都没被动过
-- ---------------------------------------------------------------------------

-- ★★ 本脚本【没有任何 UPDATE / DELETE / ALTER】，只 CREATE 了一张新表。
--    所以下面这些数字必须和跑之前一模一样。
--    （尤其核对用户手工录入的那 5 件商品：id 9 / 28 / 29 / 75 / 76）
SELECT COUNT(*) AS 分类数 FROM category;
SELECT COUNT(*) AS 商品数 FROM product;
SELECT COUNT(*) AS SKU数  FROM product_sku;
SELECT COUNT(*) AS 会员数 FROM member;
SELECT COUNT(*) AS 订单数 FROM orders;
SELECT COUNT(*) AS 订单明细数 FROM order_item;
SELECT COUNT(*) AS 评价数 FROM product_review;

SELECT id, name, create_time FROM product WHERE id IN (9, 28, 29, 75, 76) ORDER BY id;


-- ---------------------------------------------------------------------------
-- 4. 验证：两条 active_token 断言现在（空表上）必然成立
-- ---------------------------------------------------------------------------

-- ★★ 这两条断言的定义必须和 status 的定义一致。两者一旦分岔，症状是
--    「这条明细永远申请不了售后」（该释放没释放）
--    或者「同一行能同时有两张售后单」（该占用没占用）。
--    空表时两条都是 0 —— 阶段 3 写过 T1~T7 之后，它们才是真的在守东西。
--    （它们在 sql/test-after-sale.py 的 I 组里会被反复重跑。）
SELECT COUNT(*) AS 应为0_进行中却带着令牌 FROM after_sale
 WHERE status IN (0, 1, 2) AND active_token <> 0;

SELECT COUNT(*) AS 应为0_已关闭却令牌为0 FROM after_sale
 WHERE status IN (3, 4, 5) AND active_token = 0;

SELECT '=== 17 完成。后端此刻完全不受影响（没有任何 Java 代码知道 after_sale 存在）。===' AS msg;
SELECT '=== 下一步：跑 migration-17b-order-freight.sql（加运费列），然后同步 mall.sql。===' AS msg;
