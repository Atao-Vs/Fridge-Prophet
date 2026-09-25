"""广场的排序与装配逻辑。

## 三种排序到底差在哪

这不是「换个 ORDER BY 字段」的三种排序，而是三种**不同的推荐意图**，
所以公式必须不一样，否则三个 Tab 点起来感觉是同一个东西：

| 排序 | 意图 | 后果 |
|---|---|---|
| 最新 `latest` | 纯时间序 | 没人互动的新帖也能被看到；刷屏时全是同一个人 |
| 热门 `hot` | 已经火起来的 | 老帖赞多会长期霸榜 → 所以必须带时间衰减 |
| 综合 `composite` | 兼顾热度与新鲜度，并给关注的人加权 | 类似 X 的「For you」；新帖有机会冒头 |

`hot` 和 `composite` 都用 `engagement / (age_hours + k) ** p` 这个形式，
差别只在衰减指数：`hot` 用 1.8（衰减快，强者恒强），
`composite` 用 1.2（衰减慢，新帖更容易被看到）。

## 为什么在 Python 里算而不是写进 SQL

时间衰减要用「当前时间 − 发布时间」，SQLite 是 `julianday()`、
PostgreSQL 是 `EXTRACT(EPOCH FROM ...)`，两边语法完全不同。
写两套 SQL 意味着以后改公式要改两个地方、还只有一边会被测到。

所以在 Python 里算，代价是要把候选集读进内存。为此加了 `_POOL_LIMIT = 500` 的上限：
只取最近 500 条参与排序。**当前规模（一个比赛演示应用）远达不到这个量级**；
真到需要分页几万条的时候，正确做法是在表上冗余一个 `hot_score` 列、
由写路径更新，然后纯 SQL 排序 —— 而不是把池子调大。
"""
from __future__ import annotations

import math
from datetime import datetime, timezone

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models.social import Follow, Post, PostLike
from app.models.user import User
from app.schemas.social import SORT_LABELS, PostOut, UserBrief

# 参与排序的候选池上限，见模块 docstring
_POOL_LIMIT = 500

# 互动量权重。评论 > 分享 > 点赞：留下评论的成本最高，说明内容真的引发了反应；
# 点赞是三个里最廉价的，所以权重最低。
_W_LIKE = 3.0
_W_COMMENT = 4.0
_W_SHARE = 5.0

# 时间衰减常数。+2 是为了让「刚发出来」的帖子不会因为 age 接近 0 而除零爆炸，
# 同时给新帖一个平滑的起步值。
_DECAY_OFFSET = 2.0
_HOT_EXP = 1.8
_COMPOSITE_EXP = 1.2

# 综合排序里，作者被我关注时的加权
_FOLLOW_BOOST = 1.6


def now() -> datetime:
    """统一的「现在」。

    抽出来是为了让测试能传一个固定时刻进去 —— 时间衰减公式如果直接用
    `datetime.now()`，测试就会随着执行时间漂移，今天过、明天挂。
    """
    return datetime.now(timezone.utc)


def engagement(like_count: int, comment_count: int, share_count: int) -> float:
    """把三种互动折成一个数。"""
    return _W_LIKE * like_count + _W_COMMENT * comment_count + _W_SHARE * share_count


def _age_hours(created_at: datetime, now: datetime) -> float:
    """发布至今的小时数。

    ⚠️ 数据库里存的是带时区的时间，但从 SQLite 读出来可能变成 naive
    （SQLite 没有原生时区类型，SQLAlchemy 的 DateTime(timezone=True) 在它上面
    存进去再取出来 tzinfo 就没了）。naive 和 aware 相减会抛 TypeError，
    所以这里显式补上 UTC —— 不能靠「读出来一定有 tzinfo」这个假设。
    """
    if created_at.tzinfo is None:
        created_at = created_at.replace(tzinfo=timezone.utc)
    delta = (now - created_at).total_seconds() / 3600.0
    # 服务器时钟比数据库略慢时可能算出极小的负数，钳到 0 避免衰减公式出现负底数
    return max(delta, 0.0)


def score_of(post: Post, sort: str, now: datetime, followed_ids: set[int]) -> float:
    """给一条动态打分。分数只在同一次请求内比较，不落库。"""
    age = _age_hours(post.created_at, now)

    if sort == "latest":
        # 时间序用不到分数，这里返回时间戳本身，排序方向在下面统一处理
        return post.created_at.timestamp()

    eng = engagement(post.like_count, post.comment_count, post.share_count)

    if sort == "hot":
        return eng / math.pow(age + _DECAY_OFFSET, _HOT_EXP)

    # composite：衰减更平缓，再给关注的作者加权
    base = (eng + 2.0) / math.pow(age + _DECAY_OFFSET, _COMPOSITE_EXP)
    if post.user_id in followed_ids:
        base *= _FOLLOW_BOOST
    return base


