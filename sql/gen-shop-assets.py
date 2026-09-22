# -*- coding: utf-8 -*-
"""
生成用户端的商品图（SVG）和建数据的 SQL。

运行：
    /d/python/python.exe sql/gen-shop-assets.py

产出三样东西：

  1. mall-shop/public/images/*.svg   每件商品一张图 + logo.svg + favicon.svg
  2. sql/migration-08c-shop-catalog.sql
       增量迁移：新增商品、给现有 12 件补封面和描述
  3. sql/generated-mall-seed.sql
       可直接粘进 mall.sql 的 seed 片段（全量脚本也要跟着改，见下）

★ 为什么让【脚本生成 SQL】，而不是手抄 44 行 INSERT？
  因为「文件名」和「cover 字段里的路径」必须严格对应，手抄 44 次必然错一两个。
  而错的表现是：那张图【静默】变成占位图（ProductImage 组件会兜底），
  页面看起来完全正常，你会以为「这件商品本来就没图」。
  让同一个 dict 同时决定文件和 SQL 两边，就不存在对不上的可能。

★ 为什么迁移脚本叫 08c 而不是 09？
  这个任务是里程碑 8 之后的收尾补齐，09 要留给「模拟支付」。
  命名跟着里程碑走是项目已有的约定（见 migration-08-order.sql）。

★ 为什么图片走 /images/xxx.svg 这种相对路径，后端一行都不用改？
  cover 是一个普通字符串，直接塞进 <img src>。
  后端【不提供任何静态资源】（WebMvcConfig 只注册了拦截器，
  没有 addResourceHandlers，resources 下也没有 static/ 目录）。
  而 Vite 在 dev 下把 mall-shop/public/ 挂在根路径 /，
  build 时原样拷进 dist/ —— 所以 /images/x.svg 始终能取到。
  管理端 mall-web 只是把 cover 渲染成一个文本输入框（ProductForm.vue），
  从来不显示成图片，所以这个相对路径在那边也无害。

⚠️ 上面这一段是【历史记录】，里程碑 11 之后它有一半不再成立，
   保留在这里是因为它解释了「这 47 个 SVG 为什么是这个形状」：

  · 「后端一行都不用改」—— 不再成立。里程碑 11 加了
    WebMvcConfig.addResourceHandlers，后端现在提供 /uploads/** 了
    （运营上传的图片走那条路，和这 47 个 SVG 是两条独立的通道）。
  · 「管理端从来不显示成图片」—— 不再成立，而且方向反了。
    里程碑 11 给管理端列表加了封面缩略图列、给表单加了预览图，
    于是这些 /images/*.svg 【在 mall-web 下必然 404】——
    因为那个工程的 public/ 里只有 favicon.svg。

  ⚠️ 这个不一致是【刻意不修的】，因为它只在开发环境存在：
    生产环境里 /images 和 /uploads 由同一个 nginx 托管，两个前端
    拿到的是同一份静态文件，不存在这个问题。修它要么往
    mall-web/public/ 复制 47 个文件（重复且会漂移），
    要么配一条 /images → localhost:5174 的跨工程代理
    （生产环境根本没有 5174 这个端口）。
    症状是管理端看到占位块而不是碎图 —— 靠 el-image 的 #error 兜底，
    见 mall-web/src/views/product/List.vue。
"""

import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

# ---------------------------------------------------------------------------
# 路径
# ---------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
IMG_DIR = os.path.join(ROOT, "mall-shop", "public", "images")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"


# ===========================================================================
# 一、绘图小工具
#
# SVG 里的 <img> 是一个【独立文档】：引不到外部字体和 CSS，
# currentColor 也无效、继承不到页面颜色。所以每个 fill 都必须写死颜色。
# （这也是本方案【不在图上写字】的原因之一 —— 写字要处理字体栈。）
# ===========================================================================

def rr(x, y, w, h, r, fill, extra=""):
    """圆角矩形"""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}"{extra}/>'


def ci(cx, cy, r, fill, extra=""):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}"{extra}/>'


def el(cx, cy, rx, ry, fill, extra=""):
    return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="{fill}"{extra}/>'


def pa(d, fill, extra=""):
    """
    路径。

    ★ fill 传空串时【不输出 fill 属性】—— 因为 extra 里往往已经带了
      fill="none"（描边图形）。两个 fill 属性在 XML 里是致命错误：
      浏览器解析会直接失败，图变成一片空白，而且控制台只报一句
      「error on line 1」之类的模糊信息，很难定位到是哪个文件。
    """
    f = f' fill="{fill}"' if fill else ""
    return f'<path d="{d}"{f}{extra}/>'


def shade(hex_color, amount):
    """
    把颜色调亮（amount>0）或调暗（amount<0）。

    用来给同一分类下的不同商品做微小的色差 ——
    否则一屏 4 张卡片全是同一个色块，看起来很假。
    """
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i : i + 2], 16) for i in (0, 2, 4))
    if amount >= 0:
        r = int(r + (255 - r) * amount / 100)
        g = int(g + (255 - g) * amount / 100)
        b = int(b + (255 - b) * amount / 100)
    else:
        k = 1 + amount / 100
        r, g, b = int(r * k), int(g * k), int(b * k)
    return f"#{r:02x}{g:02x}{b:02x}"


# ===========================================================================
# 二、插画库
#
# 每个函数返回一段 SVG 内部标记，画布固定 800×800，
# 插画主体大致占中间 260~380 的区域。
# c 是配色字典 {bg1, bg2, main, accent}，v 是同名图形的出现序号（用来做变体）。
# ===========================================================================

