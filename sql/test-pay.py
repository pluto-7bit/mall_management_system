# -*- coding: utf-8 -*-
"""
里程碑 9 测试：模拟支付 + 取消订单 + 超时自动取消

这个脚本里有三组用例的价值远高于其他：

  1. ★★★ 并发取消：12 个线程同时取消同一张订单
     必须「恰好 1 个成功」，而且库存只能被归还【一次】。
     如果归还逻辑写在了「抢状态」前面，库存会凭空翻倍 ——
     而这个 bug 单线程测不出来、肉眼看页面也看不出来。
     这是 test-order.py 里「20 线程抢 5 件库存」的镜像用例。

  2. ★★ 超时是 SQL 里的业务规则，不是定时任务的职责
     把一笔订单的 create_time 改早 31 分钟，然后【立刻】支付。
     必须被拒（1002）—— 哪怕定时任务还没跑到。
     这个用例专门盯「规则定义在哪一层」这件事。

  3. ★★ 只有待付款能取消
     已付款的订单取消必须被拒。这是用户拍板定下的业务边界。

⚠️ 这个脚本是【整个项目里最慢的】测试（约 40 秒）。
   原因：超时相关的用例必须真的等定时任务跑一轮。
   扫描间隔配的是 10 秒（见 application.yml 里的说明），
   所以「等一轮扫描」最多就是 10 秒。
   看到脚本在这里停住不动是正常的，不是卡死了。

运行：
    python test-pay.py
"""

import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "paytest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None

TOKEN_A = None
ID_A = None
TOKEN_B = None
ID_B = None
ADDR_A = None
ADDR_B = None

TEST_MEMBER_IDS = []

