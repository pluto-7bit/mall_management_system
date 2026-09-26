# -*- coding: utf-8 -*-
"""
里程碑 14 测试：金额显示的收口（静态检查）

这个脚本和仓库里其它脚本【又不一样】，先说清它站在哪儿：

    test-order.py              打接口，问「后端的【行为】对不对」
    test-address.py            打接口，问「后端的【行为】对不对」
    test-frontend-contract.py  打接口，问「前端的【假设】对不对」
    这个脚本                    不打接口，问「前端的【写法】对不对」

为什么要单独问「写法」？
    里程碑 14 发现购物车页面上同一笔钱有两种长相：
    第 421 行显示「¥599」（因为 JSON 的数字就是数字，
    JSON.parse('599.00') 得到 JS 的 599，尾随的 0 没了），
    而同一页第 515 行的合计显示「¥599.00」。用户会以为哪里算错了。

    修它的办法只有一个：让**每一处显示金额的地方都走同一个函数**。
    这件事接口层面一个字节都看不见 —— 发出去的 JSON 完全没变，
    所以仓库里那 13 个打接口的脚本【全都证明不了它修好了】。
    能守住它的只有两样东西，这个脚本是其中之一
    （另一样是浏览器验收，见 tools/shot.py 的 --eval）。

    一句话：**这轮的正确性只存在于源码和浏览器里，不存在于 HTTP 响应里。**

它检查四条规则：
  1. mall-shop 里「保留两位小数」的实现**只有一份**
  2. mall-shop 里每一处金额显示都**走了那个函数**
  3. mall-web 里每一处金额显示都**走了某种格式化**（不要求收口，只要求别退步）
  4. mall-web **没有**第二个同名文件（哨兵）

★ 它不需要后端、MySQL、Redis 任何一个在跑。
  这是它和 test-exception.py 同类的地方 —— 又一个"不需要前置条件"的例外，
  值得说清楚：因为它读的是源码，不是运行时。

运行：
    python test-frontend-format.py
"""

import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

# ---------------------------------------------------------------------------
# 路径
#
# 用绝对路径而不是相对路径：README 让人 `cd sql` 之后再跑脚本，
# 但你要是从仓库根目录跑、或者从别处跑，相对路径就全错了。
# 这里以脚本自身的位置为锚点，从哪儿跑都一样。
# ---------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHOP_SRC = os.path.join(ROOT, "mall-shop", "src")
WEB_SRC = os.path.join(ROOT, "mall-web", "src")

# 唯一的允许定义「两位小数」的文件（相对各自的 src 目录）
ONLY_IMPLEMENTATION = os.path.join("utils", "format.js")

PASS = 0
FAIL = 0
FAILED = []


def check(label, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [OK]   {label}")
    else:
        FAIL += 1
        print(f"  [FAIL] {label}")
        FAILED.append(label)
        if detail:
            for line in str(detail).splitlines():
                print(f"         {line}")


def section(title):
    print(f"\n{'=' * 72}")
    print(f"  {title}")
    print(f"{'=' * 72}")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def vue_files(src_dir):
    """列出 src 下所有 .vue 文件，返回 (绝对路径, 相对 src 的路径) 的列表。

    排序是为了输出稳定 —— 不然两个人在两台机器上跑，
    同样的失败会以不同顺序打印出来，看着像两个问题。
    """
    out = []
    for dirpath, _dirnames, filenames in os.walk(src_dir):
        for name in sorted(filenames):
            if not name.endswith(".vue"):
                continue
            full = os.path.join(dirpath, name)
            out.append((full, os.path.relpath(full, src_dir)))
    return sorted(out, key=lambda p: p[1])


def strip_noise(text):
    """
    剥掉 HTML 注释、<script> 和 <style> 块，只留下真正会渲染出来的模板文本。

    ★ 为什么必须剥：
      Orders.vue 的注释里写着「共 1 件，合计 ¥19.80」，
      Home.vue 的 <style> 里写着「/* ¥ 符号比数字小一号…… */」，
      Cart.vue 的 JSDoc 里写着「后者必须和 ¥ 符号待在一起」。
      这些地方今天【恰好】不带 {{ }} 或不在模板里，所以没被误判 ——
      但**「侥幸没被误判」不该是检查的设计依据**。
      下一个人往注释里写一句带 {{ }} 的金额示例，检查就会假红一次，
      然后他会做的不是"改注释"，而是"把这条检查删掉"。
      所以显式剥掉，让规则说得出自己边界在哪。

    ★ 连 <script> 一起剥，是为了让下面那节「上报」的数字有意义：
      金额显示只可能出现在模板里，脚本里出现的 ¥ 一定是注释或字符串。
      不剥的话 Cart.vue 会报「5 次 ¥ / 4 处插值」，看的人要去查半天，
      查完发现是句注释 —— 一个会喊狼来了的检查，很快就会被无视。
    """
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"<script\b.*?</script>", "", text, flags=re.S | re.I)
    text = re.sub(r"<style\b.*?</style>", "", text, flags=re.S | re.I)
    return text