def art_phone(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    x, y, w, h = 288, 172, 224, 456
    p = [
        rr(x, y, w, h, 36, f),
        rr(x + 15, y + 15, w - 30, h - 30, 24, s),
    ]
    m = v % 3
    if m == 0:  # 竖排三摄
        p.append(rr(x + 34, y + 36, 54, 136, 27, a))
        for i in range(3):
            p.append(ci(x + 61, y + 66 + i * 44, 15, f))
    elif m == 1:  # 方形双摄
        p.append(rr(x + 32, y + 34, 118, 118, 26, a))
        p.append(ci(x + 62, y + 74, 20, f))
        p.append(ci(x + 120, y + 74, 20, f))
        p.append(ci(x + 91, y + 126, 15, f))
    else:  # 横向胶囊双摄
        p.append(rr(x + 32, y + 36, 134, 56, 28, a))
        p.append(ci(x + 64, y + 64, 16, f))
        p.append(ci(x + 120, y + 64, 16, f))
    p.append(rr(x + 54, y + h - 186, w - 108, 15, 8, a))
    p.append(rr(x + 54, y + h - 155, w - 160, 15, 8, a))
    p.append(rr(x + w // 2 - 34, y + h - 58, 68, 9, 5, a))
    return "".join(p)


def art_tablet(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    x, y, w, h = 240, 178, 320, 444
    p = [
        rr(x, y, w, h, 26, f),
        rr(x + 18, y + 18, w - 36, h - 36, 14, s),
    ]
    # 屏幕上摆几块内容
    p.append(rr(x + 48, y + 52, 150, 96, 10, a))
    p.append(rr(x + 48, y + 170, w - 96, 16, 8, a))
    p.append(rr(x + 48, y + 200, w - 150, 16, 8, a))
    if v % 2 == 0:
        p.append(rr(x + 48, y + 244, 108, 108, 10, a))
        p.append(rr(x + 176, y + 244, 108, 108, 10, a))
    else:
        p.append(rr(x + 48, y + 244, w - 96, 108, 10, a))
    return "".join(p)


def art_laptop(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(212, 196, 376, 250, 14, f),
        rr(228, 212, 344, 206, 8, s),
        pa("M164 470 L636 470 L672 540 L128 540 Z", f),
        pa("M306 470 L494 470 L502 490 L298 490 Z", a),
    ]
    p.append(rr(258, 240, 176, 106, 8, a))
    p.append(rr(258, 364, 284, 14, 7, a))
    p.append(rr(258, 388, 200, 14, 7, a))
    if v % 2 == 1:
        p.append(rr(452, 240, 96, 106, 8, a))
    return "".join(p)


def art_monitor(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(178, 168, 444, 282, 14, f),
        rr(196, 186, 408, 246, 8, s),
        rr(368, 450, 64, 76, 4, f),
        el(400, 528, 118, 20, f),
    ]
    p.append(rr(224, 214, 168, 92, 8, a))
    p.append(rr(224, 322, 352, 14, 7, a))
    p.append(rr(224, 348, 246, 14, 7, a))
    p.append(rr(224, 374, 300, 14, 7, a))
    if v % 2 == 0:
        p.append(rr(412, 214, 168, 92, 8, a))
    return "".join(p)


def art_keyboard(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [rr(150, 300, 500, 210, 18, f)]
    for row, (cols, yy) in enumerate([(14, 322), (13, 366), (12, 410)]):
        for i in range(cols):
            p.append(rr(172 + i * 33, yy, 26, 32, 5, s if (i + row) % 3 else a))
    p.append(rr(300, 456, 200, 32, 6, a))
    return "".join(p)


def art_mouse(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M400 190 C 518 190 574 288 574 396 C 574 512 500 596 400 596 "
           "C 300 596 226 512 226 396 C 226 288 282 190 400 190 Z", f),
        pa("M400 190 C 470 190 512 232 534 292 L266 292 C288 232 330 190 400 190 Z", a),
        rr(386, 214, 28, 74, 14, s),
        rr(376, 372, 48, 14, 7, s),
    ]
    if v % 2 == 1:
        p.append(rr(228, 300, 344, 10, 5, s))
    return "".join(p)


def art_printer(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(214, 236, 372, 128, 10, a),   # 上面吐出来的纸
        rr(180, 336, 440, 208, 16, f),   # 机身
        rr(376, 452, 168, 76, 8, a),     # 出纸口
        ci(544, 372, 15, s),
        rr(232, 366, 108, 20, 10, s),
    ]
    if v % 2 == 0:
        p.append(rr(252, 254, 296, 14, 7, s))
        p.append(rr(252, 280, 220, 14, 7, s))
    return "".join(p)


def art_usb(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(300, 250, 200, 130, 12, a),   # 金属头
        rr(330, 278, 60, 74, 5, s),
        rr(400, 278, 70, 74, 5, s),
        rr(300, 376, 200, 216, 20, f),   # 塑料身
        rr(316, 396, 168, 12, 6, s),
        rr(316, 420, 108, 12, 6, s),
    ]
    if v % 2 == 1:
        p.append(ci(400, 512, 26, a))
    return "".join(p)


def art_camera(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(190, 262, 420, 288, 26, f),
        ci(400, 400, 92, a),
        ci(400, 400, 62, s),
        ci(400, 400, 34, f),
        rr(240, 234, 96, 34, 10, a),
        ci(536, 300, 18, s),
    ]
    if v % 2 == 0:
        p.append(rr(210, 296, 120, 26, 13, s))
    return "".join(p)


def art_watch(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M320 132 L480 132 L480 264 L320 264 Z", a),
        pa("M320 536 L480 536 L480 668 L320 668 Z", a),
        rr(288, 232, 224, 334, 52, f),
        rr(306, 250, 188, 298, 38, s),
        rr(516, 344, 22, 58, 11, f),
        rr(340, 292, 76, 62, 10, a),
    ]
    p.append(rr(340, 380, 120, 14, 7, a))
    p.append(rr(340, 406, 84, 14, 7, a))
    if v % 2 == 1:
        p.append(ci(430, 300, 18, a))
    return "".join(p)


def art_headphones(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M400 178 C 268 178 196 274 196 400 L196 430 L268 430 L268 372 "
           "C 268 296 322 250 400 250 C 478 250 532 296 532 372 L532 430 "
           "L604 430 L604 400 C 604 274 532 178 400 178 Z", f),
        rr(158, 398, 104, 202, 42, f),
        rr(538, 398, 104, 202, 42, f),
        rr(180, 424, 60, 150, 28, a),
        rr(560, 424, 60, 150, 28, a),
    ]
    if v % 2 == 1:
        p.append(rr(300, 178, 200, 16, 8, a))
    return "".join(p)


def art_fridge(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(246, 156, 308, 492, 26, f),
        rr(266, 176, 268, 176, 14, s),   # 冷冻室
        rr(266, 368, 268, 260, 14, s),   # 冷藏室
        rr(492, 220, 14, 84, 7, a),      # 上把手
        rr(492, 440, 14, 84, 7, a),      # 下把手
    ]
    if v % 2 == 0:
        p.append(rr(292, 236, 74, 74, 10, a))
        p.append(rr(292, 420, 74, 74, 10, a))
    return "".join(p)


def art_washer(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(212, 168, 376, 472, 24, f),
        rr(240, 198, 320, 74, 10, s),    # 控制面板
        ci(400, 420, 122, s),            # 舱门
        ci(400, 420, 92, a),
        ci(268, 236, 15, a),
        ci(504, 236, 15, a),
    ]
    if v % 2 == 0:
        p.append(pa("M400 336 C 448 384 468 412 468 440 A 68 68 0 0 1 332 440 "
                    "C 332 412 352 384 400 336 Z", s))
    return "".join(p)


def art_ac(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(146, 264, 508, 208, 24, f),
        rr(174, 292, 452, 58, 12, s),
        rr(174, 372, 452, 22, 11, a),
        rr(174, 410, 452, 22, 11, a),
        ci(600, 500, 12, a),
    ]
    if v % 2 == 1:
        p.append(rr(196, 320, 128, 14, 7, a))
        p.append(rr(196, 344, 86, 14, 7, a))
    return "".join(p)


def art_rice_cooker(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(228, 300, 344, 300, 42, f),
        rr(228, 268, 344, 60, 26, a),    # 盖子
        rr(352, 232, 96, 44, 16, a),     # 提手
        rr(272, 372, 256, 20, 10, s),
        rr(272, 412, 152, 20, 10, s),
        ci(524, 460, 26, s),
    ]
    if v % 2 == 0:
        p.append(rr(228, 540, 344, 26, 13, a))
    return "".join(p)


def art_vacuum(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(376, 156, 48, 300, 24, f),           # 长杆
        rr(320, 176, 160, 96, 20, a),           # 主机
        pa("M298 452 L502 452 L552 566 L248 566 Z", f),  # 吸头
        rr(330, 492, 140, 40, 10, s),
        ci(400, 224, 30, s),
    ]
    if v % 2 == 1:
        p.append(rr(376, 236, 48, 90, 24, a))
    return "".join(p)


def art_blender(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(268, 288, 264, 292, 22, f),   # 杯体
        rr(268, 262, 264, 42, 16, a),    # 盖
        rr(226, 580, 348, 96, 22, f),    # 底座
        rr(300, 330, 200, 20, 10, s),
        rr(300, 372, 140, 20, 10, s),
        ci(400, 628, 22, a),
    ]
    if v % 2 == 0:
        p.append(rr(300, 414, 168, 20, 10, s))
    return "".join(p)


def art_toothbrush(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(378, 300, 44, 400, 22, f),    # 刷柄
        rr(378, 176, 44, 130, 14, a),    # 刷头
        rr(366, 150, 68, 34, 12, s),
    ]
    for i in range(6):
        p.append(rr(384 + (i % 2) * 20, 178 + i * 20, 12, 26, 6, s))
    if v % 2 == 0:
        p.append(ci(400, 636, 26, a))
    return "".join(p)


def art_purifier(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(250, 168, 300, 468, 30, f),
        rr(278, 200, 244, 176, 16, s),
        ci(400, 288, 56, a),
        ci(400, 288, 30, s),
    ]
    for i in range(7):
        p.append(rr(278, 404 + i * 30, 244, 16, 8, a))
    if v % 2 == 1:
        p.append(rr(278, 636, 244, 18, 9, a))
    return "".join(p)


def art_tshirt(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M312 178 L488 178 L604 232 L560 336 L508 312 L508 624 L292 624 "
           "L292 312 L240 336 L196 232 Z", f),
        pa("M356 178 C 366 224 434 224 444 178 Z", s),
    ]
    if v % 2 == 0:
        p.append(rr(340, 380, 120, 120, 14, a))
    else:
        p.append(rr(316, 372, 168, 18, 9, a))
        p.append(rr(316, 404, 168, 18, 9, a))
    return "".join(p)


def art_jacket(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M300 178 L500 178 L610 244 L566 350 L516 326 L516 636 L284 636 "
           "L284 326 L234 350 L190 244 Z", f),
        pa("M300 178 L400 300 L500 178 L470 172 L400 250 L330 172 Z", s),
        rr(392, 300, 16, 336, 8, a),                       # 拉链
        rr(284, 600, 232, 22, 11, a),                      # 下摆
    ]
    if v % 2 == 0:
        p.append(rr(304, 400, 84, 74, 10, a))
        p.append(rr(412, 400, 84, 74, 10, a))
    return "".join(p)


def art_pants(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M288 176 L512 176 L516 300 L498 636 L410 636 L400 400 "
           "L390 636 L302 636 L284 300 Z", f),
        rr(288, 176, 224, 38, 10, s),
        rr(360, 214, 80, 24, 8, a),
    ]
    if v % 2 == 0:
        p.append(rr(316, 480, 60, 74, 8, s))
    return "".join(p)