# ★ 和 application.yml 里的 mall.order.pay-timeout-minutes 对应。
#   这里抄一份是因为测试要自己构造「已超时」的数据（改 create_time），
#   必须知道该往前拨多少分钟。
#   ⚠️ 抄来的常量要有退路：如果这个值和后端配置不一致，
#      症状是超时用例超时（等不到取消），而不是测试假通过 ——
#      因为「没被取消」会让断言失败。方向是安全的。
PAY_TIMEOUT_MINUTES = 30
# 扫描间隔（毫秒），同样抄自 application.yml
SCAN_INTERVAL_MS = 10000
# 轮询时最多等多久（扫描间隔 + 缓冲）
SCAN_WAIT_SECONDS = SCAN_INTERVAL_MS / 1000 + 12


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
    所以读错了【不会报错】—— 它只会永远停在阶段 1 迁移时的值，
    于是「下单后库存应为 N」这类前置断言会集体失败，
    看起来像「扣库存坏了」，实际是读错了地方。
    """
    rows = run_sql(f"SELECT stock FROM product_sku WHERE id = {sku_id}")
    return int(rows[0][0]) if rows else None


def set_stock(sku_id, stock):
    run_sql(f"UPDATE product_sku SET stock = {stock} WHERE id = {sku_id}")


def default_sku_of(pid):
    """无规格商品的那唯一一条「默认 SKU」的 id。"""
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid}"))


def pid_of_sku(sku_id):
    """SKU id → 商品 id。★ 只给【按商品整体】的操作用（直接 DELETE 商品）。"""
    return int(scalar(f"SELECT product_id FROM product_sku WHERE id = {sku_id}"))


def scalar(sql, default=None):
    rows = run_sql(sql)
    return rows[0][0] if rows else default


def order_row(order_no):
    """直接查库看订单的那三个新列 —— 不通过接口。

    ★ 「响应里说有」和「库里真的有」是两件事。
      接口返回的 OrderVO 是 Service 拼出来的，它可能拼错；
      只有直接查库才能证明数据真的落盘了。
      里程碑 8 的测试里也反复用了这一招（order_rows / item_rows）。
    """
    rows = run_sql(
        f"SELECT status, "
        f"IFNULL(DATE_FORMAT(pay_time, '%Y-%m-%d %H:%i:%s'), 'NULL'), "
        f"IFNULL(DATE_FORMAT(cancel_time, '%Y-%m-%d %H:%i:%s'), 'NULL'), "
        f"IFNULL(pay_method, 'NULL'), "
        f"DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') "
        f"FROM orders WHERE order_no = '{order_no}'")
    if not rows:
        return None
    return {
        "status": int(rows[0][0]),
        "pay_time": rows[0][1],
        "cancel_time": rows[0][2],
        "pay_method": rows[0][3],
        "create_time": rows[0][4],
    }


def backdate_order(order_no, minutes):
    """把订单的 create_time 往前拨 N 分钟，用来构造「已超时」的场景。

    ★ 为什么用改数据库的方式，而不是「等 30 分钟」？
      因为等 30 分钟没人会跑这个测试。
      改 create_time 等价于「这笔订单是 N 分钟前下的」，效果完全一样，
      而且它是【测试数据】，不是【被测代码】——
      测试可以为了方便而操纵数据，被测代码不行。
    """
    run_sql(f"UPDATE orders SET create_time = DATE_SUB(NOW(), INTERVAL {minutes} MINUTE) "
            f"WHERE order_no = '{order_no}'")


def item_product_ids(order_no):
    rows = run_sql(
        f"SELECT i.product_id, i.quantity FROM order_item i "
        f"JOIN orders o ON o.id = i.order_id WHERE o.order_no = '{order_no}' ORDER BY i.id")
    return [(int(r[0]), int(r[1])) for r in rows]


def cleanup():
    # ★ 里程碑 12：评价（子）和晒图（孙）要排在最前面 ——
    #   评价以 order_item_id 关联明细、晒图以 review_id 关联评价，
    #   全都【没有外键】，顺序反了不报错、只留孤儿行。
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
    # ★ 只删这次测试用到的会员的购物车，不做 KEYS mall:cart:* 一锅端
    for mid in TEST_MEMBER_IDS:
        redis_cmd("DEL", f"mall:cart:{mid}")


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
        "password": "pay123456",
        "nickname": f"支付测试{tag}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    d = r["data"]
    TEST_MEMBER_IDS.append(d["id"])
    return d["token"], d["id"]


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
    # ★ 里程碑 15 阶段 4：返回【SKU id】—— 下单、加购、扣库存都按规格走。
    return default_sku_of(r["data"])


def create_address(token, receiver, phone, region, detail):
    st, r = call("POST", "/shop/addresses", {
        "receiver": receiver, "phone": phone, "region": region, "detail": detail,
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"建测试地址失败：HTTP {st} / {r}")
    return r["data"]


def key_for(tag):
    return f"k{RUN}{tag}"


def buy_now(token, sku_id, qty, address_id, idem_key):
    return call("POST", "/shop/orders/buy-now", {
        "skuId": sku_id, "quantity": qty,
        "addressId": address_id, "idempotencyKey": idem_key,
    }, token=token)


def new_order(token, pid, qty, address_id, tag, expect_stock):
    """建一笔订单，并断言库存确实被扣了。返回订单号。

    用一个辅助函数而不是到处写三行，是为了让每个用例只关心自己要测的东西。
    """
    st, r = buy_now(token, pid, qty, address_id, key_for(tag))
    if r.get("code") != 200:
        raise SystemExit(f"建订单失败（用例 {tag}）：HTTP {st} / {r}")
    order_no = r["data"]["orderNo"]
    if stock_of(pid) != expect_stock:
        raise SystemExit(f"用例 {tag} 的前置条件不成立："
                         f"下单后库存应为 {expect_stock}，实际 {stock_of(pid)}")
    return order_no


def pay(token, order_no, method="ALIPAY"):
    return call("POST", f"/shop/orders/{order_no}/pay", {"payMethod": method},
                token=token)


def cancel(token, order_no):
    return call("POST", f"/shop/orders/{order_no}/cancel", None, token=token)


def get_order(token, order_no):
    return call("GET", f"/shop/orders/{order_no}", None, token=token)


def wait_until(predicate, timeout=SCAN_WAIT_SECONDS, interval=0.5):
    """轮询等待某个条件成立。返回 (是否成立, 实际等了多久)。

    ★ 为什么用轮询而不是 sleep 固定时长？
      因为定时任务的节拍和测试的脚步是【异步】的 ——
      固定 sleep 要么等太久（慢），要么赌运气（flaky）。
      轮询 + 超时上限，两种情况都能正确处理。
    """
    start = time.time()
    while time.time() - start < timeout:
        if predicate():
            return True, time.time() - start
        time.sleep(interval)
    return False, time.time() - start


# ======================================================================
def main():
    global TOKEN_A, ID_A, TOKEN_B, ID_B, ADDR_A, ADDR_B

    cleanup()
    admin_login()
    TOKEN_A, ID_A = register("a")
    TOKEN_B, ID_B = register("b")
    ADDR_A = create_address(TOKEN_A, "支付甲", "13800000001", "广东省 深圳市 南山区", "科技园 1 号")
    ADDR_B = create_address(TOKEN_B, "支付乙", "13800000002", "北京市 朝阳区 望京", "SOHO 2 号")

    print()
    print(f"本次运行 TAG = {TAG}")
    print(f"会员 A id={ID_A}，会员 B id={ID_B}")
    print()

    # ==================================================================
    section("1. 基础：查订单详情（这是收银台页打开时调的接口）")

    p1 = create_product(f"{PREFIX}商品一{RUN}", "199.00", 100)
    set_stock(p1, 100)

    order1 = new_order(TOKEN_A, p1, 3, ADDR_A, "o1", 97)

    st, r = get_order(TOKEN_A, order1)
    check("★ 能按订单号查到自己的订单", r.get("code") == 200, f"HTTP {st} / {r}")
    d = r.get("data") or {}
    check("订单号一致", d.get("orderNo") == order1, f"{d.get('orderNo')} != {order1}")
    check("状态是待付款(0)", d.get("status") == 0, f"{d.get('status')}")
    # ★ 比数字而不是比字符串：JSON 里 199.00 × 3 是 597.0 还是 597.00
    #   取决于序列化器，不是业务问题。钱的精度要看数据库的 DECIMAL(10,2)，
    #   那一层由里程碑 8 的测试盯着（item_rows 直接查 subtotal）。
    check("金额是服务端算的 597.00", float(d.get("totalAmount") or 0) == 597.00,
          f"{d.get('totalAmount')}")
    check("带上了收货信息快照", d.get("receiverName") == "支付甲", f"{d.get('receiverName')}")
    check("带上了明细（1 种商品）", len(d.get("items") or []) == 1,
          f"{d.get('items')}")
    # ★ 记下这一刻的 createTime。后面要验证「支付之后 create_time 没被改」——
    #   拿它和数据库里的值对，比拿它和 pay_time 比大小更准确：
    #   同一秒内下单又付款时，两个时间戳是可以相等的，
    #   「谁大谁小」判断不了「有没有被改写」。
    order1_created = d.get("createTime")

    # 三个新列这时应该都是空
    row = order_row(order1)
    check("★ 库里 pay_time 还没值（NULL）", row["pay_time"] == "NULL", f"{row}")
    check("★ 库里 cancel_time 还没值（NULL）", row["cancel_time"] == "NULL", f"{row}")
    check("★ 库里 pay_method 还没值（NULL）", row["pay_method"] == "NULL", f"{row}")

    # ==================================================================
    section("2. ★ payDeadline：只有待付款的订单才有")

    check("★ 待付款订单有 payDeadline", d.get("payDeadline") is not None,
          f"payDeadline={d.get('payDeadline')}")

    # 这个字段是「create_time + 30 分钟」算出来的。验证它和后端配置一致：
    # 用接口给的下单时间和 payDeadline 相减，应该正好是 30 分钟。
    from datetime import datetime
    created = datetime.fromisoformat(d["createTime"])
    deadline = datetime.fromisoformat(d["payDeadline"])
    delta_min = round((deadline - created).total_seconds() / 60)
    check(f"★ payDeadline 正好是 createTime + {PAY_TIMEOUT_MINUTES} 分钟",
          delta_min == PAY_TIMEOUT_MINUTES,
          f"实际相差 {delta_min} 分钟 —— 如果后端改了配置而测试没改，这里会先炸")

    # ==================================================================
    section("3. ★★ 支付成功")

    st, r = pay(TOKEN_A, order1)
    check("★ 支付返回成功", r.get("code") == 200, f"HTTP {st} / {r}")
    d = r.get("data") or {}
    check("★ 响应里状态变成已付款(1)", d.get("status") == 1, f"{d.get('status')}")
    check("★ 响应里带上了 payTime", d.get("payTime") is not None, f"{d}")
    check("★ 响应里带上了 payMethod", d.get("payMethod") == "ALIPAY", f"{d.get('payMethod')}")
    check("★ 已付款的订单【没有】payDeadline（倒计时没意义了）",
          d.get("payDeadline") is None, f"payDeadline={d.get('payDeadline')}")

    # ★★ 直接查库 —— 响应说有不算数
    row = order_row(order1)
    check("★★ 库里 status 真的是 1", row["status"] == 1, f"{row}")
    check("★★ 库里 pay_time 真的落了值", row["pay_time"] != "NULL", f"{row}")
    check("★★ 库里 pay_method 真的是 ALIPAY", row["pay_method"] == "ALIPAY", f"{row}")
    # ★ 支付改的是 status / pay_time / pay_method 三列，
    #   create_time 必须原封不动。拿库里现在的值和「下单时接口返回的那个」比，
    #   而不是和 pay_time 比大小 —— 同一秒内下单又付款，两者相等是正常的。
    check("★★ 库里 create_time 没被支付改写（还是下单时那个值）",
          row["create_time"] == order1_created,
          f"下单时接口说 {order1_created}，库里现在 {row['create_time']}")
    check("★★ pay_time 不早于 create_time",
          row["pay_time"] >= row["create_time"],
          f"下单 {row['create_time']} / 支付 {row['pay_time']}")

    # 再查一次详情接口，确认状态是持久的（这才是收银台刷新页面看到的）
    st, r = get_order(TOKEN_A, order1)
    check("★★ 重新查一次，状态还是已付款（证明真落库了，不是响应里的假象）",
          (r.get("data") or {}).get("status") == 1, f"{r.get('data')}")

    # ==================================================================
    section("4. 支付的各种失败路径")

    st, r = pay(TOKEN_A, order1)
    check("★ 重复支付已付款的订单 → 1002", r.get("code") == 1002, f"HTTP {st} / {r}")
    check("  提示里说清了当前状态",
          "已付款" in (r.get("message") or ""), f"{r.get('message')}")

    st, r = pay(TOKEN_A, "20990101000000000001")
    check("★ 订单号不存在 → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = call("POST", f"/shop/orders/{order1}/pay", {"payMethod": "BITCOIN"},
                 token=TOKEN_A)
    check("★ 不支持的支付方式 → 400", r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = call("POST", f"/shop/orders/{order1}/pay", {}, token=TOKEN_A)
    check("★ 不传支付方式 → 400", r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = call("POST", f"/shop/orders/{order1}/pay", {"payMethod": "ALIPAY"})
    check("★★ 未登录支付 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", f"/shop/orders/{order1}")
    check("★★ 未登录查订单 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("POST", f"/shop/orders/{order1}/cancel", None)
    check("★★ 未登录取消订单 → 401", st == 401, f"HTTP {st} / {r}")

    # ==================================================================
    section("5. ★★ 跨会员隔离：B 拿 A 的订单号，什么都做不了")

    st, r = get_order(TOKEN_B, order1)
    check("★★ B 查 A 的订单 → 1003（和「不存在」同一个码）",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = pay(TOKEN_B, order1)
    check("★★ B 支付 A 的订单 → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # 用一笔待付款的订单来测「B 取消 A 的订单」，
    # 否则分不清是「越权被拦」还是「状态不对被拦」
    p_iso = create_product(f"{PREFIX}隔离{RUN}", "10.00", 50)
    order_iso = new_order(TOKEN_A, p_iso, 1, ADDR_A, "iso", 49)

    st, r = cancel(TOKEN_B, order_iso)
    check("★★ B 取消 A 的订单 → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # ★ 关键：B 的越权尝试不能对 A 的订单产生任何影响
    check("★★★ B 的越权尝试没有改动 A 的订单（还是待付款）",
          order_row(order_iso)["status"] == 0, f"{order_row(order_iso)}")
    check("★★★ B 的越权尝试没有归还 A 的库存",
          stock_of(p_iso) == 49, f"stock = {stock_of(p_iso)}，期望还是 49")

    # ==================================================================
    section("6. ★★ 取消订单：状态 + 时间 + 库存精确归还")

    p2 = create_product(f"{PREFIX}商品二{RUN}", "50.00", 80)
    order2 = new_order(TOKEN_A, p2, 5, ADDR_A, "o2", 75)

    check("取消前：pay_time / cancel_time 都是 NULL",
          order_row(order2)["pay_time"] == "NULL"
          and order_row(order2)["cancel_time"] == "NULL", f"{order_row(order2)}")

    st, r = cancel(TOKEN_A, order2)
    check("★ 取消返回成功", r.get("code") == 200, f"HTTP {st} / {r}")
    d = r.get("data") or {}
    check("★ 响应里状态变成已取消(4)", d.get("status") == 4, f"{d.get('status')}")
    check("★ 响应里带上了 cancelTime", d.get("cancelTime") is not None, f"{d}")
    check("★ 已取消的订单没有 payDeadline", d.get("payDeadline") is None, f"{d}")

    row = order_row(order2)
    check("★★ 库里 status 真的是 4", row["status"] == 4, f"{row}")
    check("★★ 库里 cancel_time 真的落了值", row["cancel_time"] != "NULL", f"{row}")
    check("★★ 库里 pay_time 仍然是 NULL（取消 ≠ 支付）",
          row["pay_time"] == "NULL", f"{row}")
    check("★★ 库里 pay_method 仍然是 NULL", row["pay_method"] == "NULL", f"{row}")

    # ★★★ 库存归还 —— 这是取消订单最实质的副作用
    check("★★★ 库存精确归还到下单前的值 80", stock_of(p2) == 80,
          f"stock = {stock_of(p2)}，期望 80")

    st, r = cancel(TOKEN_A, order2)
    check("★ 重复取消 → 1002", r.get("code") == 1002, f"HTTP {st} / {r}")
    check("★★ 重复取消【没有】把库存再还一遍", stock_of(p2) == 80,
          f"stock = {stock_of(p2)}，期望 80（121 就说明还了两次）")

    st, r = pay(TOKEN_A, order2)
    check("★ 支付已取消的订单 → 1002", r.get("code") == 1002, f"HTTP {st} / {r}")

    # ==================================================================
    section("7. ★★★ 只有待付款能取消：已付款的不能取消")

    p3 = create_product(f"{PREFIX}商品三{RUN}", "30.00", 40)
    order3 = new_order(TOKEN_A, p3, 2, ADDR_A, "o3", 38)

    st, r = pay(TOKEN_A, order3)
    if r.get("code") != 200:
        raise SystemExit(f"前置失败：无法把订单支付成功 {r}")

    st, r = cancel(TOKEN_A, order3)
    check("★★★ 取消已付款的订单 → 1002（这条是业务边界，用户拍板定的）",
          r.get("code") == 1002, f"HTTP {st} / {r}")
    check("★★ 提示里说明了当前状态是「已付款」",
          "已付款" in (r.get("message") or ""), f"{r.get('message')}")
    check("★★★ 被拒之后订单状态没变（还是已付款 1）",
          order_row(order3)["status"] == 1, f"{order_row(order3)}")
    check("★★★ 被拒之后库存没被归还（不能因为一次失败的取消就多出库存）",
          stock_of(p3) == 38, f"stock = {stock_of(p3)}，期望 38")

    # ==================================================================
    section("8. ★★ 一单多件商品：每一件都要归还")

    p4a = create_product(f"{PREFIX}多件甲{RUN}", "11.00", 60)
    p4b = create_product(f"{PREFIX}多件乙{RUN}", "22.00", 70)
    p4c = create_product(f"{PREFIX}多件丙{RUN}", "33.00", 80)

    # 用购物车结算，这样一笔订单里能有三种商品
    for sku, qty in ((p4a, 2), (p4b, 3), (p4c, 4)):
        st, r = call("POST", "/shop/cart/items", {"skuId": sku, "quantity": qty},
                     token=TOKEN_A)
        if r.get("code") != 200:
            raise SystemExit(f"加购物车失败：{r}")

    st, r = call("POST", "/shop/orders", {
        "skuIds": [p4a, p4b, p4c], "addressId": ADDR_A,
        "idempotencyKey": key_for("o4"),
    }, token=TOKEN_A)
    if r.get("code") != 200:
        raise SystemExit(f"购物车结算失败：HTTP {st} / {r}")
    order4 = r["data"]["orderNo"]

    check("结算后库存：甲 58 / 乙 67 / 丙 76",
          stock_of(p4a) == 58 and stock_of(p4b) == 67 and stock_of(p4c) == 76,
          f"甲={stock_of(p4a)} 乙={stock_of(p4b)} 丙={stock_of(p4c)}")
    check("这笔订单确实有 3 行明细", len(item_product_ids(order4)) == 3,
          f"{item_product_ids(order4)}")

    st, r = cancel(TOKEN_A, order4)
    check("★ 取消成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★★★ 三种商品的库存【都】归还了（甲 60 / 乙 70 / 丙 80）",
          stock_of(p4a) == 60 and stock_of(p4b) == 70 and stock_of(p4c) == 80,
          f"甲={stock_of(p4a)}(期望60) 乙={stock_of(p4b)}(期望70) 丙={stock_of(p4c)}(期望80) "
          f"—— 如果某个没还，说明归还的循环漏了某一行")

    # ==================================================================
    section("9. ★ 商品被硬删时，取消依然成功（increaseStock 影响 0 行不该阻断）")

    p5 = create_product(f"{PREFIX}将被删{RUN}", "9.00", 20)
    order5 = new_order(TOKEN_A, p5, 2, ADDR_A, "o5", 18)

    # 直接从数据库删掉商品（模拟「运维手滑删数据」）。
    # order_item 故意没有外键，所以数据库不会拦这件事。
    # ★ 里程碑 15：SKU 行也要一起消失，否则留下孤儿。下面是【直接改库】，
    #   所以绕过了 Service 的级联删除，得自己按顺序删。
    # ⚠️ p5 是【SKU id】。直接把 skuId 当成商品 id 删，会删掉【另一件】商品 ——
    #   两种 id 都是自增数字，撞车是必然的，而且这里不会有任何报错。
    p5_pid = pid_of_sku(p5)
    run_sql(f"DELETE FROM product_sku WHERE product_id = {p5_pid}")
    run_sql(f"DELETE FROM product WHERE id = {p5_pid}")
    check("前置：商品已从库里消失", stock_of(p5) is None, f"{stock_of(p5)}")

    st, r = cancel(TOKEN_A, order5)
    check("★ 取消依然成功（不能因为商品没了就取消不掉）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 订单状态还是正确变成了已取消",
          order_row(order5)["status"] == 4, f"{order_row(order5)}")

    # ==================================================================
    section("10. ★★★ 并发取消：12 个线程同时取消同一张订单")

    p6 = create_product(f"{PREFIX}并发{RUN}", "77.00", 30)
    order6 = new_order(TOKEN_A, p6, 6, ADDR_A, "o6", 24)

    results = []
    lock = threading.Lock()

    def canceller():
        st, r = cancel(TOKEN_A, order6)
        with lock:
            results.append((st, r.get("code")))

    threads = [threading.Thread(target=canceller) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok_count = sum(1 for _, code in results if code == 200)
    invalid = sum(1 for _, code in results if code == 1002)
    other = [(st, code) for st, code in results if code not in (200, 1002)]

    print(f"  12 个并发取消结果：成功 {ok_count} 个，状态冲突(1002) {invalid} 个，"
          f"其他 {len(other)} 个")

    check("★★★ 恰好只有 1 个取消成功（不能有 2 个）",
          ok_count == 1, f"成功 {ok_count} 个，期望 1 个")
    check("★★★ 其余 11 个都是 1002（干净的业务失败，不是 500）",
          invalid == 11, f"1002 有 {invalid} 个，期望 11 个")
    check("★★★ 没有出现其他错误（说明失败都是干净的，不是系统异常）",
          len(other) == 0, f"{other}")
    check("★★★ 库存只被归还了一次 —— 精确回到 30，不是 36、42……",
          stock_of(p6) == 30,
          f"stock = {stock_of(p6)}，期望 30。"
          f"如果大于 30，说明归还逻辑写在了抢状态【之前】，是必须修的 bug")

    # ==================================================================
    section("11. ★★ 超时：SQL 里的业务规则，不依赖定时任务")

    # 先建一笔「探针订单」并把它改早，用来确定扫描的节拍。
    # ---- 为什么要这样？ ----
    # 扫描每 10 秒跑一轮，而「改早 create_time」和「立刻支付」之间
    # 可能正好插进来一轮扫描 —— 那样订单会被先取消掉，
    # 支付失败的原因就成了「已取消」而不是「超时」，
    # 这条用例就证明不了它想证明的东西了。
    # 做法：等探针订单被扫描取消，说明「一轮刚刚跑完」，
    # 接下来有大约 10 秒的安静窗口，足够在下一轮之前完成支付尝试。
    p7 = create_product(f"{PREFIX}超时{RUN}", "55.00", 15)
    probe = new_order(TOKEN_A, p7, 3, ADDR_A, "probe", 12)

    backdate_order(probe, PAY_TIMEOUT_MINUTES + 1)

    t0 = time.time()
    ok, waited = wait_until(lambda: order_row(probe)["status"] == 4)
    check(f"★★ 超时订单被定时任务自动取消了（等了 {waited:.1f} 秒）",
          ok, f"等了 {waited:.1f} 秒还是没被取消，status={order_row(probe)['status']}")
    check("★★ 自动取消时写了 cancel_time",
          order_row(probe)["cancel_time"] != "NULL", f"{order_row(probe)}")
    check("★★ 自动取消时【没有】写 pay_time",
          order_row(probe)["pay_time"] == "NULL", f"{order_row(probe)}")
    check("★★★ 自动取消后库存归还了（12 → 15）",
          stock_of(p7) == 15, f"stock = {stock_of(p7)}，期望 15")

    # ---- 现在处在「刚跑完一轮扫描」的安静窗口里 ----
    victim = new_order(TOKEN_A, p7, 2, ADDR_A, "victim", 13)
    backdate_order(victim, PAY_TIMEOUT_MINUTES + 1)

    st, r = pay(TOKEN_A, victim)
    check("★★★ 已过时限的订单【立刻】支付被拒 —— 哪怕扫描还没跑到",
          r.get("code") == 1002, f"HTTP {st} / {r}")
    check("★★★ 拒绝的原因说的是「支付时限」而不是「已取消」"
          "（这条证明规则在 SQL 里，定时任务只是执行机制）",
          "支付时限" in (r.get("message") or ""),
          f"message={r.get('message')} —— 如果说的是「已取消」，"
          f"说明扫描抢先跑了，这条用例没能验证到 SQL 规则")
    check("★★ 被拒之后订单还是待付款（没有被误改成别的东西）",
          order_row(victim)["status"] == 0, f"{order_row(victim)}")

    # ---- 未超时的订单不能被误扫 ----
    p8 = create_product(f"{PREFIX}未超时{RUN}", "44.00", 25)
    fresh = new_order(TOKEN_A, p8, 4, ADDR_A, "fresh", 21)
    backdate_order(fresh, PAY_TIMEOUT_MINUTES - 25)   # 只过 5 分钟，不该被扫

    # 等过至少一轮扫描
    time.sleep(SCAN_INTERVAL_MS / 1000 + 3)
    check("★★★ 只过了 5 分钟的订单【不会】被误扫（还是待付款）",
          order_row(fresh)["status"] == 0,
          f"status={order_row(fresh)['status']} —— 被误扫了，"
          f"说明超时判断的边界写错了")
    check("★★ 未超时的订单库存也没被归还", stock_of(p8) == 21,
          f"stock = {stock_of(p8)}，期望 21")
    st, r = pay(TOKEN_A, fresh)
    check("★★ 未超时的订单可以正常支付", r.get("code") == 200, f"HTTP {st} / {r}")

    # 顺便把 victim 等它被扫描掉，确认最终状态一致
    ok, waited = wait_until(lambda: order_row(victim)["status"] == 4)
    check(f"★★ 刚才被拒的那笔最终也被扫描取消（又等了 {waited:.1f} 秒）",
          ok, f"status={order_row(victim)['status']}")

    print()
    print(f"  （超时用例共等了约 {time.time() - t0:.0f} 秒，"
          f"这是这个脚本最慢的部分，属正常现象）")

    # ==================================================================
    section("12. 清理")

    cleanup()
    cleanup_redis()

    left = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
    check("测试会员已删除", int(left[0][0]) == 0, f"{left}")
    left = run_sql(f"SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'")
    check("测试商品已删除", int(left[0][0]) == 0, f"{left}")
    left = run_sql(
        f"SELECT COUNT(*) FROM orders o JOIN member m ON m.id = o.member_id "
        f"WHERE m.username LIKE '{PREFIX}%'")
    check("★ 测试订单已删除", int(left[0][0]) == 0, f"剩余 {left[0][0]} 条")

    orphans = run_sql("SELECT COUNT(*) FROM order_item i "
                      "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")
    check("★★ 没有孤儿订单明细",
          int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行明细指向不存在的订单")

    # 用户原有数据核对
    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个，订单 {} 笔".format(
        run_sql("SELECT COUNT(*) FROM product")[0][0],
        run_sql("SELECT COUNT(*) FROM category")[0][0],
        run_sql("SELECT COUNT(*) FROM member")[0][0],
        run_sql("SELECT COUNT(*) FROM orders")[0][0]))

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
