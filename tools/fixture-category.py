# -*- coding: utf-8 -*-
"""
两级分类的【浏览器验收夹具】（里程碑 16 起）。

<h3>★ 为什么需要它</h3>

<p>里程碑 16 的验收条件里有一条只能在浏览器里看：
「商城页点父分类，挂在子分类下的商品也在」「管理端表格是树、编辑弹窗里
上级分类只有一级分类能选」。而<b>线上库里一个父子关系都没有</b>——
6 个种子分类的 parent_id 全是 0，100 件商品一件都没挂在二级分类下。
<b>这条规则在真实数据上根本没法看。</b>

<p>★ 为什么不像计划里说的那样「把 3 件已存在的商品改挂过去，验完改回来」：
那要<b>修改用户的真实商品</b>，一旦中途失败，商品就留在错的分组里，
而"改回来"这一步没有任何东西保证它会执行（它不是事务）。
造 3 件自己的商品 + 一条 `--cleanup`，代价是三条 INSERT，
换来的是「失败时留下的脏数据是可以整个删掉的」。<b>能验的东西一样。</b>

<h3>★ 它造出来的东西</h3>

<pre>
  ZZ-夹具-父分类                      （一级，parent_id = 0）
   ├─ 商品「ZZ-夹具-商品-挂在父级」    ← 直接挂在一级分类上
   └─ ZZ-夹具-子分类                  （二级，parent_id = 父分类）
       ├─ 商品「ZZ-夹具-商品-挂在子级」
       └─ 商品「ZZ-夹具-商品-挂在子级2」
</pre>

<p><b>这是这套数据的全部意义</b>：筛「ZZ-夹具-父分类」必须返回
<b>3 件</b>（1 件直接的 + 2 件来自子分类），筛子分类必须返回 2 件。
数字对不上，就说明"含后代"没生效 —— 而且不是"报错"，是<b>少了两件</b>。

<h3>用法</h3>

<pre>
  python tools/fixture-category.py            # 建，打印 id 和两个页面的地址
  python tools/fixture-category.py --cleanup  # 按记录下的精确 id 删干净
</pre>

<h3>★★ 反通配符清理原则</h3>

<p>清理<b>只删 {@code tools/.fixture-category.json} 里记着的那些 id</b>，
绝不按名字前缀扫、绝不清空任何东西。理由见 {@code tools/fixture-pay.py}
开头那段：线上库里有用户手工录入的数据，一个 LIKE 'ZZ-%' 就能把它们一起带走。

<p>★ 而且每建一样东西就<b>立刻落一次盘</b>（不是全部建完才写）——
这是 {@code fixture-pay.py} 第一版踩过的坑：中途崩掉之后，
已经建出来的东西再也找不到 id，只能手工按名字删。
<b>失败时留下的应该是「可清理的脏数据」。</b>
"""

import json
import os
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"

# 状态文件：记着这次建了哪些 id。★ 只记 id，不记名字 ——
# 名字是给人看的，id 才是删的时候唯一可靠的东西。
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fixture-category.json")

PARENT_NAME = "ZZ-夹具-父分类"
CHILD_NAME = "ZZ-夹具-子分类"
# 三件商品的名字刻意带上挂在哪一层 —— 页面上截图时一眼能对上
P_PARENT = "ZZ-夹具-商品-挂在父级"
P_CHILD1 = "ZZ-夹具-商品-挂在子级"
P_CHILD2 = "ZZ-夹具-商品-挂在子级2"


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


class State:
    """边建边落盘的记录。每加一样东西就 save 一次。"""

    def __init__(self):
        self.data = {"categories": [], "products": []}

    def save(self):
        with open(STATE, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)

    def add_category(self, cid):
        self.data["categories"].append(cid)
        self.save()

    def add_product(self, pid):
        self.data["products"].append(pid)
        self.save()


def load_state():
    if not os.path.exists(STATE):
        return None
    with open(STATE, encoding="utf-8") as f:
        return json.load(f)


def login():
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    if r is None or "data" not in r or not r["data"]:
        raise SystemExit(f"登录失败：HTTP {st} / {r}")
    return r["data"]["token"]


