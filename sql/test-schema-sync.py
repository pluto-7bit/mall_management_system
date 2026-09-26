# -*- coding: utf-8 -*-
"""
结构一致性测试：mall.sql ⟷ 线上库

这个脚本和仓库里其它脚本【又不一样】，先说清它站在哪儿：

    test-order.py              打接口，问「后端的【行为】对不对」
    test-frontend-contract.py  打接口，问「前端的【假设】对不对」
    test-frontend-format.py    读源码，问「前端的【写法】对不对」
    这个脚本                   读 mall.sql + 查 information_schema，
                               问「初始化脚本和真实库是不是同一张表」

<h3>★ 它要守的是「两份都要改」这条约定</h3>

从里程碑 8 起，schema 变更必须同时改两处：
    mall.sql          → 全量脚本，「新人拿到项目能一键建库」
    migration-XX.sql  → 增量脚本，「已有数据的库能跟上」

这条约定在这之前**一直靠肉眼核对 SHOW CREATE TABLE 维持**。
而它漏掉的时候是【静默】的，而且症状出现在离改动最远的地方：

    漏改 mall.sql，对已有的库【毫无影响】——
    所有接口照常、所有测试照常绿，因为老库早就被迁移脚本改过了。
    唯一会踩到的是一个新人，在几天后按 README 建库，
    然后遇到一个「列不存在」或者「少了唯一索引」的错，
    而他会先怀疑自己的 MySQL 版本。

这正是本项目一路在防的那种 bug：**改动没问题，只是有一份副本没跟上。**
所以把它变成断言。里程碑 16 加 parent_id 时现写的，一下就抓到了自己一次。

<h3>运行条件</h3>
不需要后端，**也不需要 Redis**，但需要 MySQL 在跑（要查 information_schema）。
和 test-exception.py / test-frontend-format.py 一样属于「不需要前置条件」的例外。

    python test-schema-sync.py

<h3>已知盲区（写在这里，而不是假装没有）</h3>
  · COMMENT 里如果出现一个单引号（'），解析会错位。当前 12 张表没有这种注释。
  · 建表段里如果出现【行尾】的 `-- 注释`（和列定义同一行），
    它会被当成列定义的一部分。当前 mall.sql 里所有注释都独占一行。
    这两种情况都会让检查报假红，而不是漏报 —— 宁可吵，不要静。
  · 表级 COLLATE 不做比较：mall.sql 写的是 `DEFAULT CHARSET = utf8mb4`，
    线上 SHOW CREATE TABLE 会补出 `COLLATE=utf8mb4_0900_ai_ci`。
    两者等价（后者就是 utf8mb4 在 MySQL 8 的默认排序规则），
    给 12 张表都补一遍 COLLATE 是纯粹的噪音。
  · AUTO_INCREMENT 当然不比 —— 它被测试烧到过多少值，和 schema 无关。
"""

import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
# 允许用 argv[1] 换一个 mall.sql 来比 —— 这是这个脚本【自测】用的口子：
# 「一条不会红的检查等于没有检查」，所以它必须能被证明抓得住 drift。
# 做法是喂它一份人为改坏的副本（去掉一列 / 去掉一个索引），看它是否报红。
MALL_SQL = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "mall.sql")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"

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


def run_sql(sql):
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{sql}\n{result.stderr}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


# ---------------------------------------------------------------------------
# 一、解析 mall.sql
# ---------------------------------------------------------------------------

# 每张表的一条列定义 / 索引定义。类型统一成「小写、无空格」再比，
# 因为 mall.sql 写 `BIGINT UNSIGNED`，information_schema 给 `bigint unsigned`；
# mall.sql 写 `DECIMAL(10, 2)`，information_schema 给 `decimal(10,2)`。
TYPES = r"(BIGINT UNSIGNED|VARCHAR\(\d+\)|DECIMAL\(\d+,\s*\d+\)|INT|TINYINT|DATETIME|BIGINT|TEXT)"


def norm_type(t):
    return re.sub(r"\s+", "", t).lower()


def parse_mall_sql():
    text = open(MALL_SQL, encoding="utf-8").read()

    tables = {}
    for m in re.finditer(r"CREATE TABLE (\w+) \((.*?)\n\) ENGINE", text, re.S):
        name, body = m.group(1), m.group(2)
        # 独占一行的 `-- 注释` 整行丢掉（列定义里不出现行尾注释，见「已知盲区」）
        lines = [l.rstrip(",").rstrip() for l in body.splitlines()
                 if l.strip() and not l.strip().startswith("--")]

        cols, keys = [], []
        for line in lines:
            s = line.strip()
            if re.match(r"(PRIMARY KEY|UNIQUE KEY|KEY)\b", s):
                keys.append(norm_key(s.replace("`", "")))
                continue

            # ★★ 关键一步：先整段剥掉 COMMENT '...'，再做任何判断。
            #    否则注释里的字会骗过解析器 —— 加 parent_id 时第一次跑这个脚本，
            #    它的注释写着「刻意不用 NULL」，朴素的 \bNULL\b 直接把一个
            #    NOT NULL 的列判成了可空，检查对着完全正确的表报了假红。
            cmt = re.search(r"COMMENT '([^']*)'", s)
            stripped = re.sub(r"COMMENT '[^']*'", "", s)

            cname = re.match(r"(\w+)", stripped).group(1)
            ctype = re.search(TYPES, stripped)

            # 默认值。★ 一个必须归一的地方：
            #   mall.sql 里的可空列写成 `NULL DEFAULT NULL`，
            #   而 information_schema 对「没有默认值」这一列返回的也是 NULL。
            #   所以 `DEFAULT NULL` 要归一成 None —— 不归一的话，
            #   12 个可空列会一起报「默认值不一致」，而这个检查第一天就被忽略掉了。
            dflt = re.search(r"DEFAULT (CURRENT_TIMESTAMP|'[^']*'|\S+)", stripped)
            dflt = dflt.group(1).strip("'") if dflt else None
            if dflt is not None and dflt.upper() == "NULL":
                dflt = None

            cols.append({
                "name": cname,
                "type": norm_type(ctype.group(1)) if ctype else stripped,
                "null": "YES" if re.search(r"\bNULL\b", stripped.replace("NOT NULL", "")) else "NO",
                "default": dflt,
                "comment": cmt.group(1) if cmt else "",
            })
        tables[name] = {"cols": cols, "keys": keys}

    drops = re.findall(r"^DROP TABLE IF EXISTS (\w+);", text, re.M)
    return tables, drops


