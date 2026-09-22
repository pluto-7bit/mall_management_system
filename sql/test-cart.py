# -*- coding: utf-8 -*-
"""
里程碑 7 测试：购物车（Redis Hash）

这组用例的重点是四条规则有没有被守住：

  1. ★ 购物车必须登录才能用（它没有被排除出拦截器）
  2. ★ Redis 里【只存 id 和数量】，不存价格 —— 价格永远从 MySQL 现查
  3. ★★ 商品下架后，购物车里那条记录不能消失，要标记成失效
  4. ★ 加购是【增量】，改数量是【设值】—— 两个接口语义不能混

第 2 和第 3 条是这组测试的核心，都配了【对照组】：
  - 第 2 条：改完数据库价格后，购物车里的价格必须跟着变（证明没存快照）
  - 第 3 条：下架商品后，购物车里那条还在但 available=false（证明不是被删了）

运行：
    python test-cart.py
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

RUN = str(int(time.time()))[-8:]
PREFIX = "carttest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
MEMBER_TOKEN = None
MEMBER_ID = None


# ----------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------
def call(method, path, body=None, token=None):
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
# 数据库直连：只用来「准备/恢复数据」和「事后核对 Redis 里到底存了什么」
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


REDIS = "mall-redis"


def redis_cmd(*args):
    """在 Docker 里执行 redis-cli，返回原始输出行。

    ★ 为什么要能直接看 Redis？
      因为购物车是这个项目里唯一不落 MySQL 的数据。
      如果测试只能通过接口间接验证，那「Redis 里到底存了什么」
      就永远是个假设 —— 而第 2 条规则（不存价格）
      恰恰是关于【存储内容】的，只有直接看才能证明。

      这是这个测试脚本里最有价值的一处「绕过业务层」。
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
    global ADMIN_TOKEN
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")


