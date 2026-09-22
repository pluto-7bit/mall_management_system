-- ============================================================================
--  商城管理系统 —— 数据库初始化脚本
--  数据库: mall       MySQL 8.0
--
--  ⚠️ 注意：这个脚本会 DROP 掉 mall 库里现有的表并重建（含种子数据）。
--     它的定位是「随时把数据库恢复到干净初始状态」，方便你反复练习。
--     等你开始录入自己的测试数据后，再执行它就会清空，请留意。
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p < mall.sql
-- ============================================================================

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE mall;

DROP TABLE IF EXISTS product_review_image;
DROP TABLE IF EXISTS product_review;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS product_image;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS member_address;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS admin_user;

-- ⚠️ DROP 的顺序是「子表在前、主表在后」
--    （product_review_image 依赖 product_review，order_item/product_image 依赖 orders/product，
--      product_review 依赖 order_item/product/member）。
--    加表的时候如果不是追加在末尾，就要想一下这个顺序。
--
--    ★ 尤其是 product_image 和 product_review：
--      product.id 是 AUTO_INCREMENT，而这个脚本是 DROP 重建脚本 ——
--      重建后 id 【从 1 重新分配】。少写这两条 DROP 的话，
--      旧库里遗留的行会认领到全新的商品上：新种子商品带着上一个库里的图集、
--      甚至带着上一个库里别人的评价。
--      这比「留一堆孤儿行」难查得多，因为页面上一切「看起来正常」——
--      只是图是错的、评价是别人的。后者还要严重一层：
--      孤儿行只是垃圾数据，错位的评价是「把评价挂在了错误的商品上」。


-- ---------------------------------------------------------------------------
--  全库约定：★ 故意不加任何外键
-- ---------------------------------------------------------------------------
--  10 张表一个 FOREIGN KEY 都没有，这是刻意的，不是漏了。
--
--  一句话理由：
--    **加外键之后，「删主表那一行」会变成一条可能失败的语句，
--      而数据库约束拦下来的错误只能翻译成一句「系统繁忙」。**
--
--  展开说两点：
--    1. 外键拦的是「这个主表行还有明细」，但这个信息应用层【已经知道】——
--       它完全可以在删之前先查一次明细，给出「请先删除该商品的图片」
--       这种有用的提示。有外键的话，那句提示会退化成一个 SQL 约束异常。
--    2. 外键把「删除顺序」变成一个隐式的、看不见的规则。没有外键时，
--       顺序写在 Java 代码里（比如 ProductServiceImpl.delete 先删图集再删商品），
--       读代码的人必须显式地想一遍「先删哪个」—— 这个思考是好事。
--
--  ⚠️ 代价必须说清楚：数据库不替你拦，你就得更小心。
--     孤儿行不会有人报错，只能靠测试断言去查
--     （sql/test-upload.py / sql/test-review.py 里都有 LEFT JOIN 找孤儿的用例）。
--
--  ⚠️ 顺带说明这里的 order_item / product_image / product_review 为什么是「明细表」：
--     它们都持有 product_id，但都没有外键 —— 所以【商品可以被硬删除】
--     而明细行留下来。order_item 靠快照（product_name / price）自洽，
--     ProductMapper.increaseStock 在商品已不存在时影响 0 行、记 warn 后继续。
--     product_image / product_review 不能这样：它们没有任何快照，
--     商品没了它们就只是垃圾行，所以 ProductServiceImpl.delete 必须先把它们删掉。
--     ★ 而且 product_review 是【两级】的：晒图挂在评价下面，
--       所以删商品是四级（晒图 → 评价 → 图集 → 商品），一层都不能反。
--
--  这份说明被 ProductMapper.increaseStock 的 javadoc 引用
--  （「见 mall.sql 里那段说明」）—— 改这里的时候留意那边。


