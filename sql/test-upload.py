# -*- coding: utf-8 -*-
"""
里程碑 11 测试：图片上传 + 商品图集

这个脚本填的是一个【真实存在的空白】：
在它之前，全项目 10 个测试脚本里 **PUT /api/admin/products/{id} 一次都没被调用过**
（所有 "PUT" 的命中都在 /shop/addresses、/shop/cart、/admin/categories）。
也就是说 ProductServiceImpl.update 这条路径完全没有测试覆盖 ——
而图集最核心的语义（null 不动 / [] 清空 / 有值全删重插）正好【全落在它身上】。

所以这一节不是「顺手补几条」，是这个功能唯一的防线。

它要守住的几类 bug：

  1. ★★ 魔数校验失效 —— 把一段纯文本改名成 .png 传上去必须被拒。
     这条断言的价值不在于「现在是对的」，而在于【挡住未来某个人
     「优化」成只看文件名或 Content-Type】。那两样都是客户端说了算的。

  2. ★★ 静态资源映射（addResourceHandlers）配错 —— 上传成功、返回了 URL、
     磁盘上文件也在，但 GET 不到。症状和「前端没写对」一模一样，
     混在一起查会很久。所以这里必须【真正 GET 一次】并核对 Content-Type。

  3. ★★ null / [] 的分界 —— 这是本轮最容易写错、也最难发现的一条。
     按「null 也当清空」实现的话，所有不传 images 的老测试脚本
     都会变成「每建一次商品就把图集抹一遍」，而且不报错。

  4. ★ sort_no 真的落库了 —— 光断言「返回 3 张」是没有鉴别力的：
     那 3 张按 id 排也还是那 3 张。必须提交一个【和插入顺序不同的排列】，
     才能证明顺序是 sort_no 决定的，而不是碰巧。

  5. ★★ 删商品时的孤儿图集 —— product_image 没有外键（全库约定），
     顺序删反了不会报任何错，只会留下永远查不到的垃圾行。
     和 test-order-list.py 里那条孤儿明细断言是同一个形状。

  6. ★ 磁盘文件清理 —— 本项目第一次做「文件系统清理」，
     所以格外小心：只删【本次脚本自己上传的路径】，逐个按 URL 映射回磁盘删。
     绝不做「清空 uploads 目录」或按前缀通配 —— tools/fixture-pay.py
     的注释专门写过为什么反对通配。

运行：
    python test-upload.py
"""

import json
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080/api"
HOST = "http://localhost:8080"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

# 上传目录。★ 这里写死相对路径是有风险的（mall.upload.dir 是相对【进程工作目录】的，
# 从 mall-server/ 启动落在 mall-server/uploads/）—— 所以脚本会自己核对它存不存在，
# 不存在就明确报出来，而不是让清理悄悄地什么都没删。
UPLOAD_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "mall-server", "uploads")

RUN = str(int(time.time()))[-8:]
PREFIX = "upl"

PASS = 0
FAIL = 0
FAILED = []

ADMIN_TOKEN = None
MEMBER_TOKEN = None
MEMBER_ID = None
CATEGORY_ID = None

# ★ 本次脚本上传过的所有 URL。清理时【只删这里面记着的】。
UPLOADED = []


# ----------------------------------------------------------------------
def call(method, path, body=None, token=None, timeout=30, base=BASE):
    url = base + path
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


