# -*- coding: utf-8 -*-
"""
分类模块的端到端接口测试（分类管理 + 分类树 + 层级规则 + 含后代筛选）。

用 Python 而不是 curl + shell，原因是两个 Windows 上很烦的问题：
  1. 中文编码：Git Bash 会把命令行里的中文转成 GBK 发出去，
     后端按 UTF-8 解析就报 "Invalid UTF-8 start byte"
  2. JSON 断言：curl 只想把响应打出来，判断对错得靠人眼看；
     Python 可以直接 assert，跑完就告诉你哪里不对

★ 设计原则：这个脚本跑在**会变的开发库**上（你随时可能在浏览器里
   加几条数据），所以：
     - 不写死「总数是 4」这类绝对值，全部基于开局快照做相对判断
     - 需要前置数据的用例自己造、自己清，不依赖库里已有的记录
   写死绝对值的话，你手动加一条分类测试就会红一片，
   然后你就开始不信任这个测试了 —— 那它就没用了。

<h3>★★ 里程碑 16 改了什么：分页断言换成树断言（不是删掉）</h3>

管理端分类列表从「分页的扁平表格」变成了「一棵树」——
{@code GET /api/admin/categories} 的返回类型从 {@code PageResult<Category>}
变成了 {@code List<CategoryTreeVO>}。于是原来那一整个「分页与搜索」的小节
（总数 / 第 1 页 2 条 / 总页数 / 两页不重复 / 超范围页码空 / pageSize 规范化）
在这里<b>全部失去了意义</b>。

但「换掉」不等于「删掉」。分页那一节真正在守的东西是
**「列表这个视图的形状是对的」**，而树也需要有人守同样的东西，
只是换成了树的形状：
    · 全节点数 = 数据库里的行数（不多不少，没有节点被静默丢掉）
    · 兄弟之间按 sort ASC, id ASC
    · 先父后子，且同一个父的子节点在序列里是连续的
    · 每个非根节点的父节点都能在树里找到
    · 没有环（靠「节点数对得上」来体现，因为成环的节点会被丢弃）
这三条是「一条会红的检查」，而「删掉那六条」是一条不会红的空白。

★ 新增的两节是这个里程碑真正的新东西：
    第 4 节 —— 五条层级规则（1009 / 1010）
    第 6 节 —— 「按父分类筛商品要含后代」，而且<b>两个端各断言一次</b>
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

# Windows 的控制台默认用 GBK，直接 print 中文会变成乱码。
# 显式把标准输出改成 UTF-8。如果还是乱码，在命令行里执行：chcp 65001
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:8080/api"
TIMEOUT = 10

passed = 0
failed = 0

# 测试期间创建的资源，最后统一清理（万一中途 assert 挂了也不留垃圾）
cleanup_categories = []
cleanup_products = []


# 登录后拿到 token 存这里，由 call() 自动带上。
#
# ★ 里程碑 4 给 /api/admin/** 全都加上了登录校验，
#   所以这个脚本不能再匿名调接口了 —— 必须先登录换一个 token。
#   这里用模块级变量而不是给每个调用点加参数，是为了让
#   「加鉴权」这件事不污染测试用例本身：用例关心的是业务规则，
#   不该每行都写一遍 token 怎么传。
TOKEN = None


def login():
    """用测试账号换一个 token。换不到就直接退出，别让后面刷一屏 401。"""
    global TOKEN
    st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
    TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not TOKEN:
        raise SystemExit(f"登录失败，后续用例无法进行：HTTP {st} / {r}")
    print(f"登录成功，已获取 token（{len(TOKEN)} 字符）")


def call(method, path, body=None):
    """发一个请求，返回 (http_status, json_body)。"""
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"

    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def check(label, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  [OK]   {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}")
        if detail:
            print(f"         {detail}")


def section(title):
    print(f"\n=== {title} ===")


def q(s):
    """把中文查询参数编码进 URL（直接拼中文会被 urllib 拒掉）"""
    return urllib.parse.quote(s)


def make_category(name, sort, status=1, parent_id=None):
    """建一个分类并登记待清理，返回它的 id。"""
    body = {"name": name, "sort": sort, "status": status}
    if parent_id is not None:
        body["parentId"] = parent_id
    st, r = call("POST", "/admin/categories", body)
    cid = r.get("data")
    if isinstance(cid, int):
        cleanup_categories.append(cid)
    return cid


def get_tree(query=""):
    """拉一次分类树，返回 (http_status, 树)。

    ★ 注意返回的是【嵌套结构】：一级分类的数组，子分类在各自的 children 里。
      下面所有断言都基于 flatten() 展开后的结果做，但树本身的结构性质
      （先父后子、子节点连续）必须直接在嵌套结构上验，展开之后就看不出来了。
    """
    st, r = call("GET", f"/admin/categories{query}")
    data = r.get("data")
    return st, (data if isinstance(data, list) else []), r


def flatten(nodes):
    """把树按【先父后子】的顺序展开成一个扁平列表（深度优先，前序）。

    ★ 这个顺序本身就是被断言的对象之一 —— 所以它不能写成
      「先 collect 所有节点再 sort」，那样就等于把要验的东西
      在验证之前自己抹平了。见第 5 节。
    """
    out = []
    for n in nodes:
        out.append(n)
        kids = n.get("children")
        if kids:
            out.extend(flatten(kids))
    return out


def find_node(nodes, cid):
    """在树里找某个 id 的节点，找不到返回 None"""
    for n in flatten(nodes):
        if n["id"] == cid:
            return n
    return None


def tree_url(fragment):
    """给树接口拼一个带查询参数的 URL（参数名固定为 name/status）"""
    return "/admin/categories?" + fragment


# ---------------------------------------------------------------------------
section("0. 登录 + 清理上次残留 + 开局快照")
# ---------------------------------------------------------------------------

# 必须先登录 —— 下面每一个请求都要靠它拿到的 token 才能通过拦截器
login()


# 万一上一次跑到一半挂了，库里会留下名字以「自检」开头的垃圾数据。
# 先按名字前缀清一遍，保证脚本可以反复运行 ——
# 测试脚本必须能被重复执行，否则第二次跑就红一片，你就不信它了。
#
# ★ 里程碑 16 起清理顺序变成了【关键】：
#   有子分类的分类删不掉（1011 会拦住），所以必须先删子、后删父。
#   做法是把树按先父后子的顺序展开，然后【倒着删】——
#   倒过来的先父后子正好是先子后父。这不是碰巧，是深度优先前序的性质。
st, tree, _ = get_tree()
stale = [c for c in flatten(tree) if c["name"].startswith("自检")]
for c in reversed(stale):
    # 分类下可能还挂着商品，先清商品（否则分类删不掉，1004）
    st, pr = call("GET", f"/admin/products?categoryId={c['id']}&pageSize=100")
    for p in pr.get("data", {}).get("list", []):
        call("DELETE", f"/admin/products/{p['id']}")
    call("DELETE", f"/admin/categories/{c['id']}")
if stale:
    print(f"         清掉了 {len(stale)} 条上次的残留数据")

st, tree, raw = get_tree()
check("分类树可访问", raw.get("code") == 200, raw)
baseline_nodes = len(flatten(tree))
baseline_roots = len(tree)

st, r = call("GET", "/admin/categories/options")
baseline_options = len(r.get("data", []))

print(f"         当前库里有 {baseline_nodes} 个分类（{baseline_roots} 个一级），"
      f"其中启用的 {baseline_options} 个")
print("         （这些是你自己的数据，脚本不会动它们）")

# ---------------------------------------------------------------------------
section("1. 分类：读接口（扁平出口没有被树化）")
# ---------------------------------------------------------------------------

st, r = call("GET", "/admin/categories/options")
opts = r.get("data", [])
check("下拉框数量与快照一致", len(opts) == baseline_options, f"{len(opts)} vs {baseline_options}")
check("下拉框只含启用分类", all(o["status"] == 1 for o in opts), [o for o in opts if o["status"] != 1])
check("下拉框按 sort 升序", [o["sort"] for o in opts] == sorted(o["sort"] for o in opts), opts)

# ★ 这一条是本轮最容易被顺手改坏的地方：
#   /options 必须【保持扁平】。商品表单用的是 el-select，
#   它拿到嵌套数据不会报错 —— 它会把每个一级分类渲染成一个空白选项，
#   下拉框还在、还能点，就是不显示分类名。
#   所以在这里钉住「它返回的每一个元素都没有 children 键」。
check("★ /options 仍然是扁平数组（没有 children 键）",
      all("children" not in o for o in opts),
      [o["name"] for o in opts if "children" in o])
check("★ /options 带 parentId（前端靠它缩进）",
      all("parentId" in o for o in opts),
      [o["name"] for o in opts if "parentId" not in o])

# /options 是固定路径，必须优先于 /{id}，否则会被当成 id="options" 而报错
check("固定路径 /options 未被 /{id} 抢走", isinstance(opts, list), r)

st, r = call("GET", "/admin/categories/999999")
check("查不存在的分类返回 1003", r.get("code") == 1003, r)

# 商城端的分类接口【也】必须保持扁平 —— 理由不同但同样是静默失败：
# Home.vue 的 banner 是 list.find(x => x.name === ...)，find 在嵌套结构上
# 找不到就是 undefined，banner 悄悄退回首页。
st, sr = call("GET", "/shop/categories")
shop_cats = sr.get("data", [])
check("★ 商城端分类仍然是扁平数组", isinstance(shop_cats, list), sr)
check("★ 商城端每个分类都带 parentId",
      all("parentId" in c for c in shop_cats), sr)
check("★ 商城端只返回启用分类", all(c["status"] == 1 for c in shop_cats), sr)

# ---------------------------------------------------------------------------
section("2. 分类：新增（含 parentId 的两条归一规则）")
# ---------------------------------------------------------------------------

new_id = make_category("自检测试分类", 9901)
check("新增成功且返回 id", isinstance(new_id, int), new_id)

st, r = call("GET", f"/admin/categories/{new_id}")
d = r.get("data", {})
check("新增后能查到", d.get("id") == new_id, d)
check("status 默认为启用", d.get("status") == 1, d)
check("createTime 由数据库自动填充", d.get("createTime") is not None, d)

# ★ 归一规则之一：不传 parentId = 一级分类，而且必须是【0，不是 null】。
#   这一条钉的是「刻意不用 NULL」那个决定（见 migration-14 头部）：
#   如果哪天有人把它改成可空，前端就要同时处理 null 和 0 两种「没有父」，
#   而两者在 JS 里都是假值 —— 于是没人会注意到，直到某个 filter 写错。
check("★ 不传 parentId 时是 0（不是 null）",
      d.get("parentId") == 0 and d.get("parentId") is not None, d)

explicit_root = make_category("自检-显式一级分类", 9902, parent_id=0)
st, r = call("GET", f"/admin/categories/{explicit_root}")
check("★ 显式传 parentId=0 也是 0（两种写法归一成同一个值）",
      r.get("data", {}).get("parentId") == 0, r)

st, r = call("POST", "/admin/categories", {"name": "自检测试分类", "sort": 9903, "status": 1})
check("重名被应用层拒绝（1005）", r.get("code") == 1005, r)
check("错误信息里点了名", "自检测试分类" in str(r.get("message", "")), r)

# 每个用例只违反一条约束 —— 一次违反多条时后端只返回第一条，
# 而「哪条算第一条」由字段顺序决定、不可靠，断言会时对时错
st, r = call("POST", "/admin/categories", {"name": "", "sort": 0, "status": 1})
check("空名称被拦住（400）", r.get("code") == 400, r)
check("提示说的是「名称」", "名称" in str(r.get("message", "")), r)

st, r = call("POST", "/admin/categories", {"name": "负数排序", "sort": -5, "status": 1})
check("负数排序被拦住（400）", r.get("code") == 400, r)

st, r = call("POST", "/admin/categories", {"name": "超长名称" + "啊" * 60, "sort": 0, "status": 1})
check("超长名称被拦住（400）", r.get("code") == 400, r)

st, r = call("POST", "/admin/categories", {"name": "缺排序值"})
check("缺 sort 被拦住（400）", r.get("code") == 400, r)

# 名称前后带空格：Service 里做了 trim
trimmed_id = make_category("  自检-首尾有空格  ", 9904)
st, r = call("GET", f"/admin/categories/{trimmed_id}")
check("名称前后空格被 trim 掉", r.get("data", {}).get("name") == "自检-首尾有空格", r)

# 禁用状态的分类不该出现在下拉框，但管理页要能看到
disabled_id = make_category("自检待禁用分类", 9905, status=0)
st, r = call("GET", "/admin/categories/options")
check("禁用的分类不出现在下拉框", all(o["id"] != disabled_id for o in r.get("data", [])), r)

st, tree, raw = get_tree("?status=0")
check("管理页按 status=0 能筛到它",
      any(c["id"] == disabled_id for c in flatten(tree)), raw)

# ---------------------------------------------------------------------------
section("3. 分类：修改")
# ---------------------------------------------------------------------------

st, r = call("PUT", f"/admin/categories/{new_id}",
             {"name": "自检测试分类改名了", "sort": 88, "status": 0})
check("修改成功", r.get("code") == 200, r)

st, r = call("GET", f"/admin/categories/{new_id}")
d = r.get("data", {})
check("名称已更新", d.get("name") == "自检测试分类改名了", d)
check("排序已更新", d.get("sort") == 88, d)
check("状态已更新为禁用", d.get("status") == 0, d)
# 这里刻意不断言 updateTime != createTime：
# DATETIME 只精确到秒，同一秒内创建又修改的话两者相等，
# 断言会偶发失败。写测试时要避免这种「依赖时间精度」的判断。

# 把名字改成自己原来的名字，不该被判成重名
st, r = call("PUT", f"/admin/categories/{new_id}",
             {"name": "自检测试分类改名了", "sort": 88, "status": 0})
check("改成同名（自己）不算重复", r.get("code") == 200, r)

st, r = call("PUT", "/admin/categories/999999", {"name": "不存在", "sort": 1, "status": 1})
check("修改不存在的分类返回 1003", r.get("code") == 1003, r)

# ★ 归一规则之二：PUT 不传 parentId = 「把它变成一级分类」（全量替换）。
#   这一条不是随口定的：parent_id 是 NOT NULL DEFAULT 0，
#   「没有父」在这张表里是一个确定的值，不是一种缺席。
#   所以前端表单必须带上这个字段 —— 这里就是在钉这个约定。
st, r = call("PUT", f"/admin/categories/{trimmed_id}",
             {"name": "自检-首尾有空格", "sort": 9904, "status": 1})
st, r = call("GET", f"/admin/categories/{trimmed_id}")
check("★ PUT 不传 parentId = 一级分类（0，不是 null）",
      r.get("data", {}).get("parentId") == 0, r)

# ---------------------------------------------------------------------------
section("4. ★★ 层级规则：五条（1009 / 1010）")
# ---------------------------------------------------------------------------

# 造一对父子，本节的规则和后面的树断言都用它
tree_parent = make_category("自检-树-父", 9910)
tree_child = make_category("自检-树-子", 9911, parent_id=tree_parent)
check("把分类挂到另一个分类下面是允许的", isinstance(tree_child, int), tree_child)

st, r = call("GET", f"/admin/categories/{tree_child}")
check("子分类的 parentId 指向父分类",
      r.get("data", {}).get("parentId") == tree_parent, r)

# 规则 1：不能挂到自己下面
st, r = call("PUT", f"/admin/categories/{tree_parent}",
             {"name": "自检-树-父", "sort": 9910, "status": 1, "parentId": tree_parent})
check("规则1 自己挂自己 → 1009", r.get("code") == 1009, r)

# 规则 2：上级必须存在
st, r = call("POST", "/admin/categories",
             {"name": "悬挂到不存在的分类", "sort": 9912, "status": 1, "parentId": 999999})
check("规则2 上级不存在 → 1010", r.get("code") == 1010, r)
check("规则2 提示说「不存在」", "不存在" in str(r.get("message", "")), r)

# 规则 3：上级必须启用
st, r = call("POST", "/admin/categories",
             {"name": "悬挂到禁用的分类", "sort": 9913, "status": 1, "parentId": disabled_id})
check("规则3 上级被禁用 → 1010", r.get("code") == 1010, r)
check("规则3 提示里点了上级的名字",
      "自检待禁用分类" in str(r.get("message", "")), r)

# 规则 4：上级自己必须是根 —— 这一条挡住三级
st, r = call("POST", "/admin/categories",
             {"name": "三级分类", "sort": 9914, "status": 1, "parentId": tree_child})
check("规则4 往二级分类下面再挂 → 1009（挡住三级）", r.get("code") == 1009, r)
check("规则4 提示说「最多两级」", "两级" in str(r.get("message", "")), r)

# 规则 5：有子分类的分类不许再挂到别人下面 —— 这一条挡住环
st, r = call("PUT", f"/admin/categories/{tree_parent}",
             {"name": "自检-树-父", "sort": 9910, "status": 1, "parentId": new_id})
check("规则5 有子分类的分类改挂别人 → 1009（挡住环）", r.get("code") == 1009, r)
check("规则5 提示说「还有子分类」", "子分类" in str(r.get("message", "")), r)

# ★ 被拒绝之后状态必须没变 —— 「拒绝了但已经改了一半」是最糟的结果
st, r = call("GET", f"/admin/categories/{tree_parent}")
check("★ 被拒绝后父分类仍然是一级分类",
      r.get("data", {}).get("parentId") == 0, r)

st, r = call("GET", f"/admin/categories/{tree_child}")
check("★ 被拒绝后子分类仍然挂在原来的父下面",
      r.get("data", {}).get("parentId") == tree_parent, r)

# 合法的「降级」要允许：把子分类提升成一级分类
st, r = call("PUT", f"/admin/categories/{tree_child}",
             {"name": "自检-树-子", "sort": 9911, "status": 1, "parentId": 0})
check("规则允许把子分类提升为一级分类", r.get("code") == 200, r)
st, r = call("PUT", f"/admin/categories/{tree_child}",
             {"name": "自检-树-子", "sort": 9911, "status": 1, "parentId": tree_parent})
check("……再挂回去也允许", r.get("code") == 200, r)

# ---------------------------------------------------------------------------
section("5. ★★ 树的形状（替换掉里程碑 15 那一整节分页断言）")
# ---------------------------------------------------------------------------

# 此时还活着的本次新增分类：new_id / explicit_root / trimmed_id /
# disabled_id / tree_parent / tree_child
expected_nodes = baseline_nodes + 6

st, tree, raw = get_tree()
check("接口返回的是数组（不再有 pageNum/total/pages 这层壳）",
      isinstance(raw.get("data"), list), raw)
flat = flatten(tree)

check("★ 全节点数 = 快照 + 本次新增的 6 个（没有节点被静默丢掉）",
      len(flat) == expected_nodes,
      f"期望 {expected_nodes}，实际 {len(flat)}")

check("★ 每个节点只出现一次（成环或重复挂载会让某个节点出现两次）",
      len({c["id"] for c in flat}) == len(flat),
      f"节点 {len(flat)} 个，distinct id {len({c['id'] for c in flat})} 个")

# ★ 没有环：环上的节点既不是根、也不是任何根的后代，会被实现丢弃 ——
#   所以「节点数对得上」这一条同时也证明了没有环。
#   下面这条是它的正面表述，红了能直接指出是哪个节点自己指向自己。
check("★ 没有自己指向自己的分类",
      all(c["parentId"] != c["id"] for c in flat),
      [c["name"] for c in flat if c["parentId"] == c["id"]])

# ★ 每个非根节点的父节点都能在树里找到。
#   找不到就意味着渲染出来的是一棵【凭空的根】——那正是「游离的父」
#   这种脏数据的症状。这条比 SQL 里那句 LEFT JOIN 检查更贴近用户看到的东西。
flat_ids = {c["id"] for c in flat}
orphans = [c["name"] for c in flat if c["parentId"] != 0 and c["parentId"] not in flat_ids]
check("★ 没有游离节点（parentId 指向的节点都在树里）", not orphans, orphans)

# ★ 先父后子：每个子节点在序列里的位置都在它的父节点【之后】
index_of = {c["id"]: i for i, c in enumerate(flat)}
bad_order = [c["name"] for c in flat
             if c["parentId"] != 0 and index_of[c["parentId"]] > index_of[c["id"]]]
check("★ 先父后子（子节点的下标大于父节点）", not bad_order, bad_order)

# ★ 同一个父的子节点在序列里是【连续的】。
#   这一条验的是递归组树的正确性：如果实现是「先平铺再回填 children」，
#   子节点会散落到别处，前端展开父节点时就会看到一串不相干的行。
#   做法：对每个父节点，它在序列里所有子节点的下标必须构成一个连续区间。
positions = {}
for i, c in enumerate(flat):
    positions.setdefault(c["parentId"], []).append(i)
not_contiguous = []
for pid, idx in positions.items():
    if pid == 0 or len(idx) < 2:
        continue
    if idx[-1] - idx[0] + 1 != len(idx):
        not_contiguous.append(pid)
check("★ 同一个父的子节点在序列里连续", not not_contiguous, not_contiguous)

# ★ 兄弟之间按 sort ASC, id ASC —— 和下单时的排序规则必须是同一个
def sorted_ok(kids):
    keys = [(k["sort"], k["id"]) for k in kids]
    return keys == sorted(keys)

bad_siblings = []
for parent in [None] + [c["id"] for c in flat]:
    kids = tree if parent is None else (find_node(tree, parent) or {}).get("children")
    if kids and not sorted_ok(kids):
        bad_siblings.append(parent)
check("★ 每一层的兄弟都按 sort ASC, id ASC", not bad_siblings, bad_siblings)

# 树确实分出了层级（否则上面几条都是在验一个扁平数组）
check("★ 树里确实有带 children 的节点（否则上面几条是空转的）",
      any("children" in c for c in flat), [c["name"] for c in flat][:5])

# 我自己造的那一对父子，位置关系必须对
parent_node = find_node(tree, tree_parent)
child_node = find_node(tree, tree_child)
check("★ 父分类节点出现在顶层", parent_node is not None, type(parent_node))
check("★ 子分类出现在父分类的 children 里",
      parent_node is not None
      and any(k["id"] == tree_child for k in (parent_node.get("children") or [])),
      parent_node)

# 叶子节点没有 children 键（靠 default-property-inclusion: non_null）
check("★ 叶子节点没有 children 键（不是空数组）",
      "children" not in (find_node(tree, tree_child) or {"children": "MISSING"}),
      find_node(tree, tree_child))

# 搜索的语义：命中节点 + 整棵子树 + 祖先链
st, tree_s, raw = get_tree("?name=" + q("树-子"))
hits = flatten(tree_s)
check("按子分类名搜索能命中它", any(c["id"] == tree_child for c in hits), [c["name"] for c in hits])
check("★ 命中子分类时，父分类【也在结果里】（否则它是个凭空的根）",
      any(c["id"] == tree_parent for c in hits), [c["name"] for c in hits])
check("★ 搜到的子分类挂在父分类下面（不是平铺在顶层）",
      any(k["id"] == tree_child
          for k in (find_node(tree_s, tree_parent) or {}).get("children") or []),
      find_node(tree_s, tree_parent))

st, tree_p, raw = get_tree("?name=" + q("自检-树-父"))
check("★ 搜索命中父分类时，整棵子树都在（子分类跟着出现）",
      any(c["id"] == tree_child for c in flatten(tree_p)),
      [c["name"] for c in flatten(tree_p)])

# ★ 分页参数被【静默忽略】：不报错，返回的是全量树。
#   「参数被静默忽略」必须写成断言 —— 靠「看起来没坏」是发现不了的
st, tree_ign, raw = get_tree("?pageNum=1&pageSize=2")
check("★ 传了 pageNum/pageSize 不报错（被静默忽略）", raw.get("code") == 200, raw)
check("★ ……而且返回的是全量树，不是 2 条",
      len(flatten(tree_ign)) == expected_nodes,
      f"期望 {expected_nodes}，实际 {len(flatten(tree_ign))}")

st, tree_neg, raw = get_tree("?pageNum=-1&pageSize=-1")
check("负数页码不再有「规范化」这回事，只是一个被忽略的参数",
      raw.get("code") == 200 and len(flatten(tree_neg)) == expected_nodes, raw)

# ---------------------------------------------------------------------------
section("6. ★★ 按父分类筛商品要【含后代】（管理端和用户端各一次）")
# ---------------------------------------------------------------------------

# 造三个商品，分别挂在三个层级上：
#   · tree_parent 下           —— 直接挂在父分类下
#   · tree_child 下            —— 挂在子分类下（「含后代」要能筛到它）
#   · 一个【后来被禁用的子分类】下 —— 见下面那条最值钱的断言
#
# ★ 第三个为什么要「先建好商品、再禁用分类」，而不能直接建在一个禁用的分类下？
#   因为建商品时如果分类是禁用的，接口会当场拒绝（400「所选分类已被禁用」）——
#   这是对的行为（不该往商城页看不见的分类里挂新商品）。
#   所以真实世界里「禁用的子分类下面有商品」这件事，只能是【先挂后禁】产生的：
#   商家先把商品挂进去，过一阵子把那个子分类关掉。
#   这也正是它危险的地方 —— 关闭的那一刻没有任何提示，
#   而商品从此要么在父分类里消失，要么在导航里变成孤儿。
dis_child = make_category("自检-树-禁用的子", 9915, parent_id=tree_parent)
check("建一个（暂时启用的）子分类", isinstance(dis_child, int), dis_child)

placements = [("自检-商品-挂父", tree_parent),
              ("自检-商品-挂子", tree_child),
              ("自检-商品-挂禁用子", dis_child)]
for pname, cid in placements:
    # 里程碑 15：价格和库存搬到了 product_sku 上，建商品时必须显式给一条「默认 SKU」
    st, r = call("POST", "/admin/products",
                 {"categoryId": cid, "name": pname, "status": 1,
                  "specSchema": [], "skus": [{"specs": [], "price": 1.00, "stock": 1}]})
    pid = r.get("data")
    if isinstance(pid, int):
        cleanup_products.append(pid)
    check(f"建了一个挂在「{pname}」下的商品", isinstance(pid, int), r)

# 现在把那个子分类禁用掉 —— 商品已经挂好了
st, r = call("PUT", f"/admin/categories/{dis_child}",
             {"name": "自检-树-禁用的子", "sort": 9915, "status": 0, "parentId": tree_parent})
check("把那个子分类禁用掉（此时它下面已经有商品了）", r.get("code") == 200, r)

# ★ 禁用只该管住导航：它自己不在商城端导航里了……
st, sr = call("GET", "/shop/categories")
shop_ids = {c["id"] for c in sr.get("data", [])}
check("★ 被禁用的子分类从商城端导航里消失", dis_child not in shop_ids, sorted(shop_ids))
check("★ 但它的父分类还在（只藏了那一个分支）", tree_parent in shop_ids, sorted(shop_ids))

# ★ ……但它下面的商品【不该】被藏起来。
#   「分类禁用只该管住导航，不该把商品从列表里藏起来」——
#   这一条钉的就是这句话。不带分类筛选时，那件商品必须还在。
st, r = call("GET", "/shop/products?pageSize=100")
all_shop_names = {p["name"] for p in r.get("data", {}).get("list", [])}
check("★ 禁用子分类下的商品仍然出现在「全部商品」里（禁用不影响上架）",
      "自检-商品-挂禁用子" in all_shop_names, sorted(all_shop_names)[:8])

st, r = call("GET", f"/admin/products?categoryId={tree_parent}&pageSize=100")
admin_names = {p["name"] for p in r.get("data", {}).get("list", [])}
check("★ 管理端：按父分类筛，挂在【父】下的商品在", "自检-商品-挂父" in admin_names, admin_names)
check("★ 管理端：按父分类筛，挂在【子】下的商品也在（含后代）",
      "自检-商品-挂子" in admin_names, admin_names)
# ★★★ 这一条是本轮最值钱的断言：
#   如果「后代集合」是从【启用的分类】算出来的（而不是全部分类），
#   下面这个商品会从结果里消失 —— 因为它挂在一个被禁用的子分类下。
#   而用户看到的是「这件商品没了」，没有任何一层会报错。
#   分类禁用只该管住导航，不该把商品从父分类里藏起来。
check("★★ 管理端：挂在被禁用子分类下的商品【也】在（后代集合不能只算启用的）",
      "自检-商品-挂禁用子" in admin_names, admin_names)

st, r = call("GET", f"/shop/products?categoryId={tree_parent}&pageSize=100")
shop_names = {p["name"] for p in r.get("data", {}).get("list", [])}
check("★ 用户端：按父分类筛也含后代（两端用同一份规则）",
      {"自检-商品-挂父", "自检-商品-挂子"} <= shop_names, shop_names)

st, r = call("GET", f"/admin/products?categoryId={tree_child}&pageSize=100")
child_names = {p["name"] for p in r.get("data", {}).get("list", [])}
check("★ 按子分类筛时【不】含父分类的商品（后代是单向的）",
      "自检-商品-挂子" in child_names and "自检-商品-挂父" not in child_names, child_names)
check("★ 按子分类筛时也不含【兄弟】分支的商品（禁用子分类是另一个分支）",
      "自检-商品-挂禁用子" not in child_names, child_names)

# ★★ 哨兵：用不存在的分类 id 筛 → 必须返回空页，不是全部商品。
#   「后代 id 集合」算出来是空的时候，如果忘了短路就会 IN () → 500；
#   如果忘了【计算】这个集合（比如有人把那段代码删了），
#   筛选会被静默忽略 → 返回全部 100 件商品。这一条把两种情况都钉住。
st, r = call("GET", "/admin/products?categoryId=999999&pageSize=100")
check("★★ 用不存在的分类筛商品 → 空页（不是 500，也不是全部商品）",
      r.get("code") == 200 and r.get("data", {}).get("list") == []
      and r.get("data", {}).get("total") == 0, r.get("data"))
st, r = call("GET", "/shop/products?categoryId=999999&pageSize=100")
check("★★ 用户端同样：不存在的分类 → 空页",
      r.get("code") == 200 and r.get("data", {}).get("total") == 0, r.get("data"))

# ---------------------------------------------------------------------------
section("7. 分类：删除的业务规则（自给自足，不依赖库里的既有数据）")
# ---------------------------------------------------------------------------

# ★★ 1011：有子分类时不许删。
#
#   ★ 此刻 tree_parent【同时】有子分类（tree_child / dis_child）和商品
#     （直接挂在它下面的「自检-商品-挂父」）。
#     所以这一条不只是验 1011 存在，它还验了【检查顺序】：
#     如果 Service 里先查商品，返回的会是 1004 —— 而两者都要返回 1011
#     才算对，因为「结构调整」优先于「内容清理」。
st, r = call("DELETE", f"/admin/categories/{tree_parent}")
check("★ 有子分类时拒绝删除（1011，而且优先于 1004）", r.get("code") == 1011, r)
check("★ 提示说的是「子分类」而不是「商品」", "子分类" in str(r.get("message", "")), r)
check("★ 提示里带了具体数字（运营才知道要处理多少）",
      any(ch.isdigit() for ch in str(r.get("message", ""))), r)

st, r = call("GET", f"/admin/categories/{tree_parent}")
check("被拒绝后父分类仍然存在", r.get("code") == 200, r)

# 「拒绝」的全部含义是「什么都没变」——拒绝了但已经改了一半是最糟的结果
st, tree_now, _ = get_tree()
check("★ 被拒绝后两个子分类都还在（拒绝是彻底的，不是改了一半）",
      all(find_node(tree_now, cid) is not None for cid in (tree_child, dis_child)),
      [c["name"] for c in flatten(tree_now)])

# 1004：先把子分类下的商品删掉、再删子分类，父分类这时只剩商品 → 1004
#
#   ★ 顺序不能反：子分类自己也挂着商品，直接删子分类会拿到 1004
#     （这一点本身也说明「有商品就不许删」这条老规则对新分类同样生效）。
st, r = call("DELETE", f"/admin/categories/{tree_child}")
check("子分类自己也挂着商品 → 1004（老规则对子分类一视同仁）", r.get("code") == 1004, r)

products_by_name = {p["name"]: p["id"] for p in
                    call("GET", f"/admin/products?categoryId={tree_parent}&pageSize=100")[1]
                    .get("data", {}).get("list", [])}
for pname in ("自检-商品-挂子", "自检-商品-挂禁用子"):
    pid = products_by_name.get(pname)
    if pid:
        call("DELETE", f"/admin/products/{pid}")
        if pid in cleanup_products:
            cleanup_products.remove(pid)

for cid in (tree_child, dis_child):
    st, r = call("DELETE", f"/admin/categories/{cid}")
    check(f"子分类的商品清空后能删掉子分类（id={cid}）", r.get("code") == 200, r)
    if cid in cleanup_categories:
        cleanup_categories.remove(cid)

st, r = call("DELETE", f"/admin/categories/{tree_parent}")
check("子分类清空后，它自己还有商品 → 1004", r.get("code") == 1004, r)
check("错误信息说明了原因和数量", "商品" in str(r.get("message", "")), r)
check("提示里带了具体数字", any(ch.isdigit() for ch in str(r.get("message", ""))), r)

pid = products_by_name.get("自检-商品-挂父")
if pid:
    call("DELETE", f"/admin/products/{pid}")
    if pid in cleanup_products:
        cleanup_products.remove(pid)

st, r = call("DELETE", f"/admin/categories/{tree_parent}")
check("商品清空后可以删除分类", r.get("code") == 200, r)
if tree_parent in cleanup_categories:
    cleanup_categories.remove(tree_parent)

st, r = call("DELETE", f"/admin/categories/{tree_parent}")
check("重复删除返回 1003", r.get("code") == 1003, r)

# 唯一索引兜底：绕过接口直接用 SQL 插重名会被数据库拒绝。
# （这里从接口侧验证不了 —— 应用层校验会先拦下来，
#   并发场景才走得到数据库那一层，见 GlobalExceptionHandler 的注释）

# ---------------------------------------------------------------------------
section("8. 改造验证：/api/admin 前缀生效，旧路径已下线")
# ---------------------------------------------------------------------------

st, r = call("GET", "/admin/products?pageNum=1&pageSize=3")
check("商品接口在新前缀下可用", r.get("code") == 200, r)
check("商品分页每页 3 条（商品列表仍然分页）",
      len(r.get("data", {}).get("list", [])) == 3, r.get("data"))

st, r = call("GET", "/admin/products/1")
check("商品详情带 categoryName（join 仍正常）",
      r.get("data", {}).get("categoryName") is not None, r)

st, r = call("GET", "/products")
check("旧路径 /api/products 已下线（真 404）", st == 404, f"HTTP {st} / {r}")

st, r = call("GET", "/categories")
check("旧路径 /api/categories 已下线（真 404）", st == 404, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/nothing-here")
check("任意不存在的路径返回 404 而不是「系统繁忙」", st == 404, f"HTTP {st} / {r}")

# 格式错误的 JSON 要报 400 并说清原因，而不是伪装成系统故障。
#
# ★ 注意这里必须带上 token。拦截器是在请求体被解析【之前】执行的，
#   不带 token 的话请求会先被拦成 401，压根走不到 JSON 解析那一步 ——
#   那样测的就不是异常处理器，而是拦截器了。
#   这条用例想验的是：请求体畸形时后端返回 400 并说清原因，而不是 500。
req = urllib.request.Request(
    BASE + "/admin/categories",
    data=b'{"name": bad json}',
    headers={"Content-Type": "application/json",
             "Authorization": f"Bearer {TOKEN}"},
    method="POST",
)
try:
    urllib.request.urlopen(req, timeout=TIMEOUT)
    check("格式错误的 JSON 被拒绝", False, "居然成功了？")
except urllib.error.HTTPError as e:
    check("格式错误的 JSON 返回 400（不是 500）", e.code == 400, f"HTTP {e.code}")
    body = json.loads(e.read().decode("utf-8"))
    check("提示说明了是格式/编码问题", "格式" in body.get("message", ""), body)

# ---------------------------------------------------------------------------
section("清理")
# ---------------------------------------------------------------------------

for pid in cleanup_products:
    call("DELETE", f"/admin/products/{pid}")

# ★ 删除顺序：先子后父。有子分类的分类会被 1011 拦住，
#   所以用当前树算一遍「先父后子」再倒过来删 —— 深度优先前序的逆序
#   保证任何一个分类在它自己之前、它的所有后代都已经被删掉了。
st, tree, _ = get_tree()
order = {c["id"]: i for i, c in enumerate(flatten(tree))}
for cid in sorted(cleanup_categories, key=lambda c: -order.get(c, -1)):
    call("DELETE", f"/admin/categories/{cid}")

st, tree, raw = get_tree()
check("清理干净，节点数回到快照状态",
      len(flatten(tree)) == baseline_nodes,
      f"期望 {baseline_nodes}，实际 {len(flatten(tree))}")
check("清理干净，一级分类数回到快照状态", len(tree) == baseline_roots,
      f"期望 {baseline_roots}，实际 {len(tree)}")

# ---------------------------------------------------------------------------
print(f"\n{'=' * 46}")
print(f"  通过 {passed} 项，失败 {failed} 项")
print(f"{'=' * 46}")
sys.exit(1 if failed else 0)
