"""Supabase 连接自检。

填好 .env 里的 DATABASE_URL / SUPABASE_URL / SUPABASE_SERVICE_KEY 之后运行：

    python scripts/check_supabase.py

它会依次检查：数据库能否连通、表是否建好、Storage bucket 能否访问。
"""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_DIR))

from app.core.config import settings  # noqa: E402

OK = "  [OK]  "
BAD = "  [FAIL]"
WARN = "  [WARN]"


def main() -> int:
    problems = 0

    print("\n=== 1. 配置检查 ===")
    is_sqlite = settings.DATABASE_URL.startswith("sqlite")
    print(f"{WARN if is_sqlite else OK} 数据库: {'SQLite（开发模式）' if is_sqlite else 'PostgreSQL'}")
    if not is_sqlite:
        masked = settings.DATABASE_URL
        if "@" in masked:
            head, tail = masked.split("@", 1)
            masked = head.split("://")[0] + "://****:****@" + tail
        print(f"         {masked}")

    print(f"{OK if settings.ai_enabled else WARN} AI: "
          f"{'通义千问 ' + settings.VISION_MODEL if settings.ai_enabled else 'MOCK 模式（未配置密钥）'}")

    storage_on = settings.supabase_storage_enabled
    print(f"{OK if storage_on else WARN} 图片存储: "
          f"{'Supabase Storage / ' + settings.SUPABASE_BUCKET if storage_on else '本地磁盘'}")

    if settings.SECRET_KEY.startswith("change-me") or settings.SECRET_KEY.startswith("dev-only"):
        print(f"{WARN} SECRET_KEY 还是默认值，上线前务必换掉")
        print('         生成方式: python -c "import secrets;print(secrets.token_urlsafe(48))"')

    print("\n=== 2. 数据库连接 ===")
    try:
        from sqlalchemy import inspect, text

        from app.db.session import Base, engine, init_db

        dialect = engine.dialect.name
        probe = "SELECT version()" if dialect == "postgresql" else "SELECT sqlite_version()"
        with engine.connect() as conn:
            version = conn.execute(text(probe)).scalar()
        print(f"{OK} 连接成功（{dialect}）")
        print(f"         {str(version)[:90]}")

        init_db()
        expected = sorted(Base.metadata.tables.keys())
        print(f"{OK} 建表完成，共 {len(expected)} 张表")

        existing = set(inspect(engine).get_table_names())
        missing = [t for t in expected if t not in existing]
        if missing:
            print(f"{BAD} 以下表未创建成功: {missing}")
            problems += 1
        else:
            print(f"{OK} 全部表已存在于数据库中")
            for t in expected:
                print(f"         - {t}")

    except Exception as exc:  # noqa: BLE001
        print(f"{BAD} 数据库连接失败: {exc}")
        print("         排查方向：")
        print("         1) DATABASE_URL 里的 postgresql:// 是否改成了 postgresql+psycopg://")
        print("         2) 密码里若有 @ # % 等字符，需要 URL 编码（@ → %40）")
        print("         3) Supabase 项目是否被暂停（免费版闲置 7 天会暂停）")
        print("         4) 用户名格式应为 postgres.项目ID，不是 postgres")
        print("         5) 端口：常驻服务器用 5432，Serverless 才用 6543")
        problems += 1

    print("\n=== 3. Supabase Storage ===")
    if not storage_on:
        print(f"{WARN} 未配置，跳过。不配也能跑，照片存服务器本地磁盘")
    else:
        from app.services.storage_service import ensure_bucket

        if ensure_bucket():
            print(f"{OK} bucket 可用: {settings.SUPABASE_BUCKET}")
        else:
            print(f"{BAD} bucket 不可用，检查 SUPABASE_URL / SUPABASE_SERVICE_KEY 是否正确")
            problems += 1

    print("\n" + "=" * 56)
    print("全部就绪，可以启动服务了" if problems == 0 else f"发现 {problems} 个问题，见上方说明")
    print("=" * 56 + "\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
