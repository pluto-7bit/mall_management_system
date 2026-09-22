# 商城管理系统

一个**学习用途**的前后端分离商城系统，含**管理端**与**用户端**两侧，从零分 13 个里程碑搭起来。

它能走完一整条真实的交易闭环：

> 注册 → 浏览商品 → 加入购物车 → 填地址 → 下单 → 模拟支付 → 管理员发货 → 确认收货 → 发表评价

---

## 一、这是什么 / 学什么

这个项目的目的**不是**做一个能上线的商城，而是**把一套完整的后端分层和 Spring 生态用法亲手走一遍**。

**学的是**（这三条是搭这个项目时明确的目标）：

- **理解分层与代码设计** —— Controller / Service / Mapper 各自该管什么，一条业务规则该在哪一层定义、在哪一层强制；
- **完整跑通一个系统** —— 不是零件演示，而是一个从建库到前端页面的闭环；
- **熟悉 Spring 生态用法** —— Spring Boot、Spring MVC、MyBatis、拦截器、AOP 式横切（全局异常处理）、定时任务。

**刻意不侧重**：SQL 与表设计。表结构讲解点到为止（10 张表都建了，但不追求复杂范式），因为这不是这个项目的学习目标。

**规模**（数字都是写这份 README 时当场数出来的）：

| 项 | 数量 |
|---|---|
| 后端 Java | 114 个文件 / 15,739 行 |
| MyBatis XML 映射 | 12 个文件 / 2,082 行 |
| 管理端前端 `mall-web` | 20 个源文件 / 4,336 行 |
| 用户端前端 `mall-shop` | 32 个源文件 / 10,386 行 |
| 测试与工具脚本（Python） | 约 11,000 行 |
| 数据库表 | 10 张 |
| REST 端点 | 47 个 |
| Controller | 15 个 |

> 合计约 4.4 万行。数量本身没有意义，列出来只是让人对体量有个概念。

