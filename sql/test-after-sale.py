# -*- coding: utf-8 -*-
"""
里程碑 17 测试：售后 + 运费

这个脚本里有四组用例的价值远高于其他，它们各自盯着一个【静默】的失败：

  1. ★★★ C 组「同意时库存不动」
     退货退款走完 0 → 1 → 2 的【每一步】都断言 product_sku.stock 一个数都没变，
     直到 2 → 3（管理员确认收到）才变。而且撤销（T7）作为对照组也走一整条流程。
     盯的是：把「还库存」挂在了「同意」那条边上 ——
     那样买家可以既不寄回又拿到退款，而库存已经加回去了，货凭空多一份。
     这个 bug 单线程看页面完全看不出来。

  2. ★★★ D 组「只还一次」
     12 个线程同时打同一个 /receive，必须【恰好 1 个成功】，其余全是 1002，
     而且库存必须【恰好】加一个 quantity —— 不是两个。
     盯的是：副作用写在了「抢边」前面。那是本轮最高风险的一处顺序问题，
     因为 StockRestoreService 的契约就是【不做任何资格判断】，
     顺序反了它会照做，而且没有任何一层会报错。

  3. ★★★ E 组「订单终态」
     部分退 → orders.status 不变；整单退 → 变 5；★ 变 5 之后【发货被拒】。
     最后那一条是「orders.status = 5」这个状态值存在的全部意义：
     没有它，管理员点一下「发货」就把已经退过款的东西发出去了，
     钱货两空，而且没有任何一层会报错。

  4. ★★ F 组「运费与金额」
     98.99 收运费、99.00 免运费（【两侧都测】）；total = 明细 + 运费；
     部分退只退货款、整单退退货款 + 运费；所有退款之和 ≤ 实付。
     盯的是 total_amount 的语义变成「实付」之后的那条减法/加法分岔 ——
     两个方向的实现分岔时，用户看到「运费 ¥0 却多付了 10 元」，
     而页面不报错、代码不报错。

⚠️ 这个脚本会在结束时跑 §I 组的四条 SQL 断言，它们是【全库】范围的：
   - §1.2  active_token 的两条（进行中↔0、已关闭↔id）
   - §3.4  「一条明细的已退款售后单 ≤ 1 张」的哨兵
   - §5.2  实付 = 明细小计之和 + 运费
   - §4.3  orders.status = 5 与「全部明细都退完」的双向等价
   它们在 cleanup() 之前跑，所以能看见本次造出来的数据 ——
   那是它们最有价值的时刻。全部必须返回【空行】。

运行：
    python test-after-sale.py
"""

import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from decimal import Decimal
from urllib.parse import quote

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "astest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None

TOKEN_A = None
ID_A = None
USER_A = None
TOKEN_B = None
ID_B = None
ADDR_A = None
ADDR_B = None

TEST_MEMBER_IDS = []

# ======================================================================
#  ★ 三个抄自 application.yml 的常量
#
#  为什么测试要自己抄一份：它要【自己构造边界数据】——
#  98.99 和 99.00 这两个金额、往前拨 8 天的 complete_time，
#  都是测试造的，所以它必须知道规则是什么。
#
#  ⚠️ 抄来的常量必须有退路。这三条都满足：配置改了而这里没改时，
#     测试会【变红】而不是假通过 ——
#       门槛从 99 改成 199  → f2 期望「免运费」却收到 10 元运费 → 红
#       时限从 7 天改成 30 天 → b8 期望 1014 却申请成功 → 红
#     方向是安全的。
# ======================================================================
FREIGHT_AMOUNT = Decimal("10.00")          # mall.order.freight-amount
FREE_FREIGHT_THRESHOLD = Decimal("99.00")  # mall.order.free-freight-threshold
AFTER_SALE_WINDOW_DAYS = 7                 # mall.order.after-sale-window-days

# 状态常量（和 OrderStatus / AfterSaleStatus 对齐 —— 测试里直接用数字，
# 是因为这些数字就是接口契约的一部分，将来重编号必须连测试一起改）
O_PENDING, O_PAID, O_SHIPPED, O_COMPLETED, O_CANCELLED, O_REFUNDED = 0, 1, 2, 3, 4, 5
AS_APPLIED, AS_WAITING_RETURN, AS_WAITING_RECEIVE = 0, 1, 2
AS_REFUNDED, AS_REJECTED, AS_CANCELLED = 3, 4, 5

ONLY_REFUND, RETURN_REFUND = 1, 2

R_APPLIED, R_QUALITY, R_NOT_AS_DESCRIBED = 1, 2, 3

# 业务码
C_OK = 200
C_AFTER_SALE_EXISTS = 1012
C_ORDER_ITEM_REFUNDED = 1013
C_AFTER_SALE_EXPIRED = 1014
C_ORDER_STATUS_INVALID = 1002
C_NOT_FOUND = 1003
C_BAD_REQUEST = 400


# ----------------------------------------------------------------------
# ★★ URL 里出现非 ASCII 必须先百分号编码。
#   http.client 只接受 ASCII，直接把中文拼进 query string 会抛
#   UnicodeEncodeError —— 而且那是在【客户端】炸的，跟后端毫无关系，
#   症状看着像"接口挂了"，实际是这个脚本自己不会拼 URL。
#   （同一条纪律的另一个形态：不要在 Git Bash 命令行里内联中文。）
_SAFE = ":/?&=%,[]@!$'()*+;"


def call(method, path, body=None, token=None, timeout=30):
    url = BASE + quote(path, safe=_SAFE)
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


def code_of(r):
    return r.get("code") if isinstance(r, dict) else None


def msg_of(r):
    return r.get("message") if isinstance(r, dict) else None


def money(v):
    """把接口返回的金额转成 Decimal。

    ★ 不能直接拿 float 比：JSON 里的 108.99 到了 Python 是 float，
      而 108.99 在二进制里没有精确表示。用 Decimal(str(...)) 这一步
      走的是「十进制字符串 → 十进制数」，和数据库的 DECIMAL(10,2) 对齐。
    """
    return Decimal(str(0 if v is None else v))


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
    result = subprocess.run(
        ["docker", "exec", REDIS, "redis-cli", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"redis-cli 执行失败：{args}\n{result.stderr}")
    return [l for l in result.stdout.strip().splitlines() if l]


def stock_of(sku_id):
    """★ 库存的唯一真源是 product_sku.stock（里程碑 15 阶段 4 起）。"""
    rows = run_sql(f"SELECT stock FROM product_sku WHERE id = {sku_id}")
    return int(rows[0][0]) if rows else None


def set_stock(sku_id, stock):
    run_sql(f"UPDATE product_sku SET stock = {stock} WHERE id = {sku_id}")


def default_sku_of(pid):
    """无规格商品的那唯一一条「默认 SKU」的 id。"""
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid}"))


