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

-- ⚠️ 这里的 COLLATE 必须是 utf8mb4_0900_ai_ci，不能写成 utf8mb4_general_ci。
--
--   ★ 表定义里写的是 `DEFAULT CHARSET = utf8mb4` 而【没有】写 COLLATE，
--     所以每张表的排序规则继承的是【库】的排序规则 —— 这一行决定了全部 12 张表。
--
--   ★ 而线上开发库里的表全都是 utf8mb4_0900_ai_ci。之前这里写的是 general_ci，
--     于是「全新装库」和「跟着迁移走的库」在排序规则上分叉了。
--     这不是学究问题，两者的差别是能看见的：
--       · general_ci 是 **PAD SPACE**，0900_ai_ci 是 **NO PAD** ——
--         所以 '黑' 和 '黑 ' 在两边【是不是同一个值】都不一样。
--         这正是 uk_product_spec（product_sku）拦不拦得住重复的前提，
--         也是 uk_name（category）拦不拦得住的前提。
--       · 中文按 name 排序的顺序两边也不同。
--     ★ 这正是本项目一直在防的那类事故：「我本地是好的」。
--
--   ⚠️ 对【已经存在】的库，CREATE DATABASE IF NOT EXISTS 是空操作，
--     不会改任何东西 —— 所以这一行改的只是「新装出来的库」。
--     已经存在的库要改排序规则得走 ALTER DATABASE / ALTER TABLE，那是另一件事。
--
--   ★ 全库排序规则这件事在 category 那一节的注释里也提到过
--     （「name 的排序规则是 utf8mb4_0900_ai_ci …… 这是 MySQL 8 的默认排序规则」）
--     —— 之前那句话和这一行是矛盾的，现在对上了。
CREATE DATABASE IF NOT EXISTS mall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE mall;

DROP TABLE IF EXISTS product_review_image;
DROP TABLE IF EXISTS product_review;
DROP TABLE IF EXISTS after_sale;
DROP TABLE IF EXISTS order_logistics;
DROP TABLE IF EXISTS order_item;
DROP TABLE IF EXISTS product_image;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS member_address;
DROP TABLE IF EXISTS product_sku;
DROP TABLE IF EXISTS product;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS admin_user;

