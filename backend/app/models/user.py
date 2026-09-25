"""用户、用户画像、健康偏好、家庭成员。"""
from datetime import datetime, timezone

from sqlalchemy import JSON, DateTime, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.session import Base


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    nickname: Mapped[str] = mapped_column(String(64), default="")
    hashed_password: Mapped[str] = mapped_column(String(255))
    avatar_url: Mapped[str | None] = mapped_column(String(512), nullable=True)
    bio: Mapped[str | None] = mapped_column(String(200), nullable=True)  # 个性简介
    is_active: Mapped[bool] = mapped_column(default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)

    preference: Mapped["UserPreference"] = relationship(
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )
    health: Mapped["HealthPreference"] = relationship(
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )
    family_members: Mapped[list["FamilyMember"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    privacy: Mapped["PrivacySetting"] = relationship(
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )


class UserPreference(Base):
    """策划书第六节的快速问答结果。"""

    __tablename__ = "user_preferences"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True
    )

    cuisine: Mapped[str] = mapped_column(String(32), default="家常菜")  # 菜系偏好
    taste: Mapped[str] = mapped_column(String(32), default="正常")  # 清淡/正常/重口/辣
    cook_time_max: Mapped[int] = mapped_column(Integer, default=30)  # 分钟
    diet_goal: Mapped[str] = mapped_column(String(32), default="正常饮食")
    disliked_foods: Mapped[list] = mapped_column(JSON, default=list)  # 不吃的
    allergies: Mapped[list] = mapped_column(JSON, default=list)  # 过敏
    onboarded: Mapped[bool] = mapped_column(default=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )

    user: Mapped[User] = relationship(back_populates="preference")


class HealthPreference(Base):
    """健康管理模式。仅做一般性营养约束，不做疾病诊断。"""

    __tablename__ = "health_preferences"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True
    )

    low_carb: Mapped[bool] = mapped_column(default=False)
    low_sodium: Mapped[bool] = mapped_column(default=False)
    low_fat: Mapped[bool] = mapped_column(default=False)
    high_protein: Mapped[bool] = mapped_column(default=False)
    high_fiber: Mapped[bool] = mapped_column(default=False)
    vegetarian: Mapped[bool] = mapped_column(default=False)

    # 扩充项：都属于「一般性营养取向」，不涉及疾病诊断或治疗建议
    low_sugar: Mapped[bool] = mapped_column(default=False)  # 控糖
    high_calcium: Mapped[bool] = mapped_column(default=False)  # 补钙
    high_iron: Mapped[bool] = mapped_column(default=False)  # 补铁
    low_purine: Mapped[bool] = mapped_column(default=False)  # 低嘌呤（痛风人群常需）
    no_raw_food: Mapped[bool] = mapped_column(default=False)  # 避免生食（孕期/免疫力低）

    height_cm: Mapped[float | None] = mapped_column(Float, nullable=True)
    weight_kg: Mapped[float | None] = mapped_column(Float, nullable=True)
    age: Mapped[int | None] = mapped_column(Integer, nullable=True)
    activity_level: Mapped[str | None] = mapped_column(String(32), nullable=True)

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )

    user: Mapped[User] = relationship(back_populates="health")


class PrivacySetting(Base):
    """「我的主页在广场里能被别人看到什么」的开关。

    ## 为什么不塞进 HealthPreference

    这些字段管的是**展示层的可见性**，和「该吃什么」的业务逻辑毫无关系。
    混进 HealthPreference 以后，读健康约束的代码（菜谱生成）会顺手把
    `share_health` 这类字段也一起读走，早晚有人把它当成营养开关用。
    分开放，两类字段各自只有一个含义。

    ## 默认全关

    健康偏好、身体数据属于敏感信息。默认必须是「不公开」，
    由用户主动打开。反过来（默认公开）在隐私上是不可接受的：
    用户没做过任何选择，信息就已经对全站可见了。

    ## 为什么拆成 5 个开关而不是 1 个总开关

    用户想分享饮食口味不代表愿意公开身高体重。
    粗粒度的总开关会逼用户二选一，最后结果是全部关掉——
    等于这个功能不存在。
    """

    __tablename__ = "privacy_settings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True
    )

    share_preference: Mapped[bool] = mapped_column(default=False)  # 饮食偏好
    share_health: Mapped[bool] = mapped_column(default=False)  # 健康管理目标
    share_body: Mapped[bool] = mapped_column(default=False)  # 身高/体重/年龄
    share_family: Mapped[bool] = mapped_column(default=False)  # 家庭成员
    share_stats: Mapped[bool] = mapped_column(default=False)  # 做菜/发布统计

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, onupdate=_utcnow
    )

    user: Mapped[User] = relationship(back_populates="privacy")


class FamilyMember(Base):
    """第二阶段功能：一桌饭同时满足一家人。"""

    __tablename__ = "family_members"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)

    name: Mapped[str] = mapped_column(String(64))
    relation: Mapped[str] = mapped_column(String(32), default="")
    diet_goal: Mapped[str] = mapped_column(String(32), default="正常饮食")
    taste: Mapped[str] = mapped_column(String(32), default="正常")
    disliked_foods: Mapped[list] = mapped_column(JSON, default=list)
    allergies: Mapped[list] = mapped_column(JSON, default=list)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)

    user: Mapped[User] = relationship(back_populates="family_members")
