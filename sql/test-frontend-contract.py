# -*- coding: utf-8 -*-
"""
里程碑 8 测试（三）：前端契约

这个脚本和前面那些【目的完全不同】，值得先把它和 test-order.py、
test-address.py 的区别说清楚：

    test-order.py     在问「后端的行为对不对」
                      —— 并发扣库存会不会超卖、事务回滚了没、幂等生效没
    test-address.py   在问「后端的行为对不对」
                      —— 越权能不能拦住、全量替换的契约守住了没
    这个脚本          在问「前端的假设对不对」

为什么要单独问这个问题？
    因为前端没有类型检查。Vue 模板里写 `product.status`，
    而后端返回的对象里【根本没有 status 字段】—— 这不报错，
    结果是 undefined，然后 `undefined !== 1` 恒为真，
    整个立即购买入口一点就废。

    这是里程碑 8 真实发生过的一个 bug（见 Checkout.vue 里的注释）。
    构建工具不会发现它，ESLint 不会发现它，手点页面也要点到那一步
    才看得见。但**它可以用一个脚本发现**：

        把「前端读了哪些字段」和「接口实际返回了哪些字段」对一遍。

    所以这个脚本做的事情很朴素 —— 它是一个【契约测试】。

它检查四类东西：
  1. 字段存在性：前端模板里读的每一个字段，接口响应里都要有
  2. 请求形状  ：前端真正发出去的那个 body，后端必须收得下
                （尤其是 crypto.randomUUID() 生成的、带连字符的键）
  3. 幂等时机  ：模拟「提交 → 响应丢了 → 用户按 F5 → 再提交」，
                必须在服务端被识别为重复提交
  4. 响应形状  ：里程碑 9 加的。收银台（Pay.vue）拿支付/取消接口的响应
                【直接覆盖页面】而不重新 GET —— 所以那两个接口一旦
                从返回 OrderVO 变成返回别的（比如 boolean），
                整个收银台会白屏，而后端自己的测试【全都还是绿的】。
                这一条只有契约测试能守住。

  5. 隐私边界  ：里程碑 12 加的，但它是第 1 类的一个特例，值得单列。
                评价区是【匿名游客也能拉】的接口，所以它【没有】
                phone / username 这两个字段这件事，必须是一条断言。
                「没有某个字段」这种契约，行为测试天然守不住 ——
                行为测试只会去读它有的字段，多出来的那个没人会读，
                也就没人会发现它悄悄多出来了。

运行：
    python test-frontend-contract.py
"""

import datetime
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"
REDIS = "mall-redis"

RUN = str(int(time.time()))[-8:]
PREFIX = "fctest"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
CATEGORY_ID = None
TOKEN = None
MEMBER_ID = None


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


def has(obj, key):
    """对象里【真的有】这个键吗（而不是取出来是 None）。"""
    return isinstance(obj, dict) and key in obj


def require_keys(obj, keys, label):
    """
    检查一个对象里该有的字段是不是都在。

    ★ 注意用的是 `key in obj` 而不是 `obj.get(key) is not None`。
      因为前端真正怕的不是"值是 null"，而是【键根本不存在】——
      JS 里两者都会得到 undefined，但原因完全不同：
        键不存在  →  前端写错了字段名，或者后端改了字段名 → 纯 bug
        值是 null →  后端有意给了个空值（比如订单没备注）→ 正常
      把这两种混在一起报，就分不清"代码错了"还是"数据本来就空"。
    """
    missing = [k for k in keys if not has(obj, k)]
    check(f"{label} 的字段齐全", not missing,
          f"缺 {missing}；实际有 {sorted(obj.keys()) if isinstance(obj, dict) else type(obj)}")


def new_uuid():
    """模拟前端的 crypto.randomUUID()。

    ★ 这一点必须用真的 UUID 来测：uuid4 的格式是
      xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx，【带 4 个连字符】。
      而后端 OrderBaseDTO 上的正则要求 ^[A-Za-z0-9_-]{8,64}$ ——
      连字符在允许的集合里，所以理论上没问题。
      但"理论上没问题"和"确实没问题"是两件事，
      而整个前端下单都依赖这一步。所以这里必须真发一次请求验证。
    """
    return str(uuid.uuid4())


def parse_ts(s):
    """把后端返回的 'yyyy-MM-dd HH:mm:ss' 解析成 datetime。

    ★ 用【显式格式】而不是 datetime.fromisoformat()：
      fromisoformat 在 Python 3.11 之前只认 ISO 的 'T' 分隔符，
      3.11 起才放宽到接受空格。这个项目跑在 3.14 上，恰好能过 ——
      而"恰好能过"是坏的：换台机器、换个解释器，就会以一种
      和当前改动毫无关系的方式失败，排查起来要绕一大圈。
      **把格式写死在 strptime 里，既是解析也是断言。**

      （前端 Pay.vue 里对这个格式也要做类似的处理：
         new Date("2026-09-22 20:48:06") 是非标准写法，
         必须换成 'T' 才是跨浏览器安全的。两端的坑是同一个坑。）
    """
    return datetime.datetime.strptime(s, "%Y-%m-%d %H:%M:%S")


# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(run_sql("SELECT id FROM category ORDER BY id LIMIT 1")[0][0])


