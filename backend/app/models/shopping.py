"""采购清单与采购项。"""
from datetime import date, datetime, timezone

from sqlalchemy import Date, DateTime, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.session import Base


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class ShoppingList(Base):
    __tablename__ = "shopping_lists"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)

    title: Mapped[str] = mapped_column(String(128), default="今日采购")
    status: Mapped[str] = mapped_column(String(16), default="pending")  # pending/done
    # 关联的菜谱名，便于回溯「这单是为哪顿饭买的」
    source_recipes: Mapped[str | None] = mapped_column(String(512), nullable=True)
    planned_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )

    items: Mapped[list["ShoppingItem"]] = relationship(
        back_populates="shopping_list", cascade="all, delete-orphan", lazy="selectin"
    )


class ShoppingItem(Base):
    __tablename__ = "shopping_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    shopping_list_id: Mapped[int] = mapped_column(
        ForeignKey("shopping_lists.id", ondelete="CASCADE"), index=True
    )

    food_name: Mapped[str] = mapped_column(String(64))
    quantity: Mapped[float] = mapped_column(default=0)
    unit: Mapped[str] = mapped_column(String(16), default="个")
    category: Mapped[str] = mapped_column(String(32), default="其他")
    estimated_price: Mapped[float | None] = mapped_column(nullable=True)

    checked: Mapped[bool] = mapped_column(default=False)
    # 勾选后是否已经写回冰箱库存
    applied_to_inventory: Mapped[bool] = mapped_column(default=False)

    shopping_list: Mapped[ShoppingList] = relationship(back_populates="items")