# ★ 匹配「紧跟在 ¥ 后面的那个插值」。
#
# 中间允许夹**完整的 HTML 标签**和空白，因为首页的写法是：
#     <span class="product-price"><i>¥</i>{{ p.price }}</span>
# 符号被 <i> 包着（那是刻意的样式，见 Home.vue 的 <style>），
# 所以 ¥ 和 {{ 之间隔着一个 </i>。
#
#   ¥{{ item.price }}      → 中间什么都没有
#   ¥</i>{{ p.price }}     → 中间是 </i>
#
# ⚠️ 已知盲区（写在这里而不是假装没有）：
#    如果哪天有人把符号写进插值里（{{ '¥' + price }}），
#    或者写成 ¥</span><span>{{ x }}（跨了两个元素），
#    这条规则会漏掉。所以下面会额外上报「¥ 出现几次 / 匹配到几次」，
#    让你一眼看出对不上 —— 而不是让它安静地漏过去。
MONEY_INTERP = re.compile(r"¥(?:\s*</?[a-zA-Z][a-zA-Z0-9-]*[^<>]*>|\s)*\{\{([^}]*)\}\}")


def describe(captures):
    """把捕获到的插值体拼成一段可读的失败详情。"""
    return "\n".join(f"· {{{{{c.strip()}}}}}" for c in captures)


# ===========================================================================
section("规则 1：mall-shop 里「保留两位小数」只有一份实现")
# ===========================================================================
#
# 判据是「这个数是不是钱」，不是「有没有调 toFixed」——
# 所以只认 toFixed(2)，不认 toFixed。
#
# ★ 为什么这条不能写成「toFixed(2) 全局只出现一次」：
#   utils/format.js 自己的注释里就有 "toFixed(2)" 这个字符串。
#   数【次数】会让这条断言无谓地脆（改一句注释就红），
#   而我们真正关心的是「还有没有【第二个文件】在做这件事」。
#   所以按文件计，不按次数计。

implementations = []
for full, rel in vue_files(SHOP_SRC) + [
    (os.path.join(SHOP_SRC, "utils", "format.js"), ONLY_IMPLEMENTATION)
]:
    if not os.path.isfile(full):
        continue
    with open(full, encoding="utf-8") as f:
        if "toFixed(2)" in f.read():
            implementations.append(rel)

implementations = sorted(set(implementations))

check(
    f"mall-shop 里定义「两位小数」的文件恰好是 {ONLY_IMPLEMENTATION}",
    implementations == [ONLY_IMPLEMENTATION],
    f"实际是：{implementations}\n"
    f"多出来的文件说明又有人就地写了一份 —— 请把它改成\n"
    f"  import {{ formatAmount }} from '@/utils/format'",
)

# ===========================================================================
section("规则 2：mall-shop 每一处金额显示都走了那个函数")
# ===========================================================================
#
# 这条是里程碑 14 的核心产出：收口之后，
# **mall-shop 里每一个 ¥ 后面都跟着 formatAmount(，没有例外。**
#
# ★ 「没有例外」这四个字是关键。收口前的写法是让 computed 返回
#   格式化好的字符串（¥{{ selectedAmount }}），那样能少改两个模板，
#   但会留下【唯一一个】¥ 后面不跟 formatAmount( 的位置 ——
#   而这条规则就必须给它开一个例外。
#   例外是规则腐烂的起点，所以宁可多改那两个模板。

