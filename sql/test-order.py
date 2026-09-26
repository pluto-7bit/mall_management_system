# -*- coding: utf-8 -*-
"""
里程碑 8 测试（二）：下单

这是整个项目里最需要认真写的一个测试脚本，因为下单模块有三个
「平时看不出问题、一出问题就是真金白银」的地方：

  1. ★★ 超卖 —— 两个用户同时买最后一件，只能有一个人成功
  2. ★★ 幂等 —— 网络超时用户重复点提交，只能产生一笔订单
  3. ★★ 事务回滚 —— 一笔订单里有个商品库存不够，前面已经扣掉的
     库存必须一起还回来

这三条都是【并发/异常】场景，靠手点浏览器几乎验证不到 ——
这就是必须有脚本的原因。第 1 条用真的多线程并发来测。

另外还有两条安全用例：
  4. ★ 收货地址的归属（不能把货寄到别人的地址）
  5. ★★ 幂等键按会员隔离（A 和 B 用同一个键，各自都能下单）
     —— 这条专门验证 migration-08b 改的那个索引

运行：
    python test-order.py
"""

import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from decimal import Decimal

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "ordertest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None

# 两个会员，分别用来测「越权」和「幂等键隔离」
TOKEN_A = None
ID_A = None
TOKEN_B = None
ID_B = None

# 用来清理它们的购物车 Redis key（只清自己的，不动别人的）
TEST_MEMBER_IDS = []


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


def stock_of(sku_id):
    """★ 里程碑 15 阶段 4：库存的唯一真源是 product_sku.stock。

    以前读的是 product.stock。阶段 2~5 期间那列还在（回滚预案），
    所以读错了【不会报错】—— 只是数字永远停在阶段 1 迁移时的那个值，
    所有「库存扣了 1」的断言会集体红，而原因看起来会是「扣库存没生效」。
    """
    rows = run_sql(f"SELECT stock FROM product_sku WHERE id = {sku_id}")
    return int(rows[0][0]) if rows else None


def set_stock(sku_id, stock):
    run_sql(f"UPDATE product_sku SET stock = {stock} WHERE id = {sku_id}")


def set_status(pid, status):
    """⚠️ 参数是【商品 id】：下架是商品级操作（整个商品连同它的全部规格
    一起不可售），所以它必须走 product 表。调用处要写 pid_of_sku(...)。"""
    run_sql(f"UPDATE product SET status = {status} WHERE id = {pid}")


def default_sku_of(pid):
    """无规格商品的那唯一一条「默认 SKU」的 id。"""
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid}"))


def pid_of_sku(sku_id):
    """SKU id → 商品 id。只给「按商品整体」的操作（下架、删除）用。"""
    return int(scalar(f"SELECT product_id FROM product_sku WHERE id = {sku_id}"))


def order_rows(member_id):
    """直接查库看订单，不通过接口。用来验证「响应说成功，库里真的有」。"""
    return run_sql(
        f"SELECT order_no, total_amount, status, receiver_name, receiver_address "
        f"FROM orders WHERE member_id = {member_id} ORDER BY id")


def order_count(member_id):
    rows = run_sql(f"SELECT COUNT(*) FROM orders WHERE member_id = {member_id}")
    return int(rows[0][0])


def item_rows(order_no):
    return run_sql(
        f"SELECT i.product_name, i.price, i.quantity, i.subtotal, i.sku_id, i.sku_spec "
        f"FROM order_item i JOIN orders o ON o.id = i.order_id "
        f"WHERE o.order_no = '{order_no}' ORDER BY i.id")


def cart_keys_for(member_id):
    """这个会员购物车里剩下的【规格 id】（空列表表示车是空的）。

    ★ 里程碑 15 阶段 4：field 从商品 id 换成了规格 id。
      无规格商品一一对应，所以这个函数的返回值在【数量】上和以前一样，
      只是数字的含义变了。
    """
    out = redis_cmd("HKEYS", f"mall:cart:{member_id}")
    return sorted(int(x) for x in out) if out else []


def cart_qty(member_id, sku_id):
    out = redis_cmd("HGET", f"mall:cart:{member_id}", str(sku_id))
    return int(out[0]) if out else 0


# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(run_sql("SELECT id FROM category ORDER BY id LIMIT 1")[0][0])


def register(tag):
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}{tag}{RUN}",
        "password": "order123456",
        "nickname": f"订单测试{tag}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    d = r["data"]
    TEST_MEMBER_IDS.append(d["id"])
    return d["token"], d["id"]


def scalar(sql, default=None):
    rows = run_sql(sql)
    return rows[0][0] if rows else default


