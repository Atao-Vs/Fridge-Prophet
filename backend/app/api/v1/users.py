"""用户画像：饮食偏好、健康管理、家庭成员。"""
import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.api.deps import (
    CurrentUser,
    DbSession,
    get_or_create_preference,
    get_or_create_privacy,
)
from app.data.food_tips import COMMON_ALLERGENS, COMMON_DISLIKED
from app.data.health_goals import HEALTH_DISCLAIMER, HEALTH_GOAL_LABELS
from app.models.user import FamilyMember, HealthPreference
from app.schemas.social import PrivacySettingIn, PrivacySettingOut
from app.schemas.user import (
    FamilyMemberIn,
    FamilyMemberOut,
    HealthPreferenceIn,
    HealthPreferenceOut,
    ProfileOut,
    ProfileUpdate,
    UserOut,
    UserPreferenceIn,
    UserPreferenceOut,
)
from app.services.storage_service import upload_image

router = APIRouter(prefix="/users", tags=["用户画像"])


@router.get("/options", summary="可选的过敏原与忌口清单（引导页 / 编辑画像用）")
def get_option_lists(user: CurrentUser) -> dict:
    """把「过敏原」和「忌口」的可选项集中返回，前端直接渲染成可点选的标签。

    为什么放后端而不是写死在 App 里：这两份清单会持续扩充，
    写死在客户端意味着每加一样都要发版。放后端改一次就生效。

    安全边界：过敏信息**只用于生成菜谱时规避**，不构成医学建议。
    所以返回值里带了 disclaimer 字段，客户端要原样展示。
    """
    return {
        "allergens": [
            {"group": group, "items": items} for group, items in COMMON_ALLERGENS.items()
        ],
        "disliked_foods": [
            {"group": group, "items": items} for group, items in COMMON_DISLIKED.items()
        ],
        "health_goals": [
            {"key": key, "label": label} for key, label in HEALTH_GOAL_LABELS.items()
        ],
        "health_disclaimer": HEALTH_DISCLAIMER,
        "disclaimer": (
            "过敏信息仅用于菜谱规避参考，不能替代医学诊断。"
            "如有严重过敏史，请以医生建议为准。"
        ),
    }


@router.get("/profile", response_model=ProfileOut, summary="获取完整画像（App 启动时调用）")
def get_profile(user: CurrentUser, db: DbSession) -> ProfileOut:
    pref = get_or_create_preference(db, user.id)
    health = (
        db.query(HealthPreference).filter(HealthPreference.user_id == user.id).one_or_none()
    )
    if health is None:
        health = HealthPreference(user_id=user.id)
        db.add(health)
        db.commit()
        db.refresh(health)

    members = (
        db.query(FamilyMember).filter(FamilyMember.user_id == user.id).all()
    )
    privacy = get_or_create_privacy(db, user.id)
    return ProfileOut(
        user=UserOut.model_validate(user),
        preference=UserPreferenceOut.model_validate(pref),
        health=HealthPreferenceOut.model_validate(health),
        family_members=[FamilyMemberOut.model_validate(m) for m in members],
        privacy=PrivacySettingOut.model_validate(privacy),
    )


@router.patch("/profile", response_model=UserOut, summary="修改昵称/头像/个性简介")
def update_profile(payload: ProfileUpdate, user: CurrentUser, db: DbSession) -> UserOut:
    if payload.nickname is not None:
        user.nickname = payload.nickname
    if payload.avatar_url is not None:
        user.avatar_url = payload.avatar_url
    # bio 传空字符串表示「清空简介」，所以判断条件是 is not None 而不是真值判断
    if payload.bio is not None:
        user.bio = payload.bio.strip() or None
    db.commit()
    db.refresh(user)
    return UserOut.model_validate(user)


AVATAR_MIME = {"image/jpeg", "image/jpg", "image/png", "image/webp"}
AVATAR_MAX_BYTES = 5 * 1024 * 1024
AVATAR_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


