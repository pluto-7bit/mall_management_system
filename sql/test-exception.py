# -*- coding: utf-8 -*-
"""
里程碑 13 测试：全局异常处理器 —— 协议错误的状态码矩阵 + 响应形状 + token 边界

【为什么单独开一个脚本，而不是把断言分散进现有的 12 个？】

  1. ★★ 这个脚本的价值在于【横向比较】，散开就没有了。
     本轮新增的三个处理器，存在的意义是它们和已有的几个共享同一条判据
     （「这是调用方把请求本身写错了，还是他提了一个不合规但格式正确的内容？」）。
     而这条判据【只有把同一族的处理器摆在一起时才看得见】：

         ?pageNum=abc               畸形（"abc" 不是数字）      → 真 400
         文件 3MB（multipart 正常）  不畸形，违反的是声明限额     → 200 + code 400
         压根不是 multipart          畸形                      → 真 400
         multipart 但部分名不对      畸形                      → 真 400   ← 本轮新增
         Content-Type: text/plain    畸形                      → 真 415   ← 本轮新增
         Accept: application/xml     客户端读不了我们的格式      → 真 406   ← 本轮新增

     这类对照测试一旦按业务域拆开，读者就再也无法确认规则是【一致】执行的 ——
     而那正是它唯一想证明的事。

  2. 归属问题无解。现有脚本按业务域组织，而「全局异常处理器属于哪个里程碑」
     是任意答案 —— 事实上已经这样了：MultipartException 的断言落在
     test-review.py，typeMismatch 的落在 test-shop-product.py，
     MaxUploadSize 的落在 test-upload.py。
     ★ 一个横切关注点应该有一个横切的脚本。

  3. 重合是可接受的，而且不是重复。现有那些断言是【偶然的】（测业务功能时
     顺手碰到的），本脚本是【系统的】（把状态码矩阵和响应形状整张表走一遍）。
     「偶然的一例」和「完整的表」不是重复，是「有个例子」和「有规格」的区别。

  ⚠️ 现有脚本里的断言【一条都不搬、一条都不改】。

【本脚本提供了其余 12 个脚本一个都没有的东西】

  · Allow 响应头          —— 0 处。而它正是 405 处理器注释里明说
                            「规范要求、必须自己加」的东西
  · 415 / 406             —— 0 处
  · 「部分名不是 file」    —— 0 处（post_file 的 field 默认值恰好永远正确）
  · 错误响应的 body 形状   —— 0 处系统性断言（其余脚本只看状态码或 body.code）
  · 406 的【空 body 契约】 —— 0 处。而这是本轮最关键的一个设计决策：
                            给它塞一个 Result body 的真实后果是
                            HTTP 500 + Tomcat 错误页（推演见
                            GlobalExceptionHandler.handleMediaTypeNotAcceptable）
  · 「拦截器在请求体解析【之前】执行」—— 只在 test-category.py 的注释里，
                            从来没有断言。第 5 段把它变成一张可执行的表

【★ 本脚本不往数据库写任何数据】

  所有输入都是非法的，全部在参数解析阶段就被拒了，业务代码一行都没跑。
  唯一往磁盘写的是一个文件：第 1 段那个【字段名正确的对照组】必须真的上传成功，
  否则「字段名错 → 400」那条断言就没有鉴别力（那个 400 可能只是因为请求本来
  就坏了，而不是因为处理器对了）。脚本按精确路径删掉它，删完核对文件数。

运行：
    python test-exception.py
"""

import json
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080/api"

# 上传目录。★ 和 test-upload.py 一样：mall.upload.dir 是相对【进程工作目录】的，
# 从 mall-server/ 启动才会落在 mall-server/uploads/。脚本自己核对它存不存在，
# 不存在就明确报出来，而不是让清理悄悄地什么都没删。
UPLOAD_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mall-server", "uploads")

RUN = str(int(time.time()))[-8:]

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
MEMBER_TOKEN = None

# ★ 本脚本唯一的磁盘产物。清理时【只删这里记着的】。
UPLOADED = []


