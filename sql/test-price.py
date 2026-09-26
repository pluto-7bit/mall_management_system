# -*- coding: utf-8 -*-
"""
里程碑 16 测试：价格体系（划线价 / 价格区间 / 成本价与毛利）

本轮往 product_sku 上加了两个新列（migration-14b）：

    market_price  划线价（原价/市场价）
    cost_price    成本价（进货价）★ 只在管理端出现

它们各自带来一整类【新的静默失败】，这组用例就是围着这些失败写的：

  划线价  —— 它有一个【展示规则】：「只有 market_price > price 才画删除线」。
             于是保存时如果不校验，一个填错的划线价会被前端静默吃掉 ——
             商家以为自己设了，商城页什么都不显示，两端都不报错。
             ★ 而且这里还埋了一个更细的陷阱：校验和存储的【舍入必须一致】，
               否则「100.004 > 100.00」通过校验、MySQL 存成 100.00、
               展示规则又变成假 —— 静默失败会从后门绕回来。B5 打的就是它。

  价格区间 —— minPrice / maxPrice 是同一个事实的两种来源（列表走 SQL 聚合，
             详情走 Java 计算）。两者分岔的现象是「首页写 ¥4999 ~ ¥6999，
             点进去变成 ¥4999 ~ ¥5999」，而两个数都各自「算对了」。

  成本价   —— 它是本轮唯一一个【有安全边界】的字段：绝不能进 /api/shop/**。
             危险之处在于边界不在 SQL 上（SQL 该查就查，见 ProductSkuMapper.xml），
             而在 VO 上：ShopSkuVO extends SkuVO，所以往父类加一个字段，
             用户端接口立刻匿名泄漏，一行改动、零编译错误。

★ 所以 E 组必须是【双向】的：用户端不许有，管理端必须有。
  只有前半句的话，在「成本字段根本没接上」时它也会全绿 ——
  一个空转的检查比没有检查更危险（E0 那条反空转守卫就是防这个的）。

★ F 组断言的是【实测出来的】行为，不是计划里假设的。
  计划里写「out-of-range → 400」，实测是 HTTP 200 + body.code 500
  （和 price 完全同码）。一条照着假设写的断言会有两种结局：
  要么它红（然后被人改宽，从此没人信它），要么它恰好对了（然后没人知道那是对的）。
  两种都不如先量一遍。（F5 旁边写了完整理由。）

运行：
    python test-price.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

RUN = str(int(time.time()))[-8:]
PREFIX = "pricetest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None


# ----------------------------------------------------------------------
# HTTP
#
# ★ 这里的 call() 比别个子脚本多返回一个【原始响应文本】。
#   理由是本轮有两条断言必须看文本，不能看解析后的对象：
#     · "grossMarginPercent":30.00 —— 解析成 float 之后 30.00 和 30.0 分不出来，
#       而这个字段的【小数位】正是「金额一律两位」这条约定的可见部分。
#     · "marketPrice" 这个键【在不在】—— 解析成 dict 之后
#       判断的是 key，而 key 判断用对象也对。真正需要文本的是上一条。
# ----------------------------------------------------------------------
def call(method, path, body=None, token=None, timeout=30, raw_body=None):
    """发一次请求，返回 (http状态码, 解析后的响应体, 原始响应文本)。

    ★ 解析失败时把原文塞进 {"_raw": ...} —— 有一半用例要的就是
      「这个请求该被拒绝」，失败路径的响应体正是被测对象。
    """
    headers = {"Accept": "application/json"}
    data = None
    if raw_body is not None:
        data = raw_body.encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, (json.loads(text) if text else None), text
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(text), text
        except Exception:
            return e.code, {"_raw": text}, text


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
# 数据库直连
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


# ----------------------------------------------------------------------
# 登录 & 清理
# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r, _ = call("POST", "/admin/auth/login",
                    {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(scalar("SELECT id FROM category ORDER BY id LIMIT 1"))


def cleanup():
    """删掉本脚本自己造的商品。

    ⚠️ 用【名字前缀】而不是 id 列表：本脚本中途会主动造十几件商品，
      而且拒绝路径（400/500）里会不会留下半截数据【正是被测对象】——
      所以清理必须能兜住「造了一半就崩了」的情况。
      前缀 pricetest + 时间戳是本脚本独有的，不会碰到真人数据
      （库里另有 100 件商品，名字里一个 pricetest 都没有）。

    ★ 顺序不能反：product_sku 没有外键，所以它必须排在商品之前。
      反了不报错，只会安静地攒孤儿行 —— junk_snapshot() 在盯着这个。
    """
    run_sql(f"DELETE FROM product_sku WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")
    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")


def junk_snapshot():
    """数一遍「本脚本有没有在库里留下无主行」。

    ★ 判据是「无主行」，不是「行数」：行数是会变的（真人也会录商品），
      无主行不会 —— 一个正常的库，孤儿永远是 0。
    """
    return (
        int(scalar(f"SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'")),
        int(scalar("SELECT COUNT(*) FROM product_sku s "
                   "LEFT JOIN product p ON p.id = s.product_id WHERE p.id IS NULL")),
    )


# ----------------------------------------------------------------------
# 数据准备
# ----------------------------------------------------------------------
def create(name, skus, spec_schema=None, status=1):
    return call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name, "status": status,
        "specSchema": spec_schema or [], "skus": skus,
    }, token=ADMIN_TOKEN)


def update(pid, name, skus, spec_schema=None, status=1):
    return call("PUT", f"/admin/products/{pid}", {
        "categoryId": CATEGORY_ID, "name": name, "status": status,
        "specSchema": spec_schema or [], "skus": skus,
    }, token=ADMIN_TOKEN)


def make(name, skus, spec_schema=None):
    """建一件商品，失败就退出（前置条件不满足时继续跑没意义）。"""
    st, r, _ = create(name, skus, spec_schema)
    if r.get("code") != 200:
        raise SystemExit(f"建商品失败（{name}）：HTTP {st} / {r}")
    return r["data"]


def admin_detail(pid):
    st, r, text = call("GET", f"/admin/products/{pid}", token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"查管理端详情失败：HTTP {st} / {r}")
    return r["data"], text


def sku_rows(pid):
    """直接看库里的 SKU 行：(id, spec_json, price, market_price, cost_price, stock)。"""
    return run_sql(f"SELECT id, spec_json, price, IFNULL(market_price,'NULL'), "
                   f"IFNULL(cost_price,'NULL'), stock FROM product_sku "
                   f"WHERE product_id = {pid} ORDER BY id")


def sku_ids(pid):
    return [int(r[0]) for r in sku_rows(pid)]


def shop_list(**params):
    qs = "&".join(f"{k}={quote(str(v))}" for k, v in params.items())
    return call("GET", f"/shop/products?{qs}")


def shop_find(pid, **params):
    """在商城列表里找出某一个商品那一行。查不到返回 None。

    ⚠️ ★★ 商城列表的搜索参数叫 <b>keyword</b>，管理端那个才叫 <b>name</b>。
       ★ 传错名字的后果是【静默】的：那个参数被无视，接口返回默认的第一页
         —— 不报错、不缺参数、HTTP 200。于是「找到了」这件事可能只是
         因为那件商品恰好排在前面。
       ★ 这个坑是在写本脚本时【真的踩到】的：第一次跑 C5a 传的是 name，
         回来的是一串跟测试无关的商品 id。修完参数名才是真的在查这两件。
         （顺手记一句：那次的 C5a 之所以报错，是因为它断言的是「集合相等」；
         如果它写的是「这两件都在列表里」，那次会【绿着放过去】。）
    """
    st, r, _ = shop_list(**params)
    if r.get("code") != 200:
        raise SystemExit(f"商城列表查询失败：HTTP {st} / {r}")
    for row in r["data"]["list"]:
        if row["id"] == pid:
            return row
    return None


def shop_detail(pid):
    return call("GET", f"/shop/products/{pid}")


# ----------------------------------------------------------------------
# 递归扫 JSON 里所有的键
# ----------------------------------------------------------------------
def walk_keys(node, path=""):
    """把一个 JSON 结构里【所有的】键展平成 (路径, 键名)。

    ★ 为什么要递归，而不是只扫第一层：
      「成本价不许进用户端」这句话管的是【响应里任何一个位置】——
      DTO 嵌套一层（product.skus[0].costPrice）照样是泄漏，
      而且那正是它最可能的藏法。只扫顶层的话，这个脚本会绿着放它过去。
    """
    out = []
    if isinstance(node, dict):
        for k, v in node.items():
            p = f"{path}.{k}"
            out.append((p, k))
            out.extend(walk_keys(v, p))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            out.extend(walk_keys(v, f"{path}[{i}]"))
    return out


def leak_hits(name, body):
    """响应里所有「键名（不区分大小写）含 cost 或 margin」的位置。"""
    return [f"{name} → {p}" for p, k in walk_keys(body)
            if "cost" in k.lower() or "margin" in k.lower()]


# ======================================================================
# A · 结构
# ======================================================================
def group_a():
    section("A · 结构：两列的形状、迁移没发明数据、product 上仍然没有价格")

    rows = run_sql(
        "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, ORDINAL_POSITION "
        "FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku' "
        "  AND COLUMN_NAME IN ('market_price', 'cost_price') "
        "ORDER BY COLUMN_NAME")
    check("A1 两列都存在，都是 DECIMAL(10,2)，都允许 NULL",
          len(rows) == 2
          and all(r[1] == "decimal(10,2)" and r[2] == "YES" for r in rows),
          f"information_schema 查到：{rows}")

    # ---- 列顺序：market_price 紧跟 price，cost_price 紧跟 market_price ----
    # ★ 这条看着像洁癖，其实是 migration-14b 里那两句 AFTER 的可执行版本：
    #   「新列挨着它参照的那一列」是给人看的（SHOW CREATE TABLE 是第一个
    #   会被人打开的文档），而 AFTER 写错了不会有任何报错 ——
    #   列会在末尾，功能完全正常，只是读表的人要滚到最后才发现它。
    pos = {r[0]: int(r[1]) for r in run_sql(
        "SELECT COLUMN_NAME, ORDINAL_POSITION FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product_sku' "
        "  AND COLUMN_NAME IN ('price', 'market_price', 'cost_price', 'stock')")}
    check("A2 列顺序是 price → market_price → cost_price → stock（migration-14b 的 AFTER 生效了）",
          len(pos) == 4 and pos.get("market_price") == pos.get("price", 0) + 1
          and pos.get("cost_price") == pos.get("market_price", 0) + 1
          and pos.get("stock") == pos.get("cost_price", 0) + 1,
          f"实际位置：{pos}")

    # ---- ★ 迁移没有发明数据 ----
    #
    # ★★ 这条【在跑着 tools/fixture-price.py 的时候会红】，而那是故意的：
    #    夹具的本职就是造出非 NULL 的两列。所以它的意思是
    #    「此刻库里没有划线价/成本价」而不是「这个功能坏了」。
    #    一条会因为夹具而红的检查，比一条永远绿的检查有价值 ——
    #    后者只能证明「这个脚本自己没崩」。
    leaked = int(scalar("SELECT COUNT(*) FROM product_sku "
                        "WHERE market_price IS NOT NULL OR cost_price IS NOT NULL") or 0)
    check("A3 ★ 全库没有任何一行有划线价/成本价（迁移只加列，没有回填发明出来的数据）",
          leaked == 0,
          f"{leaked} 行有值 —— 如果是 tools/fixture-price.py 造的，先 --cleanup 再跑本脚本")

    # ---- 用户手工录入的那 5 件商品 ----
    #
    # ★ 上面那条是全局的，这一条是【具体到人】的：id 9/28/29/75/76 是用户
    #   自己录进去的真实商品，给它们编一个成本价是【发明数据】。
    #   两条一起看才是完整的意思：迁移没有回填任何一行，尤其是没有回填那 5 行。
    hand = run_sql(
        "SELECT p.id, p.name, s.price, IFNULL(s.market_price,'NULL'), "
        "IFNULL(s.cost_price,'NULL') FROM product p "
        "JOIN product_sku s ON s.product_id = p.id "
        "WHERE p.id IN (9, 28, 29, 75, 76) ORDER BY p.id")
    check("A4 ★ 用户手工录入的 5 件商品（9/28/29/75/76）两列都还是 NULL",
          {r[0] for r in hand} == {"9", "28", "29", "75", "76"}
          and all(r[3] == "NULL" and r[4] == "NULL" for r in hand),
          f"实际：{hand}")

    # ---- 15 轮不变量：product 上没有价格 ----
    cols = run_sql(
        "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'product' "
        "  AND COLUMN_NAME IN ('price', 'stock', 'market_price', 'cost_price', "
        "                      'min_price', 'max_price')")
    check("A5 ★ product 上仍然没有任何价格列（里程碑 15 的不变量，本轮没有偷偷加回来）",
          len(cols) == 0, f"还存在：{[c[0] for c in cols]}")


# ======================================================================
# B · 划线价
# ======================================================================
def group_b(p_single):
    section("B · 划线价：保存校验、舍入陷阱、以及商城列表上那把 sku_count = 1 的锁")

    rows = sku_rows(p_single)
    check("B1 单规格商品 price=100 / marketPrice=150 存进去了",
          len(rows) == 1 and float(rows[0][2]) == 100.00 and float(rows[0][3]) == 150.00,
          f"库里：{rows}")

    # ---- B2 低于售价：401 拒掉，而且一件商品都不留 ----
    bad_name = f"{TAG}-划线价低于售价"
    st, r, _ = create(bad_name, [
        {"specs": [], "price": 100.00, "marketPrice": 80.00, "stock": 10}])
    check("B2a 划线价 80 < 售价 100 → 业务码 400",
          r.get("code") == 400, f"HTTP {st} / {r}")
    check("B2b 而且报错里说得清是哪个数不对",
          "划线价" in str(r.get("message", "")) and "售价" in str(r.get("message", "")),
          f"实际提示：{r.get('message')!r}")
    check("B2c ★ 库里没有留下这件商品（拒绝 == 什么都没发生，不是删一半）",
          int(scalar(f"SELECT COUNT(*) FROM product WHERE name = '{bad_name}'") or 0) == 0,
          "400 之后库里多了一件商品 —— 那说明校验发生得太晚，事务没兜住")

    # ---- B3 改的时候也一样拒，而且【老值不动】 ----
    #
    # ★ 这条和 B2 不是同一件事：B2 是「插入路径」，这条是「替换路径」。
    #   replaceSkus 是【全量覆盖】，所以它失败时最危险的 symptom 是
    #   「删了旧的、没写进新的」—— 接口 400，而商城里那件商品的划线价
    #   凭空消失了。所以这里必须回头【读库】确认老值还在。
    st, r, _ = update(p_single, f"{TAG}-单规格", [
        {"specs": [], "price": 100.00, "marketPrice": 80.00, "stock": 10}])
    check("B3a 改成 划线价 80 < 售价 100 → 业务码 400", r.get("code") == 400,
          f"HTTP {st} / {r}")
    rows = sku_rows(p_single)
    check("B3b ★★ 库里那条 SKU 的划线价【还是 150.00】（全量覆盖失败时不能删一半）",
          len(rows) == 1 and rows[0][3] == "150.00" and float(rows[0][2]) == 100.00,
          f"库里现在是：{rows}")

    # ---- B4 等于售价也不行 ----
    st, r, _ = create(f"{TAG}-划线价等于售价", [
        {"specs": [], "price": 100.00, "marketPrice": 100.00, "stock": 10}])
    check("B4 划线价 == 售价 → 业务码 400（展示规则是【严格大于】）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    # ---- B5 ★★ 舍入陷阱：校验和存储必须用同一把尺子 ----
    #
    # ★ 这是本会话里被【实测】抓出来的一个后门：
    #   Java 用原始值比较「100.004 > 100.00」→ 真；MySQL 存储时按
    #   DECIMAL(10,2) 舍入成 100.00；前端那条展示规则又是「严格大于」→ 假。
    #   于是这个划线价【存在库里、谁也看不见、两端都不报错】——
    #   正是 B2 想消灭的那个静默失败，绕了一圈从后门回来了。
    #   ProductServiceImpl.planSkus 里那句 setScale(MONEY_SCALE, HALF_UP)
    #   就是补这个洞的，这两条用例是它的回归测试。
    st, r, _ = create(f"{TAG}-舍入陷阱-100004", [
        {"specs": [], "price": 100.00, "marketPrice": 100.004, "stock": 10}])
    check("B5a ★★ 100.004 舍入后是 100.00，不满足「严格大于」→ 业务码 400",
          r.get("code") == 400, f"HTTP {st} / {r} —— 放它进去就会变成一个看不见的划线价")
    check("B5b 提示里显示的是【舍入后】的数（100.00），因为那才是比较用的那个值",
          "100.00" in str(r.get("message", "")),
          f"实际提示：{r.get('message')!r}")

    st, r, _ = create(f"{TAG}-舍入陷阱-100005", [
        {"specs": [], "price": 100.00, "marketPrice": 100.005, "stock": 10}])
    check("B5c 100.005 舍入后是 100.01（HALF_UP 进位）→ 通过",
          r.get("code") == 200, f"HTTP {st} / {r}")
    if r.get("code") == 200:
        rows = sku_rows(r["data"])
        check("B5d 而且库里存的确实是 100.01（Java 的舍入和 MySQL 的存储是同一把尺子）",
              len(rows) == 1 and rows[0][3] == "100.01",
              f"库里：{rows} —— 两边舍入不一致的话，校验通过的那个数在库里是另一个数")

    # ---- B6 单规格商品：商城列表【有】marketPrice ----
    row = shop_find(p_single, keyword=f"{TAG}-单规格")
    check("B6 单规格商品的列表行有 marketPrice，且等于那条 SKU 的原价",
          row is not None and float(row.get("marketPrice", -1)) == 150.00,
          f"实际行：{row}")


# ======================================================================
# C · 价格区间
# ======================================================================
def group_c(p_wide, p_single, p_sort):
    section("C · 价格区间：minPrice / maxPrice 的两种来源，和那条「排序 == 显示」的旧不变量")

    d, _ = admin_detail(p_wide)
    sql = run_sql(f"SELECT MIN(price), MAX(price) FROM product_sku "
                  f"WHERE product_id = {p_wide}")[0]
    check("C1a 管理端详情 minPrice == MIN(price)（100）",
          float(d.get("minPrice", -1)) == float(sql[0]) == 100.00,
          f"接口 {d.get('minPrice')!r} vs SQL {sql[0]}")
    check("C1b ★ 管理端详情 maxPrice == MAX(price)（300）",
          float(d.get("maxPrice", -1)) == float(sql[1]) == 300.00,
          f"接口 {d.get('maxPrice')!r} vs SQL {sql[1]}")

    row = shop_find(p_wide, keyword=f"{TAG}-区间-三档")
    check("C2 商城列表行给的也是 100 / 300（SQL 聚合那一份）",
          row is not None and (float(row["minPrice"]), float(row["maxPrice"])) == (100.00, 300.00),
          f"实际行：{row}")

    st, r, _ = shop_detail(p_wide)
    check("C3 ★ 商城【详情】的 maxPrice == max(skus[].price)（Java 算的那一份）",
          r.get("code") == 200
          and float(r["data"]["maxPrice"]) == max(float(s["price"]) for s in r["data"]["skus"]) == 300.00,
          f"实际 {r.get('data', {}).get('maxPrice')!r} vs skus "
          f"{[s['price'] for s in (r.get('data') or {}).get('skus', [])]}")

    d2, _ = admin_detail(p_single)
    check("C4 单规格商品 minPrice == maxPrice（前端据此不画那个波浪号）",
          float(d2.get("minPrice", -1)) == float(d2.get("maxPrice", -2)) == 100.00,
          f"min={d2.get('minPrice')!r} max={d2.get('maxPrice')!r}")

    # ---- ★★ C5 排序：这是本轮最容易悄悄坏掉的一条旧不变量 ----
    #
    # ★ 里程碑 15 立的规矩是「排序用的价格必须等于列表上显示的价格」。
    #   本轮同时动了【价格取法】（加了 MAX）和【列表显示】（加了区间），
    #   正是破坏它的高危时刻。
    #
    #   ★ 这条断言的构造值得说一句：两件商品
    #       三档的那件（p_wide）  min=100 max=300
    #       单档的那件（p_sort）  min=200 max=200
    #     按 MIN 排 → 三档在前；按 MAX 排 → 单档在前。
    #     所以「谁在前」这一个观察就区分开了两种实现 ——
    #     如果只造一件商品、只断言「起售价等于 100」，两种实现都会绿。
    #
    #   ⚠️ 单档那件【不能用 p_single】去比：它的价格是 100，
    #      和三档那件的 MIN 相等 —— 相等时 ORDER BY 会落到第二排序键
    #      (p.id DESC) 上，于是这条断言测的就成了 id 顺序。所以专门造了
    #      一件 200 的（p_sort）。**造对照件时，价格必须真的能区分开。**
    st, r, _ = shop_list(keyword=f"{TAG}-区间", sort="price_asc")
    rows = r.get("data", {}).get("list", []) if r.get("code") == 200 else []
    ids = [x["id"] for x in rows]
    check("C5a 两件对照商品都回来了（多一件少一件都会让下面两条失去意义）",
          set(ids) == {p_wide, p_sort}, f"实际 {ids} —— 期望 {[p_wide, p_sort]}")
    check("C5b ★★ sort=price_asc 按 MIN(price) 排（起售价 100 的那件在 200 的前面）",
          len(ids) == 2 and ids.index(p_wide) < ids.index(p_sort),
          f"实际顺序 {ids} —— 反了说明有人把 a.min_price 改成了 a.max_price，"
          f"而列表上显示的仍然是 minPrice，用户会看到 [200, 100]")
    prices = [float(x["minPrice"]) for x in rows]
    check("C5c ★ 返回的顺序按 minPrice 单调不减（排序依据 == 页面显示的那个数）",
          prices == sorted(prices), f"实际 minPrice 序列 {prices}")


# ======================================================================
# D · 成本与毛利
# ======================================================================
def group_d():
    section("D · 成本与毛利：只在管理端详情，且「毛利 0」和「没设成本」必须分得开")

    p_seven = make(f"{TAG}-毛利-七成", [
        {"specs": [], "price": 100.00, "costPrice": 70.00, "stock": 5}])
    p_zero = make(f"{TAG}-毛利-零", [
        {"specs": [], "price": 100.00, "costPrice": 100.00, "stock": 5}])
    p_none = make(f"{TAG}-毛利-未设", [
        {"specs": [], "price": 100.00, "stock": 5}])
    p_round = make(f"{TAG}-毛利-除不尽", [
        {"specs": [], "price": 333.33, "costPrice": 100.00, "stock": 5}])

    d, text = admin_detail(p_seven)
    sku = d["skus"][0]
    check("D1a 管理端详情的 SKU 上三个字段都在",
          all(k in sku for k in ("costPrice", "grossMargin", "grossMarginPercent")),
          f"实际键：{sorted(sku.keys())}")
    check("D1b price=100 / cost=70 → 毛利 30.00、毛利率 30.00",
          float(sku["grossMargin"]) == 30.00 and float(sku["grossMarginPercent"]) == 30.00,
          f"实际 {sku.get('grossMargin')!r} / {sku.get('grossMarginPercent')!r}")
    check("D1c ★ 原始 JSON 文本里是 30.00（两位小数是这条约定的一部分，解析成 float 就看不见了）",
          '"grossMarginPercent":30.00' in text and '"grossMargin":30.00' in text,
          "响应文本里没找到 「\"grossMarginPercent\":30.00」 —— 小数位不是两位？")

    # ---- D2 「没设成本」：三个键整个消失 ----
    #
    # ★ 这里断言的是 key 【不在】，而不是值为 null：
    #   application.yml 里配了 non_null，null 字段不参与序列化。
    #   写 "sku.get('costPrice') is None" 也会绿（缺的键读出来就是 None），
    #   但它分不清「键没了」和「键在、值是 null」—— 而前端要按前者写代码
    #   （用宽松真值判断，不要写成 === null）。
    d, _ = admin_detail(p_none)
    sku = d["skus"][0]
    check("D2 ★ 没设成本价时三个键从 JSON 里整个消失（前端据此渲染「未设置」）",
          not any(k in sku for k in ("costPrice", "grossMargin", "grossMarginPercent")),
          f"实际键：{sorted(sku.keys())}")

    # ---- D3 ★★ 成本 == 售价：毛利是 0.00，键必须在 ----
    #
    # ★ 这条是「前端用宽松真值判断」那条约定的【例外】，而例外必须有人守着：
    #   前端如果写 v-if="sku.grossMargin"，0 会被当成假 →
    #   表格把「毛利 0.00」显示成「未设置」。两件事完全不同：
    #   一个是「这个规格不赚钱」，一个是「这件商品没填成本」。
    #   ⚠️ 后端这边能守的只有「键必须在」这一半，前端那一半由
    #      sql/test-frontend-format.py 和 mall-web 的实现守着。
    d, text = admin_detail(p_zero)
    sku = d["skus"][0]
    check("D3a ★★ 成本 == 售价 → 毛利是 0.00，而且三个键【都在】（不是消失）",
          all(k in sku for k in ("costPrice", "grossMargin", "grossMarginPercent")),
          f"实际键：{sorted(sku.keys())} —— 键没了的话，前端会把「不赚钱」显示成「没填成本」")
    check("D3b 毛利 0.00 / 毛利率 0.00",
          float(sku["grossMargin"]) == 0.0 and float(sku["grossMarginPercent"]) == 0.0,
          f"实际 {sku.get('grossMargin')!r} / {sku.get('grossMarginPercent')!r}")
    check("D3c 原始 JSON 文本里也是 0.00（不是被 non_null 吃掉的 null）",
          '"grossMarginPercent":0.00' in text,
          "响应文本里没找到 「\"grossMarginPercent\":0.00」")

    # ---- D4 除不尽：毛利率只保留两位 ----
    d, _ = admin_detail(p_round)
    sku = d["skus"][0]
    check("D4 price=333.33 / cost=100 → 毛利 233.33、毛利率 70.00（不是 69.9997…）",
          float(sku["grossMargin"]) == 233.33 and float(sku["grossMarginPercent"]) == 70.00,
          f"实际 {sku.get('grossMargin')!r} / {sku.get('grossMarginPercent')!r}")

    # ---- D5 除零的前置条件：价格不允许是 0 ----
    #
    # ★ AdminSkuVO 里那句「除以 price 之前仍然判零」看着像多余 ——
    #   因为 price 的校验是 @DecimalMin("0.01")，运行时到不了 0。
    #   ⚠️ 但那是【另一个类的规则】：校验在 SkuSaveDTO 上，除法在 VO 上，
    #      两处唯一的联系方式就是这条断言。校验哪天放松了，这条会先红。
    st, r, _ = create(f"{TAG}-零价", [{"specs": [], "price": 0, "stock": 1}])
    check("D5 售价 0 → 业务码 400（AdminSkuVO 里那句判零因此是防御，不是死代码）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    # ---- D6 成本/毛利【不在】管理端列表上 ----
    #
    # ★ 判据是「跨规格的成本汇总没有唯一答案」：同一件商品的 4 个规格
    #   进价各不相同，列表上写「成本 200」是在替商家做一个他没做的加法。
    #   那是「经营看板」那一轮的事。
    st, r, _ = call("GET", f"/admin/products?name={quote(TAG + '-毛利')}&pageSize=20",
                    token=ADMIN_TOKEN)
    rows = (r.get("data") or {}).get("list", [])
    check("D6 管理端【列表】行里没有 costPrice / grossMargin（只有详情有）",
          len(rows) > 0 and not any(
              k in x for x in rows for k in ("costPrice", "grossMargin", "grossMarginPercent")),
          f"实际列表行的键：{sorted(rows[0].keys()) if rows else '一条都没有'}")

    return p_seven


# ======================================================================
# E · ★★ 匿名泄漏扫描（双向）
# ======================================================================
def group_e(p_wide, p_single, p_cost):
    section("E · ★★ 匿名泄漏扫描（双向：用户端一个都不许有、管理端必须有）")

    probes = []
    st, r, _ = shop_list(keyword=TAG)
    probes.append(("/shop/products（列表，匿名）", r))
    for pid, tag in ((p_wide, "多规格"), (p_single, "单规格"), (p_cost, "带成本价")):
        st, r, _ = shop_detail(pid)
        probes.append((f"/shop/products/{pid}（{tag}，匿名）", r))
    for sku in sku_ids(p_cost):
        st, r, _ = call("GET", f"/shop/skus/{sku}")
        probes.append((f"/shop/skus/{sku}（匿名）", r))

    all_keys = [(p, k) for name, body in probes for p, k in walk_keys(body)]
    key_names = {k.lower() for _, k in all_keys}

    # ---- E0 ★ 反空转守卫 ----
    #
    # ★★ 没有这一条的话，下面那条「一个都没有」在【扫描器坏了】的时候也会绿：
    #    比如接口返回了 401（未登录）→ body 里没有键 → 「没有 cost」成立。
    #    一个空转的检查比没有检查更危险，因为它看起来是绿的。
    #    判据就是「扫描器确实读到了它该读到的东西」：price、marketPrice。
    check("E0 ★ 反空转守卫：扫描器确实读到了商品数据（键名里有 price 和 marketPrice）",
          "price" in key_names and "marketprice" in key_names and len(all_keys) > 100,
          f"扫了 {len(probes)} 个响应、{len(all_keys)} 个键；键名样本 "
          f"{sorted(key_names)[:12]} —— 没有 price 说明扫描拿到的是空对象/错误响应，"
          f"那么 E1 是【空转通过】的")

    hits = [h for name, body in probes for h in leak_hits(name, body)]
    check("E1 ★★ 用户端三个接口的响应里，没有任何一个键名含 cost 或 margin",
          len(hits) == 0,
          f"泄漏位置：{hits[:5]} —— ★ 最可能的改法是有人把 costPrice 加到了父类 "
          f"SkuVO 上，那样 ShopSkuVO 会立刻把它带出去")

    note("扫描允许 marketPrice 存在：划线价是给人看的，本来就该在用户端出现。")
    note(f"本次扫到的键名总数 {len(all_keys)}，其中 price/marketPrice 都在 —— "
         f"所以上面那条 E1 是真的在扫东西。")

    # ---- E2 ★★ 反向断言：管理端【必须】有 ----
    #
    # ★ 为什么反向的这一半不能省：
    #   如果哪天 AdminSkuVO 忘了在 of() 里补成本字段（fill() 是公共字段的
    #   搬运工，管不到子类字段），字段会恒 null → non_null 让它们整个消失 →
    #   接口 200、管理端页面少一列。而那时 E1 依然是绿的：
    #   用户端「没有成本字段」这件事在成本字段【根本不存在】时当然成立。
    #   ★★ 一个只会说「没有」的检查，必须配一条会说「有」的检查。
    d, _ = admin_detail(p_cost)
    skus = d.get("skus") or []
    missing = [s.get("id") for s in skus
               if not all(k in s for k in ("costPrice", "grossMargin", "grossMarginPercent"))]
    check("E2 ★★ 管理端详情里每个 SKU 【必须】有成本三件套（这条是 E1 的反向对照）",
          len(skus) > 0 and not missing,
          f"缺字段的 SKU：{missing} —— 缺了的话 E1 会因为「字段压根不存在」而空转通过")


# ======================================================================
# F · 参数校验（★ 断言的是【实测】行为，不是计划里的假设）
# ======================================================================
def group_f():
    section("F · 参数校验：拒绝的方式和错误码（下面每条都是量出来的，不是猜的）")

    before = int(scalar(f"SELECT COUNT(*) FROM product WHERE name LIKE '{TAG}%'") or 0)

    st, r, _ = create(f"{TAG}-负划线价", [
        {"specs": [], "price": 100.00, "marketPrice": -1, "stock": 1}])
    check("F1 划线价 -1 → 业务码 400（负数当然不满足「严格大于售价」）",
          r.get("code") == 400 and "划线价" in str(r.get("message", "")),
          f"HTTP {st} / {r}")

    st, r, _ = create(f"{TAG}-负成本价", [
        {"specs": [], "price": 100.00, "costPrice": -1, "stock": 1}])
    check("F2 成本价 -1 → 业务码 400 + 字段级提示（@DecimalMin 在 DTO 上）",
          r.get("code") == 400 and "成本价" in str(r.get("message", "")),
          f"HTTP {st} / {r}")

    st, r, _ = create(f"{TAG}-字符串划线价", [
        {"specs": [], "price": 100.00, "marketPrice": "abc", "stock": 1}])
    check("F3 划线价 = \"abc\" → 【真的 HTTP 400】（反序列化就失败了，业务层没被调用）",
          st == 400, f"HTTP {st} / {r}")

    st, r, _ = create(f"{TAG}-字符串售价", [{"specs": [], "price": "abc", "stock": 1}])
    check("F4 售价 = \"abc\" → 同样是真的 HTTP 400（两者走同一条路，不是两套规则）",
          st == 400, f"HTTP {st} / {r}")

    # ---- F5 ★★ 超范围：和 price 同码（HTTP 200 + body.code 500）----
    #
    # ★★ 这一条【和计划里写的不一样】，而实测优先：
    #   计划里假设的是「超出 DECIMAL(10,2) 范围 → 400」。实测下来
    #   price 和 marketPrice 都是 HTTP 200 + body.code 500
    #   （「系统繁忙，请稍后重试」）—— 原因是这个值能通过所有业务校验
    #   （99999999999 > 100 成立），一路走到 MySQL，由 DECIMAL 范围报错，
    #   最后被全局异常处理器兜成 500。
    #
    #   ★ 为什么【认下这个行为】而不是去补一个 400：
    #     它和 price 现在的行为【完全一致】。给 marketPrice 单独补一条
    #     范围校验，就出现两套规则：同一个越界值在 price 上是 500、
    #     在 marketPrice 上是 400 —— 而这轮没有任何理由让它们不一样。
    #   ★ 而且 500 是【吵的】：它进了日志、有个明确的堆栈。
    #     本轮真正要防的是【静】的失败（B5 那个看不见的划线价）。
    st, r, _ = create(f"{TAG}-超范围划线价", [
        {"specs": [], "price": 100.00, "marketPrice": 99999999999, "stock": 1}])
    check("F5a 划线价 99999999999（超出 DECIMAL(10,2)）→ body.code 500",
          r.get("code") == 500, f"HTTP {st} / {r}")

    st, r, _ = create(f"{TAG}-超范围售价", [{"specs": [], "price": 99999999999, "stock": 1}])
    check("F5b ★ 售价越界也是 500 —— 两者同码（这条比 500 本身重要：它证明没有两套规则）",
          r.get("code") == 500, f"HTTP {st} / {r}")
    note("★ 所以「越界是 500 而不是 400」是本轮【认下的】行为，和 price 一致，"
         "而且它是吵的（进日志、有堆栈）。")

    # ---- F6 ★ 所有被拒绝的请求，一件商品都不许留下 ----
    #
    # ★ 上面八条用例全都在问「拒绝的方式对不对」，没有一条在问
    #   「拒绝了之后库里多了什么」。而「接口返回 400 + 库里留下半件商品」
    #   是完全可能的 —— 那正是这条在看的。
    after = int(scalar(f"SELECT COUNT(*) FROM product WHERE name LIKE '{TAG}%'") or 0)
    check("F6 ★★ 上面这些被拒绝的请求，一件商品都没往库里落",
          after == before,
          f"跑之前 {before} 件 → 跑之后 {after} 件 —— 多了的话说明校验发生在写之后，"
          f"事务没兜住")


def main():
    print("里程碑 16 · 价格体系测试")
    print(f"本轮标签：{TAG}")

    # ★ 先清理上一次跑崩留下的残留，再建本轮要用的东西。
    #   顺序反了的话，下一次的清理会把这一次刚建的也删掉。
    cleanup()
    admin_login()
    junk_before = junk_snapshot()

    # ★★ A 组必须【第一个】跑：它有一条断言是「全库还没有任何一行两列有值」。
    #    只要先建了一件带划线价的商品，那条就会红 —— 而它会红的理由
    #    和「迁移做错了」这件事毫无关系。**断言的执行顺序是它语义的一部分。**
    group_a()

    p_single = make(f"{TAG}-单规格", [
        {"specs": [], "price": 100.00, "marketPrice": 150.00, "stock": 10}])
    group_b(p_single)

    # 三档 100/200/300，每档各带一个【属于自己的】原价 150/250/350。
    # ★ 后一个数是【行内】的：它和同一行的 price 配对，不是跨行的 MIN。
    #   多规格商品的列表上那个商品级 marketPrice 因此【整个消失】（B7/C6），
    #   而这正是要测的那把锁。
    TIERS = [("甲", 100.00, 150.00), ("乙", 200.00, 250.00), ("丙", 300.00, 350.00)]
    p_wide = make(f"{TAG}-区间-三档",
                  [{"specs": [{"name": "档位", "value": v}], "price": p,
                    "marketPrice": m, "stock": 4} for v, p, m in TIERS],
                  spec_schema=[{"name": "档位", "values": [v for v, _, _ in TIERS]}])
    p_sort = make(f"{TAG}-区间-单档", [{"specs": [], "price": 200.00, "stock": 4}])

    group_c(p_wide, p_single, p_sort)

    # ---- B7 ★ 多规格商品上那把 sku_count = 1 的锁 ----
    section("B7 · ★ 多规格商品：列表上【没有】商品级划线价，但每一行都有")
    rows = sku_rows(p_wide)
    check("B7a 库里三行的 market_price 都真的存着（150/250/350）",
          [r[3] for r in rows] == ["150.00", "250.00", "350.00"],
          f"库里：{[r[3] for r in rows]} —— 没有值的话，下面是空转通过")

    row = shop_find(p_wide, keyword=f"{TAG}-区间-三档")
    check("B7b ★★ 多规格商品的列表行里【没有】marketPrice 这个键（不是 null，是不存在）",
          row is not None and "marketPrice" not in row,
          f"实际行：{row} —— 有了它就会出现「¥100 ~~¥350~~」这种【不存在的折扣】"
          f"（100 是甲档的售价、350 是丙档的原价，两个数从不属于同一个规格）")

    st, r, _ = shop_detail(p_wide)
    skus = r["data"]["skus"]
    check("B7c ★ 详情【也不是】商品级给一个划线价（ShopProductDetailVO 刻意没有这个字段）",
          "marketPrice" not in r["data"],
          f"详情顶层有 marketPrice：{r['data'].get('marketPrice')!r}")
    check("B7d ★★ 但每一个 SKU 各自带着自己那一行的 marketPrice（详情页敢问「哪个规格」）",
          len(skus) == 3 and all(float(s["marketPrice"]) == float(s["price"]) + 50 for s in skus),
          f"实际：{[(s['specText'], s['price'], s.get('marketPrice')) for s in skus]}")

    p_cost = group_d()
    group_e(p_wide, p_single, p_cost)
    group_f()

    cleanup()

    # ★★ 最后这条断言才是「清理逻辑对不对」的判据。
    #    上面所有用例都在问「接口做了它该做的事」，
    #    这一条在问「脚本自己有没有留下垃圾」。
    junk_after = junk_snapshot()
    check("★★ 跑完之后库里没有多出任何无主行（残留商品 / 无主 SKU）",
          junk_after == junk_before,
          f"跑之前 {junk_before} → 跑之后 {junk_after}"
          f"（顺序是 残留商品/无主SKU/无主SKU或测试商品）—— "
          f"对不上说明 cleanup() 漏了一层，而【脚本自己不会报错】")

    section("本脚本的覆盖边界（★ 全绿不等于覆盖全了）")
    note("下面这些【不是忘了写】，是本轮明确不做或结构性测不到的：")
    note("  · 会员等级价 / 秒杀价 / 活动价 —— 用户已明确推迟（见 README 已知取舍）。")
    note("  · 订单上的划线价快照 —— 订单记的是「你付了多少」，划线价是展示。")
    note("  · 购物车 / 结算页的划线价 —— 同理，那两个页面只该出现应付金额。")
    note("  · 管理端列表的成本/毛利列 —— 跨规格的汇总没有唯一答案，是「经营看板」那一轮的事。")
    note("  · 前端的显示规则（marketPrice > price 才画删除线）—— 这是后端脚本，")
    note("    它只能证明「数给对了」，证明不了「画对了」。那一半在 mall-shop 的")
    note("    Home.vue / ProductDetail.vue 和 sql/test-frontend-format.py 那边。")
    note("  ★ A3 那条会在 tools/fixture-price.py 活着的时候红，而那是故意的 ——")
    note("    夹具的本职就是造出非 NULL 的两列。")

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
