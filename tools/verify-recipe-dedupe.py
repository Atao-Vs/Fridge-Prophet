"""对**真实开发库**跑一遍菜谱去重验证（回归工具）。

背景
----
修复前 `/recipes/generate` 是无条件 INSERT，反复点「生成新菜谱」
会把同名菜攒成十几条（实测「番茄鸡蛋豆腐」重复 11 次）。

`tests/test_e2e.py` 用临时库证明了代码逻辑对；
这个脚本打的是 `backend/fridge_prophet.db`，用来确认**真实数据**也干净。

用法
----
    cd backend
    .venv/Scripts/python.exe ../tools/verify-recipe-dedupe.py

它会注册一个临时用户（`verify_<时间戳>@example.com`）走完整流程。
"""
import os
import sqlite3
import sys
import time
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1] / "backend"
DB_FILE = BACKEND / "fridge_prophet.db"

sys.path.insert(0, str(BACKEND))
# 必须在导入 app 之前设置 —— config 是模块级单例
os.environ["DATABASE_URL"] = f"sqlite:///{DB_FILE.as_posix()}"
os.environ["DASHSCOPE_API_KEY"] = ""

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

FAILED: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        print(f"  [OK]   {name}")
    else:
        FAILED.append(name)
        print(f"  [FAIL] {name}  {detail}")


def main() -> int:
    if not DB_FILE.exists():
        print(f"[x] 找不到数据库：{DB_FILE}")
        return 1

    print(f"目标库：{DB_FILE}")
    email = f"verify_{int(time.time())}@example.com"

    with TestClient(app) as client:
        print("\n--- 准备一个临时用户 ---")
        r = client.post(
            "/api/v1/auth/register",
            json={"email": email, "password": "verify123456", "nickname": "去重验证"},
        )
        check("注册验证用户", r.status_code == 201, r.text[:200])
        if r.status_code != 201:
            return 1
        headers = {"Authorization": f"Bearer {r.json()['access_token']}"}

        # 冰箱必须有东西，否则 /generate 直接返回空列表（设计如此）
        for food, qty, unit in (("鸡蛋", 6, "个"), ("西红柿", 3, "个"), ("小葱", 20, "g")):
            r = client.post(
                "/api/v1/inventory",
                headers=headers,
                json={"food_name": food, "quantity": qty, "unit": unit},
            )
            check(f"加入库存 {food}", r.status_code in (200, 201), r.text[:200])

        print("\n--- 反复生成 ---")
        r = client.post(
            "/api/v1/recipes/generate", headers=headers,
            json={"count": 3, "save": True},
        )
        check("首次生成", r.status_code == 200, r.text[:300])
        first = client.get("/api/v1/recipes", headers=headers).json()

        for i in range(3):
            r = client.post(
                "/api/v1/recipes/generate", headers=headers,
                json={"count": 3, "save": True},
            )
            check(f"第 {i + 2} 次生成仍然 200", r.status_code == 200, r.text[:200])

        after = client.get("/api/v1/recipes", headers=headers).json()
        names = [x["name"] for x in after]

        print("\n--- 断言 ---")
        check("反复生成后列表不增长",
              len(after) == len(first),
              f"{len(first)} → {len(after)} 条，说明又在重复入库")
        check("列表里没有同名菜谱",
              len(names) == len(set(names)),
              f"重名：{sorted({n for n in names if names.count(n) > 1})}")

    # 库里也不能留重复行 —— 列表层去重只是兜底，
    # 物理行还在的话用户删一条就会冒出另一条
    conn = sqlite3.connect(DB_FILE)
    dup = conn.execute(
        "SELECT user_id, name, COUNT(*) c FROM recipes GROUP BY user_id, name HAVING c > 1"
    ).fetchall()
    total = conn.execute("SELECT COUNT(*) FROM recipes").fetchone()[0]
    conn.close()
    check("数据库里没有同名菜谱行", not dup, str(dup))
    print(f"\n当前 recipes 表共 {total} 条。")

    if FAILED:
        print(f"\n失败 {len(FAILED)} 项：{FAILED}")
        return 1
    print("\n全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
