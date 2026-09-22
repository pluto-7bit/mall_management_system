# -*- coding: utf-8 -*-
"""
收银台的「浏览器验收」夹具。

<h3>★ 这个脚本是干什么的？</h3>

<p>它不是测试 —— 测试在 {@code sql/} 下。它是一台<b>造数据的机器</b>：
用真实的 HTTP 接口建好一个会员、一件商品、一笔待付款订单、一笔已付款订单、
一笔已取消订单，以及一笔<b>属于别人的</b>订单，
然后把「token 和订单号」打印成可以直接粘进 {@code tools/shot.py} 的用法。

<p>为什么需要它？因为 {@code /pay/:orderNo} 这个页面<b>没法凭空打开</b>：
<ul>
  <li>它要登录态，而 {@code shot.py} 每次都用全新的 Chrome 配置目录
      （所以没有 localStorage）—— 只能靠 {@code --setup} 现写一个 token 进去；</li>
  <li>它要一个<b>真实存在的订单号</b>，而这个订单号必须是「刚建的、还没过期的」，
      手输不了、隔天就失效；</li>
  <li>它要能看到四种状态，而这四种状态<b>互斥</b>：
      一笔订单只可能处在一种状态里，所以必须建好几笔。</li>
</ul>

<h3>用法</h3>

<pre>
  # 造数据（会打印出可以直接复制的命令）
  /d/python/python.exe tools/fixture-pay.py

  # 拿着打印出来的 token 和订单号去截图
  /d/python/python.exe tools/shot.py http://localhost:5174/pay/&lt;订单号&gt; /tmp/pay.png \
      --setup "localStorage.setItem('mall_member_token', '&lt;token&gt;')" --wait 3

  # 验完了一定要清掉（它建的东西会一直留在你的库里）
  /d/python/python.exe tools/fixture-pay.py --cleanup
</pre>

<h3>⚠️ 为什么 cleanup 要单独跑一次？</h3>

<p>因为「造数据」和「验收」是两个进程、两个时间点。把清理写在造数据的末尾
就等于验收之前就清掉了。所以它把建出来的 id 记在一个小文件里
（{@code tools/.fixture-pay.json}），{@code --cleanup} 时读回来照着删。
<b>宁可多一个文件，也不要让「忘了清」变成一次静默的数据污染。</b>

<p>⚠️ 而且它<b>只删自己记下的 id</b>，不做「按名字前缀通配删除」。
前缀是 {@code fixturepay} + 时间戳，理论上不会撞上你的数据 ——
但"理论上"不该是删数据的依据。真实项目里清理脚本删错东西的事故，
几乎都是因为用了通配。

<p>它也只碰 {@code mall} 这一个库，和其余脚本一致。
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

HERE = os.path.dirname(os.path.abspath(__file__))
STATE_FILE = os.path.join(HERE, ".fixture-pay.json")

PREFIX = "fixturepay"
RUN = str(int(time.time()))[-6:]

sys.stdout.reconfigure(encoding="utf-8")


# ----------------------------------------------------------------------
def call(method, path, body=None, token=None, timeout=30):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=data, headers=headers,
                                 method=method)
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


def ok(st, r, what):
    """只关心「成功了没有」，不关心返回体。

    <p>★ 有些接口（比如加购物车）返回的是 {@code Result.success(null)}，
    而项目配了 {@code non_null}，所以 JSON 里<b>根本没有 data 这个键</b>。
    对它们用 must() 会得到一个 KeyError: 'data' ——
    <b>把"接口没返回东西"报成了"出错了"，方向完全反了。</b>
    所以：「有没有东西要拿」和「成不成功」是两件事，分两个函数。
    """
    if not isinstance(r, dict) or r.get("code") != 200:
        raise SystemExit(f"✗ {what}失败：HTTP {st} / {r}")


def must(st, r, what):
    """既要成功、也要有返回体。"""
    ok(st, r, what)
    return r["data"]


def run_sql(sql):
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{sql}\n{result.stderr}")
    return [line.split("\t") for line in result.stdout.splitlines() if line]


# ----------------------------------------------------------------------
def build():
    if os.path.exists(STATE_FILE):
        print(f"⚠️ {STATE_FILE} 已经存在，说明上一次的夹具还没清。")
        print("   先跑一次 `--cleanup`，或者手工确认后删掉那个文件。")
        sys.exit(1)

    # 管理端登录 —— 建商品要用
    st, r = call("POST", "/admin/auth/login",
                 {"username": "admin", "password": "123456"})
    admin = must(st, r, "管理员登录")["token"]
    category_id = int(run_sql("SELECT id FROM category ORDER BY id LIMIT 1")[0][0])

    state = {"run": RUN, "members": [], "products": [], "addresses": [],
             "orders": []}

    def save():
        """★ 每建一样东西就落一次盘，不是等全部建完再写。

        <p>这不是"小心过度"。第一版就是结尾才写文件 —— 结果中途
        一个字段取错崩了，<b>已经建出来的会员就再也删不掉了</b>
        （清理脚本读不到 id）。数据污染还只是小事，
        更麻烦的是它<b>静默</b>：你以为脚本失败等于什么都没发生。

        <p>正确的状态是「失败时留下的是可清理的脏数据」，
        不是「失败时留下的是无法追踪的脏数据」。
        """
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)

    def new_member(tag):
        st, r = call("POST", "/shop/auth/register", {
            "username": f"{PREFIX}{tag}{RUN}",
            "password": "fixture123",
            "nickname": f"夹具{tag}",
        })
        d = must(st, r, f"注册会员 {tag}")
        state["members"].append(d["id"])
        save()
        return d["token"], d["id"]

    def new_product(name, price, stock):
        st, r = call("POST", "/admin/products", {
            "categoryId": category_id, "name": name, "price": price,
            "stock": stock, "status": 1,
        }, token=admin)
        # ⚠️ 建商品也返回【裸 id】，不是对象
        pid = must(st, r, f"建商品 {name}")
        state["products"].append(pid)
        save()
        return pid

    def new_address(token, who):
        st, r = call("POST", "/shop/addresses", {
            "receiver": who, "phone": "13800000000",
            "region": "广东省 深圳市 南山区", "detail": "夹具路 1 号",
        }, token=token)
        # ⚠️ 地址返回的是【裸 id】，不是对象。
        #
        #   ★ 这三行值得单独标一下，因为同一个坑我在这个文件里踩了【两次】：
        #     POST /admin/products → 裸 id
        #     POST /shop/addresses → 裸 id
        #     POST /shop/orders    → 【对象】（因为要拿 orderNo 和 totalAmount）
        #   取错形状不会得到一句"形状不对"，只会让 d["id"] 抛一个
        #   TypeError: 'int' object is not subscriptable —— 报错信息
        #   和真正的原因（我猜错了返回什么）隔了一层。
        #
        #   教训：**同一组接口之间形状不一致时，别凭手感写，去看一眼。**
        #   而更该记住的是：它们不一致本身并不算错 ——
        #   建订单返回对象是合理的（前端要用订单号），
        #   建商品返回 id 也合理（除了 id 没别的可说）。
        #   一致性不是目的，「客户端需要什么」才是。
        aid = must(st, r, "建地址")
        state["addresses"].append(aid)
        save()
        return aid

    def new_order(token, pid, qty, address_id, tag):
        st, r = call("POST", "/shop/orders/buy-now", {
            "productId": pid, "quantity": qty, "addressId": address_id,
            "idempotencyKey": f"fx{RUN}{tag}",
        }, token=token)
        d = must(st, r, f"建订单 {tag}")
        state["orders"].append(d["id"])
        save()
        return d["orderNo"]

    token_a, _ = new_member("a")
    token_b, _ = new_member("b")
    addr_a = new_address(token_a, "夹具甲")
    addr_b = new_address(token_b, "夹具乙")

    # ★ 商品给足库存：这一轮只会扣掉几件，但万一你连着截几次图，
    #   库存不够会让建单失败，报错还不好懂（"库存不足"而不是"你没清上一次"）
    pid_main = new_product(f"{PREFIX}收银台主商品{RUN}", "199.00", 500)
    pid_small = new_product(f"{PREFIX}收银台小商品{RUN}", "9.90", 500)

    # ---- 1. 待付款：收银台的主场景（也要有明细可看，所以买了 2 件不同的商品）
    #     —— 用购物车结算才能一单多件，正好让明细列表不是只有一行
    for p, q in ((pid_main, 1), (pid_small, 3)):
        st, r = call("POST", "/shop/cart/items", {"productId": p, "quantity": q},
                     token=token_a)
        ok(st, r, "加购物车")
    st, r = call("POST", "/shop/orders", {
        "productIds": [pid_main, pid_small], "addressId": addr_a,
        "idempotencyKey": f"fx{RUN}cart", "remark": "",
    }, token=token_a)
    d = must(st, r, "购物车结算")
    state["orders"].append(d["id"])
    save()
    order_pending = d["orderNo"]

    # ---- 2. 已付款
    order_paid = new_order(token_a, pid_main, 1, addr_a, "paid")
    must(*call("POST", f"/shop/orders/{order_paid}/pay", {"payMethod": "WECHAT"},
               token=token_a), "支付")

    # ---- 3. 已取消
    order_cancelled = new_order(token_a, pid_small, 2, addr_a, "cancel")
    must(*call("POST", f"/shop/orders/{order_cancelled}/cancel", None,
               token=token_a), "取消")

    # ---- 4. 别人的订单（用 B 的 token 打开 A 的订单号，应该看到空状态）
    order_other = new_order(token_b, pid_main, 1, addr_b, "other")

    state["token_a"] = token_a
    state["token_b"] = token_b
    state["orders_by_name"] = {
        "pending": order_pending,
        "paid": order_paid,
        "cancelled": order_cancelled,
        "other": order_other,
    }
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)

    # ------------------------------------------------------------------
    print()
    print("=" * 72)
    print("  夹具已就绪。下面这些可以直接复制。")
    print("=" * 72)
    print()
    print(f"  会员 A 的 token（要写进 localStorage）：")
    print(f"    {token_a}")
    print()
    print(f"  会员 A 的收货人：夹具甲    会员 B：夹具乙")
    print()
    print(f"  ① 待付款（收银台主场景：倒计时 + 选支付方式 + 两个按钮）")
    print(f"       {order_pending}")
    print(f"  ② 已付款（应该【没有】取消按钮）")
    print(f"       {order_paid}")
    print(f"  ③ 已取消（显示取消时间）")
    print(f"       {order_cancelled}")
    print(f"  ④ 别人的订单（用 A 的 token 打开，应该看到空状态）")
    print(f"       {order_other}")
    print()
    print("  截图命令（把 ①②③④ 里的订单号填进去）：")
    print()
    print(f"    /d/python/python.exe tools/shot.py \\")
    print(f"        http://localhost:5174/pay/<订单号> /tmp/pay.png \\")
    print(f"        --setup \"localStorage.setItem('mall_member_token', '{token_a}')\" \\")
    print(f"        --wait 3")
    print()
    print("  ⚠️ 验完记得清掉（这次建了 2 个会员 / 2 件商品 / 3 个地址 / 4 笔订单）：")
    print()
    print(f"    /d/python/python.exe tools/fixture-pay.py --cleanup")
    print()


# ----------------------------------------------------------------------
def cleanup():
    if not os.path.exists(STATE_FILE):
        print("没有找到夹具状态文件，没什么要清的。")
        return

    with open(STATE_FILE, encoding="utf-8") as f:
        state = json.load(f)

    def ids(key):
        return ",".join(str(i) for i in state.get(key) or []) or "0"

    # ★★ 订单按【会员】删，不只是按记下来的那几个 id 删。
    #
    #   为什么？因为「造数据」和「清理」之间，你会在浏览器里点来点去 ——
    #   每点一次「提交订单」就多出一笔 fixture 的订单，而它不在记录里。
    #   只按记录删的话，这些订单会静默地留下来，
    #   连带他们的 order_item 变成孤儿明细，下一次跑测试时
    #   "你的数据：订单 N 笔" 就悄悄涨了。
    #
    #   ⚠️ 这仍然不是通配删除：会员 id 是夹具自己刚建出来的，
    #   "这些人的订单" 是一个精确的集合。**用关系圈定范围，
    #   而不是用名字前缀去猜** —— 前者删不掉别人的东西，
    #   后者只在你对命名规则的记忆正确时才安全。
    member_ids = ids("members")

    # ★ 顺序有讲究：先删明细，再删订单，最后删会员和商品。
    #   order_item 没有外键（里程碑 8 的决定），所以数据库【不会】替我们拦
    #   "删了订单留下一堆孤儿明细" —— 必须自己按正确的顺序删。
    run_sql(
        f"DELETE i FROM order_item i JOIN orders o ON o.id = i.order_id "
        f"WHERE o.id IN ({ids('orders')}) OR o.member_id IN ({member_ids})")
    run_sql(f"DELETE FROM orders WHERE id IN ({ids('orders')}) "
            f"OR member_id IN ({member_ids})")
    run_sql(f"DELETE FROM member_address WHERE id IN ({ids('addresses')})")
    # 商品即便还有订单明细指着它也无所谓 —— order_item 故意没有外键
    run_sql(f"DELETE FROM product WHERE id IN ({ids('products')})")
    run_sql(f"DELETE FROM member WHERE id IN ({ids('members')})")

    # 购物车在 Redis 里，不随数据库一起走。key 是 mall:cart:<会员id>
    for mid in state.get("members") or []:
        subprocess.run(["docker", "exec", "mall-redis", "redis-cli", "DEL",
                        f"mall:cart:{mid}"], capture_output=True)

    os.remove(STATE_FILE)

    # ---- 核对自己删干净了 ----
    left = 0
    for mid in state.get("members") or []:
        left += int(run_sql(
            f"SELECT COUNT(*) FROM orders WHERE member_id = {mid}")[0][0])
    print(f"清掉了 {len(state.get('orders') or [])} 笔订单"
          f"（剩余 {left} 笔）、{len(state.get('members') or [])} 个会员、"
          f"{len(state.get('products') or [])} 件商品。")
    print()
    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个，订单 {} 笔".format(
        run_sql("SELECT COUNT(*) FROM product")[0][0],
        run_sql("SELECT COUNT(*) FROM category")[0][0],
        run_sql("SELECT COUNT(*) FROM member")[0][0],
        run_sql("SELECT COUNT(*) FROM orders")[0][0]))


# ----------------------------------------------------------------------
if __name__ == "__main__":
    if "--cleanup" in sys.argv:
        cleanup()
    else:
        build()
