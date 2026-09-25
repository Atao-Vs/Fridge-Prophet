"""清理菜谱表里的重复行：同一个用户的同名菜谱只保留最新一条。

背景
----
修复前 `/recipes/generate` 是无条件 INSERT。用户每点一次「生成新菜谱」
就多存一组，而 MOCK 模式和低温度下的模型又总给同样的几道菜，
于是库里攒下大量同名行（实测「番茄鸡蛋豆腐」重复了 11 条），
菜谱页看起来就是同一道菜刷屏。

代码已经改成 upsert 了（见 api/v1/recipes.py::_persist_or_update），
但**历史脏数据还在库里**，所以需要跑一次这个脚本清掉。

用法
----
    cd backend
    .venv/Scripts/python.exe ../tools/dedupe-recipes.py --dry-run   # 先看要删什么
    .venv/Scripts/python.exe ../tools/dedupe-recipes.py             # 真删
"""
import argparse
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = ROOT / "backend" / "fridge_prophet.db"


def main() -> int:
    ap = argparse.ArgumentParser(description="删除重复菜谱行（同名只保留最新一条）")
    ap.add_argument("--db", default=str(DEFAULT_DB), help="SQLite 数据库文件路径")
    ap.add_argument("--dry-run", action="store_true", help="只报告，不写入")
    args = ap.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"[x] 找不到数据库：{db_path}")
        return 1

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA foreign_keys = ON")
    cur = conn.cursor()

    before = cur.execute("SELECT COUNT(*) FROM recipes").fetchone()[0]

    dupes = cur.execute(
        """
        SELECT user_id, name, COUNT(*) AS c
        FROM recipes
        GROUP BY user_id, name
        HAVING c > 1
        ORDER BY c DESC
        """
    ).fetchall()

    if not dupes:
        print(f"没有重复菜谱，无需清理（recipes 共 {before} 条）。")
        conn.close()
        return 0

    print(f"发现 {len(dupes)} 组重复菜谱：\n")
    total_dropped = 0

    for user_id, name, count in dupes:
        ids = [
            r[0]
            for r in cur.execute(
                "SELECT id FROM recipes WHERE user_id IS ? AND name = ? ORDER BY id DESC",
                (user_id, name),
            ).fetchall()
        ]
        keep, drop = ids[0], ids[1:]
        print(f"  用户 {user_id} / {name}：{count} 条 → 保留 id={keep}，删除 {len(drop)} 条")
        total_dropped += len(drop)
        if args.dry_run:
            continue

        marks = ",".join("?" * len(drop))
        # 手动清子表，不依赖 SQLite 的 FK 级联（它默认是关的）
        cur.execute(f"DELETE FROM meal_history WHERE recipe_id IN ({marks})", drop)
        cur.execute(f"DELETE FROM recipe_ingredients WHERE recipe_id IN ({marks})", drop)
        cur.execute(f"DELETE FROM recipes WHERE id IN ({marks})", drop)

    if args.dry_run:
        print(f"\n[试运行] 共将删除 {total_dropped} 条，未写入数据库。")
        print("确认无误后去掉 --dry-run 再跑一次。")
    else:
        conn.commit()
        after = cur.execute("SELECT COUNT(*) FROM recipes").fetchone()[0]
        print(f"\n[完成] 已删除 {total_dropped} 条。recipes：{before} → {after} 条。")

    conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