def order_row(order_no):
    """直接查库看订单的那几列 —— 不通过接口。

    ★ 「响应里说有」和「库里真的有」是两件事。接口返回的 OrderVO
      是 Service 拼出来的，它可能拼错；只有直接查库才能证明数据真的落盘了。
    """
    rows = run_sql(
        f"SELECT status, total_amount, freight_amount, "
        f"IFNULL(pay_method, 'NULL'), "
        f"IFNULL(DATE_FORMAT(complete_time, '%Y-%m-%d %H:%i:%s'), 'NULL') "
        f"FROM orders WHERE order_no = '{order_no}'")
    if not rows:
        return None
    return {
        "status": int(rows[0][0]),
        "total_amount": Decimal(rows[0][1]),
        "freight_amount": Decimal(rows[0][2]),
        "pay_method": rows[0][3],
        "complete_time": rows[0][4],
    }


def after_sale_row(no):
    """直接查库看售后单的那几列。★ 同上：接口说有 ≠ 库里真有。"""
    rows = run_sql(
        f"SELECT status, type, active_token, order_item_id, "
        f"IFNULL(refund_amount, 'NULL'), refund_freight, "
        f"IFNULL(refund_method, 'NULL'), "
        f"IFNULL(DATE_FORMAT(refund_time, '%Y-%m-%d %H:%i:%s'), 'NULL') "
        f"FROM after_sale WHERE after_sale_no = '{no}'")
    if not rows:
        return None
    return {
        "status": int(rows[0][0]),
        "type": int(rows[0][1]),
        "active_token": int(rows[0][2]),
        "order_item_id": int(rows[0][3]),
        "refund_amount": rows[0][4],
        "refund_freight": Decimal(rows[0][5]),
        "refund_method": rows[0][6],
        "refund_time": rows[0][7],
    }


def backdate_complete_time(order_no, days):
    """把 complete_time 往前拨 N 天，构造「已超过售后期限」的场景。

    ★ 和 test-pay.py 里 backdate_order 同一个理由：等 7 天没人会跑这个测试。
      改的是【测试数据】，不是【被测代码】。
    """
    run_sql(f"UPDATE orders SET complete_time = DATE_SUB(NOW(), INTERVAL {days} DAY) "
            f"WHERE order_no = '{order_no}'")