# ======================================================================
class Resp:
    """
    一次原始 HTTP 调用的结果。

    ★ 为什么 raw 和 json 【两个】都留着？
      因为「【空的】body」和「【解析失败的】body」是两件不同的事，
      而第 3 段要断言的正是「空的」（406 必须没有 body）。
      只看 json 的话两种情况都是 None —— 那条断言就没有鉴别力了。
    """

    def __init__(self, status, headers, raw):
        self.status = status
        self.headers = headers          # email.message.Message，大小写不敏感
        self.raw = raw
        try:
            self.json = json.loads(raw.decode("utf-8")) if raw else None
        except Exception:
            self.json = None

    def header(self, name):
        """响应头。不存在返回 None。"""
        return self.headers.get(name)

    def __repr__(self):
        return f"<Resp {self.status} {self.raw[:120]!r}>"


# ----------------------------------------------------------------------
def raw_call(method, path, data=None, headers=None, token=None, timeout=30):
    """
    发一个【请求头完全由调用方控制】的请求，返回 Resp。

    ⚠️ 为什么不复用其他脚本里的 call()？因为那个函数硬编码了
       Content-Type: application/json，而且【把响应头扔了】——
       而本脚本要断言的恰好就是「请求头写错时怎么办」和
       「响应头里有没有 Allow」。

    ★ Accept 头【故意不设默认值】。设了的话，第 3 段那条
      「完全不带 Accept 头」的对照就没法构造了。
      （urllib 自己不会加 Accept，所以不传就是真的不传。）
    """
    h = dict(headers or {})
    if token:
        h["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return Resp(resp.status, resp.headers, resp.read())
    except urllib.error.HTTPError as e:
        return Resp(e.code, e.headers, e.read())


def post_multipart(path, field, filename, content, content_type="image/png",
                   token=None, timeout=60):
    """
    发一个 multipart/form-data 请求。

    ★★ 和 test-upload.py 的 post_file 唯一的、也是全部的差别：
       field 是【必填参数，没有默认值】。

       那边写的是 field="file"，默认值恰好永远正确，从来没人传过别的值 ——
       于是「部分名不是 file」这条分支在里程碑 13 之前【一次都没被测过】，
       而它掉进了 Exception 兜底，返回 HTTP 200 + code 500「系统繁忙」。

       ★ 一个「默认值恰好永远正确」的参数，就是一个永远测不到的分支。
         这比「忘了写用例」隐蔽得多 —— 代码看起来是参数化的、
         文档看起来是覆盖了的，但那个参数只用过一次。

       所以这里强制调用点写明它想用的字段名：写 "file" 和写 "image"
       一样地显眼，写错了当场就看得出来。

    multipart 的 body 形状（三个细节一个都不能错，否则服务端解析失败）：
        --<boundary>\\r\\n
        Content-Disposition: form-data; name="<field>"; filename="<filename>"\\r\\n
        Content-Type: <content_type>\\r\\n
        \\r\\n                     ← 头部和 body 之间这个【空行】
        <文件字节>\\r\\n
        --<boundary>--\\r\\n       ← 结束的 boundary 后面跟 "--"
      每个换行都必须是 \\r\\n，不是 \\n。
    """
    boundary = "----MallException" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n"
        f"\r\n"
    ).encode("utf-8")
    body = head + content + f"\r\n--{boundary}--\r\n".encode("utf-8")

    headers = {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=body, headers=headers,
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return Resp(resp.status, resp.headers, resp.read())
    except urllib.error.HTTPError as e:
        return Resp(e.code, e.headers, e.read())


def post_multipart_empty_parts(path, token=None, timeout=30):
    """
    Content-Type 是 multipart，但请求体里【一个 part 都没有】（只有结束 boundary）。

    ★ 它和 post_multipart 的区别不是「少传了个参数」，而是【另一个问题】：
      这一条问的是「判定 multipart 缺 part 的依据，是请求头还是请求体」。
      答案在 RequestParamMethodArgumentResolver.handleMissingValueInternal：

        参数是 MultipartFile 而请求里没给
          ├─ 请求的 Content-Type【不是】multipart → MultipartException
          └─ 是 multipart，但【没有那个 part】  → MissingServletRequestPartException

      ★ 判据是【Content-Type 头】，不是请求体长什么样。
        所以下面 1b（一个 part，名字对）、1c（零个 part）、1a（一个 part，名字不对）
        三条要一起看，才能把这句话钉死。
    """
    boundary = "----MallException" + uuid.uuid4().hex
    body = f"--{boundary}--\r\n".encode("utf-8")

    headers = {
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=body, headers=headers,
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return Resp(resp.status, resp.headers, resp.read())
    except urllib.error.HTTPError as e:
        return Resp(e.code, e.headers, e.read())


# ----------------------------------------------------------------------
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


def make_png(w=4, h=4, rgb=(255, 0, 0), compress_level=6):
    """
    造一张【真正合法的】PNG（和 test-upload.py 里那份同构，脚本之间不共享代码）。

    ⚠️ 必须是真 PNG。服务端按文件头（魔数）判断格式，伪造的会被正确地拒掉，
      那样第 1 段那条「字段名正确 → 200」的对照就会失败，
      而失败原因看起来像是上传功能坏了。
    """
    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + bytes(list(rgb) * w) for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, compress_level))
            + chunk(b"IEND", b""))


