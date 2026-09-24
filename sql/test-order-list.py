# -*- coding: utf-8 -*-
"""
里程碑 10 测试：订单列表 + 发货 / 确认收货

这个脚本要守住的是【列表类接口特有的】几类 bug —— 它们和单笔接口的 bug
完全不同，而且几乎都不能靠手点浏览器发现：

  1. ★★ 批量装明细串单 —— 一页 10 条只查 1 次明细，然后按 orderId 分组。
     分组键取错的话，A 的明细会挂到 B 头上。
     ⚠️ 而它在「每单都只有 1 件商品」的数据上【完全看不出来】——
     所以本脚本必须造出「件数不同的订单」才有鉴别力。

  2. ★ 分页边界 —— pageSize 不设上限就是数据爬取；
     页码超过末页必须返回空列表但 total 不变（不是 total=0）；
     pageNum=0 / 负数要归一到第 1 页。

  3. ★ ORDER BY 稳定性 —— 只按 create_time 排序是不安全的：
     同一秒创建的订单顺序不确定，LIMIT 翻页会重复某些行、跳过另一些行。
     唯一的验法就是造几笔同秒订单然后连着翻页。

  4. ★★ 确认收货【不还库存】—— 这条断言的价值不在于「现在是对的」，
     而在于【挡住未来某个人「顺手补上还库存」】。所以它必须直查库。

  5. ★ 状态机约束 —— 只有「已付款」能发货、只有「已发货」能确认收货。
     ⚠️ 每个非法状态都要单独断言一次：`WHERE status = 1` 写错成别的数字时，
     只测一个状态的用例可能刚好漏过。

  6. ★ 鉴权 —— 会员 token 打管理端、管理员 token 打用户端，两边都必须 401。

运行：
    python test-order-list.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "ordlist"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None

# 三个会员，各有分工：
#   A —— 主测试会员，7 笔订单覆盖全部 5 种状态 + 3 种明细件数
#   B —— 「别人」，用来验证跨会员隔离
#   C —— 同秒批量建 5 单，专门用来验翻页稳定性
TOKEN_A = TOKEN_B = TOKEN_C = None
ID_A = ID_B = ID_C = None
ADDR_A = ADDR_B = ADDR_C = None

TEST_MEMBER_IDS = []

# 状态码（和 OrderStatus 对齐，写成本地常量免得每次去翻 Java）
S_PENDING, S_PAID, S_SHIPPED, S_DONE, S_CANCELLED = 0, 1, 2, 3, 4
STATUS_TEXT = {0: "待付款", 1: "已付款", 2: "已发货", 3: "已完成", 4: "已取消"}


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


def redis_cmd(*args):
    result = subprocess.run(
        ["docker", "exec", REDIS, "redis-cli", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"redis-cli 执行失败：{args}\n{result.stderr}")
    return [l for l in result.stdout.strip().splitlines() if l]


def scalar(sql, default=None):
    rows = run_sql(sql)
    return rows[0][0] if rows else default


def stock_of(sku_id):
    """★ 里程碑 15 阶段 4：库存的唯一真源是 product_sku.stock。"""
    rows = run_sql(f"SELECT stock FROM product_sku WHERE id = {sku_id}")
    return int(rows[0][0]) if rows else None


def default_sku_of(pid):
    """无规格商品的那唯一一条「默认 SKU」的 id。"""
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid}"))


def db_order(order_no):
    """直接查库看一笔订单的真实状态，不通过接口。

    ★ 验「发货/确认收货真的写进去了」必须走这条路 ——
      只看接口响应的话，一个「响应说成功但 SQL 没执行」的 bug 会溜过去。
    """
    rows = run_sql(
        f"SELECT status, IFNULL(ship_time,'NULL'), IFNULL(complete_time,'NULL') "
        f"FROM orders WHERE order_no = '{order_no}'")
    if not rows:
        return None
    return {"status": int(rows[0][0]), "shipTime": rows[0][1], "completeTime": rows[0][2]}


# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(run_sql("SELECT id FROM category ORDER BY id LIMIT 1")[0][0])


def register(tag):
    """注册一个测试会员。

    ⚠️ username 和 nickname 都带 RUN（时间戳）后缀，这样 memberKeyword
       的模糊搜索在本脚本内部才不会有误命中。
    """
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}{tag}{RUN}",
        "password": "ordlist123456",
        "nickname": f"订单列表{tag}{RUN}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    d = r["data"]
    TEST_MEMBER_IDS.append(d["id"])
    return d["token"], d["id"]


def create_product(name, price, stock):
    # 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品也要显式给一条
    # 「默认 SKU」（specs 为空数组），后端拿它的 price/stock 当作这件商品的价格和库存。
    st, r = call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name,
        "specSchema": [],
        "skus": [{"specs": [], "price": price, "stock": stock}],
        "status": 1,
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    # ★ 里程碑 15 阶段 4：返回【SKU id】—— 下单和加购现在都按规格走。
    return default_sku_of(r["data"])


def create_address(token, receiver):
    st, r = call("POST", "/shop/addresses", {
        "receiver": receiver, "phone": "13800000000",
        "region": "测试省测试市测试区", "detail": f"{PREFIX}路 1 号",
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"建测试地址失败：HTTP {st} / {r}")
    return r["data"]


def key_for(tag):
    """幂等键：必须符合后端 @Pattern("^[A-Za-z0-9_-]{8,64}$")。"""
    return f"k{RUN}{tag}"


def buy_now(token, sku_id, qty, address_id, idem_key):
    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": sku_id, "quantity": qty,
        "addressId": address_id, "idempotencyKey": idem_key,
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"立即购买失败（{idem_key}）：HTTP {st} / {r}")
    return r["data"]["orderNo"]


def cart_order(token, sku_ids, address_id, idem_key):
    st, r = call("POST", "/shop/orders", {
        "skuIds": sku_ids,
        "addressId": address_id, "idempotencyKey": idem_key,
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"购物车结算失败（{idem_key}）：HTTP {st} / {r}")
    return r["data"]["orderNo"]


def cart_add(token, sku_id, qty):
    st, r = call("POST", "/shop/cart/items", {"skuId": sku_id, "quantity": qty},
                 token=token)
    if r.get("code") != 200:
        raise SystemExit(f"加入购物车失败：HTTP {st} / {r}")


def pay(token, order_no, method="ALIPAY"):
    return call("POST", f"/shop/orders/{order_no}/pay", {"payMethod": method},
                token=token)


def complete(token, order_no):
    return call("POST", f"/shop/orders/{order_no}/complete", None, token=token)


def ship(order_no):
    return call("POST", f"/admin/orders/{order_no}/ship", None, token=ADMIN_TOKEN)


def shop_list(token, **params):
    """GET /api/shop/orders —— 带查询参数的版本。

    ★ 用 urlencode 而不是手工拼字符串：空格会被编码成 '+'，
      这正是本脚本验「订单号带首尾空格也能搜到」时需要的。
    """
    qs = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    path = "/shop/orders" + (f"?{qs}" if qs else "")
    st, r = call("GET", path, None, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"查我的订单失败：HTTP {st} / {r}")
    return r["data"]


def admin_list(**params):
    qs = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    path = "/admin/orders" + (f"?{qs}" if qs else "")
    st, r = call("GET", path, None, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"查管理端订单失败：HTTP {st} / {r}")
    return r["data"]


def nos(page):
    return [o["orderNo"] for o in page["list"]]


# ----------------------------------------------------------------------
def cleanup():
    # ★ 顺序：晒图 → 评价 → 明细 → 订单 → 地址 → 会员。
    #   order_item 故意没有外键（见 mall.sql 里的说明），顺序错了就是孤儿明细。
    #   ★ 里程碑 12 在最前面补了评价和晒图两层 —— 本脚本不写评价，
    #     所以今天删的是 0 行；要的是【顺序永远是对的】这件事。
    run_sql("DELETE pri FROM product_review_image pri "
            "JOIN product_review r ON r.id = pri.review_id "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE r FROM product_review r "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE oi FROM order_item oi "
            "JOIN orders o ON o.id = oi.order_id "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE o FROM orders o "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE a FROM member_address a "
            f"JOIN member m ON m.id = a.member_id WHERE m.username LIKE '{PREFIX}%'")
    # ★ 里程碑 15：product_sku 同样【没有外键】，所以它也必须排在商品之前。
    run_sql(f"DELETE FROM product_sku WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")
    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")


def cleanup_redis():
    # ★ 只删这次测试用到的会员的购物车，不用 KEYS mall:cart:* 一锅端。
    for mid in TEST_MEMBER_IDS:
        redis_cmd("DEL", f"mall:cart:{mid}")


# ======================================================================
def main():
    global TOKEN_A, ID_A, TOKEN_B, ID_B, TOKEN_C, ID_C
    global ADDR_A, ADDR_B, ADDR_C

    cleanup()
    admin_login()
    TOKEN_A, ID_A = register("a")
    TOKEN_B, ID_B = register("b")
    TOKEN_C, ID_C = register("c")
    ADDR_A = create_address(TOKEN_A, f"{PREFIX}收货人A")
    ADDR_B = create_address(TOKEN_B, f"{PREFIX}收货人B")
    ADDR_C = create_address(TOKEN_C, f"{PREFIX}收货人C")

    print()
    print(f"本次运行 RUN = {RUN}")
    print(f"会员 A id={ID_A} / B id={ID_B} / C id={ID_C}")

    # ---- 测试商品 ----
    # pA 用来造「1 件」的订单，pB 用来造「2 件」，pS 用来造「3 件」+ 验库存不变。
    # （★ 这三个商品的价格/库存本身不重要，重要的是它们【是不同的商品】——
    #   明细串单的 bug 只有在「不同订单的商品集合不同」时才看得出来。）
    pA = create_product(f"{PREFIX}主品", 10.00, 500)
    pB = create_product(f"{PREFIX}配件", 5.00, 500)
    pS = create_product(f"{PREFIX}库存品", 20.00, 500)
    print(f"商品：主品#{pA} 配件#{pB} 库存品#{pS}")

    section("0. 造数据：A 的 7 笔订单（覆盖 5 种状态 + 3 种明细件数）")

    # ★ 件数刻意做成 1 / 2 / 3 三种 —— 这是本脚本最有价值的一条设计。
    #   如果每单都是 1 件，「批量装明细的分组键取错」这个 bug 完全看不出来：
    #   每单都有且只有一条明细，挂错了也还是「一单一条」。
    a1 = buy_now(TOKEN_A, pA, 1, ADDR_A, key_for("a1"))     # 待付款
    a2 = buy_now(TOKEN_A, pA, 1, ADDR_A, key_for("a2"))     # 已付款 → 后面发货
    a3 = buy_now(TOKEN_A, pA, 1, ADDR_A, key_for("a3"))     # 已付款（一直保持，用来验「已付款不能确认收货」）
    a4 = buy_now(TOKEN_A, pS, 3, ADDR_A, key_for("a4"))     # 已付款 → 发货 → 确认收货（验库存不变）
    a5 = buy_now(TOKEN_A, pA, 1, ADDR_A, key_for("a5"))     # 待付款 → 取消

    cart_add(TOKEN_A, pA, 1)
    cart_add(TOKEN_A, pB, 1)
    a6 = cart_order(TOKEN_A, [pA, pB], ADDR_A, key_for("a6"))          # 2 件

    cart_add(TOKEN_A, pA, 1)
    cart_add(TOKEN_A, pB, 1)
    cart_add(TOKEN_A, pS, 1)
    a7 = cart_order(TOKEN_A, [pA, pB, pS], ADDR_A, key_for("a7"))      # 3 件

    check("A 建了 7 笔订单", len({a1, a2, a3, a4, a5, a6, a7}) == 7,
          f"订单号有重复：{[a1, a2, a3, a4, a5, a6, a7]}")

    # 状态推进：
    #   a2 → 已付款（第 6 节要拿它验「发货成功」）
    #   a3 → 已付款（一直保持，第 8 节要拿它验「已付款不能确认收货」）
    #   a4 → 已付款 → 已发货（第 7 节要拿它验「确认收货 + 库存不变」）
    #   a5 → 已取消
    for label, order_no in [("a2", a2), ("a3", a3), ("a4", a4)]:
        st, r = pay(TOKEN_A, order_no)
        check(f"{label} 支付成功", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = ship(a4)
    check("a4 发货成功（第 7 节的确认收货前置条件）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = call("POST", f"/shop/orders/{a5}/cancel", None, token=TOKEN_A)
    check("a5 取消成功", r.get("code") == 200, f"HTTP {st} / {r}")

    # ★ 把夹具的状态【钉死】。
    #   这不是多余的：上面任何一步少写了一行，症状都会在几十个 check 之后
    #   才浮现，而且表现为「状态筛选的条数不对」这种看不出根因的失败。
    #   有了这一段，夹具本身坏掉时会在这里直接报出来。
    for label, order_no, want in [
        ("a1", a1, S_PENDING), ("a2", a2, S_PAID), ("a3", a3, S_PAID),
        ("a4", a4, S_SHIPPED), ("a5", a5, S_CANCELLED),
        ("a6", a6, S_PENDING), ("a7", a7, S_PENDING),
    ]:
        got = db_order(order_no)
        check(f"夹具核对：{label} 是「{STATUS_TEXT[want]}」",
              got and got["status"] == want,
              f"实际是 {got and STATUS_TEXT.get(got['status'])}（{got}）")

    # B 的 1 笔订单（用来验跨会员隔离）
    b1 = buy_now(TOKEN_B, pA, 1, ADDR_B, key_for("b1"))

    # C 的 5 笔订单（用来验翻页稳定性）。连着建，尽量落在同一秒里。
    c_orders = []
    for i in range(5):
        c_orders.append(buy_now(TOKEN_C, pA, 1, ADDR_C, key_for(f"c{i}")))

    c_seconds = run_sql(
        f"SELECT DISTINCT create_time FROM orders WHERE order_no IN "
        f"({','.join(repr(x) for x in c_orders)})")
    print(f"  C 的 5 笔订单落在 {len(c_seconds)} 个不同的秒上"
          f"{'（★ 同秒，正是要测的情形）' if len(c_seconds) == 1 else ''}")

    # 每笔订单期望的明细：{【规格id】: 数量}
    # ★ 里程碑 15 阶段 4：键从商品 id 换成了规格 id。
    #   这三件都是无规格商品，各自只有一条默认 SKU，所以只是数字变了 ——
    #   但如果谁把明细按商品 id 去重分组，同一件商品的两个规格会被合并成一行，
    #   而这里恰好都是单规格商品，看不出来。所以 test-sku.py 的 C4 那组
    #   专门用【一件商品两个规格】来盯这件事。
    EXPECT_ITEMS = {
        a1: {pA: 1}, a2: {pA: 1}, a3: {pA: 1}, a4: {pS: 3}, a5: {pA: 1},
        a6: {pA: 1, pB: 1},
        a7: {pA: 1, pB: 1, pS: 1},
    }

    # ==================================================================
    section("1. ★★ 批量装明细：明细不能串单")

    page = shop_list(TOKEN_A, pageSize=50)
    check("A 的列表返回 7 笔", page["total"] == 7,
          f"total={page['total']}，期望 7")
    check("列表一次带回全部 7 笔（pageSize=50）", len(page["list"]) == 7,
          f"实际 {len(page['list'])} 条")

    for order in page["list"]:
        no = order["orderNo"]
        expect = EXPECT_ITEMS.get(no)
        if expect is None:
            check(f"订单 {no} 是 A 自己的订单", False, "列表里出现了不属于本测试的订单号")
            continue

        items = order.get("items") or []
        got = {}
        for it in items:
            got[it["skuId"]] = got.get(it["skuId"], 0) + it["quantity"]

        ok = (len(items) == len(expect)) and (got == expect)
        check(f"订单 …{no[-4:]} 的明细是 {len(expect)} 件且商品正确（实际 {len(items)} 件）",
              ok, f"期望 {expect}，实际 {got}")

    # ★ 这一条单独拎出来：明细里必须带 orderId，而且必须是【这一单】的 id。
    #   它是分组的依据，也是里程碑 10 新加的字段。
    bad_group = []
    for order in page["list"]:
        for it in (order.get("items") or []):
            if it.get("orderId") != order["id"]:
                bad_group.append(f"{order['orderNo']} 的明细 orderId={it.get('orderId')}")
    check("★ 每条明细的 orderId 都指向它所在的那笔订单（分组键正确）",
          not bad_group, f"串单了：{bad_group[:3]}")

    # ==================================================================
    section("2. 用户端列表：只返回自己的订单 + 排序")

    check("★ 列表里【没有】B 的订单（跨会员隔离）",
          b1 not in nos(page), f"B 的订单 {b1} 出现在了 A 的列表里 —— 越权！")
    check("★ 列表里【没有】C 的订单（跨会员隔离）",
          not (set(c_orders) & set(nos(page))), "C 的订单出现在了 A 的列表里 —— 越权！")

    # ★ 顺序断言：ORDER BY create_time DESC, id DESC。
    #   倒序 = 最新的在最前，而「同一秒」时由 id DESC 决定，
    #   所以整体恰好是创建顺序的完全逆序（状态变更不影响 id 和 create_time）。
    expected_order = [a7, a6, a5, a4, a3, a2, a1]
    check("★ 排序是「最新在前」（create_time DESC, id DESC）",
          nos(page) == expected_order,
          f"期望 {expected_order}\n         实际 {nos(page)}")

    b_page = shop_list(TOKEN_B, pageSize=50)
    check("B 只看到自己那 1 笔", b_page["total"] == 1 and nos(b_page) == [b1],
          f"total={b_page['total']}, list={nos(b_page)}")

    # ==================================================================
    section("3. ★ 分页边界")

    p1 = shop_list(TOKEN_A, pageNum=1, pageSize=3)
    p2 = shop_list(TOKEN_A, pageNum=2, pageSize=3)
    p3 = shop_list(TOKEN_A, pageNum=3, pageSize=3)
    check("pageSize=3 时共 3 页", p1["pages"] == 3, f"pages={p1['pages']}")
    check("第 1 页 3 条", len(p1["list"]) == 3, f"{len(p1['list'])} 条")
    check("第 2 页 3 条", len(p2["list"]) == 3, f"{len(p2['list'])} 条")
    check("第 3 页只剩 1 条", len(p3["list"]) == 1, f"{len(p3['list'])} 条")
    check("★ 三页拼起来恰好是全部 7 笔，无重复无遗漏",
          nos(p1) + nos(p2) + nos(p3) == expected_order,
          f"{nos(p1)} + {nos(p2)} + {nos(p3)}")

    # ★★ 页码超过末页：必须返回【空列表但 total 不变】。
    #   如果实现里写成 total=0，前端的分页器会突然显示「共 0 条」，
    #   而且用户没法判断是自己翻过头了还是真的没有数据。
    p99 = shop_list(TOKEN_A, pageNum=99, pageSize=3)
    check("★ 页码超出末页 → 空列表", p99["list"] == [],
          f"返回了 {len(p99['list'])} 条")
    check("★ 页码超出末页 → total 仍然是 7（不是 0）",
          p99["total"] == 7, f"total={p99['total']}")

    p0 = shop_list(TOKEN_A, pageNum=0, pageSize=3)
    check("pageNum=0 → 归一到第 1 页", p0["pageNum"] == 1 and nos(p0) == nos(p1),
          f"pageNum={p0['pageNum']}, list={nos(p0)}")
    pneg = shop_list(TOKEN_A, pageNum=-5, pageSize=3)
    check("pageNum=-5 → 归一到第 1 页", pneg["pageNum"] == 1 and nos(pneg) == nos(p1),
          f"pageNum={pneg['pageNum']}, list={nos(pneg)}")

    # ★ pageSize 上限：不设的话前端传个 999999 就能把整张订单表拉走。
    pbig = shop_list(TOKEN_A, pageSize=1000)
    check("★ pageSize=1000 被钳到 100", pbig["pageSize"] == 100,
          f"pageSize={pbig['pageSize']} —— 上限没生效？")
    pzero = shop_list(TOKEN_A, pageSize=0)
    check("pageSize=0 → 兜底成默认 10", pzero["pageSize"] == 10,
          f"pageSize={pzero['pageSize']}")

    # ==================================================================
    section("4. ★ ORDER BY 稳定性：同秒订单连着翻页不重不漏")

    c1 = shop_list(TOKEN_C, pageNum=1, pageSize=2)
    c2 = shop_list(TOKEN_C, pageNum=2, pageSize=2)
    c3 = shop_list(TOKEN_C, pageNum=3, pageSize=2)
    combined = nos(c1) + nos(c2) + nos(c3)
    check("C 共 5 笔", c1["total"] == 5, f"total={c1['total']}")
    check("★ 三页并起来恰好 5 笔，无重复",
          len(combined) == 5 and len(set(combined)) == 5,
          f"拿到 {combined}")
    check("★ 三页并起来恰好 5 笔，无遗漏",
          set(combined) == set(c_orders),
          f"期望 {sorted(c_orders)}\n         实际 {sorted(combined)}")
    check("★ 每笔恰好出现一次（不是某些行重复出现、某些从未出现）",
          sorted(combined) == sorted(c_orders),
          f"重复或缺失：{combined}")

    # ==================================================================
    section("5. 管理端列表：能看到所有人 + 三种筛选")

    # ★ username 全部以 PREFIX 开头，所以这个关键词只可能命中本测试的三个会员。
    #   这也顺便证明了「管理员能看到【别的会员】的订单」——
    #   如果管理端也悄悄带了 member_id 条件，这里只会返回 0 条。
    all_admin = admin_list(memberKeyword=PREFIX, pageSize=100)
    check("★ 管理端能看到本测试全部 13 笔（A7 + B1 + C5）",
          all_admin["total"] == 13, f"total={all_admin['total']}")
    check("★ 管理端列表里同时有 A 和 B 的订单（没有会员隔离）",
          a1 in nos(all_admin) and b1 in nos(all_admin),
          "A 或 B 的订单不在管理端列表里")

    a_admin = admin_list(memberKeyword=f"订单列表a{RUN}", pageSize=100)
    check("按 nickname 模糊搜索 → 只命中 A 的 7 笔",
          a_admin["total"] == 7, f"total={a_admin['total']}")
    check("按 nickname 搜出来的确实是 A 的订单",
          set(nos(a_admin)) == set(EXPECT_ITEMS.keys()),
          f"{sorted(nos(a_admin))}")

    one = admin_list(orderNo=a6)
    check("按订单号精确搜索 → 恰好 1 笔", one["total"] == 1 and nos(one) == [a6],
          f"total={one['total']}, list={nos(one)}")

    # ★ trim 的承重验证：管理员从页面上复制订单号，很容易带进来一个尾随空格。
    #   精确匹配带着空格就是 0 行，而且【看不出任何错】。
    spaced = admin_list(orderNo=f"  {a6}  ")
    check("★ 订单号带首尾空格也能搜到（normalize 里的 trim 在承重）",
          spaced["total"] == 1 and nos(spaced) == [a6],
          f"total={spaced['total']} —— trim 没生效？")

    none_admin = admin_list(orderNo="NO_SUCH_ORDER_12345678")
    check("不存在的订单号 → 0 条", none_admin["total"] == 0,
          f"total={none_admin['total']}")

    st4 = admin_list(memberKeyword=PREFIX, status=S_CANCELLED, pageSize=100)
    check("★ 管理端 status + memberKeyword 组合筛选（共用条件片段）",
          st4["total"] == 1 and nos(st4) == [a5],
          f"total={st4['total']}, list={nos(st4)}")

    # 管理端 VO 的会员字段
    if all_admin["list"]:
        row = [o for o in all_admin["list"] if o["orderNo"] == a1][0]
        check("管理端订单带 memberUsername",
              bool(row.get("memberUsername")), f"memberUsername={row.get('memberUsername')}")
        check("管理端订单带 memberNickname",
              bool(row.get("memberNickname")), f"memberNickname={row.get('memberNickname')}")
        check("★ 用户端订单【没有】memberUsername（两端 VO 不同）",
              "memberUsername" not in page["list"][0],
              "用户端也能看到会员名 —— 字段泄露了")

    # ==================================================================
    section("6. 管理端发货")

    st, r = ship(a2)
    d = db_order(a2)
    check("已付款 → 发货成功（HTTP 200 + code 200）",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    check("发货后响应里 status=2", (r.get("data") or {}).get("status") == S_SHIPPED,
          f"status={(r.get('data') or {}).get('status')}")
    check("★ 直查库：a2 的状态真的是 2（不只是响应说成功）",
          d and d["status"] == S_SHIPPED, f"库里是 {d}")
    check("★ 直查库：ship_time 真的写进去了（不是 NULL）",
          d and d["shipTime"] != "NULL", f"ship_time={d and d['shipTime']}")
    check("发货响应里带 shipTime", bool((r.get("data") or {}).get("shipTime")),
          f"shipTime={(r.get('data') or {}).get('shipTime')}")
    check("发货响应里【没有】completeTime（还没完成）",
          not (r.get("data") or {}).get("completeTime"),
          f"completeTime={(r.get('data') or {}).get('completeTime')}")

    st, r = ship(a2)
    check("★ 重复发货 → 1002（条件更新当闸门）", r.get("code") == 1002,
          f"code={r.get('code')} / {r.get('message')}")

    # ==================================================================
    section("7. ★★ 确认收货：状态推进 + 库存【一件都不变】")

    stock_before = stock_of(pS)
    st, r = complete(TOKEN_A, a4)
    stock_after = stock_of(pS)
    d = db_order(a4)

    check("已发货 → 确认收货成功", st == 200 and r.get("code") == 200,
          f"HTTP {st} / {r}")
    check("确认后响应里 status=3", (r.get("data") or {}).get("status") == S_DONE,
          f"status={(r.get('data') or {}).get('status')}")
    check("★ 直查库：a4 的状态真的是 3", d and d["status"] == S_DONE, f"库里是 {d}")
    check("★ 直查库：complete_time 真的写进去了",
          d and d["completeTime"] != "NULL", f"complete_time={d and d['completeTime']}")

    # ★★ 本脚本最重要的一条断言。
    #    它的价值不在于「现在是对的」，而在于挡住未来某个人
    #    「看到 cancel 要还库存，就顺手给 complete 也补一次」—— 那是超卖。
    check("★★ 确认收货后库存【一件都没变】（不归还库存）",
          stock_before == stock_after,
          f"确认前 {stock_before} → 确认后 {stock_after} —— "
          f"库存被改了！确认收货绝不能归还库存（货已经在买家手里了）")

    # ==================================================================
    section("8. ★ 状态机约束：非法状态一律 1002")

    # ★ 发货：四个非「已付款」状态各断言一次。
    #   只测一个是不够的 —— WHERE status = 1 写错成别的数字时，
    #   如果那个数字恰好是唯一被测的状态，这个错误就漏过去了。
    for order_no, expected_status, label in [
        (a1, S_PENDING, "待付款"),
        (a2, S_SHIPPED, "已发货"),
        (a4, S_DONE, "已完成"),
        (a5, S_CANCELLED, "已取消"),
    ]:
        st, r = ship(order_no)
        check(f"★ {label}的订单不能发货 → 1002", r.get("code") == 1002,
              f"code={r.get('code')} / {r.get('message')}")

    st, r = ship("NO_SUCH_ORDER_12345678")
    check("发货不存在的订单 → 1003", r.get("code") == 1003,
          f"code={r.get('code')} / {r.get('message')}")

    # ★ 确认收货：从「已付款」和「已完成」各断言一次。
    st, r = complete(TOKEN_A, a3)
    check("★ 已付款（还没发货）不能确认收货 → 1002", r.get("code") == 1002,
          f"code={r.get('code')} / {r.get('message')}")

    st, r = complete(TOKEN_A, a4)
    check("★ 已完成的订单不能重复确认收货 → 1002", r.get("code") == 1002,
          f"code={r.get('code')} / {r.get('message')}")

    st, r = complete(TOKEN_A, a1)
    check("待付款不能确认收货 → 1002", r.get("code") == 1002,
          f"code={r.get('code')} / {r.get('message')}")

    # ★ 越权：B 确认 A 的订单。
    #   和「订单不存在」共用 1003 —— 区分开来等于确认了「这个订单号存在」。
    st, r = complete(TOKEN_B, a2)
    check("★ B 确认 A 的订单 → 1003（不泄露订单是否存在）",
          r.get("code") == 1003, f"code={r.get('code')} / {r.get('message')}")
    check("★ B 确认之后 a2 的状态没有被改动",
          db_order(a2)["status"] == S_SHIPPED, f"a2 状态变成了 {db_order(a2)}")

    st, r = call("POST", f"/shop/orders/{a2}/complete", None)
    check("未登录确认收货 → 401", st == 401, f"HTTP {st} / {r}")

    # ==================================================================
    section("9. 状态筛选（在所有状态推进【之后】验，用的才是最终状态）")

    # 最终状态：a1=0 a2=2 a3=1 a4=3 a5=4 a6=0 a7=0
    expected_by_status = {
        S_PENDING: {a1, a6, a7},
        S_PAID: {a3},
        S_SHIPPED: {a2},
        S_DONE: {a4},
        S_CANCELLED: {a5},
    }
    for status, expected in expected_by_status.items():
        got = shop_list(TOKEN_A, status=status, pageSize=50)
        check(f"status={status}（{STATUS_TEXT[status]}）筛选准确 → {len(expected)} 笔",
              got["total"] == len(expected) and set(nos(got)) == expected,
              f"total={got['total']}, list={sorted(nos(got))}")

    # ★★ status=0 必须能真的筛出「待付款」。
    #    这是本里程碑埋得最隐蔽的一个坑：前端如果复用 toPositiveInt
    #    （判据是 n > 0），status=0 会被当成「解析失败」退回 fallback，
    #    于是「待付款」这个 Tab 永远选不中 —— 而它恰恰是用户最常看的那个。
    #    这一条验的是后端，但它证明「0 是一个合法状态值」这件事本身成立。
    st0 = shop_list(TOKEN_A, status=0, pageSize=50)
    check("★★ status=0 能筛出「待付款」（0 是合法状态，不是「全部」）",
          st0["total"] == 3 and set(nos(st0)) == {a1, a6, a7},
          f"total={st0['total']}, list={sorted(nos(st0))}")

    allpage = shop_list(TOKEN_A, pageSize=50)
    check("★ 「全部」（不传 status）包含了已取消的订单",
          a5 in nos(allpage), "已取消的订单没有出现在「全部」里")
    check("★ 「全部」共 7 笔 = 各状态笔数之和",
          allpage["total"] == 7, f"total={allpage['total']}")

    # ★ 这里用裸 call 而不是 shop_list：shop_list 遇到非 200 会 SystemExit，
    #   而这两条用例【本来就是】在验「非 200 / 非报错」的行为，
    #   用那个 helper 会把「断言失败」变成「脚本崩掉」。
    st, r = call("GET", "/shop/orders?status=99&pageSize=50", None, token=TOKEN_A)
    check("status=99（不存在的状态）→ 200 + 空结果（非法值看不到不该看的就静默返回空）",
          st == 200 and r.get("code") == 200
          and (r.get("data") or {}).get("total") == 0
          and (r.get("data") or {}).get("list") == [],
          f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/orders?status=abc", None, token=TOKEN_A)
    check("status=abc（非整数）→ HTTP 400（参数格式错是协议错，不是业务错）",
          st == 400, f"HTTP {st} / {r}")

    # ==================================================================
    section("10. ★ 鉴权：两端 token 不能互用")

    st, r = call("GET", "/admin/orders", None, token=TOKEN_A)
    check("★ 会员 token 调管理端订单接口 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("POST", f"/admin/orders/{a1}/ship", None, token=TOKEN_A)
    check("★ 会员 token 调发货接口 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/orders", None, token=ADMIN_TOKEN)
    check("★ 管理员 token 调用户端订单列表 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("POST", f"/shop/orders/{a1}/complete", None, token=ADMIN_TOKEN)
    check("★ 管理员 token 调确认收货 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/orders", None)
    check("未登录查订单列表 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", "/admin/orders", None)
    check("未登录查管理端订单列表 → 401", st == 401, f"HTTP {st} / {r}")

    # ==================================================================
    section("11. 清理与核对")

    cleanup()
    cleanup_redis()

    left = run_sql(
        f"SELECT (SELECT COUNT(*) FROM orders o JOIN member m ON m.id=o.member_id "
        f"        WHERE m.username LIKE '{PREFIX}%'), "
        f"       (SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'), "
        f"       (SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%')")
    check("测试订单已清理", int(left[0][0]) == 0, f"还剩 {left[0][0]} 笔")
    check("测试商品已清理", int(left[0][1]) == 0, f"还剩 {left[0][1]} 个")
    check("测试会员已清理", int(left[0][2]) == 0, f"还剩 {left[0][2]} 个")

    orphans = run_sql(
        "SELECT COUNT(*) FROM order_item i "
        "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")
    check("★ 没有孤儿订单明细（order_item 都指向真实存在的订单）",
          int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行明细指向不存在的订单 —— 多半是删订单时忘了先删明细")

    leftover_items = run_sql(
        "SELECT COUNT(*) FROM order_item i "
        f"JOIN orders o ON o.id = i.order_id "
        f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    check("测试明细已清理（先删明细再删订单）", int(leftover_items[0][0]) == 0,
          f"还剩 {leftover_items[0][0]} 行")

    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个".format(
        run_sql("SELECT COUNT(*) FROM product")[0][0],
        run_sql("SELECT COUNT(*) FROM category")[0][0],
        run_sql("SELECT COUNT(*) FROM member")[0][0]))

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