print("  （下面每行是「这个文件里有多少处金额显示」）")
shop_total = 0
shop_bad_total = 0

for full, rel in vue_files(SHOP_SRC):
    with open(full, encoding="utf-8") as f:
        text = strip_noise(f.read())

    bodies = MONEY_INTERP.findall(text)
    if not bodies:
        continue

    bad = [b for b in bodies if "formatAmount(" not in b]
    shop_total += len(bodies)
    shop_bad_total += len(bad)

    mark = "[OK]  " if not bad else "[FAIL]"
    print(f"    {mark} {rel}: {len(bodies)} 处")
    if bad:
        print(f"           ↑ 其中 {len(bad)} 处没走 formatAmount：")
        for b in bad:
            print(f"             · {{{{{b.strip()}}}}}")

check(
    f"mall-shop 的 {shop_total} 处金额显示全部走了 formatAmount",
    shop_bad_total == 0,
    f"{shop_bad_total} 处没走。改法：把 ¥{{{{ x }}}} 写成 ¥{{{{ formatAmount(x) }}}}，"
    f"并在 <script setup> 里 import {{ formatAmount }} from '@/utils/format'\n"
    f"⚠️ 唯一的例外是购物车失效商品那一行 —— 它的「查不到价格」"
    f"要显示成破折号而不是 0.00，所以写成\n"
    f"   ¥{{{{ item.price == null ? '—' : formatAmount(item.price) }}}}",
)

# ===========================================================================
section("规则 3：mall-web 每一处金额显示也都走了格式化")
# ===========================================================================
#
# ★ 这条【不要求 mall-web 收口】，只要求它别退步。
#
#   为什么两条规则不一样？因为两个工程的欠账不一样：
#     mall-shop 有 4 处【裸金额】在渲染（真 bug），所以必须收口；
#     mall-web 的 4 处【本来就已经格式化过了】，它没有账要清。
#
#   而且 mall-web 按它自己写在 order/List.vue 里的判据
#   （「等这里也出现第四处时再考虑提取」）现在只有 2 处，
#   提取是错的时机 —— 那条判据还是从 mall-shop 这边学过去的。
#
#   所以两个工程现在一份 / 两份，这个【不对称是对的】。
#   这条规则防的是「下一个人以为是没做完，顺手把 mall-shop 那份同步过去」。
#
# 这里允许 formatAmount( 也允许 .toFixed( —— 因为 mall-web 今天两种都有，
# 而这轮一个字都没改它。以后它自己收口了，这条断言会自动跟着满足。

web_total = 0
web_bad_total = 0

for full, rel in vue_files(WEB_SRC):
    with open(full, encoding="utf-8") as f:
        text = strip_noise(f.read())

    bodies = MONEY_INTERP.findall(text)
    if not bodies:
        continue

    bad = [b for b in bodies if "formatAmount(" not in b and ".toFixed(" not in b]
    web_total += len(bodies)
    web_bad_total += len(bad)

    mark = "[OK]  " if not bad else "[FAIL]"
    print(f"    {mark} mall-web/{rel}: {len(bodies)} 处")
    if bad:
        print(f"           ↑ 其中 {len(bad)} 处没有任何格式化：")
        for b in bad:
            print(f"             · {{{{{b.strip()}}}}}")

check(
    f"mall-web 的 {web_total} 处金额显示全部有格式化",
    web_bad_total == 0,
    f"{web_bad_total} 处没有 —— 和 mall-shop 第 14 轮修的是同一类问题",
)

# ===========================================================================
section("规则 4：哨兵 —— mall-web 没有第二个同名文件")
# ===========================================================================

web_format = os.path.join(WEB_SRC, "utils", "format.js")

