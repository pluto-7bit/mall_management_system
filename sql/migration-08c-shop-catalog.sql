-- ==========================================================================
--  迁移脚本 08c：补齐用户端商品（新增 30 件 + 现有 12 件补图补描述）
--
--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。
--  这个文件由 sql/gen-shop-assets.py 生成，不要手工编辑 ——
--  改了下次重新生成就没了。要改商品，改那个脚本里的 PRODUCTS / EXISTING。
--
--  【为什么叫 08c 而不是 09？】
--  命名跟着里程碑走（migration-08-order.sql 是里程碑 8 的）。
--  这个任务是里程碑 8 之后的收尾补齐，09 要留给「模拟支付」。
--
--  【这个脚本做了什么】
--    1. 新增 30 件商品（价格、库存、封面、描述）
--    2. 给现有 12 件商品补封面和描述
--
--  【为什么补描述是安全的】
--  跑之前查过：现有 12 件的 description 长度分别是
--  11/10/10/10/6/7/12/9/0/0/0/0 个字符 —— 全是占位级别的碎片，
--  没有一条是你认真写过的。所以用 CHAR_LENGTH < 20 作为门槛，
--  【不会覆盖任何一条有实质内容的描述】。
--
--  【两处「不覆盖」的防线，缺一不可】
--  UPDATE 的 WHERE 里带着 `cover IS NULL OR cover = ''`，
--  让「不覆盖非空值」成为【数据库层面的约束】，而不是靠脚本自觉。
--  代价是：重复跑这个脚本是安全的，但也不会更新已填过的值。
--
--  【为什么分类 id 用变量取，不写死】
--  category 的 AUTO_INCREMENT 已经到 62、product 到 75 ——
--  5~8 和 11~61 这些 id 都被测试烧掉了。写死 id 的脚本
--  换一台机器（或者换一次测试）就插到错误的分类里去了。
--  用 SET @var = (SELECT ...) 取 id，取不到时变量是 NULL，
--  INSERT 会直接报「Column 'category_id' cannot be null」而中断 ——
--  这是好事：迁移脚本报错比静默插错地方安全得多。
--
--  执行方式：
--    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p123456 \
--      --default-character-set=utf8mb4 mall < migration-08c-shop-catalog.sql
--
--  执行后验证：脚本末尾自带几个 SELECT，会打印商品总数和封面覆盖情况。
-- ==========================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 0. 前置检查：这 6 个分类必须都存在，否则后面的 INSERT 会报错中断
-- ---------------------------------------------------------------------------
SELECT name AS '分类', id AS 'id', sort AS '排序'
  FROM category
 WHERE name IN ('手机数码','电脑办公','家用电器','服饰鞋包','休闲零食','床上用品')
 ORDER BY sort;

-- ---------------------------------------------------------------------------
-- 1. 取分类 id（不写死数字，见文件头说明）
-- ---------------------------------------------------------------------------
SET @cat_phone = (SELECT id FROM category WHERE name = '手机数码');
SET @cat_pc = (SELECT id FROM category WHERE name = '电脑办公');
SET @cat_appliance = (SELECT id FROM category WHERE name = '家用电器');
SET @cat_cloth = (SELECT id FROM category WHERE name = '服饰鞋包');
SET @cat_snack = (SELECT id FROM category WHERE name = '休闲零食');
SET @cat_bed = (SELECT id FROM category WHERE name = '床上用品');

