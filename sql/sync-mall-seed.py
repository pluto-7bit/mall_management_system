#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 generated-mall-seed.sql 的 seed 段替换进 mall.sql。

    /d/python/python.exe sql/sync-mall-seed.py

===========================================================================
为什么单独一个脚本，而不是让 gen-shop-assets.py 顺手改了？
===========================================================================

因为 mall.sql 的开头是 7 条 DROP TABLE —— 它是「推倒重来」脚本。
让一个每天都在跑的生成器获得「改写这个文件」的权力，等于把
「不小心把开发库清空」变成一个可以静默发生的事故。

所以职责切成两半：
  gen-shop-assets.py  只写自己的产物（图片、migration、seed 片段），
                      碰不到任何已有的文件。
  sync-mall-seed.py   唯一一个会改 mall.sql 的地方，而且【要你主动跑】。

跑完它会打印替换了多少行，并且写完立刻重新读回来做一次自检。

===========================================================================
为什么用脚本替换，而不是「复制、粘贴」？
===========================================================================

手工粘 42 行 INSERT 是一个必定出错的操作：错了不会报错，
只会让全新装出来的库少一件商品、或者某件商品的封面路径指向一个
不存在的文件（表现为一张静默的占位图）。

而且这两份数据必须【永远】一致：
  mall.sql    → 新人拿到项目，一键建库
  migration   → 已有数据的库，跟上进度
少改一份，两条路就分叉了 —— 新人装出来的库和老开发机上的库不一样，
这种 bug 排查起来极其昂贵，因为「我本地是好的」。

===========================================================================
它怎么保证没搞错地方
===========================================================================
不按行号替换（行号会随着文件其他部分变动而失效），而是按【内容锚点】：
  · 起点：第一行以 `INSERT INTO category` 开头的行 再往前一行（`-- 分类`）
  · 终点：第一行以 `INSERT INTO member` 开头的行（`-- 会员`）
  · 替换前 assert 这两个锚点确实长这样，不对就直接退出，绝不半途改文件。
  · 只替换【一个】区间：mall.sql 里 category / product 两段 seed，
    正好夹在这两个锚点中间，admin_user 段在前面、member 段在后面，都不动。
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MALL = os.path.join(HERE, "mall.sql")
SEED = os.path.join(HERE, "generated-mall-seed.sql")

# seed 片段里正文的起点。这行以上的都是「这个文件是干嘛的」的说明，
# 不该进 mall.sql —— mall.sql 自己已经有小节标题了。
SEED_START = "-- 分类"


def find(lines, prefix, what):
    """返回第一个以 prefix 开头的行的下标；找不到就报错退出。"""
    for i, line in enumerate(lines):
        if line.startswith(prefix):
            return i
    sys.exit(f"✗ 在 {what} 里找不到以 {prefix!r} 开头的行 —— 文件结构变了，脚本需要更新。")


def section_start(lines, insert_idx, header, what):
    """
    从 INSERT 那一行【向上】找它所属的小节标题。

    ★ 为什么不能直接 insert_idx - 1：
      小节标题和 INSERT 之间隔着【注释行】和空行，行数不固定。
      本节标题下面是 1 行注释还是 2 行，取决于生成器怎么写 ——
      而生成器的注释是会变的（这一次就从 1 行变成了 2 行），
      `- 1` 这种写法在变的那天会静默地少替换一行，留下半截旧注释。

    所以一路向上找「最近的、以 header 开头的那行」，并且验证
    中间只夹着注释和空行 —— 没夹别的，才敢把它当小节边界。
    """
    for i in range(insert_idx - 1, -1, -1):
        s = lines[i].strip()
        if s.startswith(header):
            # 中间只允许出现注释行和空行
            for j in range(i + 1, insert_idx):
                t = lines[j].strip()
                if t != "" and not t.startswith("--"):
                    sys.exit(f"✗ {what} 第 {i+1} 行到第 {insert_idx+1} 行之间夹着非注释内容 "
                             f"（第 {j+1} 行：{lines[j]!r}）—— 中止，没有改动任何文件。")
            return i
    sys.exit(f"✗ 在 {what} 里找不到 {header!r} 小节标题 —— 中止，没有改动任何文件。")