def art_shoe(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M182 508 C 182 434 232 402 296 386 L400 360 L440 292 L500 316 "
           "L516 386 L600 424 C 626 436 636 462 636 496 L636 528 L182 528 Z", f),
        pa("M182 528 L636 528 L636 566 C 636 580 624 588 610 588 L208 588 "
           "C 194 588 182 580 182 566 Z", a),
        pa("M296 386 L400 360 L418 430 L306 452 Z", s),
    ]
    if v % 2 == 1:
        p.append(rr(470, 500, 130, 16, 8, s))
    return "".join(p)


def art_bag(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(238, 268, 324, 380, 46, f),
        pa("M328 268 C 328 190 472 190 472 268", "", ' stroke="%s" stroke-width="26" fill="none"' % a),
        rr(276, 396, 248, 176, 24, s),
        rr(310, 236, 40, 60, 18, a),
        rr(450, 236, 40, 60, 18, a),
    ]
    if v % 2 == 1:
        p.append(rr(322, 440, 156, 14, 7, a))
    return "".join(p)


def art_snack_bag(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    zig = "M252 236 L296 206 L340 236 L384 206 L428 236 L472 206 L516 236 L548 206 L548 268 L252 268 Z"
    p = [
        pa("M252 268 L548 268 L566 610 C 568 626 556 636 540 636 L260 636 "
           "C 244 636 232 626 234 610 Z", f),
        pa(zig, a),
        rr(292, 372, 216, 132, 18, s),
    ]
    if v % 2 == 0:
        p.append(ci(400, 438, 42, a))
    else:
        p.append(rr(322, 418, 156, 18, 9, a))
        p.append(rr(322, 448, 108, 18, 9, a))
    return "".join(p)


def art_pouch(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M266 300 C 266 276 534 276 534 300 L558 596 C 560 620 542 634 518 634 "
           "L282 634 C 258 634 240 620 242 596 Z", f),
        rr(240, 262, 320, 54, 16, a),
        rr(300, 386, 200, 140, 20, s),
    ]
    if v % 2 == 1:
        p.append(ci(400, 456, 44, a))
    return "".join(p)


def art_bottle(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(310, 168, 180, 78, 16, a),    # 瓶盖
        pa("M330 246 L470 246 L512 336 L512 622 C 512 634 502 644 490 644 "
           "L310 644 C 298 644 288 634 288 622 L288 336 Z", f),
        rr(320, 388, 160, 152, 14, s),
    ]
    if v % 2 == 0:
        p.append(rr(352, 424, 96, 80, 10, a))
    return "".join(p)


def art_box(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(200, 316, 400, 300, 20, f),
        rr(200, 268, 400, 62, 16, a),
        rr(376, 268, 48, 348, 4, s),
        rr(250, 380, 126, 96, 12, s),
    ]
    if v % 2 == 0:
        p.append(ci(470, 428, 42, a))
    return "".join(p)


def art_pillow(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(168, 288, 464, 240, 70, f),
        rr(200, 320, 400, 176, 50, s),
        pa("M330 320 C 384 372 384 444 330 496", "", ' stroke="%s" stroke-width="14" fill="none"' % a),
        pa("M470 320 C 416 372 416 444 470 496", "", ' stroke="%s" stroke-width="14" fill="none"' % a),
    ]
    if v % 2 == 1:
        p.append(rr(250, 558, 300, 16, 8, a))
    return "".join(p)


def art_bedding(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(160, 248, 480, 108, 20, a),   # 叠起来的三层
        rr(160, 360, 480, 108, 20, f),
        rr(160, 472, 480, 108, 20, a),
    ]
    for y in (300, 412, 524):
        p.append(rr(196, y, 408, 14, 7, s))
    if v % 2 == 0:
        p.append(rr(196, 324, 120, 14, 7, s))
    return "".join(p)


def art_quilt(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        pa("M172 372 C 172 300 240 262 400 262 C 560 262 628 300 628 372 "
           "L628 520 C 628 556 596 578 560 578 L240 578 C 204 578 172 556 172 520 Z", f),
        pa("M172 400 C 260 448 540 448 628 400", "", ' stroke="%s" stroke-width="16" fill="none"' % a),
        rr(200, 302, 400, 18, 9, s),
    ]
    if v % 2 == 1:
        p.append(rr(200, 340, 260, 18, 9, s))
    return "".join(p)


def art_mattress(c, v):
    f, s, a = c["main"], c["bg1"], c["accent"]
    p = [
        rr(150, 320, 500, 220, 26, f),
        rr(150, 320, 500, 74, 26, a),
        rr(150, 470, 500, 70, 26, s),
    ]
    for x in range(0, 4):
        p.append(rr(196 + x * 106, 350, 76, 22, 11, s))
    if v % 2 == 1:
        p.append(rr(196, 500, 76, 22, 11, a))
        p.append(rr(408, 500, 76, 22, 11, a))
    return "".join(p)


ART = {
    "phone": art_phone, "tablet": art_tablet,
    "laptop": art_laptop, "monitor": art_monitor, "keyboard": art_keyboard,
    "mouse": art_mouse, "printer": art_printer, "usb": art_usb,
    "camera": art_camera, "watch": art_watch, "headphones": art_headphones,
    "fridge": art_fridge, "washer": art_washer, "ac": art_ac,
    "rice_cooker": art_rice_cooker, "vacuum": art_vacuum, "blender": art_blender,
    "toothbrush": art_toothbrush, "purifier": art_purifier,
    "tshirt": art_tshirt, "jacket": art_jacket, "pants": art_pants,
    "shoe": art_shoe, "bag": art_bag,
    "snack_bag": art_snack_bag, "pouch": art_pouch, "bottle": art_bottle,
    "box": art_box,
    "pillow": art_pillow, "bedding": art_bedding, "quilt": art_quilt,
    "mattress": art_mattress,
}


# ===========================================================================
# 三、每个分类的色系
#
# 同一分类共用一个色系，分类内靠 shade() 微调明度做出差异。
# ===========================================================================
CATEGORY_STYLE = {
    "手机数码": dict(bg1="#f5f8ff", bg2="#e2ecff", main="#4a76d4", accent="#93b2e9"),
    "电脑办公": dict(bg1="#f6f8fa", bg2="#e4eaf1", main="#55697e", accent="#9aabbe"),
    "家用电器": dict(bg1="#f1fbfa", bg2="#dcf2ef", main="#2f9c92", accent="#82ccc5"),
    "服饰鞋包": dict(bg1="#fef7f5", bg2="#fbe3dd", main="#d4664f", accent="#eda795"),
    "休闲零食": dict(bg1="#fffbf1", bg2="#fdeed2", main="#d99b28", accent="#efc67c"),
    "床上用品": dict(bg1="#f8f6fd", bg2="#e9e3fa", main="#7a6fd0", accent="#ada5e6"),
}

# 分类名 → SQL 变量名
CAT_VAR = {
    "手机数码": "@cat_phone",
    "电脑办公": "@cat_pc",
    "家用电器": "@cat_appliance",
    "服饰鞋包": "@cat_cloth",
    "休闲零食": "@cat_snack",
    "床上用品": "@cat_bed",
}


# ===========================================================================
# 四、商品数据 —— 唯一的一份真相
#
# 下面的 PRODUCTS / EXISTING 决定了：生成哪些图片、图片叫什么名字、
# SQL 里 cover 字段写什么、描述写什么。
# 两边由同一个 dict 推导，所以不可能对不上。
# ===========================================================================