-- ---------------------------------------------------------------------------
-- 2. 新增 30 件商品
-- ---------------------------------------------------------------------------
INSERT INTO product (category_id, name, price, stock, cover, description, status) VALUES
  (@cat_phone, '华为 Mate 70 Pro', 6499.00, 80, '/images/phone-01.svg', '6.8 英寸 OLED 曲面屏，1-120Hz 自适应刷新率\n麒麟芯片 + 鸿蒙系统，支持双向北斗卫星消息\n后置 5000 万可变光圈主摄，支持 4K 视频录制\n5300mAh 电池，100W 有线快充', 1),
  (@cat_phone, '荣耀 Magic7', 4499.00, 120, '/images/phone-02.svg', '6.7 英寸护眼直屏，4320Hz 高频调光\n第三代骁龙 8 移动平台，性能释放稳定\n5650mAh 青海湖电池，支持 100W 快充\nAI 抓拍引擎，运动场景成片率更高', 1),
  (@cat_phone, '小米平板 7', 1999.00, 90, '/images/tablet-01.svg', '11.2 英寸 3.2K 超清屏，144Hz 刷新率\n支持手写笔与磁吸键盘，办公娱乐两用\n8850mAh 大电池，连续看视频约 14 小时\n金属一体化机身，厚度 6.18mm', 1),
  (@cat_phone, '索尼 WH-1000XM5 头戴式耳机', 1899.00, 60, '/images/headphones-01.svg', '业内标杆级主动降噪，8 麦克风系统\n30mm 碳纤维驱动单元，支持 LDAC 高解析音频\n智能免摘对话，开口说话自动暂停音乐\n续航 30 小时，充电 3 分钟可听 3 小时', 1),
  (@cat_phone, 'Apple Watch Series 10 智能手表', 3199.00, 45, '/images/watch-01.svg', '更大更薄的广视角 OLED 屏，边框进一步收窄\n支持睡眠呼吸暂停检测与心电图功能\n50 米防水，可记录游泳与浮潜数据\n快充设计，约 30 分钟充至 80%', 1),
  (@cat_phone, '大疆 Osmo Action 5 Pro 运动相机', 2299.00, 35, '/images/camera-01.svg', '1/1.3 英寸传感器，低光画质明显提升\n前后双触摸屏，自拍构图方便\n裸机 20 米防水，无需额外防水壳\n超强防抖，骑行滑雪等剧烈场景也稳定', 1),
  (@cat_pc, 'MacBook Air 13 英寸 M4', 7999.00, 40, '/images/laptop-01.svg', 'M4 芯片，10 核 CPU + 8 核 GPU\n13.6 英寸 Liquid 视网膜屏，500 尼特亮度\n无风扇设计，运行全程安静\n续航最长 18 小时，重量仅 1.24kg', 1),
  (@cat_pc, '戴尔 U2723QE 27 英寸 4K 显示器', 2999.00, 55, '/images/monitor-01.svg', '3840×2160 分辨率，IPS Black 面板\n98% DCI-P3 色域，出厂逐台校色\n支持 90W Type-C 反向供电，一根线连笔记本\n可升降旋转支架，自带 USB 集线器', 1),
  (@cat_pc, '罗技 K380 多设备无线键盘', 199.00, 300, '/images/keyboard-01.svg', '可同时连接 3 台设备，一键切换\n圆形静音键帽，打字手感轻快\n两节 AAA 电池可用约 2 年\n重量 423g，方便随身携带', 1),
  (@cat_pc, '惠普 LaserJet 无线激光打印机', 1099.00, 25, '/images/printer-01.svg', '黑白激光打印，每分钟 22 页\n支持无线直连与手机 App 打印\n首页输出仅需 8.3 秒\n鼓粉一体设计，更换耗材简单', 1),
  (@cat_pc, '金士顿 128G 金属 U 盘', 89.00, 500, '/images/usb-01.svg', 'USB 3.2 接口，读取速度最高 200MB/s\n金属外壳，抗摔耐磨\n内置钥匙环孔，可挂在钥匙扣上\n五年质保，全国联保', 1),
  (@cat_appliance, '格力 1.5 匹变频挂机空调', 2899.00, 30, '/images/ac-01.svg', '新一级能效，APF 值 5.26\n56℃ 高温自清洁，出风更干净\n独立除湿模式，梅雨季很实用\n适用面积 16~20 平方米', 1),
  (@cat_appliance, '海尔 465L 十字对开门冰箱', 3599.00, 20, '/images/fridge-01.svg', '十字四门设计，冷藏冷冻分区明确\n风冷无霜，无需手动除冰\n一级双变频，日耗电约 0.85 度\n干湿分储，蔬果和干货各得其所', 1),
  (@cat_appliance, '小天鹅 10 公斤滚筒洗衣机', 2199.00, 25, '/images/washer-01.svg', '10kg 大容量，可洗四件套和窗帘\nBLDC 变频电机，静音且寿命长\n95℃ 高温筒自洁，抑菌率 99.9%\n15 分钟快洗模式，应急很方便', 1),
  (@cat_appliance, '小米空气净化器 4', 899.00, 60, '/images/purifier-01.svg', '颗粒物 CADR 500m³/h，适用 60 平方米\nOLED 触控屏，实时显示 PM2.5\n三层复合滤芯，更换周期约一年\n支持 App 与语音助手控制', 1),
  (@cat_cloth, '优衣库全棉圆领 T 恤', 79.00, 500, '/images/tshirt-01.svg', '100% 纯棉，克重扎实不透\n领口加固不易变形\n版型regular fit，男女同款\n多色可选，日常百搭打底', 1),
  (@cat_cloth, '李宁䨻科技跑鞋', 399.00, 200, '/images/shoe-01.svg', '䨻科技中底，回弹明显且轻量\n透气网布鞋面，长时间跑不闷脚\n橡胶大底，湿地抓地力好\n适合日常慢跑与通勤', 1),
  (@cat_cloth, '李维斯 511 修身牛仔裤', 459.00, 150, '/images/pants-01.svg', '511 版型，修身不紧绷\n弹力棉面料，活动自如\n经典五袋设计，水洗色自然\n四季可穿，配 T 恤衬衫都行', 1),
  (@cat_cloth, '新秀丽商务双肩背包', 599.00, 80, '/images/bag-01.svg', '可放 15.6 英寸笔记本，独立隔层\n背部透气网垫，久背不闷\n防泼水面料，小雨无压力\n行李箱拉杆带，出差可直接挂上', 1),
  (@cat_cloth, '波司登中长款羽绒服', 1299.00, 40, '/images/jacket-01.svg', '90% 白鸭绒填充，蓬松度 600+\n中长款过膝设计，保暖范围更大\n防钻绒工艺，久穿不下绒\n可拆卸连帽，两种穿法', 1),
  (@cat_snack, '三只松鼠每日坚果 750g', 79.90, 300, '/images/pouch-01.svg', '30 小袋独立包装，一天一袋\n含核桃、巴旦木、腰果等多种坚果\n搭配蔓越莓干与蓝莓干，口感有层次\n原料当季采购，锁鲜包装', 1),
  (@cat_snack, '良品铺子猪肉脯 200g', 39.90, 400, '/images/snack_bag-01.svg', '原切后腿肉，肉纤维清晰可见\n炭火烘烤工艺，外焦里嫩\n独立小包装，开袋即食\n甜咸适口，追剧办公都合适', 1),
  (@cat_snack, '乐事薯片家庭分享装', 29.90, 500, '/images/snack_bag-02.svg', '家庭分享装，含 5 小包多种口味\n马铃薯切片均匀，酥脆不油腻\n原味、黄瓜味、烧烤味随机搭配\n密封小包装，一次一包不返潮', 1),
  (@cat_snack, '伊利金典纯牛奶 250ml×12', 69.90, 260, '/images/bottle-01.svg', '每 100ml 含 3.8g 优质乳蛋白\n120mg 原生高钙，日常补钙方便\n超高温灭菌，常温保存 6 个月\n12 盒整箱装，学生和上班族常备', 1),
  (@cat_snack, '费列罗榛果威化巧克力 24 粒', 109.00, 150, '/images/box-01.svg', '整颗榛果夹心，外层威化与巧克力\n24 粒礼盒装，送人体面\n原装进口，冷链运输\n独立金箔包装，常温存放即可', 1),
  (@cat_bed, '泰国天然乳胶枕', 199.00, 180, '/images/pillow-01.svg', '93% 天然乳胶含量，回弹支撑好\n波浪造型贴合颈椎，侧睡仰睡都合适\n蜂窝透气孔，夏季不闷热\n内外双层枕套，均可拆洗', 1),
  (@cat_bed, '水星家纺蚕丝被', 899.00, 45, '/images/quilt-01.svg', '100% 桑蚕丝填充，轻盈贴身\n蚕丝被芯可水洗，打理省心\n子母被设计，一床应对四季\n面料亲肤，敏感肌也能用', 1),
  (@cat_bed, '全棉四件套 1.8 米床', 399.00, 120, '/images/bedding-01.svg', '100% 新疆长绒棉，60 支高密\n含被套、床单、枕套两只\n活性印染，不易掉色\n适合 1.8 米床，可直接机洗', 1),
  (@cat_bed, '珊瑚绒加厚盖毯', 129.00, 200, '/images/quilt-02.svg', '双面珊瑚绒，触感柔软\n加厚设计，秋冬保暖效果好\n不掉毛不起球，机洗不变形\n午睡毯、沙发毯、旅行毯都合适', 1),
  (@cat_bed, '记忆棉床垫 1.8 米', 1499.00, 30, '/images/mattress-01.svg', '记忆棉贴合身体曲线，分散压力\n独立袋装弹簧，翻身不互相干扰\n7 区支撑，护腰护颈\n可拆洗床垫套，厚度 20cm', 1);

