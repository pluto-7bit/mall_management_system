# -*- coding: utf-8 -*-
"""
里程碑 15 测试：商品多规格（SKU）

本轮最终不变量是：

    ★★ product 表上没有价格，也没有库存。★★

价格和库存的唯一真源是 product_sku。

  ★ 这句话在阶段 2~5 期间【不成立】：那两列还在，只是降级成了派生汇总
    （删列之前的回滚预案）。阶段 6 跑完 migration-13b 之后它才成立，
    A 组的 A4 / A5 就是它的两条落地断言 —— 一条看 schema，一条看源码。

  ★ 这两条断言的顺序是「先证明、再删」：A5（没有任何 SQL 还在读那两列）
    是删列的许可证，A4（列确实没了）是删列的结果。
    反过来做就是赌博 —— 删完再发现某条 SQL 在读它，是一句 500。

这组用例按组组织，每组盯着一类「SKU 化之后才存在的东西」：

  A 结构     —— 唯一索引在不在、有没有商品没 SKU、★ 那两列删干净了没有
  B 规范化   —— ★ 本轮最核心的一组：spec_json 的规范化是那条唯一索引【唯一的前提】
  C 计价与并发 —— 20 线程抢同一个 skuId、取消订单归还到【正确那一行】
  D 购物车   —— field 是 skuId、同商品两规格各占一行、改一个不影响另一个
  E 历史与孤儿 —— 新订单有 sku_id、孤儿明细取消时不炸、删商品 SKU 无残留
  F 聚合     —— 管理端：minPrice / totalStock / skuCount 是怎么算出来的
  G 商城读链路 —— 用户端：规格选择器要的那几组数据、defaultSkuId 的契约、
                 下架之后能不能绕过

★ A5 是这个目录下【唯一一条读源码的断言】。它和别的一百多条不一样：
  别的都在问「运行起来的系统做了什么」，它在问「代码里还有没有那种写法」。
  有些约定是运行时看不见的 —— 「没有任何一条 SQL 读 product.price」
  在列删掉之后就自动成立了，所以运行时永远测不出「差点没删干净」。
  这类断言只能读源码。代价是它绑在文件路径和写法上，改了结构要回来改它
  （所以它自带一条反空转守卫）。

★ 为什么 B 组值得写这么多条：
  uk_product_spec 要保证的是「同一件商品不能有两个相同的规格组合」，
  但「相同的组合」是个【语义】判断，唯一索引只懂【字符串相等】。
  两者之间那座桥就是 SpecJson.canonical()。桥断了不会有任何报错 ——
  数据库老老实实插两行「黑色 128G」，MIN(price) 静默取到更低那个，
  前端规格选择器永远匹配不上其中一行。所以这一组每条都在打这座桥。

运行：
    python test-sku.py
"""

import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080/api"

# ★ A5 要读 mapper XML 的源码，所以需要知道仓库根在哪。
#   HERE 是 sql/ 目录，往上一层就是仓库根。
#   ⚠️ 用 __file__ 推而不是写死路径：写死的话，这个脚本换个地方跑
#      （或者项目被拷到别的盘）就会「找不到目录 → 断言全绿」，
#      而它偏偏是一条静态断言 —— 静默为真比报错危险得多，所以下面
#      专门有一条「找到了 mapper 目录」的守卫断言。
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "skutest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None

# C/D/E 三组（阶段 4）要用到的会员
MEMBER_TOKEN = None
MEMBER_ID = None



# ----------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------
def call(method, path, body=None, token=None, timeout=30):
    url = BASE + path
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {"_raw": text}


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [OK]   {name}")
    else:
        FAIL += 1
        FAILED.append(f"{name}  -->  {detail}")
        print(f"  [FAIL] {name}")
        if detail:
            print(f"         {detail}")


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def note(text):
    """只打印、不断言的一行。用来上报「现在是这样、将来会变」的事实。"""
    print(f"  [--]   {text}")


# ----------------------------------------------------------------------
# 数据库直连：用来核对「接口说完之后库里到底变成了什么样」
#
# ★ 本轮的很多断言必须看库才能下 —— 「漏一行时库里一行都没变」
#   这句话是关于存储状态的，光看接口返回 400 证明不了任何事：
#   可能它先删了 4 行、再报错、事务没兜住，看起来一样是 400。
# ----------------------------------------------------------------------
def run_sql(sql):
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{sql}\n{result.stderr}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


def scalar(sql, default=None):
    rows = run_sql(sql)
    return rows[0][0] if rows else default


def redis_cmd(*args):
    """在 Docker 里执行 redis-cli，返回原始输出行。

    ★ 为什么购物车那一组（D）必须直接看 Redis，而不能只看接口返回？
      因为阶段 4 改的恰恰是【存储格式】—— field 从 productId 变成 skuId。
      接口返回的 items 里两种 id 都有可能被拼出来（后端可以「贴心地」
      帮你转换），所以只有打开 Redis 看【那个 field 到底是什么】，
      才真的证明了改动落到了存储上。

      这也是本脚本里唯一一处绕过业务层的地方。
    """
    result = subprocess.run(
        ["docker", "exec", REDIS, "redis-cli", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"redis-cli 执行失败：{args}\n{result.stderr}")
    return [l for l in result.stdout.strip().splitlines() if l]


# ----------------------------------------------------------------------
# 登录 & 数据准备
# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(scalar("SELECT id FROM category ORDER BY id LIMIT 1"))


def member_setup():
    """注册一个临时会员并登录。C/D/E 三组的购物车和订单都挂在他身上。

    用注册接口而不是某个预先存在的账号，是为了让脚本自包含 ——
    跑一百遍也不会互相干扰，而且清理时能靠用户名前缀精确定位。
    """
    global MEMBER_TOKEN, MEMBER_ID
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}{RUN}",
        "password": "sku123456",
        "nickname": "SKU测试",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    MEMBER_TOKEN = r["data"]["token"]
    MEMBER_ID = r["data"]["id"]


def cleanup():
    # ★ 里程碑 15 阶段 4 新增最前面几层：C/D/E 三组会真的下单，
    #   订单挂在会员上，明细挂在订单上。顺序仍然是「子 → 父」。
    #
    # ⚠️ 这里删的是【本脚本自己造的】会员（用户名带 PREFIX），
    #   不是 KEYS mall:cart:* 一锅端 —— Redis 上可能还有真实使用
    #   留下的购物车，测试脚本不该动它。
    #
    # ★★★ 里程碑 15 阶段 6 追查出来的一处【静默泄漏】，改法在这里：
    #
    #   原来这一段的订单/明细/地址三层都是「join member 按用户名删」：
    #
    #       DELETE o FROM orders o JOIN member m ON m.id = o.member_id
    #        WHERE m.username LIKE 'skutest%'
    #
    #   这个写法有一个致命的隐含前提：**那个会员行还在**。
    #   而 cleanup() 的最后一句恰恰就是删会员 —— 于是只要它先跑过一次，
    #   会员行就没了，这三条 join 从此永远匹配 0 行：
    #   **删掉了会员，却把它的订单、明细、地址【全留在库里】，一句错都不报。**
    #
    #   ⚠️ 更要命的是「已经被删掉的会员，它的数据按用户名再也找不回来」——
    #      用户名已经不存在了，没有任何 join 能重建这条线索。
    #      所以这不是「换个更严谨的写法」，是**用户名这条线索本身就不可靠**。
    #      可靠的线索只有 id：它在注册时就拿到了，而且不依赖会员行是否还在。
    #
    #   ★ 实测的后果（阶段 6 清理时数的）：每跑一次这个脚本，库里就多 9 笔
    #     孤儿订单 + 9 条孤儿明细 + 3 条孤儿地址，而脚本始终全绿。
    #     累积了 90 笔孤儿订单、33 条孤儿地址之后才被人发现。
    #     **一条没有断言的清理逻辑，等于没有清理逻辑。**
    #
    #   ★ 为什么两种写法都留：按 id 删覆盖「本次跑出来的」（不依赖会员行），
    #     按用户名前缀删覆盖「上一次崩在半路留下的」（那时会员行还在）。
    #     两种都是精确路径，都不碰别人的数据。
    if MEMBER_ID:
        run_sql(f"DELETE FROM order_item WHERE order_id IN "
                f"(SELECT id FROM orders WHERE member_id = {MEMBER_ID})")
        run_sql(f"DELETE FROM orders WHERE member_id = {MEMBER_ID}")
        run_sql(f"DELETE FROM member_address WHERE member_id = {MEMBER_ID}")
    run_sql("DELETE oi FROM order_item oi "
            "JOIN orders o ON o.id = oi.order_id "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE o FROM orders o "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE a FROM member_address a "
            f"JOIN member m ON m.id = a.member_id WHERE m.username LIKE '{PREFIX}%'")
    # ★ 里程碑 15：product_sku 和别的子表一样【没有外键】，
    #   所以它必须排在商品之前。顺序反了不报错，只会安静地攒孤儿行。
    run_sql(f"DELETE FROM product_sku WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")
    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")
    if MEMBER_ID:
        redis_cmd("DEL", f"mall:cart:{MEMBER_ID}")


def junk_snapshot():
    """数一遍「本脚本有没有在库里留下无主行」。

    ★ 为什么要有这个函数（它是被一次真实的泄漏换来的）：
      上面那段清理逻辑，错误版本整整跑了十几轮都没被发现 ——
      因为**没有任何一条断言在看「脚本跑完之后库里多了什么」**。
      本脚本那一百多条断言全都在看「接口做了它该做的事」，没有一条在看
      「脚本自己有没有留垃圾」。而「接口全对 + 库里留了一堆垃圾」
      是完全可能的，也正是当时的状态。

      ⚠️ 这里**故意不写「N 条断言」的具体数字**：本脚本每加一条用例这个数字就旧了，
        而一个写着旧数字的注释比不写更糟 —— 它看起来是刚核对过的。
        （这条规矩在 README 的「测试怎么跑」里也写过一次。）

    ★ 判据用的是「无主行」而不是「行数」：行数是会变的（真人也会下单），
      无主行不会 —— 一个正常的库，孤儿永远是 0。
      ⚠️ 注意这里的孤儿是【结构性的】（明细找不到订单），
      和 E 组故意造的「sku_id = NULL 的历史明细」不是一回事：
      那一条明细的 order_id 是好的，它只是不知道当初买的是哪个规格。
    """
    return (
        int(scalar("SELECT COUNT(*) FROM orders o "
                   "LEFT JOIN member m ON m.id = o.member_id WHERE m.id IS NULL")),
        int(scalar("SELECT COUNT(*) FROM order_item i "
                   "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")),
        int(scalar("SELECT COUNT(*) FROM member_address a "
                   "LEFT JOIN member m ON m.id = a.member_id WHERE m.id IS NULL")),
        int(scalar("SELECT COUNT(*) FROM product_sku s "
                   "LEFT JOIN product p ON p.id = s.product_id WHERE p.id IS NULL")),
        int(scalar(f"SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'")),
    )


