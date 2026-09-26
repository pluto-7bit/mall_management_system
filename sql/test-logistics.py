# -*- coding: utf-8 -*-
"""
里程碑 18 测试：物流（发货带单号 + 手工轨迹 + 签收联动）

这个脚本要守住的是【本轮特有的】几类 bug —— 它们的共同点是
「数据没坏、页面少东西、没人知道」：

  1. ★★ 排序键用 id 而不是 trace_time —— 补录（本功能的【默认用法】：
     不做快递公司 API 对接，手工录入是轨迹唯一的来源）之后时间线倒过来。
     页面、代码、控制台全都正常。

  2. ★★ 排序少了 id 这个次级键 —— 两条 trace_time 相同的节点顺序不确定，
     每次查询还可能不一样：断言随机红，然后被人用 DISTINCT 掩盖。

  3. ★★ 签收联动漏了 / 被 if 包住 —— 录了「已签收」订单却还停在「已发货」。

  4. ★★ 联动【检查了 affected】—— affected = 0 的正常含义是
     「订单已经完成过了」，检查它会假失败 → 回滚事务 →
     连刚写的轨迹节点一起没了。

  5. ★ 发货的 body 变成可选 —— 「已发货但没单号」成为常态，
     用户端连「查看物流」的入口都渲染不出来（它按单号有没有值显示）。

  6. ★ 删除节点时顺手回退了订单状态 —— 订单状态机第一次出现反向边。

  7. ★ 删除只按 traceId、没带 order_id —— 前端传错 id 就能删掉别人的节点。

  8. ★ 鉴权 —— 会员能读别人的物流 / 会员 token 能打管理端物流接口。

运行：
    python test-logistics.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "logi"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None

TOKEN_A = TOKEN_B = None
ID_A = ID_B = None
ADDR_A = None
TEST_MEMBER_IDS = []

# 状态码（和 OrderStatus 对齐，写成本地常量免得每次去翻 Java）
S_PENDING, S_PAID, S_SHIPPED, S_DONE, S_CANCELLED, S_REFUNDED = 0, 1, 2, 3, 4, 5

# 节点状态码（和 LogisticsStatus 对齐）
L_PICKED, L_TRANSIT, L_DELIVERING, L_SIGNED, L_EXCEPTION = 1, 2, 3, 4, 5

R_APPLIED = 1          # 售后原因：不想要了
ONLY_REFUND = 1        # 售后类型：仅退款


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


def db_order(order_no):
    """直接查库看一笔订单的真实状态。

    ★ 验「签收联动真的写进去了」必须走这条路 —— 只看接口响应的话，
      一个「响应说成功但 SQL 没执行」的 bug 会溜过去。
    """
    rows = run_sql(
        f"SELECT status, IFNULL(complete_time,'NULL'), IFNULL(logistics_company,'NULL'), "
        f"IFNULL(tracking_no,'NULL'), IFNULL(ship_time,'NULL') "
        f"FROM orders WHERE order_no = '{order_no}'")
    if not rows:
        return None
    return {
        "status": int(rows[0][0]),
        "completeTime": rows[0][1],
        "logisticsCompany": rows[0][2],
        "trackingNo": rows[0][3],
        "shipTime": rows[0][4],
    }


def trace_ids(order_no):
    """直接从库里取轨迹 id，按接口返回的顺序（trace_time DESC, id DESC）。"""
    return [r[0] for r in run_sql(
        f"SELECT l.id FROM order_logistics l JOIN orders o ON o.id = l.order_id "
        f"WHERE o.order_no = '{order_no}' "
        f"ORDER BY l.trace_time DESC, l.id DESC")]


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
        "password": "logi123456",
        "nickname": f"物流{tag}{RUN}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    d = r["data"]
    TEST_MEMBER_IDS.append(d["id"])
    return d["token"], d["id"]


def create_product(name, price, stock):
    # 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品也要显式给一条
    # 「默认 SKU」（specs 为空数组）。
    st, r = call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name,
        "specSchema": [],
        "skus": [{"specs": [], "price": price, "stock": stock}],
        "status": 1,
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {r['data']}"))


def create_address(token):
    st, r = call("POST", "/shop/addresses", {
        "receiver": "物流测试", "phone": "13800000000",
        "region": "测试省测试市测试区", "detail": f"{PREFIX}路 1 号",
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"建测试地址失败：HTTP {st} / {r}")
    return r["data"]


def key_for(tag):
    """幂等键：必须符合后端 @Pattern("^[A-Za-z0-9_-]{8,64}$")。"""
    return f"k{RUN}{tag}"


def get_order(token, order_no):
    return call("GET", f"/shop/orders/{order_no}", None, token=token)


def make_order(token, sku_id, tag, state=S_PENDING):
    """建一笔【单明细】订单并推进到指定状态。返回 (orderNo, [orderItemId])。"""
    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": sku_id, "quantity": 1,
        "addressId": ADDR_A, "idempotencyKey": key_for(tag),
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"立即购买失败（{tag}）：HTTP {st} / {r}")
    order_no = r["data"]["orderNo"]
    item_ids = [it["id"] for it in r["data"]["items"]]

    # ★★ 取消要【最先】判断：取消在状态图里只有「待付款 → 已取消」这一条边，
    #   所以它必须在付款【之前】发生。
    #   ⚠️ 本脚本第一版把它写在了 pay 之后，于是 cancel 拿到 1002
    #      （「订单当前是『已发货』，无法取消」）—— 状态机拦住了，是好事。
    if state == S_CANCELLED:
        st, r = call("POST", f"/shop/orders/{order_no}/cancel", None, token=token)
        if r.get("code") != 200:
            raise SystemExit(f"取消失败（{tag}）：HTTP {st} / {r}")
        return order_no, item_ids

    if state >= S_PAID:
        st, r = call("POST", f"/shop/orders/{order_no}/pay",
                     {"payMethod": "ALIPAY"}, token=token)
        if r.get("code") != 200:
            raise SystemExit(f"支付失败（{tag}）：HTTP {st} / {r}")

    if state >= S_SHIPPED:
        st, r = ship(order_no)
        if r.get("code") != 200:
            raise SystemExit(f"发货失败（{tag}）：HTTP {st} / {r}")

    return order_no, item_ids


# ★ 里程碑 18：发货接口现在【必须】带承运商 + 快递单号（服务端不接受空值）。
SHIP_BODY = {"logisticsCompany": "顺丰", "trackingNo": "SF1234567890"}


def ship(order_no, body=SHIP_BODY):
    return call("POST", f"/admin/orders/{order_no}/ship", body, token=ADMIN_TOKEN)


def add_trace(order_no, status, desc, trace_time, token=None):
    return call("POST", f"/admin/orders/{order_no}/logistics", {
        "status": status, "description": desc, "traceTime": trace_time,
    }, token=token or ADMIN_TOKEN)


def get_logistics(order_no, token=None):
    """管理端读物流（默认）。传 token 可以换成用户端那条路。"""
    return call("GET", f"/admin/orders/{order_no}/logistics", None,
                token=token or ADMIN_TOKEN)


def del_trace(order_no, trace_id):
    return call("DELETE", f"/admin/orders/{order_no}/logistics/{trace_id}", None,
                token=ADMIN_TOKEN)


def now_str():
    return time.strftime("%Y-%m-%d %H:%M:%S")


def yesterday_str():
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - 86400))


# ======================================================================
def main():
    global TOKEN_A, TOKEN_B, ID_A, ID_B, ADDR_A

    admin_login()
    TOKEN_A, ID_A = register("a")
    TOKEN_B, ID_B = register("b")
    ADDR_A = create_address(TOKEN_A)
    sku = create_product(f"{PREFIX}测试商品{RUN}", 10.00, 500)

    # ==================================================================
    section("A. 结构：两张表的列都加对了")

    cols = run_sql(
        "SELECT COLUMN_NAME, IS_NULLABLE, DATA_TYPE, "
        "       IFNULL(CHARACTER_MAXIMUM_LENGTH, 0), ORDINAL_POSITION "
        "FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'orders' "
        "  AND COLUMN_NAME IN ('ship_time','logistics_company','tracking_no','complete_time') "
        "ORDER BY ORDINAL_POSITION")
    check("orders 的 4 个生命周期列都在", len(cols) == 4, f"查到 {len(cols)} 个：{cols}")

    by_name = {c[0]: c for c in cols}
    check("★ logistics_company 是 VARCHAR(50) 且可空",
          by_name.get("logistics_company", ["", ""])[1] == "YES"
          and by_name.get("logistics_company", ["", "", ""])[2] == "varchar"
          and by_name["logistics_company"][3] == "50",
          f"实际 {by_name.get('logistics_company')}")
    check("★ tracking_no 是 VARCHAR(64) 且可空",
          by_name.get("tracking_no", ["", ""])[1] == "YES"
          and by_name["tracking_no"][3] == "64",
          f"实际 {by_name.get('tracking_no')}")
    check("★★ 两列插在 ship_time 之后（列序和迁移链一致）",
          by_name.get("ship_time", ["", "", "", "", "0"])[4] == str(
              int(by_name.get("logistics_company", ["", "", "", "", "0"])[4]) - 1),
          f"ship_time 在第 {by_name.get('ship_time', ['', '', '', '', '?'])[4]} 位，"
          f"logistics_company 在第 {by_name.get('logistics_company', ['', '', '', '', '?'])[4]} 位")

    tcols = run_sql(
        "SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = 'mall' AND TABLE_NAME = 'order_logistics'")
    tmap = {c[0]: c[1] for c in tcols}
    check("order_logistics 表存在且 6 列齐全",
          set(tmap) == {"id", "order_id", "status", "description", "trace_time",
                        "create_time"},
          f"实际列：{sorted(tmap)}")
    check("★ 只有 create_time 可空，其余都是 NOT NULL",
          tmap.get("create_time") == "NO"
          and all(v == "NO" for k, v in tmap.items()
                  if k in ("order_id", "status", "description", "trace_time")),
          f"实际 {tmap}")

    idx = run_sql("SHOW INDEX FROM order_logistics WHERE Key_name = 'idx_order_trace'")
    idx_cols = [r[4] for r in sorted(idx, key=lambda r: r[3])]
    check("★★ 索引是 (order_id, trace_time, id) 三列且顺序正确",
          idx_cols == ["order_id", "trace_time", "id"], f"实际 {idx_cols}")

    # ★★ 为什么【不】断言「存量订单的两列是 NULL」：
    #   那是「迁移那一刻的样子」，是一个【快照】不是【不变量】——
    #   用户哪天正常地把一笔老的已付款订单发货了，这条断言就红了，
    #   而它想守的那件事（迁移没发明数据）好端端的。
    #   同一个坑在 test-sku.py 里踩过一次（那条「商品总数是 100 件」）。
    #
    #   下面两条换成【永远成立的不变量】：它们说的是
    #   「这两列只可能由同一条 UPDATE 一起写」，所以怎么用都不会过期。
    mismatch = scalar(
        "SELECT COUNT(*) FROM orders "
        "WHERE (logistics_company IS NULL) <> (tracking_no IS NULL)")
    check("★★ 承运商和单号总是【同时有或同时无】（它们写在同一条 UPDATE 里）",
          int(mismatch) == 0, f"有 {mismatch} 笔只填了一个 —— 说明有人分两次写的")

    no_ship = scalar(
        "SELECT COUNT(*) FROM orders WHERE tracking_no IS NOT NULL AND ship_time IS NULL")
    check("★★ 有单号就一定有发货时间（不可能「发了货但没记时间」）",
          int(no_ship) == 0, f"有 {no_ship} 笔有单号却没有 ship_time")

    # ==================================================================
    section("B. 发货带单号：请求体是【必填】的契约")

    o_paid, _ = make_order(TOKEN_A, sku, "B1", S_PAID)

    # ① 不带 body → HTTP 400。★ 这是本轮「契约真的变了」的见证：
    #    里程碑 10 的这个接口没有请求体，多传的 body 会被【静默忽略】；
    #    现在它是必填的。
    st, r = call("POST", f"/admin/orders/{o_paid}/ship", None, token=ADMIN_TOKEN)
    check("★★ 发货不带请求体 → HTTP 400（钉住「必填」而不是「可选」）",
          st == 400, f"HTTP {st} / {r}")

    # ② 空对象 → @Valid 那条路，业务码 400
    #
    # ★★ 这里【不能】断言报的是哪一个字段：两个字段都缺时，
    #    Hibernate Validator 不保证先报哪个 —— 同一份代码，
    #    这条断言在两次运行里先后拿到过「请填写快递公司」和「请填写快递单号」。
    #    钉死其中一个等于写了一条会随机红的断言，然后被人改成永远为真。
    #    真正要钉的是「空 body 会被 @Valid 拦住」，以及下面那两条【只缺一个字段】
    #    的用例（那种情况下只有一个违规，是确定的）。
    st, r = ship(o_paid, {})
    check("★ 发货 body 是空对象 → code 400，提示里点名了缺哪个字段",
          r.get("code") == 400 and "快递" in (r.get("message") or ""),
          f"HTTP {st} / {r}")

    st, r = ship(o_paid, {"logisticsCompany": "顺丰"})
    check("★ 只填承运商 → code 400 且提示缺快递单号",
          r.get("code") == 400 and "快递单号" in (r.get("message") or ""),
          f"HTTP {st} / {r}")

    st, r = ship(o_paid, {"logisticsCompany": "  ", "trackingNo": "SF1"})
    check("★ 承运商只有空格（@NotBlank 而不是 @NotNull）→ code 400",
          r.get("code") == 400, f"HTTP {st} / {r}")

    # ③ 长度边界
    st, r = ship(o_paid, {"logisticsCompany": "A" * 51, "trackingNo": "SF1"})
    check("★ 承运商 51 字（列宽 50）→ code 400",
          r.get("code") == 400 and "50" in (r.get("message") or ""),
          f"HTTP {st} / {r}")

    st, r = ship(o_paid, {"logisticsCompany": "顺丰", "trackingNo": "B" * 65})
    check("★ 单号 65 字（列宽 64）→ code 400",
          r.get("code") == 400 and "64" in (r.get("message") or ""),
          f"HTTP {st} / {r}")

    # ④ 边界【正好等于】列宽 → 必须成功（@Size(max=N) 是 ≤ 不是 <）
    o_edge, _ = make_order(TOKEN_A, sku, "B2", S_PAID)
    edge_body = {"logisticsCompany": "C" * 50, "trackingNo": "D" * 64}
    st, r = ship(o_edge, edge_body)
    check("★★ 承运商正好 50 字 / 单号正好 64 字 → 200（上界是【包含】的）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    row = run_sql(f"SELECT CHAR_LENGTH(logistics_company), CHAR_LENGTH(tracking_no) "
                  f"FROM orders WHERE order_no = '{o_edge}'")
    check("★ 满长度的两个值原样写进了库里（没有被截断）",
          row and row[0] == ["50", "64"], f"实际 {row}")

    # ⑤ 正常发货：响应回显 + 库里真的写进去了
    o_ship, _ = make_order(TOKEN_A, sku, "B3", S_PAID)
    st, r = ship(o_ship)
    check("发货 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    d = r.get("data") or {}
    check("★★ 发货响应里回显了承运商和快递单号（前端拿它直接刷新那一行）",
          d.get("logisticsCompany") == "顺丰" and d.get("trackingNo") == "SF1234567890",
          f"拿到 logisticsCompany={d.get('logisticsCompany')!r} / "
          f"trackingNo={d.get('trackingNo')!r}")
    check("发货响应里状态变成了已发货（2）", d.get("status") == S_SHIPPED,
          f"status={d.get('status')}")
    check("发货响应里有 ship_time", bool(d.get("shipTime")), f"shipTime={d.get('shipTime')}")

    db = db_order(o_ship)
    check("★★ 直查库：两列和时间都真的写进去了",
          db["logisticsCompany"] == "顺丰" and db["trackingNo"] == "SF1234567890"
          and db["shipTime"] != "NULL", f"实际 {db}")

    # ⑥ 重复发货仍然被 status=1 的闸门挡住（里程碑 10 的规矩没变）
    st, r = ship(o_ship)
    check("★ 已发货的订单再发一次 → 1002（闸门 WHERE status = 1 一个字没动）",
          r.get("code") == 1002, f"HTTP {st} / {r}")

    # ==================================================================
    section("C. 轨迹：补录、排序、增删")

    st, r = get_logistics(o_ship)
    check("刚发货的订单：单号有了、轨迹是空数组（不是 null）",
          r.get("code") == 200 and r["data"].get("traces") == [],
          f"HTTP {st} / {r}")

    st, r = get_logistics(o_ship)
    check("★ 未发货时的三个顶层字段齐全（这一单已发货，所以有值）",
          r["data"].get("logisticsCompany") == "顺丰", f"{r}")

    # ★★ 核心场景：补录。先录【今天】，再录一条【昨天】的。
    st, r = add_trace(o_ship, L_TRANSIT, "快件已到达【杭州转运中心】", now_str())
    check("新增节点（运输中）→ 200", r.get("code") == 200, f"HTTP {st} / {r}")
    id_today = (r.get("data") or {}).get("traces", [{}])[0].get("id")

    st, r = add_trace(o_ship, L_PICKED, "顺丰速运已揽收", yesterday_str())
    check("新增节点（已揽收，时间填【昨天】= 补录）→ 200", r.get("code") == 200,
          f"HTTP {st} / {r}")
    traces = (r.get("data") or {}).get("traces") or []
    id_yesterday = traces[-1].get("id") if traces else None

    check("★★ 补录（时间填昨天、但后录）的那条【排在下面】，不在最上面",
          len(traces) == 2 and traces[0].get("id") == id_today,
          f"顺序（id）：{[t.get('id') for t in traces]}，"
          f"今天录的 id={id_today}，补录的 id={id_yesterday}")
    check("★ 返回顺序是按 trace_time 倒序（最新在上）",
          [t.get("traceTime") for t in traces] == sorted(
              [t.get("traceTime") for t in traces], reverse=True),
          f"实际 {[t.get('traceTime') for t in traces]}")

    # ★ 相同 trace_time 的两条：顺序必须【稳定】，而且按 id 倒序做次级键
    same = now_str()
    add_trace(o_ship, L_DELIVERING, "派送中（同一时刻 A）", same)
    add_trace(o_ship, L_EXCEPTION, "异常（同一时刻 B）", same)

    seen = []
    for _ in range(5):
        st, r = get_logistics(o_ship)
        # ★ 记 (id, traceTime) 两元组：稳不稳看整个序列，
        #   而「次级键是不是 id」要在相同 traceTime 的组内看。
        seen.append(tuple((t.get("id"), t.get("traceTime"))
                          for t in r["data"]["traces"]))
    check("★★ 连查 5 次，相同 trace_time 的节点顺序完全一致（次级键 id 在起作用）",
          len(set(seen)) == 1, f"5 次拿到 {len(set(seen))} 种顺序：{set(seen)}")

    # ★★ 「次级键是 id DESC」只能【在 trace_time 相同的组内】断言。
    #   ⚠️ 写成「整个列表按 id 倒序」是错的（本脚本第一版就写错了）：
    #      id 倒序只在同一时刻里成立，而列表里混着好几个不同的 trace_time ——
    #      [4, 3, 1, 2] 才是对的（4/3 是同一时刻，1 是今天更早，2 是昨天）。
    #   分组验的话，这条断言才真的在验「次级键」而不是在验「恰好没别的节点」。
    groups = {}
    for t in seen[0]:
        groups.setdefault(t[1], []).append(t[0])
    tie_groups = [g for g in groups.values() if len(g) > 1]
    check("★★ 同一 trace_time 的组内，id 是倒序（后录的排在上面）",
          tie_groups and all(g == sorted(g, reverse=True) for g in tie_groups),
          f"各时刻的 id 分组：{groups}")

    # 描述两端空格被 trim（@NotBlank 只挡全空白，不挡首尾空格）
    st, r = add_trace(o_ship, L_TRANSIT, "  到达【北京转运中心】  ", now_str())
    got = [t.get("description") for t in r["data"]["traces"]
           if t.get("description", "").find("北京") >= 0]
    check("★ 节点说明两端的空格被 trim 掉", got == ["到达【北京转运中心】"],
          f"实际 {got}")

    st, r = add_trace(o_ship, L_TRANSIT, "   ", now_str())
    check("★ 说明只有空格 → code 400（@NotBlank）", r.get("code") == 400,
          f"HTTP {st} / {r}")

    st, r = add_trace(o_ship, 99, "状态码 99", now_str())
    check("★★ 节点状态 99（不在码表里）→ code 400（白名单校验，不是 @Min/@Max）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = add_trace(o_ship, L_TRANSIT, "缺时间", None)
    check("★ traceTime 不传 → code 400（必填，不许服务端兜底成 now）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    # 删一个不存在的 id
    st, r = del_trace(o_ship, 99999999)
    check("删不存在的节点 → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # ★★ 删别人的订单的节点 → 必须删不到
    o_other, _ = make_order(TOKEN_A, sku, "C1", S_SHIPPED)
    st, r = add_trace(o_other, L_TRANSIT, "另一单的节点", now_str())
    other_trace_id = r["data"]["traces"][0]["id"]

    st, r = del_trace(o_ship, other_trace_id)
    check("★★ 拿【另一张订单】的节点 id 去删 → 1003，而且那个节点还在",
          r.get("code") == 1003
          and str(other_trace_id) in trace_ids(o_other),
          f"code={r.get('code')}，另一单还剩 {trace_ids(o_other)}")

    # 正常删除
    before = len(trace_ids(o_ship))
    st, r = del_trace(o_ship, id_today)
    check("删除自己的节点 → 200 且数量少 1",
          r.get("code") == 200 and len(trace_ids(o_ship)) == before - 1,
          f"HTTP {st} / 前 {before} 后 {len(trace_ids(o_ship))}")
    check("★ 返回的是删除后的完整列表（前端直接用，不用再发一次 GET）",
          r.get("code") == 200 and isinstance(r["data"].get("traces"), list)
          and len(r["data"]["traces"]) == before - 1, f"{r}")

    # ==================================================================
    section("D. ★★ 签收联动：录「已签收」→ 订单自动完成")

    o_sign, _ = make_order(TOKEN_A, sku, "D1", S_SHIPPED)
    add_trace(o_sign, L_TRANSIT, "运输中", now_str())

    db = db_order(o_sign)
    check("前置：这一单现在是已发货（2）、complete_time 为空",
          db["status"] == S_SHIPPED and db["completeTime"] == "NULL", f"{db}")

    st, r = add_trace(o_sign, L_SIGNED, "已签收，感谢使用顺丰", now_str())
    check("录「已签收」→ 200", r.get("code") == 200, f"HTTP {st} / {r}")

    db = db_order(o_sign)
    check("★★ 订单被自动推到了已完成（3）",
          db["status"] == S_DONE, f"status={db['status']}（期望 3）")
    check("★★ complete_time 被写上了（★ 它同时是售后 7 天窗口的起点）",
          db["completeTime"] != "NULL", f"completeTime={db['completeTime']}")

    # ★★ affected = 0 不是错误：再录一条签收
    n_before = len(trace_ids(o_sign))
    st, r = add_trace(o_sign, L_SIGNED, "已签收（管理员重复录了一次）", now_str())
    check("★★ 再录一条「已签收」→ 仍然 200（affected = 0 是正常情况，不是失败）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    check("★★ 而且那一条新节点【真的写进去了】（没被假失败回滚掉）",
          len(trace_ids(o_sign)) == n_before + 1,
          f"前 {n_before} 条，后 {len(trace_ids(o_sign))} 条")
    check("★ 订单状态没被第二次联动搞坏，还是已完成", db_order(o_sign)["status"] == S_DONE,
          f"{db_order(o_sign)}")

    # 给不该记物流的订单记 → 1002
    o_p2, _ = make_order(TOKEN_A, sku, "D2", S_PAID)
    st, r = add_trace(o_p2, L_TRANSIT, "还没发货就记物流", now_str())
    check("★ 给【已付款】的订单记物流 → 1002", r.get("code") == 1002,
          f"HTTP {st} / {r}")
    check("★ 1002 的话术把当前状态说出来了（不是干巴巴一句「状态不允许」）",
          "已付款" in (r.get("message") or ""), f"message={r.get('message')!r}")

    o_c, _ = make_order(TOKEN_A, sku, "D3", S_CANCELLED)
    st, r = add_trace(o_c, L_TRANSIT, "已取消的订单", now_str())
    check("★ 给【已取消】的订单记物流 → 1002", r.get("code") == 1002,
          f"HTTP {st} / {r}")

    # 已退款：★ 用【已付款】的订单走「仅退款」。
    #   ⚠️ 不能拿已发货的订单做这个：售后的规则是「已发货只能申请退货退款」
    #      （要买家先寄回、卖家确认收到才退），多两步而且不是本脚本要验的东西。
    #      本脚本要的只是一个 status = 5 的订单。
    o_r, items_r = make_order(TOKEN_A, sku, "D4", S_PAID)
    st, r = call("POST", "/shop/after-sales", {
        "orderNo": o_r, "orderItemIds": items_r,
        "type": ONLY_REFUND, "reason": R_APPLIED,
    }, token=TOKEN_A)
    if r.get("code") != 200:
        raise SystemExit(f"申请售后失败（D4）：HTTP {st} / {r}")
    as_no = r["data"][0]["afterSaleNo"]
    st, r = call("POST", f"/admin/after-sales/{as_no}/approve", None, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"同意售后失败（D4）：HTTP {st} / {r}")
    check("前置：这一单已经变成已退款（5）", db_order(o_r)["status"] == S_REFUNDED,
          f"{db_order(o_r)}")

    st, r = add_trace(o_r, L_TRANSIT, "已退款的订单", now_str())
    check("★ 给【已退款】的订单记物流 → 1002（货都退回来了）",
          r.get("code") == 1002, f"HTTP {st} / {r}")

    # ★★ §5.2 那条【只单向成立】的断言
    bad = run_sql(
        "SELECT o.order_no FROM orders o "
        "WHERE o.status = 2 AND EXISTS (SELECT 1 FROM order_logistics l "
        "                               WHERE l.order_id = o.id AND l.status = 4)")
    check("★★ 有「已签收」节点的订单，没有一张还停在「已发货」",
          len(bad) == 0, f"这些订单录了签收却没联动：{bad}")

    # ==================================================================
    section("E. ★★ 删除节点【不回退】订单状态")

    db_before = db_order(o_sign)
    signed_ids = [t for t in trace_ids(o_sign)]
    st, r = del_trace(o_sign, int(signed_ids[0]))
    check("删掉那条签收节点 → 200", r.get("code") == 200, f"HTTP {st} / {r}")

    db_after = db_order(o_sign)
    check("★★ 订单【仍然是已完成】（3）—— 删除不回退，这是决定不是漏了",
          db_after["status"] == S_DONE,
          f"删之前 {db_before['status']}，删之后 {db_after['status']}")
    check("★★ complete_time 也没被清掉（售后 7 天窗口的起点保持不动）",
          db_after["completeTime"] == db_before["completeTime"],
          f"前 {db_before['completeTime']}，后 {db_after['completeTime']}")

    # ==================================================================
    section("F. 鉴权：物流里含收货地址级别的隐私信息")

    # 用户端读【自己】的 → 200
    st, r = call("GET", f"/shop/orders/{o_ship}/logistics", None, token=TOKEN_A)
    check("用户查自己的订单物流 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 用户端和管理端返回的是同一个形状",
          isinstance(r.get("data"), dict)
          and r["data"].get("trackingNo") == "SF1234567890"
          and isinstance(r["data"].get("traces"), list), f"{r}")

    # 用户端读【别人】的 → 1003
    st, r = call("GET", f"/shop/orders/{o_ship}/logistics", None, token=TOKEN_B)
    check("★★ 会员 B 查会员 A 的订单物流 → 1003（不是 403，也不多说一个字）",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = call("GET", f"/shop/orders/{o_ship}/logistics", None, token=ADMIN_TOKEN)
    check("★★ 管理员 token 调用户端物流接口 → 401",
          st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", f"/shop/orders/{o_ship}/logistics")
    check("未登录查订单物流 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", f"/admin/orders/{o_ship}/logistics", None, token=TOKEN_A)
    check("★★ 会员 token 调管理端物流接口 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", f"/admin/orders/{o_ship}/logistics")
    check("未登录查管理端物流接口 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = add_trace(o_ship, L_TRANSIT, "会员越权录节点", now_str(), token=TOKEN_A)
    check("★★ 会员 token 往管理端录物流节点 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("DELETE", f"/admin/orders/{o_ship}/logistics/{signed_ids[0]}", None,
                 token=TOKEN_A)
    check("★★ 会员 token 调管理端删节点 → 401", st == 401, f"HTTP {st} / {r}")

    # 订单不存在时，管理端读物流 → 1003（而不是 500）
    st, r = call("GET", "/admin/orders/ZZZZNOPE/logistics", None, token=ADMIN_TOKEN)
    check("管理端读一笔不存在的订单的物流 → 1003", r.get("code") == 1003,
          f"HTTP {st} / {r}")

    # ==================================================================
    section("G. 清理与核对")

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

    orphan_trace = scalar(
        "SELECT COUNT(*) FROM order_logistics l "
        "LEFT JOIN orders o ON o.id = l.order_id WHERE o.id IS NULL")
    check("★★ 没有孤儿物流节点（删订单之前先删了轨迹）",
          int(orphan_trace) == 0,
          f"有 {orphan_trace} 行轨迹指向不存在的订单 —— 清理顺序反了")

    orphan_item = scalar(
        "SELECT COUNT(*) FROM order_item i "
        "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")
    check("★ 没有孤儿订单明细", int(orphan_item) == 0, f"有 {orphan_item} 行")

    orphan_as = scalar(
        "SELECT COUNT(*) FROM after_sale a "
        "LEFT JOIN orders o ON o.id = a.order_id WHERE o.id IS NULL")
    check("★ 没有孤儿售后单", int(orphan_as) == 0, f"有 {orphan_as} 行")

    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个，订单 {} 笔，"
          "售后 {} 张，物流节点 {} 条".format(
              run_sql("SELECT COUNT(*) FROM product")[0][0],
              run_sql("SELECT COUNT(*) FROM category")[0][0],
              run_sql("SELECT COUNT(*) FROM member")[0][0],
              run_sql("SELECT COUNT(*) FROM orders")[0][0],
              run_sql("SELECT COUNT(*) FROM after_sale")[0][0],
              run_sql("SELECT COUNT(*) FROM order_logistics")[0][0]))

    print()
    print("=" * 72)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 72)
    if FAILED:
        print()
        print("失败清单：")
        for f in FAILED:
            print(f"  - {f}")


# ----------------------------------------------------------------------
def cleanup():
    # ★ 顺序：晒图 → 评价 → 物流轨迹 → 售后 → 明细 → 订单 → 地址 → SKU → 商品 → 会员。
    #   这些表【全都没有外键】（既定约定），所以顺序错了就是孤儿行，
    #   不会有任何一层拦你。
    run_sql("DELETE pri FROM product_review_image pri "
            "JOIN product_review r ON r.id = pri.review_id "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE r FROM product_review r "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    # ★ 里程碑 18 新增的这一层，必须在 orders 之前。
    run_sql("DELETE l FROM order_logistics l "
            "JOIN orders o ON o.id = l.order_id "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE a FROM after_sale a "
            "JOIN orders o ON o.id = a.order_id "
            f"JOIN member m ON m.id = o.member_id WHERE m.username LIKE '{PREFIX}%'")
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
    # ★ 只删这次测试用到的会员的购物车，不用 KEYS mall:cart:* 一锅端。
    for mid in TEST_MEMBER_IDS:
        redis_cmd("DEL", f"mall:cart:{mid}")


if __name__ == "__main__":
    try:
        main()
    finally:
        # ★★ 断言失败 / 中途 SystemExit / Ctrl-C 都要清理。
        #
        #   「清理」不该依赖「跑到底」—— 本脚本第一版在 D3 崩掉时就是直接
        #   SystemExit 出局，G 组一个字没跑，库里留下 2 个会员、1 件商品、
        #   7 笔订单和 9 条轨迹，然后要人工去认哪些是测试数据。
        #   而人工认数据这件事，正是本项目最不想要的（认错就删了用户的）。
        #
        #   ★ 成功路径上这里是【第二次】调用：cleanup 里的 DELETE 都是幂等的，
        #     删第二次影响 0 行，不会把别的东西删掉。
        cleanup()
        cleanup_redis()