def post_file(path, filename, content, content_type="image/png", token=None,
              field="file", timeout=60):
    """
    发一个 multipart/form-data 请求。

    ★ 全项目唯一需要手写 multipart 的地方 —— 现有的 call() 只会发 JSON。
      不用 requests 是因为那要多一个依赖，而这里需要的东西
      urllib 加几十行胶水就够了（而且它是标准库，脚本在任何机器上都能跑）。

    multipart 的 body 形状：
        --<boundary>\\r\\n
        Content-Disposition: form-data; name="file"; filename="x.png"\\r\\n
        Content-Type: image/png\\r\\n
        \\r\\n
        <文件字节>\\r\\n
        --<boundary>--\\r\\n

    ⚠️ 三个细节一个都不能错，否则服务端会报「缺少 file 参数」或者干脆解析失败：
       · 每个换行都必须是 \\r\\n（不是 \\n）
       · 头部和 body 之间有一个【空行】（就是那个单独的 \\r\\n）
       · 结束的 boundary 后面要跟 "--"

    ⚠️ filename 参数是【故意暴露出来】的：本脚本要用它测路径穿越
      （传 "../../../../evil.png"）。urllib 不做任何转义，
      所以这里能原样把恶意文件名送上去 —— 这正是我们要测的。
    """
    boundary = "----MallUpload" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n"
        f"\r\n"
    ).encode("utf-8")
    body = head + content + f"\r\n--{boundary}--\r\n".encode("utf-8")

    headers = {
        "Accept": "application/json",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Content-Length": str(len(body)),
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(BASE + path, data=body, headers=headers,
                                 method="POST")
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


def fetch_raw(path, base=HOST, timeout=30):
    """直接取一个 URL（不解析 JSON），返回 (状态码, Content-Type, 字节)。"""
    req = urllib.request.Request(base + path)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers.get("Content-Type", ""), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()


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


# ----------------------------------------------------------------------
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
def make_png(w=4, h=4, rgb=(255, 0, 0), compress_level=6):
    """
    造一张【真正合法的】PNG。

    ⚠️ 必须是真 PNG，不能是「随便几个字节 + .png 后缀」——
      服务端是按文件头（魔数）判断格式的，伪造的会被正确地拒掉，
      那样后面所有「上传成功」的用例都会失败，而失败原因看起来
      像是上传功能坏了。

    PNG 的结构（这里手写而不用 Pillow，是为了不给脚本加依赖）：
        \\x89PNG\\r\\n\\x1a\\n   ← 8 字节魔数
        IHDR 块            ← 宽高、位深、颜色类型
        IDAT 块            ← zlib 压缩后的像素数据
        IEND 块            ← 结束标记
      每个块的形状都是：长度(4) + 类型(4) + 数据 + CRC32(4)
    """
    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    # 每行开头那个 \x00 是「行过滤器类型」，PNG 每行都必须有
    raw = b"".join(b"\x00" + bytes(list(rgb) * w) for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, compress_level))
            + chunk(b"IEND", b""))


PNG = make_png()

# 一张比 2MB 大的 PNG，用来验大小限制。
# ★ 用 compress_level=0（不压缩）来把体积撑起来 —— 压缩过的纯色图
#   只有几 KB，造不出超限的效果。
BIG_PNG = make_png(w=1400, h=1400, rgb=(0, 0, 255), compress_level=0)


# ----------------------------------------------------------------------
def upload(content=PNG, filename="test.png", content_type="image/png",
           token=None, remember=True):
    st, r = post_file("/admin/images", filename, content, content_type,
                      token=ADMIN_TOKEN if token is None else token)
    if remember and isinstance(r, dict) and isinstance(r.get("data"), str):
        UPLOADED.append(r["data"])
    return st, r


def upload_ok(**kw):
    """上传并断言成功，返回 URL。失败时直接中断（后面的用例都依赖它）。"""
    st, r = upload(**kw)
    if r.get("code") != 200:
        raise SystemExit(f"上传测试图片失败：HTTP {st} / {r}")
    return r["data"]


def url_to_disk(url):
    """
    把 /uploads/2026/09/xx.png 映射回磁盘路径。

    ⚠️ 这里【只】做一次前缀剥离，不做任何通配 —— 见文件头的清理说明。
    """
    assert url.startswith("/uploads/"), f"不是上传路径：{url}"
    rel = url[len("/uploads/"):]
    # ⚠️ 多一道防御：映射结果必须还在 UPLOAD_ROOT 里面。
    #    url 是服务端生成的，理论上不会带 ".."，但清理代码是删文件的，
    #    多判一次的代价是零，出错时的代价是删掉别的目录。
    full = os.path.abspath(os.path.join(UPLOAD_ROOT, rel))
    root = os.path.abspath(UPLOAD_ROOT)
    if not full.startswith(root + os.sep):
        raise SystemExit(f"拒绝删除 uploads 目录之外的文件：{full}")
    return full