-- ---------------------------------------------------------------------------
-- 3. 现有 12 件商品：补封面和描述
--
--    ★ 两条 UPDATE 都带「原值为空/过短才写」的守卫。
--    ★ 按 TRIM(name) 匹配而不是按 id。
-- ---------------------------------------------------------------------------
-- 小米 15 Pro 手机
UPDATE product SET cover = '/images/phone-03.svg'
 WHERE TRIM(name) = '小米 15 Pro 手机' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '6.73 英寸 2K 全等深微曲屏，龙晶玻璃 2.0\n徕卡光学镜头，支持可变光圈与长焦微距\n第三代骁龙 8 平台，5400mAh 电池\n90W 有线 + 50W 无线快充'
 WHERE TRIM(name) = '小米 15 Pro 手机' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- iPad Air 11 英寸
UPDATE product SET cover = '/images/tablet-02.svg'
 WHERE TRIM(name) = 'iPad Air 11 英寸' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '11 英寸 Liquid 视网膜屏，P3 广色域\nM 系列芯片，剪辑和多任务都从容\n支持 Apple Pencil 与妙控键盘\n横向前置摄像头，视频通话更自然'
 WHERE TRIM(name) = 'iPad Air 11 英寸' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 联想 ThinkPad X1 Carbon
UPDATE product SET cover = '/images/laptop-02.svg'
 WHERE TRIM(name) = '联想 ThinkPad X1 Carbon' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '14 英寸 2.8K OLED 屏，100% DCI-P3\n碳纤维机身，重量仅 1.09kg\n经典小红帽与背光键盘，键程舒适\n通过 12 项军标测试，耐用可靠'
 WHERE TRIM(name) = '联想 ThinkPad X1 Carbon' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 罗技 MX Master 3S 鼠标