check(
    "mall-web 没有 src/utils/format.js",
    not os.path.exists(web_format),
    "mall-web 出现了 utils/format.js。\n"
    "如果这是【有意的】（比如 mall-web 自己也攒够 4 处了），\n"
    "那就删掉这条断言，并把理由写进 order/List.vue 的注释里。\n"
    "⚠️ 但别只是因为「mall-shop 有一份」就同步一份过来：\n"
    "   两个工程刻意不共享代码，两份同名文件一定会分岔 ——\n"
    "   这件事 mall-web/src/utils/orderStatus.js 的开头已经论证过了。",
)

# ===========================================================================
section("规则 5：五张码表在【后端 ↔ 两端字典】之间双向一致")
# ===========================================================================
#
# ★★ 里程碑 17 新增。它防的是一类【完全不报错】的错：
#
#   后端加了第 6 个订单状态（`REFUNDED = 5`），前端字典没跟上。后果：
#     `orderStatusLabel(5)` 走到 default，那一行显示「未知状态」——
#     页面正常、代码正常、控制台干净。只有用户看到四个字不对劲。
#   而管理端的筛选下拉漏了一项的后果更安静：
#     运营永远筛不出「已退款」的订单，他不会报 bug，
#     只会以为「就是没有这种单」。
#
# ★ 为什么这个文件管得了它：因为这些码表的「定义者」是 Java 常量类，
#   而它们的「手抄版」是前端字典 —— 两样都是【源码】。
#   这正是本脚本存在的理由（开头那段）：这轮的正确性不在 HTTP 响应里。
#
# ★★ 两个方向都要查，而且要一起查：
#     正向：后端每一个码都在字典里、值一样
#           → 抓「后端加了、前端没加」
#     反向：字典里不许有后端没有的名字
#           → 抓「前端多编了一个」
#   ⚠️ 只查正向的话，前端把 `REFUNDED: 5` 抄成 `REFUNDED: 6`（多编一个值）
#      依然全绿 —— 而它会和将来后端真加的第 6 个状态撞车。
#      这正是 README 里那条:「同一事实两份实现必然分岔，配一条断言。
#      而断言必须能抓住【两个方向】，否则它只保护了一半。」

JAVA_COMMON = os.path.join(ROOT, "mall-server", "src", "main", "java",
                           "com", "example", "mall", "common")

# ★ 只认 `public static final int` —— 那些才是对外契约。
#   文件里的私有常量、局部变量不该进这张表。
JAVA_CONST = re.compile(r"public\s+static\s+final\s+int\s+(\w+)\s*=\s*(\d+)\s*;")


def java_constants(filename):
    """读出 Java 常量类的 {名字: 值}；文件不存在或读不到时返回 None。"""
    path = os.path.join(JAVA_COMMON, filename)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        text = f.read()
    found = JAVA_CONST.findall(text)
    return {name: int(val) for name, val in found} if found else None


def js_dict(path, dict_name):
    """读出一个 `export const NAME = { A: 1, ... }` 字面量的 {名字: 值}。

    ★ 只认纯字面量的 `NAME: 123,`。写成算出来的值、或注释里的示例，
      都读不到 —— 这是好事：码表本来就【必须】是字面量，
      能算出来的码表意味着有人在别处又定义了一遍。
    """
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = re.search(rf"export const {dict_name}\s*=\s*\{{(.*?)\n\}}", text, re.S)
    if not m:
        return None
    return {n: int(v) for n, v in re.findall(r"(\w+)\s*:\s*(-?\d+)\s*,", m.group(1))}


def js_function_body(path, fn_name):
    """取出 `export function NAME(...) { ... }` 的函数体文本。"""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = re.search(rf"export function {fn_name}\s*\([^)]*\)\s*\{{(.*?)\n\}}", text, re.S)
    return m.group(1) if m else None