-- ---------------------------------------------------------------------------
-- 1. admin_user  管理员表
-- ---------------------------------------------------------------------------
CREATE TABLE admin_user (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(50)     NOT NULL                COMMENT '登录账号',
    password    VARCHAR(100)    NOT NULL                COMMENT '密码（BCrypt 加密后，长度固定 60）',
    nickname    VARCHAR(50)     DEFAULT NULL            COMMENT '昵称',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=启用 0=禁用',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理员表';


-- ---------------------------------------------------------------------------
-- 2. category  商品分类表
-- ---------------------------------------------------------------------------
--    ★ uk_name 是唯一索引，它和 Service 里的重名校验是「两道防线」：
--      应用层校验负责给出友好提示（「分类名称「手机数码」已存在」），
--      唯一索引负责兜住并发 —— 两个请求同时新增同名分类时，
--      应用层两边都查到「不存在」，只有数据库能挡住第二次插入。
--      两者不是二选一，而是都要有。
--
--      顺带一个容易忽略的点：name 的排序规则是 utf8mb4_0900_ai_ci，
--      ci = case insensitive（大小写不敏感），ai = accent insensitive（重音不敏感）。
--      所以 'TEST' 和 'test' 会被判为重复。这是 MySQL 8 的默认排序规则。
--      重要的是：应用层的 WHERE name = ? 用的是<b>同一个</b>排序规则，
--      所以两边判断标准一致，不会出现「应用层放行、数据库拒绝」的错位。
CREATE TABLE category (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)     NOT NULL                COMMENT '分类名称（唯一）',
    sort        INT             NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=启用 0=禁用',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_sort (sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品分类表';


-- ---------------------------------------------------------------------------
-- 3. product  商品表
--
--    价格用 DECIMAL(10,2) 而不是 FLOAT/DOUBLE：
--    浮点数存不下 0.1 这种十进制小数，累加会出误差（0.1+0.2 != 0.3），
--    钱绝对不能用浮点存，这是硬性规矩。
--
--    stock 扣减靠 "UPDATE ... WHERE stock >= ?" 配合行锁保证不超卖，
--    详见后续下单流程的实现。
-- ---------------------------------------------------------------------------
CREATE TABLE product (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_id BIGINT UNSIGNED NOT NULL                COMMENT '所属分类 id',
    name        VARCHAR(100)    NOT NULL                COMMENT '商品名称',
    price       DECIMAL(10, 2)  NOT NULL                COMMENT '售价（元）',
    stock       INT             NOT NULL DEFAULT 0      COMMENT '库存数量',
    cover       VARCHAR(255)    DEFAULT NULL            COMMENT '封面图 URL',
    description VARCHAR(500)    DEFAULT NULL            COMMENT '商品描述',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=上架 0=下架',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_name (name),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';


-- ---------------------------------------------------------------------------
-- 4. member  会员表（下单的人）
-- ---------------------------------------------------------------------------
CREATE TABLE member (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(50)     NOT NULL                COMMENT '登录账号',
    password    VARCHAR(100)    NOT NULL                COMMENT '密码（BCrypt 加密后）',
    nickname    VARCHAR(50)     DEFAULT NULL            COMMENT '昵称',
    phone       VARCHAR(20)     DEFAULT NULL            COMMENT '手机号',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=正常 0=禁用',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会员表';


-- ---------------------------------------------------------------------------
-- 5. member_address  收货地址簿
--
--    一个会员可以存多个收货地址，下单时挑一个用。
--
--    ★ region 和 detail 为什么拆成两列，而不是一个 address 字段？
--      因为这两段数据的【性质不同】：
--        region 是「行政区划」，是可枚举的、有层级的（省/市/区）
--        detail 是「门牌号」，是自由文本
--      真实商城里 region 由三级联动选择器产生，detail 由用户手打。
--      未来要做「按城市统计订单量」时，能直接对 region 做分组，
--      不必去正则解析一段自由文本。
--
--    ⚠️ 本项目【没有做】省市区三级联动：region 就是一个普通输入框。
--       原因是那需要一份行政区划数据字典 + 一个三级联动组件，
--       是一块和「事务 / 锁 / 快照」这些核心要点无关的独立工作量。
--       这个取舍是刻意的 —— 把 region 拆成一列已经保留了
--       「行政区划和门牌号是两种数据」这个概念，这就够了。
--
--    ★ is_default 没有用唯一索引来保证「一个会员只有一个默认地址」。
--      唯一索引挡不住「一个会员【零个】默认地址」这种情况，
--      而「一个都没有」同样是需要处理的坏状态。
--      所以这个约束只能由 Service 维护：设默认时先把该会员的其他地址
--      全部置 0，再置 1（见 AddressServiceImpl.setDefault）。
--      判断一个约束该放数据库还是应用层，标准是
--      「数据库能不能完整表达它」—— 表达不全的，就别硬塞。
-- ---------------------------------------------------------------------------
CREATE TABLE member_address (
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
-- 6. orders  订单主表
--
--    表名用 orders 而不是 order：ORDER 是 SQL 保留字，
--    用 order 当表名每次都得写反引号 `order`，容易踩坑。
--
--    total_amount 是下单那一刻算出来的总金额，同样属于「快照」。
--
--    ★ 收货信息（receiver_*）为什么是【三列文本】而不是只存一个 address_id？
--
--    这是整张表最值得想清楚的一处设计，和 order_item 里存商品名快照
--    是同一个道理：**订单是历史事实，不是对当前状态的引用。**
--
--    假设只存 address_id：用户下单时填的是「张三，深圳」，三个月后
--    把这条地址改成了「李四，北京」。那么这份历史订单的收货人是谁？
--    查出来会变成李四 —— 但货早就寄给张三了。
--    更糟的是用户直接【删掉】那条地址，订单就彻底查不到收货信息了。
--
--    所以收货信息必须在下单那一刻【抄一份】存进订单表。
--    address_id 也留着，但它的用途只有一个：追溯「当时用的是地址簿里哪一条」，
--    它是线索，不是真相。真相是那三列文本。
--
--    ⚠️ 注意这里【故意不加外键】到 member_address。
--       加了外键的话，用户想删一个用过的地址就会被数据库拒绝
--       （或者要级联删掉订单，那更是灾难）。
--       **快照表和来源表之间不该有强引用** —— 一旦强引用，
--       来源表就不再是「可以随便改的草稿」，而变成了「动不了的档案」。
--
--    ★ idempotency_key 是防重复提交用的（见 OrderServiceImpl.createOrder）。
--       唯一索引是最后一道防线：即使应用层的预检查因为并发漏掉了，
--       数据库也一定会挡住第二条重复的订单。
--       这类「先查出没有、再插入」的并发缺口，只能靠唯一索引兜住 ——
--       和 category.uk_name 是同一个模式。
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no         VARCHAR(32)     NOT NULL                COMMENT '订单号（业务唯一编号，非自增 id）',
    member_id        BIGINT UNSIGNED NOT NULL                COMMENT '下单会员 id',
    receiver_name    VARCHAR(50)     NOT NULL                COMMENT '收货人姓名（下单时快照）',
    receiver_phone   VARCHAR(20)     NOT NULL                COMMENT '收货电话（下单时快照）',
    receiver_address VARCHAR(255)    NOT NULL                COMMENT '收货地址全文（下单时快照）',
    address_id       BIGINT UNSIGNED DEFAULT NULL            COMMENT '来源地址 id，仅作追溯用，故意不加外键',
    total_amount     DECIMAL(10, 2)  NOT NULL                COMMENT '订单总金额（快照）',
    status           TINYINT         NOT NULL DEFAULT 0      COMMENT '状态：0=待付款 1=已付款 2=已发货 3=已完成 4=已取消',
    -- ★ 下面这【5 个】生命周期列都可为 NULL，和上面三个收货快照列（NOT NULL）正好相反。
    --   判断标准是同一句话：这个列在「这一行刚插入时」有值吗？
    --   收货人下单时就必须有 → NOT NULL，让代码忘了传时在 INSERT 就炸；
    --   支付/取消/发货/完成时间下单时【都还没有】这回事 → 只能 NULL。
    --   给它一个默认值 CURRENT_TIMESTAMP 等于把「没付过款」记成「已付款」，
    --   那是数据造假，比空着危险得多。
    --   见 migration-09-payment.sql 和 migration-10-ship.sql。
    pay_time         DATETIME        DEFAULT NULL            COMMENT '支付时间，未支付为 NULL',
    cancel_time      DATETIME        DEFAULT NULL            COMMENT '取消时间，未取消为 NULL',
    pay_method       VARCHAR(16)     DEFAULT NULL            COMMENT '支付方式：ALIPAY/WECHAT/BANK，未支付为 NULL',
    -- ★ 下面两列同理（见 migration-10-ship.sql）。为什么加，而 cancel_type 又不加？
    --   判断标准只有一句：**有读者才加列**。
    --   这两列有确定的读者 —— 里程碑 10 的两个订单页面要显示
    --   「已发货 2026-09-22」「已完成 2026-09-22」；
    --   cancel_type 没有任何代码会分支判断它，所以不加。
    --
    --   ⚠️ 位置是插在这里而不是追加到末尾，不要随手挪 ——
    --   migration-09/10 刻意让「迁移链跑出来的列序」和这份建表语句逐列一致，
    --   好让「跑过迁移的老库」和「新建的库」SHOW CREATE TABLE 出来一模一样。
    ship_time        DATETIME        DEFAULT NULL            COMMENT '发货时间，未发货为 NULL',
    complete_time    DATETIME        DEFAULT NULL            COMMENT '完成时间，未确认为 NULL',
    idempotency_key  VARCHAR(64)     NOT NULL                COMMENT '幂等键（防重复提交，作用域是同会员内唯一）',
    remark           VARCHAR(255)    DEFAULT NULL            COMMENT '订单备注',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    update_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    -- ★ 幂等键的唯一性【按会员】而不是全局，理由见 migration-08b 的注释：
    --   幂等的作用域 = 幂等主体 + 幂等键。只对键本身建唯一索引，
    --   会让 A 抢先把某个键占住、导致 B 用同样的键下不了单。
    UNIQUE KEY uk_member_idempotency (member_id, idempotency_key),
    KEY idx_member_id (member_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单主表';


-- ---------------------------------------------------------------------------
-- 7. order_item  订单明细表
--
--    ★ 这张表是全库设计上最值得琢磨的地方。
--
--    price 和 product_name 存的是「下单当时的快照」，不是外键关联查出来的现值。
--    原因：商品改价 / 改名后，历史订单必须保持原样。
--    如果订单金额靠 join product 表现算，商品一改价，去年的订单金额全跟着变
--    —— 这在真实业务里是重大事故。
--
--    subtotal 同理，存下来避免每次重算，也避免精度问题。
-- ---------------------------------------------------------------------------
CREATE TABLE order_item (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id     BIGINT UNSIGNED NOT NULL                COMMENT '所属订单 id',
    product_id   BIGINT UNSIGNED NOT NULL                COMMENT '商品 id',
    product_name VARCHAR(100)    NOT NULL                COMMENT '商品名称（下单时快照）',
    price        DECIMAL(10, 2)  NOT NULL                COMMENT '单价（下单时快照）',
    quantity     INT             NOT NULL                COMMENT '购买数量',
    subtotal     DECIMAL(10, 2)  NOT NULL                COMMENT '小计 = price * quantity',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细表';


-- ---------------------------------------------------------------------------
-- 8. product_image  商品图集
--
--    ★ 为什么单独一张表，而不是给 product 加一个 images 列存 JSON / 逗号拼接？
--      因为图集是「一个商品有 N 张图，且顺序可以调」——
--      这是一个一对多关系，它天然就是一张表。
--      塞进一个列的话，「上移一张图」会变成「读出整个字符串、在 Java 里
--      拆开、换位、再拼回去、整列覆盖写」——一个读改写操作，
--      两个人同时编辑就会互相覆盖，而且 SQL 层面完全看不出来发生了这件事。
--
--    ★ 这张表和新加的 /uploads/** 静态资源映射是配套的：
--      url 存的都是 /uploads/yyyy/MM/<uuid>.<ext> 这种【本服务自己产出的路径】，
--      由 Service 层强制校验（不接受 http:// 外链，也不接受 /images/ 这类老路径）。
--      所以这个列的值域是可控的，不是「用户随手填的任意字符串」。
--      product.cover 没有这个待遇 —— 它从建表起就是手填的，至今仍然可以是外链。
--
--    ★ sort_no 是这张表存在的理由之一（用户端按它展示，管理端按它上下移动）。
--      ORDER BY 的时候一定要带上 id 作为决胜列 —— sort_no 有可能重复
--      （比如将来批量导入的数据全是默认值 0），没有决胜列时
--      同一份数据两次查出来的顺序可以不一样。
--      这条规矩和订单列表的 ORDER BY create_time DESC, id DESC 是同一条。
-- ---------------------------------------------------------------------------
CREATE TABLE product_image (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id  BIGINT UNSIGNED NOT NULL                COMMENT '所属商品 id',
    url         VARCHAR(255)    NOT NULL                COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png',
    sort_no     INT             NOT NULL DEFAULT 0      COMMENT '展示顺序，从 0 开始',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品图集';

-- ⚠️ 这张表【没有种子数据】，这是刻意的。
--    图集只能由管理端上传产生，全新装出来的库一件商品都不该有图集。
--    顺带一个假报警要认得出来：sync-mall-seed.py 的自检里数「product 的 INSERT」
--    用的是【子串匹配】，而 product_image 的 INSERT 语句里恰好包含那个子串
--    （它是以 product 开头的那张表的名字打头的）。所以 seed 段里一旦出现
--    product_image 的插入语句，就会报「product 的 INSERT 不止一处」——
--    一个和真实原因毫无关系的提示。
--    ⚠️⚠️ 这条注释本身也不能把那个字符串原样写出来，否则它会自己触发这条假报警
--          （写这份文件的时候就这么踩过一次）。
--    ★ 里程碑 12 加的两张评价表【同样以 product 开头】，所以它们也刻意没有种子数据 ——
--      理由见下面第 10 节末尾。


-- ---------------------------------------------------------------------------
-- 9. product_review  商品评价
--
--    ★ 这张表存在的理由不是「存评价」，是【一条订单明细只能评一次】。
--      用户对评价的选择是「不能改也不能删，一次定终身」。而这件事
--      **只有数据库能真的保证**：Service 里那句「先查有没有评过」挡不住并发 ——
--      两个请求可以同时查到「没评过」，然后两个都往下走。
--
--      uk_order_item 这个唯一索引，和下单的幂等键
--      uk_member_idempotency (member_id, idempotency_key) 是同一个手法：
--      **把「不可能发生」交给数据库。** 代价是一个索引，
--      收益是这个不变量【永远】成立 —— 不管将来谁写了什么代码、并发有多高。
--
--    ★ 为什么 product_id / member_id 是冗余列（它们能从 order_item_id 推出来）？
--      判断标准一直是那一条：**有读者才加列。**
--      两列都有确定的、高频的读者（详情页的评价列表和聚合、昵称 join、
--      管理端按会员筛选），所以加。
--      而冗余唯一的危险是「两个地方会不一致」，在这个场景里【它不存在】：
--      order_item 行一旦写下就永不修改（它的 product_id 是「去查原始商品的
--      线索」，不是会被改的快照字段），所以从它推导出来的这两列也永不改变。
--      **源头不可变时，冗余没有代价。**
--
--    ⚠️ rating 【故意不加】 CHECK (rating BETWEEN 1 AND 5)，
--       尽管 uk_order_item 已经开了「让数据库兜底」的先例。
--       区别在**这两件事是不是并发问题**：并发挡不住 → 必须靠索引；
--       rating 越界不是并发问题 → DTO 上的 @Min(1)/@Max(5) 就够了，
--       而且校验失败还能给出「评分最低 1 星」这种说得清的错误。
--       全库 10 张表现在一个 CHECK 都没有，破例要先有理由。
--
--    ★ 三个索引各有各的查询：
--        uk_order_item  写评价时必查（也是那个不变量本身）
--        idx_product    详情页每一次打开都要「按商品分页、按时间倒序」——
--                       第二列 create_time 让 ORDER BY 免排序
--        idx_member     管理端「按会员筛选」
--      判据不是「表变大了要加索引」，而是「有没有一条查询会用到它」。
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


-- ---------------------------------------------------------------------------
-- 10. product_review_image  评价晒图
--
--    ★★ 这张表和 product_image 是刻意的一处【对照】—— 它没有 sort_no。
--       判据还是那一条：**有没有读者。**
--         product_image.sort_no：图集有上移/下移两个按钮，顺序是
--           「用户能改、下次打开还要原样看到」的东西 → 有读者，加。
--         评价晒图：评价一旦提交就不可修改，**没有任何界面能让用户调整顺序**
--           → 「顺序」这件事没有读者，不加。
--       展示顺序 = 上传顺序 = 插入顺序，ORDER BY id 就够了
--       （id 是唯一的，天然满足「ORDER BY 必须以唯一列结尾」那条规矩）。
--
--       **同一个形态的东西第二次出现时，该复用的复用、该分岔的分岔，
--         理由都要重新问一遍，不能因为「上次加了这次也加」。**
--
--    ★ 为什么单独一张表，而不是给 product_review 加 image1/image2/image3 三列，
--      或者一个逗号拼接的字符串？
--      - 三个列：把「最多 3 张」这个【业务规则焊死进了表结构】——
--        哪天要改成 5 张就得动 schema，而且每张图都要处理「这个槽位是空的」。
--      - 拼接字符串：违反本项目「不存分隔字符串」的约定，
--        而且「删掉中间一张」会变成一次读改写。
--      一对多关系天然就是一张表，这和 product_image 是同一个结论。
--
--    ⚠️ 这两张表【都没有种子数据】，这是刻意的：
--      评价是用户产生的，全新装出来的库一条评价都不该有
--      （和 product_image「图集只能由上传产生」是同一条理由）。
--
--      顺带绕开一个陷阱：sync-mall-seed.py 的自检里数「product 的 INSERT」
--      用的是【子串匹配】，而这两张表的名字都以 product 开头 ——
--      它们一旦有了插入语句，就会触发「product 的 INSERT 不止一处」
--      这个和真实原因毫无关系的提示。不写种子数据，这个坑就根本不存在。
--      （⚠️ 和上面第 8 节末尾同一个警告：写注释时也不能把那串字符原样写出来。）
-- ---------------------------------------------------------------------------
CREATE TABLE product_review_image (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    review_id   BIGINT UNSIGNED NOT NULL                COMMENT '所属评价 id',
    url         VARCHAR(255)    NOT NULL                COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_review_id (review_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评价晒图';


-- ============================================================================
--  种子数据
--  所有账号密码统一是 123456（已 BCrypt 加密）
-- ============================================================================

-- 管理员：admin / 123456
INSERT INTO admin_user (username, password, nickname, status) VALUES
('admin', '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '超级管理员', 1);

-- 分类（注意：休闲零食和床上用品原来只存在于你的库里，
-- mall.sql 里没有它们，所以全新安装时商品会插不进去）
INSERT INTO category (name, sort, status) VALUES
  ('手机数码', 1, 1),
  ('电脑办公', 2, 1),
  ('家用电器', 3, 1),
  ('服饰鞋包', 4, 1),
  ('休闲零食', 5, 1),
  ('床上用品', 6, 1);

-- 商品
-- ⚠️ '优衣库轻型羽绒服' 的库存是故意写成 0 的（不是线上值 2）：这是全新装库时用来手动验「已售罄」的夹具。见 MALL_SEED_STOCK_OVERRIDE。
INSERT INTO product (category_id, name, price, stock, cover, description, status) VALUES
  ((SELECT id FROM category WHERE name = '手机数码'), '小米 15 Pro 手机', 4999.00, 100, '/images/phone-03.svg', '6.73 英寸 2K 全等深微曲屏，龙晶玻璃 2.0\n徕卡光学镜头，支持可变光圈与长焦微距\n第三代骁龙 8 平台，5400mAh 电池\n90W 有线 + 50W 无线快充', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'iPad Air 11 英寸', 4799.00, 50, '/images/tablet-02.svg', '11 英寸 Liquid 视网膜屏，P3 广色域\nM 系列芯片，剪辑和多任务都从容\n支持 Apple Pencil 与妙控键盘\n横向前置摄像头，视频通话更自然', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '联想 ThinkPad X1 Carbon', 9999.00, 30, '/images/laptop-02.svg', '14 英寸 2.8K OLED 屏，100% DCI-P3\n碳纤维机身，重量仅 1.09kg\n经典小红帽与背光键盘，键程舒适\n通过 12 项军标测试，耐用可靠', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '罗技 MX Master 3S 鼠标', 699.00, 200, '/images/mouse-01.svg', '8000DPI 传感器，几乎可在任何表面使用\nMagSpeed 电磁滚轮，一秒滚动千行\n静音按键，点击噪音降低 90%\n可同时连接三台设备并一键切换', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '戴森 V12 吸尘器', 3699.00, 40, '/images/vacuum-01.svg', '激光探测功能，让微尘无处藏身\n整机过滤系统，锁住 99.99% 微尘\n续航最长 60 分钟，可替换电池\n多款吸头覆盖地板、床褥与缝隙', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '美的电饭煲 4L', 399.00, 150, '/images/rice_cooker-01.svg', '4L 容量，适合 3~5 人家庭\nIH 电磁加热，米粒受热更均匀\n12 种预设菜单，支持 24 小时预约\n内胆可拆卸，清洗方便', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '优衣库轻型羽绒服', 598.00, 0, '/images/jacket-02.svg', '轻量设计，可收纳进随身小袋\n90% 羽绒填充，保暖效率高\n防泼水表面，应付小雨小雪\n内搭外穿都合适，通勤旅行皆宜', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'iPhone duo', 15999.00, 100, '/images/phone-04.svg', '双卡双待，工作生活两个号码分开\n超视网膜 XDR 显示屏，HDR 显示出色\nA 系列芯片，日常使用流畅省电\n支持无线充电与 IP68 防水', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'iPhone 18 pro 256G', 9999.00, 1000, '/images/phone-05.svg', '256GB 存储，照片视频随便存\nPro 级三摄系统，支持 ProRAW 与 ProRes\n钛金属中框，强度高且更轻\nProMotion 自适应刷新率，最高 120Hz', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '联想拯救者Y9000P', 9999.00, 999, '/images/laptop-03.svg', '16 英寸 2.5K 电竞屏，240Hz 刷新率\n满血版独立显卡，3A 大作高帧运行\n霜刃散热系统，双风扇多热管\n支持独显直连，游戏延迟更低', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '卫龙辣条', 9.90, 100, '/images/snack_bag-03.svg', '经典麻辣味，面筋筋道有嚼劲\n独立小包装，干净卫生不脏手\n非油炸工艺，解馋无负担\n追剧办公的国民小零食', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '床单', 99.00, 100, '/images/bedding-02.svg', '100% 纯棉，亲肤透气\n高支高密织造，触感细腻\n可机洗，越洗越柔软\n适合 1.5~1.8 米床', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '华为 Mate 70 Pro', 6499.00, 80, '/images/phone-01.svg', '6.8 英寸 OLED 曲面屏，1-120Hz 自适应刷新率\n麒麟芯片 + 鸿蒙系统，支持双向北斗卫星消息\n后置 5000 万可变光圈主摄，支持 4K 视频录制\n5300mAh 电池，100W 有线快充', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '荣耀 Magic7', 4499.00, 120, '/images/phone-02.svg', '6.7 英寸护眼直屏，4320Hz 高频调光\n第三代骁龙 8 移动平台，性能释放稳定\n5650mAh 青海湖电池，支持 100W 快充\nAI 抓拍引擎，运动场景成片率更高', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '小米平板 7', 1999.00, 90, '/images/tablet-01.svg', '11.2 英寸 3.2K 超清屏，144Hz 刷新率\n支持手写笔与磁吸键盘，办公娱乐两用\n8850mAh 大电池，连续看视频约 14 小时\n金属一体化机身，厚度 6.18mm', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '索尼 WH-1000XM5 头戴式耳机', 1899.00, 60, '/images/headphones-01.svg', '业内标杆级主动降噪，8 麦克风系统\n30mm 碳纤维驱动单元，支持 LDAC 高解析音频\n智能免摘对话，开口说话自动暂停音乐\n续航 30 小时，充电 3 分钟可听 3 小时', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'Apple Watch Series 10 智能手表', 3199.00, 45, '/images/watch-01.svg', '更大更薄的广视角 OLED 屏，边框进一步收窄\n支持睡眠呼吸暂停检测与心电图功能\n50 米防水，可记录游泳与浮潜数据\n快充设计，约 30 分钟充至 80%', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '大疆 Osmo Action 5 Pro 运动相机', 2299.00, 35, '/images/camera-01.svg', '1/1.3 英寸传感器，低光画质明显提升\n前后双触摸屏，自拍构图方便\n裸机 20 米防水，无需额外防水壳\n超强防抖，骑行滑雪等剧烈场景也稳定', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), 'MacBook Air 13 英寸 M4', 7999.00, 40, '/images/laptop-01.svg', 'M4 芯片，10 核 CPU + 8 核 GPU\n13.6 英寸 Liquid 视网膜屏，500 尼特亮度\n无风扇设计，运行全程安静\n续航最长 18 小时，重量仅 1.24kg', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '戴尔 U2723QE 27 英寸 4K 显示器', 2999.00, 55, '/images/monitor-01.svg', '3840×2160 分辨率，IPS Black 面板\n98% DCI-P3 色域，出厂逐台校色\n支持 90W Type-C 反向供电，一根线连笔记本\n可升降旋转支架，自带 USB 集线器', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '罗技 K380 多设备无线键盘', 199.00, 300, '/images/keyboard-01.svg', '可同时连接 3 台设备，一键切换\n圆形静音键帽，打字手感轻快\n两节 AAA 电池可用约 2 年\n重量 423g，方便随身携带', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '惠普 LaserJet 无线激光打印机', 1099.00, 25, '/images/printer-01.svg', '黑白激光打印，每分钟 22 页\n支持无线直连与手机 App 打印\n首页输出仅需 8.3 秒\n鼓粉一体设计，更换耗材简单', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '金士顿 128G 金属 U 盘', 89.00, 500, '/images/usb-01.svg', 'USB 3.2 接口，读取速度最高 200MB/s\n金属外壳，抗摔耐磨\n内置钥匙环孔，可挂在钥匙扣上\n五年质保，全国联保', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '格力 1.5 匹变频挂机空调', 2899.00, 30, '/images/ac-01.svg', '新一级能效，APF 值 5.26\n56℃ 高温自清洁，出风更干净\n独立除湿模式，梅雨季很实用\n适用面积 16~20 平方米', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '海尔 465L 十字对开门冰箱', 3599.00, 20, '/images/fridge-01.svg', '十字四门设计，冷藏冷冻分区明确\n风冷无霜，无需手动除冰\n一级双变频，日耗电约 0.85 度\n干湿分储，蔬果和干货各得其所', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '小天鹅 10 公斤滚筒洗衣机', 2199.00, 25, '/images/washer-01.svg', '10kg 大容量，可洗四件套和窗帘\nBLDC 变频电机，静音且寿命长\n95℃ 高温筒自洁，抑菌率 99.9%\n15 分钟快洗模式，应急很方便', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '小米空气净化器 4', 899.00, 60, '/images/purifier-01.svg', '颗粒物 CADR 500m³/h，适用 60 平方米\nOLED 触控屏，实时显示 PM2.5\n三层复合滤芯，更换周期约一年\n支持 App 与语音助手控制', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '优衣库全棉圆领 T 恤', 79.00, 500, '/images/tshirt-01.svg', '100% 纯棉，克重扎实不透\n领口加固不易变形\n版型regular fit，男女同款\n多色可选，日常百搭打底', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '李宁䨻科技跑鞋', 399.00, 200, '/images/shoe-01.svg', '䨻科技中底，回弹明显且轻量\n透气网布鞋面，长时间跑不闷脚\n橡胶大底，湿地抓地力好\n适合日常慢跑与通勤', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '李维斯 511 修身牛仔裤', 459.00, 150, '/images/pants-01.svg', '511 版型，修身不紧绷\n弹力棉面料，活动自如\n经典五袋设计，水洗色自然\n四季可穿，配 T 恤衬衫都行', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '新秀丽商务双肩背包', 599.00, 80, '/images/bag-01.svg', '可放 15.6 英寸笔记本，独立隔层\n背部透气网垫，久背不闷\n防泼水面料，小雨无压力\n行李箱拉杆带，出差可直接挂上', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '波司登中长款羽绒服', 1299.00, 40, '/images/jacket-01.svg', '90% 白鸭绒填充，蓬松度 600+\n中长款过膝设计，保暖范围更大\n防钻绒工艺，久穿不下绒\n可拆卸连帽，两种穿法', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '三只松鼠每日坚果 750g', 79.90, 300, '/images/pouch-01.svg', '30 小袋独立包装，一天一袋\n含核桃、巴旦木、腰果等多种坚果\n搭配蔓越莓干与蓝莓干，口感有层次\n原料当季采购，锁鲜包装', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '良品铺子猪肉脯 200g', 39.90, 400, '/images/snack_bag-01.svg', '原切后腿肉，肉纤维清晰可见\n炭火烘烤工艺，外焦里嫩\n独立小包装，开袋即食\n甜咸适口，追剧办公都合适', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '乐事薯片家庭分享装', 29.90, 500, '/images/snack_bag-02.svg', '家庭分享装，含 5 小包多种口味\n马铃薯切片均匀，酥脆不油腻\n原味、黄瓜味、烧烤味随机搭配\n密封小包装，一次一包不返潮', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '伊利金典纯牛奶 250ml×12', 69.90, 260, '/images/bottle-01.svg', '每 100ml 含 3.8g 优质乳蛋白\n120mg 原生高钙，日常补钙方便\n超高温灭菌，常温保存 6 个月\n12 盒整箱装，学生和上班族常备', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '费列罗榛果威化巧克力 24 粒', 109.00, 150, '/images/box-01.svg', '整颗榛果夹心，外层威化与巧克力\n24 粒礼盒装，送人体面\n原装进口，冷链运输\n独立金箔包装，常温存放即可', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '泰国天然乳胶枕', 199.00, 180, '/images/pillow-01.svg', '93% 天然乳胶含量，回弹支撑好\n波浪造型贴合颈椎，侧睡仰睡都合适\n蜂窝透气孔，夏季不闷热\n内外双层枕套，均可拆洗', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '水星家纺蚕丝被', 899.00, 45, '/images/quilt-01.svg', '100% 桑蚕丝填充，轻盈贴身\n蚕丝被芯可水洗，打理省心\n子母被设计，一床应对四季\n面料亲肤，敏感肌也能用', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '全棉四件套 1.8 米床', 399.00, 120, '/images/bedding-01.svg', '100% 新疆长绒棉，60 支高密\n含被套、床单、枕套两只\n活性印染，不易掉色\n适合 1.8 米床，可直接机洗', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '珊瑚绒加厚盖毯', 129.00, 200, '/images/quilt-02.svg', '双面珊瑚绒，触感柔软\n加厚设计，秋冬保暖效果好\n不掉毛不起球，机洗不变形\n午睡毯、沙发毯、旅行毯都合适', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '记忆棉床垫 1.8 米', 1499.00, 30, '/images/mattress-01.svg', '记忆棉贴合身体曲线，分散压力\n独立袋装弹簧，翻身不互相干扰\n7 区支撑，护腰护颈\n可拆洗床垫套，厚度 20cm', 1);

-- 会员
INSERT INTO member (username, password, nickname, phone, status) VALUES
('zhangsan', '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '张三', '13800138001', 1),
('lisi',     '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '李四', '13800138002', 1),
('wangwu',   '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '王五', '13800138003', 1);