# 新增商品：(分类, 名称, 价格, 库存, 图形, 描述行)
PRODUCTS = [
    # ---------------- 手机数码 ----------------
    ("手机数码", "华为 Mate 70 Pro", "6499.00", 80, "phone", [
        "6.8 英寸 OLED 曲面屏，1-120Hz 自适应刷新率",
        "麒麟芯片 + 鸿蒙系统，支持双向北斗卫星消息",
        "后置 5000 万可变光圈主摄，支持 4K 视频录制",
        "5300mAh 电池，100W 有线快充",
    ]),
    ("手机数码", "荣耀 Magic7", "4499.00", 120, "phone", [
        "6.7 英寸护眼直屏，4320Hz 高频调光",
        "第三代骁龙 8 移动平台，性能释放稳定",
        "5650mAh 青海湖电池，支持 100W 快充",
        "AI 抓拍引擎，运动场景成片率更高",
    ]),
    ("手机数码", "小米平板 7", "1999.00", 90, "tablet", [
        "11.2 英寸 3.2K 超清屏，144Hz 刷新率",
        "支持手写笔与磁吸键盘，办公娱乐两用",
        "8850mAh 大电池，连续看视频约 14 小时",
        "金属一体化机身，厚度 6.18mm",
    ]),
    ("手机数码", "索尼 WH-1000XM5 头戴式耳机", "1899.00", 60, "headphones", [
        "业内标杆级主动降噪，8 麦克风系统",
        "30mm 碳纤维驱动单元，支持 LDAC 高解析音频",
        "智能免摘对话，开口说话自动暂停音乐",
        "续航 30 小时，充电 3 分钟可听 3 小时",
    ]),
    ("手机数码", "Apple Watch Series 10 智能手表", "3199.00", 45, "watch", [
        "更大更薄的广视角 OLED 屏，边框进一步收窄",
        "支持睡眠呼吸暂停检测与心电图功能",
        "50 米防水，可记录游泳与浮潜数据",
        "快充设计，约 30 分钟充至 80%",
    ]),
    ("手机数码", "大疆 Osmo Action 5 Pro 运动相机", "2299.00", 35, "camera", [
        "1/1.3 英寸传感器，低光画质明显提升",
        "前后双触摸屏，自拍构图方便",
        "裸机 20 米防水，无需额外防水壳",
        "超强防抖，骑行滑雪等剧烈场景也稳定",
    ]),

    # ---------------- 电脑办公 ----------------
    ("电脑办公", "MacBook Air 13 英寸 M4", "7999.00", 40, "laptop", [
        "M4 芯片，10 核 CPU + 8 核 GPU",
        "13.6 英寸 Liquid 视网膜屏，500 尼特亮度",
        "无风扇设计，运行全程安静",
        "续航最长 18 小时，重量仅 1.24kg",
    ]),
    ("电脑办公", "戴尔 U2723QE 27 英寸 4K 显示器", "2999.00", 55, "monitor", [
        "3840×2160 分辨率，IPS Black 面板",
        "98% DCI-P3 色域，出厂逐台校色",
        "支持 90W Type-C 反向供电，一根线连笔记本",
        "可升降旋转支架，自带 USB 集线器",
    ]),
    ("电脑办公", "罗技 K380 多设备无线键盘", "199.00", 300, "keyboard", [
        "可同时连接 3 台设备，一键切换",
        "圆形静音键帽，打字手感轻快",
        "两节 AAA 电池可用约 2 年",
        "重量 423g，方便随身携带",
    ]),
    ("电脑办公", "惠普 LaserJet 无线激光打印机", "1099.00", 25, "printer", [
        "黑白激光打印，每分钟 22 页",
        "支持无线直连与手机 App 打印",
        "首页输出仅需 8.3 秒",
        "鼓粉一体设计，更换耗材简单",
    ]),
    ("电脑办公", "金士顿 128G 金属 U 盘", "89.00", 500, "usb", [
        "USB 3.2 接口，读取速度最高 200MB/s",
        "金属外壳，抗摔耐磨",
        "内置钥匙环孔，可挂在钥匙扣上",
        "五年质保，全国联保",
    ]),

    # ---------------- 家用电器 ----------------
    ("家用电器", "格力 1.5 匹变频挂机空调", "2899.00", 30, "ac", [
        "新一级能效，APF 值 5.26",
        "56℃ 高温自清洁，出风更干净",
        "独立除湿模式，梅雨季很实用",
        "适用面积 16~20 平方米",
    ]),
    ("家用电器", "海尔 465L 十字对开门冰箱", "3599.00", 20, "fridge", [
        "十字四门设计，冷藏冷冻分区明确",
        "风冷无霜，无需手动除冰",
        "一级双变频，日耗电约 0.85 度",
        "干湿分储，蔬果和干货各得其所",
    ]),
    ("家用电器", "小天鹅 10 公斤滚筒洗衣机", "2199.00", 25, "washer", [
        "10kg 大容量，可洗四件套和窗帘",
        "BLDC 变频电机，静音且寿命长",
        "95℃ 高温筒自洁，抑菌率 99.9%",
        "15 分钟快洗模式，应急很方便",
    ]),
    ("家用电器", "小米空气净化器 4", "899.00", 60, "purifier", [
        "颗粒物 CADR 500m³/h，适用 60 平方米",
        "OLED 触控屏，实时显示 PM2.5",
        "三层复合滤芯，更换周期约一年",
        "支持 App 与语音助手控制",
    ]),

    # ---------------- 服饰鞋包 ----------------
    ("服饰鞋包", "优衣库全棉圆领 T 恤", "79.00", 500, "tshirt", [
        "100% 纯棉，克重扎实不透",
        "领口加固不易变形",
        "版型regular fit，男女同款",
        "多色可选，日常百搭打底",
    ]),
    ("服饰鞋包", "李宁䨻科技跑鞋", "399.00", 200, "shoe", [
        "䨻科技中底，回弹明显且轻量",
        "透气网布鞋面，长时间跑不闷脚",
        "橡胶大底，湿地抓地力好",
        "适合日常慢跑与通勤",
    ]),
    ("服饰鞋包", "李维斯 511 修身牛仔裤", "459.00", 150, "pants", [
        "511 版型，修身不紧绷",
        "弹力棉面料，活动自如",
        "经典五袋设计，水洗色自然",
        "四季可穿，配 T 恤衬衫都行",
    ]),
    ("服饰鞋包", "新秀丽商务双肩背包", "599.00", 80, "bag", [
        "可放 15.6 英寸笔记本，独立隔层",
        "背部透气网垫，久背不闷",
        "防泼水面料，小雨无压力",
        "行李箱拉杆带，出差可直接挂上",
    ]),
    ("服饰鞋包", "波司登中长款羽绒服", "1299.00", 40, "jacket", [
        "90% 白鸭绒填充，蓬松度 600+",
        "中长款过膝设计，保暖范围更大",
        "防钻绒工艺，久穿不下绒",
        "可拆卸连帽，两种穿法",
    ]),
    # ---------------- 休闲零食 ----------------
    ("休闲零食", "三只松鼠每日坚果 750g", "79.90", 300, "pouch", [
        "30 小袋独立包装，一天一袋",
        "含核桃、巴旦木、腰果等多种坚果",
        "搭配蔓越莓干与蓝莓干，口感有层次",
        "原料当季采购，锁鲜包装",
    ]),
    ("休闲零食", "良品铺子猪肉脯 200g", "39.90", 400, "snack_bag", [
        "原切后腿肉，肉纤维清晰可见",
        "炭火烘烤工艺，外焦里嫩",
        "独立小包装，开袋即食",
        "甜咸适口，追剧办公都合适",
    ]),
    ("休闲零食", "乐事薯片家庭分享装", "29.90", 500, "snack_bag", [
        "家庭分享装，含 5 小包多种口味",
        "马铃薯切片均匀，酥脆不油腻",
        "原味、黄瓜味、烧烤味随机搭配",
        "密封小包装，一次一包不返潮",
    ]),
    ("休闲零食", "伊利金典纯牛奶 250ml×12", "69.90", 260, "bottle", [
        "每 100ml 含 3.8g 优质乳蛋白",
        "120mg 原生高钙，日常补钙方便",
        "超高温灭菌，常温保存 6 个月",
        "12 盒整箱装，学生和上班族常备",
    ]),
    ("休闲零食", "费列罗榛果威化巧克力 24 粒", "109.00", 150, "box", [
        "整颗榛果夹心，外层威化与巧克力",
        "24 粒礼盒装，送人体面",
        "原装进口，冷链运输",
        "独立金箔包装，常温存放即可",
    ]),
    # ---------------- 床上用品 ----------------
    ("床上用品", "泰国天然乳胶枕", "199.00", 180, "pillow", [
        "93% 天然乳胶含量，回弹支撑好",
        "波浪造型贴合颈椎，侧睡仰睡都合适",
        "蜂窝透气孔，夏季不闷热",
        "内外双层枕套，均可拆洗",
    ]),
    ("床上用品", "水星家纺蚕丝被", "899.00", 45, "quilt", [
        "100% 桑蚕丝填充，轻盈贴身",
        "蚕丝被芯可水洗，打理省心",
        "子母被设计，一床应对四季",
        "面料亲肤，敏感肌也能用",
    ]),
    ("床上用品", "全棉四件套 1.8 米床", "399.00", 120, "bedding", [
        "100% 新疆长绒棉，60 支高密",
        "含被套、床单、枕套两只",
        "活性印染，不易掉色",
        "适合 1.8 米床，可直接机洗",
    ]),
    ("床上用品", "珊瑚绒加厚盖毯", "129.00", 200, "quilt", [
        "双面珊瑚绒，触感柔软",
        "加厚设计，秋冬保暖效果好",
        "不掉毛不起球，机洗不变形",
        "午睡毯、沙发毯、旅行毯都合适",
    ]),
    ("床上用品", "记忆棉床垫 1.8 米", "1499.00", 30, "mattress", [
        "记忆棉贴合身体曲线，分散压力",
        "独立袋装弹簧，翻身不互相干扰",
        "7 区支撑，护腰护颈",
        "可拆洗床垫套，厚度 20cm",
    ]),
]


