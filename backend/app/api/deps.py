"""FastAPI 依赖：当前用户、数据库会话、分页。"""
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.core.security import decode_access_token
from app.db.session import get_db
from app.models.user import PrivacySetting, User, UserPreference

bearer_scheme = HTTPBearer(auto_error=False)

DbSession = Annotated[Session, Depends(get_db)]


def get_current_user(
    db: DbSession,
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)] = None,
) -> User:
    unauthorized = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="未登录或登录已过期",
        headers={"WWW-Authenticate": "Bearer"},
    )
    if creds is None or not creds.credentials:
        raise unauthorized

    payload = decode_access_token(creds.credentials)
    if not payload or not payload.get("sub"):
        raise unauthorized

    try:
        user_id = int(payload["sub"])
    except (TypeError, ValueError):
        raise unauthorized from None

    user = db.get(User, user_id)
    if user is None or not user.is_active:
        raise unauthorized
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


def get_or_create_preference(db: Session, user_id: int) -> UserPreference:
    pref = db.query(UserPreference).filter(UserPreference.user_id == user_id).one_or_none()
    if pref is None:
        pref = UserPreference(user_id=user_id)
        db.add(pref)
        db.commit()
        db.refresh(pref)
    return pref


def get_or_create_privacy(db: Session, user_id: int) -> PrivacySetting:
    """取公开性开关，没有就按**全关**建一个。

    做成「用时创建」而不是注册时创建：老账号（这个功能上线之前注册的）
    数据库里没有对应行，如果只依赖注册流程写入，老用户一进广场就会 500。
    全关是安全默认值 —— 见 models/user.py 里 PrivacySetting 的说明。
    """
    row = db.query(PrivacySetting).filter(PrivacySetting.user_id == user_id).one_or_none()
    if row is None:
        row = PrivacySetting(user_id=user_id)
        db.add(row)
        db.commit()
        db.refresh(row)
    return row