def compare_code_table(java_file, title, targets):
    """把一张 Java 码表和两端（或多端）的 JS 字典对着比。

    targets 里每一项是 (显示名, js 绝对路径, 字典名, label 函数名)。
    """
    backend = java_constants(java_file)
    check(f"读到了 {java_file}（{title}的权威定义）", backend is not None,
          f"文件不存在或没有 public static final int：{JAVA_COMMON}")
    if not backend:
        return

    for label_name, path, dict_name, fn_name in targets:
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        js = js_dict(path, dict_name)

        if js is None:
            check(f"{rel} 里有 {dict_name}", False,
                  f"读不到 `export const {dict_name} = {{ ... }}`。\n"
                  f"★ 后端现在有 {len(backend)} 个码：{sorted(backend)}")
            continue

        # ---- 正向：后端每一个都要在，且值一样 ----
        missing = {n: v for n, v in backend.items() if js.get(n) != v}
        check(f"{rel} 的 {dict_name} 覆盖后端全部 {len(backend)} 个码（{title}）",
              not missing,
              "这些码没抄过来或抄错了：\n" +
              "\n".join(f"  后端 {n} = {v}，前端是 {js.get(n, '【没有这个名】')}"
                        for n, v in missing.items()))

        # ---- 反向：不许有后端没有的 ----
        extra = {n: v for n, v in js.items() if n not in backend}
        check(f"{rel} 的 {dict_name} 没有后端不存在的码",
              not extra,
              "前端多编了这些（后端没有）：\n" +
              "\n".join(f"  {n} = {v}" for n, v in extra.items()) +
              "\n★ 只查正向的话，这一条永远抓不住 —— 而它会和将来"
              "后端真加的那个码撞车。")

        # ---- label 函数必须覆盖全部名字 ----
        body = js_function_body(path, fn_name)
        if body is None:
            check(f"{rel} 里有 {fn_name}()", False, "读不到函数体")
            continue
        uncovered = [n for n in backend
                     if f"case {n}:" not in body and f"case {dict_name}.{n}:" not in body]
        check(f"{rel} 的 {fn_name}() 覆盖全部 {len(backend)} 个码",
              not uncovered,
              f"这些码没有 case 分支（会走到 default）：{uncovered}")


SHOP_ORDER_JS = os.path.join(SHOP_SRC, "utils", "orderStatus.js")
WEB_ORDER_JS = os.path.join(WEB_SRC, "utils", "orderStatus.js")
SHOP_AS_JS = os.path.join(SHOP_SRC, "utils", "afterSaleStatus.js")
WEB_AS_JS = os.path.join(WEB_SRC, "utils", "afterSaleStatus.js")

compare_code_table("OrderStatus.java", "订单状态", [
    ("mall-shop", SHOP_ORDER_JS, "ORDER_STATUS", "orderStatusLabel"),
    ("mall-web", WEB_ORDER_JS, "ORDER_STATUS", "orderStatusLabel"),
])

compare_code_table("AfterSaleStatus.java", "售后状态", [
    ("mall-shop", SHOP_AS_JS, "AFTER_SALE_STATUS", "afterSaleStatusLabel"),
    ("mall-web", WEB_AS_JS, "AFTER_SALE_STATUS", "afterSaleStatusLabel"),
])

compare_code_table("AfterSaleReason.java", "售后申请原因", [
    ("mall-shop", SHOP_AS_JS, "AFTER_SALE_REASONS", "afterSaleReasonLabel"),
    ("mall-web", WEB_AS_JS, "AFTER_SALE_REASONS", "afterSaleReasonLabel"),
])

compare_code_table("AfterSaleType.java", "售后类型", [
    ("mall-shop", SHOP_AS_JS, "AFTER_SALE_TYPES", "afterSaleTypeLabel"),
    ("mall-web", WEB_AS_JS, "AFTER_SALE_TYPES", "afterSaleTypeLabel"),
])