def rank(posts: list[Post], sort: str, now: datetime, followed_ids: set[int]) -> list[Post]:
    """按指定排序返回**新列表**，不改动入参顺序。"""
    key = lambda p: (score_of(p, sort, now, followed_ids), p.id)  # noqa: E731
    # 副键用 id 而不是 created_at：同一秒发的两条帖子必须有稳定次序，
    # 否则翻页时会出现「第 1 页见过、第 2 页又出现」的重复。
    return sorted(posts, key=key, reverse=True)


def fetch_followed_ids(db: Session, viewer_id: int) -> set[int]:
    rows = db.query(Follow.followee_id).filter(Follow.follower_id == viewer_id).all()
    return {r[0] for r in rows}


def fetch_liked_ids(db: Session, viewer_id: int, post_ids: list[int]) -> set[int]:
    """一次性查出「我赞过这堆帖子里的哪些」。

    刻意不做成「每条帖子查一次」：列表一屏 20 条就是 20 次查询。
    """
    if not post_ids:
        return set()
    rows = (
        db.query(PostLike.post_id)
        .filter(PostLike.user_id == viewer_id, PostLike.post_id.in_(post_ids))
        .all()
    )
    return {r[0] for r in rows}


def load_pool(db: Session, only_following_of: int | None = None) -> list[Post]:
    """取候选池：最近 _POOL_LIMIT 条。`only_following_of` 非空时只取该用户关注的人。"""
    query = db.query(Post)
    if only_following_of is not None:
        sub = db.query(Follow.followee_id).filter(Follow.follower_id == only_following_of)
        # 关注流里也包含自己的帖子 —— 自己在自己首页看不到自己发的东西会很怪
        query = query.filter(
            (Post.user_id.in_(sub)) | (Post.user_id == only_following_of)
        )
    return query.order_by(Post.created_at.desc(), Post.id.desc()).limit(_POOL_LIMIT).all()


def to_out(post: Post, viewer_id: int, liked_ids: set[int]) -> PostOut:
    """把 ORM 行转成返回体。"""
    author = post.author
    return PostOut(
        id=post.id,
        author=UserBrief(
            id=author.id,
            nickname=author.nickname or f"用户{author.id}",
            avatar_url=author.avatar_url,
            bio=author.bio,
        ),
        content=post.content or "",
        image_url=post.image_url,
        recipe_id=post.recipe_id,
        recipe_name=post.recipe_name,
        steps=list(post.steps or []),
        tags=list(post.tags or []),
        like_count=post.like_count,
        comment_count=post.comment_count,
        share_count=post.share_count,
        liked_by_me=post.id in liked_ids,
        is_mine=post.user_id == viewer_id,
        created_at=post.created_at,
    )


def sort_options() -> list[dict]:
    return [{"value": k, "label": v} for k, v in SORT_LABELS.items()]


def follower_count(db: Session, user_id: int) -> int:
    return db.query(func.count(Follow.id)).filter(Follow.followee_id == user_id).scalar() or 0


def following_count(db: Session, user_id: int) -> int:
    return db.query(func.count(Follow.id)).filter(Follow.follower_id == user_id).scalar() or 0


def post_count(db: Session, user_id: int) -> int:
    return db.query(func.count(Post.id)).filter(Post.user_id == user_id).scalar() or 0


def is_following(db: Session, follower_id: int, followee_id: int) -> bool:
    return (
        db.query(Follow.id)
        .filter(Follow.follower_id == follower_id, Follow.followee_id == followee_id)
        .first()
        is not None
    )


def search_users(db: Session, keyword: str, limit: int = 20) -> list[User]:
    """按昵称搜人。用于「关注」页找人。

    只搜昵称不搜邮箱：邮箱是登录凭据，允许用它检索等于提供了一个
    「输入邮箱 → 确认某人是否注册」的接口。
    """
    kw = f"%{keyword.strip()}%"
    return (
        db.query(User)
        .filter(User.is_active.is_(True), User.nickname.like(kw))
        .order_by(User.id.asc())
        .limit(limit)
        .all()
    )
