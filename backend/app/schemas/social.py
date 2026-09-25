"""广场（社区）模块的出入参模型。"""
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

# 三种排序。客户端传字符串，用 Literal 收窄 —— 传错值会得到 422 而不是静默按默认排。
FeedSort = Literal["latest", "hot", "composite"]

SORT_LABELS: dict[str, str] = {
    "latest": "最新",
    "hot": "热门",
    "composite": "综合",
}


class UserBrief(BaseModel):
    """广场里露出的作者信息。**只含公开字段**。

    刻意不含 email：广场是所有人可见的，把邮箱塞进每条动态的返回体里
    等于把用户的登录标识群发出去。需要邮箱的地方只有 /users/profile（本人）。
    """

    model_config = ConfigDict(from_attributes=True)

    id: int
    nickname: str = ""
    avatar_url: str | None = None
    bio: str | None = None


class PostCreate(BaseModel):
    content: str = Field(default="", max_length=1000)
    image_url: str | None = Field(default=None, max_length=512)
    recipe_id: int | None = None
    recipe_name: str | None = Field(default=None, max_length=128)
    steps: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list, max_length=8)


class PostOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    author: UserBrief
    content: str = ""
    image_url: str | None = None
    recipe_id: int | None = None
    recipe_name: str | None = None
    steps: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    like_count: int = 0
    comment_count: int = 0
    share_count: int = 0
    # 这两个是**针对请求者**算的，不是动态自身的属性
    liked_by_me: bool = False
    is_mine: bool = False
    created_at: datetime


class FeedOut(BaseModel):
    items: list[PostOut] = Field(default_factory=list)
    total: int = 0
    offset: int = 0
    limit: int = 20
    has_more: bool = False
    sort: str = "composite"
    sort_label: str = "综合"
    # 供客户端渲染排序切换器，避免把选项写死在 App 里
    sort_options: list[dict] = Field(default_factory=list)


class CommentCreate(BaseModel):
    content: str = Field(min_length=1, max_length=500)


class CommentOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    author: UserBrief
    content: str
    created_at: datetime
    is_mine: bool = False


class CommentListOut(BaseModel):
    items: list[CommentOut] = Field(default_factory=list)
    total: int = 0


class LikeResultOut(BaseModel):
    post_id: int
    liked: bool
    like_count: int


class ShareResultOut(BaseModel):
    post_id: int
    share_count: int
    # 客户端拿去调系统分享面板；服务端不主动外发
    share_text: str = ""


class FollowResultOut(BaseModel):
    user_id: int
    following: bool
    follower_count: int


class FollowUserOut(BaseModel):
    """关注列表 / 粉丝列表里的一行。"""

    user: UserBrief
    followed_by_me: bool = False
    followed_at: datetime | None = None
    post_count: int = 0


class PublicProfileOut(BaseModel):
    """别人在广场点开你主页看到的内容。

    ## 为什么用「字段可为空」而不是「字段直接不返回」

    被隐藏的区块返回 `None`，客户端据此显示「TA 未公开」的灰条。
    如果直接不返回这个 key，客户端无法区分「没公开」和「没填」，
    只能显示空白，用户会以为是加载失败。
    """

    user: UserBrief
    post_count: int = 0
    follower_count: int = 0
    following_count: int = 0
    followed_by_me: bool = False
    is_me: bool = False

    # 以下区块受 PrivacySetting 控制，未公开时为 None
    preference: dict | None = None
    health: dict | None = None
    family_members: list[dict] | None = None
    cooked_count: int | None = None

    # 各区块的公开状态，客户端用来渲染「未公开」提示
    visibility: dict = Field(default_factory=dict)


class PrivacySettingIn(BaseModel):
    share_preference: bool = False
    share_health: bool = False
    share_body: bool = False
    share_family: bool = False
    share_stats: bool = False


class PrivacySettingOut(PrivacySettingIn):
    model_config = ConfigDict(from_attributes=True)

    updated_at: datetime | None = None


class ImageUploadOut(BaseModel):
    url: str
