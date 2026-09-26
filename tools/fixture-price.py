# -*- coding: utf-8 -*-
"""
价格体系的【浏览器验收夹具】（里程碑 16 起）。

<h3>★ 为什么需要它</h3>

<p>这一轮的验收条件有一半在浏览器里：「商城卡片上要有删除线」「详情页选中不同规格，
划线价要跟着变」「管理端 SKU 矩阵里的毛利率不能把 0.00% 显示成『未设置』」。

<p>而<b>线上库的 100 件商品，划线价和成本价全是 NULL</b>。这是刻意的
（14b 那个迁移一条数据都没发明），代价就是<b>在真实数据上什么都看不到</b>：
商城里没有一条删除线、管理端的毛利率列全是「未设置」。
所以「眼见为实」得靠这个夹具造出来，验完删掉。

<p>做法和 {@code fixture-sku.py} / {@code fixture-pay.py} 一样：
<b>只碰自己建的那两件商品</b>，删的时候只按自己打印出来的 id 删。

<h3>★ 它造出来的两件商品（每一件都对应几个【具体的】要看的现象）</h3>

<pre>
① ZZ-价格夹具-划线价T恤        无规格 · 1 条默认 SKU
     price 199 / marketPrice 299 / costPrice 199     ← 成本【等于】售价
   看：商城卡片「¥199.00 ~~¥299.00~~」
       商城详情「¥199.00 ~~¥299.00~~」（无规格商品的那条 SKU 是自动选中的）
       管理端列表「¥199.00」—— 单规格不写区间
       ★★ 编辑弹窗里毛利率显示「0.00%」，不是「未设置」

② ZZ-价格夹具-多规格价格T恤     颜色 × 3 档 · 3 条 SKU
     常规      199 / 299 / 成本 120    → 毛利率  39.70%
     特价       99 / 149 / 成本  30    → 毛利率  69.70%
     亏本清仓  199 / 249 / 成本 250    → 毛利率 -25.63%   ← 红色
   看：商城卡片「¥99.00 ~ ¥199.00」而【没有】删除线
       （多规格商品的原价没有唯一答案，后端刻意不给 marketPrice 这个键）
       详情页「¥99.00 ~ ¥199.00」，选中「常规」→「¥199.00 ~~¥299.00~~」，
       选中「特价」→「¥99.00 ~~¥149.00~~」★ 划线价跟着所选规格走
       管理端编辑弹窗三列换算，其中一行是红色的负数

</pre>

<h3>★★ 为什么①的「成本 = 售价」不是凑数，它才是这个夹具的主角</h3>

<p>成本价等于售价时毛利是 <b>0</b>，而 {@code !0} 在 JS 里是 <b>true</b>。
所以表格里只要写成 {@code v-if="row.grossMargin"}，这一行就会被显示成
「未设置」—— <b>和一个真的没填成本价的规格长得一模一样</b>。

<p>这一轮里「前端一律用宽松真值判断」那条约定反过来咬人，而<b>只有 0.00%
这个值能让它现形</b>（39.70% 那种常规值怎么判都是对的）。
所以夹具必须造出一个毛利恰好为 0 的规格 —— 它不是边界值，它是探针。

<h3>用法</h3>

<pre>
  python tools/fixture-price.py                 # 建两件，打印 id、地址和删除命令
  python tools/fixture-price.py --cleanup 1033 1034
</pre>

<p>⚠️ 商品名是固定的，所以「建之前先删掉同名的那一件」只会删掉上一次
没清干净的自己。<b>不用 LIKE 通配符去扫</b> —— 那会删到别人的东西。
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080/api"

# ① 单规格：划线价看得见，毛利率是 0.00%
SINGLE_NAME = "ZZ-价格夹具-划线价T恤"
SINGLE = {
    "cover": "/images/tshirt-01.svg",
    "description": "里程碑 16 浏览器验收用：单规格商品的划线价 + 毛利率 0.00%",
    "price": 199.00,
    # ★ 299 是【故意】的：它必须严格大于 199，否则后端会拒绝保存，
    #   而商城页那条「marketPrice > price 才画删除线」也不会成立。
    "marketPrice": 299.00,
    # ★★ 等于售价。这一条是这个夹具的全部分量所在，理由见文件头的注释。
    "costPrice": 199.00,
    "stock": 50,
}

# ② 多规格：区间、逐规格的划线价、以及毛利率那一列的三种状态
MULTI_NAME = "ZZ-价格夹具-多规格价格T恤"
MULTI = {
    "cover": "/images/tshirt-02.svg",
    "description": "里程碑 16 浏览器验收用：价格区间 + 每个规格各自的划线价和毛利率",
    # (取值, 售价, 划线价, 成本价, 库存)
    "rows": [
        ("常规", 199.00, 299.00, 120.00, 50),      # 毛利率  39.70%
        ("特价", 99.00, 149.00, 30.00, 50),        # 毛利率  69.70%
        ("亏本清仓", 199.00, 249.00, 250.00, 50),  # 毛利率 -25.63%  红色
    ],
}


def call(method, path, body=None, token=None):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
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


def login():
    st, r = call("POST", "/admin/auth/login",
                 {"username": "admin", "password": "123456"})
    return r["data"]["token"]


def money(v):
    """金额按两位小数打印。

    <p>⚠️ 不是为了好看：JSON 里存的是 {@code 199.00}，
    而 Python 的 {@code json} 把它读成 float 之后 {@code print} 出来是
    <b>199.0</b>。这个脚本的用途就是「拿它打出来的数去和浏览器上的数
    对一眼」，少一个小数位会让对眼这件事多一层怀疑 ——
    「是它没存对，还是我的脚本打印得省事？」一次都不该出现。
    """
    if v is None:
        return "—"
    return f"{float(v):.2f}"


def wipe_same_name(token):
    """建之前先把同名的那件删掉 —— 它只可能是上一次没清干净的自己。

    <p>★ <b>不是 LIKE 前缀扫描。</b>反通配符清理原则：只删脚本自己
    认得出的精确名字，绝不清空目录、绝不按前缀删除。
    （按前缀搜出来看一眼是可以的，删的时候必须精确到名字。）

    <p>⚠️ 管理端列表的筛选参数是 <b>{@code name}</b>，不是 {@code keyword} ——
    这是本轮真的踩到过的坑：<b>传错了参数【不会报错】</b>，后端当没看见，
    照样返回默认的第一页。于是「搜到了 0 条」和「搜错了字段」长得一模一样。
    （{@code fixture-sku.py} 里传的就是 {@code keyword=}，它靠「列表按 id 倒序、
    新建的永远在第一页」侥幸对了。这里不靠侥幸。）
    """
    keyword = urllib.parse.quote("ZZ-价格夹具")
    st, r = call("GET", f"/admin/products?name={keyword}&pageNum=1&pageSize=50",
                 token=token)
    for row in (r.get("data") or {}).get("list", []):
        if row["name"] in (SINGLE_NAME, MULTI_NAME):
            print(f"发现上一次没清干净的 {row['id']}（{row['name']}），先删掉")
            call("DELETE", f"/admin/products/{row['id']}", token=token)


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--cleanup":
        # ★ 支持一次清多件：这一轮的夹具建的是【两件】商品，
        #   一次一条命令清完，比让用户自己记着跑两遍可靠。
        ids = sys.argv[2:]
        token = login()
        for pid in ids:
            st, r = call("DELETE", f"/admin/products/{pid}", token=token)
            print(f"删除商品 {pid} → HTTP {st} / {r}")
        return

    token = login()
    wipe_same_name(token)

    # 分类随便取第一个（和 fixture-sku.py / test-sku.py 一样）。
    # ★ 用 /shop/categories 而不是管理端的树：这里只需要一个合法的 id，
    #   而这两个夹具商品不该依赖分类树长什么样。
    st, r = call("GET", "/shop/categories")
    cid = r["data"][0]["id"]

    created = []

    # ---- ① 单规格 ----
    st, r = call("POST", "/admin/products", {
        "categoryId": cid,
        "name": SINGLE_NAME,
        "cover": SINGLE["cover"],
        "description": SINGLE["description"],
        "status": 1,
        "specSchema": [],
        "skus": [{
            "specs": [],
            "price": SINGLE["price"],
            "marketPrice": SINGLE["marketPrice"],
            "costPrice": SINGLE["costPrice"],
            "stock": SINGLE["stock"],
        }],
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"① 建商品失败：HTTP {st} / {r}")
    pid1 = r["data"]
    created.append(pid1)
    print(f"① {SINGLE_NAME}  id = {pid1}")
    print(f"   商城详情  http://localhost:5174/product/{pid1}")
    print(f"   要看：¥{SINGLE['price']:.2f} 旁边有一条划掉的 "
          f"¥{SINGLE['marketPrice']:.2f}（卡片和详情页都有）")

    # ---- ② 多规格 ----
    st, r = call("POST", "/admin/products", {
        "categoryId": cid,
        "name": MULTI_NAME,
        "cover": MULTI["cover"],
        "description": MULTI["description"],
        "status": 1,
        "specSchema": [{"name": "颜色", "values": [row[0] for row in MULTI["rows"]]}],
        "skus": [{
            "specs": [{"name": "颜色", "value": row[0]}],
            "price": row[1],
            "marketPrice": row[2],
            "costPrice": row[3],
            "stock": row[4],
        } for row in MULTI["rows"]],
    }, token=token)
    if r.get("code") != 200:
        raise SystemExit(f"② 建商品失败：HTTP {st} / {r}")
    pid2 = r["data"]
    created.append(pid2)
    print(f"② {MULTI_NAME}  id = {pid2}")
    print(f"   商城详情  http://localhost:5174/product/{pid2}")
    print(f"   要看：先是一段区间（没有删除线 —— 多规格的原价没有唯一答案），"
          f"选中某个颜色之后才出现那个颜色【自己的】删除线")

    # ---- 复核：把库里真实存下来、以及后端算出来的数打出来 ----
    #
    # ★ 这一段是「不信自己刚才发的东西」：接口返回 200 不等于存对了，
    #   而下面这些数字正是等下要在浏览器里对上眼的那几个。
    #   毛利率那一列取的是【后端算的】grossMarginPercent（AdminSkuVO），
    #   弹窗里显示的是前端现算的预览 —— 两边到这里应该一样，
    #   不一样就说明两份实现的舍入已经分岔了。
    print()
    print("库里现在是这样（等下浏览器里要看到的就是这几个数）：")
    for pid in created:
        st, r = call("GET", f"/admin/products/{pid}", token=token)
        d = r["data"]
        print(f"  商品 {pid}  {d['name']}"
              f"   minPrice={money(d.get('minPrice'))}"
              f" maxPrice={money(d.get('maxPrice'))}")
        for sku in d.get("skus", []):
            print(f"    sku {sku['id']:>5}  {sku['specText']:<12}"
                  f" 售价 {money(sku.get('price')):>7}"
                  f"  划线 {money(sku.get('marketPrice')):>7}"
                  f"  成本 {money(sku.get('costPrice')):>7}"
                  f"  毛利率 {money(sku.get('grossMarginPercent')):>7}")

    print()
    print("验完请清掉（★ 只删这两件，不用通配符）：")
    print(f"  /d/python/python.exe tools/fixture-price.py --cleanup "
          f"{' '.join(str(p) for p in created)}")


if __name__ == "__main__":
    main()
