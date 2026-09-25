"""数据库引擎与会话。SQLite / PostgreSQL 通过 DATABASE_URL 切换。"""
from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.core.config import settings

_is_sqlite = settings.DATABASE_URL.startswith("sqlite")

engine = create_engine(
    settings.DATABASE_URL,
    connect_args={"check_same_thread": False} if _is_sqlite else {},
    pool_pre_ping=not _is_sqlite,
    echo=False,
)

SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


class Base(DeclarativeBase):
    pass


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db() -> None:
    """建表 + 补齐后来新增的列。

    `create_all` 只建**不存在的表**，不会给已存在的表加列，
    所以后面还要跑一次 ensure_columns，否则老库上会报 no such column。
    见 app/db/migrate.py 的说明。
    """
    from app import models  # noqa: F401  触发模型注册
    from app.db.migrate import ensure_columns

    Base.metadata.create_all(bind=engine)
    ensure_columns(engine)