# 现有商品：(名称, 图形, 描述行)
#
# 这些行已经在库里了，只补 cover 和 description，不碰价格和库存。
# ★ 按【名称】匹配而不是按 id：category 和 product 的 AUTO_INCREMENT
#   都被测试烧掉了（category 已经到 62，product 到 75），
#   写死 id 的脚本换台机器就跑不了。
EXISTING = [
    ("小米 15 Pro 手机", "phone", [
        "6.73 英寸 2K 全等深微曲屏，龙晶玻璃 2.0",
        "徕卡光学镜头，支持可变光圈与长焦微距",
        "第三代骁龙 8 平台，5400mAh 电池",
        "90W 有线 + 50W 无线快充",
    ]),
    ("iPad Air 11 英寸", "tablet", [
        "11 英寸 Liquid 视网膜屏，P3 广色域",
        "M 系列芯片，剪辑和多任务都从容",
        "支持 Apple Pencil 与妙控键盘",
        "横向前置摄像头，视频通话更自然",
    ]),
    ("联想 ThinkPad X1 Carbon", "laptop", [
        "14 英寸 2.8K OLED 屏，100% DCI-P3",
        "碳纤维机身，重量仅 1.09kg",
        "经典小红帽与背光键盘，键程舒适",
        "通过 12 项军标测试，耐用可靠",
    ]),
    ("罗技 MX Master 3S 鼠标", "mouse", [
        "8000DPI 传感器，几乎可在任何表面使用",
        "MagSpeed 电磁滚轮，一秒滚动千行",
        "静音按键，点击噪音降低 90%",
        "可同时连接三台设备并一键切换",
    ]),
    ("戴森 V12 吸尘器", "vacuum", [
        "激光探测功能，让微尘无处藏身",
        "整机过滤系统，锁住 99.99% 微尘",
        "续航最长 60 分钟，可替换电池",
        "多款吸头覆盖地板、床褥与缝隙",
    ]),
    ("美的电饭煲 4L", "rice_cooker", [
        "4L 容量，适合 3~5 人家庭",
        "IH 电磁加热，米粒受热更均匀",
        "12 种预设菜单，支持 24 小时预约",
        "内胆可拆卸，清洗方便",
    ]),
    ("优衣库轻型羽绒服", "jacket", [
        "轻量设计，可收纳进随身小袋",
        "90% 羽绒填充，保暖效率高",
        "防泼水表面，应付小雨小雪",
        "内搭外穿都合适，通勤旅行皆宜",
    ]),
    ("iPhone duo", "phone", [
        "双卡双待，工作生活两个号码分开",
        "超视网膜 XDR 显示屏，HDR 显示出色",
        "A 系列芯片，日常使用流畅省电",
        "支持无线充电与 IP68 防水",
    ]),
    ("iPhone 18 pro 256G", "phone", [
        "256GB 存储，照片视频随便存",
        "Pro 级三摄系统，支持 ProRAW 与 ProRes",
        "钛金属中框，强度高且更轻",
        "ProMotion 自适应刷新率，最高 120Hz",
    ]),
    ("联想拯救者Y9000P", "laptop", [
        "16 英寸 2.5K 电竞屏，240Hz 刷新率",
        "满血版独立显卡，3A 大作高帧运行",
        "霜刃散热系统，双风扇多热管",
        "支持独显直连，游戏延迟更低",
    ]),
    ("卫龙辣条", "snack_bag", [
        "经典麻辣味，面筋筋道有嚼劲",
        "独立小包装，干净卫生不脏手",
        "非油炸工艺，解馋无负担",
        "追剧办公的国民小零食",
    ]),
    ("床单", "bedding", [
        "100% 纯棉，亲肤透气",
        "高支高密织造，触感细腻",
        "可机洗，越洗越柔软",
        "适合 1.5~1.8 米床",
    ]),
]


# ===========================================================================
# 五、生成
# ===========================================================================

def slugify(art, n):
    return f"{art}-{n:02d}"


def build_catalog():
    """
    把 PRODUCTS 和 EXISTING 合成一张统一的目录表，给每件商品分配图片文件名。

    文件名 = {图形名}-{该图形出现序号:02d}，例如 phone-01、phone-02。
    序号按商品在文件里出现的顺序累加 —— 所以【调整商品顺序会改变文件名】。
    这不影响正确性（SQL 是同一次生成的），但意味着这个脚本不该反复跑
    去「改」已有商品的封面（那边的 UPDATE 带 `cover 为空才写` 的守卫，
    所以重复跑也不会覆盖）。
    """
    counters = {}
    catalog = []
    for cat, name, price, stock, art, desc in PRODUCTS:
        counters[art] = counters.get(art, 0) + 1
        catalog.append(dict(
            category=cat, name=name, price=price, stock=stock,
            art=art, desc=desc, cover=f"/images/{slugify(art, counters[art])}.svg",
            is_new=True, variant=counters[art] - 1,
        ))
    for name, art, desc in EXISTING:
        counters[art] = counters.get(art, 0) + 1
        catalog.append(dict(
            category=None, name=name, price=None, stock=None,
            art=art, desc=desc, cover=f"/images/{slugify(art, counters[art])}.svg",
            is_new=False, variant=counters[art] - 1,
        ))
    return catalog


