# -*- coding: utf-8 -*-
"""
里程碑 4 的端到端测试：管理端登录 + JWT + 拦截器。

★ 这个脚本里最有价值的一段是「自己伪造 JWT」那几个用例。
  它验证的正是 JWT 方案里最关键的那个漏洞：
  admin_user.id 和 member.id 都是从 1 开始自增的，两个 id=1 必然同时存在。
  如果 JWT 里只放 id 不放身份类型，一个普通会员就能冒充管理员。

  为了确保「拒绝 MEMBER token」这个结论是可信的，
  脚本会【同时】伪造一个 ADMIN token 并验证它被接受 ——
  否则「被拒绝」可能只是因为签名算错了，
  那这个测试就什么也没证明。
"""

import base64
import hashlib
import hmac
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:8080/api"
TIMEOUT = 10

passed = 0
failed = 0


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def call(method, path, body=None, token=None, raw_token=None):
    """
    发一个请求，返回 (http_status, json_body)。

    token     参数会自动加上 "Bearer " 前缀
    raw_token 参数原样放进 Authorization 头（用来测试格式错误的情况）
    """
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if raw_token is not None:
        headers["Authorization"] = raw_token

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
    """JWT 用的是 Base64URL 编码，而且去掉了尾部的 = 补齐符"""
    padding = "=" * (-len(segment) % 4)
    return base64.urlsafe_b64decode(segment + padding)