# ----------------------------------------------------------------------
def cleanup():
    # ★★ 顺序必须【逆着创建顺序】。售后单以 order_item_id 关联明细、
    #    明细以 order_id 关联订单，全都【没有外键】——
    #    顺序反了不报错，只留孤儿行，而孤儿行会让下一次运行的断言莫名其妙地红。
    run_sql("DELETE pri FROM product_review_image pri "
            "JOIN product_review r ON r.id = pri.review_id "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE r FROM product_review r "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    # ★ after_sale 必须排在 order_item / orders 之前
    run_sql("DELETE a FROM after_sale a "
            f"JOIN member m ON m.id = a.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE oi FROM order_item oi "
            "JOIN orders o ON o.id = oi.order_id "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE o FROM orders o "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE a FROM member_address a "
            f"JOIN member m ON m.id = a.member_id WHERE m.username LIKE '{PREFIX}%'")
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
        "password": "astest123456",
        "nickname": f"售后测试{tag}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    d = r["data"]
    TEST_MEMBER_IDS.append(d["id"])
    return d["token"], d["id"], d["username"]


def create_product(name, price, stock):
    """建一个无规格商品（一条默认 SKU）。返回 (productId, skuId)。"""
    st, r = call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name, "status": 1,
        "specSchema": [],
        "skus": [{"specs": [], "price": price, "stock": stock}],
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    pid = r["data"]
    return pid, default_sku_of(pid)


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


def get_order(token, order_no):
    return call("GET", f"/shop/orders/{order_no}", None, token=token)


def pay(token, order_no, method="ALIPAY"):
    return call("POST", f"/shop/orders/{order_no}/pay", {"payMethod": method}, token=token)


# ★ 里程碑 18：发货接口现在【必须】带承运商 + 快递单号 ——
#   服务端刻意不接受空值（允许为空会让「已发货但没单号」成为常态，
#   而用户端按 `v-if="o.trackingNo"` 显示「查看物流」入口，
#   没单号的订单页面上【什么都没有、也不报错】）。
#   本脚本要验的是订单/售后链路，快递单号长什么样不是这里的事，用一组固定值。
SHIP_BODY = {"logisticsCompany": "顺丰", "trackingNo": "SF1234567890"}


def ship(order_no):
    return call("POST", f"/admin/orders/{order_no}/ship", SHIP_BODY,
                token=ADMIN_TOKEN)


def complete(token, order_no):
    return call("POST", f"/shop/orders/{order_no}/complete", None, token=token)


def cart_add(token, sku_id, qty):
    return call("POST", "/shop/cart/items", {"skuId": sku_id, "quantity": qty}, token=token)


def cart_order(token, sku_ids, address_id, idem_key):
    return call("POST", "/shop/orders", {
        "skuIds": sku_ids, "addressId": address_id, "idempotencyKey": idem_key,
    }, token=token)


# ----------------------------------------------------------------------
#  售后接口
# ----------------------------------------------------------------------
def apply_as(token, order_no, item_ids, type_, reason=R_APPLIED, desc=None):
    body = {"orderNo": order_no, "orderItemIds": item_ids, "type": type_, "reason": reason}
    if desc:
        body["description"] = desc
    return call("POST", "/shop/after-sales", body, token=token)


def my_after_sales(token, query=""):
    return call("GET", f"/shop/after-sales{query}", None, token=token)


def cancel_as(token, no):
    return call("POST", f"/shop/after-sales/{no}/cancel", None, token=token)


def return_as(token, no, company="顺丰", tracking="SF1234567890"):
    return call("POST", f"/shop/after-sales/{no}/return",
                {"returnCompany": company, "returnTracking": tracking}, token=token)


def admin_after_sales(query=""):
    return call("GET", f"/admin/after-sales{query}", None, token=ADMIN_TOKEN)


def approve_as(no):
    return call("POST", f"/admin/after-sales/{no}/approve", None, token=ADMIN_TOKEN)


def reject_as(no, reason):
    return call("POST", f"/admin/after-sales/{no}/reject",
                {"rejectReason": reason}, token=ADMIN_TOKEN)


def receive_as(no):
    return call("POST", f"/admin/after-sales/{no}/receive", None, token=ADMIN_TOKEN)


# ----------------------------------------------------------------------
#  评价（只给 G 组用）
# ----------------------------------------------------------------------
def post_review(token, order_item_id, rating=5, content="售后测试评价"):
    return call("POST", "/shop/reviews", {
        "orderItemId": order_item_id, "rating": rating, "content": content,
    }, token=token)


def product_reviews(token, product_id, query="?pageNum=1&pageSize=50"):
    return call("GET", f"/shop/products/{product_id}/reviews{query}", None, token=token)


# ----------------------------------------------------------------------
#  组合动作
# ----------------------------------------------------------------------
def make_order(token, sku_id, qty, address_id, tag, state=O_PENDING):
    """建一笔订单并推进到指定状态，返回 (orderNo, [orderItemId], 订单详情)。

    ★ 用一个辅助函数而不是到处写五行，是为了让每个用例只关心自己要测的东西。
    """
    st, r = buy_now(token, sku_id, qty, address_id, key_for(tag))
    if r.get("code") != 200:
        raise SystemExit(f"建订单失败（用例 {tag}）：HTTP {st} / {r}")
    d = r["data"]
    order_no = d["orderNo"]
    item_ids = [it["id"] for it in d["items"]]

    if state >= O_PAID:
        st, r = pay(token, order_no)
        if r.get("code") != 200:
            raise SystemExit(f"支付失败（用例 {tag}）：HTTP {st} / {r}")
    if state >= O_SHIPPED:
        st, r = ship(order_no)
        if r.get("code") != 200:
            raise SystemExit(f"发货失败（用例 {tag}）：HTTP {st} / {r}")
    if state >= O_COMPLETED:
        st, r = complete(token, order_no)
        if r.get("code") != 200:
            raise SystemExit(f"确认收货失败（用例 {tag}）：HTTP {st} / {r}")

    st, r = get_order(token, order_no)
    if r.get("code") != 200:
        raise SystemExit(f"查订单失败（用例 {tag}）：HTTP {st} / {r}")
    return order_no, item_ids, r["data"]


def make_cart_order(token, sku_ids, address_id, tag, state=O_PENDING):
    """用购物车建一笔【多明细】订单。返回 (orderNo, [orderItemId], 订单详情)。"""
    for sid in sku_ids:
        st, r = cart_add(token, sid, 1)
        if r.get("code") != 200:
            raise SystemExit(f"加购物车失败（用例 {tag}）：HTTP {st} / {r}")

    st, r = cart_order(token, sku_ids, address_id, key_for(tag))
    if r.get("code") != 200:
        raise SystemExit(f"购物车下单失败（用例 {tag}）：HTTP {st} / {r}")
    d = r["data"]
    order_no = d["orderNo"]
    item_ids = [it["id"] for it in d["items"]]

    if state >= O_PAID:
        st, r = pay(token, order_no)
        if r.get("code") != 200:
            raise SystemExit(f"支付失败（用例 {tag}）：HTTP {st} / {r}")

    st, r = get_order(token, order_no)
    return order_no, item_ids, r["data"]


def apply_and_get_no(token, order_no, item_ids, type_):
    """申请售后并返回售后单号；申请失败直接终止（说明前置条件坏了）。"""
    st, r = apply_as(token, order_no, item_ids, type_)
    if r.get("code") != 200:
        raise SystemExit(f"申请售后失败（{order_no}）：HTTP {st} / {r}")
    return r["data"][0]["afterSaleNo"]


# ======================================================================
def main():
    global TOKEN_A, ID_A, USER_A, TOKEN_B, ID_B, ADDR_A, ADDR_B

    cleanup()
    admin_login()
    TOKEN_A, ID_A, USER_A = register("a")
    TOKEN_B, ID_B, _ = register("b")
    ADDR_A = create_address(TOKEN_A, "售后甲", "13800000001", "广东省 深圳市 南山区", "科技园 1 号")
    ADDR_B = create_address(TOKEN_B, "售后乙", "13800000002", "北京市 朝阳区 望京", "SOHO 2 号")

    print()
    print(f"本次运行 TAG = {TAG}")
    print(f"会员 A id={ID_A}，会员 B id={ID_B}")
    print()

    # ==================================================================
    section("A. 结构与迁移：新表建对了，而且迁移没有发明数据")

    rows = run_sql("SHOW COLUMNS FROM after_sale")
    cols = {r[0]: r for r in rows}
    check("after_sale 有 22 列", len(rows) == 22, f"实际 {len(rows)} 列：{sorted(cols)}")

    # ★ 两列的可空性是【刻意的相反】，所以必须逐列断言 ——
    #   它们的分工写在 AfterSale.refundAmount 的注释里：
    #   refund_amount 的 0 会被当成「退了 0 元」拿去求和 → DEFAULT NULL
    #   refund_freight 的 0 是真实值「这次不含运费」    → NOT NULL DEFAULT 0.00
    check("★ refund_amount 可空（缺席 ≠ 退了 0 元）",
          cols.get("refund_amount", ["", ""])[2] == "YES",
          f"Null={cols.get('refund_amount')}")
    check("★ refund_freight NOT NULL DEFAULT 0.00（0 是真实值）",
          cols.get("refund_freight", ["", "", ""])[2] == "NO"
          and "0.00" in cols.get("refund_freight", ["", "", "", "", ""])[4],
          f"{cols.get('refund_freight')}")
    check("active_token NOT NULL DEFAULT 0",
          cols.get("active_token", ["", "", ""])[2] == "NO"
          and cols.get("active_token", ["", "", "", "", ""])[4] == "0",
          f"{cols.get('active_token')}")

    idx = {r[2]: r for r in run_sql("SHOW INDEX FROM after_sale")}
    check("★ uk_order_item_active 存在且是 (order_item_id, active_token)",
          idx.get("uk_order_item_active") is not None,
          f"索引清单：{sorted(idx)}")
    keys = {r[2]: r[4] for r in run_sql("SHOW INDEX FROM after_sale")}
    check("★ uk_after_sale_no 存在", "uk_after_sale_no" in keys, f"{sorted(keys)}")

    row = run_sql("SHOW COLUMNS FROM orders LIKE 'freight_amount'")
    check("orders.freight_amount 存在且 NOT NULL DEFAULT 0.00",
          row and row[0][2] == "NO" and row[0][4] == "0.00",
          f"{row}")

    # ★★ 迁移没发明数据：已有的真实订单本来就没收运费，0.00 就是它们的真值。
    #    这条断言是「加列不影响任何现有代码」这句话的证据。
    n = scalar("SELECT COUNT(*) FROM orders WHERE freight_amount <> 0")
    check("★ 现有订单的 freight_amount 全是 0（迁移没发明数据）",
          int(n) == 0, f"有 {n} 笔订单的运费不为 0")

    n = scalar("SELECT COUNT(*) FROM after_sale")
    check("★ 迁移之后 after_sale 是空表（这一步在本次测试的最前面）",
          int(n) == 0, f"有 {n} 张售后单")

    # ==================================================================
    section("B. 申请：七道检查各拦一种情况")

    pid, sku = create_product(f"{PREFIX}常规{RUN}", "50.00", 500)

    # ---- b1: 明细不属于这笔订单 / 根本不存在 ----
    o1, items1, _ = make_order(TOKEN_A, sku, 1, ADDR_A, "b1a", O_PAID)
    o2, items2, _ = make_order(TOKEN_A, sku, 1, ADDR_A, "b1b", O_PAID)

    st, r = apply_as(TOKEN_A, o1, [items2[0]], ONLY_REFUND)
    check("★ 别人的明细 id（属于另一笔订单）→ 1003", code_of(r) == C_NOT_FOUND,
          f"HTTP {st} / {r}")

    st, r = apply_as(TOKEN_A, o1, [999999999], ONLY_REFUND)
    check("★ 不存在的明细 id → 1003", code_of(r) == C_NOT_FOUND, f"HTTP {st} / {r}")

    # ---- b2: 别人的订单 ----
    ob, _, _ = make_order(TOKEN_B, sku, 1, ADDR_B, "b2", O_PAID)
    st, r = apply_as(TOKEN_A, ob, [1], ONLY_REFUND)
    check("★ 别人的订单 → 1003", code_of(r) == C_NOT_FOUND, f"HTTP {st} / {r}")

    # ---- b3/b4: 状态不支持 ----
    o3, items3, _ = make_order(TOKEN_A, sku, 1, ADDR_A, "b3", O_PENDING)
    st, r = apply_as(TOKEN_A, o3, items3, ONLY_REFUND)
    check("★ 待付款的订单 → 1002", code_of(r) == C_ORDER_STATUS_INVALID,
          f"HTTP {st} / {r} / {msg_of(r)}")

    o4, items4, _ = make_order(TOKEN_A, sku, 1, ADDR_A, "b4", O_PENDING)
    _st, cancel_r = call("POST", f"/shop/orders/{o4}/cancel", None, token=TOKEN_A)
    if code_of(cancel_r) != 200:
        raise SystemExit(f"取消订单失败：{cancel_r}")
    st, r = apply_as(TOKEN_A, o4, items4, ONLY_REFUND)
    check("★ 已取消的订单 → 1002", code_of(r) == C_ORDER_STATUS_INVALID,
          f"HTTP {st} / {r} / {msg_of(r)}")

    # ---- b5: type 与状态不匹配 ----
    st, r = apply_as(TOKEN_A, o1, items1, RETURN_REFUND)
    check("★ 已付款（未发货）却申请「退货退款」→ 1002",
          code_of(r) == C_ORDER_STATUS_INVALID, f"HTTP {st} / {r} / {msg_of(r)}")

    # ---- b6: 非法取值 ----
    st, r = apply_as(TOKEN_A, o1, items1, 9)
    check("★ 非法的 type → 400", code_of(r) == C_BAD_REQUEST, f"HTTP {st} / {r}")
    st, r = apply_as(TOKEN_A, o1, items1, ONLY_REFUND, reason=99)
    check("★ 非法的 reason → 400", code_of(r) == C_BAD_REQUEST, f"HTTP {st} / {r}")
    st, r = apply_as(TOKEN_A, o1, items1 + items1, ONLY_REFUND)
    check("★ 同一个明细传两遍 → 400（不是让它撞唯一索引）",
          code_of(r) == C_BAD_REQUEST, f"HTTP {st} / {r} / {msg_of(r)}")

    # ---- b7: 申请成功，且返回的是完整 VO ----
    st, r = apply_as(TOKEN_A, o1, items1, ONLY_REFUND, desc="买重了")
    check("★ 申请成功", code_of(r) == 200, f"HTTP {st} / {r}")
    a1 = (r.get("data") or [{}])[0]
    check("★ 返回的售后单号前缀是 AS（和订单号是两个号码空间）",
          str(a1.get("afterSaleNo", "")).startswith("AS"), f"{a1.get('afterSaleNo')}")
    check("返回状态是待审核(0)", a1.get("status") == AS_APPLIED, f"{a1.get('status')}")
    check("返回里带明细快照（退的是哪件东西）",
          a1.get("productName") and money(a1.get("subtotal")) == Decimal("50.00"),
          f"{a1.get('productName')} / {a1.get('subtotal')}")
    check("★ 申请时【没有】refundAmount（金额要到退款那一刻才算得准）",
          a1.get("refundAmount") is None, f"{a1.get('refundAmount')}")

    row = after_sale_row(a1["afterSaleNo"])
    check("★ 库里 refund_amount 是 NULL，不是 0", row["refund_amount"] == "NULL", f"{row}")
    check("★ 库里 active_token = 0（进行中）", row["active_token"] == 0, f"{row}")

    # ---- b8: 重复申请 → 1012 ----
    st, r = apply_as(TOKEN_A, o1, items1, ONLY_REFUND)
    check("★★ 同一明细重复申请 → 1012（唯一索引挡的，不是 Java 挡的）",
          code_of(r) == C_AFTER_SALE_EXISTS, f"HTTP {st} / {r} / {msg_of(r)}")

    # ---- b9: 整单退：一次提交 N 行 ----
    p9a, sku9a = create_product(f"{PREFIX}整单退甲{RUN}", "10.00", 100)
    p9b, sku9b = create_product(f"{PREFIX}整单退乙{RUN}", "20.00", 100)
    o9, items9, _ = make_cart_order(TOKEN_A, [sku9a, sku9b], ADDR_A, "b9", O_PAID)
    check("前置：这笔订单有 2 条明细", len(items9) == 2, f"{items9}")

    st, r = apply_as(TOKEN_A, o9, items9, ONLY_REFUND)
    check("★ 整单退：一次提交 2 行 → 建 2 张单",
          code_of(r) == 200 and len(r.get("data") or []) == 2, f"HTTP {st} / {r}")
    nos9 = [a["afterSaleNo"] for a in (r.get("data") or [])]
    check("★ 2 张单分别对应 2 条明细",
          len({a["orderItemId"] for a in r["data"]}) == 2, f"{r.get('data')}")

    # ---- b10: 已退款的行不能再申请 → 1013 ----
    st, r = approve_as(nos9[0])
    check("前置：整单退的其中一张被同意（仅退款 → 直接退款）",
          code_of(r) == 200, f"HTTP {st} / {r}")
    st, r = apply_as(TOKEN_A, o9, [items9[0]], ONLY_REFUND)
    check("★★ 已退款的明细再申请 → 1013", code_of(r) == C_ORDER_ITEM_REFUNDED,
          f"HTTP {st} / {r} / {msg_of(r)}")

    # ---- b11: 已完成 + 超过期限 → 1014 ----
    o11, items11, _ = make_order(TOKEN_A, sku, 1, ADDR_A, "b11", O_COMPLETED)
    st, r = apply_as(TOKEN_A, o11, items11, RETURN_REFUND)
    check("★ 已完成且【在期限内】→ 申请成功", code_of(r) == 200, f"HTTP {st} / {r}")
    if code_of(r) == 200:
        cancel_as(TOKEN_A, r["data"][0]["afterSaleNo"])

    backdate_complete_time(o11, AFTER_SALE_WINDOW_DAYS + 1)
    st, r = apply_as(TOKEN_A, o11, items11, RETURN_REFUND)
    check(f"★★ 已完成且超过 {AFTER_SALE_WINDOW_DAYS} 天 → 1014",
          code_of(r) == C_AFTER_SALE_EXPIRED, f"HTTP {st} / {r} / {msg_of(r)}")

    # ★ 已发货的订单没有 complete_time，所以没有起点 —— 不受期限约束。
    #   这条是「时限只作用于已完成」的正面证据（不是靠删数据来证明的）。
    o11b, items11b, _ = make_order(TOKEN_A, sku, 1, ADDR_A, "b11b", O_SHIPPED)
    st, r = apply_as(TOKEN_A, o11b, items11b, RETURN_REFUND)
    check("★★ 已发货（没有 complete_time）→ 不受期限约束，申请成功",
          code_of(r) == 200, f"HTTP {st} / {r} / {msg_of(r)}")
    if code_of(r) == 200:
        cancel_as(TOKEN_A, r["data"][0]["afterSaleNo"])

    # ==================================================================
    section("C. ★★ 同意时库存不动：退货退款走完 0→1→2 库存一个数都没变")

    p_stock, sku_stock = create_product(f"{PREFIX}库存{RUN}", "50.00", 100)
    set_stock(sku_stock, 100)
    check("前置：初始库存 = 100", stock_of(sku_stock) == 100, f"{stock_of(sku_stock)}")

    # ---- c1: 退货退款全程 ----
    oC, itemsC, _ = make_order(TOKEN_A, sku_stock, 2, ADDR_A, "c1", O_SHIPPED)
    check("下单后库存 = 98（扣了 2 件）", stock_of(sku_stock) == 98, f"{stock_of(sku_stock)}")

    noC = apply_and_get_no(TOKEN_A, oC, itemsC, RETURN_REFUND)
    check("★★ 申请之后库存还是 98（申请不动库存）",
          stock_of(sku_stock) == 98, f"{stock_of(sku_stock)}")

    st, r = approve_as(noC)
    check("同意退货退款（0 → 1）成功", code_of(r) == 200, f"HTTP {st} / {r}")
    check("★★★ 同意之后库存还是 98（★ 货还在买家手里，凭什么还库存）",
          stock_of(sku_stock) == 98, f"{stock_of(sku_stock)}")
    check("状态变成「待买家寄回」(1)",
          (r.get("data") or {}).get("status") == AS_WAITING_RETURN,
          f"{(r.get('data') or {}).get('status')}")

    st, r = return_as(TOKEN_A, noC)
    check("买家填寄回单号（1 → 2）成功", code_of(r) == 200, f"HTTP {st} / {r}")
    check("★★★ 填完寄回单号库存还是 98（货在路上，还没到仓库）",
          stock_of(sku_stock) == 98, f"{stock_of(sku_stock)}")
    row = after_sale_row(noC)
    check("库里存下了寄回物流", row is not None, f"{row}")

    st, r = receive_as(noC)
    check("确认收到退货（2 → 3）成功", code_of(r) == 200, f"HTTP {st} / {r}")
    check("★★★ 确认收到之后库存才是 100（恰好 +2，不是 +4）",
          stock_of(sku_stock) == 100, f"{stock_of(sku_stock)}")
    check("状态变成「退款完成」(3)",
          (r.get("data") or {}).get("status") == AS_REFUNDED,
          f"{(r.get('data') or {}).get('status')}")

    # ---- c2: 撤销（T7）作为对照组 ----
    oC2, itemsC2, _ = make_order(TOKEN_A, sku_stock, 2, ADDR_A, "c2", O_SHIPPED)
    check("对照组：下单后库存 = 98", stock_of(sku_stock) == 98, f"{stock_of(sku_stock)}")

    st, r = apply_as(TOKEN_A, oC2, itemsC2, RETURN_REFUND)
    noC2 = r["data"][0]["afterSaleNo"]
    st, r = cancel_as(TOKEN_A, noC2)
    check("对照组的撤销成功", code_of(r) == 200, f"HTTP {st} / {r}")
    check("★★ 撤销走完一整条流程，库存【完全不变】(98) —— "
          "它就是「同意前库存不动」那个对照组的价值",
          stock_of(sku_stock) == 98, f"{stock_of(sku_stock)}")
    row = after_sale_row(noC2)
    check("★ 撤销后 active_token 被释放（= id，不再是 0）",
          row["active_token"] == int(scalar(
              f"SELECT id FROM after_sale WHERE after_sale_no = '{noC2}'")),
          f"{row}")
    check("★ 撤销不写退款金额（refund_amount 仍是 NULL）",
          row["refund_amount"] == "NULL", f"{row}")

    # ★ 撤销之后那一行可以重新申请（这正是 active_token 那套设计要支持的场景）
    st, r = apply_as(TOKEN_A, oC2, itemsC2, RETURN_REFUND)
    check("★★ 撤销之后可以【重新申请】（已关闭的历史单不限张数）",
          code_of(r) == 200, f"HTTP {st} / {r} / {msg_of(r)}")
    if code_of(r) == 200:
        cancel_as(TOKEN_A, r["data"][0]["afterSaleNo"])

    # ==================================================================
    section("D. ★★ 并发「只还一次」：12 个线程同时确认收到")

    p_conc, sku_conc = create_product(f"{PREFIX}并发{RUN}", "50.00", 300)
    set_stock(sku_conc, 300)

    oD, itemsD, _ = make_order(TOKEN_A, sku_conc, 3, ADDR_A, "d1", O_SHIPPED)
    noD = apply_and_get_no(TOKEN_A, oD, itemsD, RETURN_REFUND)
    approve_as(noD)
    return_as(TOKEN_A, noD)
    check("前置：库存 = 297，售后单在「待卖家收货」",
          stock_of(sku_conc) == 297 and after_sale_row(noD)["status"] == AS_WAITING_RECEIVE,
          f"stock={stock_of(sku_conc)} / {after_sale_row(noD)}")

    results = []
    results_lock = threading.Lock()
    barrier = threading.Barrier(12)

    def hammer():
        try:
            barrier.wait(timeout=20)
        except Exception:
            pass
        _st, _r = receive_as(noD)
        with results_lock:
            results.append(code_of(_r))

    threads = [threading.Thread(target=hammer) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=40)

    ok_count = sum(1 for c in results if c == C_OK)
    invalid_count = sum(1 for c in results if c == C_ORDER_STATUS_INVALID)
    check("★★★ 12 个并发请求【恰好 1 个】成功", ok_count == 1,
          f"成功 {ok_count} 个，业务码分布：{sorted(set(results))}")
    check("★★★ 其余 11 个全是 1002（状态不允许）",
          invalid_count == 11, f"1002 有 {invalid_count} 个：{results}")
    check("★★★★ 库存【恰好】300（+3 一次，不是 +3 两次）",
          stock_of(sku_conc) == 300, f"{stock_of(sku_conc)}")
    check("库里的状态是 3", after_sale_row(noD)["status"] == AS_REFUNDED,
          f"{after_sale_row(noD)}")

    st, r = receive_as(noD)
    check("★★ 并发之后再【串行】打一次同一个接口 → 1002",
          code_of(r) == C_ORDER_STATUS_INVALID, f"HTTP {st} / {r}")
    check("★★ 串行重复之后库存仍然是 300", stock_of(sku_conc) == 300,
          f"{stock_of(sku_conc)}")

    # ==================================================================
    section("E. ★★ 订单终态：部分退不变、整单退变 5、变 5 之后发货被拒")

    pEa, skuEa = create_product(f"{PREFIX}终态甲{RUN}", "10.00", 100)
    pEb, skuEb = create_product(f"{PREFIX}终态乙{RUN}", "20.00", 100)
    oE, itemsE, dE = make_cart_order(TOKEN_A, [skuEa, skuEb], ADDR_A, "e1", O_PAID)

    check("前置：订单有 2 条明细、状态是已付款(1)",
          len(itemsE) == 2 and order_row(oE)["status"] == O_PAID,
          f"{itemsE} / {order_row(oE)}")

    st, r = apply_as(TOKEN_A, oE, [itemsE[0]], ONLY_REFUND)
    noE1 = r["data"][0]["afterSaleNo"]
    approve_as(noE1)
    check("★★ 部分退：orders.status 仍然是 1（还有一件没退完）",
          order_row(oE)["status"] == O_PAID, f"{order_row(oE)}")
    check("★★ 部分退：售后单本身已经退款完成", after_sale_row(noE1)["status"] == AS_REFUNDED,
          f"{after_sale_row(noE1)}")

    st, r = ship(oE)
    check("★ 部分退之后仍然可以发货（另一件确实还在）", code_of(r) == 200,
          f"HTTP {st} / {r} / {msg_of(r)}")

    st, r = apply_as(TOKEN_A, oE, [itemsE[1]], RETURN_REFUND)
    check("前置：已发货的订单只能申请退货退款", code_of(r) == 200, f"HTTP {st} / {r}")
    noE2 = r["data"][0]["afterSaleNo"]
    approve_as(noE2)
    return_as(TOKEN_A, noE2)
    st, r = receive_as(noE2)
    check("第二件（最后一件）也退完了", code_of(r) == 200, f"HTTP {st} / {r}")

    check("★★★ 整单退完 → orders.status 变成 5（已退款）",
          order_row(oE)["status"] == O_REFUNDED, f"{order_row(oE)}")

    st, r = ship(oE)
    check("★★★★ 订单变成 5 之后，发货被拒（1002）—— 这是 status = 5 存在的【全部意义】",
          code_of(r) == C_ORDER_STATUS_INVALID, f"HTTP {st} / {r} / {msg_of(r)}")

    st, r = complete(TOKEN_A, oE)
    check("★★ 已退款的订单也不能再确认收货 → 1002",
          code_of(r) == C_ORDER_STATUS_INVALID, f"HTTP {st} / {r} / {msg_of(r)}")

    # ==================================================================
    section("F. 运费与金额：两侧边界、整单退才退运费")

    # ---- f1: 98.99 不满门槛 → 收运费 ----
    p_f1, sku_f1 = create_product(f"{PREFIX}差一分{RUN}", "98.99", 50)
    oF1, itemsF1, dF1 = make_order(TOKEN_A, sku_f1, 1, ADDR_A, "f1", O_PAID)
    check("★ 商品 98.99（差 0.01 不满门槛）→ 运费 10.00",
          money(dF1.get("freightAmount")) == FREIGHT_AMOUNT,
          f"freightAmount={dF1.get('freightAmount')}")
    check("★ 实付 = 98.99 + 10.00 = 108.99",
          money(dF1.get("totalAmount")) == Decimal("98.99") + FREIGHT_AMOUNT,
          f"totalAmount={dF1.get('totalAmount')}")
    check("★ 库里的两个金额和接口一致",
          order_row(oF1)["freight_amount"] == FREIGHT_AMOUNT
          and order_row(oF1)["total_amount"] == Decimal("108.99"),
          f"{order_row(oF1)}")

    noF1 = apply_and_get_no(TOKEN_A, oF1, itemsF1, ONLY_REFUND)
    approve_as(noF1)
    row = after_sale_row(noF1)
    check("★★ 整单退 → 退货款 + 运费（108.99）",
          Decimal(row["refund_amount"]) == Decimal("108.99"),
          f"refund_amount={row['refund_amount']}")
    check("★★ 其中运费部分是 10.00", row["refund_freight"] == FREIGHT_AMOUNT,
          f"refund_freight={row['refund_freight']}")
    check("★ 退款去向 = 该订单 pay_method 的快照",
          row["refund_method"] == "ALIPAY", f"{row}")

    # ---- f2: 99.00 恰好等于门槛 → 免运费  ★ 边界的两侧都测 ----
    p_f2, sku_f2 = create_product(f"{PREFIX}刚好够{RUN}", "99.00", 50)
    oF2, itemsF2, dF2 = make_order(TOKEN_A, sku_f2, 1, ADDR_A, "f2", O_PAID)
    check("★★ 商品恰好 99.00（等于门槛）→ 免运费（边界往「免」这边靠）",
          money(dF2.get("freightAmount")) == Decimal("0"),
          f"freightAmount={dF2.get('freightAmount')}")
    check("★ 实付 = 99.00", money(dF2.get("totalAmount")) == Decimal("99.00"),
          f"totalAmount={dF2.get('totalAmount')}")

    noF2 = apply_and_get_no(TOKEN_A, oF2, itemsF2, ONLY_REFUND)
    approve_as(noF2)
    row = after_sale_row(noF2)
    check("★ 包邮的订单整单退 → 退款额 = 货款本身，运费部分是 0",
          Decimal(row["refund_amount"]) == Decimal("99.00")
          and row["refund_freight"] == Decimal("0"),
          f"{row}")

    # ---- f3: 部分退只退货款、整单退退货款 + 运费 ----
    p_fa, sku_fa = create_product(f"{PREFIX}分摊甲{RUN}", "10.00", 100)
    p_fb, sku_fb = create_product(f"{PREFIX}分摊乙{RUN}", "20.00", 100)
    oF3, itemsF3, dF3 = make_cart_order(TOKEN_A, [sku_fa, sku_fb], ADDR_A, "f3", O_PAID)
    check("前置：商品小计 30.00（不满门槛）→ 运费 10.00，实付 40.00",
          money(dF3.get("freightAmount")) == FREIGHT_AMOUNT
          and money(dF3.get("totalAmount")) == Decimal("40.00"),
          f"{dF3.get('freightAmount')} / {dF3.get('totalAmount')}")

    noF3a = apply_and_get_no(TOKEN_A, oF3, [itemsF3[0]], ONLY_REFUND)
    approve_as(noF3a)
    rowA = after_sale_row(noF3a)
    check("★★ 部分退第 1 件 → 只退货款 10.00，运费部分是 0",
          Decimal(rowA["refund_amount"]) == Decimal("10.00")
          and rowA["refund_freight"] == Decimal("0"),
          f"{rowA}")

    noF3b = apply_and_get_no(TOKEN_A, oF3, [itemsF3[1]], ONLY_REFUND)
    approve_as(noF3b)
    rowB = after_sale_row(noF3b)
    check("★★★ 整单退的最后一件 → 退货款 20.00 + 运费 10.00 = 30.00",
          Decimal(rowB["refund_amount"]) == Decimal("30.00")
          and rowB["refund_freight"] == FREIGHT_AMOUNT,
          f"{rowB}")
    check("★★ 两次退款之和 = 30.00 + 10.00 = 40.00 = 实付金额",
          Decimal(rowA["refund_amount"]) + Decimal(rowB["refund_amount"])
          == order_row(oF3)["total_amount"],
          f"{rowA['refund_amount']} + {rowB['refund_amount']} vs "
          f"{order_row(oF3)['total_amount']}")

    # ==================================================================
    section("G. ★★ 评价资格：退过款的那一行不能评价，没退的行照旧能评价")

    # ★★ 这一组要的订单形状很讲究：一笔【两条明细】、已完成的订单，
    #    然后只退其中一条。只有这样「行级事实 vs 订单级状态」才会真的分岔 ——
    #    光看 orders.status = 3 是分不出那两条明细谁退过款的。
    pGa, sku_ga = create_product(f"{PREFIX}评价甲{RUN}", "10.00", 100)
    pGb, sku_gb = create_product(f"{PREFIX}评价乙{RUN}", "20.00", 100)
    oG, itemsG, _ = make_cart_order(TOKEN_A, [sku_ga, sku_gb], ADDR_A, "g1", O_PAID)
    _st, _r = ship(oG)
    _st, _r = complete(TOKEN_A, oG)
    check("前置：2 条明细的订单走完到「已完成」",
          order_row(oG)["status"] == O_COMPLETED and len(itemsG) == 2,
          f"{order_row(oG)} / {itemsG}")

    # 只退第 1 条（退货退款，已完成订单允许）
    noG = apply_and_get_no(TOKEN_A, oG, [itemsG[0]], RETURN_REFUND)
    approve_as(noG)
    return_as(TOKEN_A, noG)
    receive_as(noG)
    check("前置：第 1 条退完，但订单还停在「已完成」（第 2 条没退）",
          after_sale_row(noG)["status"] == AS_REFUNDED
          and order_row(oG)["status"] == O_COMPLETED,
          f"{after_sale_row(noG)} / {order_row(oG)}")

    # ★★★ 这一条断言的形状是刻意的：订单【是】已完成的，
    #     所以「确认收货后才能评价」那道老检查一定会放行 ——
    #     能拦住它的只有新加的那一支。如果顺序写反了（先判订单状态），
    #     这一条会拿到 200 而不是 1013。
    st, r = post_review(TOKEN_A, itemsG[0])
    check("★★★ 退过款的明细 → 1013（★ 不是 1002：这笔订单本身是完全正常的）",
          code_of(r) == C_ORDER_ITEM_REFUNDED, f"HTTP {st} / {r} / {msg_of(r)}")

    st, r = post_review(TOKEN_A, itemsG[1], content="没退的那件，好用")
    check("★★ 没退过款的那一条【照旧能评价】",
          code_of(r) == 200, f"HTTP {st} / {r} / {msg_of(r)}")
    review_id = (r.get("data") or {}) if code_of(r) == 200 else None

    # ★ 再退第 2 条，让订单进终态
    noG2 = apply_and_get_no(TOKEN_A, oG, [itemsG[1]], RETURN_REFUND)
    approve_as(noG2)
    return_as(TOKEN_A, noG2)
    receive_as(noG2)
    check("前置：第 2 条也退完 → 订单变 5",
          order_row(oG)["status"] == O_REFUNDED, f"{order_row(oG)}")

    # ★★★ 评价是用户当时的真实看法，退款不改变「他收到过并写了评价」这个历史事实。
    #     删掉的结果更坏：商品页的评分会因为一笔退款而悄悄变化，谁都无从解释。
    st, r = product_reviews(TOKEN_A, pGb)
    lst = (r.get("data") or {}).get("list") or []
    check("★★★ 先评价、后退款 → 那条评价【仍然在】商品页上（退款不删已有评价）",
          code_of(r) == 200 and any(x["id"] == review_id for x in lst),
          f"共 {len(lst)} 条，找 id={review_id}：{[x['id'] for x in lst]}")

    # ★ 这一条把「已退款」和「已经评价过」两个都为真的情况钉下来：
    #   返回的是 1013，不是 400。
    #   ★ 这是一个【有意的优先级】，理由：1013 说的是「这一行结束了」，
    #     400 说的是「你做过这件事了」——前者是行级的终态事实，
    #     后者是一次操作的历史。行已经结束了，就没必要再跟用户
    #     讨论他做过什么。前端对 1013 的处理也正好更彻底（永久去掉入口）。
    #   ⚠️ 反过来写也不会出错，只会让「已退款的商品」还留着评价入口 ——
    #      所以这里必须有一条断言把它钉住，否则将来调整顺序时没人知道
    #      这个先后是刻意的还是顺手写的。
    st, r = post_review(TOKEN_A, itemsG[1])
    check("★ 「已退款」+「已评价」同时为真时返回 1013（行级终态优先于操作历史）",
          code_of(r) == C_ORDER_ITEM_REFUNDED, f"HTTP {st} / {r} / {msg_of(r)}")

    # ==================================================================
    section("H. 列表与筛选")

    st, r = my_after_sales(TOKEN_A, "?pageSize=100")
    check("用户端能查到自己的售后列表", code_of(r) == 200 and (r.get("data") or {}).get("list"),
          f"HTTP {st} / {(r.get('data') or {}).get('total')}")
    a_nos = {a["afterSaleNo"] for a in (r["data"]["list"] if code_of(r) == 200 else [])}

    st, r = my_after_sales(TOKEN_B, "?pageSize=100")
    b_nos = {a["afterSaleNo"] for a in (r["data"]["list"] if code_of(r) == 200 else [])}
    check("★★ 会员 B 一条 A 的售后都看不到（member_id 是安全边界）",
          not (a_nos & b_nos) and len(a_nos) > 0,
          f"A 有 {len(a_nos)} 条，B 有 {len(b_nos)} 条，交集 {a_nos & b_nos}")

    st, r = my_after_sales(TOKEN_A, "?status=3&pageSize=100")
    lst = (r.get("data") or {}).get("list") or []
    check("★ status 筛选：3 = 只返回退款完成的",
          lst and all(a["status"] == AS_REFUNDED for a in lst),
          f"状态集合：{sorted({a['status'] for a in lst})}")
    total3 = (r.get("data") or {}).get("total")
    check("★ status 筛选时 total 和 list 是同一份条件（不是「显示 12 条、总共 8 条」）",
          int(total3) >= len(lst), f"total={total3} / 本页 {len(lst)} 条")

    st, r = admin_after_sales(f"?afterSaleNo={noF1}")
    lst = (r.get("data") or {}).get("list") or []
    check("★ 管理端按售后单号精确查 → 恰好 1 条",
          len(lst) == 1 and lst[0]["afterSaleNo"] == noF1, f"{lst}")

    st, r = admin_after_sales(f"?memberKeyword={USER_A}&pageSize=100")
    lst = (r.get("data") or {}).get("list") or []
    check("★ 管理端按会员名搜 → 全是 A 的单，且带上了会员用户名",
          lst and all(a.get("memberUsername") == USER_A for a in lst),
          f"{sorted({a.get('memberUsername') for a in lst})}")

    st, r = admin_after_sales(f"?memberKeyword=不存在的会员名{RUN}")
    check("★ 管理端搜一个不存在的会员名 → 0 条（不是报错）",
          code_of(r) == 200 and (r.get("data") or {}).get("total") == 0,
          f"{r.get('data')}")

    st, r = admin_after_sales("?type=2&pageSize=100")
    lst = (r.get("data") or {}).get("list") or []
    check("★ 管理端按 type=2（退货退款）筛",
          lst and all(a["type"] == RETURN_REFUND for a in lst),
          f"类型集合：{sorted({a['type'] for a in lst})}")

    lf = run_sql("SELECT IFNULL(refund_freight, -1) FROM after_sale LIMIT 0")
    check("（占位：refund_freight 这一列从不出现 NULL）", lf == [], f"{lf}")
    n = scalar("SELECT COUNT(*) FROM after_sale WHERE refund_freight IS NULL")
    check("★★ refund_freight 一列一个 NULL 都没有（NOT NULL DEFAULT 0）",
          int(n) == 0, f"{n} 行是 NULL")

    # ==================================================================
    section("I. 全库 SQL 断言（必须全部返回空）")

    # §1.2 两条 —— active_token 和 status 说的是同一件事，一个方向一条
    rows = run_sql("SELECT after_sale_no, status, active_token FROM after_sale "
                   "WHERE status IN (0,1,2) AND active_token <> 0")
    check("★★ §1.2(a) 进行中的单 active_token 必须是 0（该占用没占用 → 同一行两张进行中的单）",
          rows == [], f"{rows}")

    rows = run_sql("SELECT after_sale_no, status, active_token FROM after_sale "
                   "WHERE status IN (3,4,5) AND active_token = 0")
    check("★★ §1.2(b) 已关闭的单 active_token 必须是 id（该释放没释放 → 那一行永远申请不了售后）",
          rows == [], f"{rows}")

    # §3.4 —— 「还库存」只挂在 status → 3 那条 UPDATE 上，所以这条
    #         「一条明细的已退款售后单 ≤ 1 张」等价于「库存最多被还一次」。
    #        ★ 诚实地说：它盖不住「一张单还了两次」，那由 D 组的值断言覆盖。
    rows = run_sql("SELECT order_item_id, COUNT(*) FROM after_sale WHERE status = 3 "
                   "GROUP BY order_item_id HAVING COUNT(*) > 1")
    check("★★★ §3.4 一条明细的【已退款】售后单 ≤ 1 张（= 库存最多被归还一次）",
          rows == [], f"{rows}")

    # §5.2 —— 实付 = 明细小计之和 + 运费。
    #    ★★ 这条同时是「忘了给 OrderMapper.insert 的列清单加 freight_amount」的探测器：
    #      那一列有 DEFAULT 0.00，INSERT 照样成功，运费永远是 0，而 total_amount 含了运费。
    rows = run_sql(
        "SELECT o.order_no, o.total_amount, o.freight_amount, "
        "COALESCE(SUM(i.subtotal), 0) AS 明细合计 "
        "FROM orders o LEFT JOIN order_item i ON i.order_id = o.id "
        "GROUP BY o.id "
        "HAVING o.total_amount <> COALESCE(SUM(i.subtotal), 0) + o.freight_amount")
    check("★★★ §5.2 实付 = 明细小计之和 + 运费（减法 vs 加法，两个方向对着比）",
          rows == [], f"{rows}")

    # §4.3 —— orders.status = 5 与「每一条明细都有一张已退款的售后单」双向等价
    rows = run_sql(
        "SELECT o.order_no, o.status, "
        "(SELECT COUNT(*) FROM order_item i WHERE i.order_id = o.id) AS 明细数, "
        "(SELECT COUNT(*) FROM order_item i WHERE i.order_id = o.id "
        "   AND NOT EXISTS (SELECT 1 FROM after_sale a "
        "                    WHERE a.order_item_id = i.id AND a.status = 3)) AS 未退完数 "
        "FROM orders o "
        "WHERE (o.status = 5 AND (SELECT COUNT(*) FROM order_item i WHERE i.order_id = o.id "
        "        AND NOT EXISTS (SELECT 1 FROM after_sale a "
        "                         WHERE a.order_item_id = i.id AND a.status = 3)) > 0) "
        "   OR (o.status <> 5 "
        "       AND EXISTS (SELECT 1 FROM after_sale a WHERE a.order_id = o.id AND a.status = 3) "
        "       AND EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id) "
        "       AND NOT EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id "
        "            AND NOT EXISTS (SELECT 1 FROM after_sale a "
        "                             WHERE a.order_item_id = i.id AND a.status = 3)))")
    check("★★★ §4.3 orders.status = 5 ⇔ 全部明细都退完（方向 A：提前置终态；方向 B：汇总漏写）",
          rows == [], f"{rows}")

    # §6.3(b) —— 退款三件套的原子性
    rows = run_sql(
        "SELECT after_sale_no, status, refund_amount, refund_time, refund_method, refund_freight "
        "FROM after_sale "
        "WHERE (status = 3) <> (refund_amount IS NOT NULL) "
        "   OR (status = 3) <> (refund_time IS NOT NULL) "
        "   OR (status = 3) <> (refund_method IS NOT NULL) "
        "   OR (status <> 3 AND refund_freight <> 0)")
    check("★★ §6.3(b) 退款三件套 + 运费在同一次 UPDATE 里一起出现（原子性）",
          rows == [], f"{rows}")

    # §6.3(a) —— 退款额之和不得超过实付
    rows = run_sql(
        "SELECT o.order_no, o.total_amount, COALESCE(SUM(a.refund_amount), 0) AS 已退金额 "
        "FROM orders o JOIN after_sale a ON a.order_id = o.id AND a.status = 3 "
        "GROUP BY o.id HAVING COALESCE(SUM(a.refund_amount), 0) > o.total_amount")
    check("★★ §6.3(a) 一张订单的已退金额之和 ≤ 实付金额（全退才退运费，不会退成两份）",
          rows == [], f"{rows}")

    # ==================================================================
    section("清理与基线核对")

    cleanup()
    cleanup_redis()

    left = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
    check("★ 测试会员已删除", int(left[0][0]) == 0, f"剩余 {left[0][0]} 条")
    left = run_sql(f"SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'")
    check("★ 测试商品已删除", int(left[0][0]) == 0, f"剩余 {left[0][0]} 条")
    left = run_sql("SELECT COUNT(*) FROM after_sale")
    check("★★ 售后单表回到 0 行（本次测试造的单全清了）",
          int(left[0][0]) == 0, f"剩余 {left[0][0]} 张")

    orphans = run_sql("SELECT COUNT(*) FROM after_sale a "
                      "LEFT JOIN order_item i ON i.id = a.order_item_id WHERE i.id IS NULL")
    check("★★ 没有孤儿售后单（清理顺序没反）",
          int(orphans[0][0]) == 0, f"有 {orphans[0][0]} 张指向不存在的明细")

    orphans = run_sql("SELECT COUNT(*) FROM order_item i "
                      "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")
    check("★★ 没有孤儿订单明细", int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行明细指向不存在的订单")

    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个，订单 {} 笔，售后 {} 张".format(
        run_sql("SELECT COUNT(*) FROM product")[0][0],
        run_sql("SELECT COUNT(*) FROM category")[0][0],
        run_sql("SELECT COUNT(*) FROM member")[0][0],
        run_sql("SELECT COUNT(*) FROM orders")[0][0],
        run_sql("SELECT COUNT(*) FROM after_sale")[0][0]))

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
