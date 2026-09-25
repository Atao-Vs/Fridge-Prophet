"""轻量级自动迁移：给已存在的表补上**后来新增的列**。

## 为什么不用 Alembic

Alembic 是标准方案，但它带来的成本是：多一个依赖、多一套迁移脚本、
部署时多一步 `alembic upgrade head`，而且每次改模型都要手工生成脚本。
这个项目数据量小、部署环境单一（一台服务器 + 一个 Supabase 库），
这些成本大于收益。

## 这个方案做什么、不做什么

**做**：启动时检查每张表缺哪些登记过的列，缺就 `ALTER TABLE ... ADD COLUMN`。
SQLite 和 PostgreSQL 都支持 `ADD COLUMN`，对新增可空列/带默认值的列是安全操作。

**不做**：改列类型、删列、加外键约束、数据回填。
这些必须手工处理——真需要的时候再引入 Alembic 也不迟。

## 新增字段时的操作

1. 改 `app/models/` 里的模型
2. 在下面的 `_ADDED_COLUMNS` 里登记这一列（含 SQLite 与 PostgreSQL 两种类型）
3. 重启服务，迁移会自动执行并打日志

**注意**：`Base.metadata.create_all` 只负责「建不存在的表」，
它**不会**给已存在的表加列——所以第 2 步不能省，
否则老库上会报 `no such column`。
"""
from __future__ import annotations

import logging

from sqlalchemy import inspect, text
from sqlalchemy.engine import Engine

logger = logging.getLogger(__name__)

# {表名: {列名: (SQLite 用的类型, PostgreSQL 用的类型)}}
# 只登记「建表之后新增的列」——建表时就有的列由 create_all 负责。
_ADDED_COLUMNS: dict[str, dict[str, tuple[str, str]]] = {
    "users": {
        "bio": ("VARCHAR(200)", "VARCHAR(200)"),
    },
    "health_preferences": {
        # SQLite 没有真正的布尔类型，用 0 表示假；
        # PostgreSQL 必须写 FALSE，写 0 会报类型错误。所以两边分开给。
        "low_sugar": ("BOOLEAN NOT NULL DEFAULT 0", "BOOLEAN NOT NULL DEFAULT FALSE"),
        "high_calcium": ("BOOLEAN NOT NULL DEFAULT 0", "BOOLEAN NOT NULL DEFAULT FALSE"),
        "high_iron": ("BOOLEAN NOT NULL DEFAULT 0", "BOOLEAN NOT NULL DEFAULT FALSE"),
        "low_purine": ("BOOLEAN NOT NULL DEFAULT 0", "BOOLEAN NOT NULL DEFAULT FALSE"),
        "no_raw_food": ("BOOLEAN NOT NULL DEFAULT 0", "BOOLEAN NOT NULL DEFAULT FALSE"),
    },
}


def ensure_columns(engine: Engine) -> list[str]:
    """补齐缺失的列。返回本次实际执行的 ALTER 语句（用于打日志和测试断言）。"""
    is_sqlite = engine.dialect.name == "sqlite"
    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())
    applied: list[str] = []

    for table, columns in _ADDED_COLUMNS.items():
        if table not in existing_tables:
            continue  # 表还不存在，create_all 会带着完整列建出来

        present = {col["name"] for col in inspector.get_columns(table)}
        for name, (sqlite_type, pg_type) in columns.items():
            if name in present:
                continue
            col_type = sqlite_type if is_sqlite else pg_type
            stmt = f"ALTER TABLE {table} ADD COLUMN {name} {col_type}"
            with engine.begin() as conn:
                conn.execute(text(stmt))
            applied.append(stmt)
            logger.info("自动迁移：%s", stmt)

    if applied:
        logger.info("自动迁移完成，共补充 %d 个列", len(applied))
    return applied