def create(name, spec_schema, skus, status=1):
    """建一个商品，返回 (http_status, 响应体, 商品id或None)。

    ★ 这里【故意】不抛异常 —— B 组有一半用例要的就是「这个请求该被拒绝」，
      失败路径的响应体正是被测对象。抛异常的话那些用例没法写。
    """
    st, r = call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name, "status": status,
        "specSchema": spec_schema, "skus": skus,
    }, token=ADMIN_TOKEN)
    pid = (r.get("data") if isinstance(r, dict) and r.get("code") == 200 else None)
    return st, r, pid


def update(pid, name, spec_schema, skus, status=1):
    return call("PUT", f"/admin/products/{pid}", {
        "categoryId": CATEGORY_ID, "name": name, "status": status,
        "specSchema": spec_schema, "skus": skus,
    }, token=ADMIN_TOKEN)


def detail(pid):
    st, r = call("GET", f"/admin/products/{pid}", token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"查商品详情失败：HTTP {st} / {r}")
    return r["data"]


def sku_rows(pid):
    """直接看库里的 SKU 行：(id, spec_json, price, stock)，按 id 排。"""
    return run_sql(f"SELECT id, spec_json, price, stock FROM product_sku "
                   f"WHERE product_id = {pid} ORDER BY id")


def sku_ids(pid):
    return sorted(int(r[0]) for r in sku_rows(pid))


# 注释剥除：XML 注释 <!-- ... --> 和 SQL 行注释 -- ...
# ⚠️ 顺序不能反：先剥 XML 注释。反过来的话，XML 注释【里面】那些
#    以 -- 开头的说明文字会先被当 SQL 注释处理，
#    而 '-->' 里的那个 '-' 会让后面的正文切错位置。
_XML_COMMENT = re.compile(r"<!--.*?-->", re.S)
_SQL_COMMENT = re.compile(r"--[^\n]*")


def strip_comments(text):
    """把 XML 注释和 SQL 行注释去掉，只留【会被数据库执行的那部分】。

    ★ 为什么必须剥：A5 要找的是「还有没有 SQL 在读 product.price」。
      注释里提到它不算数 —— 注释是人读的说明，数据库看不见它。
      ProductMapper.xml 里有几段【故意留着】的注释在讲 p.price
      （「这里的 p.price 换成了 a.min_price」那一段），
      它们是这一轮的重要文档，不该为了讨好一条断言而删掉。
      两条都要保住，那就只能让断言学会分辨。
    """
    return _SQL_COMMENT.sub("", _XML_COMMENT.sub("", text))


# ======================================================================
# 颜色 × 尺码 —— B 组反复用到的那件 2 维 2 值商品
# ======================================================================
COLORS = ["黑", "白"]
SIZES = ["S", "M"]
SCHEMA_CS = [{"name": "颜色", "values": COLORS}, {"name": "尺码", "values": SIZES}]


def combos_cs(order=(0, 1)):
    """叉乘出 4 个规格组合。〔order〕可以颠倒每一维内部的顺序。

    ★ 这个参数就是 B 组第二、三条用例的全部内容：
      同一组规格，换个提交顺序，必须落到同 4 行 SKU 上。
    """
    dims = [("颜色", COLORS), ("尺码", SIZES)]
    if order == (1, 0):
        dims = [("尺码", SIZES), ("颜色", COLORS)]
    out = []
    for v1 in dims[0][1]:
        for v2 in dims[1][1]:
            out.append([{"name": dims[0][0], "value": v1},
                        {"name": dims[1][0], "value": v2}])
    return out


def skus_of(spec_list, price=10.00, stock=5):
    return [{"specs": s, "price": price, "stock": stock} for s in spec_list]


def price_rows(pid):
    """给每个 SKU 一个【和规格绑死】的价格，方便事后认人。

    黑/S = 10.00、白/S = 20.00、黑/M = 30.00、白/M = 40.00
    —— 用价格当身份证，比看 spec_json 直观。
    """
    idx = {"黑|S": 10.00, "白|S": 20.00, "黑|M": 30.00, "白|M": 40.00}
    out = []
    for specs in combos_cs():
        key = "|".join(i["value"] for i in specs)
        out.append({"specs": specs, "price": idx[key], "stock": 5})
    return out