def member_setup():
    """注册一个临时会员并登录，返回 (token, memberId)。

    用注册接口而不是预先存在的账号，是为了让脚本自包含 ——
    跑一百遍也不会互相干扰。
    """
    username = f"{PREFIX}{RUN}"
    st, r = call("POST", "/shop/auth/register", {
        "username": username,
        "password": "cart123456",
        "nickname": "购物车测试",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    data = r["data"]
    return data["token"], data["id"]


def cleanup():
    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")
    # Redis 里的购物车也要清 —— 会员 id 是自增的，
    # 下次注册会拿到一个新的 id，但历史 key 会一直堆在那里
    keys = redis_cmd("KEYS", "mall:cart:*")
    if keys:
        redis_cmd("DEL", *keys)


def create_product(name, category_id, price, stock, status=1):
    st, r = call("POST", "/admin/products", {
        "categoryId": category_id, "name": name, "price": price,
        "stock": stock, "status": status,
    }, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    return r["data"]


def set_product_status(pid, status):
    """直接改数据库。用 SQL 而不是管理端接口，是因为管理端更新接口
    要求传完整的商品信息（categoryId/name/price/stock 都不能为空），
    为了改一个 status 要先把整个商品查出来再传回去，很啰嗦。

    ★ 测试脚本里「绕过业务层直接改数据」是可以的 ——
      它模拟的正是「运营在后台下架了商品」这个外部事件，
      而这个事件从购物车的视角看，就是「数据库里的 status 变了」。
    """
    run_sql(f"UPDATE product SET status = {status} WHERE id = {pid}")


def set_product_price(pid, price):
    run_sql(f"UPDATE product SET price = {price} WHERE id = {pid}")


def set_product_stock(pid, stock):
    run_sql(f"UPDATE product SET stock = {stock} WHERE id = {pid}")


# ---- 购物车接口的便捷封装（都用会员 token）----------------------------------
def cart_add(product_id, quantity):
    return call("POST", "/shop/cart/items",
                {"productId": product_id, "quantity": quantity}, token=MEMBER_TOKEN)


def cart_update(product_id, quantity):
    return call("PUT", f"/shop/cart/items/{product_id}",
                {"quantity": quantity}, token=MEMBER_TOKEN)


def cart_remove(product_id):
    return call("DELETE", f"/shop/cart/items/{product_id}", token=MEMBER_TOKEN)


def cart_get():
    return call("GET", "/shop/cart", token=MEMBER_TOKEN)


def cart_clear():
    return call("DELETE", "/shop/cart", token=MEMBER_TOKEN)


def items_of(r):
    return ((r.get("data") or {}).get("items")) or []


def find_item(r, product_id):
    for i in items_of(r):
        if i["productId"] == product_id:
            return i
    return None


# ======================================================================
def main():
    global MEMBER_TOKEN, MEMBER_ID

    admin_login()
    cleanup()
    MEMBER_TOKEN, MEMBER_ID = member_setup()

    print()
    print(f"本次运行 TAG = {TAG}")
    print(f"测试会员 id = {MEMBER_ID}，Redis key = mall:cart:{MEMBER_ID}")

    section("0. 准备数据")

    st, r = call("GET", "/admin/categories/options", token=ADMIN_TOKEN)
    cats = r.get("data") or []
    CAT = cats[0]["id"]

    # 五个测试商品，各有各的用途：
    #   P1  上架 100.00 库存 10   主力商品，测增删改查
    #   P2  上架 200.00 库存 3    库存很少，测「数量超过库存」
    #   P3  上架 300.00 库存 50   测下架后变失效
    #   P4  上架 400.00 库存 50   测改动价格后购物车要跟着变
    #   P5  上架  50.00 库存 500  ★ 唯一一个库存【大于 99】的商品。
    #                             只有它能把「超过 99 上限」和「超过库存」
    #                             这两种超限区分开 —— 用 P2（库存 3）测 99 上限
    #                             是测不出来的，因为库存先拦住了，根本到不了 99
    p1 = create_product(f"{TAG} 商品一", CAT, "100.00", 10)
    p2 = create_product(f"{TAG} 商品二", CAT, "200.00", 3)
    p3 = create_product(f"{TAG} 商品三", CAT, "300.00", 50)
    p4 = create_product(f"{TAG} 商品四", CAT, "400.00", 50)
    p5 = create_product(f"{TAG} 商品五", CAT, "50.00", 500)
    print(f"  已创建：P1={p1}(100元/库存10)  P2={p2}(200元/库存3)  "
          f"P3={p3}(300元/库存50)  P4={p4}(400元/库存50)  P5={p5}(50元/库存500)")

    # 从干净状态开始
    redis_cmd("DEL", f"mall:cart:{MEMBER_ID}")

    # ==================================================================
    section("1. ★ 购物车必须登录（它没有被排除出拦截器）")

    st, r = call("GET", "/shop/cart")
    check("游客查购物车 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("POST", "/shop/cart/items", {"productId": p1, "quantity": 1})
    check("★ 游客加购 → 401（否则任何人都能改别人的购物车）",
          st == 401, f"HTTP {st} / {r}")

    st, r = call("PUT", f"/shop/cart/items/{p1}", {"quantity": 2})
    check("游客改数量 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("DELETE", f"/shop/cart/items/{p1}")
    check("游客删商品 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("DELETE", "/shop/cart")
    check("游客清空购物车 → 401", st == 401, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/cart/count")
    check("游客查角标数量 → 401", st == 401, f"HTTP {st} / {r}")

    # 对照：商品浏览【必须】还是匿名的 ——
    # 加购物车时改了 WebMvcConfig，别把里程碑 6 的开放配置改坏了
    st, r = call("GET", "/shop/products?pageSize=1")
    check("（对照）商品浏览仍然匿名可访问 —— 没被误伤",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")

    # ==================================================================
    section("2. 空购物车")

    st, r = cart_get()
    check("空购物车 → HTTP 200（不是 404，「空」是正常状态）",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    check("空购物车返回结构完整的空对象，不是 null",
          isinstance(r.get("data"), dict) and items_of(r) == [],
          f"{r.get('data')}")
    check("空购物车的 totalQuantity = 0 且 totalAmount = 0",
          (r["data"].get("totalQuantity") == 0
           and float(r["data"].get("totalAmount")) == 0), f"{r.get('data')}")

    st, r = call("GET", "/shop/cart/count", token=MEMBER_TOKEN)
    check("空购物车的角标数量 = 0", r.get("data") == 0, f"{r}")

    # ==================================================================
    section("3. 加入购物车（增量语义）")

    st, r = cart_add(p1, 2)
    check("加购成功 → HTTP 200 + code 200", st == 200 and r.get("code") == 200,
          f"HTTP {st} / {r}")

    st, r = cart_get()
    item = find_item(r, p1)
    check("购物车里出现了这个商品，数量 = 2",
          item is not None and item.get("quantity") == 2, f"{r.get('data')}")

    # ★ 增量语义的核心用例：再加 3 件，应该是 5 而不是 3
    cart_add(p1, 3)
    st, r = cart_get()
    item = find_item(r, p1)
    check("★ 再加 3 件 → 数量变成 5（增量），而不是 3（设值）",
          item.get("quantity") == 5, f"数量 = {item.get('quantity')}")

    st, r = cart_get()
    check("小计 = 单价 × 数量 = 100 × 5 = 500",
          float(find_item(r, p1)["subtotal"]) == 500.0, f"{find_item(r, p1)}")
    check("合计金额 = 500", float(r["data"]["totalAmount"]) == 500.0, f"{r.get('data')}")
    check("总件数 = 5", r["data"]["totalQuantity"] == 5, f"{r.get('data')}")

    # ---- 参数校验 ----
    st, r = cart_add(p1, 0)
    check("加购数量 0 → 业务码 400（数量至少为 1）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = cart_add(p1, -5)
    check("加购数量 -5 → 业务码 400（负数不能当减法用）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    # ★ DTO 上的 @Max(999) 是【防呆】，不是业务上限。
    #   1000 这种值走不到 Service，在参数绑定阶段就被拒了。
    #   （「累加后超过 99 上限」的行为在第 8 节测，用的是库存充足的商品）
    st, r = cart_add(p1, 1000)
    check("单次加购 1000 件 → 业务码 400（DTO 防呆，挡住乱填的荒谬值）",
          r.get("code") == 400, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("★ 被 DTO 拒掉的请求【完全没有副作用】，车里数量没变",
          find_item(r, p1).get("quantity") == 5, f"{find_item(r, p1)}")

    st, r = call("POST", "/shop/cart/items", {"quantity": 1}, token=MEMBER_TOKEN)
    check("不传 productId → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = call("POST", "/shop/cart/items", {"productId": p1}, token=MEMBER_TOKEN)
    check("不传 quantity → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    # ★ 参数白名单：多传的字段必须被忽略，不能生效
    st, r = call("POST", "/shop/cart/items",
                 {"productId": p1, "quantity": 1,
                  "price": "0.01", "memberId": 99999, "id": 1},
                 token=MEMBER_TOKEN)
    check("★ 多传 price / memberId / id → 被忽略（参数白名单）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("★ 价格没有被 body 里的 0.01 污染，仍是数据库里的 100",
          float(find_item(r, p1)["price"]) == 100.0, f"{find_item(r, p1)}")

    # ---- 加不存在的商品 ----
    st, r = cart_add(999999, 1)
    check("加购不存在的商品 → 业务码 1003",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = cart_add(0, 1)
    check("加购 id=0 → 业务码 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = cart_add(-1, 1)
    check("加购负数 id → 业务码 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = cart_add(p3, 1)
    check("（准备）把 P3 也加进购物车", r.get("code") == 200, f"{r}")

    # ==================================================================
    section("4. ★★ Redis 里到底存了什么（直接看，不靠猜）")

    raw = redis_cmd("HGETALL", f"mall:cart:{MEMBER_ID}")
    check("Redis 里存在 key mall:cart:<会员id>", len(raw) > 0, f"HGETALL 返回空")

    # HGETALL 返回的是 [field1, value1, field2, value2, ...] 的扁平数组
    pairs = dict(zip(raw[0::2], raw[1::2]))
    print(f"  Redis 里的原始内容：{pairs}")

    check("★ field 是商品 id（字符串形式）",
          str(p1) in pairs and str(p3) in pairs, f"{pairs}")
    check("★ value 是数量（字符串形式）",
          pairs.get(str(p1)) == "6" and pairs.get(str(p3)) == "1",
          f"{pairs}")

    # ★★ 这条是本节的核心：Redis 里【不能】有价格、名称、库存这些东西。
    #    只要有任何一个是「存下来的」，就会出现「运营改了价，
    #    购物车还显示旧价」的问题。
    joined = " ".join(f"{k}={v}" for k, v in pairs.items())
    leaked = [w for w in ("price", "name", "100.00", "300.00", "商品一", "商品三")
              if w in joined]
    check("★★ Redis 里【没有】价格、名称等商品信息 —— 只存 id 和数量",
          not leaked, f"发现了不该存的东西：{leaked}；实际内容：{pairs}")

    check("key 带了 mall: 业务前缀（共享 Redis 的基本礼貌）",
          all(k.startswith("mall:cart:") for k in redis_cmd("KEYS", "mall:cart:*")),
          f"{redis_cmd('KEYS', 'mall:cart:*')}")

    ttl = redis_cmd("TTL", f"mall:cart:{MEMBER_ID}")
    check("★ key 设置了过期时间（0 表示永不过期，是个隐患）",
          ttl and int(ttl[0]) > 0, f"TTL = {ttl}")
    check("过期时间在 30 天左右（滑动过期，每次写操作刷新）",
          ttl and 29 * 86400 < int(ttl[0]) <= 30 * 86400, f"TTL = {ttl[0]} 秒")

    # ==================================================================
    section("5. ★★ 价格必须实时从 MySQL 查（改价后购物车要跟着变）")

    st, r = cart_get()
    check("（准备）改价前购物车里 P4 的价格还没出现（P4 还没加）",
          find_item(r, p4) is None, f"{items_of(r)}")

    cart_add(p4, 2)
    st, r = cart_get()
    check("（准备）加购 P4 两件 → 小计 800",
          float(find_item(r, p4)["subtotal"]) == 800.0, f"{find_item(r, p4)}")

    # ★ 模拟运营在后台把价格从 400 调到 250
    set_product_price(p4, "250.00")

    st, r = cart_get()
    item4 = find_item(r, p4)
    check("★★ 数据库改价后，购物车里的单价立刻变成 250（证明没存价格快照）",
          float(item4["price"]) == 250.0,
          f"购物车显示的价格 = {item4.get('price')}，期望 250")
    check("★★ 小计也跟着重算 = 250 × 2 = 500",
          float(item4["subtotal"]) == 500.0, f"小计 = {item4.get('subtotal')}")

    # 改回去，别影响后面的合计断言
    set_product_price(p4, "400.00")
    st, r = cart_get()
    check("（恢复）价格改回 400 后购物车也跟着回到 400",
          float(find_item(r, p4)["price"]) == 400.0, f"{find_item(r, p4)}")

    # ==================================================================
    section("6. ★★ 商品下架后：那条记录不能消失，要标记为失效")

    # （准备）确认 P3 现在是不是可用的
    st, r = cart_get()
    check("（准备）P3 现在在购物车里且是可用状态",
          find_item(r, p3) is not None and find_item(r, p3).get("available") is True,
          f"{find_item(r, p3)}")

    before_total = float(r["data"]["totalAmount"])

    # ★ 模拟运营在后台下架 P3
    set_product_status(p3, 0)

    st, r = cart_get()
    item3 = find_item(r, p3)

    check("★★ 下架后这条记录【还在】购物车里（不是被静默删掉）",
          item3 is not None,
          f"P3 从购物车里消失了。用户会以为「我明明加过」")

    if item3:
        check("★★ 被标记为 available = false", item3.get("available") is False, f"{item3}")
        check("★★ 给了明确的失效原因（不能只告诉用户「不能买」）",
              bool(item3.get("unavailableReason")),
              f"unavailableReason = {item3.get('unavailableReason')}")
        print(f"  失效原因：「{item3.get('unavailableReason')}」")

    check("★ 失效商品【不计入】合计金额",
          float(r["data"]["totalAmount"]) == before_total - 300.0,
          f"改前合计 = {before_total}，改后 = {r['data']['totalAmount']}，"
          f"期望 {before_total - 300.0}")

    # 把算式写出来，别再靠心算：
    #   P1 有 6 件（第 3 节 2 + 3 + 白名单那次的 1）
    #   P3 有 1 件，【已下架，不计】
    #   P4 有 2 件
    # 所以合计件数 = 6 + 2 = 8
    check("★ 失效商品也不计入总件数（P1 的 6 + P4 的 2 = 8，不含 P3 的 1）",
          r["data"]["totalQuantity"] == 8, f"总件数 = {r['data']['totalQuantity']}")

    check("★ 失效商品沉到列表底部（能买的排前面）",
          items_of(r)[-1]["productId"] == p3,
          f"顺序：{[i['productId'] for i in items_of(r)]}")

    # 下架的商品不能【新】加进购物车
    st, r = cart_add(p3, 1)
    check("★ 已被下架的商品无法新加入购物车 → 1003",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    # 下架后也不能改数量
    st, r = cart_update(p3, 5)
    check("下架商品无法修改数量 → 非 200", r.get("code") != 200, f"HTTP {st} / {r}")

    # ---- 库存不足的失效 ----
    # P1 在购物车里有 6 件，把库存降到 2
    set_product_stock(p1, 2)
    st, r = cart_get()
    item1 = find_item(r, p1)
    check("★ 库存不足时标记为失效，并说明只剩几件",
          item1.get("available") is False
          and "2" in (item1.get("unavailableReason") or ""),
          f"{item1}")
    print(f"  失效原因：「{item1.get('unavailableReason')}」")

    # 库存降到 0 = 售罄
    set_product_stock(p1, 0)
    st, r = cart_get()
    check("库存为 0 时原因是「已售罄」",
          find_item(r, p1).get("unavailableReason") == "已售罄", f"{find_item(r, p1)}")

    # 恢复
    set_product_stock(p1, 10)
    set_product_status(p3, 1)

    # ==================================================================
    section("7. 修改数量（设值语义）")

    st, r = cart_update(p1, 3)
    check("改数量成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("★ 从 6 改成 3 → 是 3（设值），而不是 9（增量）",
          find_item(r, p1).get("quantity") == 3,
          f"数量 = {find_item(r, p1).get('quantity')}")

    # 幂等：同样的请求发两次结果一样
    cart_update(p1, 3)
    st, r = cart_get()
    check("★ 同样的 PUT 发两次，结果还是 3（幂等）",
          find_item(r, p1).get("quantity") == 3, f"{find_item(r, p1)}")

    st, r = cart_update(p1, 0)
    check("改成 0 → 业务码 400（减到 0 应该用 DELETE）",
          r.get("code") == 400, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("★ 被拒绝的 0 没有写进去，数量还是 3",
          find_item(r, p1).get("quantity") == 3, f"{find_item(r, p1)}")

    st, r = cart_update(p1, -1)
    check("改成 -1 → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    # ★ 100 现在走的是 Service 的业务上限判定（返回 1008），
    #   而不是 DTO 的 @Max（那会返回 400）。
    #   这里是 P1，库存 10，所以 limit = min(99, 10) = 10，先被库存拦住
    st, r = cart_update(p1, 100)
    check("改成 100（超过库存）→ 业务码 1008",
          r.get("code") == 1008, f"HTTP {st} / {r}")

    st, r = cart_update(p1, 1000)
    check("改成 1000 → 业务码 400（DTO 防呆，走不到 Service）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    # P2 库存只有 3，改成 5 应该被拒绝
    cart_add(p2, 1)
    st, r = cart_update(p2, 5)
    check("★ 改成超过库存的数量 → 业务码 1008",
          r.get("code") == 1008, f"HTTP {st} / {r}")
    print(f"  提示信息：「{r.get('message')}」")

    st, r = cart_get()
    check("★ 被拒绝的 5 没有写进去，P2 数量还是 1",
          find_item(r, p2).get("quantity") == 1, f"{find_item(r, p2)}")

    # 改一个不在车里的商品
    st, r = cart_update(999999, 2)
    check("★ 改一个不在购物车里的商品 → 1003（PUT 不能当 POST 用）",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    # 参数白名单：body 里多写 productId 也不该生效
    st, r = call("PUT", f"/shop/cart/items/{p1}", {"quantity": 2, "productId": 999999},
                 token=MEMBER_TOKEN)
    check("★ 改数量时 body 里的 productId 被忽略（URL 才是准的）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("★ 没有凭空多出一个 id=999999 的条目",
          find_item(r, 999999) is None, f"{[i['productId'] for i in items_of(r)]}")

    # ==================================================================
    section("8. ★ 两种「超限」：超过库存 vs 超过 99 上限")

    # 两者都返回 1008，但触发的上限不同。
    # 要分清它们，必须用【库存大于 99】的商品来测 99 那条线 ——
    # 否则库存先拦住了，永远测不到 99。P5 的库存 500 就是为这个准备的。

    # ---- 8.1 超过【库存】----
    # P2 库存 3，车里已有 1
    st, r = cart_add(p2, 5)
    check("★ 加购后超过库存 → 业务码 1008（不是静默成功）",
          r.get("code") == 1008, f"HTTP {st} / {r}")
    msg_stock = r.get("message") or ""
    print(f"  超库存的提示：「{msg_stock}」")

    st, r = cart_get()
    check("★ 数量被压到库存上限 3（而不是原样加上去变成 6）",
          find_item(r, p2).get("quantity") == 3,
          f"数量 = {find_item(r, p2).get('quantity')}")

    # ---- 8.2 超过【99 上限】（P5 库存 500，碰不到库存那条线）----
    st, r = cart_add(p5, 95)
    check("（准备）P5 加购 95 件 → 成功（95 ≤ 99 且 95 ≤ 500）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = cart_add(p5, 10)
    check("★ 累加到 105 件 → 业务码 1008（撞的是 99 上限，不是库存）",
          r.get("code") == 1008, f"HTTP {st} / {r}")
    msg_limit = r.get("message") or ""
    print(f"  超 99 上限的提示：「{msg_limit}」")

    st, r = cart_get()
    check("★ 数量被压到 99（不是 105，也不是 500）",
          find_item(r, p5).get("quantity") == 99,
          f"数量 = {find_item(r, p5).get('quantity')}")

    # ★ 两种超限共用 1008 这一个业务码 —— 这是对的，因为前端的动作完全一样：
    #   把输入框重置成服务端说的那个数。真正要区分的是【提示语】，
    #   用户得看出来自己是被库存卡住了还是被平台规则卡住了
    check("★ 两种超限的提示语都告诉用户「最多能买几件」",
          "最多只能买" in msg_stock and "最多只能买" in msg_limit,
          f"超库存：「{msg_stock}」 / 超99：「{msg_limit}」")
    check("★ 但提示里的数字不同：库存那条说 3，99 上限那条说 99",
          "3" in msg_stock and "99" in msg_limit
          and msg_stock != msg_limit,
          f"超库存：「{msg_stock}」 / 超99：「{msg_limit}」")

    # 边界：正好 99 是允许的
    st, r = cart_update(p5, 99)
    check("★ 正好 99 件 → 允许（边界值本身是合法的）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = cart_update(p5, 100)
    check("★ 100 件 → 1008（只比边界多 1 就不行）",
          r.get("code") == 1008, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("★ 被拒绝后数量仍是 99（没有写进去）",
          find_item(r, p5).get("quantity") == 99, f"{find_item(r, p5)}")

    cart_remove(p5)

    # ==================================================================
    section("9. 移除 / 清空（幂等语义）")

    st, r = cart_remove(p2)
    check("移除购物车里的商品成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("移除后购物车里没有它了", find_item(r, p2) is None,
          f"{[i['productId'] for i in items_of(r)]}")

    # ★ 幂等：再删一次也应该成功
    st, r = cart_remove(p2)
    check("★ 重复移除同一个商品 → 仍然 200（幂等，不报错）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    # 移除一个从没加过的商品
    st, r = cart_remove(999999)
    check("★ 移除一个从没加过的商品 → 200（目标状态已达成就不算失败）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    # 会员之间隔离
    other_token, other_id = None, None
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}b{RUN}", "password": "cart123456",
    })
    if r.get("code") == 200:
        other_token, other_id = r["data"]["token"], r["data"]["id"]
        st, r2 = call("GET", "/shop/cart", token=other_token)
        check("★★ 新会员看到的是【空】购物车（不同会员的数据互相隔离）",
              items_of(r2) == [] and r2["data"]["totalQuantity"] == 0,
              f"新会员的购物车：{r2.get('data')}")
        check("★ 另一个会员的 Redis key 是独立的",
              f"mall:cart:{other_id}" != f"mall:cart:{MEMBER_ID}"
              and other_id != MEMBER_ID,
              f"memberId {MEMBER_ID} vs {other_id}")
    else:
        check("（准备）第二个测试会员注册成功", False, f"{r}")

    # 清空
    st, r = cart_clear()
    check("清空购物车成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = cart_get()
    check("清空后购物车是空的", items_of(r) == [], f"{items_of(r)}")

    raw = redis_cmd("HGETALL", f"mall:cart:{MEMBER_ID}")
    check("★ 清空后 Redis 里那个 key 也被删掉了（不是留一个空 Hash）",
          raw == [], f"HGETALL = {raw}")

    st, r = call("GET", "/shop/cart/count", token=MEMBER_TOKEN)
    check("清空后角标数量 = 0", r.get("data") == 0, f"{r}")

    st, r = cart_clear()
    check("重复清空 → 仍然 200（幂等）", r.get("code") == 200, f"HTTP {st} / {r}")

    # ==================================================================
    section("10. 角标数量用「件数」而不是「条目数」")

    cart_add(p1, 2)
    cart_add(p4, 3)
    st, r = call("GET", "/shop/cart/count", token=MEMBER_TOKEN)
    check("★ 2 个条目、共 5 件 → 角标显示 5（不是 2）",
          r.get("data") == 5, f"角标 = {r.get('data')}")

    st, r = cart_get()
    check("（对照）items 的长度是 2，证明上面那个 5 不是条目数",
          len(items_of(r)) == 2, f"{len(items_of(r))}")

    # ==================================================================
    section("11. 盘点：整个流程走一遍，金额算得对不")

    cart_clear()
    cart_add(p1, 2)   # 100 × 2 = 200
    cart_add(p2, 3)   # 200 × 3 = 600
    cart_add(p4, 1)   # 400 × 1 = 400
    # 合计 1200，件数 6

    st, r = cart_get()
    check("合计金额 = 100×2 + 200×3 + 400×1 = 1200",
          float(r["data"]["totalAmount"]) == 1200.0, f"{r['data']['totalAmount']}")
    check("总件数 = 2 + 3 + 1 = 6", r["data"]["totalQuantity"] == 6,
          f"{r['data']['totalQuantity']}")

    # ★ 金额不用浮点数运算的验证：把所有商品调成 0.10 元
    #   double 累加 0.1 三次会得到 0.30000000000000004
    for pid in (p1, p2, p4):
        set_product_price(pid, "0.10")
    cart_clear()
    cart_add(p1, 1)
    cart_add(p2, 1)
    cart_add(p4, 1)
    st, r = cart_get()
    total_str = str(r["data"]["totalAmount"])
    check("★★ 三个 0.10 元相加 = 0.30，不是 0.30000000000000004（BigDecimal）",
          total_str in ("0.30", "0.3"), f"合计 = {total_str}")

    for pid, price in ((p1, "100.00"), (p2, "200.00"), (p4, "400.00")):
        set_product_price(pid, price)

    # ==================================================================
    section("12. 清理")

    cart_clear()
    for pid in (p1, p2, p3, p4, p5):
        st, r = call("DELETE", f"/admin/products/{pid}", token=ADMIN_TOKEN)
        check(f"删除测试商品 {pid}", r.get("code") == 200, f"HTTP {st} / {r}")

    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")
    rows = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
    check("测试会员已删除", int(rows[0][0]) == 0, f"{rows}")

    keys = redis_cmd("KEYS", "mall:cart:*")
    check("★ Redis 里没有残留的测试购物车 key",
          all(str(MEMBER_ID) not in k for k in keys) or not keys,
          f"残留的 key：{keys}")

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