def cleanup():
    # ★ 顺序：晒图 → 评价 → 明细 → 订单 → 地址 → 会员。
    #   虽然没加外键（故意不加），但按这个顺序删读起来最清楚。
    #
    # ★ 里程碑 12 新增最前面两层：评价挂在 order_item 上（uk_order_item），
    #   晒图挂在评价上。本脚本不写评价，所以今天这两条删的是 0 行 ——
    #   加它们的理由和里程碑 11 给 test-shop-product.py 补删图集一样：
    #   【顺序正确比这次侥幸没出事重要】。
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
    # ★ 只删这次测试用到的会员的购物车，不用 KEYS mall:cart:* 一锅端 ——
    #   Redis 上可能还有真实使用留下的购物车，测试脚本不该动它。
    for mid in TEST_MEMBER_IDS:
        redis_cmd("DEL", f"mall:cart:{mid}")


def create_product(name, price, stock, status=1):
    # 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品也要显式给一条
    # 「默认 SKU」（specs 为空数组），后端拿它的 price/stock 作为这件商品的价格和库存。
    st, r = call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name, "status": status,
        "specSchema": [],
        "skus": [{"specs": [], "price": price, "stock": stock}],
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    # ★ 里程碑 15 阶段 4：返回的是【SKU id】—— 下单、扣库存、购物车
    #   现在全都按规格走。需要商品 id 的地方显式写 pid_of_sku(...)。
    return default_sku_of(r["data"])


