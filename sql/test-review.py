# -*- coding: utf-8 -*-
"""
里程碑 12 测试：商品评价

这个脚本和前面那些最不一样的地方：**它测的主要不是 CRUD，是一条推导出来的规则。**

「这个人能不能评这个商品」不是他填了什么表单，而是从【订单域】推出来的一个资格：
订单已确认收货 + 这条明细还没被评过。所以本脚本前半部分的重心全在
「资格判得对不对」上，后半部分才是列表/聚合/管理端那些常规的东西。

它要守住的几类 bug：

  1. ★★ 资格判错 —— 没确认收货就放行（凭空多出一堆无法核实的评价），
     或者更糟：**能评别人的订单**。后者是越权，必须返回 1003 而不是 1002，
     否则「这条明细存在」这件事本身就泄漏了 —— 攻击者能靠错误码的差别
     枚举出别人的订单明细 id。

  2. ★★★ 「一次定终身」被绕过 —— 这是本轮最有鉴别力的一条。
     它不是断言 Service 里那句查重，而是**直插一次库，要求 MySQL 报
     Duplicate entry**。
     ★ 把 ProductReviewServiceImpl 里那句查重删掉，这条仍然应该过；
       而把 uk_order_item 索引删掉，这条会立刻红。
       这才是「这个不变量由数据库保证」的正确测法。

  3. ★★★ 并发重复评价 —— 20 个线程同时评同一条明细。
     Java 里的「先查后写」挡不住并发，真正的闸门是唯一索引；
     而唯一索引被触发时抛的是 DuplicateKeyException，
     必须被 catch 成 400，不能漏成 500。

  4. ★ 星级分布写反 —— 造一组【故意不均匀】的分布（5 星×2、3 星×1），
     断言 count5/count3/count2。全 5 星的数据上，count1..count5
     就算整段写反了也看不出来 —— 那种用例是没有鉴别力的。

  5. ★★ 隐私泄漏 —— 匿名游客能拉的评价列表里，不能出现
     phone / username。这是 MemberInfoVO.phone 的 javadoc 在里程碑 10
     就写下的预言，本轮把它变成断言。

  6. ★ 级联顺序 —— 删商品时要连着删晒图 → 评价。
     两张表都没有外键（全库约定），顺序反了不报错，只留孤儿行。

  7. ★ 磁盘文件清理 —— 沿用 test-upload.py 的做法：
     只删本脚本自己上传的路径，逐个按 URL 映射回磁盘，绝不通配。

运行：
    python test-review.py
"""

import json
import os
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import zlib

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

UPLOAD_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mall-server", "uploads")

RUN = str(int(time.time()))[-8:]
PREFIX = "rev"

# 订单状态常量。★ 用名字而不是 0/1/2/3 —— 用例名里写「status=2」的话，
# 半年后没人记得 2 是发货还是收货。
PENDING_PAY = 0
PAID = 1
SHIPPED = 2
COMPLETED = 3

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

# ★ 本次脚本上传过的所有 URL。清理时【只删这里面记着的】。
UPLOADED = []

# 用来建幂等键的序号 —— 每次自增，保证同一个脚本里不会有重复的键
KEY_SEQ = [0]


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


def post_file(path, filename, content, content_type="image/png", token=None,
              field="file", timeout=60):
    """
    发一个 multipart/form-data 请求。

    ★ 从 test-upload.py 原样复制过来的 —— 两个脚本不共享代码（每个脚本
      都必须能单独跑），所以宁可复制一份也不 import。
      （这属于「测试脚本之间」的复制，和业务代码的复制是两回事：
        业务代码复制出的第二份会各自演化、互相不一致；
        而这段胶水是死的，它不会变。）

    ⚠️ 三个细节一个都不能错：
       · 每个换行都必须是 \\r\\n（不是 \\n）
       · 头部和 body 之间有一个【空行】
       · 结束的 boundary 后面要跟 "--"
    """
    boundary = "----MallReview" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n"
        f"\r\n"
    ).encode("utf-8")
    body = head + content + f"\r\n--{boundary}--\r\n".encode("utf-8")

    headers = {
        "Accept": "application/json",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=body, headers=headers,
                                 method="POST")
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


def try_sql(sql):
    """
    执行一条【允许失败】的 SQL，返回 (ok, stderr)。

    ★ 为什么需要它：唯一索引那条断言的本体就是「这条 SQL 必须报错」。
      而 run_sql 在 returncode != 0 时直接 SystemExit —— 那是给
      「脚本自己写错了 SQL」用的护栏，在这里反而会挡住用例。
    """
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return result.returncode == 0, (result.stderr or "")


def redis_cmd(*args):
    result = subprocess.run(
        ["docker", "exec", REDIS, "redis-cli", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"redis-cli 执行失败：{args}\n{result.stderr}")
    return [l for l in result.stdout.strip().splitlines() if l]


# ----------------------------------------------------------------------
def make_png(w=4, h=4, rgb=(200, 100, 50), compress_level=6):
    """造一张真正合法的 PNG（照抄 test-upload.py，理由见那边）。"""
    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + bytes(list(rgb) * w) for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, compress_level))
            + chunk(b"IEND", b""))


PNG = make_png()


def url_to_disk(url):
    """把 /uploads/... 映射回磁盘路径。⚠️ 只做前缀剥离，不通配。"""
    assert url.startswith("/uploads/"), f"不是上传路径：{url}"
    rel = url[len("/uploads/"):]
    full = os.path.abspath(os.path.join(UPLOAD_ROOT, rel))
    root = os.path.abspath(UPLOAD_ROOT)
    if not full.startswith(root + os.sep):
        raise SystemExit(f"拒绝删除 uploads 目录之外的文件：{full}")
    return full