def norm_key(k):
    """`UNIQUE KEY uk_x (a, b)` → `uk_x(a,b)`。列顺序算进 key 里：
    uk_product_spec 的 (product_id, spec_json) 和 (spec_json, product_id)
    是两个不同的索引，只是名字一样。"""
    k = k.replace("PRIMARY KEY", "PRIMARY")
    m = re.match(r"(?:UNIQUE KEY |KEY )?(\w+)?\s*\((.*)\)$", k)
    if not m:
        return re.sub(r"\s+", "", k)
    return f"{m.group(1) or 'PRIMARY'}({re.sub(r'[ ]', '', m.group(2))})"


# ---------------------------------------------------------------------------
# 二、查线上库
# ---------------------------------------------------------------------------

def live_tables():
    return sorted(r[0] for r in run_sql(
        "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='%s'" % DB))


def live_cols(table):
    rows = run_sql("""
        SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, IFNULL(COLUMN_DEFAULT, '<<NULL>>'),
               IFNULL(COLUMN_COMMENT, '')
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA='%s' AND TABLE_NAME='%s' ORDER BY ORDINAL_POSITION""" % (DB, table))
    out = []
    for r in rows:
        out.append({
            "name": r[0], "type": norm_type(r[1]), "null": r[2],
            "default": None if r[3] == "<<NULL>>" else r[3], "comment": r[4],
        })
    return out


def live_keys(table):
    rows = run_sql("""
        SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA='%s' AND TABLE_NAME='%s' ORDER BY INDEX_NAME, SEQ_IN_INDEX"""
                   % (DB, table))
    grouped = {}
    for name, _seq, col in rows:
        grouped.setdefault(name, []).append(col)
    return sorted(f"{k}({','.join(v)})" for k, v in grouped.items())


# ---------------------------------------------------------------------------
# 三、比
# ---------------------------------------------------------------------------

def main():
    section("0 · 表清单：mall.sql 的 DROP 列表 = CREATE 列表 = 线上库")
    tables, drops = parse_mall_sql()
    live = live_tables()

    check("mall.sql 里 DROP TABLE 的数量 = CREATE TABLE 的数量（%d 张）" % len(tables),
          len(drops) == len(tables),
          f"DROP {len(drops)} 个 / CREATE {len(tables)} 个 —— 有一边漏了")

    missing_drop = sorted(set(tables) - set(drops))
    check("每张建的表都在 DROP 列表里（否则重复跑建库会报「已存在」）",
          not missing_drop, "缺 DROP：%s" % missing_drop)

    only_file = sorted(set(tables) - set(live))
    only_live = sorted(set(live) - set(tables))
    check("线上库的表 = mall.sql 建的表",
          not only_file and not only_live,
          "只在 mall.sql 里：%s\n只在线上库里：%s" % (only_file, only_live))

    section("1 · 逐表核对：列（名字/顺序/类型/可空/默认值/注释）")
    for name in sorted(tables):
        if name not in live:
            continue
        f, d = tables[name]["cols"], live_cols(name)

        if len(f) != len(d):
            check(f"{name}: 列数一致", False,
                  f"mall.sql {len(f)} 列 vs 线上 {len(d)} 列")
            continue

        diffs = []
        for i, (a, b) in enumerate(zip(f, d)):
            for field, label in (("name", "列名"), ("type", "类型"), ("null", "可空"),
                                 ("default", "默认值"), ("comment", "注释")):
                if a[field] != b[field]:
                    diffs.append(f"#{i} {b['name']} 的{label}："
                                 f"mall.sql={a[field]!r}  线上={b[field]!r}")
        check(f"{name}: {len(f)} 列逐列一致", not diffs, "\n".join(diffs))

    section("2 · 逐表核对：索引（名字 + 列 + 列顺序）")
    for name in sorted(tables):
        if name not in live:
            continue
        f, d = sorted(tables[name]["keys"]), live_keys(name)
        check(f"{name}: {len(d)} 个索引一致", f == d,
              "只在 mall.sql 里：%s\n只在线上库里：%s"
              % (sorted(set(f) - set(d)), sorted(set(d) - set(f))))

    section("3 · 全库约定")
    fk = run_sql("""SELECT TABLE_NAME, CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
                    WHERE CONSTRAINT_SCHEMA='%s' AND CONSTRAINT_TYPE='FOREIGN KEY'""" % DB)
    check("全库零外键（既定约定，删除顺序由代码负责）", not fk, str(fk))

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


if __name__ == "__main__":
    main()
