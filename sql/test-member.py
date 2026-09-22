# -*- coding: utf-8 -*-
"""
里程碑 5 的端到端测试：用户端会员注册 / 登录 / 拦截器。

★ 这个脚本里最该看的是第 4 节和第 7 节：

  第 4 节「参数白名单」—— 验证注册接口【不能】被客户端塞进
  status / id 这类字段。这是一个真实的提权入口：
  如果后端直接把请求体映射到实体，攻击者注册时带上 status: 2
  就可能给自己开出一个「超级会员」，而这类 bug 不会有任何报错。

  第 7 节「跨端越权」—— 里程碑 4 只是【伪造】了一个 MEMBER token
  来证明类型检查有效。这一节用的是【真实】签发的 token，
  而且把两个方向都测了：会员 token 打管理端、管理员 token 打用户端。

这个脚本需要直接连数据库做两件 API 做不到的事：
  1. 造一个「已禁用」的会员（管理端还没有会员管理接口）
  2. 清理测试数据（没有注销账号的接口）
只操作 mall 库的 member 表，且只动用户名以 zizhen 开头的行。
"""

import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:8080/api"
TIMEOUT = 10

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

# 用户名里带时间戳，保证每次运行都是全新账号（不会撞上上次的残留）
RUN = str(int(time.time()))[-8:]
PREFIX = "zizhen"

passed = 0
failed = 0

# 本次运行创建的所有测试账号，结束时统一删掉
created = []


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def run_sql(sql):
    """执行一条 SQL，返回按行切分的结果（-N 去掉表头，-B 用 tab 分隔）。"""
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{sql}\n{result.stderr}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


def call(method, path, body=None, token=None):
    """发一个请求，返回 (http_status, json_body)。"""
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

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


def b64url_decode(segment):
    padding = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(segment + padding)


def jwt_payload(token):
    """解出 JWT 的载荷（不验签，只是看内容）。"""
    return json.loads(b64url_decode(token.split(".")[1]))


def register(username, password="test1234", **extra):
    """注册一个测试账号，并登记到清理列表里。"""
    body = {"username": username, "password": password}
    body.update(extra)
    st, r = call("POST", "/shop/auth/register", body)
    if isinstance(r, dict) and r.get("code") == 200:
        created.append(username)
    return st, r


def cleanup():
    """删掉所有本次运行（以及历史遗留）的测试账号。"""
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")


# ---------------------------------------------------------------------------
section("0. 清理历史残留")
# ---------------------------------------------------------------------------

cleanup()
leftover = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
check("清理干净，没有残留的测试账号", leftover[0][0] == "0", leftover)

baseline = int(run_sql("SELECT COUNT(*) FROM member")[0][0])
print(f"         库里有 {baseline} 个会员（zhangsan / lisi / wangwu 是你自己的数据，脚本不会动）")

# ---------------------------------------------------------------------------
section("1. 注册：成功路径")
# ---------------------------------------------------------------------------

USER_A = f"{PREFIX}{RUN}a"
st, r = register(USER_A, "test1234", nickname="测试小明", phone="13900139000")
check("注册成功", r.get("code") == 200, r)

data = r.get("data", {})
token_a = data.get("token")
member_a_id = data.get("id")

check("返回了 token", isinstance(token_a, str) and len(token_a) > 50, token_a)
check("返回了新账号的 id", isinstance(member_a_id, int), data)
check("返回了用户名", data.get("username") == USER_A, data)
check("返回了昵称", data.get("nickname") == "测试小明", data)

# 密码哈希绝不能出现在响应里
check("响应里没有 password 字段", "password" not in data, list(data.keys()))
raw = json.dumps(r, ensure_ascii=False)
check("响应体里没有 BCrypt 哈希的痕迹", "$2b$" not in raw, raw[:200])

