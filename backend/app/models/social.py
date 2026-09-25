"""广场（社区）模块：动态、点赞、评论、关注。

## 为什么菜名和做法要冗余存一份

`Post.recipe_id` 指向用户菜谱库里的某道菜，但 `recipe_name` / `steps` 是**另外存的一份副本**。
原因：动态是「某个时刻我做了这道菜」的记录，它应该是一份快照。
如果只存 id，那么用户后来把菜谱删了、或者菜谱被重新生成覆盖了，
广场上这条动态就会跟着变形甚至变成空白——那不是用户发的内容了。
所以菜谱是「可选关联」，动态本身必须自包含。

## 为什么计数字段冗余存

`like_count` / `comment_count` / `share_count` 都是冗余列，真值在各自的表里。
理由：广场列表每屏要渲染十几条动态，每条都 `COUNT(*)` 一次点赞表，
在 PostgreSQL 上就是十几次子查询。冗余列让列表查询退化成一次普通 SELECT。
代价是删点赞时要记得同步减，这个风险由 `social_service` 统一收口，不允许别处直接改。
"""
from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.session import Base
from app.models.user import User


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Post(Base):
    """一条广场动态。可以只是一句话，也可以带成品图 + 做法。"""

    __tablename__ = "posts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )

    content: Mapped[str] = mapped_column(Text, default="")
    image_url: Mapped[str | None] = mapped_column(String(512), nullable=True)

    # 可选关联到自己菜谱库里的某道菜
    recipe_id: Mapped[int | None] = mapped_column(
        ForeignKey("recipes.id", ondelete="SET NULL"), nullable=True, index=True
    )
    # 快照字段，不跟随 recipes 表变化
    recipe_name: Mapped[str | None] = mapped_column(String(128), nullable=True)
    steps: Mapped[list] = mapped_column(JSON, default=list)
    tags: Mapped[list] = mapped_column(JSON, default=list)

    # 冗余计数，见模块 docstring
    like_count: Mapped[int] = mapped_column(Integer, default=0)
    comment_count: Mapped[int] = mapped_column(Integer, default=0)
    share_count: Mapped[int] = mapped_column(Integer, default=0)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, index=True
    )

    author: Mapped[User] = relationship(lazy="joined")


class PostLike(Base):
    """点赞。靠唯一约束保证一个人对一条动态只能赞一次。"""

    __tablename__ = "post_likes"
    __table_args__ = (UniqueConstraint("post_id", "user_id", name="uq_post_like"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    post_id: Mapped[int] = mapped_column(
        ForeignKey("posts.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class PostComment(Base):
    __tablename__ = "post_comments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    post_id: Mapped[int] = mapped_column(
        ForeignKey("posts.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    content: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, index=True
    )

    author: Mapped[User] = relationship(lazy="joined")


class Follow(Base):
    """关注关系。`follower` 关注了 `followee`。

    注意方向：查「我关注了谁」用 follower_id，查「谁关注了我」用 followee_id，
    两个方向都要建索引，否则粉丝数一多就全表扫。
    """

    __tablename__ = "follows"
    __table_args__ = (
        UniqueConstraint("follower_id", "followee_id", name="uq_follow"),
        Index("ix_follow_followee", "followee_id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    follower_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    followee_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