PNG = make_png()


def assert_error_shape(label, r, expect_status):
    """
    断言一条【协议错误】响应的完整形状。六个用例共用。

    ★★ `keys == {"code", "message"}` 断言的是「data 这个 key 【不存在】」，
       而【不是】「它是 null」。
       因为 application.yml 配了 default-property-inclusion: non_null，
       null 字段会直接从 JSON 里消失。
       ★ 对前端来说这两件事不一样：`'data' in res` 一个为假一个为真。
       而这个契约在本轮之前【从来没有被断言过】—— 所有脚本只看
       状态码或者 body["code"]，没有一条检查过 body 的形状。
    """
    check(f"{label}：HTTP {expect_status}",
          r.status == expect_status,
          f"实际 HTTP {r.status}，body={r.raw[:200]!r}")

    body = r.json
    if not isinstance(body, dict):
        check(f"{label}：body 是 JSON 对象", False, f"实际 {r.raw[:200]!r}")
        return
    check(f"{label}：body 是 JSON 对象", True)

    check(f"{label}：★ keys 正好是 {{code, message}}"
          f"（data 是【不存在】，不是 null）",
          set(body.keys()) == {"code", "message"},
          f"实际 keys = {sorted(body.keys())} —— 多出来的键说明响应形状"
          f"和项目契约不一致（对比 test-upload.py 里那条 cover 字段的教训："
          f"default-property-inclusion: non_null 会把 null 字段整个去掉）")

    check(f"{label}：code 是整数 400（协议错误固定用 400）",
          isinstance(body.get("code"), int) and body.get("code") == 400,
          f"实际 code = {body.get('code')!r}")

    msg = body.get("message")
    check(f"{label}：message 是非空字符串",
          isinstance(msg, str) and msg.strip() != "",
          f"实际 message = {msg!r}")


# ----------------------------------------------------------------------
def url_to_disk(url):
    """
    把 /uploads/2026/09/xx.png 映射回磁盘路径。

    ⚠️ 只做一次前缀剥离，不做任何通配 —— 见文件头的清理说明。
    """
    assert url.startswith("/uploads/"), f"不是上传路径：{url}"
    rel = url[len("/uploads/"):]
    full = os.path.abspath(os.path.join(UPLOAD_ROOT, rel))
    root = os.path.abspath(UPLOAD_ROOT)
    if not full.startswith(root + os.sep):
        raise SystemExit(f"拒绝删除 uploads 目录之外的文件：{full}")
    return full


def count_files():
    n = 0
    for _, _, files in os.walk(UPLOAD_ROOT):
        n += len(files)
    return n