# ★ 里程碑 18：第五张码表（物流节点状态）。
#
# ★★ 为什么它必须【单独写一段】，不能靠"以后加码表顺手也加一行"：
#   规则 5 的方向是「后端每一个码都要在前端字典里」——
#   所以【漏掉一整个 compare_code_table 调用】的后果是
#   「那张表谁都不量」，而其余四张照样全绿、整段看起来毫无异常。
#   这正是里程碑 17 那条「一份谁都不量的清单一定会过期」的同一个形状，
#   只不过这次过期的是「码表的清单」本身。
#
# ★ 它防的具体失败：前端少一个节点状态 → logisticsStatusLabel(3) 走 default
#   → 物流时间线上那一行显示「未知状态」。页面正常、控制台干净。
SHOP_LOGI_JS = os.path.join(SHOP_SRC, "utils", "logisticsStatus.js")
WEB_LOGI_JS = os.path.join(WEB_SRC, "utils", "logisticsStatus.js")

compare_code_table("LogisticsStatus.java", "物流节点状态", [
    ("mall-shop", SHOP_LOGI_JS, "LOGISTICS_STATUS", "logisticsStatusLabel"),
    ("mall-web", WEB_LOGI_JS, "LOGISTICS_STATUS", "logisticsStatusLabel"),
])

# ===========================================================================
section("规则 6：筛选选项的取值集合 —— 管理端必须【等于】，用户端必须【是子集】")
# ===========================================================================
#
# ★★ 这一条抓的是规则 5 抓不到的那一半：
#
#   规则 5 管「字典全不全」，这一条管「**用**字典的地方全不全」。
#   后端加了 `REFUNDED = 5`、前端字典也加了，但管理端筛选下拉忘了加第 6 项 ——
#   规则 5 全绿，而运营永远筛不出「已退款」的订单，
#   ★ 页面不报错、代码不报错、控制台干净。
#   README 里那条「一条不会红的检查等于没有检查」的兄弟:
#   **一个不会报错的缺口等于没有缺口 —— 除非有人专门去量它。**
#
# ★★ 「等于」和「是子集」是【两个不同的断言】，不是一个松一个紧：
#     管理端筛选：必须【等于】。五个状态就得有五个选项（外加「全部」）——
#                 少一项 = 筛不到，而筛不到是静默的。
#     用户端 Tab：必须是【子集】。它刻意不给「已取消」单独的 Tab，
#                 理由写在 Orders.vue 里（没人会专门去找一笔自己取消掉的订单）。
#                 ⚠️ 用「等于」会把它判红，然后下一个人会去改 Tab 而不是改断言 ——
#                 那正好把一个【有意的决定】改成了错误。
#   **断言写松了会漏，写紧了会逼着人做错事。**
#
# ⚠️ 读不懂的写法（不是 `XXX.NAME` 这种字面量引用）一律判红，不静默跳过 ——
#    否则把引用改成算出来的值就能绕过这条检查。


def resolve_option_values(text, array_name, dict_name, mapping):
    """从 `const NAME = [ { value: XXX.YYY, ... }, ... ]` 里解出值的集合。

    返回 (值的集合, 解不出来的原因)；解不出来时集合为 None。
    """
    m = re.search(rf"const {array_name}\s*=\s*\[(.*?)\n\]", text, re.S)
    if not m:
        return None, f"读不到数组 {array_name}"
    names = []
    for raw in re.findall(r"value:\s*([^,\n]+)", m.group(1)):
        raw = raw.strip()
        if raw == "null":
            continue  # 「全部」的哨兵，不参与比较（见 ORDER_STATUS_OPTIONS 的注释）
        mm = re.match(rf"{dict_name}\.(\w+)$", raw)
        if not mm:
            return None, f"{array_name} 里有一项写的是 `value: {raw}`，不是 {dict_name}.名字"
        names.append(mm.group(1))
    unknown = [n for n in names if n not in mapping]
    if unknown:
        return None, f"{array_name} 引用了 {dict_name} 里没有的名字：{unknown}"
    return {mapping[n] for n in names}, None