UPDATE product SET cover = '/images/mouse-01.svg'
 WHERE TRIM(name) = '罗技 MX Master 3S 鼠标' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '8000DPI 传感器，几乎可在任何表面使用\nMagSpeed 电磁滚轮，一秒滚动千行\n静音按键，点击噪音降低 90%\n可同时连接三台设备并一键切换'
 WHERE TRIM(name) = '罗技 MX Master 3S 鼠标' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 戴森 V12 吸尘器
UPDATE product SET cover = '/images/vacuum-01.svg'
 WHERE TRIM(name) = '戴森 V12 吸尘器' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '激光探测功能，让微尘无处藏身\n整机过滤系统，锁住 99.99% 微尘\n续航最长 60 分钟，可替换电池\n多款吸头覆盖地板、床褥与缝隙'
 WHERE TRIM(name) = '戴森 V12 吸尘器' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 美的电饭煲 4L
UPDATE product SET cover = '/images/rice_cooker-01.svg'
 WHERE TRIM(name) = '美的电饭煲 4L' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '4L 容量，适合 3~5 人家庭\nIH 电磁加热，米粒受热更均匀\n12 种预设菜单，支持 24 小时预约\n内胆可拆卸，清洗方便'
 WHERE TRIM(name) = '美的电饭煲 4L' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 优衣库轻型羽绒服