def main():
    if not os.path.exists(SEED):
        sys.exit("✗ 没有 generated-mall-seed.sql，先跑：/d/python/python.exe sql/gen-shop-assets.py")

    mall = open(MALL, encoding="utf-8").read().split("\n")
    seed = open(SEED, encoding="utf-8").read().split("\n")

    # ---- 在 mall.sql 里定位替换区间 ----
    cat = find(mall, "INSERT INTO category", "mall.sql")
    mem = find(mall, "INSERT INTO member", "mall.sql")

    # ★ 边界靠【向上找小节标题】确定，不靠固定偏移量。
    #   这两条函数内部已经有「不对就退出」的检查：锚点找错、或者
    #   锚点和 INSERT 之间夹了非注释内容，都会【在改文件之前】中止。
    #   宁可报错停下来让人看一眼 —— 改错这里等于删掉一段建表语句。
    #
    # ★ 用「以 '-- 分类' 开头」而不是「等于 '-- 分类'」：
    #   第一次同步之后，这一行会变成带后缀的 `-- 分类（注意：…）`。
    #   用全等比较的话，脚本跑第二次就认不出自己的产物而中止 ——
    #   那意味着「改了商品数据想重新同步」这个最基本的操作做不了。
    #   前缀比较让它幂等：跑一次和跑十次结果一样。
    start = section_start(mall, cat, "-- 分类", "mall.sql")
    end = section_start(mall, mem, "-- 会员", "mall.sql")

    # ---- 取 seed 片段的正文 ----
    si = find(seed, SEED_START, "generated-mall-seed.sql")
    block = seed[si:]
    while block and block[-1].strip() == "":
        block.pop()

    # ---- 替换 ----
    new = mall[:start] + block + [""] + mall[end:]
    with open(MALL, "w", encoding="utf-8", newline="\n") as fp:
        fp.write("\n".join(new))

    # ---- 自检：读回来确认改对了 ----
    back = open(MALL, encoding="utf-8").read()
    problems = []
    if back.count("INSERT INTO product") != 1:
        problems.append("product 的 INSERT 不止一处")
    if back.count("INSERT INTO category") != 1:
        problems.append("category 的 INSERT 不止一处")
    if back.count("INSERT INTO member") != 1:
        problems.append("member 的 INSERT 丢了")
    if back.count("INSERT INTO admin_user") != 1:
        problems.append("admin_user 的 INSERT 丢了")
    if back.count("DROP TABLE") != 10:
        problems.append(f"DROP TABLE 数量变了（应为 10，实际 {back.count('DROP TABLE')}）")

    # ★ 这几条断言是里程碑 11 加的，加它的理由是一条真实踩过的设计陷阱：
    #   mall.sql 里 CREATE TABLE 分两处 —— 种子数据区【前面】的是表定义，
    #   【里面】的会被本脚本的替换区间整块吃掉。而上面那几条自检
    #   只数 INSERT 和 DROP TABLE，**根本检查不到 CREATE TABLE**。
    #   所以一张建表语句如果不小心落在了替换区间里（比如 `-- 分类` 和 `-- 会员`
    #   之间），它会被【静默删掉】—— 自检全绿，新人却建不出那张表。
    #   对每一张「不该出现在替换区间里」的表加一条存在性断言，
    #   把那个静默失败变成一次明确的报错。
    #
    #   ⚠️ 加新表的时候【一定要在这里补一条】。这个清单漏了一张表，
    #      就等于那张表的建表语句可以在无人察觉的情况下消失。
    for table in ("product_image", "product_review", "product_review_image"):
        if f"CREATE TABLE {table}" not in back:
            problems.append(f"{table} 的建表语句不见了（可能落进了替换区间）")

    print(f"  mall.sql: 替换了 {end - start} 行 → {len(block) + 1} 行")
    print(f"  商品行数：{back.count(chr(10) + '  ((SELECT')}")
    if problems:
        for p in problems:
            print(f"  ✗ {p}")
        sys.exit("✗ 自检没过。用 git 或者备份把 mall.sql 还原，然后检查脚本。")
    print("  ✓ 自检通过：category / product / member / admin_user 各一处，10 条 DROP TABLE 完好，"
          "product_image / product_review / product_review_image 三张表的建表语句都在位")


if __name__ == "__main__":
    main()