def write_banners():
    """
    生成首页主视觉的横幅图（3 张，轮播用）。

    <h3>★ 图上【不写字】，文字由 Home.vue 用 HTML 覆盖上去</h3>
    这不是偷懒，是三条理由叠加：

    1. {@code <img>} 引用的 SVG 是一个<b>独立文档</b>，
       它引不到页面的字体和 CSS。要写字就只能依赖系统字体 ——
       而「系统字体解析失败」的表现是中文变成一个个空心方框（豆腐块），
       整张图就废了。这个项目里所有字都是中文，赌不起。

    2. 图里的文字<b>选不中、翻译不了、屏幕阅读器读不到</b>。
       一个首页最显眼的大标题，恰恰是最该能被选中和朗读的东西。

    3. 文字和背景分离之后，改文案（「智能家电臻品之选」→ 别的）
       不用重新生成图片。图片是要进 git 的二进制资产，
       文案是每天都在变的东西，两者的变更频率差了几个数量级。

    <h3>★ 复用商品的 art_* 函数，靠 transform 摆位</h3>
    横幅里那几个漂浮的产品（耳机、冰箱、洗衣机……）不是新画的，
    是把商品图的 art_* 函数原样调用一次，再用
    {@code <g transform="translate(...) scale(...)">} 缩小并挪到位置上。
    同一份画法只维护一遍 —— 改了 art_fridge，
    商品图和横幅里的那台冰箱会一起变。
    """
    os.makedirs(IMG_DIR, exist_ok=True)

    # 每张横幅 = (文件名, 背景渐变起止色, 装饰色, 要摆哪些产品)
    # 产品的元组是 (art 名, 变体序号, x 偏移, y 偏移, 缩放)
    #
    # ★ 画布 1400×650（约 2.15:1），并且【左边约 40% 故意空着】。
    #   那块空白是给 Home.vue 用 HTML 覆盖上去的大标题留的 ——
    #   见上面「图上不写字」那一段。京东自己的横幅也是这个构图：
    #   文案在左、主体图形在右。
    #   所以下面所有产品的 x 偏移都从 400 起，没有一个是靠左的。
    #   （400 是【1000 宽画布】下的坐标，由下面的 X 系数换算成 1400 宽的。)
    #
    # ★ 为什么从 1000 加宽到 1400：横幅是按 object-fit: cover 铺满的，
    #   容器比例 < 图片比例时裁【左右】，> 时裁【上下】。
    #   首页主视觉中间那栏随屏幕变宽（约 1.75:1 到 2.5:1），
    #   1000×650 的 1.54:1 在这个区间里【永远是被裁上下】——
    #   裁掉的正是产品图形本身，手机/耳机经常被切掉一截。
    #   2.15:1 落在这个区间的中间，两头最多各裁一点点。
    #
    # ★ 产品之间允许轻微重叠。刻意的：一左一右并排放会显得像在「列清单」，
    #   前后错开一点才有前后层次。手工摆位做不到精确不重叠，
    #   但在这个尺寸下叠一点点反而是好事。
    #
    # ★★ 下面所有 x 坐标都是按【1000 宽】的手工摆位写的，真正落到 SVG 里之前
    #    统一乘 X 放大到画布宽度 —— 只动 x，不动 y、也不动 scale。
    #    这样加宽画布【不会改变构图】（谁在左谁在右、占画布百分之几全都不变），
    #    只是把产品之间拉开、给右边腾出空间。
    #    如果直接改画布尺寸而不换算 x，产品会全部挤在左边 40% 里。
    BANNER_W, BANNER_H = 1400, 650
    X = BANNER_W / 1000.0

    banners = [
        (
            "banner-01", "#1e3a8a", "#3b82f6", "#60a5fa",
            [("headphones", 0, 416, 76, 0.46), ("phone", 1, 652, 182, 0.42),
             ("watch", 0, 640, 405, 0.30)],
        ),
        (
            "banner-02", "#0f766e", "#14b8a6", "#5eead4",
            [("fridge", 0, 404, 70, 0.50), ("washer", 0, 660, 210, 0.44),
             ("rice_cooker", 0, 520, 420, 0.28)],
        ),
        (
            "banner-03", "#9f1239", "#e11d48", "#fb7185",
            [("jacket", 1, 404, 60, 0.50), ("bag", 0, 700, 90, 0.36),
             ("shoe", 0, 600, 340, 0.40)],
        ),
    ]

    total = 0
    for slug, bg1, bg2, deco, props in banners:
        # ★ 横幅里的产品是【白色主体 + 彩色细节】，不是照搬商品图那套同色系配色。
        #   商品图的背景是浅灰、产品是深色，两者对比才够；
        #   横幅的背景是深色（深蓝/深青/深红），产品要反过来用白色才能跳出来。
        #   art_* 函数读的是 c["main"]（主体）、c["bg1"]（屏幕那类浅色面）、
        #   c["accent"]（细节），所以这里把 main 给白、另两个给横幅的亮色。
        c = {"main": "#ffffff", "bg1": deco, "accent": deco}
        parts = [
            # 斜向渐变底
            f'<defs><linearGradient id="bn-{slug}" x1="0" y1="0" x2="1" y2="1">'
            f'<stop offset="0" stop-color="{bg1}"/>'
            f'<stop offset="1" stop-color="{bg2}"/></linearGradient></defs>'
            f'<rect width="{BANNER_W}" height="{BANNER_H}" fill="url(#bn-{slug})"/>'
        ]
        # 装饰：两个大而极淡的圆 + 一条斜向亮带。
        # 作用是让纯色底「不平」—— 一整块纯色在小图上还过得去，
        # 铺到 600px 宽就会显得很空。
        parts.append(ci(880 * X, 70, 210, deco, ' opacity="0.18"'))
        parts.append(ci(150 * X, 560, 180, deco, ' opacity="0.14"'))
        parts.append(
            pa(
                f"M0 500 L{BANNER_W} 230 L{BANNER_W} 330 L0 600 Z",
                "#ffffff",
                ' opacity="0.08"',
            )
        )
        # 漂浮的产品：白底 + 投影，让它们从背景上「浮」起来
        for art, v, tx, ty, sc in props:
            body = ART[art](c, v)
            parts.append(
                f'<g transform="translate({tx * X:.0f},{ty}) scale({sc})" '
                f'opacity="0.95">{body}</g>'
            )
        doc = (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{BANNER_W}" '
            f'height="{BANNER_H}" viewBox="0 0 {BANNER_W} {BANNER_H}">'
            + "".join(parts)
            + "</svg>"
        )
        with open(os.path.join(IMG_DIR, f"{slug}.svg"), "w", encoding="utf-8") as fp:
            fp.write(doc)
        total += 1
    return total


def write_images(catalog):
    os.makedirs(IMG_DIR, exist_ok=True)
    total = 0
    for item in catalog:
        slug = os.path.basename(item["cover"])[:-4]
        base = CATEGORY_STYLE[item["category"]] if item["category"] else None
        if base is None:
            # 现有商品的分类要去库里查，这里手工映射（按名称前缀判断不稳妥，
            # 所以直接把 category 补进 EXISTING 的图形名上不方便 —— 见下）
            base = CATEGORY_STYLE[EXISTING_CATEGORY[item["name"]]]
        # 同分类内按序号微调明度，免得一屏卡片全是同一个色块
        delta = (item["variant"] % 5) * 9 - 18
        c = dict(
            bg1=base["bg1"], bg2=base["bg2"],
            main=shade(base["main"], delta),
            accent=shade(base["accent"], delta // 2),
        )
        body = ART[item["art"]](c, item["variant"])
        doc = (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="800" height="800" '
            f'viewBox="0 0 800 800">'
            f'<defs><linearGradient id="bg-{slug}" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{c["bg1"]}"/>'
            f'<stop offset="1" stop-color="{c["bg2"]}"/>'
            f'</linearGradient></defs>'
            f'<rect width="800" height="800" fill="url(#bg-{slug})"/>'
            f'{body}</svg>'
        )
        with open(os.path.join(IMG_DIR, f"{slug}.svg"), "w", encoding="utf-8") as fp:
            fp.write(doc)
        total += 1

    # 站点 logo 和 favicon。不加 favicon 的话浏览器每次都会去请求
    # /favicon.ico 并留下一条 404。
    #
    # ★ 图案是【白色购物袋】，不是字母。
    #   一开始这里画的是「JD」两个字母的路径，结果渲染出来是一条竖线
    #   加一个 E —— 因为手写字母路径要摆对每一段坐标，写错了也照样
    #   是合法 SVG，只是长得不像那个字母。
    #   换成几何图形（袋子 + 提手）就不存在「画歪了」这种失败模式：
    #   它是不是袋子，一眼就能看出来。
    #   顺带避开字体问题 —— <img> 里的 SVG 是独立文档，引用不到页面字体。
    logo = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="72" height="72" viewBox="0 0 72 72">'
        '<rect width="72" height="72" rx="14" fill="#e1251b"/>'
        # 提手：一段描边的圆弧（只有下半段，看起来像拎手）
        '<path d="M26 30v-4a10 10 0 0 1 20 0v4" fill="none" stroke="#fff" '
        'stroke-width="5" stroke-linecap="round"/>'
        # 袋身
        '<path d="M19 30h34l-3.5 24a5 5 0 0 1-5 4.3H27.5a5 5 0 0 1-5-4.3z" fill="#fff"/>'
        '</svg>'
    )
    with open(os.path.join(IMG_DIR, "logo.svg"), "w", encoding="utf-8") as fp:
        fp.write(logo)
    with open(os.path.join(IMG_DIR, "favicon.svg"), "w", encoding="utf-8") as fp:
        fp.write(logo)
    return total


def sql_str(text):
    """
    把 Python 字符串变成 MySQL 单引号字面量。

    ★ 两个必须处理的转义：
      - 单引号 → 两个单引号（SQL 的标准做法）
      - 换行 → 反斜杠 n。MySQL 默认开启反斜杠转义，
        所以 '第一行\\n第二行' 会被解析成真正的换行。
        （ProductDetail.vue 的 .desc-text 是 white-space: pre-wrap，
          换行会原样渲染出来，模板一行都不用改。）
    """
    return "'" + text.replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n") + "'"


