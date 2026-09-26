# -*- coding: utf-8 -*-
"""
收银台的「浏览器验收」夹具。

<h3>★ 这个脚本是干什么的？</h3>

<p>它不是测试 —— 测试在 {@code sql/} 下。它是一台<b>造数据的机器</b>：
用真实的 HTTP 接口建好两个会员、两件商品（其中一件有<b>两个规格、价格不同</b>）、
一笔待付款订单、一笔已付款订单、一笔已取消订单，以及一笔<b>属于别人的</b>订单，
然后把「token 和订单号」打印成可以直接粘进 {@code tools/shot.py} 的用法。

<p>★ 里程碑 15 阶段 5：小商品那两个规格不是装饰。待付款那一单是
<b>整轮 SKU 改造唯一能一眼看出来的证据</b> —— 明细里同一件商品占两行，
规格和单价都不同。如果两件商品都只有默认 SKU，截出来的收银台
和改造之前一模一样：页面是绿的，但什么也没验证到。

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


def scalar(sql):
    """取一个格子的值，查不到返回 None。

    <p>单独封一层不是为了少打字，是因为 {@code run_sql(...)[0][0]}
    在<b>一行都没查到</b>时抛的是 {@code IndexError: list index out of range}——
    它把「这行不存在」报成了「我下标取错了」，方向完全反了。
    （同一类错这个文件里已经栽过一次，见 {@code new_address} 上面那段。）
    """
    rows = run_sql(sql)
    return rows[0][0] if rows else None


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

    def post_product(name, spec_schema, skus):
        """建一件商品，返回【商品 id】。

        <p>★ 这里返回的是商品 id，而下面两个建商品的函数返回的是 <b>SKU id</b> ——
        形状不一致<b>是刻意的</b>，因为「这件商品」和「这个规格」在这份夹具里
        是两个不同用途的东西：商品 id 只用来记录和清理，SKU id 只用来下单加购。
        <b>两个 id 都是自增数字，传错一个不会 404，会静默地操作另一行</b>，
        所以每个返回值叫什么名字必须名副其实。
        """
        st, r = call("POST", "/admin/products", {
            "categoryId": category_id, "name": name, "status": 1,
            "specSchema": spec_schema, "skus": skus,
        }, token=admin)
        # ⚠️ 建商品返回【裸 id】，不是对象
        pid = must(st, r, f"建商品 {name}")
        state["products"].append(pid)
        save()
        return pid

    def default_sku_of(pid):
        """查一件无规格商品的默认 SKU（{@code spec_json = '[]'} 的那一条）。"""
        return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid} "
                          f"AND spec_json = '[]'"))

    def new_product(name, price, stock):
        """建一件【没有规格】的商品，返回它的【默认 SKU id】。

        <p>★ 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品
        也要显式给一条「默认 SKU」（specs 为空数组），后端拿它的
        price/stock 当作这件商品的价格和库存。

        <p>⚠️ 返回值是 SKU id，不是商品 id。因为它只会被填进
        「加购 / 下单」的请求体，而那两个接口现在收的是 skuId。
        """
        pid = post_product(name, [],
                           [{"specs": [], "price": price, "stock": stock}])
        return default_sku_of(pid)

    def new_spec_product(name, dim, variants):
        """建一件【单维规格】的商品，返回 {@code {规格值: SKU id}}。

        <p>{@code variants} 是 {@code [(规格值, 价格, 库存)]}。
        单维就够用了 —— 夹具要的是「同一件商品占两行」，不是把
        3×3 的矩阵也搬过来（那种组合的验证在 {@code sql/test-sku.py} 里）。
        """
        pid = post_product(
            name,
            [{"name": dim, "values": [v for v, _, _ in variants]}],
            [{"specs": [{"name": dim, "value": v}], "price": p, "stock": s}
             for v, p, s in variants])

        # 按规格值回查 id：不能靠「插入顺序」猜，更不能靠 pid + 1、pid + 2 推 ——
        # SKU 的 id 是全表共用一个 AUTO_INCREMENT，跟商品 id 没有任何关系。
        #
        # ★ 这里把整件商品的 SKU 行<b>拉回来在 Python 里比</b>，而不是
        #   下一条 `spec_json LIKE '%"黑色"'`。为什么？
        #
        #   我第一版就是那样写的，它<b>恒不匹配</b>：mysql 客户端在 Windows 上
        #   自己解析命令行，参数里带 `"` 的那个 LIKE 模式传到服务端时已经不是
        #   原来那串字符了 —— 查询合法、返回 0 行、不报任何错。
        #   而 `LIKE '%黑色%'`（不带引号）是能匹配的，所以这个坑只在
        #   "我想精确匹配 `"值"` 这个形状"时才踩得到，更难联想到编码。
        #
        #   教训不是"记住这个怪癖"，而是：**能不在 SQL 里拼字符串就别拼。**
        #   拉回来比一次，既躲开了引号/编码，也比子串匹配更严格 ——
        #   `%黑色%` 会连"规格名叫 黑色系"的行一起匹配上，逐项比较不会。
        rows = run_sql(f"SELECT id, spec_json FROM product_sku "
                       f"WHERE product_id = {pid}")
        found = {}
        for sku_id, spec_json in rows:
            for item in json.loads(spec_json):
                if item["name"] == dim:
                    found[item["value"]] = int(sku_id)
        return found

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

    def new_order(token, sku, qty, address_id, tag):
        # ★ 里程碑 15 阶段 4：立即购买的请求体从 productId 换成 skuId。
        #   参数名一并跟着改 —— 留一个叫 pid 的形参收 skuId，
        #   下一个读这段代码的人一定会把它当商品 id 用。
        st, r = call("POST", "/shop/orders/buy-now", {
            "skuId": sku, "quantity": qty, "addressId": address_id,
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
    sku_main = new_product(f"{PREFIX}收银台主商品{RUN}", "199.00", 500)

    # ★ 小商品做成【两个规格、价格不同】，这一单才是这一轮的证据。
    #   单价刻意差得远（9.90 / 19.90）：金额算错时不会「碰巧相等」。
    small = new_spec_product(f"{PREFIX}收银台小商品{RUN}", "颜色",
                             [("黑色", "9.90", 500), ("白色", "19.90", 500)])

    # ---- 1. 待付款：收银台的主场景（也要有明细可看，所以买了 3 行，
    #     其中 2 行是同一件商品的两个规格）
    #     —— 用购物车结算才能一单多行，正好让明细列表不是只有一行
    cart_lines = [(sku_main, 1), (small["黑色"], 1), (small["白色"], 2)]
    for sku, q in cart_lines:
        st, r = call("POST", "/shop/cart/items", {"skuId": sku, "quantity": q},
                     token=token_a)
        ok(st, r, "加购物车")
    st, r = call("POST", "/shop/orders", {
        "skuIds": [sku for sku, _ in cart_lines], "addressId": addr_a,
        "idempotencyKey": f"fx{RUN}cart", "remark": "",
    }, token=token_a)
    d = must(st, r, "购物车结算")
    state["orders"].append(d["id"])
    save()
    order_pending = d["orderNo"]

    # ---- 2. 已付款
    order_paid = new_order(token_a, sku_main, 1, addr_a, "paid")
    must(*call("POST", f"/shop/orders/{order_paid}/pay", {"payMethod": "WECHAT"},
               token=token_a), "支付")

    # ---- 3. 已取消（买的是【白色】这一档）
    #     取消时库存要还到「白色」自己那一行，不是这件商品的第一条 SKU ——
    #     这是阶段 4 里最容易改错、错了也最看不出来的地方（接口 200，订单状态也对）。
    order_cancelled = new_order(token_a, small["白色"], 2, addr_a, "cancel")
    must(*call("POST", f"/shop/orders/{order_cancelled}/cancel", None,
               token=token_a), "取消")

    # ---- 4. 别人的订单（用 B 的 token 打开 A 的订单号，应该看到空状态）
    order_other = new_order(token_b, sku_main, 1, addr_b, "other")

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
    print(f"       ↑ 明细应该【三行】，其中两行是同一件「小商品」的「黑色」和「白色」，")
    print(f"         规格分别写着 颜色:黑色 / 颜色:白色，小计 9.90 和 39.80，")
    print(f"         合计 248.70（= 199.00 + 9.90 + 19.90×2）。")
    print(f"         ★ 这两行是整轮 SKU 改造唯一能一眼看出来的证据 ——")
    print(f"           改造前它做不出来，改造后如果幂等键或购物车 field 改错了，")
    print(f"           这里会先露馅。")
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
    print("  ⚠️ 验完记得清掉（这次建了 2 个会员 / 2 件商品 / 3 个规格 / 2 个地址 / 4 笔订单）：")
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
    # ★★ 里程碑 18：order_logistics 是订单的【第二张】子表，同一个坑要踩第二遍。
    #   它同样没有外键，所以删订单之前必须先把轨迹删掉，
    #   否则 sql/test-logistics.py 的孤儿检查会红（"有 N 行轨迹指向不存在的订单"）。
    #   ⚠️ 这是【验收时才发现】的：夹具自己不发货、不录轨迹，所以 grep
    #      ship / logistics 一处命中都没有，看起来完全不用改 ——
    #      但它会在浏览器里被点出来（管理员发货 + 录节点），
    #      而那些轨迹记在夹具建的订单上，于是清理时成了孤儿。
    #   ★ 判据：**改了一张"挂在订单上的子表"，就要检查每一个"删订单"的地方。**
    run_sql(
        f"DELETE l FROM order_logistics l JOIN orders o ON o.id = l.order_id "
        f"WHERE o.id IN ({ids('orders')}) OR o.member_id IN ({member_ids})")
    run_sql(f"DELETE FROM orders WHERE id IN ({ids('orders')}) "
            f"OR member_id IN ({member_ids})")
    run_sql(f"DELETE FROM member_address WHERE id IN ({ids('addresses')})")
    # ★ 里程碑 15：product_sku 也没有外键，所以它同样要排在自己的父表之前。
    run_sql(f"DELETE FROM product_sku WHERE product_id IN ({ids('products')})")
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
