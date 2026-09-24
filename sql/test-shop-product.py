# -*- coding: utf-8 -*-
"""
里程碑 6 测试：用户端商品浏览（列表 / 详情 / 搜索 / 分类筛选 / 排序 / 分页）

这组用例的重点不是「接口能不能返回数据」，而是三条业务规则有没有被守住：

  1. ★ 游客不登录也能浏览（excludePathPatterns 生效）
  2. ★★ 下架商品对用户端【完全不可见】—— 列表里没有，详情也打不开
  3. ★ 查询参数传脏值不会出事（pageSize 封顶、sort 白名单、空关键词）

第 2 条是这组测试的核心，所以它配了【对照组】：
先确认管理端能看到这个下架商品（证明它真的存在），
再去用户端确认看不到。没有对照组的话，「看不到」有可能只是商品根本没建成功。

运行：
    python test-shop-product.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

# 每次运行用一个不同的后缀，避免和上次残留的数据撞车。
# 用「时间戳后 8 位」是因为它够短（搜索关键词不至于太长）又几乎不会重复。
RUN = str(int(time.time()))[-8:]
PREFIX = "sptest"          # 测试商品名统一前缀，方便清理
TAG = f"{PREFIX}{RUN}"     # 本次运行的唯一标记，会出现在商品名里

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None


# ----------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------
def call(method, path, body=None, token=None, raw=None):
    """发一个请求，返回 (HTTP状态码, 解析后的 body 或 None)。

    raw 不为 None 时直接把它当请求体发出去（用来测「不是合法 JSON」的情况），
    这时候忽略 body 参数。
    """
    url = BASE + path
    headers = {"Accept": "application/json"}
    data = None
    if raw is not None:
        data = raw
        headers["Content-Type"] = "application/json"
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
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


# ----------------------------------------------------------------------
# 直连数据库：只用来做「清理」和「事后核对」
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


def admin_login():
    global ADMIN_TOKEN
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败，后续用例无法进行：HTTP {st} / {r}")


# ----------------------------------------------------------------------
# 数据准备
# ----------------------------------------------------------------------
def cleanup():
    """删掉本次以及历史运行残留的测试商品。

    走数据库而不是走接口，是因为接口删不干净时（比如脚本中途崩了）
    反而更难收拾。这是测试脚本里少数可以「绕过业务层」的地方。

    ★ 里程碑 11：删商品之前先删它的图集行，顺序不能反。
    ★ 里程碑 12：再多两层 —— 晒图（孙）→ 评价（子）→ 商品（父）。
      本脚本不产生评价，所以今天删的是 0 行；加它的理由和里程碑 11
      加图集那一行完全一样，见下面那段。

    ⚠️ 这个脚本建的测试商品【没有图集】，所以今天这一步删的是 0 行。
    那为什么还要加？因为「顺序正确」比「这次侥幸没出事」重要：

      product_image 和 order_item 一样【没有外键】（全库约定，
      见 mall.sql 里那段说明）。没有外键，数据库就不会拦住
      「商品没了但图集行还在」—— 留下的是一批永远查不到、
      也永远删不掉的孤儿行。

    今天这个脚本不传 images，所以没事。但哪天有人在 create_product
    里顺手加了个 images 参数（图集测试挪过来一点是很自然的事），
    这里的顺序错了就会开始攒孤儿行，而且【不会有任何报错】。
    一行 DELETE 换掉这个隐患，很划算。
    """
    run_sql(f"DELETE FROM product_image WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")

    # ★ 里程碑 12：评价和晒图也要在商品之前删。
    #   理由和上面那段（图集）一字不差 —— product_review 和
    #   product_review_image 同样【没有外键】，顺序错了不报错、只留孤儿行。
    #   而且评价比图集更严重一层：它带着 member_id 和 rating，
    #   孤儿评价会是「查不到商品、但看得见评分」的幽灵数据。
    #   晒图（孙）必须在评价（子）之前。
    run_sql(f"DELETE FROM product_review_image WHERE review_id IN "
            f"(SELECT id FROM product_review WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%'))")
    run_sql(f"DELETE FROM product_review WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")

    # ★ 里程碑 15：product_sku 也【没有外键】，所以它同样必须排在商品之前。
    #   顺序错了不报错，只会留下一批「商品没了但 SKU 行还在」的孤儿 ——
    #   和上面图集/评价那两段一模一样的道理。
    run_sql(f"DELETE FROM product_sku WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")

    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")


def create_product(name, category_id, price, stock, status, description=""):
    """用管理端接口建商品，返回新商品的 id。"""
    # 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品也要显式给一条
    # 「默认 SKU」（specs 为空数组），后端拿它的 price/stock 作为这件商品的价格和库存。
    st, r = call("POST", "/admin/products", {
        "categoryId": category_id,
        "name": name,
        "specSchema": [],
        "skus": [{"specs": [], "price": price, "stock": stock}],
        "description": description,
        "status": status,
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    return r["data"]


def shop_list(**params):
    """调用户端列表接口，不带 token（游客视角）。

    必须用 quote 做百分号编码，不能手工拼 "k=v&k=v"。
    URL 里只能出现 ASCII 字符，中文关键词原样拼进去会直接抛
    UnicodeEncodeError（在 http.client 里，还没发出去就炸了）。

    浏览器和 axios 会自动帮你编码，所以这个问题在页面上永远看不到 ——
    只有自己拼 URL 的时候才会撞上。记住：**凡是拼 URL，就要编码。**
    """
    qs = urlencode(params)
    return call("GET", f"/shop/products?{qs}" if qs else "/shop/products")


def ids_of(page):
    return [p["id"] for p in (page.get("list") or [])]


# ======================================================================
def main():
    admin_login()

    print()
    print(f"本次运行标记 TAG = {TAG}")

    section("0. 准备数据（全部通过管理端接口创建）")
    cleanup()

    # 拿两个分类 id 来测筛选。
    # 用 /admin/categories/options 而不是 /admin/categories ——
    # 后者返回的是分页对象（{list,total,...}），前者才直接返回数组。
    # 这类「同一个资源有多个查询入口，返回结构还不一样」的情况
    # 在真实项目里非常常见，写测试脚本时经常栽在这里。
    st, r = call("GET", "/admin/categories/options", token=ADMIN_TOKEN)
    cats = r.get("data") or []
    if len(cats) < 2:
        raise SystemExit(f"分类不够两条，测不了分类筛选：{r}")
    CAT_A, CAT_B = cats[0]["id"], cats[1]["id"]
    print(f"  使用分类：A={CAT_A}({cats[0]['name']})  B={CAT_B}({cats[1]['name']})")

    # 建测试数据【之前】先记一笔用户端的总数。
    # 最后一条用例要拿它来验证「测试没有污染数据库」——
    # 所以必须是「被测试改变之前」的值。
    st, r = shop_list(pageSize=1)
    count_before = (r.get("data") or {}).get("total")
    if not isinstance(count_before, int):
        raise SystemExit(f"取不到开局快照：HTTP {st} / {r}")
    print(f"  开局快照：用户端可见商品 {count_before} 条")

    # 需要 3 个商品：
    #   ON_SALE   上架，分类 A，价格 11.11
    #   OFF_SALE  下架，分类 A，价格 22.22   ← 用来验证「下架不可见」
    #   ON_SALE_B 上架，分类 B，价格 33.33   ← 用来验证分类筛选
    # 价格故意递增，且和 id 顺序一致，方便验证排序
    p_on = create_product(f"{TAG} 上架商品A", CAT_A, "11.11", 5, 1, "这是上架商品的描述")
    p_off = create_product(f"{TAG} 下架商品", CAT_A, "22.22", 5, 0, "这是下架商品的描述")
    p_b = create_product(f"{TAG} 上架商品B", CAT_B, "33.33", 5, 1, "")
    print(f"  已创建：上架={p_on}  下架={p_off}  上架B={p_b}")

    # 建完测试数据后的基线：开局快照 + 2（本次上架了 2 个商品）。
    # 后面的分页、搜索用例都拿它做参照。用算式而不是写死数字 ——
    # 数据库里的种子数据随时会变，写死的数字过两天就会莫名其妙变红，
    # 然后被人习惯性地当成噪音忽略掉。一个会被忽略的测试等于没有测试。
    st, r = shop_list(pageSize=1)
    baseline_total = (r.get("data") or {}).get("total")
    check("建完测试数据后，用户端总数 = 开局快照 + 2（新增的两个上架商品）",
          baseline_total == count_before + 2,
          f"现在={baseline_total} 快照={count_before}")
    # 本次创建的下架商品【不在】这个增量里 —— 这一条就已经顺带证明了
    # 「用户端总数只统计上架商品」。★★ 那一组用例是它的加强版。

    # ==================================================================
    section("1. ★ 游客不登录也能浏览（excludePathPatterns 是否真的生效）")

    st, r = call("GET", "/shop/products")
    check("游客（无 token）访问商品列表 → 200", st == 200 and r.get("code") == 200,
          f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/products?pageSize=1")
    first_id = ids_of(r.get("data") or {})
    check("游客访问商品详情 → 200", st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    if first_id:
        st, r = call("GET", f"/shop/products/{first_id[0]}")
        check("游客访问商品详情 → 200", st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/categories")
    check("游客访问分类列表 → 200", st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    check("分类列表返回的是数组", isinstance(r.get("data"), list), f"{r}")
    shop_cats = r.get("data")
    if isinstance(shop_cats, list):
        check("分类列表只返回启用的分类（status 全为 1）",
              all(c.get("status") == 1 for c in shop_cats), f"{shop_cats}")

        # 用户端分类和管理端下拉框【共用同一个 Service 方法】，
        # 所以两边必须一模一样。这条断言是在保护「复用」这件事本身 ——
        # 哪天有人给用户端分类单开一个查询，这条就会红，
        # 提醒他确认这个分岔是有意的。
        st2, r2 = call("GET", "/admin/categories/options", token=ADMIN_TOKEN)
        check("用户端分类列表与管理端下拉框返回完全一致（同一个 listEnabled）",
              r2.get("data") == shop_cats,
              f"用户端={shop_cats}\n         管理端={r2.get('data')}")

    # 已登录的用户当然也应该能浏览 —— 排除路径不能反过来把登录用户挡在外面
    # （这是排除路径的常见误解：排除≠只有游客能用）
    st, login_r = call("POST", "/shop/auth/login", {"username": "zhangsan", "password": "123456"})
    member_token = (login_r.get("data") or {}).get("token")
    if member_token:
        st, r = call("GET", "/shop/products", token=member_token)
        check("已登录会员访问商品列表 → 也 200（排除不等于只准游客）",
              st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    else:
        print(f"  [跳过] 会员 zhangsan 登录失败，跳过登录态浏览用例：{login_r}")

    # 被排除的路径根本不跑拦截器，所以一个坏 token 也不会导致 401。
    # 这不是漏洞（这些接口不需要身份），但要知道这个行为的存在。
    st, r = call("GET", "/shop/products?pageSize=1", token="this.is.garbage")
    check("（行为说明）被排除路径上带伪造 token 仍然 200 —— 因为拦截器根本没跑",
          st == 200, f"HTTP {st} / {r}")

    # ==================================================================
    section("2. ★★ 下架商品对用户端完全不可见（含对照组）")

    # 对照组：先证明这个下架商品【真的存在】。
    # 没有这一步的话，下面的「看不到」可能只是因为商品压根没建成功。
    st, r = call("GET", f"/admin/products?name={TAG}&pageSize=100", token=ADMIN_TOKEN)
    admin_names = [p["name"] for p in ((r.get("data") or {}).get("list") or [])]
    check(f"（对照组）管理端能查到「下架商品」→ 证明它确实存在",
          any("下架商品" in n for n in admin_names), f"管理端查到：{admin_names}")

    st, r = call("GET", f"/admin/products/{p_off}", token=ADMIN_TOKEN)
    check("（对照组）管理端商品详情能打开下架商品，且 status=0",
          r.get("code") == 200 and (r.get("data") or {}).get("status") == 0,
          f"HTTP {st} / {r}")

    # 现在看用户端
    st, r = shop_list(keyword=TAG, pageSize=100)
    shop_ids = ids_of(r.get("data") or {})
    check("★ 用户端列表【查不到】下架商品", p_off not in shop_ids,
          f"下架商品 id={p_off} 出现在了用户端列表里：{shop_ids}")
    check("★ 用户端列表能查到同批次的上架商品（证明搜索确实生效，不是全空）",
          p_on in shop_ids and p_b in shop_ids, f"用户端查到：{shop_ids}")
    check("用户端列表只返回上架商品（status 字段本就不该出现，此处校验数据来源正确）",
          len(shop_ids) == 2, f"期望 2 条（上架A + 上架B），实际 {len(shop_ids)} 条：{shop_ids}")

    st, r = call("GET", f"/shop/products/{p_off}")
    check("★★ 用户端直接按 id 打开下架商品详情 → 业务码 1003「商品不存在或已下架」",
          st == 200 and r.get("code") == 1003, f"HTTP {st} / {r}")

    # 用户端对整个测试标记做分类筛选，同样不该带出下架商品
    st, r = shop_list(categoryId=CAT_A, pageSize=100)
    check("★ 按分类筛选时下架商品同样不可见", p_off not in ids_of(r.get("data") or {}),
          f"分类 {CAT_A} 的用户端结果：{ids_of(r.get('data') or {})}")

    # ==================================================================
    section("3. 字段可见性：不该给的字段一个都不能给")

    st, r = shop_list(keyword=TAG, pageSize=1)
    item = (ids_of(r.get("data") or {}) and (r["data"]["list"][0])) or {}
    check("用户端列表项【没有 status 字段】（否则会泄漏「存在但下架」的信息）",
          "status" not in item, f"列表项字段：{sorted(item.keys())}")
    check("用户端列表项【没有 description 字段】（列表不查长文本）",
          "description" not in item, f"列表项字段：{sorted(item.keys())}")
    check("用户端列表项【没有 createTime / updateTime 字段】",
          "createTime" not in item and "updateTime" not in item,
          f"列表项字段：{sorted(item.keys())}")
    check("用户端列表项有 categoryName（join 出来的分类名，前端要显示）",
          "categoryName" in item, f"列表项字段：{sorted(item.keys())}")

    st, r = call("GET", f"/shop/products/{p_on}")
    d = r.get("data") or {}
    check("用户端详情【有】description", "description" in d, f"详情字段：{sorted(d.keys())}")
    check("用户端详情【没有】status 字段", "status" not in d, f"详情字段：{sorted(d.keys())}")
    check("用户端详情带 categoryName", bool(d.get("categoryName")), f"{d}")

    # ==================================================================
    section("4. 搜索")

    st, r = shop_list(keyword=TAG, pageSize=100)
    check("关键词命中 → total 等于匹配条数",
          (r.get("data") or {}).get("total") == 2, f"{r.get('data')}")

    st, r = shop_list(keyword=f"{PREFIX}这个关键词一定不存在{RUN}", pageSize=100)
    check("关键词不命中 → HTTP 200 + total=0 + 空列表",
          st == 200 and r.get("code") == 200
          and (r.get("data") or {}).get("total") == 0
          and (r.get("data") or {}).get("list") == [],
          f"HTTP {st} / {r}")

    st, r = shop_list(keyword="", pageSize=1)
    check("keyword 传空串 → 不报错，等价于「不筛选」（normalize 把空串变成了 null）",
          st == 200 and (r.get("data") or {}).get("total") == baseline_total,
          f"HTTP {st} / total={(r.get('data') or {}).get('total')} / 快照={baseline_total}")

    st, r = shop_list(keyword="   ", pageSize=1)
    check("keyword 传纯空格 → 同样等价于不筛选（isBlank 而不是 isEmpty）",
          st == 200 and (r.get("data") or {}).get("total") == baseline_total,
          f"HTTP {st} / total={(r.get('data') or {}).get('total')}")

    # ==================================================================
    section("5. 分类筛选")

    st, r = shop_list(categoryId=CAT_A, pageSize=100)
    cat_a_items = (r.get("data") or {}).get("list") or []
    check("按分类筛选后，每一条的 categoryId 都等于所筛分类",
          cat_a_items and all(i.get("categoryId") == CAT_A for i in cat_a_items),
          f"{[i.get('categoryId') for i in cat_a_items]}")

    st, r = shop_list(categoryId=999999, pageSize=10)
    check("不存在的分类 → total=0，不报错",
          st == 200 and (r.get("data") or {}).get("total") == 0, f"HTTP {st} / {r}")

    st, r = shop_list(keyword=TAG, categoryId=CAT_B, pageSize=100)
    check("关键词 + 分类组合筛选按 AND 生效（命中上架商品B）",
          ids_of(r.get("data") or {}) == [p_b], f"{ids_of(r.get('data') or {})} 期望 [{p_b}]")

    st, r = shop_list(keyword=TAG, categoryId=CAT_B, pageSize=100)
    check("组合筛选下下架商品依然不可见", p_off not in ids_of(r.get("data") or {}), f"{r}")

    # ==================================================================
    section("6. 排序")

    # ★ 里程碑 15 阶段 6：这里读的键从 price 变成了 minPrice。
    #   两者在阶段 2~5 期间【恰好相等】（product.price 那时是派生汇总），
    #   所以这一节在那时"改不改都绿"—— 而那正是它值得改的理由：
    #   **排序依据必须等于列表上显示的那个数。**
    #   列表现在显示 minPrice，如果 ORDER BY 还按别的东西排，
    #   用户会看到 [¥100, ¥80, ¥50] 这样一个"没排序"的列表。
    #   ⚠️ 而这条断言【测不出】那种情况：它只验证「结果是有序的」，
    #      不验证「按哪个数有序」。真正的守护是 ProductMapper.xml 的
    #      shopOrderBy 里那句 ORDER BY a.min_price —— 两边取的是同一个值。
    st, r = shop_list(sort="price_asc", pageSize=100)
    prices = [float(i["minPrice"]) for i in ((r.get("data") or {}).get("list") or [])]
    check("sort=price_asc → 价格单调不减",
          st == 200 and prices == sorted(prices) and len(prices) > 1, f"{prices}")

    st, r = shop_list(sort="price_desc", pageSize=100)
    prices_desc = [float(i["minPrice"]) for i in ((r.get("data") or {}).get("list") or [])]
    check("sort=price_desc → 价格单调不增",
          st == 200 and prices_desc == sorted(prices_desc, reverse=True) and len(prices_desc) > 1,
          f"{prices_desc}")

    check("★ 升序和降序的结果确实不同（否则说明排序参数被忽略了）",
          prices != prices_desc, f"asc={prices[:5]} desc={prices_desc[:5]}")

    st, r = shop_list(sort="price_asc", keyword=TAG, pageSize=100)
    tagged_prices = [float(i["minPrice"]) for i in ((r.get("data") or {}).get("list") or [])]
    check("同一批商品按价格升序 → 11.11 在 33.33 前面",
          tagged_prices == [11.11, 33.33], f"{tagged_prices}")

    st, r = shop_list(sort="price_desc", keyword=TAG, pageSize=100)
    tagged_desc = [float(i["minPrice"]) for i in ((r.get("data") or {}).get("list") or [])]
    check("同一批商品按价格降序 → 33.33 在 11.11 前面",
          tagged_desc == [33.33, 11.11], f"{tagged_desc}")

    # 非法排序值：退化成默认排序，不报错。
    # 这是刻意的设计选择 —— 查询条件的非法值不该让用户看到报错页。
    st, r = shop_list(sort="drop_table", pageSize=5)
    check("sort 传非法值 → HTTP 200，退化为默认排序（不报错、不注入）",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = shop_list(sort="price_asc; DROP TABLE product", pageSize=5)
    check("★ sort 传注入串 → HTTP 200 且商品表还在（<choose> 那层防御生效）",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    rows = run_sql("SELECT COUNT(*) FROM product")
    check("★ 注入尝试后 product 表依然可以正常查询",
          bool(rows) and int(rows[0][0]) > 0, f"{rows}")

    st, r = shop_list(sort="price_asc", pageSize=5)
    check("（复核）非法排序值没有影响后续的正常排序",
          r.get("code") == 200 and [float(i["minPrice"]) for i in ((r.get("data") or {}).get("list") or [])]
          == sorted(float(i["minPrice"]) for i in ((r.get("data") or {}).get("list") or [])),
          f"{r}")

    # ==================================================================
    section("7. 分页")

    st, r = shop_list(pageNum=1, pageSize=2)
    d1 = r.get("data") or {}
    check("pageSize=2 → 本页确实只有 2 条", len(d1.get("list") or []) == 2, f"{d1}")
    check("total 返回的是总数而不是本页条数", d1.get("total") == baseline_total,
          f"total={d1.get('total')} 快照={baseline_total}")
    check("pageSize / pageNum 原样回传给前端（分页器要用）",
          d1.get("pageSize") == 2 and d1.get("pageNum") == 1, f"{d1}")

    st, r = shop_list(pageNum=2, pageSize=2)
    d2 = r.get("data") or {}
    check("★ 第 1 页和第 2 页【没有重复数据】（ORDER BY 带了主键做兜底排序键）",
          set(ids_of(d1)) & set(ids_of(d2)) == set(),
          f"第1页={ids_of(d1)} 第2页={ids_of(d2)} 交集={set(ids_of(d1)) & set(ids_of(d2))}")

    check("pages 字段 = 总页数（向上取整）",
          d1.get("pages") == (baseline_total + 1) // 2,
          f"pages={d1.get('pages')} total={baseline_total} pageSize=2")

    st, r = shop_list(pageNum=9999, pageSize=10)
    check("页码越界 → HTTP 200 + 空列表（不是报错）",
          st == 200 and (r.get("data") or {}).get("list") == [], f"HTTP {st} / {r}")
    check("页码越界时 total 仍然正常返回（前端分页器不会错乱）",
          (r.get("data") or {}).get("total") == baseline_total, f"{r.get('data')}")

    st, r = shop_list(pageSize=999999)
    check("★ pageSize=999999 被压到上限 100（防止一次拉走整张表）",
          (r.get("data") or {}).get("pageSize") == 100,
          f"回传的 pageSize={(r.get('data') or {}).get('pageSize')}")

    st, r = shop_list(pageSize=0)
    check("pageSize=0 → 兜底成默认值，不报错",
          st == 200 and (r.get("data") or {}).get("pageSize") == 10, f"HTTP {st} / {r}")

    st, r = shop_list(pageSize=-5)
    check("pageSize=-5 → 兜底成默认值，不报错",
          st == 200 and (r.get("data") or {}).get("pageSize") == 10, f"HTTP {st} / {r}")

    st, r = shop_list(pageNum=-1)
    check("pageNum=-1 → 兜底成第 1 页",
          st == 200 and (r.get("data") or {}).get("pageNum") == 1, f"HTTP {st} / {r}")

    st, r = shop_list(pageNum="abc")
    check("pageNum 传非数字 → HTTP 400（参数类型转换失败，由 Spring 拦下）",
          st == 400, f"HTTP {st} / {r}")

    # ==================================================================
    section("8. 详情接口的边界")

    st, r = call("GET", "/shop/products/999999")
    check("不存在的商品 id → HTTP 200 + 业务码 1003",
          st == 200 and r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/products/-1")
    check("负数 id → 业务码 1003（Service 里的防御性判断拦下，没查库）",
          st == 200 and r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/products/0")
    check("id=0 → 业务码 1003", st == 200 and r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/products/abc")
    check("id 传非数字 → HTTP 400（轮不到业务代码，Spring 类型转换就失败了）",
          st == 400, f"HTTP {st} / {r}")

    # 「不存在的 id」和「已下架的 id」返回的必须【完全一样】，
    # 否则攻击者可以靠对比响应来枚举出哪些商品曾经存在过。
    st1, r1 = call("GET", "/shop/products/999999")
    st2, r2 = call("GET", f"/shop/products/{p_off}")
    check("★ 「不存在」和「已下架」的响应完全一致（防止通过响应差异枚举商品）",
          st1 == st2 and r1.get("code") == r2.get("code")
          and r1.get("message") == r2.get("message"),
          f"不存在：HTTP {st1} / {r1.get('code')} / {r1.get('message')}\n"
          f"         已下架：HTTP {st2} / {r2.get('code')} / {r2.get('message')}")

    # ==================================================================
    section("9. 回归：需要登录的接口不能被这次的排除配置带跑偏")

    # ★ 里程碑 7 做完之后，购物车这条从「占位断言」升级成了「真断言」：
    #
    #   里程碑 6 时这里只能断言 404 —— 因为 Controller 还不存在，
    #   请求在到达拦截器之前就先匹配失败了。那条断言其实什么也没证明，
    #   只是确认了「排除列表里没写它」。
    #
    #   现在 Controller 存在了，请求真的会走到拦截器，
    #   返回的 401 是拦截器【拒绝】的结果，不是「没人处理」的结果。
    #   这两者区别很大：前者说明安全边界真的生效了。
    st, r = call("POST", "/shop/cart/items", {"productId": p_on, "quantity": 1})
    check("★★ 购物车接口未被排除：匿名 POST → 401（拦截器真的拦下了）",
          st == 401, f"HTTP {st} / {r}")

    # ★ 里程碑 8 做完了，这一条按上面那句"等做完就改成断言 401"兑现了。
    #
    #   为什么要连带改【方法】？因为原来用的是 GET ——
    #   那时候是为了避开"会不会误创建订单"的顾虑，随手挑了个读方法。
    #   现在订单接口只支持 POST，GET 会得到 405，
    #   而 405 是「没有处理方法」，跟"排不排除"这件事没关系，
    #   拿它来验证安全边界就成了假通过。
    #
    #   ★ 这是一个容易忽略的点：**改一条断言的时候，
    #     要连它用的请求方法一起重新想一遍。**
    #     只把 404 改成 401，请求还发 GET 的话，
    #     期望值和实际行为永远对不上，而你会以为是权限配错了。
    st, r = call("POST", "/shop/orders",
                 {"productIds": [p_on], "addressId": 1,
                  "idempotencyKey": "shopproductkey01"})
    check("★★ 订单接口未被排除：匿名 POST → 401（拦截器真的拦下了）",
          st == 401, f"HTTP {st} / {r}")

    st, r = call("POST", "/shop/orders/buy-now",
                 {"productId": p_on, "quantity": 1, "addressId": 1,
                  "idempotencyKey": "shopproductkey02"})
    check("★★ 立即购买接口未被排除：匿名 POST → 401",
          st == 401, f"HTTP {st} / {r}")

    # 管理端不受任何影响
    st, r = call("GET", "/admin/products", token=ADMIN_TOKEN)
    check("管理端商品列表仍然需要 token（排除配置没有误伤管理端）",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = call("GET", "/admin/products")
    check("管理端不带 token → 401（回归）", st == 401, f"HTTP {st} / {r}")
    st, r = call("GET", "/admin/categories")
    check("管理端分类不带 token → 401（回归）", st == 401, f"HTTP {st} / {r}")

    # ==================================================================
    section("10. 清理")

    for pid in (p_on, p_off, p_b):
        st, r = call("DELETE", f"/admin/products/{pid}", token=ADMIN_TOKEN)
        check(f"删除测试商品 {pid}", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = call("GET", f"/admin/products?name={TAG}&pageSize=100", token=ADMIN_TOKEN)
    check("管理端已查不到本次的测试商品（清理干净）",
          (r.get("data") or {}).get("total") == 0, f"{r.get('data')}")

    st, r = shop_list(pageSize=1)
    check("★ 清理后用户端总数回到【测试前】的开局快照（测试没有污染数据库）",
          (r.get("data") or {}).get("total") == count_before,
          f"现在={(r.get('data') or {}).get('total')} 开局快照={count_before}")

    # ==================================================================
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