def make_product(name, price, stock):
    # 里程碑 15：价格和库存搬到了 product_sku 上。没有规格的商品也要显式给一条
    # 「默认 SKU」（specs 为空数组），后端拿它的 price/stock 作为这件商品的价格和库存。
    st, r = call("POST", "/admin/products", {
        "name": name, "categoryId": CATEGORY_ID, "status": 1,
        "cover": "", "description": "",
        "specSchema": [], "skus": [{"specs": [], "price": price, "stock": stock}],
    }, ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    return r["data"]


def sku_of(pid):
    """商品 id → 默认 SKU id。

    ★ 里程碑 15 阶段 4：下单和加购现在只认规格 id。
    ⚠️ 为什么这个脚本【没有】把 make_product 的返回值整个换掉？
      因为它这个文件里 90% 的断言在讲【商品】（详情、列表、上下架、
      订单里的商品名），只有下单和加购那几步需要规格 id。
      两种 id 都是自增数字，撞车是必然的 —— 所以换的时候必须一处一处
      问「这里到底是哪一种」，而不是整体替换。
    """
    return int(scalar(f"SELECT id FROM product_sku WHERE product_id = {pid}"))


def register_member():
    global TOKEN, MEMBER_ID
    st, r = call("POST", "/shop/auth/register", {
        "username": TAG,
        "password": "front123456",
        "nickname": "前端契约测试",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    TOKEN = r["data"]["token"]
    MEMBER_ID = r["data"]["id"]


def cleanup():
    # ★ 顺序：晒图 → 评价 → 明细 → 订单 → 地址 → 会员 → 商品。
    #   顺序错了会留下孤儿明细 —— 里程碑 8 真的漏过一次 32 行，
    #   所以 test-order.py 现在已经加了孤儿检查。这里照那个顺序来。
    #
    #   ★ 里程碑 12：本脚本第 9 节【真的会写一条评价】，所以这两条
    #     删的不是 0 行 —— 这一节自己产生了要清理的数据。
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
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")
    # ★ 里程碑 15：product_sku 同样【没有外键】，所以它也必须排在商品之前。
    run_sql(f"DELETE FROM product_sku WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")
    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")
    redis_cmd("DEL", f"mall:cart:{MEMBER_ID}")


# ----------------------------------------------------------------------
def main():
    print()
    print("前端契约测试 —— 验证「前端读的字段」和「后端给的字段」是否对得上")

    admin_login()
    register_member()

    # ==================================================================
    section("1. 前端读的字段，接口里真的有吗")

    # ---- 商品详情（ProductDetail.vue / Checkout.vue 的立即购买）----
    pid = make_product(f"{PREFIX}契约商品", "12.30", 50)
    st, r = call("GET", f"/shop/products/{pid}")
    check("商品详情 → 200", r.get("code") == 200, f"HTTP {st} / {r}")

    detail = r.get("data") or {}
    # ★★ 这一条就是这个脚本存在的理由。
    #
    #   前端【绝对不能】去读 status：用户端商品详情的 SQL 里
    #   已经带了 `AND p.status = 1`，而且 ShopProductDetailVO
    #   里根本没有 status 字段。写 `p.status === 1` 是错的
    #   （undefined !== 1 恒为真），会让立即购买永远显示「已下架」。
    #
    #   下面这一条断言把那个决定【钉死】在这里：
    #   一旦有人给 VO 加上 status，这个测试会失败并提醒他
    #   "前端可能会开始依赖它，先想清楚"。
    # ★ 里程碑 15 阶段 6：price / stock 换成 minPrice。
    #   这两个键不是「改名」，是【换了含义】：
    #     price（已删）  = 这件商品的价格   —— 多规格商品根本没有这回事
    #     minPrice       = 各规格里最低的那个 —— 「起售价」，列表上跟一个「起」字
    #   ⚠️ 详情页刻意【没有】totalStock（列表那边才有）：
    #     跨规格的合计库存对「我要的这个规格还有没有货」这个问题没有意义，
    #     真正的库存按 skuId 看 detail.skus 里那一行。
    require_keys(detail, ["id", "name", "minPrice", "cover",
                          "description", "categoryId", "categoryName"],
                 "商品详情")
    check("★ 商品详情【没有】status 字段（能查到就代表在售）",
          not has(detail, "status"),
          f"出现了 status={detail.get('status')} —— 那前端就开始有"
          f"「它是不是在售」这个疑问了，而这个疑问本该由"
          f"SELECT 里的 p.status = 1 消化掉")

    # ---- ★ 里程碑 11：图集（ProductDetail.vue 的主图 + 缩略图条）----
    #
    #   ProductDetail.vue 里有一句：
    #       const images = product.value?.images
    #       if (images && images.length) return images
    #       return product.value?.cover ? [...] : []
    #
    #   也就是说「没有 images 字段」和「images 是空数组」在前端
    #   看起来是一样的（都退回封面图）。所以这条契约【不】检查
    #   「有没有图」，只检查【字段在不在、是不是数组】——
    #   这才是前端依赖的东西。
    #
    #   ⚠️ 关键的一条是「空的时候是 [] 而不是 null」：
    #   后端配了 default-property-inclusion: non_null，null 字段
    #   会【整个 key 消失】。所以「空图集返回 []」这件事不是自动的，
    #   是 Service 里显式 set 一个空列表换来的。
    #   哪天有人把 setImages 改成「空就不 set」，这个 key 就会消失，
    #   而前端那三行写得再小心也只能退回封面图 —— 不会报错。
    check("★★ 商品详情有 images 字段（前端读它）",
          has(detail, "images"), f"实际字段：{sorted(detail.keys())}")
    check("★ images 是数组（前端直接 .length / v-for）",
          isinstance(detail.get("images"), list),
          f"实际 {detail.get('images')!r}")
    check("★ 图集为空时是 []（不是 null、也不是这个 key 消失）",
          detail.get("images") == [],
          f"实际 {detail.get('images')!r} —— 见 ProductServiceImpl"
          f" 里 setImages 的注释：空列表是显式设的，不是自动的")

    #   ★ 列表接口【不该】有 images：Home.vue 的商品卡片只显示 cover，
    #     列表带上图集就是每翻一页都多查一次、查完没人看。
    #     （这是「有读者才加字段」那条判据在返回字段上的应用。）

    # ---- ★★ 里程碑 12：评价聚合（ProductDetail.vue 顶部那块评分）----
    #
    #   ★ 这条契约的性质和上面几条略有不同：它守的是
    #     「前端【不用】判空」这个假设。
    #     ProductDetail.vue 里写的是 product.reviewSummary.total，
    #     没有写 product.reviewSummary?.total。
    #
    #   ★★ 这个假设能成立，靠的是一条很容易被破坏的 SQL 性质：
    #      selectSummary 是【不带 GROUP BY 的聚合查询】，永远返回一行 ——
    #      零评价时是一个各字段为 0 的对象，不是 null。
    #      哪天有人给它加了 GROUP BY，零评价的商品就再也不返回行了，
    #      于是 reviewSummary 变成 null（non_null 下甚至会整个 key 消失），
    #      前端那一行会当场抛 TypeError，整个详情页白屏。
    #
    #   ⚠️ 注意这里【只】断言「字段在、是对象、total 是数字」——
    #      不断言具体值。因为这个商品在这个脚本里没有评价，
    #      而「零评价时各字段是 0」这件事由 test-review.py 第 7 节守着。
    #      契约测试管「形状」，行为测试管「数值」，两者不要混。
    check("★★ 商品详情有 reviewSummary 字段（ProductDetail.vue 直接读它）",
          has(detail, "reviewSummary"),
          f"实际字段：{sorted(detail.keys())}")
    summary = detail.get("reviewSummary")
    check("★★ reviewSummary 是对象，不是 null —— 前端写的是 .total，不是 ?.total",
          isinstance(summary, dict),
          f"实际 {summary!r} —— 若是 null 或这个 key 消失，"
          f"ProductDetail.vue 里 product.reviewSummary.total 会抛 TypeError")
    if isinstance(summary, dict):
        require_keys(summary,
                     ["total", "avgRating",
                      "count5", "count4", "count3", "count2", "count1"],
                     "reviewSummary")
        check("★ 五个分布字段都在（前端用 summary[`count${star}`] 循环取）",
              all(f"count{n}" in summary for n in range(1, 6)),
              f"实际 {sorted(summary.keys())} —— "
              f"缺一个的话，那一档的进度条会静默变成 0 长度")

    #   ★ 列表接口【不该】有 reviewSummary：Home.vue 的商品卡片不显示评分。
    #     理由和上面 images 那条一样。（同一个判据，第二次应用。）

    # ---- 商品列表（Home.vue）----
    # ⚠️ 分页参数是 pageNum / pageSize，不是 page / size。
    #    这一点我第一版猜错了 —— 测试脚本里的字段名同样是"猜"出来的，
    #    **猜错的时候失败的是测试，不是代码**，所以看到 FAIL
    #    要先分清是哪一边错了，再去改。
    st, r = call("GET", "/shop/products?pageNum=1&pageSize=5")
    check("商品列表 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    page = r.get("data") or {}
    require_keys(page, ["list", "pageNum", "pageSize", "pages", "total"],
                 "商品列表分页")
    if page.get("list"):
        # ★ 里程碑 15 阶段 6：price / stock 换成三个聚合字段。
        #   totalStock 是【跨规格合计】——列表上写「N 个规格」时用它，
        #   但**不能拿它判断「能不能买」**（合计有货不等于你要的那档有货）。
        require_keys(page["list"][0],
                     ["id", "name", "minPrice", "totalStock", "skuCount",
                      "cover", "categoryName"],
                     "商品列表条目")
        # ★ 里程碑 11：列表【不该】有 images（理由见上面那段）
        check("★ 商品列表条目【没有】images（列表页不显示它）",
              all(not has(p, "images") for p in page["list"]),
              f"第一行出现了：{sorted(page['list'][0].keys())}")
        # ★ 里程碑 12：列表同样【不该】有 reviewSummary（同一个判据）
        check("★ 商品列表条目【没有】reviewSummary（列表页不显示评分）",
              all(not has(p, "reviewSummary") for p in page["list"]),
              f"第一行出现了：{sorted(page['list'][0].keys())}")

    # ---- ★ 里程碑 11：管理端两个接口的 images 契约 ----
    #
    #   管理端和用户端在这里是【故意不对称】的：
    #     /admin/products/{id}  有 images  → ProductForm.vue 的回填要它
    #     /admin/products       无 images  → List.vue 表格只显示 cover
    #
    #   ⚠️ 管理端详情缺 images 的后果，正是 ProductForm.vue 注释里
    #      写的那两个「不报错的 bug」之一：回填时 form.images 拿到
    #      undefined（这里 [] 兜住了），但如果整个字段没了，
    #      编辑一次商品就会把它的图集清空一次，而保存时会提示「修改成功」。
    st, r = call("GET", f"/admin/products/{pid}", None, ADMIN_TOKEN)
    check("★ 管理端商品详情 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    a_detail = r.get("data") or {}
    check("★★ 管理端商品详情有 images（ProductForm 回填要它）",
          isinstance(a_detail.get("images"), list),
          f"实际 {a_detail.get('images')!r}，字段：{sorted(a_detail.keys())}")

    st, r = call("GET", "/admin/products?pageNum=1&pageSize=5", None, ADMIN_TOKEN)
    check("★ 管理端商品列表 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    a_page = r.get("data") or {}
    check("★ 管理端商品列表条目【没有】images",
          all(not has(p, "images") for p in (a_page.get("list") or [{}])),
          f"第一行：{sorted((a_page.get('list') or [{}])[0].keys())}")
    #   ⚠️ 这里【只看第一行】，不是「每一行都要有 cover」——
    #      两者看着差不多，实际差别很大，因为 Jackson 配了
    #      default-property-inclusion: non_null：
    #      **字段值是 null 时，整个 key 会从 JSON 里消失。**
    #      所以「有没有 cover 这个 key」其实是在问「这一行的 cover
    #      是不是 null」，而不是「接口有没有这个字段」。
    #
    #      列表是 ORDER BY id DESC，而 pid 是本脚本刚建的（make_product
    #      传的是 "cover": ""，空串不是 null，所以 key 一定在），
    #      所以第一行是我们【能控制】的那一行。
    #      换成「每一行都要有」就会依赖别人的数据里 cover 不为 null——
    #      一条会因为别人手工录了个没填封面的商品而变红的断言。
    #      （写 test-upload.py 时正是在这里栽过一次：断言用的是
    #       自己建的商品，而它建的时候压根没传 cover。）
    if a_page.get("list"):
        check("★ 管理端商品列表条目【有】cover（那一列缩略图用它）",
              has(a_page["list"][0], "cover"),
              f"第一行字段：{sorted(a_page['list'][0].keys())}")

    # ---- 分类（Home.vue 的筛选）----
    st, r = call("GET", "/shop/categories")
    check("分类列表 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if isinstance(r.get("data"), list) and r["data"]:
        require_keys(r["data"][0], ["id", "name"], "分类条目")

    # ---- 购物车（Cart.vue / Checkout.vue）----
    st, r = call("POST", "/shop/cart/items",
                 {"skuId": sku_of(pid), "quantity": 3}, TOKEN)
    check("加入购物车 → 200", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = call("GET", "/shop/cart", token=TOKEN)
    check("查购物车 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    cart = r.get("data") or {}
    require_keys(cart, ["items", "totalQuantity", "totalAmount"], "购物车")
    if cart.get("items"):
        item = cart["items"][0]
        # ★ 逐个对照 Cart.vue 和 Checkout.vue 模板里读到的东西
        #
        #   ⚠️ 这里【没有】unavailableReason，原因是下面那条 non_null 的断言：
        #   商品能买的时候这个字段是 null，而 null 字段根本不会出现在 JSON 里。
        #   前端只有在 !available 的分支下才读它（Cart.vue 的失效商品区），
        #   那时候它一定有值 —— 见下面单独的那条断言。
        # ★ 里程碑 15 阶段 4 新增 skuId/specText：
        #   Cart.vue 的 :key、改数量、删除三处用的都是 item.skuId，
        #   productId 还在但【可以为 null】（失效行查不到商品就没有它），
        #   所以它不能当行的身份。specText 是给用户看的规格描述。
        require_keys(item,
                     ["skuId", "productId", "specText", "name", "price", "cover",
                      "categoryName", "stock", "quantity", "subtotal", "available"],
                     "购物车条目")
        check("★ 可用商品的 available 是布尔 true（前端按 true/false 判断）",
              item.get("available") is True,
              f"拿到的是 {item.get('available')!r}（{type(item.get('available')).__name__}）——"
              f"前端写的是 i.available，如果是 1/0 或字符串，判断就会出错")

        # ★★ 这条断言钉的是【整个项目的一个全局行为】，不只是购物车。
        #
        #   application.yml 里配了：
        #       spring.jackson.default-property-inclusion: non_null
        #   意思是【值为 null 的字段直接从 JSON 里删掉】，不是给个 null。
        #
        #   为什么值得单独测：因为「字段不存在」和「字段是 null」
        #   在 JS 里长得一样（都是 undefined），但含义完全不同 ——
        #       unavailableReason 不存在  →  因为商品能买，后面没有原因
        #       unavailableReason 不存在  →  如果后端漏了这个字段，也长这样
        #   前端必须知道是前者，否则会误以为后者（或者反过来）。
        #
        #   ⚠️ 更重要的是它影响【所有】接口的写法：
        #   任何时候前端读到 undefined，都要先想清楚"是本来就没有，
        #   还是后端漏给了"——而这个项目里，答案默认是前者。
        check("★★ null 字段不出现在 JSON 里（Jackson non_null 的全局行为）",
              not has(item, "unavailableReason"),
              f"商品可买，unavailableReason 却是出现的（值 {item.get('unavailableReason')!r}）——"
              f"说明 non_null 配置被改了，所有前端代码对 undefined 的理解都要重新检查")

    # ---- 购物车件数（顶部角标，返回裸数字）----
    st, r = call("GET", "/shop/cart/count", token=TOKEN)
    check("购物车件数 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 件数接口返回的是【裸数字】不是对象（stores/cart.js 里直接当数字用）",
          isinstance(r.get("data"), int),
          f"拿到 {r.get('data')!r}（{type(r.get('data')).__name__}）")

    # ---- 地址（Addresses.vue / Checkout.vue）----
    st, r = call("POST", "/shop/addresses", {
        "receiver": "契约收", "phone": "13900001111",
        "region": "广东省深圳市南山区", "detail": "科技园 1 号",
        # ★ 前端传的是数字 1 / 0，不是 JSON 的 true / false。
        #   因为后端字段类型是 Integer —— 这一点必须真的验一次
        "isDefault": 1,
    }, TOKEN)
    check("新增地址（isDefault 传数字 1）→ 200", r.get("code") == 200, f"HTTP {st} / {r}")
    address_id = r.get("data")

    st, r = call("GET", "/shop/addresses", token=TOKEN)
    check("地址列表 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    if isinstance(r.get("data"), list) and r["data"]:
        require_keys(r["data"][0],
                     ["id", "receiver", "phone", "region", "detail", "isDefault"],
                     "地址条目")
        check("★ isDefault 是数字 1/0（前端写的是 addr.isDefault === 1）",
              isinstance(r["data"][0].get("isDefault"), int),
              f"拿到 {r['data'][0].get('isDefault')!r}"
              f"（{type(r['data'][0].get('isDefault')).__name__}）——"
              f"如果是布尔 true，=== 1 就会失败，默认标记不显示")

    st, r = call("GET", "/shop/addresses/default", token=TOKEN)
    check("默认地址 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    check("★ 默认地址返回的是对象本身，不是包一层 {address: ...}",
          has(r.get("data"), "id"),
          f"拿到 {r.get('data')!r} —— Checkout.vue 直接读 a.id")

    # ==================================================================
    section("2. 前端真正发出去的请求，后端收得下吗")

    # ★★ 真实 UUID 作幂等键。
    #    前端的 utils/checkoutIntent.js 用 crypto.randomUUID()，
    #    格式和这里的 uuid4 一样：36 个字符、带 4 个连字符。
    #    后端正则 ^[A-Za-z0-9_-]{8,64}$ 理论上接受连字符 ——
    #    但整个下单流程都押在这一步上，必须真验一次。
    key1 = new_uuid()
    check(f"（准备）用真的 UUID 当幂等键：{key1}", len(key1) == 36)

    st, r = call("POST", "/shop/orders", {
        "skuIds": [sku_of(pid)],
        "addressId": address_id,
        "idempotencyKey": key1,
        # ★ 前端在没有备注时传的是【空字符串】，不是 null、不是不传。
        #   api/order.js 里写的是 remark: remark || ''
        "remark": "",
    }, TOKEN)
    check("★★ 购物车结算：UUID 幂等键 + 空字符串备注 → 200",
          r.get("code") == 200, f"HTTP {st} / {r}")
    order1 = r.get("data") or {}
    # ★ 里程碑 9 之前，这里还【没有】createTime：
    #   OrderServiceImpl 插入订单之后是直接拿那个 Order 对象组装 VO 的，
    #   而 create_time 是【数据库默认值】填的（CURRENT_TIMESTAMP），
    #   插入时 Java 那边这个字段还是 null；MyBatis 的 useGeneratedKeys
    #   只能回填自增 id，读不到数据库生成的列。
    #
    #   ⚠️ 里程碑 8 的这条断言是写成反向的（断言 createTime「不出现」），
    #   当作一个哨兵：万一哪天它出现了，说明实现改了。
    #   里程碑 9 做收银台时它真的出现了 —— 因为 payDeadline 要按
    #   createTime 算，一个引用它的地方直接把 null 崩成了 500（见
    #   OrderServiceImpl.doCreate 第 8 步的注释）。
    #   **所以这条现在改成正向断言，并且同时验证格式。**
    require_keys(order1,
                 ["id", "orderNo", "status", "totalAmount", "receiverName",
                  "receiverPhone", "receiverAddress", "remark", "createTime"],
                 "下单响应")
    # ⚠️ 格式必须是「yyyy-MM-dd HH:mm:ss」这种带空格的字符串。
    #   JacksonConfig 全局配了 LocalDateTime 的这个格式。
    #   ★ 为什么要在这里断言格式？因为前端 Pay.vue 的倒计时要把这个串
    #   解析成 Date，而 new Date("2026-09-22 20:48:06") 是【非标准】写法。
    #   前端那边做了 replace(' ', 'T') 兜底，但只有当格式确实是
    #   「日期 空格 时间」时那个兜底才成立 —— 万一改成 ISO 的 'T' 形式，
    #   前端那句 replace 就变成空操作（无害），可万一改成带毫秒或者带时区，
    #   就会是另一个故事了。**跨端传的时间格式值得被钉住。**
    create_time_1 = order1.get("createTime")
    check("★ 下单响应里【有】createTime，且格式是 'yyyy-MM-dd HH:mm:ss'",
          isinstance(create_time_1, str) and len(create_time_1) == 19
          and create_time_1[4] == "-" and create_time_1[10] == " "
          and create_time_1[13] == ":",
          f"createTime = {create_time_1!r}")
    check("★ 下单响应里【有 orderNo】，前端的成功面板直接显示它",
          bool(order1.get("orderNo")), f"orderNo = {order1.get('orderNo')!r}")
    check("★ status 是数字 0（待支付）",
          order1.get("status") == 0,
          f"拿到 {order1.get('status')!r}（{type(order1.get('status')).__name__}）")

    # ==================================================================
    section("3. ★★ 模拟「提交 → 响应丢了 → 按 F5 → 再提交」")

    # 这一节是整个脚本的重点。
    #
    # 用户视角的完整流程：
    #   1. 点提交，请求发出去了，服务端建好了订单
    #   2. 响应没回来（网络卡 / 用户等不及）
    #   3. 用户按 F5 —— 页面重新挂载
    #   4. 用户再点提交
    #
    # 前端必须在这两次提交里给出【同一个幂等键】，
    # 否则服务端认不出来是重复提交，就会建第二笔订单。
    #
    # utils/checkoutIntent.js 的做法是：把「买什么」压成签名存进
    # sessionStorage，刷新后算出来的签名一样 → 复用同一个键。
    # 所以下面的第二次请求用的还是 key1 —— 这正是刷新后的行为。

    st, r = call("POST", "/shop/orders", {
        "skuIds": [sku_of(pid)], "addressId": address_id,
        "idempotencyKey": key1, "remark": "",
    }, TOKEN)
    check("★★ 同一个键再提交一次 → 200（不是报错）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    order2 = r.get("data") or {}
    check("★★ 返回的是【同一笔订单】（orderNo 相同）",
          order2.get("orderNo") == order1.get("orderNo"),
          f"第一次 {order1.get('orderNo')}，第二次 {order2.get('orderNo')} ——"
          f"不同就说明幂等没生效，用户会被扣两次库存")
    check("★★ 订单 id 也相同（没有新建记录）",
          order2.get("id") == order1.get("id"),
          f"{order1.get('id')} vs {order2.get('id')}")

    rows = run_sql(f"SELECT COUNT(*) FROM orders WHERE member_id = {MEMBER_ID}")
    check("★★ 库里确实只有 1 笔订单", int(rows[0][0]) == 1, f"实际 {rows[0][0]} 笔")

    items = run_sql(f"SELECT COUNT(*) FROM order_item i JOIN orders o ON o.id = i.order_id "
                    f"WHERE o.member_id = {MEMBER_ID}")
    check("★★ 明细也只有 1 行（没有重复扣库存）",
          int(items[0][0]) == 1, f"实际 {items[0][0]} 行")

    # ==================================================================
    section("3b. ★★★ 换了规格就必须换幂等键 —— 这是本轮最贵的一条")
    #
    # ★★ 背景：幂等键是 (会员, 键) 唯一的。用户改了规格但没改键，
    #    后端【认不出这是另一件事】—— 它只会把上一笔订单原样返回。
    #    症状：下单"成功"、跳转"成功"、金额和商品名都对，
    #          只是买的是【上一次那个规格】。全程没有一个地方报错。
    #
    #    所以修法只能在【前端】：签名里必须带上 skuId。
    #    下面先证明「后端确实分不出来」，再检查「前端确实算了 skuId」。

    # ---- ① 后端视角：同一个键 + 不同规格 → 只会有一笔订单 ----
    st, r = call("POST", "/admin/products", {
        "name": f"{PREFIX}两规格", "categoryId": CATEGORY_ID, "status": 1,
        "cover": "", "description": "",
        "specSchema": [{"name": "颜色", "values": ["黑", "白"]}],
        "skus": [
            {"specs": [{"name": "颜色", "value": "黑"}], "price": "1.00", "stock": 10},
            {"specs": [{"name": "颜色", "value": "白"}], "price": "2.00", "stock": 10},
        ],
    }, ADMIN_TOKEN)
    check("（准备）建一件两规格商品 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    two = (r.get("data") if r.get("code") == 200 else None)
    if not two:
        return
    black = int(scalar(f"SELECT id FROM product_sku WHERE product_id = {two} "
                       f"AND spec_json LIKE '%黑%'"))
    white = int(scalar(f"SELECT id FROM product_sku WHERE product_id = {two} "
                       f"AND spec_json LIKE '%白%'"))
    check("（准备）拿到两个规格的 id", black and white and black != white,
          f"黑={black} 白={white}")

    key_spec = new_uuid()
    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": black, "quantity": 1, "addressId": address_id,
        "idempotencyKey": key_spec, "remark": "",
    }, TOKEN)
    check("（准备）用「黑」下一单 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    first_no = (r.get("data") or {}).get("orderNo")

    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": white, "quantity": 1, "addressId": address_id,
        "idempotencyKey": key_spec, "remark": "",
    }, TOKEN)
    reused_no = (r.get("data") or {}).get("orderNo")
    n_with_key = int(scalar(
        f"SELECT COUNT(*) FROM orders WHERE member_id = {MEMBER_ID} "
        f"AND idempotency_key = '{key_spec}'"))
    check("★★★ 同一个键换成「白」再提交 → 后端【分不出来】，只建了一笔订单",
          n_with_key == 1,
          f"建了 {n_with_key} 笔 —— 后端按理应该只认 (会员, 键)，"
          f"出现 2 笔说明幂等约束被放松了")
    check("★★★ 而且它返回的是【上一笔】订单 —— 用户买到的还是「黑」",
          reused_no == first_no,
          f"{first_no} vs {reused_no} —— 这就是 checkoutIntent.js 必须"
          f"把 skuId 算进签名的【全部理由】：后端没有别的办法知道"
          f"用户改过规格")

    # ---- ② 前端视角：签名里到底算了什么 ----
    #
    # ★ 这是一条【静态断言】：读前端源码，检查 signatureOf 的原料。
    #   为什么不跑 JS？因为这里没有 Node 环境，而这条规则的本质是
    #   「签名由哪几个字段构成」—— 那是一个关于源码的事实，
    #   读源码比搭一个 JS 运行时更直接、更不容易假绿。
    #   代价：它只证明【写了 skuId】，不证明逻辑对。所以上面①那条
    #   （后端确实分不出来）必须同时在，两条合起来才是完整的论证。
    intent_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "mall-shop", "src", "utils", "checkoutIntent.js")
    if not os.path.exists(intent_path):
        check("★ checkoutIntent.js 存在", False, f"找不到 {intent_path}")
    else:
        with open(intent_path, encoding="utf-8") as f:
            intent_src = f.read()
        body = intent_src.split("export function signatureOf", 1)[-1]
        body = body.split("\n}", 1)[0]
        check("★★★ 前端签名里算了 skuId（不算的话改规格会复用同一个幂等键）",
              "skuId" in body,
              f"signatureOf 的实现里没看到 skuId：{body.strip()[:200]}")
        check("★ 而且它【没有】按 productId 算签名（那是 15 阶段 4 之前的写法）",
              "productId" not in body,
              f"signatureOf 里还有 productId：{body.strip()[:200]}")

    # 用完就删，别影响后面几节对「库里有多少笔订单」的断言
    run_sql(f"DELETE oi FROM order_item oi JOIN orders o ON o.id = oi.order_id "
            f"WHERE o.member_id = {MEMBER_ID} AND o.idempotency_key = '{key_spec}'")
    run_sql(f"DELETE FROM orders WHERE member_id = {MEMBER_ID} "
            f"AND idempotency_key = '{key_spec}'")

    # ==================================================================
    section("4. 用户【真的想再买一份】时必须能买")

    # ★ 这一节测的是幂等保护的【反面】，同样重要：
    #   如果幂等键永远复用，用户就没法买两次同样的东西了。
    #   前端的解法是「下单成功后立刻 clearIntent()」，
    #   下次进结算页会生成新的键。
    #   所以这里用一个【新键】模拟那个场景。
    key2 = new_uuid()
    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": sku_of(pid),
        "quantity": 2,
        "addressId": address_id,
        "idempotencyKey": key2,
        "remark": "",
    }, TOKEN)
    check("★ 立即购买（新键）→ 200", r.get("code") == 200, f"HTTP {st} / {r}")
    order3 = r.get("data") or {}
    check("★★ 这是【另一笔】订单（orderNo 不同）",
          order3.get("orderNo") and order3.get("orderNo") != order1.get("orderNo"),
          f"{order1.get('orderNo')} vs {order3.get('orderNo')} ——"
          f"如果相同，说明用户想再买一份时被当成了重复提交，"
          f"他会以为下单成功而实际什么也没发生")

    rows = run_sql(f"SELECT COUNT(*) FROM orders WHERE member_id = {MEMBER_ID}")
    check("★ 库里现在有 2 笔订单", int(rows[0][0]) == 2, f"实际 {rows[0][0]} 笔")

    rows = run_sql(f"SELECT quantity FROM order_item WHERE order_id = {order3.get('id')}")
    check("★ 立即购买的数量是请求里的 2（服务端没有别的真相来源）",
          rows and int(rows[0][0]) == 2, f"实际 {rows}")

    # ==================================================================
    section("5. 商品失效时，前端能不能拿到「为什么不能买」")

    # ★ 这条是上一节 non_null 断言的反面。
    #   商品【能买】时 unavailableReason 不存在（是 null 被省略了）；
    #   商品【不能买】时它必须存在，而且要有内容 ——
    #   因为 Cart.vue 的失效商品区要把它显示给用户
    #   （见那里的注释：不能让商品"悄悄消失"，得说明原因）。
    #
    #   如果这里失败，现象是：购物车里出现一条灰掉的商品，
    #   但是「为什么灰」那一行是空的 —— 用户只能反复重试。
    pid2 = make_product(f"{PREFIX}待失效商品", "5.00", 10)
    call("POST", "/shop/cart/items", {"skuId": sku_of(pid2), "quantity": 1}, TOKEN)
    run_sql(f"UPDATE product SET status = 0 WHERE id = {pid2}")

    st, r = call("GET", "/shop/cart", token=TOKEN)
    cart = r.get("data") or {}
    # ★★ 这里必须按 skuId 找，不能按 productId。
    #   失效行的 productId 是【null】（商品查不到就没有它），
    #   按 productId 找会永远返回 None —— 而 None 会让下面的断言
    #   以「失效行不见了」的形式失败，看起来像是后端的问题。
    #   见 CartItemVO 里 productId 的注释。
    bad = next((i for i in (cart.get("items") or []) if i.get("skuId") == sku_of(pid2)), None)
    check("（准备）下架的那件商品还在购物车里、且 available 为 false",
          bad is not None and bad.get("available") is False,
          f"找到的条目是 {bad}")
    if bad is not None:
        check("★★ 失效商品【有】unavailableReason，而且不是空字符串",
              isinstance(bad.get("unavailableReason"), str)
              and bad["unavailableReason"].strip() != "",
              f"拿到 {bad.get('unavailableReason')!r} ——"
              f"前端要把这句话显示给用户，空的话用户不知道该怎么办")
        check("★ 失效商品仍然出现在列表里（不是被悄悄过滤掉）",
              True if bad is not None else False, "")

    # 把它清出购物车，免得影响下面的断言
    call("DELETE", f"/shop/cart/items/{sku_of(pid2)}", token=TOKEN)
    # ★ 里程碑 15：直接改库绕过了 Service 的级联，SKU 行要自己删。
    run_sql(f"DELETE FROM product_sku WHERE product_id = {pid2}")
    run_sql(f"DELETE FROM product WHERE id = {pid2}")

    # ==================================================================
    section("6. 下单后购物车【该清的清了、该留的留着】")

    # 第 1 节往车里放了 3 件，第 2 节的购物车结算把它买走了。
    # 而第 4 节的立即购买【不碰购物车】。
    keys_now = redis_cmd("HKEYS", f"mall:cart:{MEMBER_ID}")
    check("★ 购物车结算把那件商品清掉了（前端要据此刷新角标）",
          str(sku_of(pid)) not in (keys_now or []),
          f"车里还有 {keys_now}")

    st, r = call("GET", "/shop/cart/count", token=TOKEN)
    check("★ 件数接口这时返回 0（Cart.vue 下单后不会看到旧数字）",
          r.get("data") == 0, f"拿到 {r.get('data')!r}")

    # ==================================================================
    section("7. ★ 收银台（Pay.vue）依赖的契约")

    # ★★ 这一节存在的理由：
    #   Pay.vue 是整个项目里【唯一一个完全没有被自动测试覆盖过的页面】——
    #   test-pay.py 测的是后端行为，它直接调接口，从不查看前端读了什么。
    #   而 Pay.vue 比别的页面更容易踩契约的坑，因为它依赖两件
    #   "看不见就会静默坏掉"的东西：
    #     1. payDeadline —— 没有它倒计时不显示（不会报错，就是没有）
    #     2. 支付/取消接口的【响应形状】—— Pay.vue 是直接
    #        `order.value = await payOrder(...)` 覆盖的，不重新 GET。
    #        如果哪天后端把 pay 改成返回 boolean，整个收银台会白屏，
    #        而后端自己的测试【全都还是绿的】。
    #
    #   顺带说明为什么这一节能写得这么短：真正的重活在 test-pay.py 里
    #   （并发、超时、库存归还）。这里只回答"前端读得到吗"。

    st, r = call("GET", f"/shop/orders/{order3.get('orderNo')}", token=TOKEN)
    check("★ GET 订单详情 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    detail = r.get("data") or {}

    # Pay.vue 在模板里读的每一个字段，一个不多一个不少
    require_keys(detail,
                 ["orderNo", "status", "totalAmount", "receiverName",
                  "receiverPhone", "receiverAddress", "items"],
                 "订单详情")
    item0 = (detail.get("items") or [{}])[0]
    require_keys(item0, ["productName", "quantity", "subtotal"], "订单明细第一行")

    # ⚠️ 上面这些字段名必须和 OrderVO / OrderItemVO 里的【拼写完全一致】。
    #   前端读的是 `it.subtotal`（不是 itemSubtotal、不是 amount）——
    #   拼错了不报错，界面上那个位置就是一片空白。

    # ---- payDeadline：倒计时的唯一来源 ----
    dl = detail.get("payDeadline")
    check("★★ 待付款订单【有】payDeadline（Pay.vue 的倒计时全靠它）",
          isinstance(dl, str) and len(dl) == 19 and dl[10] == " ",
          f"拿到 {dl!r} —— 没有它倒计时就是 NaN:NaN，而且不会报任何错")
    # ★ 它必须等于 createTime + 30 分钟。
    #   这里的 30 是抄 application.yml 的 mall.order.pay-timeout-minutes。
    #   ⚠️ 抄来的常量要留退路：退路是"这个断言失败只说明配置改了"，
    #   而不是"产品坏了" —— 所以失败信息里要把两个时刻都打出来，
    #   让人一眼能算出实际用了多少分钟。
    ct = detail.get("createTime")
    if isinstance(dl, str) and isinstance(ct, str):
        delta_min = (parse_ts(dl) - parse_ts(ct)).total_seconds() / 60
        check("★ payDeadline = createTime + 30 分钟（application.yml 的配置）",
              abs(delta_min - 30) < 1,
              f"createTime={ct} payDeadline={dl} → 相差 {delta_min:.2f} 分钟")
    else:
        check("★ payDeadline = createTime + 30 分钟", False,
              f"createTime={ct!r} payDeadline={dl!r}，没法比对")

    # ---- 支付：响应必须是【完整的订单】，不是 boolean ----
    st, r = call("POST", f"/shop/orders/{order3.get('orderNo')}/pay",
                 {"payMethod": "ALIPAY"}, TOKEN)
    check("★ 支付 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    paid = r.get("data") or {}
    # ★★ 这三行是这一节的真正目的：pay 的响应形状必须和 GET 详情一样。
    require_keys(paid, ["orderNo", "status", "totalAmount", "items"],
                 "支付响应（Pay.vue 拿它直接覆盖页面，不重新 GET）")
    check("★★ 支付响应里 status = 1",
          paid.get("status") == 1, f"拿到 {paid.get('status')!r}")
    check("★ 支付响应里 payTime 有值，且格式是 'yyyy-MM-dd HH:mm:ss'",
          isinstance(paid.get("payTime"), str) and len(paid["payTime"]) == 19,
          f"拿到 {paid.get('payTime')!r}")
    check("★ 支付响应里 payMethod 回显了选的那种",
          paid.get("payMethod") == "ALIPAY", f"拿到 {paid.get('payMethod')!r}")
    # ★★ 已付款的订单【不能】再有 payDeadline。
    #   Pay.vue 的倒计时靠这个：block 是 v-if="status === 0" 已经隔离了，
    #   但 startCountdown 里还有一道 `if (!deadline) return`。
    #   两道保险都在，所以这条失败不会白屏 —— 但它会说明
    #   「non_null 这个全局配置被人改掉了」，那影响的是整个项目。
    check("★★ 已付款的订单【没有】payDeadline（non_null 把 null 字段省略了）",
          not has(paid, "payDeadline"),
          f"出现了 payDeadline={paid.get('payDeadline')!r}")

    # ---- 刷新页面还能看到（浏览器验收里"状态还在"那条靠这个）----
    st, r = call("GET", f"/shop/orders/{order3.get('orderNo')}", token=TOKEN)
    again = r.get("data") or {}
    check("★★ 重新查询时已付款状态还在（证明真的落库了，不是只改了内存）",
          again.get("status") == 1 and again.get("payMethod") == "ALIPAY",
          f"status={again.get('status')!r} payMethod={again.get('payMethod')!r}")

    # ---- 取消：同样必须是完整订单 ----
    # 用第 2 节购物车结算出来的 order1（还是待付款）
    st, r = call("GET", f"/shop/orders/{order1.get('orderNo')}", token=TOKEN)
    # ★ 里程碑 15 阶段 4：读 product_sku.stock，不是 product.stock。
    #   product.stock 在阶段 2~5 期间还在（回滚预案），但下单扣的是 sku 行，
    #   所以读那列会拿到一个【永远不动的数字】—— 断言会以「库存没归还」
    #   的形式失败，而实际归还得好好的。
    before_stock = int(run_sql(
        f"SELECT stock FROM product_sku WHERE id = {sku_of(pid)}")[0][0])
    qty1 = (r.get("data") or {}).get("items", [{}])[0].get("quantity")
    check("（准备）拿到 order1 的商品件数", isinstance(qty1, int) and qty1 > 0,
          f"拿到 {qty1!r}")

    st, r = call("POST", f"/shop/orders/{order1.get('orderNo')}/cancel",
                 None, TOKEN)
    check("★ 取消 → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    cancelled = r.get("data") or {}
    require_keys(cancelled, ["orderNo", "status", "totalAmount", "items"],
                 "取消响应")
    check("★★ 取消响应里 status = 4",
          cancelled.get("status") == 4, f"拿到 {cancelled.get('status')!r}")
    check("★ 取消响应里 cancelTime 有值",
          isinstance(cancelled.get("cancelTime"), str)
          and len(cancelled["cancelTime"]) == 19,
          f"拿到 {cancelled.get('cancelTime')!r}")
    check("★★ 已取消的订单【没有】payDeadline",
          not has(cancelled, "payDeadline"),
          f"出现了 {cancelled.get('payDeadline')!r}")
    # 取消要给用户看「取消时间」，但 payMethod 从来没用过，
    # 所以它也该是 null（被 non_null 省掉）—— Pay.vue 的已取消面板不读它
    check("★ 已取消的订单也没有 payMethod（从没付过款）",
          not has(cancelled, "payMethod"),
          f"出现了 {cancelled.get('payMethod')!r}")

    # 库存归还。★ 归还的【件数】从上一次查询里读，不硬编码 ——
    # 硬编码就等于在测试脚本里再抄一份"这一单买了几件"，
    # 哪天上面的用例改了数量，这里会以一个看不懂的方式失败
    after_stock = int(run_sql(
        f"SELECT stock FROM product_sku WHERE id = {sku_of(pid)}")[0][0])
    check(f"★★ 取消后库存归还了 {qty1} 件（{before_stock} → {after_stock}）",
          after_stock == before_stock + qty1,
          f"{before_stock} → {after_stock}，期望 +{qty1}")

    # ---- 重复支付必须被拒（Pay.vue 的 catch 分支就是走这条路）----
    st, r = call("POST", f"/shop/orders/{order3.get('orderNo')}/pay",
                 {"payMethod": "ALIPAY"}, TOKEN)
    # ★ 注意这里断言的【不是 401/500】，而是业务错误码 1002 ——
    #   项目约定：业务错误返回 HTTP 200 + body.code。
    #   如果哪天变成 HTTP 4xx，前端的 request.js 拦截器就收不到
    #   body 里的 message 了，用户会看到"网络错误"这种没信息量的话。
    check("★★ 重复支付 → body.code=1002（不是 HTTP 4xx）",
          r.get("code") == 1002,
          f"HTTP {st} / code={r.get('code')!r} / {r.get('message')!r}")

    # ==================================================================
    section("8. ★ 里程碑 10：订单列表 + 发货 / 确认收货的字段契约")

    # 这一节存在的理由和上面那节一样：**它是唯一能提前发现
    # 「前端读的字段名后端不给」的东西。**
    # 里程碑 10 新加了两个页面（mall-shop 的「我的订单」、
    # mall-web 的「订单管理」），它们读的字段里有一半是新的 ——
    # 拼错了不会报错，界面上那个位置就是一片空白。
    #
    # ⚠️ 更要紧的是 items[].orderId：列表页是【一页一次性拿到所有明细】，
    #    靠 orderId 分组。没有它，前端只能猜「这条明细属于哪一单」——
    #    而猜错的症状是「A 的商品显示在 B 的订单里」，肉眼很难发现。

    st, r = call("GET", "/shop/orders?pageSize=50", token=TOKEN)
    check("★ GET /shop/orders（我的订单列表）→ 200", r.get("code") == 200,
          f"HTTP {st} / {r}")
    page = r.get("data") or {}
    require_keys(page, ["list", "total", "pageNum", "pageSize", "pages"],
                 "我的订单分页结果")

    # 列表里每一行都必须是完整订单 —— 前端就是拿这一行直接渲染卡片的
    order_rows = page.get("list") or []
    check("（准备）列表里至少有一笔订单", len(order_rows) > 0, f"拿到 {len(order_rows)} 笔")
    if order_rows:
        row = order_rows[0]
        require_keys(row,
                     ["id", "orderNo", "status", "totalAmount", "receiverName",
                      "receiverPhone", "receiverAddress", "items"],
                     "订单列表里的一行")
        # ★ 列表行的金额格式必须和详情页一致（都是 BigDecimal → JSON number）
        check("★ 列表行的 totalAmount 能当数字用",
              isinstance(row.get("totalAmount"), (int, float, str)),
              f"拿到 {row.get('totalAmount')!r}")
        # ★ 时间格式跨端统一 'yyyy-MM-dd HH:mm:ss'
        #   （Orders.vue 会显示下单时间，列表里显示的是 createTime）
        check("★ 列表行的 createTime 格式是 'yyyy-MM-dd HH:mm:ss'",
              isinstance(row.get("createTime"), str) and len(row["createTime"]) == 19
              and row["createTime"][10] == " ",
              f"拿到 {row.get('createTime')!r}")

        # ★★ items[].orderId 现在【必须存在】—— 它是前端分组/归属判断的依据。
        #   它在里程碑 8 是被【故意省掉】的（"前端已经知道这是哪个单的"），
        #   那个理由对单笔查询成立，对列表查询不成立。
        #   这一条就是那句「被省掉的字段要拿回来，就该把当初省掉它的理由
        #   一起改掉」的回归测试。
        item0 = (row.get("items") or [{}])[0]
        # ★ 里程碑 15 阶段 4 新增 skuId/skuSpec：Orders.vue 在商品名后面
        #   显示 it.skuSpec。⚠️ 但这两个字段的 null 规则【不一样】，别记混：
        #     skuSpec 永远是字符串（可能是空串 ''）
        #     skuId   可能是 null → 那个 key 会整个消失（历史/孤儿明细）
        #   所以 skuSpec 可以进 require_keys，skuId 不能 ——
        #   require_keys 断言的是「这个 key 一定在」，而 skuId 不一定在。
        require_keys(item0, ["orderId", "productId", "productName", "skuSpec",
                             "price", "quantity", "subtotal"],
                     "列表里的订单明细（★ orderId 是里程碑 10 新加回来的）")
        check("★★ 明细的 orderId 就是它所在订单的 id（前端靠它分组）",
              item0.get("orderId") == row.get("id"),
              f"orderId={item0.get('orderId')!r} 而订单 id={row.get('id')!r}")

        # ★★ shipTime / completeTime 必须【在字段定义里】（哪怕是 null 被省掉）。
        #   注意不能断言"它一定存在"—— 待付款的订单这两个字段都是 null，
        #   会被 non_null 整个省略掉。所以这里验的是【已完成的订单有它】，
        #   见下面那段。
        check("★ 用户端列表【不】带 memberUsername（那是管理端独有的字段）",
              not has(row, "memberUsername"),
              f"出现了 memberUsername={row.get('memberUsername')!r} —— 字段泄露了")

    # ---- 发货 + 确认收货：走一遍完整流程，验证两个响应形状 ----
    st, r = call("POST", "/shop/orders/buy-now", {
        "skuId": sku_of(pid), "quantity": 1, "addressId": address_id,
        "idempotencyKey": f"k{RUN}fc10a",
    }, TOKEN)
    check("（准备）新建一笔订单用于发货流程", r.get("code") == 200, f"HTTP {st} / {r}")
    flow_no = (r.get("data") or {}).get("orderNo")

    st, r = call("POST", f"/shop/orders/{flow_no}/pay", {"payMethod": "WECHAT"}, TOKEN)
    check("（准备）支付成功", r.get("code") == 200, f"HTTP {st} / {r}")

    # ★ 发货是【管理端】接口，要用管理员 token
    st, r = call("POST", f"/admin/orders/{flow_no}/ship", None, ADMIN_TOKEN)
    check("★ POST /admin/orders/{no}/ship → 200", r.get("code") == 200,
          f"HTTP {st} / {r}")
    shipped = r.get("data") or {}
    # ★★ mall-web 的订单列表拿这个响应直接刷新那一行，不重新拉列表 ——
    #    所以它必须是一行【管理端】订单的完整形状（含会员字段）。
    require_keys(shipped,
                 ["id", "orderNo", "status", "totalAmount", "items",
                  "memberUsername", "memberNickname", "shipTime"],
                 "发货响应（mall-web 拿它直接刷新那一行）")
    check("★★ 发货响应里 status = 2", shipped.get("status") == 2,
          f"拿到 {shipped.get('status')!r}")
    check("★★ 发货响应里 shipTime 有值，格式 'yyyy-MM-dd HH:mm:ss'",
          isinstance(shipped.get("shipTime"), str)
          and len(shipped["shipTime"]) == 19 and shipped["shipTime"][10] == " ",
          f"拿到 {shipped.get('shipTime')!r}")
    check("★ 发货响应里【没有】completeTime（还没确认收货）",
          not has(shipped, "completeTime"),
          f"出现了 {shipped.get('completeTime')!r}")

    # ---- 管理端列表：字段形状 ----
    st, r = call("GET", f"/admin/orders?orderNo={flow_no}", None, ADMIN_TOKEN)
    check("★ GET /admin/orders → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    a_page = r.get("data") or {}
    require_keys(a_page, ["list", "total", "pageNum", "pageSize", "pages"],
                 "管理端订单分页结果")
    a_rows = a_page.get("list") or []
    check("（准备）按订单号精确搜索能搜到", len(a_rows) == 1, f"拿到 {len(a_rows)} 行")
    if a_rows:
        require_keys(a_rows[0],
                     ["id", "orderNo", "status", "totalAmount", "items",
                      "memberUsername", "memberNickname"],
                     "管理端订单列表里的一行")

    # ---- 确认收货：用户端接口，响应必须和 GET 详情同形状 ----
    st, r = call("POST", f"/shop/orders/{flow_no}/complete", None, TOKEN)
    check("★ POST /shop/orders/{no}/complete → 200", r.get("code") == 200,
          f"HTTP {st} / {r}")
    done = r.get("data") or {}
    require_keys(done, ["orderNo", "status", "totalAmount", "items", "completeTime"],
                 "确认收货响应")
    check("★★ 确认收货响应里 status = 3", done.get("status") == 3,
          f"拿到 {done.get('status')!r}")
    check("★★ 确认收货响应里 completeTime 有值，格式 'yyyy-MM-dd HH:mm:ss'",
          isinstance(done.get("completeTime"), str)
          and len(done["completeTime"]) == 19 and done["completeTime"][10] == " ",
          f"拿到 {done.get('completeTime')!r}")
    # ★★ 已完成【不是】待付款，所以 payDeadline 必须没有。
    #    这一条顺便守着 non_null 那个全局配置（同上面已付款那条）。
    check("★★ 已完成的订单【没有】payDeadline",
          not has(done, "payDeadline"),
          f"出现了 {done.get('payDeadline')!r}")

    # ---- 状态筛选：mall-shop 的 5 个 Tab 就靠这个参数 ----
    # ★★ status=0 必须能真的筛出「待付款」。
    #    这是本里程碑埋得最隐蔽的坑：前端读 status 时如果复用
    #    toPositiveInt（判据是 n > 0），status=0 会被当成「解析失败」
    #    退回 fallback —— 于是「待付款」这个 Tab 永远选不中，
    #    而它恰恰是用户最常看的那个。前端那一半的验法在浏览器里，
    #    这一条验的是后端确实把 0 当成一个合法状态值。
    st, r = call("GET", "/shop/orders?status=0", token=TOKEN)
    zero = r.get("data") or {}
    check("★★ status=0 能筛出「待付款」（0 是合法状态，不是「全部」）",
          r.get("code") == 200 and all(o["status"] == 0 for o in zero.get("list") or []),
          f"HTTP {st} / 拿到 {[o.get('status') for o in zero.get('list') or []]}")
    check("★ status=0 的结果【不等于】不传 status 的结果",
          zero.get("total", -1) != (page.get("total") or 0)
          or len(zero.get("list") or []) != len(order_rows),
          "两者相同 —— 说明 status=0 被当成了「全部」")

    # ==================================================================
    section("9. ★ 里程碑 12：评价 + 订单明细上的评价状态")

    # 这一节守的是「我的订单」页面里那个评价按钮 ——
    # Orders.vue 的明细行上写的是：
    #     <el-button v-if="!it.reviewId" ... @click="openReview(it)">评价</el-button>
    #     <el-tag v-else>已评价</el-tag>
    # 两个字段里的每一个拼错，结果都不是报错，而是「按钮永远显示」或
    # 「按钮永远不显示」。这类 bug 手点页面要点到那一步才看得见。

    items = done.get("items") or []
    check("（准备）已完成订单里有明细", len(items) > 0, f"拿到 {len(items)} 条")
    item = items[0]
    require_keys(item, ["id", "orderId", "productId", "productName", "skuSpec",
                        "price", "quantity", "subtotal"], "订单明细")
    check("★★ 订单明细【有】id（前端拿它当 orderItemId 传回去）",
          isinstance(item.get("id"), int),
          f"拿到 {item.get('id')!r} —— "
          f"没有它，评价按钮不知道该评哪一条明细，只能整单评价一次")
    check("★★ 未评价的明细【没有】reviewId 这个键（non_null 让它整个消失）",
          not has(item, "reviewId"),
          f"出现了 reviewId={item.get('reviewId')!r} —— "
          f"前端因此必须写 !it.reviewId，"
          f"写 it.reviewId === null 是【永远为 false】的")

    # ---- 提交一条评价 ----
    st, r = call("POST", "/shop/reviews", {
        "orderItemId": item["id"], "rating": 4, "content": "契约测试写的评价",
    }, TOKEN)
    check("★ POST /shop/reviews → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    new_review_id = r.get("data")
    check("★ 返回一个裸的评价 id", isinstance(new_review_id, int),
          f"拿到 {new_review_id!r}")

    # ★★ 评价之后，同一个订单明细上【要】出现 reviewId。
    #    两种状态都测，是因为它们走的是两个不同的分支：
    #    未评价时是 LEFT JOIN 没命中 → null → 键消失；
    #    已评价时是命中 → 有值。只测一种，另一种写错了看不出来。
    st, r = call("GET", f"/shop/orders/{flow_no}", None, TOKEN)
    item2 = (r.get("data") or {}).get("items", [{}])[0]
    check("★★ 已评价的明细【有】reviewId，且等于评价 id",
          item2.get("reviewId") == new_review_id,
          f"拿到 reviewId={item2.get('reviewId')!r}（期待 {new_review_id}）")

    # ---- 用户端评价列表（ProductDetail.vue 的评价区块）----
    st, r = call("GET", f"/shop/products/{pid}/reviews?pageNum=1&pageSize=10")
    check("★ GET /shop/products/{id}/reviews → 200（★ 匿名可调）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    rpage = r.get("data") or {}
    require_keys(rpage, ["list", "total", "pageNum", "pageSize", "pages"],
                 "评价分页结果")
    rrows = rpage.get("list") or []
    check("（准备）列表里有刚写的那条", len(rrows) >= 1, f"拿到 {len(rrows)} 条")
    if rrows:
        require_keys(rrows[0], ["id", "rating", "content",
                                "memberNickname", "createTime", "images"],
                     "评价列表条目")
        check("★★ 评价列表里【没有】phone（匿名游客能拉这个接口）",
              not has(rrows[0], "phone"),
              f"出现了 phone —— 评价区是「别人也能看到」的界面，"
              f"这是 MemberInfoVO.phone 的 javadoc 在里程碑 10 "
              f"就预警过的合规事故")
        check("★★ 评价列表里【没有】username（只给昵称）",
              not has(rrows[0], "username"),
              f"出现了 username={rrows[0].get('username')!r} —— "
              f"登录账号不是给陌生人看的")
        check("★ 但昵称是有的（昵称可以公开）",
              rrows[0].get("memberNickname") is not None,
              f"拿到 {rrows[0].get('memberNickname')!r}")
        check("★ images 是数组（前端 v-if 之后 v-for 渲染晒图）",
              isinstance(rrows[0].get("images"), list),
              f"拿到 {rrows[0].get('images')!r}")
        check("★ createTime 已由后端格式化好（前端不引入新的时间格式函数）",
              isinstance(rrows[0].get("createTime"), str)
              and len(rrows[0]["createTime"]) == 19,
              f"拿到 {rrows[0].get('createTime')!r}")

    # ---- 管理端评价列表（mall-web 的 views/review/List.vue）----
    st, r = call("GET", "/admin/reviews?pageNum=1&pageSize=10", None, ADMIN_TOKEN)
    check("★ GET /admin/reviews → 200", r.get("code") == 200, f"HTTP {st} / {r}")
    apage = r.get("data") or {}
    require_keys(apage, ["list", "total", "pageNum", "pageSize", "pages"],
                 "管理端评价分页结果")
    arows = apage.get("list") or []
    check("（准备）管理端列表非空", len(arows) >= 1, f"拿到 {len(arows)} 条")
    if arows:
        require_keys(arows[0],
                     ["id", "rating", "content", "createTime", "images",
                      "productId", "productName",
                      "memberUsername", "memberNickname"],
                     "管理端评价列表的一行")
        check("★ 管理端【有】memberUsername（管理员本来就看得到，用户端没有）",
              arows[0].get("memberUsername") is not None,
              f"拿到 {arows[0].get('memberUsername')!r} —— "
              f"同一个字段两端一给一不给，判据是「看这个接口的人是谁」")
        check("★ 管理端也不给 phone（管理员有 username 就够了）",
              not has(arows[0], "phone"),
              f"出现了 phone")

    # ==================================================================
    section("10. 清理")

    cleanup()

    left = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
    check("测试会员已删除", int(left[0][0]) == 0, f"{left}")
    left = run_sql(f"SELECT COUNT(*) FROM product WHERE name LIKE '{PREFIX}%'")
    check("测试商品已删除", int(left[0][0]) == 0, f"{left}")
    left = run_sql(f"SELECT COUNT(*) FROM orders WHERE member_id = {MEMBER_ID}")
    check("★ 测试订单已删除", int(left[0][0]) == 0, f"剩余 {left[0][0]} 条")

    orphans = run_sql("SELECT COUNT(*) FROM order_item i "
                      "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")
    check("★★ 没有孤儿订单明细", int(orphans[0][0]) == 0,
          f"{orphans[0][0]} 行明细指向不存在的订单")

    # ★ 里程碑 12：两类新的孤儿。★ 这个脚本的清理顺序如果反了，
    #   症状就是这两条红 —— 而它们比「明细孤儿」更隐蔽，
    #   因为孤儿评价仍然会在管理端列表里显示（带着一个空的商品名）。
    orphans = run_sql("SELECT COUNT(*) FROM product_review r "
                      "LEFT JOIN product p ON p.id = r.product_id "
                      "WHERE p.id IS NULL")
    check("★★ 没有孤儿评价", int(orphans[0][0]) == 0,
          f"{orphans[0][0]} 行评价指向不存在的商品")
    orphans = run_sql("SELECT COUNT(*) FROM product_review_image i "
                      "LEFT JOIN product_review r ON r.id = i.review_id "
                      "WHERE r.id IS NULL")
    check("★★ 没有孤儿晒图", int(orphans[0][0]) == 0,
          f"{orphans[0][0]} 行晒图指向不存在的评价")

    print()
    print(f"你的数据：商品 "
          f"{run_sql('SELECT COUNT(*) FROM product')[0][0]} 个，分类 "
          f"{run_sql('SELECT COUNT(*) FROM category')[0][0]} 个，会员 "
          f"{run_sql('SELECT COUNT(*) FROM member')[0][0]} 个")

    print()
    print("=" * 72)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 72)
    if FAILED:
        print()
        print("失败项：")
        for f in FAILED:
            print(f"  - {f}")
    print()


if __name__ == "__main__":
    main()