def create_product(name, stock=100, images="__OMIT__", cover=None):
    body = {"categoryId": CATEGORY_ID, "name": name, "price": 9.90,
            "stock": stock, "status": 1}
    # ★ 用哨兵值区分「不传这个字段」和「传 null」——
    #   本脚本要测的正是这两种情况的区别，所以不能都写成 body["images"] = None
    if images != "__OMIT__":
        body["images"] = images
    if cover:
        body["cover"] = cover
    st, r = call("POST", "/admin/products", body, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"建测试商品失败：HTTP {st} / {r}")
    return r["data"]


def update_product(pid, name, images="__OMIT__"):
    """PUT /api/admin/products/{id} —— ★ 里程碑 11 之前全项目没人调过这个接口。"""
    body = {"categoryId": CATEGORY_ID, "name": name, "price": 9.90,
            "stock": 100, "status": 1}
    if images != "__OMIT__":
        body["images"] = images
    return call("PUT", f"/admin/products/{pid}", body, token=ADMIN_TOKEN)


def admin_detail(pid):
    st, r = call("GET", f"/admin/products/{pid}", None, token=ADMIN_TOKEN)
    if r.get("code") != 200:
        raise SystemExit(f"查管理端商品详情失败：HTTP {st} / {r}")
    return r["data"]


def gallery_of(pid):
    """直查库拿图集顺序 —— ★ 不信任接口，直接看数据库里 sort_no 是什么。"""
    rows = run_sql(
        f"SELECT url, sort_no FROM product_image WHERE product_id = {pid} "
        f"ORDER BY sort_no, id")
    return [(r[0], int(r[1])) for r in rows]


def admin_login():
    global ADMIN_TOKEN, CATEGORY_ID
    st, r = call("POST", "/admin/auth/login",
                 {"username": "admin", "password": "123456"})
    ADMIN_TOKEN = (r.get("data") or {}).get("token") if isinstance(r, dict) else None
    if not ADMIN_TOKEN:
        raise SystemExit(f"管理员登录失败：HTTP {st} / {r}")
    CATEGORY_ID = int(run_sql("SELECT id FROM category ORDER BY id LIMIT 1")[0][0])


def register_member():
    global MEMBER_TOKEN, MEMBER_ID
    st, r = call("POST", "/shop/auth/register", {
        "username": f"{PREFIX}{RUN}",
        "password": "upl123456",
        "nickname": f"上传测试{RUN}",
    })
    if r.get("code") != 200:
        raise SystemExit(f"注册测试会员失败：HTTP {st} / {r}")
    MEMBER_TOKEN = r["data"]["token"]
    MEMBER_ID = r["data"]["id"]


# ----------------------------------------------------------------------
def cleanup():
    """
    ★ 顺序：图集 → 商品 → 会员。

    product_image 【没有外键】（全库约定），所以顺序删反了不会报任何错，
    只会留下孤儿行 —— 那正是本脚本末尾要断言的东西之一。
    """
    run_sql(f"DELETE FROM product_image WHERE product_id IN "
            f"(SELECT id FROM product WHERE name LIKE '{PREFIX}%')")
    run_sql(f"DELETE FROM product WHERE name LIKE '{PREFIX}%'")
    run_sql(f"DELETE FROM member WHERE username LIKE '{PREFIX}%'")


def cleanup_files():
    """
    ★★ 删磁盘上【本次脚本自己上传的】文件。

    这是本项目第一次做文件系统清理，所以规矩格外紧：
      · 只删 UPLOADED 里记着的路径，逐个映射回磁盘
      · 不做任何通配、不清空目录、不按前缀批量删
      · 记下清理前的文件总数，删完再数一次，核对「少掉的数量 == 删掉的数量」

    最后那一条是关键：它把「我删的都是我上传的」这件事从【信念】变成【断言】。
    """
    before = count_files()
    removed = 0
    missing = 0
    for url in UPLOADED:
        path = url_to_disk(url)
        try:
            os.remove(path)
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
    for sub in ("2026",):
        for d in [os.path.join(UPLOAD_ROOT, sub)]:
            if os.path.isdir(d):
                for month in os.listdir(d):
                    try:
                        os.rmdir(os.path.join(d, month))
                    except OSError:
                        pass
                try:
                    os.rmdir(d)
                except OSError:
                    pass


