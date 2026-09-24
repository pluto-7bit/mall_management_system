# -*- coding: utf-8 -*-
"""
里程碑 3 的端到端接口测试（分类管理 + /api/admin 工程改造）。

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
"""

import json
import math
import sys
import urllib.error
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


def make_category(name, sort, status=1):
    """建一个分类并登记待清理，返回它的 id。"""
    st, r = call("POST", "/admin/categories", {"name": name, "sort": sort, "status": status})
    cid = r.get("data")
    if isinstance(cid, int):
        cleanup_categories.append(cid)
    return cid


# ---------------------------------------------------------------------------
section("0. 登录 + 清理上次残留 + 开局快照")
# ---------------------------------------------------------------------------

# 必须先登录 —— 下面每一个请求都要靠它拿到的 token 才能通过拦截器
login()


# 万一上一次跑到一半挂了，库里会留下名字以「自检」开头的垃圾数据。
# 先按名字前缀清一遍，保证脚本可以反复运行 ——
# 测试脚本必须能被重复执行，否则第二次跑就红一片，你就不信它了。
st, r = call("GET", "/admin/categories?pageNum=1&pageSize=100")
leftovers = [c for c in r.get("data", {}).get("list", []) if c["name"].startswith("自检")]
for c in leftovers:
    # 分类下可能还挂着商品，先清商品（否则分类删不掉）
    st, pr = call("GET", f"/admin/products?categoryId={c['id']}&pageSize=100")
    for p in pr.get("data", {}).get("list", []):
        call("DELETE", f"/admin/products/{p['id']}")
    call("DELETE", f"/admin/categories/{c['id']}")
if leftovers:
    print(f"         清掉了 {len(leftovers)} 条上次的残留数据")

st, r = call("GET", "/admin/categories?pageNum=1&pageSize=100")
check("分类列表可访问", r.get("code") == 200, r)
baseline_total = r.get("data", {}).get("total", 0)

st, r = call("GET", "/admin/categories/options")
baseline_options = len(r.get("data", []))

print(f"         当前库里有 {baseline_total} 个分类，其中启用的 {baseline_options} 个")
print("         （这些是你自己的数据，脚本不会动它们）")

# ---------------------------------------------------------------------------
section("1. 分类：读接口")
# ---------------------------------------------------------------------------

st, r = call("GET", "/admin/categories/options")
opts = r.get("data", [])
check("下拉框数量与快照一致", len(opts) == baseline_options, f"{len(opts)} vs {baseline_options}")
check("下拉框只含启用分类", all(o["status"] == 1 for o in opts), [o for o in opts if o["status"] != 1])
check("下拉框按 sort 升序", [o["sort"] for o in opts] == sorted(o["sort"] for o in opts), opts)

# /options 是固定路径，必须优先于 /{id}，否则会被当成 id="options" 而报错
check("固定路径 /options 未被 /{id} 抢走", isinstance(opts, list), r)

st, r = call("GET", "/admin/categories/999999")
check("查不存在的分类返回 1003", r.get("code") == 1003, r)

# ---------------------------------------------------------------------------
section("2. 分类：新增")
# ---------------------------------------------------------------------------

new_id = make_category("自检测试分类", 9901)
check("新增成功且返回 id", isinstance(new_id, int), new_id)

st, r = call("GET", f"/admin/categories/{new_id}")
d = r.get("data", {})
check("新增后能查到", d.get("id") == new_id, d)
check("status 默认为启用", d.get("status") == 1, d)
check("createTime 由数据库自动填充", d.get("createTime") is not None, d)

st, r = call("POST", "/admin/categories", {"name": "自检测试分类", "sort": 9902, "status": 1})
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
trimmed_id = make_category("  自检-首尾有空格  ", 9903)
st, r = call("GET", f"/admin/categories/{trimmed_id}")
check("名称前后空格被 trim 掉", r.get("data", {}).get("name") == "自检-首尾有空格", r)

# 禁用状态的分类不该出现在下拉框，但管理页要能看到
disabled_id = make_category("自检待禁用分类", 9904, status=0)
st, r = call("GET", "/admin/categories/options")
check("禁用的分类不出现在下拉框", all(o["id"] != disabled_id for o in r.get("data", [])), r)
st, r = call("GET", "/admin/categories?status=0&pageSize=100")
check("管理页按 status=0 能筛到它",
      any(c["id"] == disabled_id for c in r.get("data", {}).get("list", [])), r)

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

# ---------------------------------------------------------------------------
section("4. 分类：删除的业务规则（自给自足，不依赖库里的既有数据）")
# ---------------------------------------------------------------------------

# 先造一个「有商品的分类」，用完自己清掉
biz_cat = make_category("自检-有商品的分类", 9905)
# 里程碑 15：价格和库存搬到了 product_sku 上，建商品时必须显式给一条「默认 SKU」
# （specs 为空数组）。这个商品没有规格，所以 specSchema 是空数组、skus 恰好一条。
st, r = call("POST", "/admin/products",
             {"categoryId": biz_cat, "name": "自检-占位商品", "status": 1,
              "specSchema": [], "skus": [{"specs": [], "price": 1.00, "stock": 1}]})