-- ⚠️ DROP 的顺序是「子表在前、主表在后」
--    （product_review_image 依赖 product_review，order_item/product_image 依赖 orders/product，
--      product_review 依赖 order_item/product/member，
--      product_sku 依赖 product）。
--    加表的时候如果不是追加在末尾，就要想一下这个顺序。
--
--    ★ product_sku 排在这里，是为了让「product 的子孙都清干净了，才轮到 product」
--      这个读法成立。它的位置比上面几张表【深一层】：
--      order_item / product_image / product_review 挂的是 product.id，
--      而 product_sku 自己也被 order_item.sku_id 指着。
--      所以删商品时的级联是五级（晒图 → 评价 → 图集 → **SKU** → 商品），
--      SKU 必须排在商品前一步 —— 商品一没，这些 SKU 行就再也没人能按 id 找到了。
--      ⚠️ 但这会留下悬空的 order_item.sku_id（历史订单的快照还在，指的那行没了），
--         所以 increaseSkuStock 必须容忍「影响 0 行」，见那个方法的 javadoc。
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
--  12 张表一个 FOREIGN KEY 都没有，这是刻意的，不是漏了。
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
--     而明细行留下来。order_item 靠快照（product_name / sku_spec / price）自洽，
--     ProductSkuMapper.increaseSkuStock 在那行 SKU 已不存在时影响 0 行、记 warn 后继续。
--     product_image / product_review 不能这样：它们没有任何快照，
--     商品没了它们就只是垃圾行，所以 ProductServiceImpl.delete 必须先把它们删掉。
--     ★ 而且 product_review 是【两级】的：晒图挂在评价下面，
--       所以删商品是五级（晒图 → 评价 → 图集 → SKU → 商品），一层都不能反。
--       SKU 是里程碑 13 加进来的一级 —— 它排在图集之后、商品之前：
--       商品一没，这些 SKU 行就再也没人按 id 找得到了。
--       ⚠️ 这一级会留下悬空的 order_item.sku_id（历史订单的快照还在），
--          这正是上面那句「记 warn 后继续」要处理的第二种情形
--          （第一种是里程碑 8 起就有的「商品被硬删」，注释里记着真的漏过 32 行）。
--
--  这份说明被 ProductSkuMapper.increaseSkuStock 的 javadoc 引用
--  （「见 mall.sql 里那段说明」）—— 改这里的时候留意那边。
--
--  ★ 上面这段描述的是【里程碑 13 全部跑完之后】的形态（migration-13 +
--     migration-13b 都跑过）。13 和 13b 之间那段中间状态已经过去了 ——
--     在 13b 之前，product.stock 还在，真正跑的仍是
--     ProductMapper.increaseStock 那条老路径，而那条路径只在 product 上扣，
--     于是留下了一条汇总漂移（商品 683，见 product 那一节）。
--     ⚠️ 读这条注释时留意：它是【当前状态】的描述，不是历史记录；
--        历史那一段在 sql/migration-13-sku.sql 的开头。


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
--
--    ★★ 里程碑 16（migration-14）：parent_id 让分类变成一棵树。
--
--       parent_id = 0 表示「一级分类」，**刻意不用 NULL**。
--       理由和 product_sku.spec_json 是同一条：**MySQL 把多个 NULL 当成互不相等** ——
--       于是「这个分类有没有父」会在 WHERE parent_id IS NULL / = 0 两种写法之间分岔，
--       而两者都能「看起来正常工作」，直到某天两个页面给出不同的答案。
--       用 0 表示「没有父」，就是把「没有父」当成一个【确定的值】。
--
--       ⚠️ 这和 product_sku.market_price / cost_price 的 DEFAULT NULL 是
--          【故意相反】的两种选择，判据是：这个 0 会不会被当成一个真实存在的
--          数据去比较和计算。parent_id = 0 永远不会 join 到一个真实分类（id 从 1 开始）；
--          而 market_price = 0 会被拿去和售价比大小。详见 migration-14b 的头部。
--
--       ★ 深度上限是【两级】，但那条规则不在数据库里 —— 数据库拦不住三级。
--         它住在 CategoryServiceImpl（错误码 1009），由 sql/test-category.py 断言。
--         为什么必须拦住：**前端只画两级**，第三级在商城页会凭空消失而不报错。
--
--       ★ DEFAULT 0 还让本文件的种子区（下面 INSERT INTO category）一个字都不用改 ——
--         已有的 6 个分类 0 就是它们的真值。这就是里程碑 13 那条结论的复现：
--         **「加列」不影响任何现有代码，「收紧约束」才影响。**
CREATE TABLE category (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '上级分类 id；0 = 一级分类（刻意不用 NULL，见 migration-14 头部）',
    name        VARCHAR(50)     NOT NULL                COMMENT '分类名称（唯一）',
    sort        INT             NOT NULL DEFAULT 0      COMMENT '排序值，越小越靠前',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=启用 0=禁用',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_sort (sort),
    -- ★ 6 行数据上加索引换不来任何速度，这一点不装糊涂。
    --   加它是因为「按父查子」是这张表唯一的读法（组装树、算后代集合都靠它），
    --   和 idx_sort 是同一个性质的东西：表达「这张表怎么被访问」，不是今天的性能。
    --   （对照 product_sku 的 uk_product_spec —— 那个是为了【正确性】必须有的，
    --     两者性质不同，注释里不该混为一谈。）
    KEY idx_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品分类表';


-- ---------------------------------------------------------------------------
-- 3. product  商品表
--
--    ★★ 里程碑 13：这张表上【没有价格，也没有库存】。
--
--       起因是「一件商品只能有一个价格、一个库存」—— 同一件 T 恤
--       黑色 M 码 99 元、白色 L 码 109 元，在这个模型里根本无法表达。
--       migration-13 把价格和库存搬去了 product_sku，
--       migration-13b 把这张表上留下的那两列删掉了。
--       **product_sku 是它们的【唯一真源】。**
--
--       ⚠️ 一个字段只能有一个定义者：留一份「汇总库存」就立刻产生第二个写入者，
--          而库存在事务里被并发扣减（sql/test-sku.py 有 20 线程抢库存的用例），
--          汇总必然漂移。
--          ★ 这不是推理，是实测过的：阶段 5 跑全量测试时库里真的出现过
--            商品 683（卫龙辣条）product.stock=29 / product_sku.stock=30，
--            两个数不同、页面照常显示、接口全部 200。
--            详见 README 的「汇总库存一定会漂移」那一节。
--
--    ⚠️ 所以这里【不要】再给商品加 price / stock 列，也不要加任何
--       「从 SKU 汇总出来的缓存列」。要展示起售价和总库存，
--       用查询时聚合（见 ProductMapper.xml 的 skuAggregate）——
--       聚合是【算出来的】，缓存是【写出来的】，只有后者会漂移。
--
--    price 用 DECIMAL(10,2) 而不是 FLOAT/DOUBLE 这条规矩没有变，
--    它现在管的是 product_sku.price：
--    浮点数存不下 0.1 这种十进制小数，累加会出误差（0.1+0.2 != 0.3），
--    钱绝对不能用浮点存，这是硬性规矩。
-- ---------------------------------------------------------------------------
CREATE TABLE product (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_id BIGINT UNSIGNED NOT NULL                COMMENT '所属分类 id',
    name        VARCHAR(100)    NOT NULL                COMMENT '商品名称',
    cover       VARCHAR(255)    DEFAULT NULL            COMMENT '封面图 URL',
    description VARCHAR(500)    DEFAULT NULL            COMMENT '商品描述',
    spec_schema VARCHAR(500)    DEFAULT NULL            COMMENT '规格定义 JSON，形如 [{"name":"颜色","values":["黑","白"]}]，值的顺序就是前端展示顺序；[] = 无规格（该商品只有一条默认 SKU）',
    status      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1=上架 0=下架',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_name (name),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表（价格与库存【不】在这张表上，唯一真源是 product_sku）';


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
    -- ★ 里程碑 17：total_amount 的语义【变了】—— 它现在是「实付金额」
    --   （= 明细小计之和 + 运费），不再是「商品小计」。
    --   于是「商品小计」有两个算法：减法 total_amount - freight_amount、
    --   加法 SUM(order_item.subtotal)。这是同一事实的两份实现，
    --   分岔时【不会有任何一层报错】—— 只有 sql/test-after-sale.py 里
    --   那条「减法 vs 加法」断言能发现。见 migration-17b-order-freight.sql。
    total_amount     DECIMAL(10, 2)  NOT NULL                COMMENT '订单实付金额 = 明细小计之和 + 运费（下单时快照）',
    -- ★ 为什么是 NOT NULL DEFAULT 0.00 而不是可空：满额包邮下的 0 是一个
    --   【真实的 0】，它会被拿去算「实付 = 商品小计 + 运费」。
    --   判据见 migration-14b：「0 是一个值，NULL 是缺席」——
    --   对照 after_sale.refund_amount 用的是 DEFAULT NULL（那里 0 会被当成
    --   「退了 0 元」拿去求和）。两列必须分得清，别顺手改成一样。
    -- ★★ 它是【不可重算的快照】：重算会让运营改门槛的那一刻，
    --    历史订单页显示「运费 0、合计 99」这种自相矛盾的数字。
    freight_amount   DECIMAL(10, 2)  NOT NULL DEFAULT 0.00   COMMENT '本单实际收取的运费（下单时的快照，永不重算）',
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
    -- ★ 里程碑 18：物流两列。和上面 5 个生命周期列一样可空，
    --   但判据不同 —— 它们不是「这件事还没发生」，而是「这件事发生了但当时没记」：
    --   历史订单（含 5 笔真实订单）发货时项目还没有这两个字段。
    --   ★ 刻意 DEFAULT NULL 而不是 DEFAULT ''：'' 会被 `if (company)` 当成
    --     「有值」，用户端订单卡片上会显示一行空白。见 migration-18-logistics.sql。
    --   ★ 长度对齐 after_sale.return_company(50) / return_tracking(64) ——
    --     同一件事（快递公司 + 单号），只是方向相反（那边是买家寄回）。
    logistics_company VARCHAR(50)    DEFAULT NULL            COMMENT '承运商（快递公司）。下单时未知，发货时填；历史订单为 NULL',
    tracking_no      VARCHAR(64)     DEFAULT NULL            COMMENT '快递单号。同 logistics_company',
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
--
--    ★ 里程碑 13 的 SKU 化：四列排成【引用 / 快照】两组 ——
--      引用：product_id、sku_id     （指向哪件商品、哪个规格）
--      快照：product_name、sku_spec （当时它叫什么、当时什么规格）
--      一眼能看出哪个会过期、哪个永远不变。
--      ⚠️ 列序要和 migration-13-sku.sql 的 ALTER 结果完全一致，
--         不一致就是两份 schema 悄悄分叉。
--
--    ★ 为什么多了 sku_spec 这个文本快照，而不是只存 sku_id 去 join product_sku？
--      和 product_name / price 是同一条理由：**订单是快照，不是视图。**
--      SKU 行会被改价、会被删掉、商品会被改规格，而三个月后用户打开
--      「我的订单」，必须看到【他当时买的那件东西】，不是「现在那个 sku_id
--      恰好指向的东西」—— 后者可能已经不存在了。
--
--    ★ sku_id 为什么可空（而 sku_spec 不可空）：判据是【这一行刚插入时有值吗】。
--      新订单一定有 sku_id；但里程碑 13 之前下的历史订单没有，
--      而且这个项目里真实存在「商品被硬删、明细成了孤儿」的行
--      （见上面「故意不加任何外键」那段）。给它们硬填一个值就是造假。
--      NULL 是诚实的，而且它已经有代码路径：increaseSkuStock 影响 0 行 → 记 warn 后继续。
--      sku_spec 不可空，而且【没有默认值】。
--      历史行里那些空串确实是它们的真值（那时还没有规格这回事），
--      数据保持原样；但**将来的 INSERT 必须显式给出这个值**。
--
--      ★ 这里有一段值得留档的过程。里程碑 13 当时【刻意留着】 DEFAULT ''：
--        那一刻 OrderItemMapper 的插入语句里还没有这一列，
--        一个 NOT NULL 且无默认值的列被漏掉，MySQL 严格模式会报
--          ERROR 1364: Field 'sku_spec' doesn't have a default value
--        也就是【每一次下单都 500】。这是实测出来的，测试跑出来过。
--        而去掉默认值要等 Java 侧每一条写 order_item 的路径都填了它，
--        所以那一步被拆去了 migration-13b。
--
--      ★ 为什么值得拆成两步、值得为它单独写一段注释？
--        因为这两步的**方向相同、时机相反**：
--          留着默认值 → 忘了赋值的路径静默写空串 → 订单页显示
--                        「这个商品没规格」，没有任何一层报错；
--          去掉默认值 → 那种路径当场报 SQL 错。
--        「加列不影响任何现有代码，收紧约束【必然】影响」——
--        把这两件事混在一步里，就是上面那个 500 的来源。
--
--      ⚠️ 这份 mall.sql 和迁移链必须永远一致：mall.sql 里改这一列时，
--         去看一眼 migration-13b 里那条 MODIFY COLUMN 写了什么。
-- ---------------------------------------------------------------------------
CREATE TABLE order_item (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id     BIGINT UNSIGNED NOT NULL                COMMENT '所属订单 id',
    product_id   BIGINT UNSIGNED NOT NULL                COMMENT '商品 id',
    sku_id       BIGINT UNSIGNED DEFAULT NULL            COMMENT 'SKU id（取消订单时按它归还库存）。NULL = 里程碑 13 之前下的单，或商品已被硬删、查不到当时指向哪个 SKU',
    product_name VARCHAR(100)    NOT NULL                COMMENT '商品名称（下单时快照）',
    sku_spec     VARCHAR(255)    NOT NULL                COMMENT '规格文本快照，形如「颜色:黑 / 内存:128G」；空串 = 无规格（默认 SKU）',
    price        DECIMAL(10, 2)  NOT NULL                COMMENT '单价（下单时快照）',
    quantity     INT             NOT NULL                COMMENT '购买数量',
    subtotal     DECIMAL(10, 2)  NOT NULL                COMMENT '小计 = price * quantity',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_product_id (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细表';


-- ---------------------------------------------------------------------------
--  售后单（里程碑 17）
-- ---------------------------------------------------------------------------
--  ★ 一句话：一张售后单只对【一条】订单明细。
--
--  不做「主表 + 售后明细表」（一张单退 3 行）的理由是三条独立的：
--    ① 状态挂主单 → 不能逐行审批；状态挂明细 → 主单状态变成派生聚合（漂移）。
--    ② 「一条明细有没有活跃售后」会有两个定义者（明细的令牌 + 主单的状态）。
--    ③ 「运费退多少」要引入分摊规则（按金额？按件数？按重量？），
--       除不尽时【所有行的退款额之和可能比运费多一分或少一分】。
--
--  ★★ 那「一条明细不能被重复申请」靠什么守？—— 靠 uk_order_item_active。
--     单列 uk_order_item 会连「被拒绝后重新申请」一起禁掉，业务上不可接受；
--     而 MySQL 【没有部分唯一索引】（写不出 WHERE status < 3）；
--     Service 里「先查后写」挡不住并发（ProductReviewServiceImpl 有血证）。
--     解法：把「活跃」编码成一个能被唯一索引看见的值 ——
--         进行中 (status ∈ {0,1,2}) → active_token = 0
--         已关闭 (status ∈ {3,4,5}) → active_token = 本行 id
--     于是同一条明细的令牌集合 {0} ∪ {每张已关闭单的 id} 两两不等，
--     唯一索引给出的不变量是：
--        ★ 一条明细最多一张「进行中」的售后单，已关闭的历史单不限张数。
--     ★ 生成列方案实测【建不出来】：ERROR 3109，MySQL 不允许生成列
--       引用 AUTO_INCREMENT 列 —— 所以这一条只能靠断言守
--       （见 test-after-sale.py 的 I 组，两条各抓一个方向）。
--     ★ 完整论证（含每一种被否掉的编码方式）见 migration-17-after-sale.sql。
--
--  ★ 全库零外键是既定约定，所以 order_id / order_item_id / member_id 都是普通列。
-- ---------------------------------------------------------------------------
CREATE TABLE after_sale (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    after_sale_no   VARCHAR(32)     NOT NULL                COMMENT '售后单号（前缀 AS，刻意与订单号分属两个号码空间）',
    order_id        BIGINT UNSIGNED NOT NULL                COMMENT '订单 id（推「整单是否退完」靠它，人不用看）',
    order_no        VARCHAR(32)     NOT NULL                COMMENT '订单号快照（人看/人搜；售后列表为此不必 join orders）',
    order_item_id   BIGINT UNSIGNED NOT NULL                COMMENT '被申请的订单明细 id（★ uk_order_item_active 的一半）',
    member_id       BIGINT UNSIGNED NOT NULL                COMMENT '申请会员 id（安全边界，从订单推导，绝不由客户端提供）',
    type            TINYINT         NOT NULL                COMMENT '售后类型：1=仅退款 2=退货退款（见 AfterSaleType）',
    status          TINYINT         NOT NULL DEFAULT 0      COMMENT '0=待审核 1=待买家寄回 2=待卖家收货 3=退款完成 4=已拒绝 5=已撤销',
    reason          TINYINT         NOT NULL                COMMENT '申请原因码（见 AfterSaleReason；用码不用自由文本）',
    description     VARCHAR(255)    DEFAULT NULL            COMMENT '用户补充说明（只被原样显示，不参与任何判断）',
    refund_amount   DECIMAL(10, 2)  DEFAULT NULL            COMMENT '实退金额，退款成功时写入 = 货款 + 退还的运费。★ 刻意 DEFAULT NULL',
    refund_freight  DECIMAL(10, 2)  NOT NULL DEFAULT 0.00   COMMENT '其中属于运费的部分（0 是真实值不是缺席）',
    refund_method   VARCHAR(16)     DEFAULT NULL            COMMENT '退款去向 = 该订单 pay_method 的快照，退款成功时写入',
    refund_time     DATETIME        DEFAULT NULL            COMMENT '退款时间，未退款为 NULL',
    reject_reason   VARCHAR(255)    DEFAULT NULL            COMMENT '管理员拒绝的理由',
    return_company  VARCHAR(50)     DEFAULT NULL            COMMENT '买家寄回的快递公司',
    return_tracking VARCHAR(64)     DEFAULT NULL            COMMENT '买家寄回的快递单号',
    return_time     DATETIME        DEFAULT NULL            COMMENT '买家填寄回信息的时间',
    receive_time    DATETIME        DEFAULT NULL            COMMENT '管理员确认收到退货的时间',
    -- ★★ 这一列和 status 说的是同一件事（关没关）。这是本项目里【唯一一处
    --    「同一事实两个表示」】，而且是被数据库能力逼出来的，不是设计。
    --    所以它必须配断言守着（test-after-sale.py 的 I 组），别以为它无害。
    active_token    BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '★ 唯一性令牌：0=进行中；关闭时置为本行 id。见文件头部',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_after_sale_no (after_sale_no),
    UNIQUE KEY uk_order_item_active (order_item_id, active_token),
    KEY idx_member (member_id, create_time),
    KEY idx_order (order_id),
    KEY idx_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '售后单';


-- ---------------------------------------------------------------------------
--  订单物流轨迹（里程碑 18）
-- ---------------------------------------------------------------------------
--  ★★ 这张表记的是【外部世界发生过的事】，不是系统内部的状态。
--     轨迹可以独立于订单状态存在；订单状态绝不能从轨迹里【猜】出来。
--
--     反面教材：按 description 里有没有「已签收」三个字去判断订单该不该完成。
--     它一定会在有人写下「已签收失败，改约明天」的那天静默出错。
--     ★ 所以本表是一个很好的对照：【同一批字段里两个相反的决定】
--         · status 是【码】—— 前端要按它画图标和颜色，它还是
--           「录到已签收就自动完成订单」的触发条件，必须可判定 → TINYINT
--         · orders.logistics_company / tracking_no 是【自由文本】——
--           快递公司名是外部世界的标识符，无法穷举，没人按它做分支 → VARCHAR
--       判据是同一条：「谁读它、读它来做什么」。
--
--  ★★ 排序键必须是 trace_time，不是 id
--
--     因为【补录是这个功能的默认用法】，不是边缘情况：管理员白天忙，
--     晚上把一天的节点一次性补进去 —— 于是同一张订单的 trace_time 顺序
--     和 id 顺序【相反】。只按 id 排的症状：补录之后时间线倒过来
--     （今天的「已揽收」显示在昨天的「运输中」上面），
--     而页面、代码、控制台【全都正常】。
--     ⚠️ 查询里的次级键 `, id DESC` 也是必需的、不是装饰：两个节点被填了
--        【完全相同】的 trace_time 时，单靠 trace_time 排不出先后，
--        MySQL 会返回不确定的顺序（每次查询可能不一样）——
--        断言会随机红，然后被人用 DISTINCT 掩盖。
--        同 product_image.sort_no 那条「ORDER BY 要带决胜列」的规矩。
--
--  ★ 为什么不存 order_no 快照（而 after_sale 存了）
--     判据「有读者才加列」：轨迹【只在查某一单的物流时被读】，
--     那时候手里已经有 order_id 了（接口就是 /orders/{orderNo}/logistics，
--     先按单号查到订单，再按 id 查轨迹）。after_sale 存 order_no 是因为
--     管理端售后列表【按单号搜】且刻意不 join orders —— 那个读者在这里不存在。
--
--  ★ 为什么承运商/单号不在这张表上
--     那是「这一单怎么发出去的」，是【订单级】事实（单包裹）。
--     放进轨迹表意味着每条节点抄一份承运商，改一次要改 N 行。
--
--  ★ 不加外键 —— 全库零外键是既定约定。
--  ★ 不加「操作人」列 —— 管理端目前不记录任何操作人（既定取舍），
--    单独给物流加一个会开一个只在这里成立的先例。
-- ---------------------------------------------------------------------------
CREATE TABLE order_logistics (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id    BIGINT UNSIGNED NOT NULL                COMMENT '所属订单 id（故意不加外键）',
    status      TINYINT         NOT NULL                COMMENT '节点状态码：1=已揽收 2=运输中 3=派送中 4=已签收 5=异常',
    description VARCHAR(255)    NOT NULL                COMMENT '这一节点的说明（管理员手写，如「快件已到达【杭州转运中心】」）',
    trace_time  DATETIME        NOT NULL                COMMENT '★ 这一节点【发生】的时刻（管理员可填过去的时刻 = 补录）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
    PRIMARY KEY (id),
    -- ★ 列序是 (order_id, trace_time, id) —— trace_time 在 id 前面不是随意的，
    --   它就是那个「按发生时刻排，不是按录入顺序排」的决定在索引上的样子。
    KEY idx_order_trace (order_id, trace_time, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单物流轨迹（管理员手工录入，不是快递公司推送）';


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
--      顺带绕开一个陷阱：sync-mall-seed.py 的自检里数「商品表的插入语句」
--      用的是【子串匹配】，而以 product 开头的表有五张 ——
--      它们一旦有了插入语句，就会撞上它，报出「product 的 INSERT 不止一处」
--      这个和真实原因毫无关系的提示。不写种子数据，这个坑就根本不存在。
--      （⚠️ 和上面第 8 节末尾同一个警告：写注释时也不能把那串字符原样写出来。）
--
--      ★ 里程碑 13 之后这个坑【换了形态】，见下面 product_sku 那段：
--        product_sku 必须有种子数据（100 条默认 SKU），
--        所以改用「把匹配串收窄」来绕，而不是「不写种子数据」。
-- ---------------------------------------------------------------------------
CREATE TABLE product_review_image (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    review_id   BIGINT UNSIGNED NOT NULL                COMMENT '所属评价 id',
    url         VARCHAR(255)    NOT NULL                COMMENT '图片地址，形如 /uploads/2026/09/<uuid>.png',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_review_id (review_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评价晒图';


-- ---------------------------------------------------------------------------
-- 11. product_sku  商品 SKU 表（里程碑 13）
--
--    ★★ 这张表是【价格与库存的唯一真源】。product 上那两列是一段过渡，
--       migration-13b 之后就没有了。
--
--    ★ 为什么必需：到里程碑 12 为止「一件商品」= 一行 product，
--      价格和库存直接挂在那行上 —— 这意味着【一件商品只能有一个价格、
--      一个库存】。同一件 T 恤黑色 M 码 99 元、白色 L 码 109 元，
--      这件事在这个模型里根本无法表达。
--
--    ★ “每一件商品都至少有一条 SKU”，这是本轮的铁律。
--      没有规格的商品（比如卫龙辣条）也有一条 —— spec_json 是 '[]'，
--      也就是一条【默认 SKU】。**代码里只有一条路径**，
--      不需要在每个 service 里判断「这件商品有没有规格」。
--
--    ★★★ uk_product_spec 是这张表存在的理由，不是附带的优化。
--      「同一件商品不能有两个一模一样的规格组合」这件事
--      **只有数据库能真的保证** —— Service 里那句「先查有没有重复」挡不住并发：
--      两个请求可以【同时】查到「没有重复」，然后两个都往下走。
--      这和 uk_order_item（里程碑 12）、uk_member_idempotency（里程碑 8）
--      是同一个手法：**把「不可能发生」交给数据库。**
--
--      ⚠️ 但这个索引能不能拦住，完全取决于【写入端拼出来的字符串是否一致】：
--        - utf8mb4_0900_ai_ci 是 **NO PAD** 的：'黑' 和 '黑 ' 是两个不同的字符串。
--        - 维度顺序不同也是两个不同的字符串：
--          [颜色:黑, 内存:128G] ≠ [内存:128G, 颜色:黑]
--        所以「规范化」（维度按定义顺序排、去掉首尾空白）不是锦上添花，
--        它是这个唯一索引【唯一的前提】。规范化由 java 侧的 SpecJson 一个类负责。
--
--    ★ 为什么 spec_json 是 NOT NULL，而且「无规格」必须是恰好 '[]'：
--      MySQL 的唯一索引【把多个 NULL 当成互不相等】—— 允许 NULL 就等于
--      允许同一件商品插出两条「默认 SKU」，而后果是静默的：
--      起售价会取到更低的那条，前端规格选择器永远匹配不上。'' 也不行，
--      那是「有人忘了赋值」的形态，和「这件商品真的没有规格」是两件事。
--
--    ★ 为什么 idx_product_id 不单独建：uk_product_spec 的最左前缀就是 product_id，
--      再建一个只会增加写入代价。按主键查/改走的是 PRIMARY KEY。
--
--    ★ spec_json 长度：VARCHAR(500) 在 utf8mb4 下最多 2000 字节（+2 字节长度前缀），
--      而 InnoDB 的索引键前缀上限是 3072 字节（本库行格式 Dynamic），建得起来。
--      不写 TEXT —— TEXT 上建唯一索引必须指定前缀长度，而前缀索引只能保证
--      「前 N 个字符不重复」，正是这个索引最不该有的性质。
--
--    ⚠️ 这个文件里【任何一行注释】都不能把「商品表插入语句 + 左括号」
--      那串字符原样写出来：sync-mall-seed.py 的自检数的就是它，
--      而下面种子区里 product_sku 的插入语句也算一处「以 product 开头」的
--      插入语句 —— 那个自检的匹配串已经因此收窄成带左括号的形式。
--      所以这里只能这么绕着说，见 sync-mall-seed.py 里那段注释。
--
--    ★★ 里程碑 16（migration-14b）：价格从一个数变成一套。
--
--       price        售价。用户实际付的钱 —— 唯一真源，不变
--       market_price 划线价（原价/市场价）。商城页那个被划掉的数字
--       cost_price   成本价（进货价）。★ 只在管理端出现
--
--       ★ 后两列都是 DEFAULT NULL，**和 category.parent_id 的
--         NOT NULL DEFAULT 0 故意相反**。判据是 migration-14b 头部那句话：
--         「这个 0 会不会被当成一个真实存在的数据拿去比较和计算」。
--         parent_id = 0 只是一个「没有父」的标记，永不 join 到真实分类；
--         而 market_price = 0 会被拿去和售价比大小 —— 「没设划线价」
--         是一件【缺席】的事，用 0 表示会让「0 元原价」变成一个假问题。
--         （同一个字段该用 0 还是 NULL，两次答案不同，所以两次都写下来。）
--
--       ★★ 成本价是一条【安全边界】，而边界【不在这个文件里、也不在 SQL 里】。
--         SQL 该查就查（ProductSkuMapper 的三个查询都带 cost_price），
--         真正的边界在 VO 继承树上：ShopSkuVO extends SkuVO 是用户端出口，
--         costPrice 只加在 AdminSkuVO 上。
--         ⚠️ 谁把 costPrice 加到父类 SkuVO 上，/api/shop/skus/{id}
--           立刻【匿名】泄漏成本价 —— 一行改动、零编译错误、不用登录。
--           守着它的是 sql/test-price.py 的 E 组（双向：用户端不许有、管理端必须有）。
--
--       ★ 展示规则「只有 market_price > price 才画删除线」是【前端】的事，
--         后端只给数、不给 showDiscount 这种布尔位 —— 和 skuCount > 1 时
--         前端自己加一个「起」字是同一种分工。
--         ⚠️ 代价是一个静默失败：填错的划线价（比售价低或相等）会被
--           这个规则悄悄吃掉。所以保存时有一条 400 拦它（且必须按入库的
--           舍入来比大小，理由见 migration-14b 头部）。
--
--       ★★ 列表卡片上的划线价【只在单规格商品上】出现。
--          `MIN(price)` 和 `MIN(market_price)` 可以来自两个不同的 SKU 行，
--          并排显示会渲染出一个不存在的折扣（¥4999 ~~¥8999~~，而 8999
--          是另一个规格的原价）。所以 ProductMapper 里那把锁是
--          `CASE WHEN a.sku_count = 1 THEN a.only_market_price END` ——
--          和 15 轮 defaultSkuId 用的是同一把锁、同一条理由：
--          **一个回答不了的问题不应该有一个假答案。**
-- ---------------------------------------------------------------------------
CREATE TABLE product_sku (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id   BIGINT UNSIGNED NOT NULL                COMMENT '所属商品 id',
    spec_json    VARCHAR(500)    NOT NULL                COMMENT '规格组合 JSON，形如 [{"name":"颜色","value":"黑"},{"name":"内存","value":"128G"}]，按规格定义顺序排列、无多余空白（规范化由 SpecJson 保证，因为 uk_product_spec 靠它才拦得住重复）；无规格的商品只有一条 spec_json = [] 的默认 SKU',
    price        DECIMAL(10, 2)  NOT NULL                COMMENT '售价（元）—— 价格与库存的唯一真源',
    market_price DECIMAL(10, 2)  DEFAULT NULL            COMMENT '划线价（原价/市场价）。NULL = 商家没设，不是 0（见 migration-14b 头部）',
    cost_price   DECIMAL(10, 2)  DEFAULT NULL            COMMENT '成本价（进货价）。★ 只在管理端出现，绝不允许进入任何 /api/shop/** 的响应',
    stock        INT             NOT NULL DEFAULT 0      COMMENT '库存数量',
    create_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_spec (product_id, spec_json)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品 SKU 表（价格与库存的唯一真源）';


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
INSERT INTO product (category_id, name, cover, description, status) VALUES
  ((SELECT id FROM category WHERE name = '手机数码'), '小米 15 Pro 手机', '/images/phone-03.svg', '6.73 英寸 2K 全等深微曲屏，龙晶玻璃 2.0\n徕卡光学镜头，支持可变光圈与长焦微距\n第三代骁龙 8 平台，5400mAh 电池\n90W 有线 + 50W 无线快充', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'iPad Air 11 英寸', '/images/tablet-02.svg', '11 英寸 Liquid 视网膜屏，P3 广色域\nM 系列芯片，剪辑和多任务都从容\n支持 Apple Pencil 与妙控键盘\n横向前置摄像头，视频通话更自然', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '联想 ThinkPad X1 Carbon', '/images/laptop-02.svg', '14 英寸 2.8K OLED 屏，100% DCI-P3\n碳纤维机身，重量仅 1.09kg\n经典小红帽与背光键盘，键程舒适\n通过 12 项军标测试，耐用可靠', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '罗技 MX Master 3S 鼠标', '/images/mouse-01.svg', '8000DPI 传感器，几乎可在任何表面使用\nMagSpeed 电磁滚轮，一秒滚动千行\n静音按键，点击噪音降低 90%\n可同时连接三台设备并一键切换', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '戴森 V12 吸尘器', '/images/vacuum-01.svg', '激光探测功能，让微尘无处藏身\n整机过滤系统，锁住 99.99% 微尘\n续航最长 60 分钟，可替换电池\n多款吸头覆盖地板、床褥与缝隙', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '美的电饭煲 4L', '/images/rice_cooker-01.svg', '4L 容量，适合 3~5 人家庭\nIH 电磁加热，米粒受热更均匀\n12 种预设菜单，支持 24 小时预约\n内胆可拆卸，清洗方便', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '优衣库轻型羽绒服', '/images/jacket-02.svg', '轻量设计，可收纳进随身小袋\n90% 羽绒填充，保暖效率高\n防泼水表面，应付小雨小雪\n内搭外穿都合适，通勤旅行皆宜', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'iPhone duo', '/images/phone-04.svg', '双卡双待，工作生活两个号码分开\n超视网膜 XDR 显示屏，HDR 显示出色\nA 系列芯片，日常使用流畅省电\n支持无线充电与 IP68 防水', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'iPhone 18 pro 256G', '/images/phone-05.svg', '256GB 存储，照片视频随便存\nPro 级三摄系统，支持 ProRAW 与 ProRes\n钛金属中框，强度高且更轻\nProMotion 自适应刷新率，最高 120Hz', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '联想拯救者Y9000P', '/images/laptop-03.svg', '16 英寸 2.5K 电竞屏，240Hz 刷新率\n满血版独立显卡，3A 大作高帧运行\n霜刃散热系统，双风扇多热管\n支持独显直连，游戏延迟更低', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '卫龙辣条', '/images/snack_bag-03.svg', '经典麻辣味，面筋筋道有嚼劲\n独立小包装，干净卫生不脏手\n非油炸工艺，解馋无负担\n追剧办公的国民小零食', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '床单', '/images/bedding-02.svg', '100% 纯棉，亲肤透气\n高支高密织造，触感细腻\n可机洗，越洗越柔软\n适合 1.5~1.8 米床', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '华为 Mate 70 Pro', '/images/phone-01.svg', '6.8 英寸 OLED 曲面屏，1-120Hz 自适应刷新率\n麒麟芯片 + 鸿蒙系统，支持双向北斗卫星消息\n后置 5000 万可变光圈主摄，支持 4K 视频录制\n5300mAh 电池，100W 有线快充', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '荣耀 Magic7', '/images/phone-02.svg', '6.7 英寸护眼直屏，4320Hz 高频调光\n第三代骁龙 8 移动平台，性能释放稳定\n5650mAh 青海湖电池，支持 100W 快充\nAI 抓拍引擎，运动场景成片率更高', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '小米平板 7', '/images/tablet-01.svg', '11.2 英寸 3.2K 超清屏，144Hz 刷新率\n支持手写笔与磁吸键盘，办公娱乐两用\n8850mAh 大电池，连续看视频约 14 小时\n金属一体化机身，厚度 6.18mm', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '索尼 WH-1000XM5 头戴式耳机', '/images/headphones-01.svg', '业内标杆级主动降噪，8 麦克风系统\n30mm 碳纤维驱动单元，支持 LDAC 高解析音频\n智能免摘对话，开口说话自动暂停音乐\n续航 30 小时，充电 3 分钟可听 3 小时', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'Apple Watch Series 10 智能手表', '/images/watch-01.svg', '更大更薄的广视角 OLED 屏，边框进一步收窄\n支持睡眠呼吸暂停检测与心电图功能\n50 米防水，可记录游泳与浮潜数据\n快充设计，约 30 分钟充至 80%', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '大疆 Osmo Action 5 Pro 运动相机', '/images/camera-01.svg', '1/1.3 英寸传感器，低光画质明显提升\n前后双触摸屏，自拍构图方便\n裸机 20 米防水，无需额外防水壳\n超强防抖，骑行滑雪等剧烈场景也稳定', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), 'MacBook Air 13 英寸 M4', '/images/laptop-01.svg', 'M4 芯片，10 核 CPU + 8 核 GPU\n13.6 英寸 Liquid 视网膜屏，500 尼特亮度\n无风扇设计，运行全程安静\n续航最长 18 小时，重量仅 1.24kg', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '戴尔 U2723QE 27 英寸 4K 显示器', '/images/monitor-01.svg', '3840×2160 分辨率，IPS Black 面板\n98% DCI-P3 色域，出厂逐台校色\n支持 90W Type-C 反向供电，一根线连笔记本\n可升降旋转支架，自带 USB 集线器', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '罗技 K380 多设备无线键盘', '/images/keyboard-01.svg', '可同时连接 3 台设备，一键切换\n圆形静音键帽，打字手感轻快\n两节 AAA 电池可用约 2 年\n重量 423g，方便随身携带', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '惠普 LaserJet 无线激光打印机', '/images/printer-01.svg', '黑白激光打印，每分钟 22 页\n支持无线直连与手机 App 打印\n首页输出仅需 8.3 秒\n鼓粉一体设计，更换耗材简单', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '金士顿 128G 金属 U 盘', '/images/usb-01.svg', 'USB 3.2 接口，读取速度最高 200MB/s\n金属外壳，抗摔耐磨\n内置钥匙环孔，可挂在钥匙扣上\n五年质保，全国联保', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '格力 1.5 匹变频挂机空调', '/images/ac-01.svg', '新一级能效，APF 值 5.26\n56℃ 高温自清洁，出风更干净\n独立除湿模式，梅雨季很实用\n适用面积 16~20 平方米', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '海尔 465L 十字对开门冰箱', '/images/fridge-01.svg', '十字四门设计，冷藏冷冻分区明确\n风冷无霜，无需手动除冰\n一级双变频，日耗电约 0.85 度\n干湿分储，蔬果和干货各得其所', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '小天鹅 10 公斤滚筒洗衣机', '/images/washer-01.svg', '10kg 大容量，可洗四件套和窗帘\nBLDC 变频电机，静音且寿命长\n95℃ 高温筒自洁，抑菌率 99.9%\n15 分钟快洗模式，应急很方便', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '小米空气净化器 4', '/images/purifier-01.svg', '颗粒物 CADR 500m³/h，适用 60 平方米\nOLED 触控屏，实时显示 PM2.5\n三层复合滤芯，更换周期约一年\n支持 App 与语音助手控制', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '优衣库全棉圆领 T 恤', '/images/tshirt-01.svg', '100% 纯棉，克重扎实不透\n领口加固不易变形\n版型regular fit，男女同款\n多色可选，日常百搭打底', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '李宁䨻科技跑鞋', '/images/shoe-01.svg', '䨻科技中底，回弹明显且轻量\n透气网布鞋面，长时间跑不闷脚\n橡胶大底，湿地抓地力好\n适合日常慢跑与通勤', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '李维斯 511 修身牛仔裤', '/images/pants-01.svg', '511 版型，修身不紧绷\n弹力棉面料，活动自如\n经典五袋设计，水洗色自然\n四季可穿，配 T 恤衬衫都行', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '新秀丽商务双肩背包', '/images/bag-01.svg', '可放 15.6 英寸笔记本，独立隔层\n背部透气网垫，久背不闷\n防泼水面料，小雨无压力\n行李箱拉杆带，出差可直接挂上', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '波司登中长款羽绒服', '/images/jacket-01.svg', '90% 白鸭绒填充，蓬松度 600+\n中长款过膝设计，保暖范围更大\n防钻绒工艺，久穿不下绒\n可拆卸连帽，两种穿法', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '三只松鼠每日坚果 750g', '/images/pouch-01.svg', '30 小袋独立包装，一天一袋\n含核桃、巴旦木、腰果等多种坚果\n搭配蔓越莓干与蓝莓干，口感有层次\n原料当季采购，锁鲜包装', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '良品铺子猪肉脯 200g', '/images/snack_bag-01.svg', '原切后腿肉，肉纤维清晰可见\n炭火烘烤工艺，外焦里嫩\n独立小包装，开袋即食\n甜咸适口，追剧办公都合适', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '乐事薯片家庭分享装', '/images/snack_bag-02.svg', '家庭分享装，含 5 小包多种口味\n马铃薯切片均匀，酥脆不油腻\n原味、黄瓜味、烧烤味随机搭配\n密封小包装，一次一包不返潮', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '伊利金典纯牛奶 250ml×12', '/images/bottle-01.svg', '每 100ml 含 3.8g 优质乳蛋白\n120mg 原生高钙，日常补钙方便\n超高温灭菌，常温保存 6 个月\n12 盒整箱装，学生和上班族常备', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '费列罗榛果威化巧克力 24 粒', '/images/box-01.svg', '整颗榛果夹心，外层威化与巧克力\n24 粒礼盒装，送人体面\n原装进口，冷链运输\n独立金箔包装，常温存放即可', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '泰国天然乳胶枕', '/images/pillow-01.svg', '93% 天然乳胶含量，回弹支撑好\n波浪造型贴合颈椎，侧睡仰睡都合适\n蜂窝透气孔，夏季不闷热\n内外双层枕套，均可拆洗', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '水星家纺蚕丝被', '/images/quilt-01.svg', '100% 桑蚕丝填充，轻盈贴身\n蚕丝被芯可水洗，打理省心\n子母被设计，一床应对四季\n面料亲肤，敏感肌也能用', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '全棉四件套 1.8 米床', '/images/bedding-01.svg', '100% 新疆长绒棉，60 支高密\n含被套、床单、枕套两只\n活性印染，不易掉色\n适合 1.8 米床，可直接机洗', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '珊瑚绒加厚盖毯', '/images/quilt-02.svg', '双面珊瑚绒，触感柔软\n加厚设计，秋冬保暖效果好\n不掉毛不起球，机洗不变形\n午睡毯、沙发毯、旅行毯都合适', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '记忆棉床垫 1.8 米', '/images/mattress-01.svg', '记忆棉贴合身体曲线，分散压力\n独立袋装弹簧，翻身不互相干扰\n7 区支撑，护腰护颈\n可拆洗床垫套，厚度 20cm', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '三星 Galaxy S25 Ultra', '/images/phone-06.svg', '6.9 英寸动态 AMOLED 2X 屏，1-120Hz 自适应刷新\n2 亿像素主摄，支持 5 倍光学变焦\n内置 S Pen，随手记笔记很方便\n钛金属边框，支持 IP68 防水', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'OPPO Find X8 Pro', '/images/phone-07.svg', '6.78 英寸 1.5K 曲面屏，峰值亮度 4500 尼特\n双潜望长焦，人像和远景都拿手\n5910mAh 电池，80W 有线闪充\n支持无线充电与红外遥控', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'vivo X200 Pro', '/images/phone-08.svg', '蔡司 2 亿像素长焦，远摄解析力强\n6000mAh 蓝海电池，重度用一天无压力\n旗舰平台，游戏帧率稳定\n支持 IP69 防尘防水', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '红米 K80 Pro', '/images/phone-09.svg', '第二代 2K 直屏，支持全亮度 DC 调光\n骁龙旗舰平台，性能释放激进\n6000mAh 电池 + 120W 秒充\n超声波指纹，湿手也能解锁', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '一加 13', '/images/phone-10.svg', '6.82 英寸 2K 东方屏，护眼认证齐全\n哈苏影像系统，人像色彩自然\n6000mAh 冰川电池，100W 有线快充\n支持 50W 无线闪充', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '华为 MatePad Pro 13.2', '/images/tablet-03.svg', '13.2 英寸柔性 OLED 屏，屏占比 94%\n支持星闪手写笔，书写延迟低\n重量 580g，同尺寸里属于轻的一档\n可与手机、耳机多设备协同', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '小米手环 9 Pro', '/images/watch-02.svg', '1.74 英寸大屏，亮度提升到 1200 尼特\n支持全天心率、血氧与睡眠监测\n内置 GPS，跑步不用带手机\n续航最长 21 天', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '索尼 WF-1000XM5 真无线降噪耳机', '/images/headphones-02.svg', '双处理器降噪，通勤地铁里效果明显\n8.4mm 驱动单元，低频有力\n单次续航 8 小时，配充电盒共 24 小时\n支持 LDAC 高解析音频', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), 'Bose QuietComfort 45 头戴式耳机', '/images/headphones-03.svg', '经典主动降噪，四麦克风阵列\n三档降噪模式，室内室外都能用\n续航 24 小时，快充 15 分钟用 3 小时\n可折叠收纳，附带硬壳包', 1),
  ((SELECT id FROM category WHERE name = '手机数码'), '佳能 EOS R50 微单套机', '/images/camera-02.svg', '2420 万像素 APS-C 画幅传感器\n支持 4K 30P 无裁切视频录制\n双像素对焦，人物眼睛自动追踪\n机身约 375g，适合入门和旅拍', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '联想小新 Pro 16 2025', '/images/laptop-04.svg', '16 英寸 2.5K 高刷屏，100% sRGB\n标压处理器 + 独显，办公剪辑都够用\n84Wh 大电池，续航约 10 小时\n全功能 Type-C 接口，支持 PD 充电', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '华硕 ROG 魔霸新锐', '/images/laptop-05.svg', '16 英寸 2.5K 240Hz 电竞屏\n满血独显，支持独显直连\n冰川散热架构，长时间游戏不降频\nRGB 背光键盘，键程 1.7mm', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '华为 MateBook 14', '/images/laptop-06.svg', '14.2 英寸 2.8K 触控屏，3:2 显示比例\n重量 1.31kg，金属机身\n超级终端，与华为手机一碰互传\n隐藏式摄像头，保护隐私', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '宏碁 掠夺者 擎 Neo', '/images/laptop-07.svg', '16 英寸 2.5K 165Hz 高刷屏\n双风扇四热管，散热余量充足\n内存与硬盘均可自行扩展\n带独立数字小键盘，录入方便', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '明基 GW2790 27 英寸护眼显示器', '/images/monitor-02.svg', '27 英寸 IPS 屏，三面窄边框\n硬件级低蓝光，长时间办公更舒服\n支持 100Hz 刷新率，滚动更顺滑\n内置音箱，桌面更简洁', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), 'AOC 24G2 24 英寸电竞显示器', '/images/monitor-03.svg', '24 英寸 165Hz 电竞屏，1ms 响应\n支持 FreeSync 防撕裂\n可升降旋转支架，竖屏看代码方便\n双 HDMI + DP 接口', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '雷蛇 黑寡妇蜘蛛 V4 键盘', '/images/keyboard-02.svg', '机械轴体，段落感清晰\n独立多媒体控制键与旋钮\n支持 RGB 灯效自定义\n附带磁吸式手托', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '罗技 G502 Hero 游戏鼠标', '/images/mouse-02.svg', '25600 DPI HERO 传感器\n11 个可编程按键\n可调配重块，手感自己调\n支持板载内存保存配置', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '微软 Surface 精准鼠标', '/images/mouse-03.svg', '蓝影技术，玻璃桌面也能用\n三档按键力度可调\n支持同时配对三台设备\n续航约 3 个月', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '闪迪 1TB 移动固态硬盘', '/images/usb-02.svg', '读取速度最高 1050MB/s\n金属外壳，抗冲击防跌落\nType-C 与 USB-A 双接口\n附带加密软件，保护隐私', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '爱普生 L3253 墨仓式一体机', '/images/printer-02.svg', '打印、复印、扫描三合一\n墨仓式设计，单页成本低\n支持无线打印与小程序打印\n黑白彩色同速，每分钟 10 页', 1),
  ((SELECT id FROM category WHERE name = '电脑办公'), '得力 5 级保密碎纸机', '/images/box-02.svg', '5 级保密等级，碎纸尺寸 2×12mm\n单次可碎 8 张 A4 纸\n连续工作 30 分钟不卡纸\n静音设计，办公室用不吵', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '美的 1.5 匹酷省电空调', '/images/ac-02.svg', '新一级能效，省电模式下更省\n56℃ 高温自清洁，出风更干净\n独立除湿模式，梅雨季实用\n适用面积 16~20 平方米', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '松下 506L 多门冰箱', '/images/fridge-02.svg', '多门分区，冷藏冷冻独立控温\n风冷无霜，无需手动除冰\n一级能效，日耗电约 0.9 度\n纳诺怡除菌，蔬果保鲜更久', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '西门子 10 公斤洗烘一体机', '/images/washer-02.svg', '10 公斤大容量，被套一次洗完\n洗烘一体，阴雨天不用晾\n变频电机，运行安静\n高温筒自洁，减少异味', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '九阳 破壁料理机', '/images/blender-01.svg', '高转速破壁，豆浆细腻少渣\n可做米糊、果汁、辅食\n预约功能，早上起来就能喝\n杯体可拆洗，不易藏污', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '苏泊尔 IH 电饭煲 5L', '/images/rice_cooker-02.svg', '5L 容量，适合 4~6 人家庭\nIH 电磁加热，受热更均匀\n支持 24 小时预约\n内胆可拆卸，清洗方便', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '莱克 立式吸尘器', '/images/vacuum-02.svg', '立式设计，推着走不费腰\n大吸力电机，地毯深处的灰也能吸\n多档吸力，按地面材质切换\n集尘盒可水洗，不用买耗材', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '飞利浦 声波电动牙刷', '/images/toothbrush-01.svg', '声波震动清洁，牙缝刷得更干净\n三种模式，敏感牙龈也能用\n两分钟计时，分区提醒换区\n一次充电用约 14 天', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '米家 空气净化器 Ultra', '/images/purifier-02.svg', 'CADR 值高，大客厅也能带动\n高效滤芯，可过滤 PM2.5 与甲醛\n自动模式按空气质量调节风量\n支持手机 App 查看滤芯寿命', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '格兰仕 微波炉 20L', '/images/box-03.svg', '20L 容量，日常加热够用\n机械旋钮，老人也会用\n解冻、加热两档火力\n内胆易擦洗，不留油渍', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '摩飞 便携榨汁杯', '/images/blender-02.svg', '杯机一体，榨完直接喝\n充电式设计，出门也能用\n一次可榨一杯，约 300ml\n杯体可拆下水洗', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '追觅 扫地机器人', '/images/vacuum-03.svg', '激光导航，自动规划清扫路线\n扫拖一体，边扫边拖\n自动集尘，一个月不用倒垃圾\n支持 App 划区清扫与禁扫区', 1),
  ((SELECT id FROM category WHERE name = '家用电器'), '海尔 60 升电热水器', '/images/box-04.svg', '60 升容量，够两三个人连续洗\n3000W 加热，等待时间短\n防电墙技术，用电更安心\n可预约加热，避开用电高峰', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '优衣库 摇粒绒外套', '/images/jacket-03.svg', '摇粒绒面料，保暖又轻\n立领设计，脖子不进风\n两侧口袋带拉链，不怕掉东西\n可机洗，打理省事', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '耐克 Air Force 1 板鞋', '/images/shoe-02.svg', '经典低帮板鞋，百搭不挑裤型\n头层皮鞋面，耐穿易清洁\n气垫缓震，久站也舒服\n橡胶大底，防滑耐磨', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '阿迪达斯 三条纹运动裤', '/images/pants-02.svg', '经典三条纹设计，运动休闲都能穿\n针织面料带弹力，活动不受限\n收口裤脚，显腿长\n侧边口袋带拉链', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '安踏 冠军跑鞋', '/images/shoe-03.svg', '缓震中底，落地冲击小\n网布鞋面透气，夏天不闷脚\n后跟稳定片，长距离支撑好\n重量轻，适合日常训练', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '太平鸟 男士休闲夹克', '/images/jacket-04.svg', '翻领设计，通勤休闲都合适\n面料挺括，不易起皱\n内里加薄绒，春秋能穿\n两侧斜插口袋，放手机方便', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '海澜之家 纯棉长袖衬衫', '/images/tshirt-02.svg', '100% 纯棉，贴身不扎\n免烫处理，洗完挂着就平整\n标准版型，塞进裤腰不臃肿\n多色可选，适合日常通勤', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '波司登 轻薄羽绒马甲', '/images/jacket-05.svg', '轻薄羽绒马甲，室内外都好搭\n90% 绒子含量，保暖效率高\n可收纳进随身小袋\n外穿内搭都不显臃肿', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '李宁 运动双肩包', '/images/bag-02.svg', '大容量主袋，可放 15.6 英寸笔记本\n独立鞋仓，健身换鞋分开放\n透气背垫，夏天背着不闷\n侧袋可放水杯和雨伞', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '迪卡侬 20L 徒步背包', '/images/bag-03.svg', '20L 容量，一日徒步刚好\n背部通风设计，出汗少\n腰带分担重量，走久了肩膀不酸\n自带防雨罩，突然下雨也不怕', 1),
  ((SELECT id FROM category WHERE name = '服饰鞋包'), '优衣库 高腰直筒牛仔裤', '/images/pants-03.svg', '高腰直筒版型，显腿直\n弹力牛仔面料，蹲坐不勒\n水洗工艺，颜色自然不假\n四季都能穿的厚度', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '卫龙 魔芋爽 20 包', '/images/snack_bag-04.svg', '酸辣爽脆，口感弹牙\n独立小包装，一次一包不脏手\n低热量解馋，追剧好搭档\n整盒 20 包，办公室囤货合适', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '洽洽 每日坚果 30 包', '/images/pouch-02.svg', '混合坚果与果干，一天一包\n独立分装，随手带出门\n低温烘焙，不额外油炸\n整箱 30 包，一个月的量', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '旺旺 雪饼整箱', '/images/snack_bag-05.svg', '经典米饼，咸甜适口\n蓬松酥脆，一咬就化\n整箱装，家里来客人不慌\n独立小包，受潮也不怕', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '奥利奥 夹心饼干分享装', '/images/box-05.svg', '经典可可饼干配奶油夹心\n分享装分量足，聚会合适\n泡牛奶吃更香\n密封包装，开封后不易受潮', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '农夫山泉 天然水 550ml×24', '/images/bottle-02.svg', '天然水源，入口甘冽\n550ml 常规瓶型，一次喝完不浪费\n整箱 24 瓶，办公室常备\n瓶身可回收，环保包装', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '蒙牛 特仑苏纯牛奶 250ml×16', '/images/bottle-03.svg', '每 100ml 含 3.6g 优质蛋白\n250ml 利乐包，早餐一盒刚好\n整箱 16 盒，常温存放\n不添加防腐剂，开盒尽快喝完', 1),
  ((SELECT id FROM category WHERE name = '休闲零食'), '好想你 红枣夹核桃 500g', '/images/pouch-03.svg', '红枣去核夹核桃仁，一口两样\n独立小包，随身带着补能量\n选料饱满，甜度自然\n500g 袋装，办公室常备', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '富安娜 全棉四件套 1.5 米', '/images/bedding-03.svg', '100% 全棉，亲肤透气\n被套床单枕套四件齐备\n活性印染，不易掉色\n适合 1.5 米床', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '罗莱 抗菌纤维被', '/images/quilt-03.svg', '填充纤维带抗菌处理，潮季更安心\n重量适中，春秋冬都能用\n被芯可整体水洗，不用送干洗\n四角带固定带，不跑被', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '网易严选 乳胶记忆枕', '/images/pillow-02.svg', '乳胶与记忆棉复合，回弹刚好\n贴合颈部曲线，早上起来脖子不酸\n透气孔设计，夏天不闷\n枕套可拆洗，内芯不用水洗', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '恒源祥 羊毛被', '/images/quilt-04.svg', '羊毛填充，保暖且透气\n重量轻，压在身上不闷\n被面纯棉，贴身不扎\n适合冬季或空调房使用', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '水星 全棉床笠 1.8 米', '/images/bedding-04.svg', '全棉面料，柔软亲肤\n松紧包边，套上不滑动\n深度 25cm，厚床垫也能包住\n适合 1.8 米床', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '梦洁 记忆棉护颈枕', '/images/pillow-03.svg', '慢回弹记忆棉，承托颈部\n中间低两侧高的护颈造型\n枕套可拆洗，机洗不变形\n适合侧睡与仰睡', 1),
  ((SELECT id FROM category WHERE name = '床上用品'), '南极人 加厚床垫 1.5 米', '/images/mattress-02.svg', '加厚填充，软硬适中\n底面防滑颗粒，不易移位\n可直接铺在旧床垫上翻新\n适合 1.5 米床', 1);

-- 商品 SKU（每一件商品都至少一条；'[]' = 默认 SKU，也就是「这件商品没有规格」）
INSERT INTO product_sku (product_id, spec_json, price, stock) VALUES
  ((SELECT id FROM product WHERE name = '小米 15 Pro 手机'), '[]', 4999.00, 100),
  ((SELECT id FROM product WHERE name = 'iPad Air 11 英寸'), '[]', 4799.00, 50),
  ((SELECT id FROM product WHERE name = '联想 ThinkPad X1 Carbon'), '[]', 9999.00, 30),
  ((SELECT id FROM product WHERE name = '罗技 MX Master 3S 鼠标'), '[]', 699.00, 200),
  ((SELECT id FROM product WHERE name = '戴森 V12 吸尘器'), '[]', 3699.00, 40),
  ((SELECT id FROM product WHERE name = '美的电饭煲 4L'), '[]', 399.00, 150),
  ((SELECT id FROM product WHERE name = '优衣库轻型羽绒服'), '[]', 598.00, 0),
  ((SELECT id FROM product WHERE name = 'iPhone duo'), '[]', 15999.00, 100),
  ((SELECT id FROM product WHERE name = 'iPhone 18 pro 256G'), '[]', 9999.00, 1000),
  ((SELECT id FROM product WHERE name = '联想拯救者Y9000P'), '[]', 9999.00, 999),
  ((SELECT id FROM product WHERE name = '卫龙辣条'), '[]', 9.90, 100),
  ((SELECT id FROM product WHERE name = '床单'), '[]', 99.00, 100),
  ((SELECT id FROM product WHERE name = '华为 Mate 70 Pro'), '[]', 6499.00, 80),
  ((SELECT id FROM product WHERE name = '荣耀 Magic7'), '[]', 4499.00, 120),
  ((SELECT id FROM product WHERE name = '小米平板 7'), '[]', 1999.00, 90),
  ((SELECT id FROM product WHERE name = '索尼 WH-1000XM5 头戴式耳机'), '[]', 1899.00, 60),
  ((SELECT id FROM product WHERE name = 'Apple Watch Series 10 智能手表'), '[]', 3199.00, 45),
  ((SELECT id FROM product WHERE name = '大疆 Osmo Action 5 Pro 运动相机'), '[]', 2299.00, 35),
  ((SELECT id FROM product WHERE name = 'MacBook Air 13 英寸 M4'), '[]', 7999.00, 40),
  ((SELECT id FROM product WHERE name = '戴尔 U2723QE 27 英寸 4K 显示器'), '[]', 2999.00, 55),
  ((SELECT id FROM product WHERE name = '罗技 K380 多设备无线键盘'), '[]', 199.00, 300),
  ((SELECT id FROM product WHERE name = '惠普 LaserJet 无线激光打印机'), '[]', 1099.00, 25),
  ((SELECT id FROM product WHERE name = '金士顿 128G 金属 U 盘'), '[]', 89.00, 500),
  ((SELECT id FROM product WHERE name = '格力 1.5 匹变频挂机空调'), '[]', 2899.00, 30),
  ((SELECT id FROM product WHERE name = '海尔 465L 十字对开门冰箱'), '[]', 3599.00, 20),
  ((SELECT id FROM product WHERE name = '小天鹅 10 公斤滚筒洗衣机'), '[]', 2199.00, 25),
  ((SELECT id FROM product WHERE name = '小米空气净化器 4'), '[]', 899.00, 60),
  ((SELECT id FROM product WHERE name = '优衣库全棉圆领 T 恤'), '[]', 79.00, 500),
  ((SELECT id FROM product WHERE name = '李宁䨻科技跑鞋'), '[]', 399.00, 200),
  ((SELECT id FROM product WHERE name = '李维斯 511 修身牛仔裤'), '[]', 459.00, 150),
  ((SELECT id FROM product WHERE name = '新秀丽商务双肩背包'), '[]', 599.00, 80),
  ((SELECT id FROM product WHERE name = '波司登中长款羽绒服'), '[]', 1299.00, 40),
  ((SELECT id FROM product WHERE name = '三只松鼠每日坚果 750g'), '[]', 79.90, 300),
  ((SELECT id FROM product WHERE name = '良品铺子猪肉脯 200g'), '[]', 39.90, 400),
  ((SELECT id FROM product WHERE name = '乐事薯片家庭分享装'), '[]', 29.90, 500),
  ((SELECT id FROM product WHERE name = '伊利金典纯牛奶 250ml×12'), '[]', 69.90, 260),
  ((SELECT id FROM product WHERE name = '费列罗榛果威化巧克力 24 粒'), '[]', 109.00, 150),
  ((SELECT id FROM product WHERE name = '泰国天然乳胶枕'), '[]', 199.00, 180),
  ((SELECT id FROM product WHERE name = '水星家纺蚕丝被'), '[]', 899.00, 45),
  ((SELECT id FROM product WHERE name = '全棉四件套 1.8 米床'), '[]', 399.00, 120),
  ((SELECT id FROM product WHERE name = '珊瑚绒加厚盖毯'), '[]', 129.00, 200),
  ((SELECT id FROM product WHERE name = '记忆棉床垫 1.8 米'), '[]', 1499.00, 30),
  ((SELECT id FROM product WHERE name = '三星 Galaxy S25 Ultra'), '[]', 9699.00, 45),
  ((SELECT id FROM product WHERE name = 'OPPO Find X8 Pro'), '[]', 5299.00, 70),
  ((SELECT id FROM product WHERE name = 'vivo X200 Pro'), '[]', 5499.00, 65),
  ((SELECT id FROM product WHERE name = '红米 K80 Pro'), '[]', 2999.00, 150),
  ((SELECT id FROM product WHERE name = '一加 13'), '[]', 4499.00, 80),
  ((SELECT id FROM product WHERE name = '华为 MatePad Pro 13.2'), '[]', 4999.00, 40),
  ((SELECT id FROM product WHERE name = '小米手环 9 Pro'), '[]', 399.00, 300),
  ((SELECT id FROM product WHERE name = '索尼 WF-1000XM5 真无线降噪耳机'), '[]', 1699.00, 90),
  ((SELECT id FROM product WHERE name = 'Bose QuietComfort 45 头戴式耳机'), '[]', 1499.00, 55),
  ((SELECT id FROM product WHERE name = '佳能 EOS R50 微单套机'), '[]', 4799.00, 30),
  ((SELECT id FROM product WHERE name = '联想小新 Pro 16 2025'), '[]', 5299.00, 60),
  ((SELECT id FROM product WHERE name = '华硕 ROG 魔霸新锐'), '[]', 8999.00, 25),
  ((SELECT id FROM product WHERE name = '华为 MateBook 14'), '[]', 5999.00, 45),
  ((SELECT id FROM product WHERE name = '宏碁 掠夺者 擎 Neo'), '[]', 7499.00, 30),
  ((SELECT id FROM product WHERE name = '明基 GW2790 27 英寸护眼显示器'), '[]', 1099.00, 70),
  ((SELECT id FROM product WHERE name = 'AOC 24G2 24 英寸电竞显示器'), '[]', 899.00, 85),
  ((SELECT id FROM product WHERE name = '雷蛇 黑寡妇蜘蛛 V4 键盘'), '[]', 999.00, 60),
  ((SELECT id FROM product WHERE name = '罗技 G502 Hero 游戏鼠标'), '[]', 349.00, 200),
  ((SELECT id FROM product WHERE name = '微软 Surface 精准鼠标'), '[]', 599.00, 90),
  ((SELECT id FROM product WHERE name = '闪迪 1TB 移动固态硬盘'), '[]', 699.00, 130),
  ((SELECT id FROM product WHERE name = '爱普生 L3253 墨仓式一体机'), '[]', 899.00, 40),
  ((SELECT id FROM product WHERE name = '得力 5 级保密碎纸机'), '[]', 499.00, 35),
  ((SELECT id FROM product WHERE name = '美的 1.5 匹酷省电空调'), '[]', 2399.00, 35),
  ((SELECT id FROM product WHERE name = '松下 506L 多门冰箱'), '[]', 6999.00, 15),
  ((SELECT id FROM product WHERE name = '西门子 10 公斤洗烘一体机'), '[]', 4599.00, 20),
  ((SELECT id FROM product WHERE name = '九阳 破壁料理机'), '[]', 599.00, 110),
  ((SELECT id FROM product WHERE name = '苏泊尔 IH 电饭煲 5L'), '[]', 699.00, 95),
  ((SELECT id FROM product WHERE name = '莱克 立式吸尘器'), '[]', 1899.00, 45),
  ((SELECT id FROM product WHERE name = '飞利浦 声波电动牙刷'), '[]', 299.00, 220),
  ((SELECT id FROM product WHERE name = '米家 空气净化器 Ultra'), '[]', 2499.00, 40),
  ((SELECT id FROM product WHERE name = '格兰仕 微波炉 20L'), '[]', 399.00, 140),
  ((SELECT id FROM product WHERE name = '摩飞 便携榨汁杯'), '[]', 199.00, 260),
  ((SELECT id FROM product WHERE name = '追觅 扫地机器人'), '[]', 3499.00, 22),
  ((SELECT id FROM product WHERE name = '海尔 60 升电热水器'), '[]', 1299.00, 30),
  ((SELECT id FROM product WHERE name = '优衣库 摇粒绒外套'), '[]', 249.00, 300),
  ((SELECT id FROM product WHERE name = '耐克 Air Force 1 板鞋'), '[]', 799.00, 120),
  ((SELECT id FROM product WHERE name = '阿迪达斯 三条纹运动裤'), '[]', 329.00, 180),
  ((SELECT id FROM product WHERE name = '安踏 冠军跑鞋'), '[]', 459.00, 160),
  ((SELECT id FROM product WHERE name = '太平鸟 男士休闲夹克'), '[]', 599.00, 90),
  ((SELECT id FROM product WHERE name = '海澜之家 纯棉长袖衬衫'), '[]', 199.00, 220),
  ((SELECT id FROM product WHERE name = '波司登 轻薄羽绒马甲'), '[]', 499.00, 110),
  ((SELECT id FROM product WHERE name = '李宁 运动双肩包'), '[]', 269.00, 150),
  ((SELECT id FROM product WHERE name = '迪卡侬 20L 徒步背包'), '[]', 149.00, 240),
  ((SELECT id FROM product WHERE name = '优衣库 高腰直筒牛仔裤'), '[]', 299.00, 200),
  ((SELECT id FROM product WHERE name = '卫龙 魔芋爽 20 包'), '[]', 29.90, 500),
  ((SELECT id FROM product WHERE name = '洽洽 每日坚果 30 包'), '[]', 99.00, 260),
  ((SELECT id FROM product WHERE name = '旺旺 雪饼整箱'), '[]', 39.90, 400),
  ((SELECT id FROM product WHERE name = '奥利奥 夹心饼干分享装'), '[]', 25.90, 450),
  ((SELECT id FROM product WHERE name = '农夫山泉 天然水 550ml×24'), '[]', 45.00, 300),
  ((SELECT id FROM product WHERE name = '蒙牛 特仑苏纯牛奶 250ml×16'), '[]', 79.00, 280),
  ((SELECT id FROM product WHERE name = '好想你 红枣夹核桃 500g'), '[]', 59.90, 220),
  ((SELECT id FROM product WHERE name = '富安娜 全棉四件套 1.5 米'), '[]', 499.00, 100),
  ((SELECT id FROM product WHERE name = '罗莱 抗菌纤维被'), '[]', 429.00, 90),
  ((SELECT id FROM product WHERE name = '网易严选 乳胶记忆枕'), '[]', 249.00, 160),
  ((SELECT id FROM product WHERE name = '恒源祥 羊毛被'), '[]', 899.00, 60),
  ((SELECT id FROM product WHERE name = '水星 全棉床笠 1.8 米'), '[]', 139.00, 220),
  ((SELECT id FROM product WHERE name = '梦洁 记忆棉护颈枕'), '[]', 199.00, 180),
  ((SELECT id FROM product WHERE name = '南极人 加厚床垫 1.5 米'), '[]', 599.00, 70);

-- 会员
INSERT INTO member (username, password, nickname, phone, status) VALUES
('zhangsan', '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '张三', '13800138001', 1),
('lisi',     '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '李四', '13800138002', 1),
('wangwu',   '$2b$10$U96OyMu7hsN9FYGS0qFO2OGnZ0/FIAWi53480nsvrtWPl6oapc8Pa', '王五', '13800138003', 1);