def count_files():
    n = 0
    for _, _, files in os.walk(UPLOAD_ROOT):
        n += len(files)
    return n


# ======================================================================
def main():
    global MEMBER_TOKEN

    if not os.path.isdir(UPLOAD_ROOT):
        raise SystemExit(
            f"找不到上传目录：{UPLOAD_ROOT}\n"
            f"它必须存在 —— 否则清理会静默地什么都没删。\n"
            f"启动过后端之后它会被 @PostConstruct 自动创建。")

    cleanup()
    admin_login()
    register_member()

    print()
    print(f"本次运行 RUN = {RUN}")
    print(f"上传目录 = {UPLOAD_ROOT}")
    print(f"清理前目录里已有 {count_files()} 个文件")

    # ==================================================================
    section("1. 正常上传")
    # ==================================================================

    url1 = upload_ok()
    check("PNG 上传成功，返回 /uploads/ 开头的路径",
          url1.startswith("/uploads/"),
          f"返回的是 {url1!r}")
    check("★ 路径形状是 /uploads/yyyy/MM/<uuid>.<ext>",
          url1.count("/") == 4 and url1.endswith(".png"),
          f"实际 {url1!r}")
    # uuid 原样是 36 位带连字符；服务端把连字符去掉了，所以是 32 位十六进制
    stem = url1.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    check("★ 文件名是 UUID，不含原始文件名",
          len(stem) == 32 and all(c in "0123456789abcdef" for c in stem),
          f"文件名是 {stem!r}（应该全是十六进制，长度 32）")

    # ---- ★★ 这一步才真正验证了 addResourceHandlers 配对了 ----
    st, ctype, body = fetch_raw(url1)
    check("★★ 能通过 /uploads/ 取回文件（addResourceHandlers 生效）",
          st == 200 and body == PNG,
          f"HTTP {st}, Content-Type={ctype!r}, {len(body)} 字节"
          f"（期待 200 + {len(PNG)} 字节）")
    check("★ 返回的 Content-Type 是图片类型，不是 text/html",
          ctype.startswith("image/"),
          f"实际 {ctype!r} —— 如果是 text/html，说明请求被 SPA fallback 接走了")

    # ---- ★★ 两个 dev server 的 proxy 都要有 /uploads ----
    #   ⚠️ 这条断言【故意】同时管两个端口，而且它测的不是后端 ——
    #     它测的是「两个 vite.config.js 里都配了那条代理」。
    #
    #   为什么必须在自动化测试里守住：漏配的症状是 **200 + text/html**
    #   （Vite 的 SPA fallback），<img> 解码失败 → ProductImage.vue 的
    #   @error 兜底 → 页面上安安静静显示「暂无图片」。
    #   没有 404、没有报错、控制台干干净净 ——
    #   靠人眼验收时，它会和「后端没存下文件」长得一模一样。
    for port, who in ((5173, "管理端"), (5174, "用户端")):
        st, ctype, body = fetch_raw(url1, base=f"http://localhost:{port}")
        check(f"⚠️ {who}（:{port}）的 proxy 能取到同一个文件且是图片",
              st == 200 and ctype.startswith("image/") and body == PNG,
              f"HTTP {st}, Content-Type={ctype!r}, {len(body)} 字节 —— "
              f"若是 200+text/html，说明 {who} 的 vite.config.js "
              f"少了 /uploads 那条 proxy（Vite 走了 SPA fallback）")

    url2 = upload_ok()
    check("★ 同一张图传两次得到两个不同的路径（UUID 不覆盖）",
          url1 != url2, f"两次都返回了 {url1!r}")

    # ==================================================================
    section("2. 安全：魔数校验 / 文件名 / SVG")
    # ==================================================================

    st, r = upload(content=b"this is definitely not an image, just text",
                   filename="fake.png", content_type="image/png")
    check("★★ 纯文本改名成 .png → 被拒（靠文件头判断，不看文件名）",
          r.get("code") == 400,
          f"实际 HTTP {st} / {r} —— 如果这里是 200，说明魔数校验没生效")

    # ⚠️ 这条要单独测：Content-Type 是可以被伪造的，只信它等于没校验
    st, r = upload(content=b"<?php echo 1; ?>", filename="shell.php",
                   content_type="image/png")
    check("★ 伪造 Content-Type: image/png 也拦得住",
          r.get("code") == 400, f"实际 HTTP {st} / {r}")

    st, r = upload(content=b'<svg xmlns="http://www.w3.org/2000/svg">'
                           b'<script>alert(1)</script></svg>',
                   filename="evil.svg", content_type="image/svg+xml")
    check("★ SVG 被拒（能内嵌 script，是最经典的图片 XSS 载体）",
          r.get("code") == 400,
          f"实际 HTTP {st} / {r} —— 白名单里【故意】不放 SVG，见 FileStorageServiceImpl")

    st, r = upload(filename="../../../../evil.png")
    check("★ 文件名 ../../../../evil.png → 成功但落盘路径不含 ..",
          r.get("code") == 200 and ".." not in (r.get("data") or ""),
          f"实际 HTTP {st} / {r}")
    if r.get("code") == 200:
        disk = url_to_disk(r["data"])
        check("★ 恶意文件名没有逃出 uploads 目录",
              os.path.abspath(disk).startswith(os.path.abspath(UPLOAD_ROOT) + os.sep),
              f"映射到 {disk}")
        check("★ 恶意文件名里的 evil 一点都没保留",
              "evil" not in os.path.basename(disk),
              f"落盘文件名是 {os.path.basename(disk)!r}")

    # ==================================================================
    section("3. 大小限制")
    # ==================================================================

    st, r = upload(content=BIG_PNG, filename="big.png")
    check(f"★★ 超过 2MB 的图上传说人话（{len(BIG_PNG) / 1024 / 1024:.1f}MB）",
          r.get("code") == 400 and "大" in (r.get("message") or ""),
          f"实际 HTTP {st} / {r} —— 若是 code 500「系统繁忙」，"
          f"说明 MaxUploadSizeExceededException 的处理器没生效")

    # ==================================================================
    section("4. 鉴权")
    # ==================================================================

    st, r = post_file("/admin/images", "test.png", PNG, token=None)
    check("★ 未登录上传 → HTTP 401",
          st == 401, f"实际 HTTP {st} / {r}")

    st, r = post_file("/admin/images", "test.png", PNG, token=MEMBER_TOKEN)
    check("★ 会员 token 打管理端上传接口 → HTTP 401",
          st == 401,
          f"实际 HTTP {st} / {r} —— 这是「JWT 必须带 type claim」的回归")

    # ==================================================================
    section("5. 图集 CRUD（走 PUT /api/admin/products/{id}，全项目首次覆盖）")
    # ==================================================================

    a = upload_ok()
    b = upload_ok()
    c = upload_ok()

    pid = create_product(f"{PREFIX}图集商品", images=[a, b, c])
    got = gallery_of(pid)
    check("★ 建商品带 3 张图 → 库里正好 3 行",
          len(got) == 3, f"实际 {len(got)} 行：{got}")
    check("★ 顺序和提交的一致（a, b, c）",
          [u for u, _ in got] == [a, b, c],
          f"实际 {[u for u, _ in got]}")
    check("★ sort_no 从 0 开始连续（0,1,2）",
          [s for _, s in got] == [0, 1, 2], f"实际 {[s for _, s in got]}")

    d = admin_detail(pid)
    check("管理端详情返回 images，且顺序一致",
          d.get("images") == [a, b, c], f"实际 {d.get('images')}")

    # ---- ★★ 换一个和插入顺序不同的排列 ----
    #   如果只提交 [a,b,c]，那么「按 sort_no 排」和「按 id 排」结果一样，
    #   用例没有鉴别力 —— 顺序功能是坏的也照样通过。
    st, r = update_product(pid, f"{PREFIX}图集商品", images=[c, a, b])
    check("PUT 改图集顺序 → 接口返回成功", r.get("code") == 200, f"{st} / {r}")

    got = gallery_of(pid)
    check("★★ 库里顺序真的变成了 c, a, b（sort_no 生效，不是碰巧按 id 排的）",
          [u for u, _ in got] == [c, a, b],
          f"实际 {[u for u, _ in got]} —— 期待 {[c, a, b]}")
    check("★ sort_no 重新从 0 连续编号",
          [s for _, s in got] == [0, 1, 2], f"实际 {[s for _, s in got]}")

    d = admin_detail(pid)
    check("详情接口的顺序也跟着变了", d.get("images") == [c, a, b],
          f"实际 {d.get('images')}")

    # ---- ★★ null / [] 的分界：本轮最有价值的两条 ----
    st, r = update_product(pid, f"{PREFIX}图集商品", images=[])
    check("提交 images: [] → 接口成功", r.get("code") == 200, f"{st} / {r}")
    check("★★ images: [] 把图集清空了",
          len(gallery_of(pid)) == 0,
          f"实际还剩 {len(gallery_of(pid))} 行")

    # 先放回去 2 张，再验「不传这个字段」不会动它
    st, r = update_product(pid, f"{PREFIX}图集商品", images=[a, b])
    check("重新放入 2 张", len(gallery_of(pid)) == 2, f"实际 {gallery_of(pid)}")

    # ★ 这里【不传 images 这个字段】（不是传 null，是压根没有这个 key）
    st, r = update_product(pid, f"{PREFIX}图集商品改个名")
    check("PUT 改名（请求体里没有 images 字段）→ 成功",
          r.get("code") == 200, f"{st} / {r}")
    check("★★ 不传 images → 图集原样不动（不是被清空）",
          [u for u, _ in gallery_of(pid)] == [a, b],
          f"实际 {gallery_of(pid)} —— 若为空，说明「null 当清空」了，"
          f"那会让所有不传这个字段的老接口调用都静默抹掉图集")

    # ---- 重复的 URL 允不允许？允许（图集是引用不是所有权） ----
    st, r = update_product(pid, f"{PREFIX}图集商品", images=[a, a])
    check("★ 同一张图可以出现两次（图集是引用，没有唯一约束）",
          r.get("code") == 200 and len(gallery_of(pid)) == 2,
          f"{st} / {r}，库里 {gallery_of(pid)}")

    # ---- 条数上限 ----
    st, r = update_product(pid, f"{PREFIX}图集商品", images=[a, b, c, a, b, c])
    check("★ 提交 6 张（超过 @Size(max=5)）→ 400 校验错误",
          r.get("code") == 400, f"实际 HTTP {st} / {r}")

    # ---- ★ 只接受自己上传的文件 ----
    st, r = update_product(pid, f"{PREFIX}图集商品", images=["/images/phone-03.svg"])
    check("★★ 提交 /images/ 老路径 → 被拒（只认本服务上传的图）",
          r.get("code") == 400,
          f"实际 HTTP {st} / {r} —— 图集没有历史包袱，不需要为兼容放开这条")

    st, r = update_product(pid, f"{PREFIX}图集商品",
                           images=["https://example.com/track.gif"])
    check("★★ 提交外链 → 被拒（否则每个访客的浏览器都会去请求第三方地址）",
          r.get("code") == 400, f"实际 HTTP {st} / {r}")

    # ==================================================================
    section("6. 删商品：不留孤儿图集")
    # ==================================================================

    st, r = call("DELETE", f"/admin/products/{pid}", None, token=ADMIN_TOKEN)
    check("删商品成功", r.get("code") == 200, f"{st} / {r}")
    check("★★ 该商品的图集行也被删掉了（不是留成孤儿）",
          len(gallery_of(pid)) == 0,
          f"实际还剩 {len(gallery_of(pid))} 行")

    # ==================================================================
    section("7. 用户端")
    # ==================================================================

    p2 = create_product(f"{PREFIX}用户端商品", images=[a, b], cover=a)
    st, r = call("GET", f"/shop/products/{p2}", None)
    check("用户端详情能拿到 images",
          r.get("code") == 200 and r["data"].get("images") == [a, b],
          f"实际 {r.get('data', {}).get('images')}")

    # 改顺序后用户端也要跟着变
    update_product(p2, f"{PREFIX}用户端商品", images=[b, a])
    st, r = call("GET", f"/shop/products/{p2}", None)
    check("用户端详情顺序跟着 sort_no 变",
          r["data"].get("images") == [b, a], f"实际 {r['data'].get('images')}")

    st, r = call("GET", "/shop/products?pageNum=1&pageSize=5", None)
    check("★ 用户端【列表】不返回 images（列表页不显示它，带上就是白查）",
          r.get("code") == 200
          and all("images" not in p for p in r["data"]["list"]),
          f"实际第一行 {r.get('data', {}).get('list', [{}])[0]}")

    st, r = call("GET", f"/admin/products?pageNum=1&pageSize=5", None,
                 token=ADMIN_TOKEN)
    check("★ 管理端【列表】也不返回 images",
          r.get("code") == 200
          and all("images" not in p for p in r["data"]["list"]),
          f"实际第一行 {r.get('data', {}).get('list', [{}])[0]}")

    # ★ 列表是 ORDER BY id DESC，而 p2 是刚才建的、之后没再建过别的 →
    #   第一行一定是 p2。（不靠猜：p2 的 cover 是我们自己设的，
    #   值对得上才说明 cover 真的从列表接口传出来了。）
    #
    # ⚠️ 这条断言第一版写的是 "cover" in list[0]，结果失败了 ——
    #   因为建 p2 时根本没传 cover，值是 null，被 Jackson 的
    #   default-property-inclusion: non_null 直接从 JSON 里去掉了。
    #   「字段在不在」这种断言必须配上一个【自己控制的值】，
    #   否则测的是「这行数据碰巧有没有值」，不是「接口有没有这个字段」。
    check("★ cover 字段照旧存在，且值原样传出来（图集没有取代它）",
          r["data"]["list"][0].get("cover") == a,
          f"实际 cover={r['data']['list'][0].get('cover')!r}（期待 {a!r}）；"
          f"该行全部字段：{sorted(r['data']['list'][0].keys())}")

    # ---- 老商品：图集为空 ----
    old = create_product(f"{PREFIX}无图商品")
    st, r = call("GET", f"/shop/products/{old}", None)
    check("★ 老商品（图集为空）的详情照旧正常返回",
          r.get("code") == 200, f"实际 {st} / {r}")
    check("★ 图集为空时返回的是 []（不是 null，也不是没有这个 key）",
          r["data"].get("images") == [],
          f"实际 {r['data'].get('images')!r} —— "
          f"空列表让前端可以直接写 product.images.length")

    st, r = call("GET", f"/admin/products/{old}", None, token=ADMIN_TOKEN)
    check("★ 管理端同理", r["data"].get("images") == [],
          f"实际 {r['data'].get('images')!r}")

    # ==================================================================
    section("8. 全库不变量")
    # ==================================================================

    orphans = run_sql("SELECT COUNT(*) FROM product_image i "
                      "LEFT JOIN product p ON p.id = i.product_id "
                      "WHERE p.id IS NULL")
    check("★★ 全库没有孤儿图集行",
          int(orphans[0][0]) == 0,
          f"有 {orphans[0][0]} 行图集指向不存在的商品 —— "
          f"多半是删商品时忘了先删图集")

    # 参数化占位符不该出现在数据里
    bad = run_sql("SELECT COUNT(*) FROM product_image "
                  "WHERE url NOT LIKE '/uploads/%'")
    check("★ 库里所有图集地址都是 /uploads/ 开头",
          int(bad[0][0]) == 0, f"有 {bad[0][0]} 行不是")

    # ==================================================================
    section("9. 清理")
    # ==================================================================

    cleanup()
    check("测试商品已清理",
          int(run_sql(f"SELECT COUNT(*) FROM product WHERE name LIKE "
                      f"'{PREFIX}%'")[0][0]) == 0)
    check("测试会员已清理",
          int(run_sql(f"SELECT COUNT(*) FROM member WHERE username LIKE "
                      f"'{PREFIX}%'")[0][0]) == 0)

    cleanup_files()

    print()
    print("  你的数据：商品 {} 个，分类 {} 个，会员 {} 个".format(
        run_sql("SELECT COUNT(*) FROM product")[0][0],
        run_sql("SELECT COUNT(*) FROM category")[0][0],
        run_sql("SELECT COUNT(*) FROM member")[0][0]))

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


if __name__ == "__main__":
    main()