def check_options(display, path, array_name, dict_name, mapping, expected, must_equal):
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    if not os.path.exists(path):
        check(f"{rel} 里读得到 {array_name}", False, "文件不存在")
        return
    with open(path, encoding="utf-8") as f:
        text = f.read()
    values, err = resolve_option_values(text, array_name, dict_name, mapping)
    if values is None:
        check(f"{rel} 的 {array_name} 读得懂", False, err)
        return

    if must_equal:
        ok = values == expected
        detail = (f"缺了：{sorted(expected - values)}\n" if expected - values else "") + \
                 (f"多了：{sorted(values - expected)}" if values - expected else "")
        check(f"{rel} 的 {array_name} 取值集合 == 后端状态值集合（{display}）",
              ok, detail)
    else:
        ok = values <= expected
        detail = f"出现了后端没有的值：{sorted(values - expected)}" if not ok else ""
        check(f"{rel} 的 {array_name} 取值集合 ⊆ 后端状态值集合（{display}）",
              ok, detail)


def js_number_array(path, array_name):
    """读出一个 `const NAME = [0, 1, 2]` 这种纯数字数组。读不到返回 None。"""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = re.search(rf"const {array_name}\s*=\s*\[([^\]]*)\]", text)
    if not m:
        return None
    nums = re.findall(r"\d+", m.group(1))
    return {int(n) for n in nums} if nums else None


order_java = java_constants("OrderStatus.java") or {}
order_values = set(order_java.values())

# ★★ 这是本题里【第四份】订单状态的手抄本：
#   OrderStatus.java（真源）→ 两端 orderStatus.js 的字典 → Orders.vue 的 TABS
#   → 还有这里，utils/query.js 的 VALID_ORDER_STATUS。
#   它前四份都由规则 5 / 规则 6 看着，只有这一份是谁都不管的 ——
#   而它恰恰是「URL 能不能表达某个状态」的唯一决定者。
#   ★ 它现实地漏过一次（里程碑 17 加 REFUNDED 时），
#     症状是 `/orders?status=5` 被静默地消化成「全部」。
#   **一份谁都不量的清单，一定会过期。**
_order_query_path = os.path.join(SHOP_SRC, "utils", "query.js")
_valid = js_number_array(_order_query_path, "VALID_ORDER_STATUS")
check("mall-shop/src/utils/query.js 读得到 VALID_ORDER_STATUS", _valid is not None,
      "读不到 `const VALID_ORDER_STATUS = [ ... ]`")
if _valid is not None:
    check("VALID_ORDER_STATUS 的取值集合 == 后端状态值集合",
          _valid == order_values,
          (f"缺了：{sorted(order_values - _valid)}\n" if order_values - _valid else "") +
          (f"多了：{sorted(_valid - order_values)}" if _valid - order_values else "") +
          "\n★ 少一个值的后果是「URL 里那个 status 被静默地当成『全部』」——\n"
          "  页面显示全部订单，不报错、不警告。")

web_order_text_path = WEB_ORDER_JS
check_options("管理端订单筛选", web_order_text_path, "ORDER_STATUS_OPTIONS",
              "ORDER_STATUS", order_java, order_values, must_equal=True)
check_options("用户端订单 Tab", os.path.join(SHOP_SRC, "views", "Orders.vue"), "TABS",
              "ORDER_STATUS", order_java, order_values, must_equal=False)

as_java = java_constants("AfterSaleStatus.java") or {}
as_values = set(as_java.values())

# ★ 售后这一处【两端都等于】：和订单 Tab 的取舍不同。
#   顾客会专门去找一笔「被拒绝」「自己撤销」的售后 ——
#   那是他关心的事（钱退没退成），而不是他不记得的事。
check_options("管理端售后筛选", WEB_AS_JS, "AFTER_SALE_STATUS_OPTIONS",
              "AFTER_SALE_STATUS", as_java, as_values, must_equal=True)
check_options("用户端售后筛选", SHOP_AS_JS, "AFTER_SALE_STATUS_OPTIONS",
              "AFTER_SALE_STATUS", as_java, as_values, must_equal=True)

# ★ 里程碑 18：管理端「新增物流节点」那个下拉。
#   少一个值的后果：那个状态【永远录不进去】—— 管理员在下拉里找不到它，
#   而页面、接口、控制台没有一层会报错。
logi_java = java_constants("LogisticsStatus.java") or {}
logi_values = set(logi_java.values())