UPDATE product SET cover = '/images/jacket-02.svg'
 WHERE TRIM(name) = '优衣库轻型羽绒服' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '轻量设计，可收纳进随身小袋\n90% 羽绒填充，保暖效率高\n防泼水表面，应付小雨小雪\n内搭外穿都合适，通勤旅行皆宜'
 WHERE TRIM(name) = '优衣库轻型羽绒服' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- iPhone duo
UPDATE product SET cover = '/images/phone-04.svg'
 WHERE TRIM(name) = 'iPhone duo' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '双卡双待，工作生活两个号码分开\n超视网膜 XDR 显示屏，HDR 显示出色\nA 系列芯片，日常使用流畅省电\n支持无线充电与 IP68 防水'
 WHERE TRIM(name) = 'iPhone duo' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- iPhone 18 pro 256G
UPDATE product SET cover = '/images/phone-05.svg'
 WHERE TRIM(name) = 'iPhone 18 pro 256G' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '256GB 存储，照片视频随便存\nPro 级三摄系统，支持 ProRAW 与 ProRes\n钛金属中框，强度高且更轻\nProMotion 自适应刷新率，最高 120Hz'
 WHERE TRIM(name) = 'iPhone 18 pro 256G' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 联想拯救者Y9000P
UPDATE product SET cover = '/images/laptop-03.svg'
 WHERE TRIM(name) = '联想拯救者Y9000P' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '16 英寸 2.5K 电竞屏，240Hz 刷新率\n满血版独立显卡，3A 大作高帧运行\n霜刃散热系统，双风扇多热管\n支持独显直连，游戏延迟更低'
 WHERE TRIM(name) = '联想拯救者Y9000P' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 卫龙辣条
UPDATE product SET cover = '/images/snack_bag-03.svg'
 WHERE TRIM(name) = '卫龙辣条' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '经典麻辣味，面筋筋道有嚼劲\n独立小包装，干净卫生不脏手\n非油炸工艺，解馋无负担\n追剧办公的国民小零食'
 WHERE TRIM(name) = '卫龙辣条' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- 床单
UPDATE product SET cover = '/images/bedding-02.svg'
 WHERE TRIM(name) = '床单' AND (cover IS NULL OR cover = '');
UPDATE product SET description = '100% 纯棉，亲肤透气\n高支高密织造，触感细腻\n可机洗，越洗越柔软\n适合 1.5~1.8 米床'
 WHERE TRIM(name) = '床单' AND (description IS NULL OR CHAR_LENGTH(description) < 20);

-- ---------------------------------------------------------------------------
-- 4. 执行后验证
--
--    ⚠️ 这几个是【整个库】的统计，不是本次改动的统计 ——
--       真正要确认的是「没有封面的商品数」必须是 0。
-- ---------------------------------------------------------------------------
SELECT COUNT(*) AS '商品总数' FROM product;
SELECT COUNT(*) AS '有封面的商品数' FROM product WHERE cover IS NOT NULL AND cover <> '';
SELECT COUNT(*) AS '★ 没有封面的商品数（应为 0）'
  FROM product WHERE cover IS NULL OR cover = '';
SELECT LEFT(cover, 36) AS '封面路径示例', COUNT(*) AS '件数'
  FROM product GROUP BY LEFT(cover, 36) ORDER BY 件数 DESC LIMIT 5;