def write_migration(catalog):
    new_items = [i for i in catalog if i["is_new"]]
    old_items = [i for i in catalog if not i["is_new"]]

    lines = []
    a = lines.append
    a("-- " + "=" * 74)
    a(f"--  迁移脚本 08c：补齐用户端商品"
      f"（新增 {len(new_items)} 件 + 现有 {len(old_items)} 件补图补描述）")
    a("--")
    a("--  ⚠️ 只跑一次。这是增量迁移，不是初始化脚本。")
    a("--  这个文件由 sql/gen-shop-assets.py 生成，不要手工编辑 ——")
    a("--  改了下次重新生成就没了。要改商品，改那个脚本里的 PRODUCTS / EXISTING。")
    a("--")
    a("--  【为什么叫 08c 而不是 09？】")
    a("--  命名跟着里程碑走（migration-08-order.sql 是里程碑 8 的）。")
    a("--  这个任务是里程碑 8 之后的收尾补齐，09 要留给「模拟支付」。")
    a("--")
    a("--  【这个脚本做了什么】")
    a(f"--    1. 新增 {len(new_items)} 件商品（价格、库存、封面、描述）")
    a(f"--    2. 给现有 {len(old_items)} 件商品补封面和描述")
    a("--")
    a("--  【为什么补描述是安全的】")
    a("--  跑之前查过：现有 12 件的 description 长度分别是")
    a("--  11/10/10/10/6/7/12/9/0/0/0/0 个字符 —— 全是占位级别的碎片，")
    a("--  没有一条是你认真写过的。所以用 CHAR_LENGTH < 20 作为门槛，")
    a("--  【不会覆盖任何一条有实质内容的描述】。")
    a("--")
    a("--  【两处「不覆盖」的防线，缺一不可】")
    a("--  UPDATE 的 WHERE 里带着 `cover IS NULL OR cover = ''`，")
    a("--  让「不覆盖非空值」成为【数据库层面的约束】，而不是靠脚本自觉。")
    a("--  代价是：重复跑这个脚本是安全的，但也不会更新已填过的值。")
    a("--")
    a("--  【为什么分类 id 用变量取，不写死】")
    a("--  category 的 AUTO_INCREMENT 已经到 62、product 到 75 ——")
    a("--  5~8 和 11~61 这些 id 都被测试烧掉了。写死 id 的脚本")
    a("--  换一台机器（或者换一次测试）就插到错误的分类里去了。")
    a("--  用 SET @var = (SELECT ...) 取 id，取不到时变量是 NULL，")
    a("--  INSERT 会直接报「Column 'category_id' cannot be null」而中断 ——")
    a("--  这是好事：迁移脚本报错比静默插错地方安全得多。")
    a("--")
    a("--  执行方式：")
    a('--    "C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe" -u root -p123456 \\')
    a("--      --default-character-set=utf8mb4 mall < migration-08c-shop-catalog.sql")
    a("--")
    a("--  执行后验证：脚本末尾自带几个 SELECT，会打印商品总数和封面覆盖情况。")
    a("-- " + "=" * 74)
    a("")
    a("SET NAMES utf8mb4;")
    a("")

    # ---- 0. 前置检查：分类必须都在 ----
    a("-- ---------------------------------------------------------------------------")
    a("-- 0. 前置检查：这 6 个分类必须都存在，否则后面的 INSERT 会报错中断")
    a("-- ---------------------------------------------------------------------------")
    a("SELECT name AS '分类', id AS 'id', sort AS '排序'")
    a("  FROM category")
    a(" WHERE name IN ('手机数码','电脑办公','家用电器','服饰鞋包','休闲零食','床上用品')")
    a(" ORDER BY sort;")
    a("")

    # ---- 1. 分类变量 ----
    a("-- ---------------------------------------------------------------------------")
    a("-- 1. 取分类 id（不写死数字，见文件头说明）")
    a("-- ---------------------------------------------------------------------------")
    for cat, var in CAT_VAR.items():
        a(f"SET {var} = (SELECT id FROM category WHERE name = '{cat}');")
    a("")

    # ---- 2. 新增商品 ----
    a("-- ---------------------------------------------------------------------------")
    a(f"-- 2. 新增 {len(new_items)} 件商品")
    a("-- ---------------------------------------------------------------------------")
    a("INSERT INTO product (category_id, name, price, stock, cover, description, status) VALUES")
    rows = []
    for i in new_items:
        rows.append(
            f"  ({CAT_VAR[i['category']]}, {sql_str(i['name'])}, {i['price']}, "
            f"{i['stock']}, {sql_str(i['cover'])}, {sql_str(chr(10).join(i['desc']))}, 1)"
        )
    a(",\n".join(rows) + ";")
    a("")

    # ---- 3. 现有商品补图补描述 ----
    a("-- ---------------------------------------------------------------------------")
    a(f"-- 3. 现有 {len(old_items)} 件商品：补封面和描述")
    a("--")
    a("--    ★ 两条 UPDATE 都带「原值为空/过短才写」的守卫。")
    a("--    ★ 按 TRIM(name) 匹配而不是按 id。")
    a("-- ---------------------------------------------------------------------------")
    for i in old_items:
        nm = sql_str(i["name"].strip())
        a(f"-- {i['name']}")
        a(f"UPDATE product SET cover = {sql_str(i['cover'])}")
        a(f" WHERE TRIM(name) = {nm} AND (cover IS NULL OR cover = '');")
        a(f"UPDATE product SET description = {sql_str(chr(10).join(i['desc']))}")
        a(f" WHERE TRIM(name) = {nm} AND (description IS NULL OR CHAR_LENGTH(description) < 20);")
        a("")

    # ---- 4. 验证 ----
    a("-- ---------------------------------------------------------------------------")
    a("-- 4. 执行后验证")
    a("--")
    a("--    ⚠️ 这几个是【整个库】的统计，不是本次改动的统计 ——")
    a("--       真正要确认的是「没有封面的商品数」必须是 0。")
    a("-- ---------------------------------------------------------------------------")
    a("SELECT COUNT(*) AS '商品总数' FROM product;")
    a("SELECT COUNT(*) AS '有封面的商品数' FROM product WHERE cover IS NOT NULL AND cover <> '';")
    a("SELECT COUNT(*) AS '★ 没有封面的商品数（应为 0）'")
    a("  FROM product WHERE cover IS NULL OR cover = '';")
    a("SELECT LEFT(cover, 36) AS '封面路径示例', COUNT(*) AS '件数'")
    a("  FROM product GROUP BY LEFT(cover, 36) ORDER BY 件数 DESC LIMIT 5;")

    path = os.path.join(HERE, "migration-08c-shop-catalog.sql")
    with open(path, "w", encoding="utf-8", newline="\n") as fp:
        fp.write("\n".join(lines) + "\n")
    return path