@router.post("/avatar", response_model=UserOut, summary="上传头像")
async def upload_avatar(
    user: CurrentUser,
    db: DbSession,
    file: UploadFile = File(..., description="头像图片，JPG / PNG / WEBP，不超过 5MB"),
) -> UserOut:
    """头像走和冰箱照片同一套存储层（Supabase Storage，未配置则落本地磁盘）。

    文件名用随机串而不是原名：避免用户上传 `../../x.png` 这类路径穿越，
    也避免同名覆盖和中文文件名在部分存储上的编码问题。
    """
    mime = (file.content_type or "").lower()
    if mime not in AVATAR_MIME:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"不支持的图片格式：{mime or '未知'}，请上传 JPG / PNG / WEBP",
        )

    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="上传的文件是空的")
    if len(raw) > AVATAR_MAX_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="头像不能超过 5MB，请在客户端压缩后再上传",
        )

    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in AVATAR_SUFFIXES:
        suffix = ".jpg"
    path = f"avatars/{user.id}/{uuid.uuid4().hex[:16]}{suffix}"

    url = upload_image(raw, path, mime)
    if url is None:
        raise HTTPException(status_code=500, detail="头像保存失败，请稍后重试")

    user.avatar_url = url
    db.commit()
    db.refresh(user)
    return UserOut.model_validate(user)


@router.put("/preference", response_model=UserPreferenceOut, summary="保存饮食偏好（新用户引导页）")
def save_preference(
    payload: UserPreferenceIn, user: CurrentUser, db: DbSession
) -> UserPreferenceOut:
    pref = get_or_create_preference(db, user.id)
    for field, value in payload.model_dump().items():
        setattr(pref, field, value)
    pref.onboarded = True
    db.commit()
    db.refresh(pref)
    return UserPreferenceOut.model_validate(pref)


@router.put("/health", response_model=HealthPreferenceOut, summary="保存健康管理设置")
def save_health(
    payload: HealthPreferenceIn, user: CurrentUser, db: DbSession
) -> HealthPreferenceOut:
    health = (
        db.query(HealthPreference).filter(HealthPreference.user_id == user.id).one_or_none()
    )
    if health is None:
        health = HealthPreference(user_id=user.id)
        db.add(health)

    for field, value in payload.model_dump().items():
        setattr(health, field, value)
    db.commit()
    db.refresh(health)
    return HealthPreferenceOut.model_validate(health)


@router.get("/privacy", response_model=PrivacySettingOut, summary="读取公开性设置")
def get_privacy(user: CurrentUser, db: DbSession) -> PrivacySettingOut:
    return PrivacySettingOut.model_validate(get_or_create_privacy(db, user.id))


@router.put("/privacy", response_model=PrivacySettingOut, summary="保存公开性设置")
def save_privacy(
    payload: PrivacySettingIn, user: CurrentUser, db: DbSession
) -> PrivacySettingOut:
    """控制「别人在广场点开我主页时能看到什么」。

    ⚠️ 只有**本人**能改自己的开关 —— 这里用的是 `CurrentUser`，
    请求体里没有 user_id，改的永远是当前登录者自己。
    如果哪天有人想加一个「管理员帮别人改」的接口，那必须另开一个路由并单独鉴权，
    绝不能在这个 payload 里加 user_id，否则任何人都能关掉别人的公开设置。
    """
    row = get_or_create_privacy(db, user.id)
    for field, value in payload.model_dump().items():
        setattr(row, field, value)
    db.commit()
    db.refresh(row)
    return PrivacySettingOut.model_validate(row)


@router.get("/family", response_model=list[FamilyMemberOut], summary="家庭成员列表")
def list_family(user: CurrentUser, db: DbSession) -> list[FamilyMemberOut]:
    members = db.query(FamilyMember).filter(FamilyMember.user_id == user.id).all()
    return [FamilyMemberOut.model_validate(m) for m in members]


@router.post("/family", response_model=FamilyMemberOut, status_code=status.HTTP_201_CREATED,
             summary="添加家庭成员")
def add_family(payload: FamilyMemberIn, user: CurrentUser, db: DbSession) -> FamilyMemberOut:
    member = FamilyMember(user_id=user.id, **payload.model_dump())
    db.add(member)
    db.commit()
    db.refresh(member)
    return FamilyMemberOut.model_validate(member)


@router.delete("/family/{member_id}", status_code=status.HTTP_204_NO_CONTENT,
               summary="删除家庭成员")
def delete_family(member_id: int, user: CurrentUser, db: DbSession) -> None:
    member = (
        db.query(FamilyMember)
        .filter(FamilyMember.id == member_id, FamilyMember.user_id == user.id)
        .one_or_none()
    )
    if member is None:
        raise HTTPException(status_code=404, detail="成员不存在")
    db.delete(member)
    db.commit()