def b64url_encode(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_jwt_unsigned(payload):
    """
    构造一个 alg=none 的「无签名」token —— JWT 历史上最著名的漏洞。

    规范里 alg=none 是合法的，意思是「这个 token 不签名」，
    但很多早期库的实现是「照着 header 里写的算法去验」——
    看到 none 就真的不验签了，于是任何人都能伪造任意身份的 token。

    正确做法是【由服务端决定该用什么算法】，绝不能听客户端的。
    jjwt 0.12 默认就拒绝 none，这个用例就是来验证这一点。
    """
    header = b64url_encode(json.dumps({"alg": "none", "typ": "JWT"},
                                      separators=(",", ":")).encode())
    body = b64url_encode(json.dumps(payload, separators=(",", ":")).encode())
    return f"{header}.{body}."


def make_jwt(secret, payload):
    """
    手写一个 JWT。

    不是为了替代库，而是为了说明 JWT 到底有多简单：
    它就是「两段 Base64 + 一个 HMAC-SHA256 签名」，没有任何魔法。
    看懂这 6 行，你就理解了网上所有关于 JWT 的讨论。

    ⚠️ 正因为这么容易伪造，密钥的保护才是一切的前提 ——
       任何拿到密钥的人都能签出「我是管理员」的合法 token。
    """
    header = b64url_encode(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    body = b64url_encode(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{header}.{body}".encode()
    signature = hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()
    return f"{header}.{body}.{b64url_encode(signature)}"


def load_jwt_secret():
    """从 application.yml 里读出 JWT 密钥（含 ${环境变量:默认值} 的默认值部分）。"""
    yml = Path(__file__).with_name("..") / "mall-server" / "src" / "main" / "resources" / "application.yml"
    text = yml.read_text(encoding="utf-8")
    m = re.search(r"secret:\s*\$\{[A-Z_]+:([^}]+)\}", text)
    if not m:
        raise SystemExit(f"没能从 {yml} 里解析出 JWT 密钥")
    return m.group(1).strip()


# ---------------------------------------------------------------------------
section("1. 登录：成功路径")
# ---------------------------------------------------------------------------

st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
check("登录成功", r.get("code") == 200, r)

data = r.get("data", {})
token = data.get("token")
check("返回了 token", isinstance(token, str) and len(token) > 50, token)
check("返回了用户 id", data.get("id") == 1, data)
check("返回了用户名", data.get("username") == "admin", data)

# ★ 这条断言最重要：密码哈希绝不能出现在响应里
check("响应里没有 password 字段", "password" not in data, list(data.keys()))
raw = json.dumps(r, ensure_ascii=False)
check("整个响应体里也没有 BCrypt 哈希的痕迹", "$2b$" not in raw and "$2a$" not in raw, raw[:200])

# 看一眼 token 里面到底装了什么
parts = token.split(".")
check("token 是三段式（头部.载荷.签名）", len(parts) == 3, len(parts))
header = json.loads(b64url_decode(parts[0]))
payload = json.loads(b64url_decode(parts[1]))
print(f"         头部: {header}")
print(f"         载荷: {payload}")

check("载荷里有 sub（用户 id）", payload.get("sub") == "1", payload)
check("★ 载荷里有 type=ADMIN", payload.get("type") == "ADMIN", payload)
check("载荷里有 exp（过期时间）", "exp" in payload, payload)

# ---------------------------------------------------------------------------
section("2. 登录：失败路径")
# ---------------------------------------------------------------------------

st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "wrong-password"})
check("密码错误被拒绝（1006）", r.get("code") == 1006, r)
msg_wrong_pwd = r.get("message")

st, r = call("POST", "/admin/auth/login", {"username": "no-such-user", "password": "123456"})
check("账号不存在被拒绝（1006）", r.get("code") == 1006, r)
msg_no_user = r.get("message")

# ★ 防用户名枚举：两种失败的提示必须一模一样
check("★ 账号不存在和密码错误的提示完全相同（防用户名枚举）",
      msg_wrong_pwd == msg_no_user,
      f"密码错: {msg_wrong_pwd!r} / 账号不存在: {msg_no_user!r}")

st, r = call("POST", "/admin/auth/login", {"username": "", "password": "123456"})
check("空账号被校验拦住（400）", r.get("code") == 400, r)

st, r = call("POST", "/admin/auth/login", {"username": "admin"})
check("缺密码被校验拦住（400）", r.get("code") == 400, r)

# ---------------------------------------------------------------------------
section("3. 拦截器：无凭证")
# ---------------------------------------------------------------------------

st, r = call("GET", "/admin/products")
check("不带 token 访问受保护接口 → HTTP 401", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/categories")
check("分类接口同样被保护", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/auth/me")
check("/me 也需要登录", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/products", raw_token="")
check("Authorization 头为空 → 401", st == 401, f"HTTP {st}")

st, r = call("GET", "/admin/products", raw_token="abc123")
check("缺少 Bearer 前缀 → 401", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/products", raw_token="Bearer ")
check("只有 Bearer 没有内容 → 401", st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/products", token="this.is.not-a-jwt")
check("格式非法的 token → 401", st == 401, f"HTTP {st} / {r}")

# ---------------------------------------------------------------------------
section("4. 拦截器：篡改与过期")
# ---------------------------------------------------------------------------

# 改掉载荷（把 id 从 1 改成 2），签名就对不上了
tampered_payload = b64url_encode(json.dumps(
    {"sub": "2", "username": "admin", "type": "ADMIN",
     "exp": int(time.time()) + 7200}, separators=(",", ":")).encode())
tampered = f"{parts[0]}.{tampered_payload}.{parts[2]}"
st, r = call("GET", "/admin/products", token=tampered)
check("★ 篡改载荷后签名校验失败 → 401", st == 401, f"HTTP {st} / {r}")

# 用错误的密钥签一个 token
secret = load_jwt_secret()
forged_wrong_key = make_jwt("a-completely-different-secret-key-that-is-long-enough-000",
                            {"sub": "1", "username": "admin", "type": "ADMIN",
                             "exp": int(time.time()) + 7200})
st, r = call("GET", "/admin/products", token=forged_wrong_key)
check("★ 用错误密钥签发的 token → 401", st == 401, f"HTTP {st} / {r}")

# ★ alg=none：JWT 最经典的伪造手法。
#   如果服务端「照着 header 说的算法验」，攻击者把 alg 改成 none
#   就能不带签名地伪造任意身份 —— 这里必须被拒绝
unsigned = make_jwt_unsigned({"sub": "1", "username": "admin", "type": "ADMIN",
                              "exp": int(time.time()) + 7200})
st, r = call("GET", "/admin/products", token=unsigned)
check("★ alg=none 的无签名 token → 401（JWT 最经典的伪造手法）",
      st == 401, f"HTTP {st} / {r}")

# 已经过期的 token
expired = make_jwt(secret, {"sub": "1", "username": "admin", "type": "ADMIN",
                            "exp": int(time.time()) - 60})
st, r = call("GET", "/admin/products", token=expired)
check("★ 已过期的 token → 401", st == 401, f"HTTP {st} / {r}")
check("过期提示和无效提示能区分开",
      "过期" in str(r.get("message", "")), r)

# ★ 实测发现：服务端签发的是 HS512（见上面打印的头部），
#   但用 HS256 手写的 token 在对照组里【也能通过】。
#   说明 jjwt 是照着 token 自己声明的 alg 去验签的。
#
#   这不是漏洞 —— HS256/HS384/HS512 都需要同一个密钥才能签出来，
#   换个算法并不能让攻击者绕过签名。真正危险的是下面这种：
#   如果服务端用非对称算法（RS256，私钥签、公钥验），
#   攻击者把 alg 改成 HS256，就可能骗服务端【拿公钥当 HMAC 密钥】去验，
#   而公钥是公开的 —— 那就能伪造了。这叫「算法混淆攻击」。
#   我们用对称密钥，天然没有这个问题，但值得知道它的存在。
rs_header = b64url_encode(json.dumps({"alg": "RS256", "typ": "JWT"},
                                     separators=(",", ":")).encode())
body = b64url_encode(json.dumps({"sub": "1", "username": "admin", "type": "ADMIN",
                                 "exp": int(time.time()) + 7200},
                                separators=(",", ":")).encode())
st, r = call("GET", "/admin/products", token=f"{rs_header}.{body}.AAAA")
check("★ 声明成 RS256 的 token → 401（算法混淆攻击）", st == 401, f"HTTP {st} / {r}")

# ---------------------------------------------------------------------------
section("5. ★ 身份类型校验（本里程碑最关键的一组测试）")
# ---------------------------------------------------------------------------

now = int(time.time())

# 先证明「我确实能签出合法 token」——
# 没有这一步，下面「MEMBER 被拒绝」可能只是因为签名算错了，
# 那这个测试就什么也没证明
forged_admin = make_jwt(secret, {"sub": "1", "username": "forged-admin",
                                 "type": "ADMIN", "exp": now + 7200})
st, r = call("GET", "/admin/products", token=forged_admin)
check("（对照组）伪造的 ADMIN token 能被接受 → 说明下面的拒绝是类型校验起的作用",
      r.get("code") == 200, f"HTTP {st} / {r}")

# 现在伪造一个会员身份的 token。
# 真实场景里，攻击者不需要知道密钥 —— 他只要注册一个普通会员账号，
# 拿到的就是这样一个 type=MEMBER 的 token（id 很可能也是 1）。
# 这里手写是为了不依赖用户端功能还没做的部分。
forged_member = make_jwt(secret, {"sub": "1", "username": "zhangsan",
                                  "type": "MEMBER", "exp": now + 7200})
st, r = call("GET", "/admin/products", token=forged_member)
check("★ MEMBER 身份的 token 访问管理端接口 → 401（防越权）",
      st == 401, f"HTTP {st} / {r}")

st, r = call("GET", "/admin/auth/me", token=forged_member)
check("★ MEMBER 身份访问 /me 也被拒绝", st == 401, f"HTTP {st} / {r}")

# 没有 type 声明的老 token 也必须被拒绝（安全默认值要选最严的）
no_type = make_jwt(secret, {"sub": "1", "username": "admin", "exp": now + 7200})
st, r = call("GET", "/admin/products", token=no_type)
check("★ 缺少 type 声明的 token → 401（不向后兼容）",
      st == 401, f"HTTP {st} / {r}")

# ---------------------------------------------------------------------------
section("6. 带有效 token 访问业务接口")
# ---------------------------------------------------------------------------

st, r = call("GET", "/admin/products?pageNum=1&pageSize=3", token=token)
check("带 token 能正常查商品", r.get("code") == 200, r)

st, r = call("GET", "/admin/categories", token=token)
check("带 token 能正常查分类", r.get("code") == 200, r)

st, r = call("GET", "/admin/auth/me", token=token)
me = r.get("data", {})
check("/me 返回当前用户", me.get("username") == "admin", me)
check("/me 里没有 password", "password" not in me, me)

# 写操作也要能通过
st, r = call("POST", "/admin/categories",
             {"name": "自检-鉴权测试", "sort": 9800, "status": 1}, token=token)
check("带 token 能新增分类", r.get("code") == 200, r)
tid = r.get("data")

st, r = call("DELETE", f"/admin/categories/{tid}", token=token)
check("带 token 能删除分类", r.get("code") == 200, r)

# 不带 token 的写操作必须被拦住 —— 这条比读接口更重要
st, r = call("DELETE", "/admin/categories/1")
check("★ 不带 token 的写操作被拦住（否则任何人都能删数据）",
      st == 401, f"HTTP {st} / {r}")

# ---------------------------------------------------------------------------
section("7. 登录接口本身必须公开")
# ---------------------------------------------------------------------------

st, r = call("POST", "/admin/auth/login", {"username": "admin", "password": "123456"})
check("不带 token 也能调登录接口（否则成了死锁：想登录得先登录）",
      r.get("code") == 200, r)

# ---------------------------------------------------------------------------
print(f"\n{'=' * 46}")
print(f"  通过 {passed} 项，失败 {failed} 项")
print(f"{'=' * 46}")
sys.exit(1 if failed else 0)