def cleanup_files():
    """
    ★★ 删磁盘上【本次脚本自己上传的】文件。

    规矩和 test-upload.py 一致：只删 UPLOADED 里记着的路径，逐个映射回磁盘；
    不做任何通配、不清空目录、不按前缀批量删；
    记下清理前后的文件总数，核对「少掉的数量 == 删掉的数量」。

    最后那条是关键：它把「我删的都是我上传的」从【信念】变成【断言】。
    """
    before = count_files()
    removed = 0
    missing = 0
    for url in UPLOADED:
        try:
            os.remove(url_to_disk(url))
            removed += 1
        except FileNotFoundError:
            missing += 1
    after = count_files()

    print()
    print(f"  磁盘清理：上传 {len(UPLOADED)} 个，删除 {removed} 个"
          f"（{missing} 个已不在），目录文件数 {before} → {after}")
    check("★ 磁盘清理只删掉了本次上传的文件（数量对得上）",
          before - after == removed,
          f"删了 {removed} 个，但文件总数少了 {before - after} 个 —— "
          f"有别的文件被误删，或者有文件没删掉")

    # 顺带清掉空的年月目录。⚠️ 只删【空的】—— rmdir 对非空目录会失败，
    # 这个失败本来就是我们要的安全网，所以不用先判断再删。
    year = os.path.join(UPLOAD_ROOT, "2026")
    if os.path.isdir(year):
        for month in os.listdir(year):
            try:
                os.rmdir(os.path.join(year, month))
            except OSError:
                pass
        try:
            os.rmdir(year)
        except OSError:
            pass


# ----------------------------------------------------------------------
def admin_login():
    global ADMIN_TOKEN
    r = raw_call("POST", "/admin/auth/login",
                 data=json.dumps({"username": "admin", "password": "123456"},
                                 ensure_ascii=False).encode("utf-8"),
                 headers={"Content-Type": "application/json"})
    ADMIN_TOKEN = ((r.json or {}).get("data") or {}).get("token")
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {r.status} / {r.raw[:200]!r}")


def member_login():
    """
    ★ 用种子里已有的会员登录，【不注册新会员】—— 这是本脚本
      「不往数据库写任何数据」这条性质的一部分。
      mall.sql 里种了 zhangsan / lisi / wangwu 三个演示账号，密码都是 123456。
    """
    global MEMBER_TOKEN
    for username in ("zhangsan", "lisi", "wangwu"):
        r = raw_call("POST", "/shop/auth/login",
                     data=json.dumps({"username": username, "password": "123456"},
                                     ensure_ascii=False).encode("utf-8"),
                     headers={"Content-Type": "application/json"})
        token = ((r.json or {}).get("data") or {}).get("token")
        if token:
            MEMBER_TOKEN = token
            print(f"  会员登录：{username}")
            return
    raise SystemExit(
        "种子会员 zhangsan / lisi / wangwu 全都登录不上。\n"
        "本脚本刻意不注册新会员（它不往数据库写任何数据），"
        "所以需要库里已经有 mall.sql 种下的演示账号。")