def cleanup(token):
    saved = load_state()
    if not saved:
        print("没有记录到任何东西（.fixture-category.json 不存在或是空的），" "没有要删的。")
        return 0

    failed = 0

    # ★ 顺序不能反：先商品，再子分类，最后父分类。
    #   全库零外键，所以数据库【不会】替你纠正顺序 ——
    #   删错了不报错，只会留下一批 parent_id 指向不存在分类的子分类，
    #   而它们会从商城导航里凭空消失（最难查的一类：数据没坏、页面少东西）。
    #
    #   categories 是按【创建顺序】记的：父先、子后。
    #   所以倒着删就是子先、父后。
    print("删商品：")
    for pid in reversed(saved.get("products", [])):
        st, r = call("DELETE", f"/admin/products/{pid}", token=token)
        ok = (r or {}).get("code") == 200
        print(f"  商品 {pid} → {'OK' if ok else f'失败 HTTP {st} / {r}'}")
        failed += 0 if ok else 1

    print("删分类（倒序：子先父后）：")
    for cid in reversed(saved.get("categories", [])):
        st, r = call("DELETE", f"/admin/categories/{cid}", token=token)
        ok = (r or {}).get("code") == 200
        print(f"  分类 {cid} → {'OK' if ok else f'失败 HTTP {st} / {r}'}")
        failed += 0 if ok else 1

    if failed == 0:
        # ★ 只有全部成功才删掉状态文件。
        #   留着一份"删失败了"的记录，用户才有机会重试 ——
        #   这正是别的夹具脚本踩过的坑的另一面：记录是失败后唯一的线索。
        os.remove(STATE)
        print("状态文件已删除，清理完成。")
    else:
        print(f"⚠️ {failed} 项删除失败，.fixture-category.json 保留着 —— 修好之后重跑 --cleanup。")
    return failed


def main():
    token = login()

    if len(sys.argv) > 1 and sys.argv[1] == "--cleanup":
        sys.exit(1 if cleanup(token) else 0)

    # ★ 有残留就直接拒绝，不"顺手清掉再建"。
    #   静默删东西是最不该有的行为；而且这份残留很可能正是用户
    #   上一次【正在看】的那套数据，删了他就白看了。
    if load_state():
        raise SystemExit(
            f"发现上次的记录 {STATE}，说明上一次没清理。\n"
            f"先跑 python tools/fixture-category.py --cleanup，再重新建。"
        )

    state = State()
    # 先落一个空盘：这样即使第一件东西建完就崩，文件也存在（可读、可清）。
    state.save()

    # ---- 一级分类 ----
    st, r = call("POST", "/admin/categories",
                 {"name": PARENT_NAME, "sort": 9700, "status": 1, "parentId": 0}, token=token)
    if (r or {}).get("code") != 200:
        raise SystemExit(f"建父分类失败：HTTP {st} / {r}")
    parent_id = r["data"]
    state.add_category(parent_id)

    # ---- 二级分类（挂在上面那个下面）----
    st, r = call("POST", "/admin/categories",
                 {"name": CHILD_NAME, "sort": 9710, "status": 1, "parentId": parent_id}, token=token)
    if (r or {}).get("code") != 200:
        raise SystemExit(f"建子分类失败：HTTP {st} / {r}")
    child_id = r["data"]
    state.add_category(child_id)

    # ---- 三件商品，价格递增（页面上按价格排能一眼看出顺序）----
    def make_product(name, category_id, price):
        st, r = call("POST", "/admin/products", {
            "categoryId": category_id,
            "name": name,
            "cover": "/images/jacket-01.svg",
            "description": "里程碑 16 浏览器验收用的临时商品（验完 --cleanup 删掉）",
            "status": 1,
            "specSchema": [],
            "skus": [{"specs": [], "price": price, "stock": 9}],
        }, token=token)
        if (r or {}).get("code") != 200:
            raise SystemExit(f"建商品 {name} 失败：HTTP {st} / {r}")
        state.add_product(r["data"])
        return r["data"]

    pid1 = make_product(P_PARENT, parent_id, "11.10")
    pid2 = make_product(P_CHILD1, child_id, "22.20")
    pid3 = make_product(P_CHILD2, child_id, "33.30")

    print()
    print("=" * 68)
    print("夹具已建好。")
    print("=" * 68)
    print(f"  父分类 {parent_id}  {PARENT_NAME}")
    print(f"   └ 子分类 {child_id}  {CHILD_NAME}")
    print(f"  商品  {pid1}  {P_PARENT}   ¥11.10  （挂在父分类上）")
    print(f"  商品  {pid2}  {P_CHILD1}   ¥22.20  （挂在子分类上）")
    print(f"  商品  {pid3}  {P_CHILD2}   ¥33.30  （挂在子分类上）")
    print()
    print("★ 要看的现象（数字对不上就是「含后代」没生效）：")
    print(f"   商城页点父分类  → 应该看到【3 件】")
    print(f"   商城页点子分类  → 应该看到【2 件】")
    print()
    print("★ 管理端分类页应该是一棵树（父分类下面挂着子分类）：")
    print("   http://localhost:5173/category")
    print()
    print("★ 商城页的地址（两个 id 分别试一次）：")
    print(f"   http://localhost:5174/?categoryId={parent_id}   ← 3 件")
    print(f"   http://localhost:5174/?categoryId={child_id}    ← 2 件")
    print()
    print("验完记得：python tools/fixture-category.py --cleanup")


if __name__ == "__main__":
    main()