# ★ 注册返回的 token 必须是 MEMBER 身份
payload = jwt_payload(token_a)
print(f"         token 载荷: {payload}")
check("★ 注册返回的 token 是 MEMBER 身份", payload.get("type") == "MEMBER", payload)
check("token 的 sub 是新账号的 id", payload.get("sub") == str(member_a_id), payload)

# 新账号立刻能登录（说明密码加密存对了）
st, r = call("POST", "/shop/auth/login", {"username": USER_A, "password": "test1234"})
check("注册后能立刻用同样的密码登录", r.get("code") == 200, r)

# 数据库里的实际情况
rows = run_sql(f"SELECT nickname, phone, status FROM member WHERE username = '{USER_A}'")
check("数据库里存了昵称和手机号", rows and rows[0][0] == "测试小明" and rows[0][1] == "13900139000", rows)
check("新账号的 status 是 1（正常）", rows and rows[0][2] == "1", rows)

pwd_row = run_sql(f"SELECT password FROM member WHERE username = '{USER_A}'")
check("数据库里存的是 BCrypt 哈希，不是明文",
      pwd_row and pwd_row[0][0].startswith("$2") and "test1234" not in pwd_row[0][0],
      pwd_row)

# ---------------------------------------------------------------------------
section("2. 注册：参数校验（都应该是 400）")
# ---------------------------------------------------------------------------

bad_cases = [
    ("账号太短", {"username": "abc", "password": "test1234"}),
    ("账号太长（21 位）", {"username": "z" * 21, "password": "test1234"}),
    ("账号里有空格", {"username": f"{PREFIX} abc", "password": "test1234"}),
    ("账号里有中文", {"username": f"{PREFIX}中文", "password": "test1234"}),
    ("账号里有连字符", {"username": f"{PREFIX}-abc", "password": "test1234"}),
    ("密码太短（5 位）", {"username": f"{PREFIX}{RUN}z", "password": "12345"}),
    ("密码为空", {"username": f"{PREFIX}{RUN}z", "password": ""}),
    ("手机号格式错", {"username": f"{PREFIX}{RUN}z", "password": "test1234", "phone": "12345"}),
    ("昵称超过 50 字", {"username": f"{PREFIX}{RUN}z", "password": "test1234", "nickname": "字" * 51}),
    ("账号为空", {"username": "", "password": "test1234"}),
]

for label, body in bad_cases:
    st, r = call("POST", "/shop/auth/register", body)
    check(f"{label} → 400", r.get("code") == 400, r)

# ★ 选填字段传空串必须通过。这条验证的是 DTO 里
#   ^$|^1[3-9]\d{9}$ 那个「允许空串」的分支 ——
#   前端没填的输入框传的就是空字符串，不是 null。
#   少了 ^$ 这个分支，用户什么都填反而会被报「格式不正确」
USER_B = f"{PREFIX}{RUN}b"
st, r = register(USER_B, "test1234", phone="", nickname="")
check("★ 手机号传空串能注册成功（前端没填就是这个值）", r.get("code") == 200, r)

# 空串入库时必须变成 NULL，而不是留一个 ''
ph = run_sql(f"SELECT phone IS NULL, nickname IS NULL FROM member WHERE username = '{USER_B}'")
check("★ 空串入库时被规范化成了 NULL（不是空字符串）",
      ph and ph[0][0] == "1" and ph[0][1] == "1", ph)

# ---------------------------------------------------------------------------
section("3. 注册：账号重复")
# ---------------------------------------------------------------------------

st, r = register(USER_A)
check("重复注册同一个账号 → 1007", r.get("code") == 1007, r)
check("提示说明了原因", "已被注册" in str(r.get("message", "")), r)

# ★ 数据库的排序规则 utf8mb4_0900_ai_ci 不区分大小写，
#   所以 'ZIZHEN...' 和 'zizhen...' 算同一个账号。
#   应用层和数据库层用的是同一个排序规则，所以行为一致 —— 不会出现
#   「应用层查不到、插进去却被索引拦住」这种不一致
st, r = register(USER_A.upper())
check("★ 大小写不同的同名账号也算重复（排序规则不区分大小写）",
      r.get("code") == 1007, r)