**⚠️ 这份 README 只是目录和索引。** 这个项目里真正值钱的东西是散落在源码里的成段中文论证 ——
一个设计为什么这么做、另一个做法为什么被否掉、踩过什么坑。见 [第十节](#十这个项目里最值钱的东西是代码注释)。

---

## 二、技术栈

| 工程 | 技术 | 端口 |
|---|---|---|
| `mall-server` | Spring Boot 3.5.16 · Java 21 · MyBatis (原生 XML) · MySQL 8 · Maven · Lombok | **8080** |
| `mall-web` | Vue 3.5 · Vite 8 · Element Plus 2.14 · Pinia 4 · vue-router 5 · axios | **5173** |
| `mall-shop` | 同上 | **5174** |
| 中间件 | MySQL 8.0（业务数据）· Redis（购物车） | 3306 / 6379 |

**几个选型理由**（都是刻意的，不是随手挑的）：

- **Spring Boot 3.5.16 而不是最新的 4.x** —— 3.x 的中文资料和排错答案远多于 4.x。
  对一个学习项目来说，**撞坑之后能搜到答案**比用上最新版更重要。
- **MyBatis 用原生 XML，刻意不用 MyBatis-Plus** —— 目的是看清 Mapper 层到底在做什么。
  MyBatis-Plus 会把 SQL 藏起来，而这恰恰是想学的部分（动态 SQL、`<foreach>`、`resultMap`、
  `useGeneratedKeys` 的边界……）。
- **购物车用 Redis 而不是 MySQL** —— 学习目的（想练缓存），同时也是合理的工程选择：
  购物车是高频读写的临时数据，用 Hash 存 `mall:cart:{memberId}`，30 天滑动过期。
- **登录态用 JWT 而不是 Session** —— 前后端分离下天然契合，服务端无状态。
  ⚠️ JWT 必须带 `type` claim（`ADMIN` / `MEMBER`）：`admin_user.id` 和 `member.id` 都可能等于 1，
  不带类型就能拿会员 token 冒充管理员。
- **`spring-security-crypto` 而不是 `spring-boot-starter-security`** —— 只需要 BCrypt 一个功能，
  引入整个 starter 会连带一整套自动配置的过滤器链，和本项目自己写的拦截器打架。

---

## 三、快速开始

这一节是全文唯一「必须照着做」的部分。

### 1. 前置环境

| 依赖 | 要求 |
|---|---|
| JDK | **21** |
| Maven | 3.9+ |
| Node.js | 18+ |
| MySQL | **8.x**，`root` / `123456` |
| Redis | 6379 |

> ⚠️ **Redis 必须是名为 `mall-redis` 的容器。**
> ```bash
> docker run -d --name mall-redis -p 6379:6379 redis
> ```
> 容器名不能随便改 —— 6 个测试脚本要用 `docker exec mall-redis redis-cli` 直连它清 key，
> 名字不对那些脚本会失败。这是一个真实且不显然的前置条件。

### 2. 建库

**全新安装**（库里什么都没有）：跑全量脚本 `sql/mall.sql`，它会建好 10 张表并塞入种子数据。

> ## ⚠️ `sql/mall.sql` 是 **DROP 重建**脚本
> 它会先 `DROP` 掉所有表再重建。**只能用于全新安装，
> 绝不要对一个已经有数据的库执行** —— 那会清空里面的一切。

**已有的库**（已经有数据，想让表结构跟上）：按顺序各跑一次增量迁移脚本：

```
sql/migration-08-order.sql
sql/migration-08b-idempotency-scope.sql
sql/migration-08c-shop-catalog.sql
sql/migration-09-payment.sql
sql/migration-10-ship.sql
sql/migration-11-product-image.sql
sql/migration-12-review.sql
```

> ### ★ 改动 schema 时，上面两份**都要改**
> - `mall.sql` 改它，是为了「新人拿到项目能一键建库」；
> - `migration-XX-*.sql` 改它，是为了「已有数据的库能跟上」。
>
> 只改一份的后果是**新人装的库和开发机的库悄悄分叉**，
> 而两份 `SHOW CREATE TABLE` 长得一模一样的时候，没有人会发现。
>
> ⚠️ 另外：DDL 里的 `COMMENT` 也是注释，但**它被存进了数据库**。
> 改 `.sql` 文件只影响以后新建的库，已经建好的库里那份仍是旧的
> （`SHOW FULL COLUMNS` 和 `mysqldump` 都会把它导出来）。**两处都要改。**

### 3. 启动三个工程

```bash
# 后端（⚠️ 工作目录必须是 mall-server/，见下）
cd mall-server && mvn spring-boot:run

# 管理端
cd mall-web && npm install && npm run dev

# 用户端
cd mall-shop && npm install && npm run dev
```

> ⚠️ **后端的工作目录必须是 `mall-server/`。**
> 配置里上传目录是相对路径 `./uploads`，工作目录不对的话图片会落到别的地方，
> 表现是上传成功但页面显示不出图。

### 4. 默认账号

| 角色 | 用户名 | 密码 |
|---|---|---|
| 管理员（管理端 :5173） | `admin` | `123456` |
| 演示会员（用户端 :5174） | `zhangsan` | `123456` |
| 演示会员 | `lisi` | `123456` |
| 演示会员 | `wangwu` | `123456` |

---

## 四、界面

管理端（`mall-web`，:5173）：

| 登录 | 商品管理 |
|---|---|
| ![管理端登录](docs/screenshots/admin-01-login.png) | ![商品管理](docs/screenshots/admin-02-products.png) |

| 订单管理 | 商品评价 |
|---|---|
| ![订单管理](docs/screenshots/admin-03-orders.png) | ![商品评价](docs/screenshots/admin-04-reviews.png) |

用户端（`mall-shop`，:5174）：

| 首页 | 商品详情（含评价） |
|---|---|
| ![首页](docs/screenshots/shop-01-home.png) | ![商品详情](docs/screenshots/shop-02-product-detail.png) |

| 购物车（空态） | 我的订单 |
|---|---|
| ![购物车](docs/screenshots/shop-03-cart.png) | ![我的订单](docs/screenshots/shop-04-orders.png) |

> ⚠️ **截图里是真实的数据** —— 商品、会员、订单都来自开发机上那个库。
> 这个仓库如果将来要公开，这些图会带着数据一起出去。
> 删掉它们只需要删除 `docs/screenshots/` 并移除本节和下表的引用，不影响任何代码。
>
> 购物车那张是**空态**：截图时用的是只读流程，没有往购物车里加任何东西。

---

## 五、项目结构

```
java/
├── mall-server/                    后端（Spring Boot）
│   └── src/main/
│       ├── java/com/example/mall/
│       │   ├── controller/
│       │   │   ├── admin/          管理端接口（6 个）
│       │   │   └── shop/           用户端接口（8 个）
│       │   ├── service/            业务规则在这层定义，且只定义一次
│       │   ├── mapper/             MyBatis 接口
│       │   ├── entity/             和表一一对应的对象
│       │   ├── dto/                接收入参
│       │   ├── vo/                 返回给前端的视图对象
│       │   ├── common/             统一响应体、全局异常处理、业务码
│       │   ├── config/             WebMvc、Jackson、Redis、定时任务配置
│       │   ├── interceptor/        两个鉴权拦截器
│       │   └── util/               JWT、用户上下文
│       └── resources/
│           ├── mapper/*.xml        SQL 都写在这（12 个文件）
│           └── application.yml
├── mall-web/                       管理端前端（Vue 3）
├── mall-shop/                      用户端前端（Vue 3）
├── sql/                            建库脚本 + 增量迁移 + 测试脚本
├── tools/                          辅助工具（截图、支付夹具）
└── docs/screenshots/               README 用的界面截图
```

**★ 为什么按 URL 前缀把 Controller 分成 `admin` / `shop` 两个包？**

因为两边**能看到的数据和权限完全不同**：管理端能看所有会员的订单，用户端只能看自己的；
管理端能改商品，用户端只能读。混在一个包里会变成一堆 `if (isAdmin)`，
而且**没法按路径一刀切加拦截器** —— 而现在 `AdminAuthInterceptor` 拦 `/api/admin/**`、
`MemberAuthInterceptor` 拦 `/api/shop/**`，各管各的，一行路径通配就够了。

其余几层的说明，见各包下的类注释。

---

## 六、权限边界

**认证方式**：JWT 放在 `Authorization: Bearer <token>` 请求头里，由两个拦截器校验。

| 路径 | 要求 |
|---|---|
| `/api/admin/**` | 管理员 token（`type = ADMIN`） |
| `/api/shop/**` | 会员 token（`type = MEMBER`） |
| `/uploads/**` | **公开，无鉴权** |
| `/api/health/ping` | 公开（不在任何拦截器的路径范围内） |

**被排除在鉴权外的匿名路径共 6 条**（原文照抄自 `WebMvcConfig`）：

```
/api/admin/auth/login          ← 管理端唯一一条

/api/shop/auth/register        ← 注册完即登录，本来就还没有 token
/api/shop/auth/login
/api/shop/products             ← 游客可浏览商品
/api/shop/products/**          ← 含商品详情、商品评价列表
/api/shop/categories           ← 游客可按分类筛选
```

> ### ⚠️ 这张清单是**人工维护**的，而路径匹配是**自动**的
> 两者一旦不同步，后果是**要么把该保护的接口公开出去，要么把公开接口锁死**，
> 而且都不会报错。**在 `/api/shop/products/**` 下新增接口时必须回来补一行。**
>
> 反过来的一条是**红线**：**上传接口必须挂在有鉴权的路径树下**。
> `POST /api/admin/images` 和 `POST /api/shop/images` 都要求登录 ——
> 挂错地方等于任何人都能往服务器上写文件。
>
> ★ 一个反直觉的例子：`ShopReviewController` 类前缀是 `/api/shop`，
> 它里面 `GET /api/shop/products/{id}/reviews` 因为**落在排除树的覆盖范围内**而匿名可读，
> 而 `POST /api/shop/reviews` 落在一条**全新的、没被排除的**路径树里所以必须登录。
> **同一个类里两个方法权限不同 —— 决定权在这个配置里，不在那个类里。**

**★ 为什么 `/uploads/**` 是公开的？**

因为 `<img src="...">` 发不出 `Authorization` 请求头。图片要么公开，要么就得把 token 塞进 URL
（那更糟）。商品图本来就该公开，所以这不是漏洞，是权衡后的选择。

---

## 七、13 个里程碑

每一轮都要求**可运行、可验证**，而不是「写完就算」。表里记的是那一轮**真正学到的东西**。

| # | 里程碑 | 这一轮的知识点 |
|---|---|---|
| 1 | 骨架 + 建库建表 | 分层骨架、统一响应体、连上数据库 |
| 2 | 商品 CRUD 全链路 | 讲得最细，作为后续所有功能的模板 |
| 3 | 分类管理 + `/api/admin` 前缀改造 | 路径前缀作为权限边界 |
| 4 | 管理端登录 + JWT + 拦截器 + 路由守卫 | 前后端如何各自守住「未登录不能进」 |
| 5 | 用户端工程 + 会员注册登录 | 第二套拦截器；JWT 的 `type` claim 为什么是必需的 |
| 6 | 商品浏览（列表 / 详情 / 筛选 / 搜索 / 排序） | 匿名可读的边界划在哪；筛选条件放进 URL |
| 7 | **购物车（Redis）** | 缓存的数据结构选择；**只存 id 和数量，价格永远现查** |
| 8 | **下单流程** | 事务、`UPDATE ... WHERE stock >= ?` 的影响行数、价格快照、幂等键的作用域 |
| 9 | **模拟支付 + 取消 + 超时自动取消** | **两条条件 UPDATE 就是并发防护本身**；顺序反过来会让库存凭空翻倍 |
| 10 | 我的订单 + 管理端订单管理 | 状态机闭环；**确认收货绝不归还库存**（和取消恰好相反） |
| 11 | 商品图片上传（多图图集） | 魔数校验而非信任文件名；**图集的三态 `null` / `[]` / `[...]`** |
| 12 | 商品评价 | **一条不变量交给数据库**（`UNIQUE KEY`）而不是 Service 里的查重 |
| 13 | 收尾：统一异常完善 + README | 协议错误与业务结果的边界；一条只在注释里的规则等于没有规则 |

★ 标出的几轮是全项目技术含量最高的部分。

**几个值得单独拎出来的设计**：

- **订单明细存价格和商品名快照**，不是每次 `JOIN` 商品表取现价 ——
  因为商品会改价、会下架、会被删，而历史订单必须永远显示当时的价格。
- **扣库存用 `UPDATE product SET stock = stock - ? WHERE id = ? AND stock >= ?` 判断影响行数**，
  不是「先查够不够再改」。后者在并发下必然超卖。
- **支付的幂等不需要幂等键**。下单的副作用是「新增一行且能无限新增」，所以要键；
  支付的副作用是「把一行从 A 推到 B」，`WHERE status = 0` 第二次天然拿到 0 行。
  **当「目的状态已经达成」可以被检测出来时，就不需要额外的幂等键。**
- **有些不变量只有数据库能真的保证**。「一条订单明细只能评一次」写在 Service 里挡不住并发
  （两个请求可以同时查到「没评过」），所以它是一个 `UNIQUE KEY`。

---

## 八、接口一览

统一约定：**业务结果返回 HTTP 200 + `body.code`；协议错误（路径、方法、报文格式不对）
返回真正的 HTTP 状态码。**

响应体形状：

```json
{ "code": 200, "message": "success", "data": { } }
```

> ⚠️ Jackson 配了 `default-property-inclusion: non_null` —— **值为 `null` 的字段会直接从 JSON 里消失**，
> 不是变成 `null`。前端读到 `undefined` 时，要先想清楚是「本来就没有」还是「后端漏给了」。

`code` 的取值：`200` 成功 · `400` 参数/协议问题 · `401` 未登录 · `403` 无权限 · `1001`~`1008` 业务错误 · `500` 服务端异常。

### 管理端 `/api/admin/**`（18 个）

| 方法 | 路径 | 登录 | 说明 |
|---|---|---|---|
| POST | `/api/admin/auth/login` | — | 管理员登录，返回 token |
| GET | `/api/admin/auth/me` | ✓ | 当前管理员信息（刷新页面用） |
| GET | `/api/admin/products` | ✓ | 商品分页列表（可见下架商品） |
| GET | `/api/admin/products/{id}` | ✓ | 商品详情（含图集） |
| POST | `/api/admin/products` | ✓ | 新增商品 |
| PUT | `/api/admin/products/{id}` | ✓ | 修改商品 |
| DELETE | `/api/admin/products/{id}` | ✓ | 删除商品 |
| POST | `/api/admin/images` | ✓ | 上传图片（`multipart`，字段名 `file`） |
| GET | `/api/admin/categories` | ✓ | 分类分页列表 |
| GET | `/api/admin/categories/options` | ✓ | 启用中的分类（下拉框用） |
| GET | `/api/admin/categories/{id}` | ✓ | 分类详情 |
| POST | `/api/admin/categories` | ✓ | 新增分类 |
| PUT | `/api/admin/categories/{id}` | ✓ | 修改分类 |
| DELETE | `/api/admin/categories/{id}` | ✓ | 删除分类（下面还挂着商品时会失败） |
| GET | `/api/admin/orders` | ✓ | 全部会员的订单（按状态/订单号/会员筛选） |
| POST | `/api/admin/orders/{orderNo}/ship` | ✓ | 发货（仅已付款可发货） |
| GET | `/api/admin/reviews` | ✓ | 全部评价（按商品/会员筛选） |
| DELETE | `/api/admin/reviews/{id}` | ✓ | 删除评价 |

### 用户端 `/api/shop/**`（28 个）

| 方法 | 路径 | 登录 | 说明 |
|---|---|---|---|
| POST | `/api/shop/auth/register` | — | 注册（注册完即登录） |
| POST | `/api/shop/auth/login` | — | 登录 |
| GET | `/api/shop/auth/me` | ✓ | 当前会员信息 |
| GET | `/api/shop/products` | — | 商品列表（搜索/分类/排序/分页） |
| GET | `/api/shop/products/{id}` | — | 商品详情 |
| GET | `/api/shop/products/{id}/reviews` | — | 某商品的评价列表 + 星级分布 |
| GET | `/api/shop/categories` | — | 启用中的分类 |
| GET | `/api/shop/cart` | ✓ | 查询购物车 |
| GET | `/api/shop/cart/count` | ✓ | 购物车件数（顶部角标） |
| POST | `/api/shop/cart/items` | ✓ | 加入购物车（数量为**增量**） |
| PUT | `/api/shop/cart/items/{productId}` | ✓ | 修改数量 |
| DELETE | `/api/shop/cart/items/{productId}` | ✓ | 移除一件 |
| DELETE | `/api/shop/cart` | ✓ | 清空购物车 |
| GET | `/api/shop/addresses` | ✓ | 我的地址列表 |
| GET | `/api/shop/addresses/default` | ✓ | 我的默认地址 |
| POST | `/api/shop/addresses` | ✓ | 新增地址 |
| PUT | `/api/shop/addresses/{id}` | ✓ | 修改地址 |
| PUT | `/api/shop/addresses/{id}/default` | ✓ | 设为默认（幂等，所以用 PUT） |
| DELETE | `/api/shop/addresses/{id}` | ✓ | 删除地址（幂等） |
| POST | `/api/shop/orders` | ✓ | 购物车结算下单（带幂等键） |
| POST | `/api/shop/orders/buy-now` | ✓ | 立即购买 |
| GET | `/api/shop/orders` | ✓ | 我的订单（只能是自己的） |
| GET | `/api/shop/orders/{orderNo}` | ✓ | 订单详情（用订单号，不是自增 id） |
| POST | `/api/shop/orders/{orderNo}/pay` | ✓ | 模拟支付 |
| POST | `/api/shop/orders/{orderNo}/cancel` | ✓ | 取消订单（归还库存） |
| POST | `/api/shop/orders/{orderNo}/complete` | ✓ | 确认收货（**不**归还库存） |
| POST | `/api/shop/images` | ✓ | 上传晒图（和 admin 侧形状一致） |
| POST | `/api/shop/reviews` | ✓ | 发表评价 |

### 其他

| 方法 | 路径 | 登录 | 说明 |
|---|---|---|---|
| GET | `/api/health/ping` | — | 健康检查（验 Tomcat → Spring MVC → 数据库整条链路） |

---

## 九、测试怎么跑

测试脚本都在 `sql/` 下，**用标准库 `urllib` 手写，不依赖 `requests`**，
每个脚本独立可跑、自带清理。跑之前三个服务必须在运行。

```bash
cd sql
/d/python/python.exe test-order.py       # 或者你本机的 python
```

- 输出 `结果：N 通过 / M 失败`，**失败时进程退出码是 1**（可以直接串进 CI）。
- 脚本之间**不共享代码** —— 公共辅助函数是复制过去的，不是 import 的。
  这样任何一个脚本都能单独跑、单独改，不会因为动了公共库而连带影响别的脚本。

**13 个脚本，约 930 个断言点**（★ 标的是那个脚本里最值钱的一条）。

| 脚本 | 测什么 |
|---|---|
| `test-auth.py` | 管理端登录与拦截器；★ **自己伪造 JWT** 来验证「会员 token 不能冒充管理员」 |
| `test-category.py` | 分类 CRUD、重名、删除时下面还挂着商品的拒绝 |
| `test-shop-product.py` | 商品浏览、搜索、筛选、排序；★ 下架商品对用户端**完全不可见**（配对照组） |
| `test-cart.py` | 购物车；★ **直接看 Redis 里到底存了什么**（不靠猜）、改价后立刻跟着变、下架商品标记失效而不是消失 |
| `test-address.py` | 地址 CRUD；★ 水平越权（三个入口）与「最多一个默认地址」不变量 |
| `test-order.py` | 下单、价格快照、**20 线程并发抢库存**、幂等键的作用域、事务回滚 |
| `test-pay.py` | 支付、**12 线程并发取消**；★ 超时是 SQL 里的业务规则，**不依赖定时任务** |
| `test-member.py` | 会员注册登录；★ **参数白名单**（注册时塞 `status` 不能生效）与跨端越权 |
| `test-order-list.py` | 订单列表分页、状态筛选、发货/确认收货；★ **确认收货绝不还库存**（直查库） |
| `test-upload.py` | 上传：魔数校验、路径穿越、大小上限；★ 图集的 `null` / `[]` / `[...]` 三态 |
| `test-review.py` | 评价资格判定、**20 线程并发评同一条明细**；★ **直插库**验证不变量由唯一索引保证 |
| `test-exception.py` | 全局异常处理器：状态码矩阵、`Allow` 头、406 的空 body 契约（**唯一不写库的脚本**） |
| `test-frontend-contract.py` | **前端的假设**（和上面 12 个问的不是同一个问题，见下） |

> ⚠️ **不写「恰好 N 项」**。脚本改过之后，这个数字立刻就旧了。
> 看真实数字就自己跑一遍 —— 那是唯一不会过期的来源。

**★ 两类脚本问的是不同的问题**：

- `test-*.py` 问的是「**后端行为对不对**」；
- `test-frontend-contract.py` 问的是「**前端的假设对不对**」——
  接口返回的字段名前端读得到吗？前端发的 body 后端收得下吗？

后者存在的理由：**前端没有类型检查**。字段名写错只会得到 `undefined`，
构建不报错、ESLint 不报错、页面也未必立刻崩 —— **这类 bug 只有契约测试能提前发现。**

> ### ★ 一个提醒：警惕「占位断言」
> 早期里程碑里写过一些「某接口 → 404（因为 Controller 还没做）」的断言。
> 它们测的其实是**当时的实现进度**，而不是业务规则 —— 里程碑推进后必然失败。
> 看到这类失败，先问「它是不是在测进度」，再决定改代码还是改断言。

---

## 十、这个项目里最值钱的东西是代码注释

这不是客套。这个项目的写法是：**每一个不显然的决定，都在它所在的那一行旁边写下为什么**，
包括被否掉的其他方案和踩过的坑。README 只是给你一张地图，教材在源码里。

如果要读，建议按这个顺序（每一个文件的类注释都是一篇独立的说明）：

| 文件 | 讲了什么 |
|---|---|
| `common/GlobalExceptionHandler.java` | 协议错误与业务结果的完整边界；一张「哪些异常今天可达 / 哪些不可达」的审计表 |
| `service/impl/OrderServiceImpl.java` | 事务、幂等、价格快照、扣库存、状态机 |
| `config/WebMvcConfig.java` | 权限边界是怎么划的，以及它为什么容易失效 |
| `service/impl/ProductReviewServiceImpl.java` | 「从另一个领域推导出的业务规则」；一条不变量怎么交给数据库 |
| `common/ResultCode.java` | 业务码的划分依据 |
| `resources/mapper/OrderMapper.xml` | 动态查询片段复用、`<foreach>` 的坑、列清单为什么要加表别名 |

> **同一个理由不要写第二遍。** 这个项目为此付出过代价：
> 一句关于图片路径的错误示例被抄了 6 遍，直到有人真的拿它去比对文件系统才发现。
> **两份副本一定会分岔** —— 所以 README 不复述代码注释，只做索引。

---

## 十一、已知的取舍 / 明确不做的事

这一节比它看起来重要：**知道一个系统不做什么，和知道它做什么一样有用。**

| 不做 | 为什么 / 代价 |
|---|---|
| **不加数据库外键** | 加外键之后「删商品」会变成一条可能失败的语句，而约束拦下来的错误只能翻译成一句「系统繁忙」。**代价**：删除顺序必须自己守（晒图 → 评价 → 图集 → 商品），反了会留下孤儿行而且不报错。测试里有专门找孤儿的断言。 |
| **删记录不删磁盘文件** | 换图、删商品都不删已上传的图片文件。**代价**：磁盘只增不减。理由是把「数据库事务」和「文件系统」绑在一起不可靠。 |
| **评价不能改、不能删** | 业务上定成「一次定终身」。管理端只能整体删除（删掉后那条明细可以重评）。 |
| **不做真实支付 / 物流** | 范围外。支付是模拟的（点一下按钮就成功），没有第三方对接。 |
| **不做秒杀 / 优惠券 / 分布式 / 微服务** | 范围外 —— 这个项目要学的是分层和框架用法，不是分布式。 |
| **不做图片压缩** | 上传只做大小上限（2MB）和格式白名单，不缩放。 |
| **不做三级省市区联动** | 地址的 `region` 就是一个普通文本框。**代价**：地址格式由用户自己保证。 |
| **不做限流 / 配额** | 没有防刷机制。 |
| **SVG 不允许上传** | 虽然它是图片，但能内嵌 `<script>`，是最经典的图片 XSS 载体。白名单只放 PNG / JPEG / GIF / WEBP。 |
| **`test-exception.py` 不搬运已有脚本的断言** | 现有的协议错误断言（散在 `test-review` / `test-upload` / `test-shop-product` 里）一个字都没动。「顺手改掉别人依赖的东西」是协作里最招人烦的一类改动。新脚本测的是**状态码矩阵**（系统性的表），旧断言是**偶然碰到的一例**，两者不是重复。 |

---

## 附：几个容易踩的坑（写给未来的自己）

- **`sql/mall.sql` 永远不能对已有数据的库跑**（见第三节）。
- **后端工作目录必须是 `mall-server/`**，否则 `./uploads` 会落到别处。
- **Redis 容器必须叫 `mall-redis`**，否则 6 个测试脚本清不了 key。
- **`/tmp` 对 Windows 版 Python 不可见**（那是 Git Bash 的路径），脚本里要用真实路径。
- **时间格式是 `yyyy-MM-dd HH:mm:ss`**，前端 `new Date("2026-09-22 20:48:06")` 是非标准写法 ——
  Chrome 能解析，**Safari 直接给 `Invalid Date`**。前端要 `replace(' ', 'T')`。
- **金额在前端要 `.toFixed(2)`**：后端的 `228.70` 序列化成 JSON 数字会变成 `228.7`，
  不格式化就会出现「同一笔钱在购物车和收银台长得不一样」。