def write_mall_seed(catalog):
    """
    生成可以直接粘进 mall.sql 的 seed 片段。

    项目约定「两份都要改」：mall.sql 是「新人拿到项目一键建库」用的，
    migration 是「已有数据的库跟上」用的。少改一份，两条路就会分叉。
    """
    new_items = [i for i in catalog if i["is_new"]]
    old_items = [i for i in catalog if not i["is_new"]]

    lines = []
    a = lines.append
    a("-- " + "=" * 74)
    a("--  这段由 sql/gen-shop-assets.py 生成，要粘进 mall.sql 的 seed 段。")
    a("--  生成的文件本身不是迁移脚本，别单独执行它。")
    a("-- " + "=" * 74)
    a("")
    a("-- 分类（注意：休闲零食和床上用品原来只存在于你的库里，")
    a("-- mall.sql 里没有它们，所以全新安装时商品会插不进去）")
    a("INSERT INTO category (name, sort, status) VALUES")
    a(",\n".join(f"  ({sql_str(c)}, {n}, 1)" for n, c in enumerate(CAT_VAR, start=1)) + ";")
    a("")
    a("-- 商品")
    if MALL_SEED_STOCK_OVERRIDE:
        # ★ 这条注释必须【由生成器写出来】。
        #   手写在 mall.sql 里的话，下次重新生成就没了 ——
        #   而「一行库存是 0」如果没解释，看起来就像个 bug。
        for nm, st in MALL_SEED_STOCK_OVERRIDE.items():
            a(f"-- ⚠️ '{nm}' 的库存是故意写成 {st} 的（不是线上值 {OLD_PRICE_STOCK[nm][1]}）："
              f"这是全新装库时用来手动验「已售罄」的夹具。见 MALL_SEED_STOCK_OVERRIDE。")
    a("INSERT INTO product (category_id, name, price, stock, cover, description, status) VALUES")
    rows = []
    # ★ 现有商品排在【前面】，新增的 30 件跟在后面。
    #   顺序不影响执行结果，纯粹为了人读：mall.sql 原来那段就是这 7 件，
    #   把新商品接在尾巴上，diff 一眼能看出「加了什么」，
    #   而不是「整段都变了」。
    for i in old_items:
        cat = EXISTING_CATEGORY[i["name"]]
        price, stock = OLD_PRICE_STOCK[i["name"]]
        # 有覆盖的话以覆盖为准（见上面 MALL_SEED_STOCK_OVERRIDE 的说明）
        stock = MALL_SEED_STOCK_OVERRIDE.get(i["name"], stock)
        rows.append(
            f"  ((SELECT id FROM category WHERE name = {sql_str(cat)}), "
            f"{sql_str(i['name'])}, {price}, "
            f"{stock}, {sql_str(i['cover'])}, "
            f"{sql_str(chr(10).join(i['desc']))}, 1)"
        )
    for i in new_items:
        rows.append(
            f"  ((SELECT id FROM category WHERE name = {sql_str(i['category'])}), "
            f"{sql_str(i['name'])}, {i['price']}, {i['stock']}, "
            f"{sql_str(i['cover'])}, {sql_str(chr(10).join(i['desc']))}, 1)"
        )
    a(",\n".join(rows) + ";")
    a("")

    path = os.path.join(HERE, "generated-mall-seed.sql")
    with open(path, "w", encoding="utf-8", newline="\n") as fp:
        fp.write("\n".join(lines) + "\n")
    return path


# ---------------------------------------------------------------------------
# 现有商品的两个补充表
#
# 为什么单独放：现有商品在库里已经有价格和库存了，本脚本【不去改它们】，
# 但生成 mall.sql 的 seed 时需要知道当初的值。
# 下面是从【线上库里现查到的真实值】抄过来的，不是猜的。
# ---------------------------------------------------------------------------

# 名称 → 分类（生成图片时选色系用）
EXISTING_CATEGORY = {
    "小米 15 Pro 手机": "手机数码",
    "iPad Air 11 英寸": "手机数码",
    "联想 ThinkPad X1 Carbon": "电脑办公",
    "罗技 MX Master 3S 鼠标": "电脑办公",
    "戴森 V12 吸尘器": "家用电器",
    "美的电饭煲 4L": "家用电器",
    "优衣库轻型羽绒服": "服饰鞋包",
    "iPhone duo": "手机数码",
    "iPhone 18 pro 256G": "手机数码",
    "联想拯救者Y9000P": "电脑办公",
    "卫龙辣条": "休闲零食",
    "床单": "床上用品",
}

# 名称 → (价格, 库存)，抄自线上库，供 mall.sql 的 seed 用
OLD_PRICE_STOCK = {
    "小米 15 Pro 手机": ("4999.00", 100),
    "iPad Air 11 英寸": ("4799.00", 50),
    "联想 ThinkPad X1 Carbon": ("9999.00", 30),
    "罗技 MX Master 3S 鼠标": ("699.00", 200),
    "戴森 V12 吸尘器": ("3699.00", 40),
    "美的电饭煲 4L": ("399.00", 150),
    "优衣库轻型羽绒服": ("598.00", 2),
    "iPhone duo": ("15999.00", 100),
    "iPhone 18 pro 256G": ("9999.00", 1000),
    "联想拯救者Y9000P": ("9999.00", 999),
    "卫龙辣条": ("9.90", 100),
    "床单": ("99.00", 100),
}

# mall.sql 里【故意】和线上库不一样的库存。
#
# ★ 为什么要有这么一张表，而不是直接把上面那张改了：
#   上面那张自称「抄自线上库」，它的唯一职责是【如实反映线上】。
#   往里面塞一个 0 会让那句注释变成假话，下一个人就再也分不清
#   「0 是真的，还是有人为了让某个测试跑通改的」。
#   所以「如实反映」和「故意偏离」分成两张表，各自的说的话都是真的。
#
# ★ 为什么偏偏是这一件要偏离：
#   mall.sql 里这一行原来的库存就是 0，注释写着「已售罄，用于测试库存不足」。
#   那是一个【有意的】手动测试夹具：全新装一遍库，商品列表里就有一件售罄的，
#   可以直接点进去看「已售罄」按钮和购物车里的失效行。
#   线上库后来被改成 2 了，但这个夹具的价值和线上值无关 —— 保留。
MALL_SEED_STOCK_OVERRIDE = {
    "优衣库轻型羽绒服": 0,
}


# ---------------------------------------------------------------------------
# 跑之前先看一眼：库里那 12 件商品的现状
#
# ★ 这是「动用户数据之前先查一遍」的那一步。
#   打印出来是为了让你核对：有没有哪一条其实是你认真写过的、
#   不该被覆盖 —— 那样的话把门槛从 CHAR_LENGTH < 20 调低，或者把它从
#   EXISTING 里删掉。
# ---------------------------------------------------------------------------
def show_current():
    names = ",".join(sql_str(n) for n in EXISTING_CATEGORY)
    sql = (
        "SELECT id, TRIM(name), IFNULL(CHAR_LENGTH(description), -1), "
        "IFNULL(cover, '<NULL>') FROM product "
        f"WHERE TRIM(name) IN ({names}) ORDER BY id;"
    )
    r = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, "mall"],
        capture_output=True,
    )
    if r.returncode != 0:
        print("  （查不到线上库，跳过现状核对）")
        print("   ", r.stderr.decode("utf-8", "replace").strip()[:300])
        return
    print("  库里现有商品的【改动前】状态：")
    print("  id | 名称 | 描述字符数 | 封面")
    # ★ 这里【不能】对整段输出做 .strip()。
    #   mysql -B 是 tab 分隔且不以 tab 结尾，所以 cover 为空串的行会长成
    #   "76\t名称\t0\t"（结尾一个 tab）。整段 strip() 会把【最后一行】的那个
    #   结尾 tab 一起吃掉 → 那一行 split 出来只有 3 段 → 被下面的
    #   len(parts) >= 4 过滤掉 → 报「没匹配上」。
    #   结果是：明明匹配上了，却报了一个假警报。
    #   （这个 bug 真的写出来过，而且看起来完全像是数据有问题。）
    raw = r.stdout.decode("utf-8", "replace")
    rows = [ln for ln in raw.split("\n") if ln != ""]
    found = set()
    for line in rows:
        parts = line.rstrip("\r").split("\t")
        if len(parts) >= 4:
            found.add(parts[1])
            flag = "  ← 会被补描述" if parts[2].isdigit() and int(parts[2]) < 20 else ""
            print(f"  {parts[0]:>4} | {parts[1]:<28} | {parts[2]:>4} | {parts[3]}{flag}")
    for nm in EXISTING_CATEGORY:
        if nm not in found:
            print(f"  ⚠️ 没匹配上：{nm}")
            print("      —— 脚本里的名字和库里的对不上，这条的图和描述【不会生效】。")


def main():
    catalog = build_catalog()
    print("=" * 68)
    print("生成用户端商品资源")
    print("=" * 68)
    print(f"  新增商品 {sum(1 for i in catalog if i['is_new'])} 件，"
          f"现有商品 {sum(1 for i in catalog if not i['is_new'])} 件")

    print()
    show_current()

    print()
    n = write_images(catalog)
    print(f"  ✓ 生成 {n} 张商品图 + logo.svg + favicon.svg → {IMG_DIR}")
    b = write_banners()
    print(f"  ✓ 生成 {b} 张首页横幅")

    mig = write_migration(catalog)
    print(f"  ✓ 迁移脚本 → {mig}")

    seed = write_mall_seed(catalog)
    print(f"  ✓ mall.sql 的 seed 片段 → {seed}")

    print()
    print("  接下来：")
    print("    1. mysqldump -u root -p123456 mall > sql/backup-mall-<时间戳>.sql")
    print('    2. "C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe" '
          "-u root -p123456 \\")
    print("         --default-character-set=utf8mb4 mall < sql/migration-08c-shop-catalog.sql")
    print("    3. 把 generated-mall-seed.sql 的内容替换进 mall.sql 的 seed 段")


if __name__ == "__main__":
    main()