# ---------------------------------------------------------------------------
section("4. ★ 参数白名单：注册接口不能被客户端塞进敏感字段")
# ---------------------------------------------------------------------------

USER_C = f"{PREFIX}{RUN}c"
# 这是本节的核心：请求里带上 status: 0（禁用）和 id: 1（管理员 id）
st, r = register(USER_C, "test1234", **{"status": 0, "id": 1, "createTime": "2000-01-01 00:00:00"})
check("带 status/id 的请求本身是成功的", r.get("code") == 200, r)

member_c_id = r.get("data", {}).get("id")
check("★ 客户端传的 id 被忽略了（新账号拿到的是自己的 id，不是 1）",
      isinstance(member_c_id, int) and member_c_id != 1, member_c_id)

# 如果 status: 0 真的被写进去了，这个账号会处于禁用状态、登录会被拒
st, r = call("POST", "/shop/auth/login", {"username": USER_C, "password": "test1234"})
check("★ 客户端传的 status=0 被忽略了（账号仍是启用状态，能登录）",
      r.get("code") == 200, r)

row = run_sql(f"SELECT status, create_time FROM member WHERE username = '{USER_C}'")
check("★ 数据库里 status 仍是 1", row and row[0][0] == "1", row)
check("★ 客户端传的 createTime 被忽略了（时间由数据库生成）",
      row and not row[0][1].startswith("2000"), row)

# ---------------------------------------------------------------------------
section("5. 登录：失败路径")
# ---------------------------------------------------------------------------

st, r = call("POST", "/shop/auth/login", {"username": USER_A, "password": "wrong-password"})
check("密码错误 → 1006", r.get("code") == 1006, r)
msg_wrong_pwd = r.get("message")

st, r = call("POST", "/shop/auth/login", {"username": f"{PREFIX}nobody", "password": "test1234"})
check("账号不存在 → 1006", r.get("code") == 1006, r)
msg_no_user = r.get("message")

check("★ 账号不存在和密码错误的提示完全相同（防用户名枚举）",
      msg_wrong_pwd == msg_no_user,
      f"密码错: {msg_wrong_pwd!r} / 账号不存在: {msg_no_user!r}")

st, r = call("POST", "/shop/auth/login", {"username": USER_A})
check("缺密码 → 400", r.get("code") == 400, r)

st, r = call("POST", "/shop/auth/login", {"username": USER_A, "password": ""})
check("密码为空 → 400", r.get("code") == 400, r)

# 登录时账号前后的空格会被 trim（后端做了 trim），所以能登录成功
st, r = call("POST", "/shop/auth/login", {"username": f"  {USER_A}  ", "password": "test1234"})
check("登录时账号前后空格会被自动忽略", r.get("code") == 200, r)

# ---------------------------------------------------------------------------
section("6. ★ 密码绝不能被 trim")
# ---------------------------------------------------------------------------

USER_D = f"{PREFIX}{RUN}d"
# 密码前后带空格 —— 这是完全合法的密码，空格也是字符
st, r = register(USER_D, "  space1234  ")
check("前后带空格的密码能注册成功", r.get("code") == 200, r)

st, r = call("POST", "/shop/auth/login", {"username": USER_D, "password": "  space1234  "})
check("★ 用原样的密码（含空格）能登录", r.get("code") == 200, r)

st, r = call("POST", "/shop/auth/login", {"username": USER_D, "password": "space1234"})
check("★ 用去掉空格的密码【不能】登录（说明密码没被 trim）",
      r.get("code") == 1006, r)

# 同理，密码的大小写当然也不能被忽略
st, r = call("POST", "/shop/auth/login", {"username": USER_D, "password": "  SPACE1234  "})
check("★ 密码大小写敏感", r.get("code") == 1006, r)

# ---------------------------------------------------------------------------
section("7. ★ 跨端越权（本里程碑最关键的一组）")
# ---------------------------------------------------------------------------

