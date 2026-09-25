"""菜谱、菜谱食材、用餐历史。"""
from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.session import Base

DIFFICULTIES = ("easy", "medium", "hard")
# 用户行为类型，用于策划书第七节的「AI 会学习」
MEAL_ACTIONS = ("view", "favorite", "cook", "skip", "rate")


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Recipe(Base):
    __tablename__ = "recipes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # NULL 表示系统内置菜谱，所有用户可见
    user_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=True, index=True
    )

    name: Mapped[str] = mapped_column(String(128), index=True)
    description: Mapped[str] = mapped_column(Text, default="")
    time_minutes: Mapped[int] = mapped_column(Integer, default=30)
    difficulty: Mapped[str] = mapped_column(String(16), default="easy")
    steps: Mapped[list] = mapped_column(JSON, default=list)
    # 营养数据一律标注为估算值，不构成医疗建议
    nutrition: Mapped[dict] = mapped_column(JSON, default=dict)
    tags: Mapped[list] = mapped_column(JSON, default=list)

    source: Mapped[str] = mapped_column(String(16), default="ai")  # ai / builtin
    image_url: Mapped[str | None] = mapped_column(String(512), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    ingredients: Mapped[list["RecipeIngredient"]] = relationship(
        back_populates="recipe", cascade="all, delete-orphan", lazy="selectin"
    )


class RecipeIngredient(Base):
    __tablename__ = "recipe_ingredients"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    recipe_id: Mapped[int] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), index=True
    )

    name: Mapped[str] = mapped_column(String(64), index=True)
    quantity: Mapped[float] = mapped_column(Float, default=0)
    unit: Mapped[str] = mapped_column(String(16), default="g")
    optional: Mapped[bool] = mapped_column(default=False)

    recipe: Mapped[Recipe] = relationship(back_populates="ingredients")


class MealHistory(Base):
    """记录用户看过/收藏/做过/跳过/评分的菜，用于逐步修正画像。"""

    __tablename__ = "meal_history"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    recipe_id: Mapped[int] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), index=True
    )

    action: Mapped[str] = mapped_column(String(16), default="view")
    rating: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 1-5
    cooked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, index=True
    )


class RecipeFeedback(Base):
    """用户对某道 AI 生成菜的显式负反馈，直接进推荐约束。"""

    __tablename__ = "recipe_feedback"
    __table_args__ = (UniqueConstraint("user_id", "recipe_name", name="uq_user_recipe_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    recipe_name: Mapped[str] = mapped_column(String(128), index=True)
    disliked: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
