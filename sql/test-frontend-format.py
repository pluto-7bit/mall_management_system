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