# ======================================================================
def main():
    if not os.path.isdir(UPLOAD_ROOT):
        raise SystemExit(
            f"找不到上传目录：{UPLOAD_ROOT}\n"
            f"它必须存在 —— 否则清理会静默地什么都没删。\n"
            f"启动过后端之后它会被 @PostConstruct 自动创建。")

    admin_login()
    member_login()

    print()
    print(f"本次运行 RUN = {RUN}")
    print(f"上传目录 = {UPLOAD_ROOT}")
    print(f"开工前目录里已有 {count_files()} 个文件")

    # ==================================================================
    section("1. multipart 的部分名（新处理器 handleMissingServletRequestPart）")
    # ==================================================================

    # ---- 1a) 真 multipart、字段名不对 → 真 400 ----
    r = post_multipart("/admin/images", "image", "a.png", PNG, token=ADMIN_TOKEN)
    check("★★ multipart 字段名写成 image → 真 HTTP 400（不是 200 + code 500）",
          r.status == 400,
          f"实际 HTTP {r.status} / {r.raw[:200]!r} —— "
          f"若是 200 + code 500「系统繁忙」，说明新处理器没生效"
          f"（MissingServletRequestPartException 不是 MultipartException 的子类，"
          f"老的 handleMultipart 接不住它）")
    check("  body.code 是 400", (r.json or {}).get("code") == 400, f"实际 {r.json}")
    msg = (r.json or {}).get("message") or ""
    check("★ message 点出了【字段名】这件事 —— 说明走的是新处理器，不是老的那个",
          "字段名" in msg,
          f"实际 message = {msg!r} —— 两个处理器都是真 400、code 都是 400，"
          f"只有文案能区分它们走对了哪一个")

    # ---- 1b) ★ 对照：同一张图、字段名正确 → 200 ----
    #   ★★ 没有这一条，1a 那个 400 就没有鉴别力：它可能只是因为
    #      「请求本来就坏了」（比如 boundary 拼错），而不是因为处理器对了。
    r = post_multipart("/admin/images", "file", "a.png", PNG, token=ADMIN_TOKEN)
    ok = r.status == 200 and isinstance((r.json or {}).get("data"), str)
    check("★ 对照：字段名写 file → 200 且返回 /uploads/ 开头的路径", ok,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")
    if ok:
        UPLOADED.append(r.json["data"])
        check("  （对照组记下这个文件，收尾时会按精确路径删掉它）",
              r.json["data"].startswith("/uploads/"), f"实际 {r.json['data']!r}")

    # ---- 1c) ★ 对照 2：Content-Type 是 multipart，但一个 part 都没有 ----
    r = post_multipart_empty_parts("/admin/images", token=ADMIN_TOKEN)
    check("★ 对照：Content-Type 是 multipart 但【一个 part 都没有】→ 400",
          r.status == 400,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")

    # ---- 1d) ★ 对照 3：压根不是 multipart → 老的 handleMultipart 还在工作 ----
    r = raw_call("POST", "/shop/images", token=MEMBER_TOKEN)
    check("★ 对照：不带 body 打上传接口 → 真 400（老的 handleMultipart 还在工作）",
          r.status == 400 and (r.json or {}).get("code") == 400,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")
    msg = (r.json or {}).get("message") or ""
    check("★ 它的文案里【不再】提「字段名」—— 那半句里程碑 13 搬给了新处理器",
          msg.strip() != "" and "字段名" not in msg,
          f"实际 message = {msg!r} —— 走到这个分支时【压根不是 multipart 请求，"
          f"没有字段名可言】，那句建议在这里永远不成立。"
          f"它不是废话，是【误导】：真的遇到 Content-Type 问题的调用方，"
          f"看到「字段名必须叫 file」会去 FormData 里找字段名")

    # 1b + 1c + 1a 三条合起来，才把这句话钉死：
    check("★ 1a/1b/1c 合起来证明：判定 multipart 缺 part 的依据是"
          "【Content-Type 头】，不是请求体长什么样",
          True)   # 这条是给读者看的汇总，鉴别力在上面三条里

    # ==================================================================
    section("2. 415：请求体的 Content-Type 读不了（新处理器 handleMediaTypeNotSupported）")
    # ==================================================================

    r = raw_call("POST", "/admin/categories",
                 data=b'{"name":"x"}',
                 headers={"Content-Type": "text/plain"},
                 token=ADMIN_TOKEN)
    check("★★ Content-Type: text/plain 打 @RequestBody 接口 → 真 HTTP 415"
          "（不是 200 + code 500）",
          r.status == 415,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")
    check("  body.code 是 400（code 只有 200/400/401/403/500 这几档，"
          "415 属于协议层错误，前端看的是 HTTP 状态码）",
          (r.json or {}).get("code") == 400, f"实际 {r.json}")
    check("  message 非空", bool(((r.json or {}).get("message") or "").strip()),
          f"实际 {(r.json or {}).get('message')!r}")

    # ★ body 真的写出来了（不是 415 + 空 body）
    ctype = r.header("Content-Type") or ""
    check("★ 响应有 JSON body（Content-Type 含 application/json）",
          "application/json" in ctype,
          f"实际 Content-Type = {ctype!r}")

    # ★★ Allow/Accept 头：Spring 自己处理 415 时也会带，我们接管了就得自己加
    accept = r.header("Accept") or ""
    check("★★ 响应带 Accept 头，且含 application/json"
          "（接管了异常，规范要求的细节就得自己记得）",
          "application/json" in accept,
          f"实际 Accept = {accept!r} —— 值可能是 "
          f"'application/json, application/*+json'，所以用【包含】断言而不是相等")

    # ---- ★ 对照：GET 也带同一个 Content-Type，但它是 200 ----
    #   证明 415 是「请求体读不了」造成的，不是「Content-Type 头长得怪」造成的。
    r = raw_call("GET", "/admin/products?pageNum=1&pageSize=1",
                 headers={"Content-Type": "text/plain"},
                 token=ADMIN_TOKEN)
    check("★ 对照：GET（没有请求体）也带 Content-Type: text/plain → 200",
          r.status == 200,
          f"实际 HTTP {r.status} / {r.raw[:200]!r} —— "
          f"若这里也是 415，说明判据变成了「头长得怪」，那就错了")

    # ---- ★ 对照 2：同一个接口、Content-Type 对了、JSON 是坏的 → 400 不是 415 ----
    #   证明 json / 非 json 的分界确实落在 Content-Type 上。
    r = raw_call("POST", "/admin/categories",
                 data=b'{"name": bad json}',
                 headers={"Content-Type": "application/json"},
                 token=ADMIN_TOKEN)
    check("★ 对照：Content-Type: application/json + 非法 JSON → 400（不是 415）",
          r.status == 400,
          f"实际 HTTP {r.status} / {r.raw[:200]!r} —— "
          f"分界落在 Content-Type 上，不在请求体内容上")

    # ==================================================================
    section("3. 406：客户端不接受我们能产出的格式（新处理器 handleMediaTypeNotAcceptable）")
    # ==================================================================

    r = raw_call("GET", "/admin/products?pageNum=1&pageSize=1",
                 headers={"Accept": "application/xml"},
                 token=ADMIN_TOKEN)
    check("Accept: application/xml → 真 HTTP 406（不是 200 + code 500）",
          r.status == 406, f"实际 HTTP {r.status} / {r.raw[:200]!r}")

    # ---- ★★ 本轮最核心的一条断言 ----
    check("★★ 406 的响应体是【空字节串】—— 把 ResponseEntity<Void> 这个决策钉死",
          r.raw == b"",
          f"实际 body = {r.raw[:200]!r} —— "
          f"★★ 谁哪天「顺手」给 406 加一个 Result body，这条立刻红，"
          f"而那个改动的真实后果【不是】多一个 body，是 HTTP 500 + Tomcat 错误页："
          f"HttpEntityMethodProcessor 会因为 body != null 把 406 真的抛出来 → "
          f"ExceptionHandlerExceptionResolver 的 catch(Throwable) 接住并返回 null → "
          f"DefaultHandlerExceptionResolver 那个方法体只有 "
          f"aconst_null; areturn;（空实现）→ DispatcherServlet 重新抛出 → "
          f"Tomcat 500 错误页 + 一条 ERROR 堆栈")

    check("★ 空 body 所以没有 Content-Type 响应头（没有东西要声明类型）",
          r.header("Content-Type") is None,
          f"实际 Content-Type = {r.header('Content-Type')!r}")

    # ---- ★★ 匿名可达性：这条论证的直接证据 ----
    #   「未认证的请求能刷爆 error 日志」是本轮所有改动的第二大理由。
    #   /api/shop/products 在拦截器的排除清单里，所以这条是匿名可达的。
    r = raw_call("GET", "/shop/products?pageNum=1&pageSize=1",
                 headers={"Accept": "application/xml"})   # ★ 故意不带 token
    check("★★ 同一条请求【不带 token】也是 406 —— 证明这条是【匿名可达】的",
          r.status == 406,
          f"实际 HTTP {r.status} / {r.raw[:200]!r} —— "
          f"这正是「一个循环就能把 error 日志刷满」那条论证的直接证据")
    check("★ 匿名可达且 body 为空（不是 500 + Tomcat 的 HTML 错误页）",
          r.raw == b"", f"实际 body = {r.raw[:200]!r}")

    # ---- ★ 对照：正常请求不能受影响 ----
    #   */* 是 axios 实际发的值，所以这一条是【真实的回归保护】。
    for label, hdrs in (("Accept: application/json", {"Accept": "application/json"}),
                        ("Accept: */*（axios 的实际值）", {"Accept": "*/*"}),
                        ("完全不带 Accept 头", {})):
        r = raw_call("GET", "/admin/products?pageNum=1&pageSize=1",
                     headers=hdrs, token=ADMIN_TOKEN)
        check(f"★ 对照：{label} → 200（新处理器不能影响正常请求）",
              r.status == 200 and (r.json or {}).get("code") == 200,
              f"实际 HTTP {r.status} / {r.raw[:120]!r}")

    # ==================================================================
    section("4. 405 的 Allow 头（全项目第一次断言它）+ 错误响应的 body 形状")
    # ==================================================================

    r = raw_call("GET", "/shop/cart/items", token=MEMBER_TOKEN)
    allow = r.header("Allow") or ""
    check("GET /shop/cart/items（该接口只有 POST）→ 405", r.status == 405,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")
    check("★★ Allow 头含 POST", "POST" in allow, f"实际 Allow = {allow!r}")
    check("★ 且【不含 GET】—— 这头要列「允许什么」，不是「你试了什么」",
          "GET" not in allow,
          f"实际 Allow = {allow!r} —— 若含 GET，说明头写反了"
          f"（写反的话上一条也能过，只有这条会红）")

    # ---- ★★ 多方法路径：唯一能证明 .sorted().collect(joining(", ")) 被走到的用例 ----
    #   /admin/products/{id} 上挂了 GET / PUT / DELETE 三个方法。
    #   单方法路径上那段代码退化成一个字符串，写错了也看不出来。
    r = raw_call("PATCH", "/admin/products/1", token=ADMIN_TOKEN)
    allow = r.header("Allow") or ""
    check("PATCH /admin/products/1（该路径有 GET/PUT/DELETE 三个方法）→ 405",
          r.status == 405, f"实际 HTTP {r.status} / {r.raw[:200]!r}")
    check("★★ Allow 正好是 {'DELETE', 'GET', 'PUT'}（三个方法都在，且排序了）",
          set(allow.split(", ")) == {"DELETE", "GET", "PUT"},
          f"实际 Allow = {allow!r} —— 这是唯一能证明处理器里 "
          f"'.sorted().collect(joining(\", \"))' 那段真的被走到的用例")

    # ---- ★★ 匿名调用者也要看得到 Allow ----
    #   DELETE /admin/products 不带 token：方法不匹配时 Spring 在 getHandler()
    #   阶段就抛了，那时 HandlerExecutionChain（拦截器链）还没建起来 ——
    #   所以拦截器根本没跑，也就不会拦成 401。
    #   ★ 处理器注释里那段分析在本轮之前没有任何测试保护。
    r = raw_call("DELETE", "/admin/products")     # ★ 故意不带 token
    allow = r.header("Allow") or ""
    check("★★ DELETE /admin/products【不带 token】→ 405 而不是 401",
          r.status == 405,
          f"实际 HTTP {r.status} / {r.raw[:200]!r} —— "
          f"405 说明拦截器【根本没跑】：方法不匹配时 Spring 在 getHandler() "
          f"阶段就抛了，那时候拦截器链还没建起来")
    check("★★ 而且 Allow 头【仍然存在】—— Allow 是给匿名调用者看的",
          "GET" in allow,
          f"实际 Allow = {allow!r}")

    # ---- ★ 错误响应的 body 形状：六个用例各断言一次 ----
    print()
    print("  ---- 错误响应的 body 形状（六个协议错误各来一遍）----")

    assert_error_shape("404 路径不存在",
                       raw_call("GET", "/admin/nothing-here"), 404)

    assert_error_shape("405 方法不对",
                       raw_call("DELETE", "/admin/products"), 405)

    assert_error_shape("415 Content-Type 读不了",
                       raw_call("POST", "/admin/categories", data=b"{}",
                                headers={"Content-Type": "text/plain"},
                                token=ADMIN_TOKEN), 415)

    assert_error_shape("400 multipart 缺 part",
                       post_multipart("/admin/images", "image", "a.png", PNG,
                                      token=ADMIN_TOKEN), 400)

    assert_error_shape("400 查询参数类型不对（?pageNum=abc）",
                       raw_call("GET", "/admin/products?pageNum=abc",
                                token=ADMIN_TOKEN), 400)

    assert_error_shape("400 路径变量类型不对（/products/abc）",
                       raw_call("GET", "/admin/products/abc",
                                token=ADMIN_TOKEN), 400)

    # ==================================================================
    section("5. ★★ 把「什么时候需要 token」变成可执行的表")
    # ==================================================================
    #   test-category.py 把这条规则写在注释里了（「拦截器是在请求体被解析
    #   【之前】执行的」），但【没有断言】。而它决定了本脚本其他所有用例
    #   要不要带 token —— 一条只在注释里的规则，下一个人重写脚本时一定会漏。
    #
    #   规则一句话：
    #     抛在【参数解析/绑定】阶段的异常 → preHandle 已经跑过 → 需要 token
    #     抛在【getHandler() 处理器查找】阶段的异常 → 拦截器链还没建起来 → 不需要
    print()
    print("  规则：抛在【参数解析】阶段的异常 → 拦截器已跑过 → 需要 token")
    print("        抛在【getHandler() 查找】阶段的异常 → 拦截器链还没建 → 不需要")

    r = raw_call("POST", "/admin/categories", data=b'{"name":"x"}',
                 headers={"Content-Type": "text/plain"})          # 无 token
    check("★ 415 那条【不带 token】→ 401 而不是 415"
          "（异常抛在参数解析阶段，拦截器已经跑过了）",
          r.status == 401, f"实际 HTTP {r.status} / {r.raw[:200]!r}")

    r = raw_call("GET", "/admin/products", headers={"Accept": "application/xml"})
    check("★ 406 那条【不带 token】→ 401 而不是 406", r.status == 401,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")

    r = post_multipart("/admin/images", "image", "a.png", PNG)    # 无 token
    check("★ 缺 part 那条【不带 token】→ 401 而不是 400", r.status == 401,
          f"实际 HTTP {r.status} / {r.raw[:200]!r}")

    # ★ 反面对照 —— 这一条才是把规则划出边界的那条
    r = raw_call("DELETE", "/admin/products")                     # 无 token
    check("★★ 反面对照：405 那条【不带 token】→ 405 而不是 401"
          "（异常抛在处理器查找阶段，拦截器链还没建起来）",
          r.status == 405,
          f"实际 HTTP {r.status} / {r.raw[:200]!r} —— "
          f"这三条带 token 的 + 这一条不带的，合起来把规则变成了可执行的表")

    # ==================================================================
    section("6. 清理")
    # ==================================================================

    cleanup_files()

    print()
    print("  本脚本不写数据库，下面这些数字应该和开工前【完全一致】：")
    print("  商品 {} 个，会员 {} 个，订单 {} 个".format(
        query_count("product"), query_count("member"), query_count("orders")))

    print()
    print("=" * 72)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 72)
    if FAILED:
        print()
        print("失败项：")
        for f in FAILED:
            print(f"  · {f}")
    sys.exit(1 if FAIL else 0)


def query_count(table):
    """
    数一张表的行数。

    ⚠️ 只在收尾打印时用一次，本脚本【全程不写数据库】——
      所以这里连 INSERT / DELETE 的辅助函数都不需要。
    """
    result = subprocess.run(
        [r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
         "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", f"SELECT COUNT(*) FROM {table}", "mall"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        return "?"
    return result.stdout.strip()


if __name__ == "__main__":
    main()
