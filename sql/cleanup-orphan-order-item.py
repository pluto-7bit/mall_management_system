# -*- coding: utf-8 -*-
"""
一次性清理：删除 order_item 里指向不存在订单的孤儿行。

为什么会存在这个脚本？
    里程碑 8 的 test-order.py 第一版在「第 15 节 清理」里多写了一句
        DELETE FROM orders WHERE member_id = ...
    这句在执行 cleanup() **之前**跑，把订单删了却没删明细。
    而 order_item.order_id 是【故意不加外键】的（见 mall.sql 的说明），
    所以数据库不会拦 —— 32 行明细就这么留在了库里，指向已经不存在的订单。

    test-order.py 现在已经有「孤儿检查」这条断言（第 15 节最后），
    并且清理只走 cleanup() 一个入口，不会再产生新的孤儿。
    这个脚本只用来收拾这一次的遗留。

用法：
    python cleanup-orphan-order-item.py
"""

import subprocess

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "mall"


def run_sql(sql):
    result = subprocess.run(
        [MYSQL, "-u", "root", "-p123456", "--default-character-set=utf8mb4",
         "-N", "-B", "-e", sql, DB],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"SQL 执行失败：{sql}\n{result.stderr}")
    return [line.split("\t") for line in result.stdout.strip().splitlines() if line]


ORPHANS = ("SELECT i.id, i.order_id, i.product_id, i.product_name, i.quantity "
           "FROM order_item i LEFT JOIN orders o ON o.id = i.order_id "
           "WHERE o.id IS NULL ORDER BY i.id")

print("=== 清理前 ===")
print(f"orders     总数 = {run_sql('SELECT COUNT(*) FROM orders')[0][0]}")
print(f"order_item 总数 = {run_sql('SELECT COUNT(*) FROM order_item')[0][0]}")

rows = run_sql(ORPHANS)
print(f"\n孤儿明细 {len(rows)} 行（指向不存在的订单）：")
for r in rows:
    print(f"  item.id={r[0]}  order_id={r[1]}  product_id={r[2]}  "
          f"{r[3]}  x{r[4]}")

if rows:
    run_sql("DELETE i FROM order_item i "
            "LEFT JOIN orders o ON o.id = i.order_id WHERE o.id IS NULL")

print("\n=== 清理后 ===")
print(f"order_item 总数 = {run_sql('SELECT COUNT(*) FROM order_item')[0][0]}")
print(f"孤儿明细        = {len(run_sql(ORPHANS))} 行")

# 确认用户自己的数据没有被碰到
print("\n=== 用户自己的数据（应保持不变）===")
print(f"product  总数 = {run_sql('SELECT COUNT(*) FROM product')[0][0]}")
print(f"category 总数 = {run_sql('SELECT COUNT(*) FROM category')[0][0]}")
print(f"member   总数 = {run_sql('SELECT COUNT(*) FROM member')[0][0]}")
print(f"member_address 总数 = "
      f"{run_sql('SELECT COUNT(*) FROM member_address')[0][0]}")