# --- 先拿一个真实的管理员 token ---
st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
admin_token = r.get("data", {}).get("token")
check("管理员登录成功，拿到 admin token", isinstance(admin_token, str), r)
check("admin token 的类型是 ADMIN", jwt_payload(admin_token).get("type") == "ADMIN")

# --- 用一个真实的会员 token（不是伪造的）---
st, r = call("POST", "/shop/auth/login", {"username": "zhangsan", "password": "123456"})
member_token = r.get("data", {}).get("token")
check("会员 zhangsan 登录成功，拿到 member token", isinstance(member_token, str), r)

payload_m = jwt_payload(member_token)
payload_a = jwt_payload(admin_token)
print(f"         会员 token: sub={payload_m.get('sub')}, type={payload_m.get('type')}")
print(f"         管理 token: sub={payload_a.get('sub')}, type={payload_a.get('type')}")

# ★ 核心：两个 id 都是 1，但因为 type 不同，互相进不了对方的门
check("★ 会员和管理员的 id 都是 1（撞号了）",
      payload_m.get("sub") == "1" and payload_a.get("sub") == "1",
      f"member sub={payload_m.get('sub')}, admin sub={payload_a.get('sub')}")

st, r = call("GET", "/admin/products", token=member_token)
check("★ 会员 token 访问管理端商品接口 → 401", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/auth/me", token=member_token)
check("★ 会员 token 访问管理端 /me → 401", st == 401, f"HTTP {st} / {r}")

st, r = call("DELETE", "/admin/categories/1", token=member_token)
check("★ 会员 token 不能删分类（写操作更要拦住）", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/shop/auth/me", token=admin_token)
check("★ 管理员 token 访问用户端 /me → 401（反方向同样拦）",
      st == 401, f"HTTP {st} / {r}")

# --- 各自的 token 在自己的地盘要正常工作 ---
st, r = call("GET", "/shop/auth/me", token=member_token)
check("会员 token 在用户端能正常使用", r.get("code") == 200, r)

st, r = call("GET", "/admin/products?pageNum=1&pageSize=2", token=admin_token)
check("管理员 token 在管理端能正常使用", r.get("code") == 200, r)

# ---------------------------------------------------------------------------
section("8. /api/shop/auth/me")
# ---------------------------------------------------------------------------

st, r = call("GET", "/shop/auth/me")
check("不带 token → 401", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/shop/auth/me", token="garbage.token.here")
check("垃圾 token → 401", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/shop/auth/me", token=token_a)
me = r.get("data", {})
check("带 token 能拿到会员信息", r.get("code") == 200, r)
check("返回了正确的用户名", me.get("username") == USER_A, me)
check("返回了昵称", me.get("nickname") == "测试小明", me)
check("返回了手机号", me.get("phone") == "13900139000", me)
check("★ /me 里没有 password", "password" not in me, list(me.keys()))

# ---------------------------------------------------------------------------
section("9. 被禁用的会员")
# ---------------------------------------------------------------------------

# 管理端还没有会员管理接口（里程碑 10 才做），
# 所以只能直接改数据库来造这个场景
run_sql(f"UPDATE member SET status = 0 WHERE username = '{USER_A}'")

st, r = call("POST", "/shop/auth/login", {"username": USER_A, "password": "test1234"})
check("★ 被禁用的账号登录 → 1006", r.get("code") == 1006, r)
check("提示里说明了是被禁用",
      "禁用" in str(r.get("message", "")), r)

# ★ 手里已经有的、还没过期的 token 也要立刻失效
st, r = call("GET", "/shop/auth/me", token=token_a)
check("★ 被禁用的账号，手里没过期的 token 也失效（401）",
      st == 401, f"HTTP {st} / {r}")

# 恢复，方便后面继续用这个账号
run_sql(f"UPDATE member SET status = 1 WHERE username = '{USER_A}'")
st, r = call("GET", "/shop/auth/me", token=token_a)
check("恢复启用后 token 又能用了", r.get("code") == 200, r)

# ---------------------------------------------------------------------------
section("10. 注册和登录接口必须公开")
# ---------------------------------------------------------------------------

st, r = call("POST", "/shop/auth/login", {"username": "zhangsan", "password": "123456"})
check("登录接口不带 token 也能调（否则死锁）", r.get("code") == 200, r)

USER_E = f"{PREFIX}{RUN}e"
st, r = register(USER_E, "test1234")
check("注册接口不带 token 也能调", r.get("code") == 200, r)

# ---------------------------------------------------------------------------
section("11. 拦截器只作用于真实存在的接口")
# ---------------------------------------------------------------------------

# ★ 这里有个容易误解的行为，值得亲眼确认一下。
#
# 直觉上会想「只要在拦截器范围内，没带 token 就该 401」。
# 实际不是 —— 拦截器【只保护真的能执行的方法】。
#
# 什么情况下它不介入？有两种，而且它们的表现不一样：
#
#   1. 路径根本匹配不到任何 Controller 方法
#      → Spring 交给静态资源处理器，那个 handler 不是 HandlerMethod，
#        拦截器里 `if (!(handler instanceof HandlerMethod)) return true;` 直接放行
#      → 最后走到 NoResourceFoundException → 404
#
#   2. 路径能匹配到方法，但 HTTP 方法对不上
#      （比如 /shop/cart/items 只声明了 POST，却用 GET 请求）
#      → Spring 在 getHandler() 阶段就抛 HttpRequestMethodNotSupportedException，
#        那时候 HandlerExecutionChain（拦截器链）还没建起来
#      → 拦截器压根没跑 → 405
#
# 两者的共同点是「没有代码会被执行，所以没有东西需要保护」，
# 所以都不是漏洞。但现象上的区别很重要：
# **看日志里有没有 401，是找不到这两类情况的**，
# 只有「路径和方法都对、只差身份」的请求才会走到鉴权。
#
# 这一条在里程碑 7 之前是写不出来的 —— 那时候购物车的 Controller 还不存在，
# 两种情况都只能看到 404。现在能同时看到 404 和 405，正好对照。

# 情况 1：路径存在（里程碑 7 做了），只是方法不对 → 405
st, r = call("GET", "/shop/cart/items")
check("★ 路径存在但方法不对（GET 一个只支持 POST 的接口）→ 405，不是 401",
      st == 405, f"HTTP {st} / {r}")

# 情况 2：这一条断言走到今天，期望值已经翻了【两次】
#
# ★ 它的历史，就是"占位断言"最好的教材：
#
#   里程碑 8 之前：  GET /shop/orders → 404   订单 Controller 还没做，路径压根不存在
#   里程碑 8 之后：  GET /shop/orders → 405   路径有了（只声明了 POST），方法对不上
#   ★ 里程碑 10 之后：GET /shop/orders → 401   ← 现在这里是一个【真接口】了
#
#   里程碑 10 给 ShopOrderController 加了 @GetMapping（"我的订单"列表），
#   于是同一个 URL 上 GET 方法第一次有了真正的处理方法 ——
#   「路径和方法都对、只差身份」 → 鉴权拦截器介入 → 401。
#
#   ⚠️⚠️ 值得留意的是：**这三次改动里，它想验证的那件事一个字都没变**
#        （"没有代码会被执行，就没有东西需要保护"），
#        变的只是【当时的实现进度】。
#        这正是"占位断言"的典型形态 —— 它测的是进度，不是规则。
#        一旦进度推进它就会失败，而失败的原因不是出了 bug。
#
#        **看到这种失败，要先问"它是不是在测进度"，
#        再决定是改代码还是改断言。**
#        这一次的答案仍然是【改断言】：GET /shop/orders 返回 401 是
#        【完全正确的行为】—— 它是会员的订单列表，当然必须登录。
st, r = call("GET", "/shop/orders")
check("★★ 里程碑 10 起 GET /shop/orders 是真接口（我的订单列表）→ 401，不再是 405",
      st == 401, f"HTTP {st} / {r}")

# ★ 而 405 这个现象本身仍然要单独证明一次 —— 不能因为上面那条改判 401
#   就让「方法对不上时拦截器不跑」这件事失去覆盖。
#
#   换一个【不会】被 @GetMapping("/{orderNo}") 抢走的路径：
#   /shop/orders/{orderNo}/pay 是两段，只有 @PostMapping 匹配这个形状，
#   所以用 GET 请求必然走不进任何处理方法 → 405。
#
#   ⚠️ 别用 GET /shop/orders/buy-now 来测 —— 它看起来是"POST-only 的路径用 GET"，
#      但 GET /shop/orders/{orderNo} 那条路由会把 "buy-now" 当成订单号接走，
#      于是返回 1003（订单不存在）而不是 405。**这种"以为在测 A、其实测了 B"
#      的断言比没有断言更危险**，因为它会一直绿着。
st, r = call("GET", "/shop/orders/FAKEORDER01/pay")
check("★ 路径存在但方法不对（GET 一个只支持 POST 的接口）→ 405，不是 401",
      st == 405, f"HTTP {st} / {r}")

# ★ 而真正该被永久保护的那条规则在这里：路径和方法都对、只差身份 → 401
#
#   注意上面那条 405 和这条 401 的区别 —— 它们说的是同一件事的两面：
#   405 证明「没有代码会执行」（拦截器没跑，无所谓放不放行），
#   401 证明「有代码会执行，所以必须有身份」。
#   一个接口只要做出来了，就【必须】能通过这条 401 的检查。
st, r = call("POST", "/shop/orders",
             {"productIds": [1], "addressId": 1, "idempotencyKey": "membertestkey01"})
check("★★ 购物车结算接口未登录 → 401（订单模块必须登录）",
      st == 401, f"HTTP {st} / {r}")

st, r = call("POST", "/shop/orders/buy-now",
             {"productId": 1, "quantity": 1, "addressId": 1,
              "idempotencyKey": "membertestkey02"})
check("★★ 立即购买接口未登录 → 401", st == 401, f"HTTP {st} / {r}")

# ★ 上面这两条都不是「没登录」，所以都不该返回 401。
#   用带 token 的请求再验一次：结果必须和匿名时【完全一样】——
#   这证明签名里说的「拦截器根本没跑」是真的，
#   而不是「跑了但因为某种原因放行了」
st_anon, r_anon = call("GET", "/shop/cart/items")
st_auth, r_auth = call("GET", "/shop/cart/items", token=member_token)
check("★★ 带不带 token 结果完全一致（证明拦截器真的没介入，而不是放行）",
      st_anon == st_auth and r_anon == r_auth,
      f"匿名：HTTP {st_anon} / {r_anon}\n         带token：HTTP {st_auth} / {r_auth}")

# ★ 对照：路径和方法都对、只差身份 → 这时候才轮到 401
st, r = call("POST", "/shop/cart/items", {"productId": 1, "quantity": 1})
check("★ 对照：路径和方法都对，只差身份 → 401（拦截器在这里才生效）",
      st == 401, f"HTTP {st} / {r}")

# ---------------------------------------------------------------------------
section("清理")
# ---------------------------------------------------------------------------

cleanup()
left = run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE '{PREFIX}%'")
check("测试账号已全部删除", left[0][0] == "0", left)

now_total = int(run_sql("SELECT COUNT(*) FROM member")[0][0])
check("会员总数回到测试前的状态（你原有的会员一个没动）",
      now_total == baseline, f"测试前 {baseline}，现在 {now_total}")

# ---------------------------------------------------------------------------
print(f"\n{'=' * 46}")
print(f"  通过 {passed} 项，失败 {failed} 项")
print(f"{'=' * 46}")
sys.exit(1 if failed else 0)
