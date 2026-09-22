# -*- coding: utf-8 -*-
"""
里程碑 8 测试（一）：收货地址簿

这组用例的重点不是增删改查，而是两条容易被忽略的东西：

  1. ★★ 水平越权：会员 A 能不能通过改 id 动到会员 B 的地址
  2. ★  不变量：一个会员「最多一个默认地址」，且「有地址就恰好有一个默认」

第 1 条配了三个入口的对照（改 / 删 / 设为默认），
第 2 条配了五个会破坏它的操作（首个新增、第二个新增、设为默认、
取消默认、删除默认地址）—— 漏掉任何一个入口，数据都会慢慢变脏。

还有一条容易漏的：update 时不传 isDefault，默认状态【不能】被改掉。
这是 Boolean 和 boolean 的区别在真实 bug 上的体现。

运行：
    python test-address.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080/api"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

RUN = str(int(time.time()))[-8:]
PREFIX = "addr"
TAG = f"{PREFIX}{RUN}"

PASS = 0
FAIL = 0
FAILED = []

# 两个会员，用来测越权
TOKEN_A = None
ID_A = None
TOKEN_B = None
ID_B = None


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


def run_sql(sql):
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{sql}\n{result.stderr}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


# ----------------------------------------------------------------------
def register(tag):
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}{tag}{RUN}",
        "password": "addr123456",
        "nickname": f"地址测试{tag}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    return r["data"]["token"], r["data"]["id"]


def cleanup():
    run_sql(f"DELETE a FROM member_address a JOIN member m ON m.id = a.member_id "
            f"WHERE m.username LIKE '{PREFIX}%'")
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")


def addr_list(token):
    return call("GET", "/shop/addresses", token=token)


def addr_create(token, receiver, phone, region, detail, is_default=None):
    body = {"receiver": receiver, "phone": phone, "region": region, "detail": detail}
    if is_default is not None:
        body["isDefault"] = is_default
    return call("POST", "/shop/addresses", body, token=token)


def addr_update(token, aid, body):
    return call("PUT", f"/shop/addresses/{aid}", body, token=token)


def full(receiver, phone, region, detail, is_default=None):
    """
    拼一份【完整】的地址 body。

    ★ 为什么测试里要专门有个 helper 干这个？
      因为 PUT /addresses/{id} 是【全量替换】，四个文本字段必须全传。
      如果测试里写成 addr_update(token, id, phone="138...")，
      会得到一个 400 —— 而那个 400 是【测试写错了】，
      不是被测的代码有问题。用例必须按真实契约发请求。
    """
    body = {"receiver": receiver, "phone": phone, "region": region, "detail": detail}
    if is_default is not None:
        body["isDefault"] = is_default
    return body


def addr_set_default(token, aid):
    return call("PUT", f"/shop/addresses/{aid}/default", None, token=token)


def addr_delete(token, aid):
    return call("DELETE", f"/shop/addresses/{aid}", token=token)


def items_of(r):
    return r.get("data") or []


def find(r, aid):
    for a in items_of(r):
        if a["id"] == aid:
            return a
    return None


def defaults_of(r):
    """列表里所有 isDefault=1 的地址 —— 用来验证「最多一个」"""
    return [a for a in items_of(r) if a.get("isDefault") == 1]


# ======================================================================
def main():
    global TOKEN_A, ID_A, TOKEN_B, ID_B

    cleanup()
    TOKEN_A, ID_A = register("a")
    TOKEN_B, ID_B = register("b")

    print()
    print(f"本次运行 TAG = {TAG}")
    print(f"会员 A id={ID_A}，会员 B id={ID_B}（两个不同会员，用来测越权）")

    section("1. ★ 地址是纯私人数据：所有接口都必须登录")

    for method, path, body in [
        ("GET", "/shop/addresses", None),
        ("GET", "/shop/addresses/default", None),
        ("POST", "/shop/addresses", {"receiver": "张三", "phone": "13800138000",
                                     "region": "广东省深圳市", "detail": "xx路1号"}),
        ("PUT", "/shop/addresses/1", {"receiver": "张三"}),
        ("PUT", "/shop/addresses/1/default", None),
        ("DELETE", "/shop/addresses/1", None),
    ]:
        st, r = call(method, path, body)
        check(f"游客 {method} {path} → 401",
              st == 401, f"HTTP {st} / {r}")

    # 对照：商品浏览仍然匿名可访问，这次的改动没误伤它
    st, r = call("GET", "/shop/products?pageSize=1")
    check("（对照）商品列表仍然匿名可访问 —— 没被误伤",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")

    # 会员 token 不能用管理端接口（回归）
    st, r = call("GET", "/admin/products", token=TOKEN_A)
    check("（回归）会员 token 访问管理端仍然是 401", st == 401, f"HTTP {st} / {r}")

    # ==================================================================
    section("2. 空状态：一个地址都没有的时候")

    st, r = addr_list(TOKEN_A)
    check("地址列表 → HTTP 200（不是 404，「还没有地址」是正常状态）",
          st == 200 and r.get("code") == 200, f"HTTP {st} / {r}")
    check("地址列表返回空数组，不是 null",
          items_of(r) == [], f"{r.get('data')}")

    st, r = call("GET", "/shop/addresses/default", token=TOKEN_A)
    check("★ 没有默认地址时 → HTTP 200 + data 为 null（这是成功，不是错误）",
          st == 200 and r.get("code") == 200 and r.get("data") is None,
          f"HTTP {st} / {r}")

    # ==================================================================
    section("3. ★ 不变量之一：第一个地址自动成为默认")

    st, r = addr_create(TOKEN_A, "张三", "13800138001", "广东省深圳市南山区", "科技园路 1 号")
    check("新增地址成功并返回 id", r.get("code") == 200 and isinstance(r.get("data"), int),
          f"HTTP {st} / {r}")
    a1 = r.get("data")

    st, r = addr_list(TOKEN_A)
    check("列表里能看到刚加的地址", find(r, a1) is not None, f"{items_of(r)}")
    check("★★ 没勾「设为默认」，但它是第一个地址 → 自动成为默认",
          find(r, a1).get("isDefault") == 1,
          f"isDefault = {find(r, a1).get('isDefault')}（不自动设默认的话，"
          f"用户存完地址去下单会发现一个都没选中）")

    st, r = call("GET", "/shop/addresses/default", token=TOKEN_A)
    check("单独的默认地址接口返回的正是它", (r.get("data") or {}).get("id") == a1,
          f"{r.get('data')}")

    # ==================================================================
    section("4. ★ 不变量之二：第二个地址默认不是默认")

    st, r = addr_create(TOKEN_A, "李四", "13900139002", "北京市朝阳区", "望京 SOHO T1")
    a2 = r.get("data")
    check("新增第二个地址成功", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_A)
    check("★ 第二个地址【不会】自动成为默认（只有第一个才自动）",
          find(r, a2).get("isDefault") == 0,
          f"isDefault = {find(r, a2).get('isDefault')}")
    check("★ 此时仍然只有一个默认地址（不变量成立）",
          len(defaults_of(r)) == 1, f"默认地址有 {len(defaults_of(r))} 个：{defaults_of(r)}")
    check("★ 默认地址排在最前面（前端不用自己再排一遍）",
          items_of(r)[0]["id"] == a1, f"顺序：{[a['id'] for a in items_of(r)]}")

    # ==================================================================
    section("5. ★★ 不变量之三：设为默认会清掉原来的默认")

    st, r = addr_set_default(TOKEN_A, a2)
    check("把第二个地址设为默认 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_A)
    check("★★ 新的成为默认", find(r, a2).get("isDefault") == 1,
          f"a2.isDefault = {find(r, a2).get('isDefault')}")
    check("★★ 旧的那个【被自动取消】默认 —— 否则就有两个默认地址了",
          find(r, a1).get("isDefault") == 0,
          f"a1.isDefault = {find(r, a1).get('isDefault')}")
    check("★★ 全列表里仍然只有 1 个默认地址",
          len(defaults_of(r)) == 1,
          f"默认地址有 {len(defaults_of(r))} 个：{defaults_of(r)}")

    # 幂等
    st, r = addr_set_default(TOKEN_A, a2)
    check("★ 重复设为默认 → 仍然 200（幂等）", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = addr_list(TOKEN_A)
    check("★ 重复设为默认后，默认地址还是只有 1 个",
          len(defaults_of(r)) == 1 and find(r, a2).get("isDefault") == 1,
          f"{defaults_of(r)}")

    # ==================================================================
    section("6. ★ 修改是全量替换（PUT 的语义），以及不变量之四")

    # ---- 6.1 ★ 先验证契约本身：这个接口【不】支持局部更新 ----
    #
    # 这条用例是"意外发现"变成"被测试的契约"：
    # 最早 AddressServiceImpl.update 是按局部更新写的
    # （`dto.getX() == null ? null : ...`），但 @Valid 在外层
    # 就把局部更新的请求 400 掉了，那几个 null 分支是死代码。
    # 现在语义统一成"全量替换"，这条断言负责守住它。
    st, r = call("PUT", f"/shop/addresses/{a2}", {"phone": "13700137003"}, token=TOKEN_A)
    check("★ 只传 phone（想局部更新）→ 业务码 400（PUT 是全量替换）",
          r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_A)
    check("★ 被拒绝后电话没变（校验失败不留脏数据）",
          find(r, a2).get("phone") == "13900139002",
          f"phone = {find(r, a2).get('phone')}")
    check("★ 被拒绝后默认状态也没变", find(r, a2).get("isDefault") == 1,
          f"isDefault = {find(r, a2).get('isDefault')}")

    # ---- 6.2 ★★ Boolean 和 boolean 的区别 ----
    st, r = addr_update(TOKEN_A, a2,
                        full("李四", "13700137003", "北京市朝阳区", "望京 SOHO T1"))
    check("四个字段全传、不传 isDefault → 成功", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_A)
    check("电话确实改了", find(r, a2).get("phone") == "13700137003",
          f"phone = {find(r, a2).get('phone')}")
    check("收货人也按传的值整体替换了（「全量替换」的含义就在这）",
          find(r, a2).get("receiver") == "李四",
          f"receiver = {find(r, a2).get('receiver')}")
    check("★★ 默认状态【没有被动过】，a2 仍然是默认",
          find(r, a2).get("isDefault") == 1,
          f"isDefault = {find(r, a2).get('isDefault')}。"
          f"若 DTO 里用 boolean 而不是 Boolean，「没传」会被当成 false，"
          f"用户只想改个电话，默认地址却没了")

    # ---- 6.3 显式取消默认 ----
    st, r = addr_update(TOKEN_A, a2,
                        full("李四", "13700137003", "北京市朝阳区", "望京 SOHO T1",
                             is_default=False))
    check("显式传 isDefault=false → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = addr_list(TOKEN_A)
    check("★ 传 false 才是真的取消默认（和「不传」区分开了）",
          find(r, a2).get("isDefault") == 0,
          f"isDefault = {find(r, a2).get('isDefault')}")
    check("★ 取消后没有任何默认地址 —— 这是允许的（不变量说的是「最多一个」）",
          len(defaults_of(r)) == 0, f"{defaults_of(r)}")

    # ---- 6.4 用 update 设为默认，同样要清掉旧的 ----
    addr_set_default(TOKEN_A, a1)   # 先恢复一个默认，好验证它会被清掉
    st, r = addr_update(TOKEN_A, a2,
                        full("李四", "13700137003", "北京市朝阳区", "望京 SOHO T1",
                             is_default=True))
    check("用 update 传 isDefault=true → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = addr_list(TOKEN_A)
    check("★ 用 update 也能设为默认，并且【清掉了旧的默认】",
          find(r, a2).get("isDefault") == 1 and find(r, a1).get("isDefault") == 0,
          f"a1={find(r, a1).get('isDefault')}, a2={find(r, a2).get('isDefault')}")
    check("★ 走 update 这条路之后，默认地址仍然只有 1 个",
          len(defaults_of(r)) == 1, f"{defaults_of(r)}")

    # ==================================================================
    section("7. ★★ 水平越权：A 能不能动 B 的地址（三个入口）")

    st, r = addr_create(TOKEN_B, "王五", "13600136004", "上海市浦东新区", "张江高科 88 号")
    b1 = r.get("data")
    check("（准备）会员 B 建了一个地址", r.get("code") == 200, f"HTTP {st} / {r}")

    # ---- 入口一：改 ----
    st, r = call("PUT", f"/shop/addresses/{b1}",
                 {"receiver": "被A改掉了", "phone": "13000000000",
                  "region": "黑客省", "detail": "黑客路 1 号"}, token=TOKEN_A)
    check("★ A 改 B 的地址 → 业务码 1003「地址不存在」",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_B)
    check("★★ B 的地址【没有被改动】（收货人还是王五）",
          find(r, b1) is not None and find(r, b1).get("receiver") == "王五",
          f"{find(r, b1)}")

    # ---- 入口二：设为默认 ----
    st, r = addr_set_default(TOKEN_A, b1)
    check("★ A 把 B 的地址设为默认 → 业务码 1003",
          r.get("code") == 1003, f"HTTP {st} / {r}")

    # ---- 入口三：删 ----
    st, r = addr_delete(TOKEN_A, b1)
    check("★ A 删 B 的地址 → 200（删除是幂等的，对不存在的目标不报错）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    # ★★ 这条才是真正的安全性断言。
    #    只看上面那个 200 会得出「删除成功了」的错误结论 ——
    #    对幂等接口，光断言响应码是不够的，必须断言【目标还在不在】。
    st, r = addr_list(TOKEN_B)
    check("★★ B 的地址【还在】—— 这才是安全性断言（幂等删除必须查目标，不能只看响应码）",
          find(r, b1) is not None,
          f"B 的地址列表：{[a['id'] for a in items_of(r)]}，期望包含 {b1}")

    # ---- 反向确认 A 看不到 B 的地址 ----
    st, r = addr_list(TOKEN_A)
    check("★ A 的地址列表里没有 B 的地址",
          find(r, b1) is None, f"A 的地址：{[a['id'] for a in items_of(r)]}")
    check("★ 两个人的列表长度各自独立",
          len(items_of(r)) == 2, f"A 有 {len(items_of(r))} 条，期望 2")

    # ==================================================================
    section("8. ★★ 不变量之五：删掉默认地址后自动提升一条")

    st, r = addr_list(TOKEN_B)
    defaults_before = len(defaults_of(r))
    check("（准备）B 只有一个地址，它是默认",
          defaults_before == 1 and find(r, b1).get("isDefault") == 1,
          f"{items_of(r)}")

    st, r = addr_create(TOKEN_B, "赵六", "13500135005", "浙江省杭州市西湖区", "文三路 99 号")
    b2 = r.get("data")

    # 把 b1 删掉（它是默认）
    st, r = addr_delete(TOKEN_B, b1)
    check("删除默认地址 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_B)
    check("被删的地址不在了", find(r, b1) is None, f"{[a['id'] for a in items_of(r)]}")
    check("★★ 剩下的那条【被自动提升为默认】—— 不让用户陷入「有地址却没默认」的状态",
          find(r, b2) is not None and find(r, b2).get("isDefault") == 1,
          f"b2 = {find(r, b2)}")
    check("★★ 默认地址恰好还是有 1 个（不变量继续成立）",
          len(defaults_of(r)) == 1, f"{defaults_of(r)}")

    st, r = call("GET", "/shop/addresses/default", token=TOKEN_B)
    check("默认地址接口返回的是被提升的那条", (r.get("data") or {}).get("id") == b2,
          f"{r.get('data')}")

    # 删掉最后一条 → 没有默认地址了，但也不能报错
    st, r = addr_delete(TOKEN_B, b2)
    check("删掉最后一条地址 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = addr_list(TOKEN_B)
    check("地址列表空了", items_of(r) == [], f"{items_of(r)}")
    st, r = call("GET", "/shop/addresses/default", token=TOKEN_B)
    check("没有地址时默认地址接口 → 200 + null（不报错）",
          st == 200 and r.get("data") is None, f"HTTP {st} / {r}")

    # ==================================================================
    section("9. 删除的幂等性")

    st, r = addr_delete(TOKEN_A, a1)
    check("删掉 A 的 a1 → 成功", r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = addr_delete(TOKEN_A, a1)
    check("★ 重复删同一个 → 仍然 200（幂等，用户连点两次不该看到报错）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    st, r = addr_delete(TOKEN_A, 99999999)
    check("删一个从没存在过的 id → 200（目标状态已达成就不算失败）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    # ==================================================================
    section("10. 参数校验")

    bad_cases = [
        ({"phone": "13800138000", "region": "广东省", "detail": "xx路"}, "不填收货人"),
        ({"receiver": "", "phone": "13800138000", "region": "广东省", "detail": "xx路"}, "收货人空串"),
        ({"receiver": "   ", "phone": "13800138000", "region": "广东省", "detail": "xx路"}, "收货人纯空格"),
        ({"receiver": "张三", "region": "广东省", "detail": "xx路"}, "不填电话"),
        ({"receiver": "张三", "phone": "12345", "region": "广东省", "detail": "xx路"}, "电话格式错（太短）"),
        ({"receiver": "张三", "phone": "23800138000", "region": "广东省", "detail": "xx路"}, "电话不是手机号（2 开头）"),
        ({"receiver": "张三", "phone": "138001380001", "region": "广东省", "detail": "xx路"}, "电话 12 位"),
        ({"receiver": "张三", "phone": "13800138000", "detail": "xx路"}, "不填地区"),
        ({"receiver": "张三", "phone": "13800138000", "region": "广东省"}, "不填详细地址"),
        ({"receiver": "张三", "phone": "13800138000", "region": "广东省", "detail": "   "}, "详细地址纯空格"),
    ]
    for body, desc in bad_cases:
        st, r = call("POST", "/shop/addresses", body, token=TOKEN_A)
        check(f"{desc} → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    # 超长
    st, r = call("POST", "/shop/addresses", {
        "receiver": "张" * 51, "phone": "13800138000",
        "region": "广东省", "detail": "xx路"}, token=TOKEN_A)
    check("收货人 51 字（超 50）→ 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    st, r = call("POST", "/shop/addresses", {
        "receiver": "张三", "phone": "13800138000",
        "region": "广东省", "detail": "路" * 256}, token=TOKEN_A)
    check("详细地址 256 字（超 255）→ 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    # 空 body
    st, r = call("POST", "/shop/addresses", {}, token=TOKEN_A)
    check("空 body → 业务码 400", r.get("code") == 400, f"HTTP {st} / {r}")

    # 校验失败不能留下脏数据
    st, r = addr_list(TOKEN_A)
    check("★ 所有被拒绝的请求都没有写进数据库（A 的地址数没有增加）",
          len(items_of(r)) == 1, f"A 有 {len(items_of(r))} 条地址，期望 1")

    # ==================================================================
    section("11. ★ 参数白名单：body 里的 memberId / id 必须被忽略")

    st, r = call("POST", "/shop/addresses", {
        "receiver": "白名单测试", "phone": "13800138009",
        "region": "四川省成都市", "detail": "天府大道 1 号",
        "memberId": ID_B,          # ★ 想把自己的地址挂到 B 名下
        "id": 88888,               # ★ 想指定主键
        "isDefault": 1,
    }, token=TOKEN_A)
    check("多传 memberId / id → 请求本身成功（多余字段被忽略）",
          r.get("code") == 200, f"HTTP {st} / {r}")
    sneaky = r.get("data")

    check("★ 返回的 id 不是我们传的 88888（自增主键由数据库生成）",
          sneaky != 88888, f"id = {sneaky}")

    st, r = addr_list(TOKEN_B)
    check("★★ B 的地址列表里没有多出这条（memberId 没被采纳）",
          find(r, sneaky) is None, f"B 的地址：{[a['id'] for a in items_of(r)]}")

    st, r = addr_list(TOKEN_A)
    check("★★ 这条地址挂在 A 名下（归属由 JWT 决定，不是客户端说了算）",
          find(r, sneaky) is not None, f"A 的地址：{[a['id'] for a in items_of(r)]}")

    rows = run_sql(f"SELECT member_id FROM member_address WHERE id = {sneaky}")
    check("★★ 数据库里 member_id 就是 A 的 id（直接查库确认，不只看接口）",
          rows and int(rows[0][0]) == ID_A,
          f"库里的 member_id = {rows[0][0] if rows else '?'}，A 的 id = {ID_A}")

    # 修改接口也一样。
    # ★ 注意这里必须发【完整】的 body：PUT 是全量替换，只传 memberId
    #   会得到 400，那样下面这条断言就变成空断言了 ——
    #   请求压根没执行，member_id 当然"没变"，可它什么也没证明。
    #   （第一版就是这么写的，它在报告里显示 [OK]，但其实是假的通过。）
    body = full("改了", "13800138009", "四川省成都市", "  天府大道 1 号  ",
                is_default=True)
    body["memberId"] = ID_B          # ★ 又想把自己的地址挂到 B 名下
    st, r = call("PUT", f"/shop/addresses/{sneaky}", body, token=TOKEN_A)
    check("修改时多传 memberId → 请求本身成功（多余字段被忽略）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    st, r = addr_list(TOKEN_A)
    check("★ 顺带验证 trim：前后带空格的详细地址被存成了干净的",
          find(r, sneaky).get("detail") == "天府大道 1 号",
          f"detail = {find(r, sneaky).get('detail')!r}")

    rows = run_sql(f"SELECT member_id FROM member_address WHERE id = {sneaky}")
    check("★ 修改时传 memberId 也不会改变归属",
          rows and int(rows[0][0]) == ID_A,
          f"库里的 member_id = {rows[0][0] if rows else '?'}")

    st, r = addr_list(TOKEN_B)
    check("★★ B 名下没有凭空多出地址", find(r, sneaky) is None,
          f"B 的地址：{[a['id'] for a in items_of(r)]}")

    # ==================================================================
    section("12. 路径匹配：/default 和 /{id} 不会混淆")

    # GET /addresses/default 不能被当成 GET /addresses/{id="default"}
    st, r = call("GET", "/shop/addresses/default", token=TOKEN_A)
    check("GET /addresses/default → 200（而不是 400 类型转换失败）",
          st == 200, f"HTTP {st} / {r}")

    # PUT /addresses/1/default 是设默认，不是 PUT /addresses/{id}
    st, r = addr_list(TOKEN_A)
    target = items_of(r)[0]["id"]
    st, r = addr_set_default(TOKEN_A, target)
    check("PUT /addresses/{id}/default → 200（三段路径，匹配的是设默认）",
          r.get("code") == 200, f"HTTP {st} / {r}")

    # 不存在的地址
    st, r = addr_set_default(TOKEN_A, 99999999)
    check("对不存在的地址设默认 → 业务码 1003", r.get("code") == 1003, f"HTTP {st} / {r}")
    st, r = addr_update(TOKEN_A, 99999999,
                        full("某人", "13800138000", "某省某市", "某路 1 号"))
    check("改不存在的地址 → 业务码 1003", r.get("code") == 1003, f"HTTP {st} / {r}")

    # 非数字 id
    st, r = call("PUT", "/shop/addresses/abc/default", None, token=TOKEN_A)
    check("路径 id 非数字 → HTTP 400（协议错误，轮不到业务代码）",
          st == 400, f"HTTP {st} / {r}")

    # ==================================================================
    section("13. 数据清理与不污染")

    # 清掉两个会员的全部地址
    for token in (TOKEN_A, TOKEN_B):
        st, r = addr_list(token)
        for a in items_of(r):
            addr_delete(token, a["id"])

    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")
    left = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
    check("测试会员已删除", int(left[0][0]) == 0, f"{left}")

    left = run_sql(f"SELECT COUNT(*) FROM member_address WHERE member_id IN ({ID_A}, {ID_B})")
    check("★ 测试地址已删除", int(left[0][0]) == 0, f"剩余 {left[0][0]} 条")

    total = run_sql("SELECT COUNT(*) FROM member")
    print(f"  当前会员总数 = {total[0][0]}（含你原有的会员，未受影响）")

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