product_id = r.get("data")
check("成功在该分类下建了一个商品", isinstance(product_id, int), r)
if isinstance(product_id, int):
    cleanup_products.append(product_id)

st, r = call("DELETE", f"/admin/categories/{biz_cat}")
check("分类下有商品时拒绝删除（1004）", r.get("code") == 1004, r)
check("错误信息说明了原因和数量", "商品" in str(r.get("message", "")), r)
check("提示里带了具体数字（运营才知道要处理多少）",
      any(ch.isdigit() for ch in str(r.get("message", ""))), r)

st, r = call("GET", f"/admin/categories/{biz_cat}")
check("被拒绝后分类仍然存在", r.get("code") == 200, r)

# 把商品删掉，分类就能删了
if product_id in cleanup_products:
    call("DELETE", f"/admin/products/{product_id}")
    cleanup_products.remove(product_id)
st, r = call("DELETE", f"/admin/categories/{biz_cat}")
check("商品清空后可以删除分类", r.get("code") == 200, r)

st, r = call("DELETE", f"/admin/categories/{biz_cat}")
check("重复删除返回 1003", r.get("code") == 1003, r)

# 唯一索引兜底：绕过接口直接用 SQL 插重名会被数据库拒绝。
# （这里从接口侧验证不了 —— 应用层校验会先拦下来，
#   并发场景才走得到数据库那一层，见 GlobalExceptionHandler 的注释）

# ---------------------------------------------------------------------------
section("5. 分类：分页与搜索（相对快照判断）")
# ---------------------------------------------------------------------------

expected_total = baseline_total + 3  # 本段跑完时还存在的新增分类：new_id / trimmed_id / disabled_id

st, r = call("GET", "/admin/categories?pageNum=1&pageSize=100")
check("总数 = 快照 + 本次新增的 3 个", r.get("data", {}).get("total") == expected_total,
      f"期望 {expected_total}，实际 {r.get('data', {}).get('total')}")

# 分页：每页 2 条，翻到最后一页应该正好接上，两页之间不重复
st, r = call("GET", "/admin/categories?pageNum=1&pageSize=2")
page1 = r.get("data", {})
check("第 1 页返回 2 条", len(page1.get("list", [])) == 2, page1)
check("总页数计算正确",
      page1.get("pages") == math.ceil(expected_total / 2),
      f"期望 {math.ceil(expected_total / 2)}，实际 {page1.get('pages')}")
sorts = [c["sort"] for c in page1["list"]]
check("第 1 页内部按 sort 升序", sorts == sorted(sorts), sorts)

st, r = call("GET", "/admin/categories?pageNum=2&pageSize=2")
page2 = r.get("data", {})
ids1 = {c["id"] for c in page1["list"]}
ids2 = {c["id"] for c in page2["list"]}
check("第 1、2 页数据不重复", ids1.isdisjoint(ids2), (ids1, ids2))

# 翻到超出范围的页，应该是空列表而不是报错
st, r = call("GET", f"/admin/categories?pageNum=99999&pageSize=10")
check("超出范围的页码返回空列表", r.get("code") == 200 and r.get("data", {}).get("list") == [], r)

# 用一个只有自己才有的关键词搜，结果一定是 1 条
st, r = call("GET", "/admin/categories?name=%E8%87%AA%E6%A3%80")  # 自检
check("按「自检」模糊搜索命中本次新增的 3 条",
      r.get("data", {}).get("total") == 3, r.get("data", {}))

st, r = call("GET", "/admin/categories?pageSize=99999")
check("超大 pageSize 被规范化为 100", r.get("data", {}).get("pageSize") == 100, r)

st, r = call("GET", "/admin/categories?pageNum=-1&pageSize=-1")
check("负数页码被规范化，不报错", r.get("code") == 200, r)

# ---------------------------------------------------------------------------
section("6. 改造验证：/api/admin 前缀生效，旧路径已下线")
# ---------------------------------------------------------------------------

st, r = call("GET", "/admin/products?pageNum=1&pageSize=3")
check("商品接口在新前缀下可用", r.get("code") == 200, r)
check("商品分页每页 3 条", len(r.get("data", {}).get("list", [])) == 3, r.get("data"))

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
for cid in cleanup_categories:
    call("DELETE", f"/admin/categories/{cid}")

st, r = call("GET", "/admin/categories?pageNum=1&pageSize=100")
check("清理干净，总数回到快照状态", r.get("data", {}).get("total") == baseline_total,
      f"期望 {baseline_total}，实际 {r.get('data', {}).get('total')}")

# ---------------------------------------------------------------------------
print(f"\n{'=' * 46}")
print(f"  通过 {passed} 项，失败 {failed} 项")
print(f"{'=' * 46}")
sys.exit(1 if failed else 0)
