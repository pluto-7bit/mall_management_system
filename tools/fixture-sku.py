# -*- coding: utf-8 -*-
"""
多规格商品的【浏览器验收夹具】（里程碑 15 起）。

<h3>★ 为什么需要它</h3>

<p>里程碑 15 的验收条件里有一条是浏览器里的：
「能选规格、价格和库存跟着变」「两个规格加购之后购物车里必须是两行」。
而库里现有的 100 件商品 {@code spec_schema} 全是 NULL ——
<b>一件都看不到规格选择器</b>，那些条件在真实数据上没法验。

<p>而且这个夹具<b>不能用真的下单流程去造</b>：验收要反复重来，
每来一次就多一件商品、多几条订单，最后得手工清。
所以它是「一条命令造出来、一条命令删干净」的。

<p>做法和 {@code tools/fixture-pay.py} 一样：<b>只碰自己建的那一个商品</b>。

<h3>★ 它造出来的那件商品</h3>

<pre>
  颜色 × 尺码，四档价格 10 / 20 / 30 / 40
  黑/S 库存 5、黑/M 库存 2、白/S 库存 5、白/M 库存 0
</pre>

<p>库存是故意排成这样的，每一档对应一个要看的现象：
<pre>
  白/M = 0  → 「该规格暂时缺货」+ 三个控件全禁用（缺货 ≠ 从列表里消失）
  黑/M = 2  → 换规格之后数量要夹回新规格的库存
              （在 5 件那档加到 5，再切到 2 件这档 → 必须变成 2）
  四档不等价 → 价格区在没选规格时显示「¥10.00 起」，选中后不显示「起」
</pre>

<h3>用法</h3>

<pre>
  python tools/fixture-sku.py              # 建，打印商品 id、规格 id、详情页地址
  python tools/fixture-sku.py --cleanup 1033
</pre>

<p>⚠️ 商品名是固定的，所以「建之前先删掉同名的那一个」只会删掉上一次
没清干净的自己。<b>不用 LIKE 通配符去扫</b> —— 那会删到别人的东西。
"""

import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"
NAME = "ZZ-SKU夹具-多规格T恤"

# 颜色 × 尺码，四档价格。库存是【故意】排成这样的：
#   黑/S = 5、黑/M = 2、白/S = 5、白/M = 0
#   白/M 的 0  → 验「该规格暂时缺货」和三个控件的禁用
#   黑/M 的 2  → 验「换规格之后数量要夹回新规格的库存」
#                （在 5 件那一档把数量加到 5，再切到 2 件这一档）
COLORS = ["黑", "白"]
SIZES = ["S", "M"]
TIER = [10.00, 20.00, 30.00, 40.00]
STOCKS = [5, 2, 5, 0]


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


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--cleanup":
        pid = sys.argv[2]
        st, r = call("POST", "/admin/auth/login",
                     {"username": "admin", "password": "123456"})
        token = r["data"]["token"]
        st, r = call("DELETE", f"/admin/products/{pid}", token=token)
        print(f"删除商品 {pid} → HTTP {st} / {r}")
        return

    st, r = call("POST", "/admin/auth/login",
                 {"username": "admin", "password": "123456"})
    token = r["data"]["token"]

    # 商品名固定，所以「先删旧的同名」是安全的 —— 它只可能删掉上一次
    # 没清干净的自己。★ 不用 LIKE 通配符去扫别的名字。
    st, r = call("GET", "/admin/products?keyword=&pageNum=1&pageSize=50", token=token)
    for row in (r.get("data") or {}).get("list", []):
        if row["name"] == NAME:
            print(f"发现上一次没清干净的 {row['id']}，先删掉")
            call("DELETE", f"/admin/products/{row['id']}", token=token)

    # 分类随便取第一个（和 test-sku.py 的 admin_login 一样）
    st, r = call("GET", "/shop/categories")
    cid = r["data"][0]["id"]

    combos = []
    i = 0
    for c in COLORS:
        for s in SIZES:
            combos.append({
                "specs": [{"name": "颜色", "value": c}, {"name": "尺码", "value": s}],
                "price": TIER[i],
                "stock": STOCKS[i],
            })
            i += 1

    st, r = call("POST", "/admin/products", {
        "categoryId": cid,
        "name": NAME,
        "cover": "/images/jacket-03.svg",
        "description": "阶段 3 浏览器验收用的临时商品（选规格 → 价格和库存要跟着变）",
        "status": 1,
        "specSchema": [{"name": "颜色", "values": COLORS},
                       {"name": "尺码", "values": SIZES}],
        "skus": combos,
    }, token=token)
    print(f"建商品 → HTTP {st} / code={r.get('code')}")
    if r.get("code") != 200:
        raise SystemExit(f"失败：{r}")
    pid = r["data"]
    print(f"商品 id = {pid}")
    print(f"详情页   http://localhost:5174/product/{pid}")

    st, r = call("GET", f"/admin/products/{pid}", token=token)
    for sku in (r["data"] or {}).get("skus", []):
        print(f"  sku {sku['id']}  {sku['specText']:<18} ¥{sku['price']}  库存 {sku['stock']}")


if __name__ == "__main__":
    main()