# ======================================================================
# A · 结构
# ======================================================================
def group_a():
    section("A · 结构：唯一索引、SKU 覆盖、列已删（本轮最终不变量）")

    # ---- A1 唯一索引 ----
    rows = run_sql(
        "SELECT NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
        "FROM information_schema.STATISTICS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku' "
        "  AND INDEX_NAME = 'uk_product_spec' GROUP BY NON_UNIQUE")
    check("★ uk_product_spec 存在且是【唯一】索引",
          len(rows) == 1 and rows[0][0] == "0",
          f"information_schema 查到：{rows}")
    check("★ 唯一索引的列顺序是 (product_id, spec_json)",
          len(rows) == 1 and rows[0][1] == "product_id,spec_json",
          f"实际列顺序：{rows[0][1] if rows else '查不到'}")

    # ---- A2 每件商品都有 SKU ----
    no_sku = int(scalar(
        "SELECT COUNT(*) FROM product p WHERE NOT EXISTS "
        "(SELECT 1 FROM product_sku s WHERE s.product_id = p.id)") or 0)
    total = int(scalar("SELECT COUNT(*) FROM product") or 0)
    check(f"★ 库里 {total} 件商品，没有 SKU 的 = 0 件", no_sku == 0,
          f"有 {no_sku} 件商品一条 SKU 都没有 —— 它们没有价格，卖不出去")
    check("★ 商品总数是 100 件（用户拍板「全部商品都配 SKU」）", total == 100,
          f"实际 {total} 件")

    # ---- A3 spec_json 的形态 ----
    bad_json = int(scalar(
        "SELECT COUNT(*) FROM product_sku "
        "WHERE spec_json IS NULL OR spec_json = ''") or 0)
    check("★ 没有 spec_json 为 NULL 或空串的 SKU 行（无规格必须是 '[]'）",
          bad_json == 0,
          f"有 {bad_json} 行 —— NULL 在唯一索引里互不相等，"
          f"等于允许同一件商品插出两条「默认 SKU」")

    # ---- A4 ★★★ 本轮最终不变量：product 上已经没有那两列 ----
    #
    # ★★ 这一条就是整轮的验收句：「product 表上没有价格，也没有库存。」
    #
    #   ★ 它在阶段 2~5 期间【故意没写】，因为那时那两列还在（回滚预案），
    #     写下来就是一条假的断言。当时的替代品是一条弱得多的命题：
    #     「派生汇总的漂移只出现在有旧路径订单的商品上」——
    #     它承认了漂移存在、只保证漂移的来源可解释。
    #
    #   ★ 从「漂移可解释」换成「列不存在」，是这一轮最重要的那个转向：
    #     前者要求**维护一套解释漂移的规则**（哪条路径会漂、为什么），
    #     后者只要看一眼 schema。**能用结构表达的约束，永远比能用
    #     规则表达的约束可靠** —— 因为规则会被遗忘，结构不会被遗忘。
    #
    #   ⚠️ 这条同时是「防着有人偷偷加回来」：`product.stock` 加回来
    #      不会让任何东西报错（新的写入者照常工作，只是又开始了漂移），
    #      而这条断言会红。
    cols = run_sql(
        "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product' "
        "  AND COLUMN_NAME IN ('price', 'stock') ORDER BY COLUMN_NAME")
    check("★★★ product 表上没有 price / stock 两列（本轮最终不变量）",
          len(cols) == 0,
          f"这两列还在：{[c[0] for c in cols]} —— 一个字段只能有一个定义者；"
          f"留一份汇总库存就等于留了第二个写入者，"
          f"而库存在事务里被并发扣减，汇总必然漂移")

    # ★ 顺带核对表的注释也说对了（注释不参与逻辑判断，所以说假话没人报错）
    comment = scalar(
        "SELECT TABLE_COMMENT FROM information_schema.TABLES "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product'")
    check("★ product 的表注释里写明了真源是 product_sku",
          comment is not None and "product_sku" in comment,
          f"实际注释：{comment!r} —— 只删列不改注释，库里就留着一句假话")

    # ---- A5 ★★ 静态断言：没有任何一条 SQL 还在读那两列 ----
    #
    # ★★ 这一条是 migration-13b 敢删列的【许可证】，也是 A4 的许可证：
    #    A4 断言「列不存在」，A5 断言「没有任何 SQL 依赖它」。
    #    两条一起才说明「删掉是安全的」，只删不查就是赌博。
    #
    #    它现在的顺序价值大于它的内容价值：**先证明、再删**。
    #    删完再发现某条 SQL 在读它，是一句 Unknown column 的 500；
    #    删之前发现，是一次改代码。
    #
    # ⚠️ 为什么要把注释剥掉再找：ProductMapper.xml 里有几段【故意留着】的
    #    注释在讲 p.price（「这里的 p.price 换成了 a.min_price」
    #    「只写 ORDER BY p.price 会怎样」）。它们是人读的说明文字，
    #    不是 SQL —— 把注释算进来的话，这条断言就永远红。
    #    ★ 判据是「这段文字会不会被数据库执行」：
    #      会执行才算依赖，不会执行就只是文档。
    #      所以剥掉注释不是为了让断言变绿，是因为它本来就不该算。
    mapper_dir = os.path.join(ROOT, "mall-server", "src", "main", "resources", "mapper")
    offenders = []
    sql_text_total = ""
    files = []
    if os.path.isdir(mapper_dir):
        files = sorted(f for f in os.listdir(mapper_dir) if f.endswith(".xml"))
        for fn in files:
            with open(os.path.join(mapper_dir, fn), encoding="utf-8") as fp:
                body = strip_comments(fp.read())
            sql_text_total += body
            for pat in ("p.price", "p.stock", "product.price", "product.stock"):
                if pat in body:
                    offenders.append(f"{fn}: {pat}")
    check("★ 找到了 mapper 目录（这条断言本身不是空的）",
          len(files) > 0, f"在 {mapper_dir} 下一个 .xml 都没找到 —— 路径变了")
    # ★ 反空转守卫：证明剥注释没有把整个文件剥空。
    #   一个「找不到东西」的断言，最危险的失败方式是它【什么都没检查】。
    check("★ 剥掉注释之后 SQL 正文还在（反空转）",
          "FROM product" in sql_text_total and "FROM product_sku" in sql_text_total,
          f"剥注释后连 FROM product / FROM product_sku 都找不到 —— "
          f"说明剥过了头，A5 变成了一条永远为真的断言")
    check("★★ mapper XML（不含注释）里对 product.price / product.stock 零命中",
          not offenders,
          f"还有 {len(offenders)} 处在读它们：{offenders} —— "
          f"这些会在列删掉之后变成 Unknown column 的 500")

    # ---- A6 上报：spec_schema 的形态 ----
    #
    # ★ 阶段 6 之前这里是「上报 NULL 有多少件」—— 因为那 100 行确实是 NULL。
    #   现在它是一条断言：归一化之后不该再有 NULL。
    null_schema = int(scalar(
        "SELECT COUNT(*) FROM product WHERE spec_schema IS NULL") or 0)
    check("★ spec_schema 里没有 NULL（13b 已把 NULL 归一成 '[]'）",
          null_schema == 0,
          f"还有 {null_schema} 件是 NULL —— "
          f"『无规格』有两种表示的话，"
          f"将来任何一条 WHERE spec_schema = '[]' 都会静默少返回行")
    check("★ spec_schema 全是能解析的 JSON 数组（不是空串、不是别的）",
          int(scalar("SELECT COUNT(*) FROM product "
                     "WHERE spec_schema IS NULL OR LEFT(spec_schema, 1) <> '['") or 0) == 0,
          "有空串或非数组形态 —— 那会让解析路径分岔成第二条")


# ======================================================================
# B · 规范化与唯一性
# ======================================================================
def group_b():
    section("B · 规范化与唯一性：能不能落成「恰好 4 行」")

    name = f"{TAG}-2维2值"
    # 建的时候统一价，随后再 PUT 成「按规格绑死的价格」——
    # 那一次 PUT 同时验证了「更新走的是同一条校验链」
    st, r, pid = create(name, SCHEMA_CS, skus_of(combos_cs()))
    check("B1 建一个 2 维 2 值商品 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if not pid:
        return None

    st, r = update(pid, name, SCHEMA_CS, price_rows(pid))
    check("B2 改成每个规格不同价 → 200", r.get("code") == 200, f"HTTP {st} / {r}")

    rows = sku_rows(pid)
    check("★ B3 库里恰好 4 行 SKU（各维取值数之积）", len(rows) == 4,
          f"实际 {len(rows)} 行：{rows}")

    d = detail(pid)
    check("★ B4 详情接口读回 4 行 SKU", len(d.get("skus") or []) == 4,
          f"实际 {len(d.get('skus') or [])} 行")
    check("B5 详情里的 specSchema 和提交的一模一样（含顺序）",
          d.get("specSchema") == SCHEMA_CS,
          f"返回 {d.get('specSchema')}，提交 {SCHEMA_CS}")
    check("★ B6 每一行都有 specText，人能读（形如「颜色:黑 / 尺码:S」）",
          all(s.get("specText") for s in d.get("skus") or []),
          f"specText 列表：{[s.get('specText') for s in d.get('skus') or []]}")
    check("B7 价格按规格对得上（黑/S=10、白/S=20、黑/M=30、白/M=40）",
          sorted((s["specText"], float(s["price"])) for s in d["skus"])
          == sorted([("颜色:黑 / 尺码:S", 10.0), ("颜色:白 / 尺码:S", 20.0),
                     ("颜色:黑 / 尺码:M", 30.0), ("颜色:白 / 尺码:M", 40.0)]),
          f"实际：{sorted((s['specText'], s['price']) for s in d['skus'])}")

    ids_before = sku_ids(pid)
    prices_before = {r_[1]: r_[2] for r_ in rows}

    # ------------------------------------------------------------------
    # ★★ B8 —— 这一组里最重要的一条
    #
    # 把【每一维的顺序】和【每一维内部取值的顺序】全颠倒过来重新提交。
    # 语义上这是同一套规格，必须落回同 4 行、同 4 个 id。
    #
    # 如果 SpecJson.canonical() 没在起作用，这里会变成：
    #   旧的 4 行认领不到 → 被删掉 → 插进 4 行【新 id】
    #   而 order_item.sku_id 正指着旧的那些 id
    # 症状：历史订单取消时库存还给 0 行，库存就这么丢了，接口返回 200。
    # ------------------------------------------------------------------
    reversed_schema = [{"name": "尺码", "values": list(reversed(SIZES))},
                       {"name": "颜色", "values": list(reversed(COLORS))}]
    # 组合的先后顺序颠倒、每一项的 specs 顺序也颠倒、价格全换成 1.00
    reversed_skus = [{"specs": list(reversed(specs)), "price": 1.00, "stock": 1}
                     for specs in reversed(combos_cs())]

    st, r = update(pid, name, reversed_schema, reversed_skus)
    check("B8a 顺序全颠倒地再提交一次 → 200", r.get("code") == 200, f"HTTP {st} / {r}")

    rows_after = sku_rows(pid)
    check("★★ B8b 仍然是 4 行（规范化让两种顺序落成同一套）",
          len(rows_after) == 4, f"实际 {len(rows_after)} 行：{rows_after}")
    check("★★ B8c 4 个 SKU 的 id 【一个都没变】—— 老行被认领了，不是删了重建",
          sku_ids(pid) == ids_before,
          f"提交前 {ids_before}，提交后 {sku_ids(pid)}；"
          f"id 变了意味着 order_item.sku_id 会指向不存在的行")
    check("★ B8d spec_json 存的是【规范化】形态（按规格名排序：尺码在前）",
          all(json.loads(r_[1])[0]["name"] == "尺码" for r_ in rows_after),
          f"实际：{[r_[1] for r_ in rows_after]}")
    check("B8e 价格确实被改了（证明真的走了 update，不是整个请求被忽略）",
          all(r_[2] == "1.00" for r_ in rows_after),
          f"实际价格：{[r_[2] for r_ in rows_after]}")

    # 还原价格，后面的 F 组要用
    st, r = update(pid, name, SCHEMA_CS, price_rows(pid))
    check("B8f 还原成按规格绑定的价格 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ B8g 还原后 id 依然没变（改价格不是改身份）",
          sku_ids(pid) == ids_before, f"{ids_before} → {sku_ids(pid)}")

    # ------------------------------------------------------------------
    # B9 去空白 —— NO PAD 排序规则下的必修课
    # ------------------------------------------------------------------
    pad_name = f"{TAG}-带空格"
    pad_schema = [{"name": " 颜色 ", "values": [" 黑 ", "白 "]},
                  {"name": "尺码", "values": ["S", "M"]}]
    pad_skus = [{"specs": [{"name": " 颜色 ", "value": " 黑 "}, {"name": "尺码", "value": "S"}],
                 "price": 10.00, "stock": 1},
                {"specs": [{"name": "颜色", "value": "黑"}, {"name": "尺码", "value": "M"}],
                 "price": 20.00, "stock": 1},
                {"specs": [{"name": "颜色", "value": "白"}, {"name": "尺码", "value": "S"}],
                 "price": 30.00, "stock": 1},
                {"specs": [{"name": "颜色", "value": "白"}, {"name": "尺码", "value": "M"}],
                 "price": 40.00, "stock": 1}]
    st, r, pad_pid = create(pad_name, pad_schema, pad_skus)
    check("B9a 规格名/取值带首尾空格 → 200（后端统一去空白）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    if pad_pid:
        pad_detail = detail(pad_pid)
        check("★ B9b 存进去的是【去过空白】的规格定义",
              pad_detail.get("specSchema") == SCHEMA_CS,
              f"实际存成 {pad_detail.get('specSchema')}，期望 {SCHEMA_CS}")
        check("★★ B9c 「 黑 」和「黑 」被认成同一个组合 —— 只落到 4 行",
              len(sku_rows(pad_pid)) == 4,
              f"实际 {len(sku_rows(pad_pid))} 行：{sku_rows(pad_pid)}")
        check("★ B9d 库里的 spec_json 也是去空白后的形态",
              all("  " not in r_[1] for r_ in sku_rows(pad_pid)),
              f"实际：{[r_[1] for r_ in sku_rows(pad_pid)]}")

    # ------------------------------------------------------------------
    # B10~B13 各种「该被拒绝」的输入
    #
    # ★ 每一条都同时断言【库里一行没变】。
    #   只断言「返回 400」是不够的：先删了再报错、事务又没兜住，
    #   接口看起来一样是 400，但数据已经毁了。
    # ------------------------------------------------------------------
    before = sku_rows(pid)

    dup = skus_of(combos_cs())
    dup.append({"specs": combos_cs()[0], "price": 9.99, "stock": 1})
    st, r = update(pid, name, SCHEMA_CS, dup)
    check("B10a 同一个规格组合提交两次 → 400",
          r.get("code") == 400, f"HTTP {st} / {r}")
    check("B10b 报的是「重复」而不是撞数据库唯一索引的 1062",
          "重复" in (r.get("message") or ""),
          f"message = {r.get('message')!r}")
    check("B10c 库里一行没变", sku_rows(pid) == before, "被拒的请求改动了数据")

    missing = skus_of(combos_cs())[:3]  # 4 个组合只提交 3 个
    st, r = update(pid, name, SCHEMA_CS, missing)
    check("B11a 漏掉一个规格组合 → 400", r.get("code") == 400, f"HTTP {st} / {r}")
    check("★ B11b 消息里说得清「少了什么」",
          "缺少" in (r.get("message") or ""), f"message = {r.get('message')!r}")
    check("B11c 库里一行没变", sku_rows(pid) == before, "被拒的请求改动了数据")

    wrong_value = skus_of(combos_cs())
    wrong_value[0]["specs"] = [{"name": "颜色", "value": "蓝"}, {"name": "尺码", "value": "S"}]
    st, r = update(pid, name, SCHEMA_CS, wrong_value)
    check("B12a 用了规格定义里没有的取值 → 400", r.get("code") == 400, f"HTTP {st} / {r}")
    check("★ B12b 消息里列出了可选值",
          "可选" in (r.get("message") or ""), f"message = {r.get('message')!r}")
    check("B12c 库里一行没变", sku_rows(pid) == before, "被拒的请求改动了数据")

    st, r = update(pid, name, [{"name": "颜色", "values": ["黑", "白"]},
                               {"name": "颜色", "values": ["S", "M"]}], skus_of(combos_cs()))
    check("B13a 两个规格重名 → 400", r.get("code") == 400, f"HTTP {st} / {r}")
    check("B13b 库里一行没变", sku_rows(pid) == before, "被拒的请求改动了数据")

    # ------------------------------------------------------------------
    # B14 组合数超上限
    #
    # ★ 3 维 × 10 值 = 1000 个组合。这里【只提交 1 条 SKU】——
    #   上限必须在前置校验里就被拦住，不能等把 1000 行展开算完再报错。
    # ------------------------------------------------------------------
    big_schema = [{"name": f"维{i}", "values": [f"值{j}" for j in range(10)]} for i in range(3)]
    st, r = update(pid, name, big_schema, [{"specs": [], "price": 1.00, "stock": 1}])
    check("B14a 叉乘出 1000 个组合 → 400", r.get("code") == 400, f"HTTP {st} / {r}")
    check("★ B14b 消息里报了组合数和上限 60",
          "1000" in (r.get("message") or "") and "60" in (r.get("message") or ""),
          f"message = {r.get('message')!r}")
    check("B14c 库里一行没变", sku_rows(pid) == before, "被拒的请求改动了数据")

    # ------------------------------------------------------------------
    # B15~B17 无规格商品（默认 SKU）
    # ------------------------------------------------------------------
    plain_name = f"{TAG}-无规格"
    st, r, plain_pid = create(plain_name, [], [{"specs": [], "price": 6.60, "stock": 3}])
    check("B15a 无规格商品（specSchema=[]、skus 恰好一条空 specs）→ 200",
          r.get("code") == 200, f"HTTP {st} / {r}")
    if plain_pid:
        pj = detail(plain_pid)
        check("★ B15b 详情里 specSchema 是空数组（不是 null）",
              pj.get("specSchema") == [], f"实际 {pj.get('specSchema')!r}")
        check("★ B15c 恰好一条 SKU", len(pj.get("skus") or []) == 1,
              f"实际 {len(pj.get('skus') or [])} 条")
        check("B15d 那条 SKU 的 specs 是空数组、specText 是空串",
              pj["skus"][0]["specs"] == [] and pj["skus"][0]["specText"] == "",
              f"实际 {pj['skus'][0]}")
        check("★★ B15e 库里存的是字符串 '[]'，不是 NULL 也不是空串",
              scalar(f"SELECT spec_json FROM product_sku WHERE product_id = {plain_pid}") == "[]",
              f"实际 {scalar(f'SELECT spec_json FROM product_sku WHERE product_id = {plain_pid}')!r}")

        st, r = update(plain_pid, plain_name, [], [{"specs": [], "price": 6.60, "stock": 3},
                                                   {"specs": [], "price": 7.70, "stock": 4}])
        check("B16a 无规格商品给两条 SKU → 400（它们是同一个组合）",
              r.get("code") == 400, f"HTTP {st} / {r}")
        check("B16b 库里那一行还在、价格没变",
              scalar(f"SELECT price FROM product_sku WHERE product_id = {plain_pid}") == "6.60",
              "被拒的请求改动了数据")

        st, r = update(plain_pid, plain_name, [], [{"specs": [{"name": "颜色", "value": "黑"}],
                                                    "price": 6.60, "stock": 3}])
        check("B17a 无规格商品却给 SKU 塞了规格项 → 400",
              r.get("code") == 400, f"HTTP {st} / {r}")

        st, r = update(plain_pid, plain_name,
                       [{"name": "颜色", "values": ["黑"]}], [{"specs": [], "price": 6.60, "stock": 3}])
        check("B17b 有规格定义但 SKU 的 specs 是空的 → 400",
              r.get("code") == 400, f"HTTP {st} / {r}")

    # ------------------------------------------------------------------
    # B18 五级级联：删商品要把 SKU 一起删掉
    # ------------------------------------------------------------------
    del_pid = pad_pid
    if del_pid:
        st, r = call("DELETE", f"/admin/products/{del_pid}", token=ADMIN_TOKEN)
        check("B18a 删除商品 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
        left = int(scalar(f"SELECT COUNT(*) FROM product_sku WHERE product_id = {del_pid}") or 0)
        check("★ B18b product_sku 里没有孤儿行（第五级级联生效）", left == 0,
              f"残留 {left} 行 —— 全库零外键，数据库不会帮你拦")

    return pid


# ======================================================================
# F · 聚合
# ======================================================================
def group_f(pid):
    section("F · 聚合：minPrice / totalStock / skuCount 是怎么算出来的")

    if not pid:
        check("F 组需要 B 组建出来的商品，但 B 组没建成", False, "前置条件不满足")
        return

    name = f"{TAG}-2维2值"
    # 价格是 10 / 20 / 30 / 40，库存各 5
    st, r = update(pid, name, SCHEMA_CS, price_rows(pid))
    check("F0 重置成 10/20/30/40、库存各 5 → 200", r.get("code") == 200, f"HTTP {st} / {r}")

    d = detail(pid)
    check("★ F1 详情 minPrice = 4 个规格里最便宜那个（10.00）",
          float(d.get("minPrice")) == 10.00, f"实际 {d.get('minPrice')!r}")
    check("★ F2 详情 totalStock = 4 个规格之和（20）",
          int(d.get("totalStock")) == 20, f"实际 {d.get('totalStock')!r}")
    check("★ F3 详情 skuCount = 4", int(d.get("skuCount")) == 4, f"实际 {d.get('skuCount')!r}")

    # ---- 和 SQL 直接对一遍 ----
    sql = run_sql(f"SELECT MIN(price), SUM(stock), COUNT(*) FROM product_sku "
                  f"WHERE product_id = {pid}")[0]
    check("F4 接口给的三个数和 SQL 直接算的完全一致",
          (float(d["minPrice"]), int(d["totalStock"]), int(d["skuCount"]))
          == (float(sql[0]), int(sql[1]), int(sql[2])),
          f"接口 {(d['minPrice'], d['totalStock'], d['skuCount'])} vs SQL {tuple(sql)}")

    # ---- ★★ F5 聚合是【算出来的】，不是从 product 上读的 ----
    #
    # ★ 这一条在阶段 2~5 期间测的是反面：「那三个派生汇总被写回 product 了没有」。
    #   现在反过来测：**product 上没有那三个数了，接口还能算出来。**
    #
    #   为什么值得单独立一条？因为它把「聚合」这个方案钉死了：
    #   如果哪天有人为了性能给 product 加一列缓存（min_price / total_stock），
    #   这条不会红（接口照样对）—— 但 A4 会红。两条一起才是完整的：
    #     A4  管【结构】：product 上没有价格库存
    #     F5  管【行为】：不靠那些列，接口照样算得出起售价和总库存
    #   ★ 一条约束拆成「结构」和「行为」两半来测，是这个项目里
    #     出现过很多次的手法 —— 结构断言防加回来，行为断言防删过头。
    prod_cols = run_sql(
        "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product' "
        "  AND COLUMN_NAME IN ('price', 'stock', 'min_price', 'total_stock')")
    check("★★ F5a product 上没有任何「价格/库存」列（连缓存列都没有）",
          len(prod_cols) == 0, f"还存在：{[c[0] for c in prod_cols]}")
    sql = run_sql(f"SELECT MIN(price), SUM(stock) FROM product_sku "
                  f"WHERE product_id = {pid}")[0]
    check("★★ F5b 那三个数是【现算的】：和 SQL 聚合完全一致",
          (float(d["minPrice"]), int(d["totalStock"])) == (float(sql[0]), int(sql[1])),
          f"接口 {(d['minPrice'], d['totalStock'])} vs SQL {tuple(sql)}");

    # ---- 列表接口也要有 ----
    # ★ 关键词必须 percent-encode 之后再拼进 URL：http.client 只接受
    #   ascii 的请求行，直接塞中文会抛 UnicodeEncodeError（不是 400，是脚本自己崩）。
    #   用 grep 关键词而不是拉全部 —— 列表接口一页最多 100 条，而库里有 100 件商品，
    #   不加条件的话这件测试商品可能落在第二页上，断言就会莫名其妙地失败。
    st, r = call("GET", f"/admin/products?name={quote(name)}&pageNum=1&pageSize=10",
                 token=ADMIN_TOKEN)
    rows = [x for x in (r.get("data") or {}).get("list", []) if x["id"] == pid]
    check("F6 列表接口里能找到这件商品", len(rows) == 1,
          f"没找到。返回 {len((r.get('data') or {}).get('list', []))} 条")
    if rows:
        row = rows[0]
        check("★ F7 列表的 minPrice/totalStock/skuCount 和详情一致",
              (float(row["minPrice"]), int(row["totalStock"]), int(row["skuCount"]))
              == (10.00, 20, 4),
              f"列表 {(row.get('minPrice'), row.get('totalStock'), row.get('skuCount'))}")

    # ------------------------------------------------------------------
    # F8 改一个 SKU 的价 → 起售价跟着变
    #
    # ★ 把【最便宜那个】（黑/S，10.00）改成 99.00，
    #   起售价必须从 10.00 变成 20.00（第二便宜的）。
    #   如果起售价没跟着变，说明它是一个【存下来就不管了】的副本 ——
    #   那正是本轮不变量要防的「第二个定义者」。
    # ------------------------------------------------------------------
    new_skus = price_rows(pid)
    for s in new_skus:
        if "|".join(i["value"] for i in s["specs"]) == "黑|S":
            s["price"] = 99.00
    st, r = update(pid, name, SCHEMA_CS, new_skus)
    check("F8a 把最便宜那个规格改成 99.00 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    d = detail(pid)
    check("★ F8b 起售价跟着变成 20.00（第二便宜的）",
          float(d.get("minPrice")) == 20.00,
          f"实际 {d.get('minPrice')!r} —— 没变的话起售价就是个没人维护的副本")

    # ------------------------------------------------------------------
    # F9 某个规格缺货 → 那一行【仍然在】，只是库存 0
    # ------------------------------------------------------------------
    new_skus = price_rows(pid)
    for s in new_skus:
        if "|".join(i["value"] for i in s["specs"]) == "白|M":
            s["stock"] = 0
    st, r = update(pid, name, SCHEMA_CS, new_skus)
    check("F9a 把「白/M」的库存改成 0 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    d = detail(pid)
    check("★ F9b 缺货的规格【仍然在】skus 里（不能被过滤掉）",
          len(d.get("skus") or []) == 4,
          f"实际 {len(d.get('skus') or [])} 条 —— 被过滤掉的话，"
          f"商家会以为这个规格配置丢了")
    check("F9c totalStock 变成了 15（20 - 5）", int(d.get("totalStock")) == 15,
          f"实际 {d.get('totalStock')!r}")
    check("F9d 那一行的 stock 确实是 0",
          any(s["stock"] == 0 and s["specText"] == "颜色:白 / 尺码:M" for s in d["skus"]),
          f"实际：{[(s['specText'], s['stock']) for s in d['skus']]}")

    # ------------------------------------------------------------------
    # F10 五级级联之后，聚合不会留下幽灵
    # ------------------------------------------------------------------
    st, r = call("DELETE", f"/admin/products/{pid}", token=ADMIN_TOKEN)
    check("F10a 删除商品 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("F10b 删除后 product_sku 一行不剩",
          int(scalar(f"SELECT COUNT(*) FROM product_sku WHERE product_id = {pid}") or 0) == 0,
          "残留孤儿行")


# ======================================================================
# G · 商城读链路（★ 里程碑 15 阶段 3）
# ======================================================================
def shop_list(**params):
    """查商城商品列表，返回 (http_status, 响应体)。"""
    qs = "&".join(f"{k}={quote(str(v))}" for k, v in params.items())
    return call("GET", f"/shop/products?{qs}")


def shop_find(pid, **params):
    """在商城列表里找出某一个商品那一行。查不到返回 None。

    ★ 用 name 关键词而不是拉全表：库里有 100 件商品，
      一页最多 100 条，不加条件的话测试商品可能落在第二页上，
      断言会莫名其妙地失败。B/F 两组也是同一个考虑。
    """
    st, r = shop_list(**params)
    if r.get("code") != 200:
        raise SystemExit(f"商城列表查询失败：HTTP {st} / {r}")
    for row in r["data"]["list"]:
        if row["id"] == pid:
            return row
    return None


def shop_detail(pid):
    return call("GET", f"/shop/products/{pid}")


def shop_sku(sku_id):
    return call("GET", f"/shop/skus/{sku_id}")


def group_g():
    section("G · 商城读链路：规格选择器要用的那几组数据")

    # 四档价格 10 / 20 / 30 / 40，和 combos_cs() 的顺序一一对应。
    # ★ 用价格当身份证：看到 40.00 就知道是最后那一组（白/M）。
    tier = [10.00, 20.00, 30.00, 40.00]

    name_multi = f"{TAG}-商城多规格"
    st, r, pid = create(name_multi, SCHEMA_CS, [
        {"specs": s, "price": p, "stock": 5} for s, p in zip(combos_cs(), tier)
    ])
    pid1 = None
    check("G0a 建一件多规格商品（4 个规格）→ 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") != 200:
        return

    # ---- G1 列表：聚合三个数 ----
    row = shop_find(pid, keyword=name_multi, pageNum=1, pageSize=10)
    check("G1 商城列表里能找到这件商品", row is not None, "没找到 —— 关键词是商品名")
    if not row:
        return

    sql = run_sql(f"SELECT MIN(price), SUM(stock), COUNT(*) FROM product_sku "
                  f"WHERE product_id = {pid}")[0]
    check("★ G2 列表 minPrice == MIN(sku.price)（和 SQL 直接算的对得上）",
          float(row["minPrice"]) == float(sql[0]),
          f"接口 {row['minPrice']} vs SQL {sql[0]}")
    check("★ G3 列表 totalStock == SUM(sku.stock)",
          int(row["totalStock"]) == int(sql[1]),
          f"接口 {row['totalStock']} vs SQL {sql[1]}")
    check("★ G4 列表 skuCount == COUNT(*)", int(row["skuCount"]) == int(sql[2]),
          f"接口 {row['skuCount']} vs SQL {sql[2]}")

    # ---- G5 多规格时必须【没有】defaultSkuId ----
    #
    # ★ 这一条锁的是一个【契约】而不是一个值：
    #   non_null 让 null 字段整个从 JSON 里消失，所以判断方式是
    #   「这个 key 在不在」，不是「它是不是 null」。
    #   前端必须用 falsy 判断，这条断言就是那个约定的守卫。
    check("★★ G5 多规格商品的响应里【没有】defaultSkuId 这个 key",
          "defaultSkuId" not in row,
          f"实际拿到了 {row.get('defaultSkuId')!r} —— 多规格时给一个"
          f"「随便挑的默认规格」就是替用户选了他没选的规格")

    # ---- G6 单规格商品：defaultSkuId 就是那唯一的 SKU ----
    name_single = f"{TAG}-商城单规格"
    st, r, pid1 = create(name_single, [], [{"specs": [], "price": 88.80, "stock": 7}])
    check("G6a 建一件无规格商品（默认 SKU）→ 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") == 200:
        only = sku_ids(pid1)
        row1 = shop_find(pid1, keyword=name_single, pageNum=1, pageSize=10)
        check("G6b 单规格商品的 defaultSkuId == 库里那一行的 id",
              row1 is not None and row1.get("defaultSkuId") == only[0],
              f"接口 {row1.get('defaultSkuId') if row1 else None} vs 库里 {only}")
        check("G6c 单规格商品的 skuCount == 1",
              row1 is not None and int(row1["skuCount"]) == 1,
              f"实际 {row1.get('skuCount') if row1 else None}")
        # ★ 首页「加入购物车」直接用它 —— 生成订单前必须确认它是个真 skuId
        if row1 and row1.get("defaultSkuId"):
            stx, rx = shop_sku(row1["defaultSkuId"])
            check("G6d 拿 defaultSkuId 去查 SKU 接口 → 200（它是个真 skuId）",
                  rx.get("code") == 200, f"HTTP {stx} / {rx}")

    # ---- G7 详情：specSchema 的顺序 = 管理员定义的顺序 ----
    st, r = shop_detail(pid)
    d = r.get("data") or {}
    check("G7a 商城详情能查到 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★★ G7b specSchema 的顺序就是管理员定义的顺序（颜色在前、尺码在后）",
          [g["name"] for g in (d.get("specSchema") or [])] == ["颜色", "尺码"],
          f"实际 {[g['name'] for g in (d.get('specSchema') or [])]} —— "
          f"顺序反了的话，规格选择器上两排按钮会跟着反")
    check("G7c specSchema 每一维的值顺序也保住了",
          [g["values"] for g in (d.get("specSchema") or [])] == [COLORS, SIZES],
          f"实际 {[g['values'] for g in (d.get('specSchema') or [])]}")

    # ---- G8 详情的 minPrice / skuCount 和从 skus 现算的一致 ----
    skus = d.get("skus") or []
    check("G8a 详情带了 4 条规格", len(skus) == 4, f"实际 {len(skus)} 条")
    if skus:
        expect_min = min(float(s["price"]) for s in skus)
        check("★★ G8b 详情 minPrice == min(skus[].price)（两个来源不许分叉）",
              float(d["minPrice"]) == expect_min,
              f"详情 {d.get('minPrice')} vs 现算 {expect_min}")
        check("G8c 详情 skuCount == len(skus)",
              int(d["skuCount"]) == len(skus),
              f"详情 {d.get('skuCount')} vs 实际 {len(skus)}")

    # ---- G9 specText 的维度顺序也是管理员定义的顺序 ----
    #
    # ★★ 这一条是【唯一】能证明 SpecJson.text 真的用了 schema 的断言。
    #   canonical() 为了去重把维度按【规格名】排过序，而这件商品
    #   「尺码」的码点小于「颜色」，所以【不传 schema】会渲染成
    #   「尺码:S / 颜色:黑」。看到「颜色:黑 / 尺码:S」就说明重排生效了。
    by_text = {s["specText"]: s for s in skus}
    check("★★ G9 specText 按 specSchema 的顺序渲染，不是按规格名排序的规范顺序",
          "颜色:黑 / 尺码:S" in by_text,
          f"实际拿到 {sorted(by_text)} —— 如果看到「尺码:S / 颜色:黑」，"
          f"说明 SpecJson.text 没拿到 schema，退回成了 canonical 的顺序")

    # ---- G10 缺货的规格仍然在 skus 里 ----
    st, r = update(pid, name_multi, SCHEMA_CS, [
        {"specs": s, "price": p, "stock": (0 if p == 40.00 else 5)}
        for s, p in zip(combos_cs(), tier)
    ])
    check("G10a 把最贵那个规格的库存改成 0 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = shop_detail(pid)
    d2 = r.get("data") or {}
    check("★★ G10b 缺货的规格仍然在 skus 里（详情页要把它的按钮标灰，不是让它消失）",
          len(d2.get("skus") or []) == 4,
          f"实际 {len(d2.get('skus') or [])} 条 —— 被过滤掉的话，"
          f"用户找不到「白色 / M」，只会以为商品配置坏了")
    out = [s for s in (d2.get("skus") or []) if s["stock"] == 0]
    check("G10c 缺货那条的 stock 确实是 0，而且它还在", len(out) == 1,
          f"实际 {[(s['specText'], s['stock']) for s in (d2.get('skus') or [])]}")
    check("★ G10d 缺货的规格，GET /shop/skus/{id} 仍然 200（缺货 ≠ 不存在）",
          out and shop_sku(out[0]["id"])[1].get("code") == 200,
          "缺货的 SKU 报「不存在」的话，购物车里的失效判断会跟着错")

    # ---- G11 GET /api/shop/skus/{skuId} 的四个商品字段 ----
    st, r = shop_sku(skus[0]["id"])
    sv = r.get("data") or {}
    check("G11a SKU 接口 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") == 200:
        check("★ G11b SKU 接口带了商品名（立即购买那条链接上只有 skuId，没别的地方能拿到）",
              sv.get("productName") == name_multi,
              f"实际 {sv.get('productName')!r}")
        check("G11c SKU 接口带了 productId", sv.get("productId") == pid,
              f"实际 {sv.get('productId')!r}")
        specs = sv.get("specs")
        check("G11d SKU 接口带了 specs（给代码读的那组）",
              isinstance(specs, list) and len(specs) == 2,
              f"实际 {specs!r}")
        # ★ specs 非空【必须】先判 —— 空列表会让下面那个 all() 恒为真。
        #   这正是它上一版的样子：接口返回 401、specs 是 None，
        #   而这条断言照样打出了 [OK]。真空真（vacuous truth）
        #   是断言里最安静的一种假绿。
        check("G11e SKU 接口的 specs 里有 name 和 value 两个键",
              specs and all(set(i) == {"name", "value"} for i in specs),
              f"实际 {specs!r}")

    # ---- G12 下架之后两条件都查不到 ----
    #
    # ★★ 这一条是「status = 1 这条安全规则不能被绕过」的证明：
    #   同一个 skuId，商品一下架就查不到了。如果 SKU 接口返回 200，
    #   说明有人绕过 ProductMapper 的 shop* 那一组自己 JOIN 了一次。
    st, r = update(pid, name_multi, SCHEMA_CS, [
        {"specs": s, "price": p, "stock": 5} for s, p in zip(combos_cs(), tier)
    ], status=0)
    check("G12a 把商品下架 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = shop_detail(pid)
    check("★ G12b 下架后商品详情 → 业务码 1003",
          r.get("code") == 1003, f"HTTP {st} / code={r.get('code')}")
    st, r = shop_sku(skus[0]["id"])
    check("★★ G12c 下架后 SKU 接口【也】1003（同一个 skuId，规则只有一处实现）",
          r.get("code") == 1003,
          f"HTTP {st} / code={r.get('code')} —— 返回 200 说明有人绕过"
          f"ProductMapper 的 shop* 自己 JOIN 了一次 product")
    row2 = shop_find(pid, keyword=name_multi, pageNum=1, pageSize=10)
    check("★ G12d 下架后商城列表里也找不到它", row2 is None, "下架商品泄露到列表了")

    # ---- G13 排序：price_asc 必须按 minPrice 排 ----
    #
    # ★★ 判据是「排序用的价格 == 列表上显示的价格」。
    #   两者都取 MIN(price) 天然一致；把 shopOrderBy 改回 p.price
    #   在【现在】看不出来（那一列是派生汇总，值相同），
    #   要等 13b 删列或有人漏同步时才发作 —— 所以现在就钉住它。
    st, r = shop_list(pageNum=1, pageSize=8, sort="price_asc")
    xs = [float(p["minPrice"]) for p in (r.get("data") or {}).get("list", [])]
    check("★ G13 price_asc 排出来的 minPrice 单调不减（排序依据 == 显示的价格）",
          len(xs) > 1 and all(a <= b for a, b in zip(xs, xs[1:])),
          f"实际 {xs} —— 出现 [100, 80, 50] 就是排序还在用 product.price")

    # ---- 收尾：G 组自己建的商品自己删 ----
    for x in (pid, pid1):
        if x:
            call("DELETE", f"/admin/products/{x}", token=ADMIN_TOKEN)


# ======================================================================
# C / D / E 三组（★ 里程碑 15 阶段 4）—— 购物车与下单按 skuId
# ======================================================================
#
# ★★ 这三组测的是本轮【唯一会扣真东西】的那条链路：下单扣库存。
#    前面 A/B/F/G 全是「读」——读错了顶多显示不对。
#    从这里开始，每一处改错都会让库存的数字【真的】变少或变多，
#    而且大部分改错方式都不会报错。
#
# ⚠️ 特别注意这一轮引入了两个「id 撞车」的危险：
#    productId 和 skuId 都是自增数字，撞号是必然的。
#    所以任何一处忘了改，都不会 404、不会 400 ——
#    它只会静默地【操作另一行】。

# 一维两值：颜色 黑 / 白。比 combos_cs() 的四组合更适合计价和库存用例 ——
# 只有两行，断言里能一眼看清哪个是哪个。
SCHEMA_C = [{"name": "颜色", "values": ["黑", "白"]}]

# 价格刻意差得远：「黑」10 元、「白」100 元。
# ★ 差得远是有意的 —— 金额断言算错时不会「碰巧相等」。
PRICE_C = {"黑": 10.00, "白": 100.00}


def combos_c(order=(0,)):
    """颜色那一维的两个组合。〔order〕可以颠倒值的顺序（给 B 组之外复用）。"""
    vals = ["黑", "白"]
    if order == (1,):
        vals = list(reversed(vals))
    return [[{"name": "颜色", "value": v}] for v in vals]


def skus_c(stock=5, stock_map=None):
    """颜色 × 价格。〔stock_map〕可以给某个颜色单独定库存。"""
    out = []
    for specs in combos_c():
        v = specs[0]["value"]
        out.append({
            "specs": specs,
            "price": PRICE_C[v],
            "stock": stock if not stock_map else stock_map.get(v, stock),
        })
    return out


def sku_id_of(pid, color):
    """查出某个颜色的 SKU id。用 spec_json 精确匹配，不靠顺序。"""
    row = scalar(f"SELECT id FROM product_sku "
                 f"WHERE product_id = {pid} AND spec_json LIKE '%\"{color}\"%'")
    return int(row) if row else None


def stock_of_sku(sku_id):
    """★ 直接读库里的库存，不通过接口 ——
    接口上的数字可能是缓存或者算出来的，而这里要的是【那行数据本身】。"""
    return int(scalar(f"SELECT stock FROM product_sku WHERE id = {sku_id}") or 0)


def order_id_of(order_no):
    return int(scalar(f"SELECT id FROM orders WHERE order_no = '{order_no}'"))


def item_rows(order_no):
    """订单明细：(id, product_id, sku_id, sku_spec, price, quantity, subtotal)。"""
    return run_sql(
        "SELECT oi.id, oi.product_id, oi.sku_id, oi.sku_spec, oi.price, "
        "oi.quantity, oi.subtotal "
        "FROM order_item oi JOIN orders o ON o.id = oi.order_id "
        f"WHERE o.order_no = '{order_no}' ORDER BY oi.id")


def cart_key():
    return f"mall:cart:{MEMBER_ID}"


def cart_add(sku_id, qty=1):
    return call("POST", "/shop/cart/items",
                {"skuId": sku_id, "quantity": qty}, token=MEMBER_TOKEN)


def cart_update(sku_id, qty):
    return call("PUT", f"/shop/cart/items/{sku_id}",
                {"quantity": qty}, token=MEMBER_TOKEN)


def cart_remove(sku_id):
    return call("DELETE", f"/shop/cart/items/{sku_id}", token=MEMBER_TOKEN)


def get_cart():
    return call("GET", "/shop/cart", token=MEMBER_TOKEN)


def cart_item_of(sku_id):
    """从购物车接口里挑出某一行。查不到返回 None。"""
    st, r = get_cart()
    if r.get("code") != 200:
        raise SystemExit(f"查购物车失败：HTTP {st} / {r}")
    for it in r["data"]["items"]:
        if it.get("skuId") == sku_id:
            return it
    return None


def create_address():
    st, r = call("POST", "/shop/addresses", {
        "receiver": "SKU测试", "phone": "13800000000",
        "region": "北京市 朝阳区", "detail": "测试路 1 号",
    }, token=MEMBER_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试地址失败：HTTP {st} / {r}")
    return r["data"]


def buy_now(sku_id, qty, address_id, idem_key):
    """★ 立即购买。阶段 4 起 body 里是 skuId —— 这一行本身就是被测对象之一。"""
    return call("POST", "/shop/orders/buy-now", {
        "skuId": sku_id, "quantity": qty,
        "addressId": address_id, "idempotencyKey": idem_key,
    }, token=MEMBER_TOKEN)


def cart_order(sku_ids, address_id, idem_key):
    return call("POST", "/shop/orders", {
        "skuIds": sku_ids, "addressId": address_id, "idempotencyKey": idem_key,
    }, token=MEMBER_TOKEN)


def cancel_order(order_no):
    return call("POST", f"/shop/orders/{order_no}/cancel", token=MEMBER_TOKEN)


def key_for(tag):
    """幂等键。后端要求 ^[A-Za-z0-9_-]{8,64}$，且同一个会员内唯一。"""
    return f"k{RUN}{tag}"


# ----------------------------------------------------------------------
def group_c():
    section("C · 计价与库存：下单扣的是 sku 行，不是 product 行")

    st, r, pid = create(f"{TAG}-计价", SCHEMA_C, skus_c(stock=20))
    check("C0 建一件两规格商品（黑 10 元 / 白 100 元）→ 200",
          r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") != 200:
        return

    black = sku_id_of(pid, "黑")
    white = sku_id_of(pid, "白")
    check("C0b 两个规格都拿到了 id", black and white and black != white,
          f"黑={black} 白={white}")

    addr = create_address()

    # ---- C1 计价：两行不同单价，合计必须是 p1*q1 + p2*q2 ----
    #
    # ★★ 金额由后端算。前端传的只有 skuId 和地址 ——
    #   所以这一条同时验证了「价格是从 sku 行读的」：
    #   如果哪一层还去读 product.price，这里会算成同一个数。
    cart_add(black, 3)
    cart_add(white, 2)
    st, r = cart_order([black, white], addr, key_for("c1"))
    check("C1a 同商品两规格一起下单 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") != 200:
        return
    order_no = r["data"]["orderNo"]

    expect = PRICE_C["黑"] * 3 + PRICE_C["白"] * 2
    check(f"★★ C1b 合计 = 10×3 + 100×2 = {expect:.2f}（两行按各自的单价算）",
          abs(float(r["data"]["totalAmount"]) - expect) < 0.001,
          f"实际 {r['data']['totalAmount']} —— 如果是 200.00 或 30.00，"
          f"说明有人拿其中一个规格的单价乘了两行的数量")

    # ---- C4 同商品两规格 → 两行明细，各自的 sku_id 和 sku_spec 都不同 ----
    rows = item_rows(order_no)
    check("★★ C4a 同一件商品的两条明细都落到了库里（不是被合并成一行）",
          len(rows) == 2, f"实际 {len(rows)} 行：{rows}")
    if len(rows) == 2:
        skus_in_order = {int(x[2]) for x in rows}
        specs_in_order = {x[3] for x in rows}
        check("★★ C4b 两条明细的 sku_id 不同、且就是刚才那两条",
              skus_in_order == {black, white},
              f"库里 {skus_in_order}，期望 {{{black}, {white}}} —— "
              f"两条一样的话，说明下单时用的是 productId")
        check("★★ C4c 两条明细的 sku_spec 不同（订单快照记下了买的是哪个规格）",
              len(specs_in_order) == 2,
              f"实际 {specs_in_order} —— 两条都是空串的话，"
              f"订单页上这两行会长得一模一样")
        check("★ C4d sku_spec 是给人看的那句话（「颜色:黑」这种形状）",
              all("颜色:" in x[3] for x in rows),
              f"实际 {sorted(specs_in_order)}")

    # ---- C3 取消订单：库存要还到【正确的那个 SKU】上 ----
    #
    # ★★ 这一条是「increaseSkuStock 有没有漏改」的证明。
    #   老代码拿 productId 去还库存 —— 它会撞上另一个商品的 skuId
    #   （撞上了就还给别人），或者撞不上（那就是 0 行，静默地丢掉库存）。
    #   两种都不会报错，只有这条断言能看出来。
    b_before = stock_of_sku(black)
    w_before = stock_of_sku(white)

    st, r2, pid2 = create(f"{TAG}-取消归还", SCHEMA_C, skus_c(stock=20))
    check("C3-0 建第二件同结构商品 → 200", r2.get("code") == 200, f"HTTP {st} / {r2}")
    if r2.get("code") != 200:
        return
    b2 = sku_id_of(pid2, "黑")
    w2 = sku_id_of(pid2, "白")
    b2_before = stock_of_sku(b2)
    w2_before = stock_of_sku(w2)

    st, r3 = buy_now(b2, 2, addr, key_for("c3"))
    check("C3a 买 2 件「黑」→ 200", r3.get("code") == 200, f"HTTP {st} / {r3}")
    if r3.get("code") == 200:
        check("C3b 扣的是「黑」那一行（-2）",
              stock_of_sku(b2) == b2_before - 2,
              f"{b2_before} → {stock_of_sku(b2)}")
        check("★★ C3c 同一商品的「白」一个数都没动",
              stock_of_sku(w2) == w2_before,
              f"{w2_before} → {stock_of_sku(w2)} —— 动了的话，"
              f"说明扣库存时按 product 聚合了")

        st, r4 = cancel_order(r3["data"]["orderNo"])
        check("C3d 取消订单 → 200", r4.get("code") == 200, f"HTTP {st} / {r4}")
        check("★★★ C3e 库存还回了【黑】那一行（+2）",
              stock_of_sku(b2) == b2_before,
              f"{b2_before - 2} → {stock_of_sku(b2)}，期望回到 {b2_before} —— "
              f"没回到的话，说明 increaseSkuStock 还传着 productId")
        check("★★ C3f 归还时「白」仍然一个数都没动",
              stock_of_sku(w2) == w2_before,
              f"{w2_before} → {stock_of_sku(w2)} —— "
              f"库存还给另一个 SKU 的话，两个数会一升一降")

    check("★ C3g 上面那笔用来验取消的订单，没污染到第一件商品",
          stock_of_sku(black) == b_before and stock_of_sku(white) == w_before,
          f"黑 {b_before}→{stock_of_sku(black)}，白 {w_before}→{stock_of_sku(white)}")

    # ---- C2 ★★★ 20 线程并发抢同一个 skuId ----
    #
    # ★★ 这是本轮最有价值的一条：它同时证明了
    #    「扣库存扣的是 sku 行的 stock 列」和「UPDATE 的 WHERE stock >= ? 真的在拦」。
    #    如果谁把 decreaseSkuStock 改成了先 SELECT 再 UPDATE（丢掉 WHERE 条件），
    #    唯一的症状就是这里多出 1 个成功 —— 平时完全看不出来。
    st, r5, pid3 = create(f"{TAG}-并发", SCHEMA_C, skus_c(stock_map={"黑": 5, "白": 50}))
    check("C2a 建一件库存 5 的并发测试商品 → 200",
          r5.get("code") == 200, f"HTTP {st} / {r5}")
    if r5.get("code") != 200:
        return
    race = sku_id_of(pid3, "黑")
    other = sku_id_of(pid3, "白")

    results = []
    lock = threading.Lock()

    def worker(idx):
        # 每个线程用【各自不同】的幂等键 —— 它们代表 20 个不同的下单意图。
        # 用同一个键的话会有 19 个被幂等保护挡掉，那就测不出并发了。
        stx, rx = buy_now(race, 1, addr, key_for(f"race{idx:02d}"))
        with lock:
            results.append((stx, rx.get("code"), rx.get("message")))

    before_orders = int(scalar(
        f"SELECT COUNT(*) FROM orders WHERE member_id = {MEMBER_ID}"))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok_count = sum(1 for _, code, _ in results if code == 200)
    not_enough = sum(1 for _, code, _ in results if code == 1001)
    other_err = [x for x in results if x[1] not in (200, 1001)]

    print(f"  20 个并发请求：成功 {ok_count}，库存不足 {not_enough}，"
          f"其他 {len(other_err)}")

    check("★★★ C2b 恰好只有 5 个成功（库存是 5，多一个都不行）",
          ok_count == 5,
          f"成功 {ok_count} 个 —— 6 个以上就是超卖，是必须修的 bug")
    check("★★★ C2c 其余 15 个都是「库存不足」(1001)，不是 500",
          not_enough == 15, f"1001 有 {not_enough} 个，期望 15 个")
    # ★ 「其他错误」和「库存不足」必须分开数：
    #   15 个 1001 是【正确】的失败，出现任何一个 500 或超时才是 bug。
    #   混成一个「失败数」的话，一个 500 会被 14 个正常的 1001 盖过去。
    check("★★★ C2d 没有出现其他错误（失败都是干净的业务失败，不是 500）",
          not other_err, f"非 200/1001 的响应：{other_err}")

    after_orders = int(scalar(
        f"SELECT COUNT(*) FROM orders WHERE member_id = {MEMBER_ID}"))
    check("★★★ C2e 库存精确归零 —— 不是负数，也不是还有剩",
          stock_of_sku(race) == 0, f"stock = {stock_of_sku(race)}")
    check("★★★ C2f 数据库里【恰好】多了 5 笔订单（不是 20 笔）",
          after_orders == before_orders + 5,
          f"{before_orders} → {after_orders}")
    check("★★★ C2g 同一商品另一个规格（库存 50）一个数都没动",
          stock_of_sku(other) == 50,
          f"stock = {stock_of_sku(other)} —— 被扣了的话，"
          f"说明并发下扣的是 product 层面的库存")

    for x in (pid, pid2, pid3):
        call("DELETE", f"/admin/products/{x}", token=ADMIN_TOKEN)


# ----------------------------------------------------------------------
def group_d():
    section("D · 购物车：Redis 的 field 就是 skuId")

    st, r, pid = create(f"{TAG}-购物车", SCHEMA_C, skus_c(stock=20))
    check("D0 建一件两规格商品 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") != 200:
        return

    black = sku_id_of(pid, "黑")
    white = sku_id_of(pid, "白")

    redis_cmd("DEL", cart_key())
    cart_add(black, 2)
    cart_add(white, 5)

    # ---- D1 ★★ Redis 里的 field 就是两个 skuId ----
    #
    # ★★ 这一条【必须】直接看 Redis，不能只看接口。
    #   后端完全可以在返回 items 时「贴心地」把 productId 换算成 skuId ——
    #   那样接口看起来是对的，而存储里还是老样子，
    #   下一个读购物车的地方（下单、清理）就会踩空。
    raw = redis_cmd("HGETALL", cart_key())
    fields = raw[0::2]          # HGETALL 是 field, value, field, value…
    check("★★ D1a Redis 的 field 恰好是两个 skuId（不是 productId）",
          sorted(fields) == sorted([str(black), str(white)]),
          f"实际 {fields}，期望 {sorted([str(black), str(white)])} —— "
          f"如果看到的是 {pid}，说明 field 还是商品 id")
    check("★★ D1b 同一件商品在车里占了【两行】（不是被合并成一行数量 7）",
          len(fields) == 2, f"实际 {len(fields)} 个 field：{fields}")

    # ---- D2 改一个不影响另一个 ----
    cart_update(black, 9)
    check("★★ D2 改「黑」的数量不动「白」",
          redis_cmd("HGET", cart_key(), str(white)) == ["5"]
          and redis_cmd("HGET", cart_key(), str(black)) == ["9"],
          f"黑={redis_cmd('HGET', cart_key(), str(black))} "
          f"白={redis_cmd('HGET', cart_key(), str(white))}")

    # ---- D3 TTL 仍然是 30 天 ----
    ttl = int(redis_cmd("TTL", cart_key())[0])
    check("★ D3 购物车 TTL 仍是 29~30 天（换 field 没把过期时间弄丢）",
          29 * 86400 <= ttl <= 30 * 86400,
          f"TTL = {ttl} 秒（{ttl / 86400:.2f} 天）")

    # ---- D4 价格永远从库里现查，不存快照 ----
    #
    # ★★ 对照组是这一条的全部价值：
    #   改【另一个】规格的价，这一行必须纹丝不动。
    #   不设这个对照的话，「价格变了」可能只是整个商品的价格被换了。
    item_before = cart_item_of(black)
    check("D4a 购物车里「黑」的单价是 10.00（从 sku 行现查）",
          float(item_before["price"]) == PRICE_C["黑"],
          f"实际 {item_before.get('price')}")

    sp, sr = update(pid, f"{TAG}-购物车", SCHEMA_C, [
        {"specs": s, "price": (999.00 if s[0]["value"] == "黑" else PRICE_C["白"]),
         "stock": 20}
        for s in combos_c()
    ])
    check("D4b 改「黑」的价为 999 → 200", sr.get("code") == 200, f"HTTP {sp} / {sr}")
    check("★★ D4c 购物车里「黑」的价格跟着变了（证明没在 Redis 里存价格快照）",
          float(cart_item_of(black)["price"]) == 999.00,
          f"实际 {cart_item_of(black).get('price')}")

    # 改回来，让下面那条对照更干净
    update(pid, f"{TAG}-购物车", SCHEMA_C, skus_c(stock=20))
    check("★ D4d 改回 10.00 之后购物车也跟着回来了",
          float(cart_item_of(black)["price"]) == PRICE_C["黑"],
          f"实际 {cart_item_of(black).get('price')}")

    # ---- D5 下架之后：field 还在、available=false ----
    update(pid, f"{TAG}-购物车", SCHEMA_C, skus_c(stock=20), status=0)
    st, rc = get_cart()
    check("D5a 商品下架后购物车接口仍然 200（空购物车和失效行都不是错误）",
          rc.get("code") == 200, f"HTTP {st} / {rc}")
    inv = cart_item_of(black) if rc.get("code") == 200 else None
    check("★★ D5b 下架后那一条【还在】车里（不能悄悄消失）",
          inv is not None,
          f"实际 items = {(rc.get('data') or {}).get('items')}")
    if inv:
        check("★★ D5c 它被标成 available=false",
              inv.get("available") is False, f"实际 {inv.get('available')}")
        check("D5d 带了一句给用户看的原因",
              bool(inv.get("unavailableReason")), f"实际 {inv.get('unavailableReason')!r}")
        check("★ D5e 它的 skuId 和 quantity 仍然有值（这两个来自 Redis）",
              inv.get("skuId") == black and inv.get("quantity") == 9,
              f"skuId={inv.get('skuId')} quantity={inv.get('quantity')}")
        # ⚠️⚠️ 这一条是【刻意的取舍】，不是漏做：
        #   规格文本要靠「SKU 行 + 商品」两样拼出来，而查商品那条 SQL 带
        #   status = 1 —— 失效行的商品本来就查不到，所以 specText 是 null，
        #   而 non_null 会让这个 key 整个从 JSON 里消失。
        #   计划里原本要求失效行也显示规格，实现时放弃了：为它开一条
        #   「不过滤 status」的查询，等于给「用户端能看见什么」这条安全规则
        #   开第二个口子。代价是一个安全问题，收益是一点文案。
        #   ★ 所以这条断言锁的是【现在的行为】，而不是"应该有的行为" ——
        #     它存在的意义是：将来有人补上这个功能时，会看到这里并知道
        #     那是一次有意的行为改变，而不是以为自己修好了一个 bug。
        check("★★ D5f 失效行的 specText 【整个 key 都不在】"
              "（刻意的取舍：不为它开第二条查询路径）",
              "specText" not in inv,
              f"实际拿到了 {inv.get('specText')!r} —— 如果这里开始有值了，"
              f"请先确认商品查询没有多开一个不过滤 status 的口子")
    check("★ D5g Redis 里那条 field 也还在（失效不是删除）",
          redis_cmd("HGET", cart_key(), str(black)) == ["9"],
          f"Redis: {redis_cmd('HGETALL', cart_key())}")

    # ---- D6 移除 / 下单后的清理，删的是 skuId 那个 field ----
    #
    # ★★ 这一条盯着的是本轮【头号静默风险点】：HDEL 的 field 传错，
    #   要么什么都没删掉（用户重新下一单，买两份），
    #   要么【productId 撞上另一个 skuId，删掉用户车里另一行】。
    #   后者尤其恶劣：它发生在订单事务提交【之后】的回调里，
    #   那里抛的异常只打一条日志，三层代码没有一层会报错。
    update(pid, f"{TAG}-购物车", SCHEMA_C, skus_c(stock=20))
    cart_remove(black)
    check("★★ D6a 移除「黑」之后它的 field 没了",
          redis_cmd("HEXISTS", cart_key(), str(black)) == ["0"],
          f"Redis: {redis_cmd('HGETALL', cart_key())}")
    check("★★★ D6b 而「白」那一行【原封不动】（移除没有误伤相邻的行）",
          redis_cmd("HGET", cart_key(), str(white)) == ["5"],
          f"白 = {redis_cmd('HGET', cart_key(), str(white))} —— "
          f"变成空的话，说明 HDEL 用错了 id")

    # 下单后的自动清理：那条路走的是 removeItems(skuIds)
    addr = create_address()
    redis_cmd("DEL", cart_key())
    cart_add(black, 2)
    cart_add(white, 3)
    st, ro = cart_order([black], addr, key_for("d6"))
    check("D6c 只结算「黑」→ 200", ro.get("code") == 200, f"HTTP {st} / {ro}")
    check("★★★ D6d 下单后只清掉了「黑」的 field，没动「白」",
          redis_cmd("HEXISTS", cart_key(), str(black)) == ["0"]
          and redis_cmd("HGET", cart_key(), str(white)) == ["3"],
          f"Redis: {redis_cmd('HGETALL', cart_key())} —— "
          f"「白」也被清掉的话，用户会发现车里少了东西")

    redis_cmd("DEL", cart_key())
    call("DELETE", f"/admin/products/{pid}", token=ADMIN_TOKEN)


# ----------------------------------------------------------------------
def group_e():
    section("E · 历史与孤儿：sku_id 可以是 NULL，而且那条路必须走得通")

    st, r, pid = create(f"{TAG}-孤儿", SCHEMA_C, skus_c(stock=20))
    check("E0 建一件两规格商品 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") != 200:
        return
    black = sku_id_of(pid, "黑")
    addr = create_address()

    # ---- E1 新订单的 sku_id 一定是非空的 ----
    st, r1 = buy_now(black, 1, addr, key_for("e1"))
    check("E1a 立即购买 → 200", r1.get("code") == 200, f"HTTP {st} / {r1}")
    if r1.get("code") == 200:
        order_no = r1["data"]["orderNo"]
        null_cnt = int(scalar(
            "SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id "
            f"WHERE o.order_no = '{order_no}' AND oi.sku_id IS NULL"))
        check("★★ E1b 新订单的明细 sku_id 非空（新写的路必须填上它）",
              null_cnt == 0,
              f"有 {null_cnt} 行 sku_id 是 NULL —— 取消订单时那些行"
              f"还不了库存，只能打一条 warn")

        # ---- E2 造一条 sku_id = NULL 的孤儿明细，取消它必须【安全地】走通 ----
        #
        # ★★ 这个场景是真实的：里程碑 13 之前的历史订单没有 sku_id，
        #   而且有一批「商品已被硬删」的孤儿明细（注释里记着漏过 32 行）。
        #   阶段 4 的选择是【不硬填一个假值】—— NULL 是诚实的，
        #   而且它已经有代码路径：increaseSkuStock(null, qty) 影响 0 行，
        #   走现有的「warn 不抛」分支。
        #
        # ⚠️ 「影响 0 行」和「抛异常」的区别就是这一条要证明的：
        #   抛异常的话，用户点「取消订单」会看到一个 500，
        #   而他唯一能做的操作就是取消 —— 那笔订单会永远卡在待付款。
        oid = order_id_of(order_no)
        run_sql(f"UPDATE order_item SET sku_id = NULL WHERE order_id = {oid}")

        b_before = stock_of_sku(black)
        st, r2 = cancel_order(order_no)
        check("★★★ E2a 取消一条 sku_id 为 NULL 的订单 → 200（不是 500）",
              r2.get("code") == 200,
              f"HTTP {st} / {r2} —— 报错的话，这类历史订单会永远卡在待付款")
        status_now = scalar(
            f"SELECT status FROM orders WHERE order_no = '{order_no}'")
        check("★★ E2b 订单确实变成已取消（4）",
              status_now == "4", f"实际 status = {status_now}")
        check("★★ E2c 库存【没有】被乱加（还不出库存时就该原样不动）",
              stock_of_sku(black) == b_before,
              f"{b_before} → {stock_of_sku(black)} —— "
              f"库存凭空多了的话，说明还给了某条【别的】SKU 行")

    # ---- E3 删商品时 product_sku 必须跟着走（五级级联的最后两级）----
    #
    # ★★ 这一条是在测「级联顺序」。product_sku 和别的子表一样【没有外键】，
    #   所以删商品时如果漏掉它，不会报任何错 —— 只会安静地攒孤儿行。
    leftovers = sku_ids(pid)
    check("E3a 删之前确认它有 SKU 行", len(leftovers) == 2, f"实际 {leftovers}")
    st, r3 = call("DELETE", f"/admin/products/{pid}", token=ADMIN_TOKEN)
    check("E3b 删商品 → 200", r3.get("code") == 200, f"HTTP {st} / {r3}")
    check("★★★ E3c 删完之后 product_sku 里一行残留都没有",
          int(scalar(f"SELECT COUNT(*) FROM product_sku WHERE product_id = {pid}") or 0) == 0,
          f"残留 = "
          f"{scalar(f'SELECT COUNT(*) FROM product_sku WHERE product_id = {pid}')} —— "
          f"没有外键，漏删不会有任何报错")


def main():
    print("=" * 72)
    print(f"里程碑 15 · 商品多规格（SKU）  前缀 {TAG}")
    print("=" * 72)

    admin_login()

    # ★★★ 这两行的顺序【不能反】，反了就是本轮最难查的一个 bug：
    #
    #   cleanup() 的最后一句是「删掉用户名带 skutest 前缀的会员」。
    #   写成 member_setup() 在前，就是「先注册一个会员，再把它删掉」——
    #   而 cleanup() 里删订单那三层用的是 join member 按用户名删，
    #   会员行已经没了 → 那三条 join 从此匹配 0 行 → **订单留在库里**。
    #
    #   ★ 症状为什么极难查：
    #     · 脚本全绿 —— 断言全在看接口，没有一条在看库里的垃圾；
    #     · 会员的 token 是 JWT，**签名有效就放行，不查库**，
    #       所以一个「已经不存在的会员」照样能下单、能取消、能看购物车；
    #     · 于是整个 C/D/E 三组在一个**幽灵会员**名下跑完，全部通过。
    #   排查时最误导人的一点：孤儿订单的 member_id 指向的行不存在，
    #   看起来像「有人手工删了会员」，而其实是本脚本开头自己删的。
    #
    #   ★ 正确的顺序（也是这段代码最初想表达的）：
    #     先扫掉上一次跑崩留下的残留 → 再注册本轮要用的会员。
    cleanup()
    member_setup()

    # ★ 记一个基线：跑之前库里的「无主行」有几条。跑完要一模一样。
    junk_before = junk_snapshot()

    group_a()
    group_g()
    pid = group_b()
    group_f(pid)
    group_c()
    group_d()
    group_e()

    cleanup()

    # ★★ 最后这条断言才是「清理逻辑对不对」的判据。
    #    上面所有用例都在问「接口做了它该做的事」，
    #    这一条在问「脚本自己有没有留下垃圾」——
    #    当时那几十笔孤儿订单，就是在「全绿」的注视下一笔一笔攒起来的。
    junk_after = junk_snapshot()
    check("★★ 跑完之后库里没有多出任何无主行（订单/明细/地址/SKU/商品）",
          junk_after == junk_before,
          f"跑之前 {junk_before} → 跑之后 {junk_after}"
          f"（顺序是 无主订单/无主明细/无主地址/无主SKU/残留商品）—— "
          f"对不上说明 cleanup() 漏了一层，而【脚本自己不会报错】")

    section("本脚本的覆盖边界（★ 全绿不等于覆盖全了）")
    note("阶段 2~5 期间这里写着「A4/A5 两条还没写」。阶段 6 写完它们之后，")
    note("原先列在这儿的命题一条不剩 —— 但这一段【故意留着】。")
    note("")
    note("★ 留着的理由：一个「还没写」清单变成空的时候，最自然的动作是删掉它，")
    note("  于是下一个人看到的是一个全绿、且看不出【覆盖边界在哪】的脚本。")
    note("  而这个脚本有一块覆盖边界是结构性的、永远存在的：")
    note("    · A5 读的是【源码】，不是运行中的系统 —— 它只认")
    note("      resources/mapper/*.xml 这个路径和 p.price/p.stock 这几种写法。")
    note("      换一种写法（比如给表起个别名 pp）它就看不见了。")
    note("    · 规格级图片 / SKU 级上下架 / 动态可选性【本轮明确不做】")
    note("      （见 README 的已知取舍），所以这里也不会有对应用例。")
    note("  ★ 这两条都不是「忘了写」，是「知道边界在哪」。")
    note("    全绿本身不说明覆盖全了 —— 说明这件事的只有这种清单。")

    print()
    print("=" * 72)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 72)
    if FAILED:
        print()
        print("失败清单：")
        for f in FAILED:
            print(f"  - {f}")
        sys.exit(1)


if __name__ == "__main__":
    main()