def count_files():
    n = 0
    for _, _, files in os.walk(UPLOAD_ROOT):
        n += len(files)
    return n


# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r = call("POST", "/admin/auth/login",
                 {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(run_sql("SELECT id FROM category ORDER BY id LIMIT 1")[0][0])


def register(tag):
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}{tag}{RUN}",
        "password": "rev123456",
        "nickname": f"评价测试{tag}{RUN}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    d = r["data"]
    return d["token"], d["id"]


def scalar(sql, default=None):
    rows = run_sql(sql)
    return rows[0][0] if rows else default


def create_product(name, price=19.90, stock=1000, status=1):
    # 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品也要显式给一条
    # 「默认 SKU」（specs 为空数组），后端拿它的 price/stock 作为这件商品的价格和库存。
    st, r = call("POST", "/admin/products", {
        "categoryId": CATEGORY_ID, "name": name, "status": status,
        "specSchema": [],
        "skus": [{"specs": [], "price": price, "stock": stock}],
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    # ★ 里程碑 15 阶段 4：这里【保持】返回商品 id，和以前一样。
    #   理由是这个脚本里 p1/p2/p3 主要在讲【评价】—— 评价是商品级的
    #   （一件商品一个评价列表，和规格无关），所以商品详情、评论列表、
    #   筛选项全都按商品 id 走。只有「下单/加购」那几步需要规格 id，
    #   那里显式写 sku_of(...)，让两种 id 在每一行上都分得清。
    return r["data"]


def sku_of(pid):
    """商品 id → 它的默认 SKU id。

    ★ 本脚本的测试商品都是无规格商品，所以各自只有一条默认 SKU。
    """
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid}"))


def create_address(token, receiver):
    st, r = call("POST", "/shop/addresses", {
        "receiver": receiver, "phone": "13800000000",
        "region": "浙江省杭州市西湖区", "detail": f"{receiver}的测试地址",
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"建测试地址失败：HTTP {st} / {r}")
    return r["data"]


def key_for(tag):
    """幂等键必须匹配后端 @Pattern("^[A-Za-z0-9_-]{8,64}$")。"""
    KEY_SEQ[0] += 1
    return f"r{RUN}{tag}{KEY_SEQ[0]:03d}"


# ----------------------------------------------------------------------
def items_of(order_no):
    """
    直查库拿这条订单的明细 id。

    ★ 这里【故意不】走接口 —— 接口返回的 items[].id 是本轮新加的字段，
      它自己也是被测对象（见第 12 节）。让脚本的内部管道依赖被测对象，
      一旦那个字段出问题，失败会以「后面 30 条用例全红」的形式出现，
      而不是以「契约那一条红」的形式出现。
      **测试的准备步骤应该尽量走一条不会和被测对象一起坏掉的路。**
    """
    rows = run_sql(
        f"SELECT i.id FROM order_item i JOIN orders o ON o.id = i.order_id "
        f"WHERE o.order_no = '{order_no}' ORDER BY i.id")
    return [int(r[0]) for r in rows]


def buy_now(token, sku_id, qty, addr, tag):
    """下一个待付款的订单，返回 (orderNo, [orderItemId, ...])。"""
    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": sku_id, "quantity": qty,
        "addressId": addr, "idempotencyKey": key_for(tag),
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"下单失败：HTTP {st} / {r}")
    no = r["data"]["orderNo"]
    return no, items_of(no)


def cart_order(token, sku_ids, addr, tag):
    """走购物车结算下一个订单（可以有多个明细）。"""
    for sku in sku_ids:
        st, r = call("POST", "/shop/cart/items",
                     {"skuId": sku, "quantity": 1}, token=token)
        if r.get("code") != 200:
            raise SystemExit(f"加购失败：HTTP {st} / {r}")
    st, r = call("POST", "/shop/orders", {
        "skuIds": sku_ids, "addressId": addr, "idempotencyKey": key_for(tag),
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"购物车结算失败：HTTP {st} / {r}")
    no = r["data"]["orderNo"]
    return no, items_of(no)


def advance(order_no, to_status, token=None):
    """
    把订单推进到指定状态。★ 全程走真实接口，不用 SQL 改 status ——
    这样测的「资格」才是真的从订单域推导出来的，而不是脚本摆出来的假状态。
    （订单流转本身由 test-pay.py 覆盖，这里只是搭台。）
    """
    token = TOKEN_A if token is None else token
    if to_status >= PAID:
        st, r = call("POST", f"/shop/orders/{order_no}/pay",
                     {"payMethod": "ALIPAY"}, token=token)
        if r.get("code") != 200:
            raise SystemExit(f"支付失败：HTTP {st} / {r}")
    if to_status >= SHIPPED:
        st, r = call("POST", f"/admin/orders/{order_no}/ship", None,
                     token=ADMIN_TOKEN)
        if r.get("code") != 200:
            raise SystemExit(f"发货失败：HTTP {st} / {r}")
    if to_status >= COMPLETED:
        st, r = call("POST", f"/shop/orders/{order_no}/complete", None,
                     token=token)
        if r.get("code") != 200:
            raise SystemExit(f"确认收货失败：HTTP {st} / {r}")


def order_at(pid, status, tag, token=None, addr=None):
    """造一条停在指定状态的订单，返回 (orderNo, orderItemId)。"""
    token = TOKEN_A if token is None else token
    addr = ADDR_A if addr is None else addr
    no, ids = buy_now(token, sku_of(pid), 1, addr, tag)
    advance(no, status, token=token)
    return no, ids[0]


def review(token, item_id, rating=5, content="测试评价内容", images=None):
    """提交评价。images=None 表示请求体里【没有】这个字段。"""
    body = {"orderItemId": item_id, "rating": rating, "content": content}
    if images is not None:
        body["images"] = images
    return call("POST", "/shop/reviews", body, token=token)


def list_reviews(pid, params=""):
    return call("GET", f"/shop/products/{pid}/reviews{params}", None)


def upload_image(token):
    st, r = post_file("/shop/images", "shot.png", PNG, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"会员侧上传失败：HTTP {st} / {r}")
    UPLOADED.append(r["data"])
    return r["data"]


def ok_or_die(st, r, what):
    if r.get("code") != 200:
        raise SystemExit(f"{what}失败：HTTP {st} / {r}")
    return r["data"]


# ----------------------------------------------------------------------
def cleanup():
    """
    ★ 顺序一层都不能反：晒图 → 评价 → 明细 → 订单 → 地址 → 商品 → 会员。

    product_review / product_review_image 【都没有外键】（全库约定），
    所以删反了不会有任何报错 —— 只会留下一批永远查不到的孤儿行。
    两种圈定方式（按会员、按商品）都覆盖，因为它们对应两条不同的来路。
    """
    run_sql("DELETE pri FROM product_review_image pri "
            "JOIN product_review r ON r.id = pri.review_id "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE r FROM product_review r "
            f"JOIN member m ON m.id = r.member_id WHERE m.username LIKE '{PREFIX}%'")
    run_sql("DELETE pri FROM product_review_image pri "
            "JOIN product_review r ON r.id = pri.review_id "
            f"WHERE r.product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")
    run_sql("DELETE FROM product_review WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")

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
    for mid in (ID_A, ID_B):
        if mid:
            redis_cmd("DEL", f"mall:cart:{mid}")


def cleanup_files():
    """只删本次脚本自己上传的文件，并断言数量对得上（照 test-upload.py）。"""
    before = count_files()
    removed = 0
    for url in UPLOADED:
        try:
            os.remove(url_to_disk(url))
            removed += 1
        except FileNotFoundError:
            pass
    after = count_files()

    print()
    print(f"  磁盘清理：上传 {len(UPLOADED)} 个，删除 {removed} 个，"
          f"目录文件数 {before} → {after}")
    check("★ 磁盘清理只删掉了本次上传的文件（数量对得上）",
          before - after == removed,
          f"删了 {removed} 个，但文件总数少了 {before - after} 个")


# ======================================================================
def main():
    global TOKEN_A, ID_A, TOKEN_B, ID_B, ADDR_A, ADDR_B

    if not os.path.isdir(UPLOAD_ROOT):
        raise SystemExit(
            f"找不到上传目录：{UPLOAD_ROOT}\n"
            f"它必须存在 —— 否则清理会静默地什么都没删。")

    cleanup()
    admin_login()
    TOKEN_A, ID_A = register("a")
    TOKEN_B, ID_B = register("b")
    ADDR_A = create_address(TOKEN_A, f"{PREFIX}收货人A")
    ADDR_B = create_address(TOKEN_B, f"{PREFIX}收货人B")

    print()
    print(f"本次运行 RUN = {RUN}")
    print(f"会员 A id={ID_A} / B id={ID_B}")

    p1 = create_product(f"{PREFIX}商品一")
    p2 = create_product(f"{PREFIX}商品二")
    p3 = create_product(f"{PREFIX}商品三")
    p_never = create_product(f"{PREFIX}无人问津")
    # 一批用来做「一张订单多条明细」的商品。名字里【不能】出现「商品一/二/三」，
    # 否则管理端的按商品名筛选会把它们一起捞进来（子串匹配）
    #
    # ★ 10 个的分法是本节用例数算出来的，不是随手写的：
    #     [0][1]      证明同一订单的两条明细能分别评价
    #     [2]..[7]    6 条校验用例，每条必须用一个【全新的】明细
    #     [8][9]      null 和 [] 各要一条（同一条上测两次会先撞「已评价」）
    p_batch = [create_product(f"{PREFIX}批量{n}") for n in range(1, 11)]

    # ==================================================================
    section("1. 端到端：确认收货 → 评价成功 → 全链路可见")
    # ==================================================================

    no1, item1 = order_at(p1, COMPLETED, "e2e")

    # ★ 先看评价【之前】的契约：未评价的明细没有 reviewId 这个键
    st, r = call("GET", f"/shop/orders/{no1}", None, token=TOKEN_A)
    it = (r.get("data") or {}).get("items", [{}])[0]
    check("★★ 未评价的订单明细【没有 reviewId 这个键】（non_null 让它整个消失）",
          "reviewId" not in it,
          f"实际字段：{sorted(it.keys())} —— 前端因此必须写 !it.reviewId，"
          f"不能写 it.reviewId === null")

    img1 = upload_image(TOKEN_A)
    img2 = upload_image(TOKEN_A)

    st, r = review(TOKEN_A, item1, rating=5, content="很好用，物流也快",
                   images=[img1, img2])
    check("★ 评价成功，业务码 200", r.get("code") == 200, f"HTTP {st} / {r}")
    rid1 = r.get("data")
    check("★ 返回的是一个裸的评价 id", isinstance(rid1, int) and rid1 > 0,
          f"拿到 {rid1!r}")

    rows = run_sql(f"SELECT order_item_id, product_id, member_id, rating "
                   f"FROM product_review WHERE id = {rid1}")
    check("★★ 库里落下的 product_id 是服务端查出来的，不是客户端传的",
          bool(rows) and int(rows[0][1]) == p1 and int(rows[0][2]) == ID_A,
          f"实际 {rows}（期待 product_id={p1}, member_id={ID_A}）")
    check("★ rating 落库正确", bool(rows) and int(rows[0][3]) == 5, f"{rows}")

    imgs = run_sql(f"SELECT url FROM product_review_image WHERE review_id = {rid1} "
                   f"ORDER BY id")
    check("★★ 两张晒图都落库了，且顺序 = 上传顺序",
          [x[0] for x in imgs] == [img1, img2],
          f"实际 {[x[0] for x in imgs]}（期待 {[img1, img2]}）")

    # ---- 评价之后：订单明细上有 reviewId 了 ----
    st, r = call("GET", f"/shop/orders/{no1}", None, token=TOKEN_A)
    it = (r.get("data") or {}).get("items", [{}])[0]
    check("★★ 评价之后该明细【有】reviewId，且等于评价 id",
          it.get("reviewId") == rid1,
          f"实际 reviewId={it.get('reviewId')!r}（期待 {rid1}）")

    # ---- 公众列表能看到了 ----
    st, r = list_reviews(p1)
    check("★ 商品评价列表能拉到", r.get("code") == 200, f"HTTP {st} / {r}")
    got = (r.get("data") or {}).get("list") or []
    check("★ 列表里有刚才那条", len(got) == 1 and got[0]["id"] == rid1,
          f"实际 {got}")
    if got:
        check("★ 列表项带成员昵称",
              got[0].get("memberNickname") == f"评价测试a{RUN}",
              f"实际 {got[0].get('memberNickname')!r}")
        check("★ 列表项带晒图，顺序和上传一致",
              got[0].get("images") == [img1, img2],
              f"实际 {got[0].get('images')}")

    # ==================================================================
    section("2. ★★ 资格：从订单域推导出来的那条规则")
    # ==================================================================

    maxid = int(run_sql("SELECT IFNULL(MAX(id), 0) FROM order_item")[0][0])
    st, r = review(TOKEN_A, maxid + 1000000)
    check("★ 不存在的 orderItemId → 1003「订单明细不存在」",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    # ---- ★★ 别人的明细：必须是 1003，不能是 1002 ----
    no_b, item_b = order_at(p1, COMPLETED, "b", token=TOKEN_B, addr=ADDR_B)
    st, r = review(TOKEN_A, item_b)
    check("★★ 用别人的订单明细评价 → 1003（不是 1002）",
          r.get("code") == 1003,
          f"HTTP {st} / {r} —— 若返回 1002，说明「这条明细存在」被泄漏了："
          f"攻击者能靠错误码的差别枚举出别人的订单明细 id")

    # ---- 三种「还没走完」的状态各一条 ----
    for status, label in ((PENDING_PAY, "待付款"), (PAID, "已付款"), (SHIPPED, "已发货")):
        _, item = order_at(p1, status, f"st{status}")
        st, r = review(TOKEN_A, item)
        check(f"★★ {label}的订单 → 拒，业务码 1002",
              r.get("code") == 1002,
              f"HTTP {st} / {r} —— 若成功，说明「确认收货后才能评价」这条规则没生效")

    # ---- 刚确认收货的那条能评（对照组） ----
    _, item_ok = order_at(p1, COMPLETED, "st3")
    st, r = review(TOKEN_A, item_ok, rating=4, content="确认收货后能评")
    check("★ 确认收货后 → 评价成功（1002 不是「一律拒绝」）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    # ==================================================================
    section("3. ★★★ 一次定终身：这个不变量由【数据库】保证")
    # ==================================================================

    st, r = review(TOKEN_A, item1, rating=1, content="想改一次")
    check("★★ 重复评价 → 400",
          r.get("code") == 400, f"HTTP {st} / {r}")
    check("★ 错误信息说的是「评价过了」，不是别的（用户看得懂）",
          "评价过" in (r.get("message") or ""),
          f"实际 message={r.get('message')!r}")

    # ---- ★★★ 核心：绕过 Service，直接往库里插第二条 ----
    #   ⚠️ 这条 SQL 本来就该失败，所以用 try_sql 而不是 run_sql。
    #   它验证的是【索引】而不是【Service 里那句 if】：
    #   把 ProductReviewServiceImpl 的查重删掉，这条仍然过；
    #   把 uk_order_item 删掉，这条立刻红。
    sql_dup = (
        f"INSERT INTO product_review (order_item_id, product_id, member_id, "
        f"rating, content) VALUES ({item1}, {p1}, {ID_A}, 1, '直插测试')")
    ok, err = try_sql(sql_dup)
    check("★★★ 直插第二条同 order_item_id 的评价 → MySQL 报 Duplicate entry",
          (not ok) and "Duplicate entry" in err,
          f"ok={ok}，stderr={err.strip()[:200]!r} —— "
          f"如果这里成功了，说明 uk_order_item 索引不在，"
          f"「一次定终身」就只剩 Service 里那句查重在撑，而它挡不住并发")

    check("★ 失败的直插没有留下任何痕迹（表里还是 2 条：e2e 和 st3）",
          int(run_sql(f"SELECT COUNT(*) FROM product_review "
                      f"WHERE order_item_id IN ({item1}, {item_ok})")[0][0]) == 2,
          f"实际 {run_sql('SELECT id, order_item_id FROM product_review')}")

    # ==================================================================
    section("4. ★★★ 并发：20 个线程同时评同一条明细")
    # ==================================================================

    _, item_race = order_at(p1, COMPLETED, "race")
    results = []
    lock = threading.Lock()

    def worker(idx):
        st, r = review(TOKEN_A, item_race, rating=5,
                       content=f"并发评价 {idx}")
        with lock:
            results.append((st, r.get("code"), r.get("message")))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    ok_count = sum(1 for _, code, _ in results if code == 200)
    dup_count = sum(1 for _, code, _ in results if code == 400)
    other = [(st, code, msg) for st, code, msg in results if code not in (200, 400)]

    print(f"  20 个并发请求结果：成功 {ok_count} 个，已评价 {dup_count} 个，"
          f"其他 {len(other)} 个")

    check("★★★ 恰好只有 1 个成功（同一条明细评两次就是两次，多一个都不行）",
          ok_count == 1, f"成功 {ok_count} 个 —— 大于 1 就是重复评价穿透了")
    check("★★★ 其余的 19 个都是 400，而不是 500",
          dup_count == 19 and not other,
          f"400 的有 {dup_count} 个，其他 {other} —— "
          f"若有 500，说明 DuplicateKeyException 没被 catch 住，"
          f"用户看到的是「系统繁忙」而不是「你已经评价过了」")
    check("★★ 库里只有 1 行（副作用只发生了一次）",
          int(run_sql(f"SELECT COUNT(*) FROM product_review "
                      f"WHERE order_item_id = {item_race}")[0][0]) == 1,
          f"实际 {run_sql(f'SELECT id FROM product_review WHERE order_item_id = {item_race}')}")

    # ==================================================================
    section("5. ★ 一张订单多条明细：分别评价 + 校验规则")
    # ==================================================================

    # ★★ 这张订单有 6 条明细，一次确认收货之后 6 条【各自】可以评价。
    #    这一条证明 uk_order_item 卡的是【明细】而不是【订单】——
    #    如果实现时把唯一索引建在 order_id 上（很自然的一种误写），
    #    这一节会立刻红。
    no_m, items_m = cart_order(TOKEN_A, [sku_of(x) for x in p_batch], ADDR_A, "multi")
    check("（准备）一张订单里 10 条明细", len(items_m) == 10, f"实际 {items_m}")
    advance(no_m, COMPLETED)

    st, r = review(TOKEN_A, items_m[0], rating=5, content="批量第一条")
    check("★★ 同一订单的第 1 条明细 → 成功", r.get("code") == 200, f"{st} / {r}")
    st, r = review(TOKEN_A, items_m[1], rating=5, content="批量第二条")
    check("★★ 同一订单的第 2 条明细 → 也成功（卡的是明细，不是订单）",
          r.get("code") == 200,
          f"{st} / {r} —— 若这里是 400，说明唯一索引建错了位置（卡在了订单上）")

    # ---- 校验规则。★ 每条都用一个【全新的】明细 ----
    #   ⚠️ 复用已评过的明细是不行的：那条路会先撞上「已经评价过」的 400，
    #      于是「rating=6 被拒」这个断言会因为完全无关的原因通过 ——
    #      这种用例比没有用例更糟，因为它给人一种测过了的错觉。
    cases = [
        ("rating = 0", dict(rating=0), "评分"),
        ("rating = 6", dict(rating=6), "评分"),
        ("content 为空串", dict(content=""), "评价内容"),
        ("content 501 字", dict(content="测" * 501), "500"),
        ("晒图 4 张（超过上限 3）", dict(images=["/uploads/a.png"] * 4), "3"),
        ("晒图不是本服务上传的地址", dict(images=["/images/phone-01.svg"]), "图片地址"),
    ]
    for i, (label, overrides, expect_kw) in enumerate(cases):
        item = items_m[2 + i]
        kwargs = {"rating": 5, "content": "正常内容"}
        kwargs.update(overrides)
        st, r = review(TOKEN_A, item, **kwargs)
        check(f"★ {label} → 400",
              r.get("code") == 400, f"HTTP {st} / {r}")
        check(f"  └ 错误信息点到了「{expect_kw}」",
              expect_kw in (r.get("message") or ""),
              f"实际 message={r.get('message')!r} —— "
              f"信息不明确的话，无法确认它是被【那条规则】拒的")

    # ---- ★ images 在这个 DTO 里 null 和 [] 是【同一个意思】 ----
    #   注意这里必须用最后一个全新明细，且两条都发在【同一条】明细上是不行的
    #   （第二条会撞「已评价」）。所以用两条明细分别测。
    st, r = review(TOKEN_A, items_m[8], rating=5, content="不传 images 字段")
    check("★ 请求体里没有 images 字段 → 成功", r.get("code") == 200, f"{st} / {r}")
    st, r = review(TOKEN_A, items_m[9], rating=5, content="images 空数组",
                   images=[])
    check("★ images: [] → 也成功（和 null 是同一个意思，不是「清空」）",
          r.get("code") == 200, f"{st} / {r}")
    rows = run_sql(f"SELECT COUNT(*) FROM product_review_image i "
                   f"JOIN product_review r ON r.id = i.review_id "
                   f"WHERE r.order_item_id IN ({items_m[8]}, {items_m[9]})")
    check("★ 上面两条都没有晒图行", int(rows[0][0]) == 0, f"实际 {rows[0][0]} 行")

    # ==================================================================
    section("6. ★ 星级聚合：造一组故意不均匀的分布")
    # ==================================================================

    # ★★ 为什么必须不均匀：三条全 5 星的话，count1..count5 整段写反、
    #    或者五个全映射到同一个字段，结果看起来都对。
    #    5 星×2 + 3 星×1 → avg = 13/3 = 4.3333，只有算对了才出得来。
    ratings = [5, 5, 3]
    for i, star in enumerate(ratings):
        _, item = order_at(p2, COMPLETED, f"agg{i}")
        st, r = review(TOKEN_A, item, rating=star, content=f"{star} 星的评价")
        if r.get("code") != 200:
            raise SystemExit(f"造聚合数据失败（{star} 星）：HTTP {st} / {r}")

    st, r = call("GET", f"/shop/products/{p2}", None)
    check("★ 商品详情能拿到 reviewSummary", r.get("code") == 200, f"HTTP {st} / {r}")
    s = (r.get("data") or {}).get("reviewSummary")
    check("★★ reviewSummary 这个字段【存在】（不是 null，也不是没有这个键）",
          isinstance(s, dict), f"实际 {s!r}")

    if isinstance(s, dict):
        check("★★ total = 3", s.get("total") == 3, f"实际 {s.get('total')!r}")
        check("★★ count5 = 2", s.get("count5") == 2, f"实际 {s.get('count5')!r}")
        check("★★ count3 = 1", s.get("count3") == 1, f"实际 {s.get('count3')!r}")
        check("★★ count2 = 0（没出现过的星级要是 0，不能少这个键）",
              s.get("count2") == 0,
              f"实际 {s.get('count2')!r} —— "
              f"如果是 None/缺失，说明 COALESCE 漏了，"
              f"前端读 count2 会拿到 undefined")
        check("★ count1 = 0", s.get("count1") == 0, f"实际 {s.get('count1')!r}")
        try:
            avg = float(s.get("avgRating"))
        except (TypeError, ValueError):
            avg = -1.0
        check(f"★★ avgRating = 13/3 ≈ 4.3333（不是 4.3，SQL 里没有 ROUND）",
              abs(avg - 4.3333) < 0.001,
              f"实际 {s.get('avgRating')!r} —— "
              f"若只有一位小数，说明有人在 SQL 里 ROUND 了，"
              f"那样前端再也算不了别的（四舍五入是展示层的事）")
        check("★ 五个分布字段的键名是 count5..count1（前端要循环取）",
              all(f"count{n}" in s for n in range(1, 6)),
              f"实际键：{sorted(s.keys())}")

    # ==================================================================
    section("7. ★ 零评价的形状：聚合查询永远返回一行")
    # ==================================================================

    st, r = call("GET", f"/shop/products/{p_never}", None)
    s = (r.get("data") or {}).get("reviewSummary")
    check("★★ 全新商品（0 评价）的 reviewSummary 依然存在",
          isinstance(s, dict),
          f"实际 {s!r} —— 不带 GROUP BY 的聚合查询永远返回一行，"
          f"所以 Service 里不需要判空，前端也不用写 ?. ")
    if isinstance(s, dict):
        check("★★ 零评价时 total = 0", s.get("total") == 0, f"实际 {s.get('total')!r}")
        check("★★ 零评价时 avgRating = 0（不是 null —— AVG 无行时是 NULL，靠 COALESCE 兜）",
              s.get("avgRating") in (0, 0.0) or float(s.get("avgRating")) == 0.0,
              f"实际 {s.get('avgRating')!r} —— "
              f"若是 None/缺失，前端显示的「NaN 分」就是这么来的")

    st, r = list_reviews(p_never)
    check("★ 零评价商品的评价列表是空页，不是 404",
          r.get("code") == 200 and (r.get("data") or {}).get("total") == 0,
          f"HTTP {st} / {r}")

    # ==================================================================
    section("8. ★ 列表 / 隐私 / 排序")
    # ==================================================================

    st, r = list_reviews(p2)
    page = r.get("data") or {}
    got = page.get("list") or []
    check("★ 三条评价都在", page.get("total") == 3 and len(got) == 3,
          f"total={page.get('total')!r} len={len(got)}")

    # ---- ★★ 隐私：匿名可读的接口里不能有 phone / username ----
    leaked = [k for item in got for k in ("phone", "username") if k in item]
    check("★★ 评价列表里没有 phone、没有 username（只有昵称）",
          not leaked,
          f"泄漏了 {sorted(set(leaked))} —— "
          f"这是 MemberInfoVO.phone 的 javadoc 在里程碑 10 就写下的预言，"
          f"评价区是「别人也能看到」的界面，手机号出现在这里就是合规事故")
    check("★ 但昵称是有的（昵称是可以公开的）",
          all(item.get("memberNickname") for item in got),
          f"实际 {[i.get('memberNickname') for i in got]}")

    # ---- ★ 排序：create_time DESC, id DESC ----
    #   ⚠️ create_time 是 DATETIME（秒精度），三条是同一秒插进去的 ——
    #      所以这个用例实际上在测【末尾那个唯一列 id】。
    #      少了它，同一秒内的三条顺序由 MySQL 自行决定，
    #      翻页时会出现「第 1 页和第 2 页重复同一条」。
    ids = [i["id"] for i in got]
    check("★★ 同秒插入的三条按 id 倒序（ORDER BY 以唯一列结尾才稳定）",
          ids == sorted(ids, reverse=True),
          f"实际顺序 {ids} —— 不稳定的排序会让翻页出现重复/丢行")

    st, r = list_reviews(p2, "?pageNum=2&pageSize=2")
    page2 = r.get("data") or {}
    check("★ 分页正确：第 2 页 1 条，total 仍然是 3",
          len(page2.get("list") or []) == 1 and page2.get("total") == 3,
          f"实际 {page2}")

    st, r = list_reviews(p2, "?pageSize=999999")
    check("★ 分页参数被钳住（pageSize 上限 100，不是把整表拉走）",
          r.get("code") == 200 and (r.get("data") or {}).get("pageSize") == 100,
          f"实际 pageSize={(r.get('data') or {}).get('pageSize')!r}")

    # ==================================================================
    section("9. 鉴权 + 会员侧上传接口")
    # ==================================================================

    st, r = review(None, item_ok, rating=5, content="没登录也想评")
    check("★★ 未登录评价 → HTTP 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=5", None,
                 token=TOKEN_A)
    check("★★ 会员 token 打管理端评价列表 → HTTP 401",
          st == 401, f"HTTP {st} / {r}")

    st, r = call("POST", "/shop/reviews",
                 {"orderItemId": item_ok, "rating": 5, "content": "管理员来评"},
                 token=ADMIN_TOKEN)
    check("★★ 管理员 token 打用户端评价接口 → HTTP 401",
          st == 401,
          f"HTTP {st} / {r} —— 这是「JWT 必须带 type claim」的回归")

    # ---- ★★ 这条用例是【被写出来才知道有问题】的那一类 ----
    #   第一版跑的时候它返回的是 HTTP 200 + code 500「系统繁忙，请稍后重试」——
    #   于是 GlobalExceptionHandler 补了一个 MultipartException 处理器。
    #   真实前端永远不会发这种请求（el-upload 一定会构造 FormData），
    #   只有测试会。这就是为什么值得为一个"走不到"的分支写断言。
    st, r = call("POST", "/shop/images", None, token=TOKEN_A)
    check("★★ 不带文件的 POST → 真 HTTP 400（不是「系统繁忙」的 500）",
          st == 400 and r.get("code") == 400,
          f"HTTP {st} / {r} —— "
          f"若这里是 200+500，说明 MultipartException 掉进了兜底处理器，"
          f"用户会看到「系统繁忙」并去重试一个注定失败的请求")

    st, r = call("POST", "/shop/images", {"a": 1}, token=TOKEN_A)
    check("★ 发 JSON 给上传接口 → 也是真 HTTP 400（同上）",
          st == 400, f"HTTP {st} / {r}")

    url_m = upload_image(TOKEN_A)
    check("★ 会员 token 能上传晒图，返回 /uploads/ 路径",
          url_m.startswith("/uploads/"), f"实际 {url_m!r}")

    st, r = post_file("/shop/images", "x.png", PNG, token=None)
    check("★★ 未登录上传 → HTTP 401（会员侧上传接口不是公开的）",
          st == 401, f"HTTP {st} / {r}")

    st, r = post_file("/shop/images", "fake.png",
                      b"not an image at all", token=TOKEN_A)
    check("★★ 会员侧上传【继承】了同一套魔数校验（纯文本改名 .png 被拒）",
          r.get("code") == 400,
          f"HTTP {st} / {r} —— 会员侧只有两行代码，"
          f"能拦住是因为规则在 FileStorageServiceImpl 里，没有被复制第二份")

    st, r = post_file("/shop/images", "evil.svg",
                      b'<svg xmlns="http://www.w3.org/2000/svg"/>', token=TOKEN_A)
    check("★ 会员侧同样拒 SVG", r.get("code") == 400, f"HTTP {st} / {r}")

    # ==================================================================
    section("10. 管理端：列表 / 筛选 / 删除")
    # ==================================================================

    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=50", None,
                 token=ADMIN_TOKEN)
    check("★ 管理端评价列表 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    page = r.get("data") or {}
    rows = page.get("list") or []
    check("★ 列表非空且有分页字段",
          rows and all(k in page for k in
                       ("list", "total", "pageNum", "pageSize", "pages")),
          f"实际 {page}")
    if rows:
        # ★ 管理端比用户端【多】的字段：商品名和会员登录名
        require = ["id", "rating", "content", "createTime", "images",
                   "productId", "productName", "memberUsername",
                   "memberNickname"]
        missing = [k for k in require if k not in rows[0]]
        check("★★ 管理端列表项有商品名和会员登录名（用户端没有的那些）",
              not missing,
              f"缺 {missing}；实际字段 {sorted(rows[0].keys())}")
        check("★ 管理端也不给 phone（管理员有 username 就够了）",
              "phone" not in rows[0],
              f"实际字段里有 phone")

    # ---- 按商品名筛 ----
    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=50"
                        "&productKeyword=" + urllib.parse.quote("商品二"),
                 None, token=ADMIN_TOKEN)
    rows = (r.get("data") or {}).get("list") or []
    check("★ 按商品名筛选 → 只出商品二的 3 条",
          len(rows) == 3 and all(x["productId"] == p2 for x in rows),
          f"实际 {[(x['productId'], x['productName']) for x in rows]}")

    # ---- 按会员筛 ----
    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=50"
                        "&memberKeyword=" + urllib.parse.quote("评价测试a"),
                 None, token=ADMIN_TOKEN)
    rows = (r.get("data") or {}).get("list") or []
    check("★ 按会员筛选（昵称模糊）→ 全是会员 A 的",
          rows and all(x["memberUsername"] == f"{PREFIX}a{RUN}" for x in rows),
          f"实际 {[(x['memberUsername'], x['memberNickname']) for x in rows]}")

    # ---- ★ 两个筛选同时用（各有 <if>，一起用才看得出两个条件是 AND 还是互相覆盖）----
    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=50"
                        "&productKeyword=" + urllib.parse.quote("商品二")
                        + "&memberKeyword=" + urllib.parse.quote("评价测试a"),
                 None, token=ADMIN_TOKEN)
    rows = (r.get("data") or {}).get("list") or []
    check("★★ 两个筛选同时用 → 交集（商品二 AND 会员A）",
          len(rows) == 3 and all(x["productId"] == p2 for x in rows),
          f"实际 {len(rows)} 条：{[(x['productId'], x['memberUsername']) for x in rows]}")

    # ---- 筛一个不存在的关键词 ----
    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=50"
                        "&productKeyword=" + urllib.parse.quote("绝对不存在的商品名"),
                 None, token=ADMIN_TOKEN)
    check("★ 筛不到时是空页，不是报错",
          r.get("code") == 200 and (r.get("data") or {}).get("total") == 0,
          f"实际 {r.get('data')}")

    # ---- ★★ 删除：评价和晒图一起消失 ----
    st, r = call("DELETE", f"/admin/reviews/{rid1}", None, token=ADMIN_TOKEN)
    check("★ 删除评价 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 评价行没了",
          int(run_sql(f"SELECT COUNT(*) FROM product_review "
                      f"WHERE id = {rid1}")[0][0]) == 0)
    check("★★ 它的晒图行也没了（不是留成孤儿）",
          int(run_sql(f"SELECT COUNT(*) FROM product_review_image "
                      f"WHERE review_id = {rid1}")[0][0]) == 0,
          "有残留说明 delete 只删了评价、没删晒图")

    st, r = call("DELETE", f"/admin/reviews/{rid1}", None, token=ADMIN_TOKEN)
    check("★ 再删一次 → 1003「评价不存在」（不是静默成功）",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = call("DELETE", "/admin/reviews/99999999", None, token=ADMIN_TOKEN)
    check("★ 删一条不存在的 → 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # ---- ★★ 物理删除会释放唯一索引的槽位 ----
    #   这是「删除」这个动作的一个【真实后果】，必须被断言锁住：
    #   删掉之后那条明细可以重新评价。语义上合理（被删掉的违规评价
    #   不该永久剥夺他重写的权利），但它是一件容易被忽略的事。
    st, r = review(TOKEN_A, item1, rating=3, content="被删掉之后重新写的")
    check("★★ 评价被删后，那条明细可以【重新评价】（唯一索引槽位被释放）",
          r.get("code") == 200,
          f"HTTP {st} / {r} —— 这是物理删除的真实后果，不是 bug；"
          f"但如果哪天有人把它改成逻辑删除，这条会红，"
          f"那正好提醒他重新想一遍这个语义")

    # ---- 订单明细上的 reviewId 也要跟着更新 ----
    st, r = call("GET", f"/shop/orders/{no1}", None, token=TOKEN_A)
    new_rid = (r.get("data") or {}).get("items", [{}])[0].get("reviewId")
    check("★ 订单明细上的 reviewId 跟着变成了新的那条",
          new_rid != rid1 and isinstance(new_rid, int),
          f"实际 {new_rid!r}（旧的 {rid1}）")

    # ==================================================================
    section("11. ★ 级联：删商品要把评价和晒图一起带走")
    # ==================================================================

    _, item_p3 = order_at(p3, COMPLETED, "cas")
    st, r = review(TOKEN_A, item_p3, rating=5, content="这条会被级联删掉",
                   images=[upload_image(TOKEN_A)])
    check("（准备）p3 上有 1 条带图的评价", r.get("code") == 200, f"{st} / {r}")

    before_r = int(run_sql(f"SELECT COUNT(*) FROM product_review "
                           f"WHERE product_id = {p3}")[0][0])
    before_i = int(run_sql(f"SELECT COUNT(*) FROM product_review_image i "
                           f"JOIN product_review r ON r.id = i.review_id "
                           f"WHERE r.product_id = {p3}")[0][0])
    check("（准备）p3 的评价 1 条、晒图 1 张",
          before_r == 1 and before_i == 1, f"{before_r} / {before_i}")

    st, r = call("DELETE", f"/admin/products/{p3}", None, token=ADMIN_TOKEN)
    check("★ 删商品成功", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★★ 该商品的评价行没了",
          int(run_sql(f"SELECT COUNT(*) FROM product_review "
                      f"WHERE product_id = {p3}")[0][0]) == 0)
    check("★★ 该商品评价的晒图行也没了（四级级联的最后一层）",
          int(run_sql(f"SELECT COUNT(*) FROM product_review_image i "
                      f"JOIN product_review r ON r.id = i.review_id "
                      f"WHERE r.product_id = {p3}")[0][0]) == 0)

    # ==================================================================
    section("12. 全库不变量（直接问数据库）")
    # ==================================================================

    orphans = run_sql("SELECT COUNT(*) FROM product_review r "
                      "LEFT JOIN product p ON p.id = r.product_id "
                      "WHERE p.id IS NULL")
    check("★★ 全库没有孤儿评价（指向不存在的商品）",
          int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行 —— 多半是删商品时忘了先删评价")

    orphans = run_sql("SELECT COUNT(*) FROM product_review_image i "
                      "LEFT JOIN product_review r ON r.id = i.review_id "
                      "WHERE r.id IS NULL")
    check("★★ 全库没有孤儿晒图（指向不存在的评价）",
          int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行 —— 多半是删评价时忘了先删晒图")

    # ★ 唯一的那个不变量，直接问数据库结构
    ddl = run_sql("SHOW CREATE TABLE product_review")
    ddl_text = ddl[0][1] if ddl else ""
    check("★★★ uk_order_item 索引还在（「一次定终身」的承重墙）",
          "uk_order_item" in ddl_text and "UNIQUE" in ddl_text.upper(),
          "建表语句里找不到 uk_order_item —— "
          "它一没，重复评价就只剩 Service 里那句查重在挡，而它挡不住并发")

    bad = run_sql("SELECT COUNT(*) FROM product_review WHERE rating NOT BETWEEN 1 AND 5")
    check("★ 库里没有越界的 rating（1~5）", int(bad[0][0]) == 0,
          f"有 {bad[0][0]} 行 —— DTO 的 @Min/@Max 被绕过了")

    # ==================================================================
    section("13. 清理")
    # ==================================================================

    cleanup()
    cleanup_redis()

    check("测试商品已清理",
          int(run_sql(f"SELECT COUNT(*) FROM product WHERE name LIKE "
                      f"'{PREFIX}%'")[0][0]) == 0)
    check("测试会员已清理",
          int(run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE "
                      f"'{PREFIX}%'")[0][0]) == 0)
    check("★ 测试评价已清理（按会员圈定的那一种）",
          int(run_sql("SELECT COUNT(*) FROM product_review r "
                      "JOIN member m ON m.id = r.member_id "
                      f"WHERE m.username LIKE '{PREFIX}%'")[0][0]) == 0)

    cleanup_files()

    print()
    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个，订单 {} 个".format(
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
        print("失败项：")
        for f in FAILED:
            print(f"  · {f}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