def create_address(token, receiver, phone, region, detail):
    st, r = call("POST", "/shop/addresses", {
        "receiver": receiver, "phone": phone, "region": region, "detail": detail,
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"建测试地址失败：HTTP {st} / {r}")
    return r["data"]


def key_for(tag):
    """生成一个合法的幂等键。

    必须符合后端 @Pattern("^[A-Za-z0-9_-]{8,64}$")，
    所以不能用 uuid4() 之外的花样 —— 这里就用「前缀+标签+时间戳」，
    顺便让日志里能看出是哪个用例建的。
    """
    return f"k{RUN}{tag}"


def buy_now(token, sku_id, qty, address_id, idem_key, remark=None):
    body = {"skuId": sku_id, "quantity": qty,
            "addressId": address_id, "idempotencyKey": idem_key}
    if remark is not None:
        body["remark"] = remark
    return call("POST", "/shop/orders/buy-now", body, token=token)


def cart_order(token, sku_ids, address_id, idem_key, remark=None):
    body = {"skuIds": sku_ids,
            "addressId": address_id, "idempotencyKey": idem_key}
    if remark is not None:
        body["remark"] = remark
    return call("POST", "/shop/orders", body, token=token)


def cart_add(token, sku_id, qty):
    return call("POST", "/shop/cart/items", {"skuId": sku_id, "quantity": qty},
                token=token)


# ======================================================================
def main():
    global TOKEN_A, ID_A, TOKEN_B, ID_B

    cleanup()
    admin_login()
    TOKEN_A, ID_A = register("a")
    TOKEN_B, ID_B = register("b")

    print()
    print(f"本次运行 TAG = {TAG}")
    print(f"会员 A id={ID_A}，会员 B id={ID_B}")

    # ---- 测试商品 ----
    # 每个商品的库存都是精挑的，为了让每条用例能「唯一地」暴露问题：
    p1 = create_product(f"{PREFIX}普通100", 100.00, 100)   # 常规
    p2 = create_product(f"{PREFIX}稀缺3", 200.00, 3)       # 库存很小，测超卖
    p3 = create_product(f"{PREFIX}小数33点33", 33.33, 100)   # 测小数金额
    p4 = create_product(f"{PREFIX}一毛", 0.10, 1000)        # 测 BigDecimal 精度
    p5 = create_product(f"{PREFIX}五件", 50.00, 5)          # 测并发超卖

    addr_a = create_address(TOKEN_A, "测试收货人A", "13800138001",
                            "广东省深圳市南山区", "科技园路 1 号")
    addr_b = create_address(TOKEN_B, "测试收货人B", "13900139002",
                            "北京市朝阳区", "望京 SOHO T1")

    print(f"测试商品 id：p1={p1} p2={p2} p3={p3} p4={p4} p5={p5}")
    print(f"地址：A={addr_a} B={addr_b}")

    # ==================================================================
    section("1. ★ 订单接口必须登录（路径没被排除出拦截器）")

    for method, path, body in [
        ("POST", "/shop/orders", {"skuIds": [1], "addressId": 1,
                                  "idempotencyKey": "anonkey12345678"}),
        ("POST", "/shop/orders/buy-now", {"skuId": 1, "quantity": 1,
                                          "addressId": 1,
                                          "idempotencyKey": "anonkey12345678"}),
    ]:
        st, r = call(method, path, body)
        check(f"游客 {method} {path} → 401", st == 401, f"HTTP {st} / {r}")

    # 对照：商品浏览和分类仍然匿名可访问
    st, r = call("GET", "/shop/products?pageSize=1")
    check("（对照）商品列表仍匿名可访问 —— 没被误伤",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")

    # 购物车仍然受保护（回归）
    st, r = call("GET", "/shop/cart")
    check("（回归）购物车仍需要登录", st == 401, f"HTTP {st} / {r}")

    # ==================================================================
    section("2. 参数校验")

    cases = [
        ({"skuIds": [p1], "addressId": addr_a}, "不传幂等键"),
        ({"skuIds": [p1], "idempotencyKey": key_for("v1")}, "不传收货地址"),
        ({"skuIds": [], "addressId": addr_a, "idempotencyKey": key_for("v2")}, "规格列表为空"),
        ({"addressId": addr_a, "idempotencyKey": key_for("v3")}, "不传商品列表"),
        ({"skuIds": [p1], "addressId": addr_a, "idempotencyKey": "短"},
         "幂等键太短（<8）"),
        ({"skuIds": [p1], "addressId": addr_a, "idempotencyKey": "有中文的键啊啊啊"},
         "幂等键含非法字符"),
        ({"skuIds": [p1], "addressId": addr_a, "idempotencyKey": "k" * 65},
         "幂等键超 64 位"),
        ({"skuIds": [p1], "addressId": addr_a, "idempotencyKey": key_for("v4"),
          "remark": "备" * 256}, "备注超 255 字"),
    ]
    for body, desc in cases:
        st, r = call("POST", "/shop/orders", body, token=TOKEN_A)
        check(f"{desc} → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = call("POST", "/shop/orders", {}, token=TOKEN_A)
    check("空 body → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    # 立即购买的数量校验
    for qty, desc in [(0, "买 0 件"), (-1, "买 -1 件"), (1000, "买 1000 件")]:
        st, r = buy_now(TOKEN_A, p1, qty, addr_a, key_for(f"q{qty}"))
        check(f"立即购买 {desc} → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    check("★ 以上被拒绝的请求都没有产生订单（校验失败不留脏数据）",
          order_count(ID_A) == 0, f"A 名下有 {order_count(ID_A)} 笔订单，期望 0")

    # ==================================================================
    section("3. ★★ 收货地址归属：不能把货寄到别人的地址")

    st, r = buy_now(TOKEN_A, p1, 1, addr_b, key_for("steal"))
    check("★★ A 用 B 的地址下单 → 业务码 1003「收货地址不存在」",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = cart_order(TOKEN_A, [p1], addr_b, key_for("steal2"))
    check("★★ 购物车结算用 B 的地址 → 也是 1003",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = buy_now(TOKEN_A, p1, 1, 99999999, key_for("noaddr"))
    check("用不存在的地址 id → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    check("★★ 这些请求都没有产生订单（拦截在写库之前）",
          order_count(ID_A) == 0 and order_count(ID_B) == 0,
          f"A={order_count(ID_A)}, B={order_count(ID_B)}")
    check("★★ 被拒绝后 p1 的库存没变（一克都没扣）",
          stock_of(p1) == 100, f"stock = {stock_of(p1)}")

    # ==================================================================
    section("4. 购物车结算：完整流程 + 落库核对")

    cart_add(TOKEN_A, p1, 2)
    cart_add(TOKEN_A, p3, 1)
    check("（准备）A 的购物车：p1×2 + p3×1",
          cart_keys_for(ID_A) == [p1, p3], f"{cart_keys_for(ID_A)}")

    stock_p1_before = stock_of(p1)
    stock_p3_before = stock_of(p3)

    st, r = cart_order(TOKEN_A, [p1, p3], addr_a, key_for("c1"), remark="工作日送")
    check("购物车结算 → HTTP 200 + 业务码 200", r.get("code") == 200, f"HTTP {st} / {r}")

    d = r.get("data") or {}
    order_no = d.get("orderNo")
    check("返回了订单号", isinstance(order_no, str) and len(order_no) == 20,
          f"orderNo = {order_no!r}（期望 14 位时间 + 6 位随机 = 20 位）")
    check("★ 新订单状态是「待付款」(0)", d.get("status") == 0, f"status = {d.get('status')}")

    # ★ 金额用 Decimal 算，和 BigDecimal 一样是精确十进制
    expect_total = Decimal("100.00") * 2 + Decimal("33.33") * 1
    got_total = Decimal(str(d.get("totalAmount")))
    check(f"★ 总金额 = 100.00×2 + 33.33×1 = {expect_total}",
          got_total == expect_total, f"实际 = {got_total}")

    items = d.get("items") or []
    check("明细两条", len(items) == 2, f"{items}")
    if len(items) == 2:
        check("明细 1：商品名/单价/数量/小计都对",
              items[0]["productName"].startswith(PREFIX)
              and Decimal(str(items[0]["price"])) == Decimal("100.00")
              and items[0]["quantity"] == 2
              and Decimal(str(items[0]["subtotal"])) == Decimal("200.00"),
              f"{items[0]}")
        check("明细 2：小计 = 33.33 × 1 = 33.33",
              Decimal(str(items[1]["subtotal"])) == Decimal("33.33"),
              f"{items[1]}")

    # ---- ★★ 响应说成功，库里必须真的有。三重核对：----
    rows = order_rows(ID_A)
    check("★★ 数据库里确实有这笔订单（不只看响应）",
          len(rows) == 1 and rows[0][0] == order_no, f"{rows}")
    if rows:
        check("★ 库里的金额和响应一致",
              Decimal(rows[0][1]) == expect_total, f"库里 = {rows[0][1]}")
        check("★★ 收货信息是【快照】——存的是当时的姓名和地址",
              rows[0][3] == "测试收货人A"
              and rows[0][4] == "广东省深圳市南山区 科技园路 1 号",
              f"receiver_name={rows[0][3]!r}, address={rows[0][4]!r}")

    check("★★ 明细写进了 order_item 表（两行）",
          len(item_rows(order_no)) == 2, f"{item_rows(order_no)}")

    check("★★ 库存被扣了：p1 100→98",
          stock_of(p1) == stock_p1_before - 2, f"stock = {stock_of(p1)}")
    check("★★ 库存被扣了：p3 100→99",
          stock_of(p3) == stock_p3_before - 1, f"stock = {stock_of(p3)}")

    check("★★ 购物车被清空了（结算过的商品不留在车里）",
          cart_keys_for(ID_A) == [], f"还剩 {cart_keys_for(ID_A)}")

    # ==================================================================
    section("5. ★ 部分结算：只买勾选的那几个")

    cart_add(TOKEN_A, p1, 1)
    cart_add(TOKEN_A, p3, 1)
    cart_add(TOKEN_A, p4, 1)
    check("（准备）车里有 p1/p3/p4 三件",
          cart_keys_for(ID_A) == [p1, p3, p4], f"{cart_keys_for(ID_A)}")

    s1, s3, s4 = stock_of(p1), stock_of(p3), stock_of(p4)
    st, r = cart_order(TOKEN_A, [p1], addr_a, key_for("partial"))
    check("只结算 p1 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 订单里只有 1 条明细", len((r.get("data") or {}).get("items") or []) == 1,
          f"{(r.get('data') or {}).get('items')}")

    check("★★ p1 库存扣了 1", stock_of(p1) == s1 - 1, f"{s1} → {stock_of(p1)}")
    check("★★ p3 库存【没动】", stock_of(p3) == s3, f"{s3} → {stock_of(p3)}")
    check("★★ p4 库存【没动】", stock_of(p4) == s4, f"{s4} → {stock_of(p4)}")
    check("★★ 购物车里只剩没结算的 p3/p4",
          cart_keys_for(ID_A) == [p3, p4], f"还剩 {cart_keys_for(ID_A)}")

    # ==================================================================
    section("6. ★★ 幂等：同一个键提交两次只能产生一笔订单")

    cart_add(TOKEN_A, p2, 1)
    stock_before = stock_of(p2)
    idem = key_for("idem")

    st1, r1 = cart_order(TOKEN_A, [p2], addr_a, idem)
    check("第一次提交 → 成功", r1.get("code") == 200, f"HTTP {st1} / {r1}")
    no1 = (r1.get("data") or {}).get("orderNo")

    # ★ 第二次提交：完全相同的请求（同一个幂等键）
    st2, r2 = cart_order(TOKEN_A, [p2], addr_a, idem)
    check("★★ 第二次提交（同一个幂等键）→ 仍然成功，不是报错",
          r2.get("code") == 200, f"HTTP {st2} / {r2}")
    no2 = (r2.get("data") or {}).get("orderNo")
    check("★★ 返回的是【同一笔订单】（orderNo 完全相同）",
          no1 == no2 and no1 is not None, f"第一次={no1}, 第二次={no2}")
    check("★ 连订单 id 也一样",
          (r1.get("data") or {}).get("id") == (r2.get("data") or {}).get("id"),
          f"{(r1.get('data') or {}).get('id')} vs {(r2.get('data') or {}).get('id')}")

    check("★★ 数据库里只有一笔用这个键的订单",
          run_sql(f"SELECT COUNT(*) FROM orders WHERE idempotency_key = '{idem}'")[0][0] == "1",
          "订单数 = " + run_sql(
              f"SELECT COUNT(*) FROM orders WHERE idempotency_key = '{idem}'")[0][0])
    check("★★ 库存只被扣了一次（这是最关键的一条：钱只扣一次）",
          stock_of(p2) == stock_before - 1, f"{stock_before} → {stock_of(p2)}")

    # ★ 用立即购买再验一遍（两个入口都要有幂等）
    idem2 = key_for("idem2")
    st1, r1 = buy_now(TOKEN_A, p1, 1, addr_a, idem2)
    st2, r2 = buy_now(TOKEN_A, p1, 1, addr_a, idem2)
    check("★★ 立即购买也幂等：两次返回同一个 orderNo",
          (r1.get("data") or {}).get("orderNo") == (r2.get("data") or {}).get("orderNo")
          and r1.get("code") == 200,
          f"{r1.get('data', {}).get('orderNo')} vs {r2.get('data', {}).get('orderNo')}")

    # ==================================================================
    section("7. ★ 幂等键不是「去重一切」：换个键就该产生新订单")

    before = order_count(ID_A)
    st, r = cart_order(TOKEN_A, [p3], addr_a, key_for("fresh1"))
    check("换个新键 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★★ 产生了【新】订单（用户确实想买两次是允许的）",
          order_count(ID_A) == before + 1,
          f"{before} → {order_count(ID_A)}")

    # 同一个键换个地址，应该返回【原来那笔】，而不是新建
    before = order_count(ID_A)
    st, r = cart_order(TOKEN_A, [p3], addr_b, key_for("fresh1"))
    check("★★ 同一个键换地址再提交 → 返回原来那笔，不新建",
          order_count(ID_A) == before,
          f"{before} → {order_count(ID_A)}")

    # ==================================================================
    section("8. ★★ 幂等键按会员隔离（验证 migration-08b 改的索引）")

    shared_key = key_for("shared")
    check("（准备）两个会员用【完全相同】的幂等键",
          len(shared_key) <= 64, shared_key)

    st_a, ra = buy_now(TOKEN_A, p1, 1, addr_a, shared_key)
    st_b, rb = buy_now(TOKEN_B, p1, 1, addr_b, shared_key)

    check("★★ A 用这个键能下单（不会被 B 的存在挡住）",
          ra.get("code") == 200, f"HTTP {st_a} / {ra}")
    check("★★ B 用同一个键也能下单（这就是把它从全局唯一改成按会员唯一的意义）",
          rb.get("code") == 200, f"HTTP {st_b} / {rb}")

    no_a = (ra.get("data") or {}).get("orderNo")
    no_b = (rb.get("data") or {}).get("orderNo")
    check("★★ 两笔是不同的订单", no_a != no_b and no_a and no_b, f"{no_a} vs {no_b}")

    rows = run_sql(f"SELECT member_id FROM orders WHERE idempotency_key = '{shared_key}' "
                   f"ORDER BY member_id")
    check("★★ 数据库里这个键有 2 行，分属两个会员",
          len(rows) == 2 and {int(rows[0][0]), int(rows[1][0])} == {ID_A, ID_B},
          f"{rows}")

    # 自己再用一次，仍然幂等
    st, r = buy_now(TOKEN_A, p1, 1, addr_a, shared_key)
    check("★ A 再用这个键 → 还是返回 A 自己那笔",
          (r.get("data") or {}).get("orderNo") == no_a,
          f"{(r.get('data') or {}).get('orderNo')} vs {no_a}")
    check("★ 没有产生第 3 笔订单",
          run_sql(f"SELECT COUNT(*) FROM orders WHERE idempotency_key = '{shared_key}'")[0][0] == "2",
          "订单数 = " + run_sql(
              f"SELECT COUNT(*) FROM orders WHERE idempotency_key = '{shared_key}'")[0][0])

    # ==================================================================
    section("9. ★★ 库存不足不能超卖（顺序场景）")

    set_stock(p2, 3)
    st, r = buy_now(TOKEN_A, p2, 3, addr_a, key_for("buy3"))
    check("（准备）买走全部 3 件 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 库存归零", stock_of(p2) == 0, f"stock = {stock_of(p2)}")

    st, r = buy_now(TOKEN_A, p2, 1, addr_a, key_for("buy4"))
    check("★★ 库存 0 时再买 1 件 → 业务码 1001「库存不足」",
          r.get("code") == 1001, f"HTTP {st} / {r}")
    check("★ 错误信息里带着准确的剩余数量（失败路径上重新查过库）",
          "仅剩 0 件" in (r.get("message") or ""),
          f"message = {r.get('message')!r}")
    check("★★ 库存没有被扣成负数", stock_of(p2) == 0, f"stock = {stock_of(p2)}")

    # 买超过 99 件（业务上限，不是库存）
    set_stock(p2, 500)
    st, r = buy_now(TOKEN_A, p2, 100, addr_a, key_for("buy100"))
    check("★★ 买 100 件（超单商品上限 99）→ 业务码 1008",
          r.get("code") == 1008, f"HTTP {st} / {r}")
    check("★ 提示里说的是「最多购买 99 件」而不是库存不足",
          "99" in (r.get("message") or ""), f"message = {r.get('message')!r}")
    check("★ 库存没动", stock_of(p2) == 500, f"stock = {stock_of(p2)}")

    st, r = buy_now(TOKEN_A, p2, 99, addr_a, key_for("buy99"))
    check("★ 买 99 件（正好到上限）→ 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 库存扣了 99", stock_of(p2) == 401, f"stock = {stock_of(p2)}")

    # ==================================================================
    section("10. ★★★ 并发超卖：20 个线程抢 5 件")

    set_stock(p5, 5)
    results = []
    lock = threading.Lock()

    def worker(idx):
        # 每个线程用【各自不同】的幂等键 —— 它们代表 20 个不同的下单意图
        st, r = buy_now(TOKEN_A, p5, 1, addr_a, f"k{RUN}race{idx:02d}")
        with lock:
            results.append((st, r.get("code"), r.get("message")))

    before_orders = order_count(ID_A)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok_count = sum(1 for _, code, _ in results if code == 200)
    not_enough = sum(1 for _, code, _ in results if code == 1001)
    other = [(st, code, msg) for st, code, msg in results if code not in (200, 1001)]

    print(f"  20 个并发请求结果：成功 {ok_count} 个，库存不足 {not_enough} 个，"
          f"其他 {len(other)} 个")

    check(f"★★★ 恰好只有 5 个成功（库存是 5，多一个都不行）",
          ok_count == 5, f"成功 {ok_count} 个，期望 5 个。"
                         f"如果是 6 个以上就是超卖，是必须修的 bug")
    check("★★★ 其余的 15 个都是「库存不足」(1001)，不是报 500",
          not_enough == 15, f"1001 有 {not_enough} 个，期望 15 个")
    check("★★★ 没有出现其他错误（说明失败的都是干净的业务失败，不是系统异常）",
          len(other) == 0, f"{other}")
    check("★★★ 库存精确归零 —— 不是 0 以下，也不是还有剩",
          stock_of(p5) == 0, f"stock = {stock_of(p5)}")
    check("★★★ 数据库里【恰好】多了 5 笔订单",
          order_count(ID_A) == before_orders + 5,
          f"{before_orders} → {order_count(ID_A)}，期望 +5")
    check("★★★ 没有「扣了库存但没有订单」的情况（不会出现负库存的另一种表现）",
          stock_of(p5) == 0 and order_count(ID_A) == before_orders + 5,
          f"stock={stock_of(p5)}, orders={order_count(ID_A)}")

    # ==================================================================
    section("11. ★★ 事务回滚：一笔订单里有商品库存不够，前面扣的要还回来")

    # 构造场景：购物车里有 p1 和 p2，p1 库存充足、p2 不足。
    # 下单时 p1 先被扣（productIds 的顺序决定），然后 p2 失败 ——
    # 如果没有事务，p1 的库存就白扣了。
    #
    # ★ 先把车清空。前面几节会在车里留下东西（比如第 7 节结算剩下的 p4），
    #   不清掉的话，下面「车里应该恰好是这两件」的断言就会因为
    #   无关的遗留而失败 —— 那是测试自己写得不干净，不是代码有问题。
    #   **每个用例都应该自己准备好干净的初始状态，不依赖前面用例的副作用。**
    redis_cmd("DEL", f"mall:cart:{ID_A}")
    cart_add(TOKEN_A, p1, 1)
    cart_add(TOKEN_A, p2, 1)
    check("（准备）车里恰好是 p1 和 p2",
          cart_keys_for(ID_A) == sorted([p1, p2]), f"{cart_keys_for(ID_A)}")
    set_stock(p2, 0)          # ★ 加完购物车之后再把 p2 的库存改成 0

    p1_before = stock_of(p1)
    orders_before = order_count(ID_A)

    st, r = cart_order(TOKEN_A, [p1, p2], addr_a, key_for("rollback"))
    check("含库存不足商品的整单下单 → 业务码 1001",
          r.get("code") == 1001, f"HTTP {st} / {r}")

    check("★★★ p1 的库存【回到了下单前】—— 前一步的扣减被事务回滚了",
          stock_of(p1) == p1_before,
          f"{p1_before} → {stock_of(p1)}。"
          f"如果少了 1，就说明扣库存没被事务管住，这是很严重的脏数据")
    check("★★ 没有产生订单", order_count(ID_A) == orders_before,
          f"{orders_before} → {order_count(ID_A)}")
    check("★★ 没有产生订单明细",
          run_sql(f"SELECT COUNT(*) FROM order_item i JOIN orders o ON o.id = i.order_id "
                  f"WHERE o.member_id = {ID_A} AND o.idempotency_key = "
                  f"'{key_for('rollback')}'")[0][0] == "0", "有残留明细")
    check("★★ 购物车没有被清（订单没成功，东西不该消失）",
          cart_keys_for(ID_A) == [p1, p2], f"还剩 {cart_keys_for(ID_A)}")

    # ★ 这条最能说明「afterCommit 的必要性」：
    #   如果清购物车写在事务里，上面这次失败会把购物车清掉，而订单没生成。
    check("★★★ 失败后购物车里的数量也没变（清购物车确实在事务之外）",
          cart_qty(ID_A, p1) == 1 and cart_qty(ID_A, p2) == 1,
          f"p1={cart_qty(ID_A, p1)}, p2={cart_qty(ID_A, p2)}")

    # 收拾干净
    set_stock(p2, 500)

    # ==================================================================
    section("12. 购物车里没有的商品不能结算")

    st, r = cart_order(TOKEN_A, [p1, p5], addr_a, key_for("notincart"))
    check("★★ 结算一个不在车里的商品（p5）→ 业务码 1003",
          r.get("code") == 1003, f"HTTP {st} / {r}")
    check("★ 提示是「购物车里没有这件商品」",
          "购物车" in (r.get("message") or ""), f"message = {r.get('message')!r}")

    check("★ 整单失败，p1 的库存没被扣",
          stock_of(p1) == p1_before, f"stock = {stock_of(p1)}")

    # 传一个不存在的商品 id
    st, r = cart_order(TOKEN_A, [88888888], addr_a, key_for("ghost"))
    check("结算一个不存在的商品 id → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # 用 SQL 直接往购物车里塞一个不存在的商品，看会不会被拦下
    # ★ 这是「绕过前端直接调接口」的模拟 —— 库存校验必须在服务端
    redis_cmd("HSET", f"mall:cart:{ID_A}", "88888888", "1")
    st, r = cart_order(TOKEN_A, [88888888], addr_a, key_for("ghost2"))
    check("★★ 直接往购物车里塞不存在的商品再结算 → 1003（服务端会验）",
          r.get("code") == 1003, f"HTTP {st} / {r}")
    redis_cmd("HDEL", f"mall:cart:{ID_A}", "88888888")

    # ==================================================================
    section("13. ★ 下架商品不能下单")

    p_off = create_product(f"{PREFIX}待下架", 66.00, 100)
    cart_add(TOKEN_A, p_off, 1)
    set_status(pid_of_sku(p_off), 0)     # 加完购物车之后再下架

    st, r = cart_order(TOKEN_A, [p_off], addr_a, key_for("offline"))
    check("★★ 结算已下架的商品 → 业务码 1003",
          r.get("code") == 1003, f"HTTP {st} / {r}")
    check("★ 库存没被扣", stock_of(p_off) == 100, f"stock = {stock_of(p_off)}")

    st, r = buy_now(TOKEN_A, p_off, 1, addr_a, key_for("offline2"))
    check("立即购买已下架的商品 → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # ==================================================================
    section("14. 立即购买【不碰】购物车")

    redis_cmd("DEL", f"mall:cart:{ID_A}")
    cart_add(TOKEN_A, p1, 7)
    check("（准备）车里放 7 件 p1 当诱饵", cart_qty(ID_A, p1) == 7,
          f"{cart_qty(ID_A, p1)}")

    st, r = buy_now(TOKEN_A, p4, 3, addr_a, key_for("bn1"))
    check("立即购买 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 订单数量是请求里传的 3",
          ((r.get("data") or {}).get("items") or [{}])[0].get("quantity") == 3,
          f"{(r.get('data') or {}).get('items')}")

    check("★★ 购物车里那 7 件【还在】—— 立即购买不清购物车",
          cart_qty(ID_A, p1) == 7, f"车里 p1 = {cart_qty(ID_A, p1)}")
    check("★★ 而且没有把 p4 加进购物车",
          cart_keys_for(ID_A) == [p1], f"{cart_keys_for(ID_A)}")

    # 金额精度：0.10 × 3 必须是 0.30，不能是 0.30000000000000004
    #
    # ★★ 里程碑 17：这里拆成了三条，因为 total_amount 的语义【变了】。
    #   它现在是「实付 = 商品小计 + 运费」，继续断言 totalAmount == 0.30
    #   等于在断言一句旧的话 —— 而运费是真实存在、必须被收的钱（0.30 远不到包邮门槛）。
    #
    #   ★ 但精度这件事本身仍然要测，只是该看【明细小计】那个数：
    #     它是 price × quantity 的直接结果，也正是精度问题会出现的地方。
    #
    #   ★ 这里【刻意不写死 10.00】。运费的具体值和 99/98.99 两侧的边界
    #     由 test-after-sale.py 去测（它从 application.yml 里读门槛和运费，
    #     不靠抄）。这里只测那条【与配置无关的不变量】，
    #     所以运营改运费规则不会让这条断言变红，而真正的 bug 一定会让它红。
    data = r.get("data") or {}
    item0 = (data.get("items") or [{}])[0]
    subtotal = Decimal(str(item0.get("subtotal")))
    freight = Decimal(str(data.get("freightAmount")))
    total = Decimal(str(data.get("totalAmount")))

    check("★★ 0.10 × 3 = 0.30（BigDecimal 精度，不能用 double）",
          subtotal == Decimal("0.30"), f"实际 = {subtotal}")

    check("★ 这单不到包邮门槛，运费不是 0（★ 忘了给 insert 加 freight_amount 就是这里红）",
          freight > 0, f"实际 = {freight}")

    check("★★ 实付 = 明细小计 + 运费（total_amount 的新语义）",
          total == subtotal + freight,
          f"实付 {total} / 明细 {subtotal} + 运费 {freight}")

    # 立即购买用的幂等键和购物车结算的互不干扰
    check("★ 上面几笔立即购买的订单都落库了",
          order_count(ID_A) > 0, f"{order_count(ID_A)}")

    # ==================================================================
    section("15. 清理与不污染")

    cleanup_redis()
    # ★ 只用 cleanup() 一个入口，不要再在外面单独写一句
    #   DELETE FROM orders —— 第一版就是那么写的，结果：
    #     先 DELETE orders（不带明细）→ 再 cleanup() 里那句按 member 关联删明细
    #     已经找不到订单了 → 明细永远留在库里，成了孤儿。
    #   一次清理有两条路径，就必然会漏。
    #   **清理逻辑也只能有一处。**
    cleanup()

    left = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
    check("测试会员已删除", int(left[0][0]) == 0, f"{left}")
    left = run_sql(f"SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'")
    check("测试商品已删除", int(left[0][0]) == 0, f"{left}")
    left = run_sql(
        f"SELECT COUNT(*) FROM orders o JOIN member m ON m.id = o.member_id "
        f"WHERE m.username LIKE '{PREFIX}%'")
    check("★ 测试订单已删除", int(left[0][0]) == 0, f"剩余 {left[0][0]} 条")

    # ★★ 孤儿明细检查：order_item 里有没有指向不存在订单的行。
    #
    #    加这条检查是因为**第一版确实漏了 32 行**：当时在外面多写了一句
    #    DELETE FROM orders，把订单删了却留下了明细。而 order_item.order_id
    #    【故意没有加外键】（见 mall.sql 的说明），所以数据库不会拦，
    #    也不会有任何报错 —— 数据就这么脏掉了，还看不见。
    #
    #    ★ 这就是"故意不加外键"的代价，必须是**主动**去查才知道。
    #      代价本身是值得付的（加外键就删不掉订单了），
    #      但付代价的方式应该是"多一条检查"，而不是"指望没人犯这个错"。
    orphans = run_sql("SELECT COUNT(*) FROM order_item i "
                      "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")
    check("★★ 没有孤儿订单明细（order_item 都指向真实存在的订单）",
          int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行明细指向不存在的订单 —— "
          f"多半是删订单时忘了先删明细")

    # 用户原有数据核对
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