check_options("管理端物流节点下拉", WEB_LOGI_JS, "LOGISTICS_STATUS_OPTIONS",
              "LOGISTICS_STATUS", logi_java, logi_values, must_equal=True)

# ★★ 用户端【没有】LOGISTICS_STATUS_OPTIONS 数组，这不是漏了：
#   用户端根本没有物流的筛选 / 录入 —— 节点只在那个只读弹窗里展示。
#   规则 6 量的就是「用字典的地方全不全」，而用户端没有这样的地方。
#   ⚠️ 反过来说，给用户端加一个 OPTIONS 数组是【有害的】：
#      它会是一条零读者的死代码，而且会让将来的人以为
#      「用户端也能录物流」，从而去「补上」那个根本没有的入口。
#
# ★ 也正因为如此，这一条【不能】用 must_equal=False 静默地放过 ——
#   下面这条断言就是它的替代：这里没有调用 = 这里不需要调用。
#
# ★★ 断言的是【有没有 export 这个声明】，不是「文中出现过这个名字」。
#    本脚本第一版写的是 `"LOGISTICS_STATUS_OPTIONS" not in 文件内容`，
#    然后它立刻红了 —— 因为用户端那个文件末尾的注释里，为了说明
#    「这里刻意没有它」，把那个名字写了出来。**检查被解释它自己的注释绊倒了。**
#
#    而脚本开头 strip_noise 的注释早就预言过这一幕：
#    「下一个人往注释里写一句带 {{ }} 的金额示例，检查就会假红一次，
#      然后他会做的不是『改注释』，而是『把这条检查删掉』。」
#    所以正确的修法不是去剥 .js 的注释（strip_noise 只认 .vue），
#    而是【让断言的边界和它想守的东西重合】：这里要守的是
#    「用户端没有导出这个数组」，那就只匹配 export 声明。
#    这样注释可以自由地提到它、解释它，而断言依然精确。
_sh_logi = os.path.join(SHOP_SRC, "utils", "logisticsStatus.js")
_declares_options = False
if os.path.exists(_sh_logi):
    with open(_sh_logi, encoding="utf-8") as f:
        _declares_options = bool(re.search(
            r"^\s*export\s+const\s+LOGISTICS_STATUS_OPTIONS\b", f.read(), re.M))

check("★ 用户端没有导出物流节点下拉（它没有筛选，只有只读弹窗）",
      not _declares_options,
      "mall-shop/src/utils/logisticsStatus.js 里 export 了 LOGISTICS_STATUS_OPTIONS —— "
      "用户端没有录入物流的地方，这个数组会是零读者的死代码，\n"
      "而且会让将来的人以为「用户端也能录物流」，去补一个根本不存在的入口")

# ===========================================================================
section("上报（不做断言）")
# ===========================================================================
#
# ★ 这几行是【故意不断言】的，理由见脚本开头那条「已知盲区」：
#   如果只看 ¥{{ }}，那么 {{ '¥' + price }} 这种写法会漏掉。
#   把「¥ 出现几次」和「匹配到几个插值」都打出来，
#   对不上就说明有别的写法，但【不报错】——
#   因为这两个数会随页面数量自然增长，断言它们等于写死一个会过期的数字
#   （README 里立过这条：「不要写「恰好 N 项」」）。

for label, src in (("mall-shop", SHOP_SRC), ("mall-web", WEB_SRC)):
    print(f"\n  {label}:")
    for full, rel in vue_files(src):
        with open(full, encoding="utf-8") as f:
            text = strip_noise(f.read())
        yen = text.count("¥")
        interp = len(MONEY_INTERP.findall(text))
        if yen or interp:
            flag = "" if yen == interp else "   ← 对不上，可能有别的写法"
            print(f"    {rel:32s} ¥ 出现 {yen} 次，紧跟其后的插值 {interp} 处{flag}")

# ---------------------------------------------------------------------------
print()
print("=" * 72)
print(f"结果：{PASS} 通过 / {FAIL} 失败")
print("=" * 72)
if FAILED:
    print()
    print("失败项：")
    for f in FAILED:
        print(f"  - {f}")
print()

sys.exit(1 if FAIL else 0)
