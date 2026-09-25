"""注册 / 登录 / 当前用户。"""
from fastapi import APIRouter, HTTPException, status
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession, get_or_create_preference
from app.core.config import settings
from app.core.security import create_access_token, hash_password, verify_password
from app.models.user import HealthPreference, User
from app.schemas.auth import LoginRequest, RegisterRequest, TokenResponse, UserBrief
from app.schemas.user import UserOut

router = APIRouter(prefix="/auth", tags=["鉴权"])


def _token_for(user: User, db: DbSession) -> TokenResponse:
    pref = get_or_create_preference(db, user.id)
    token = create_access_token(user.id)
    return TokenResponse(
        access_token=token,
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user=UserBrief(
            id=user.id, email=user.email, nickname=user.nickname, onboarded=pref.onboarded
        ),
    )


@router.post("/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED,
             summary="注册并直接返回登录令牌")
def register(payload: RegisterRequest, db: DbSession) -> TokenResponse:
    exists = db.query(User).filter(User.email == payload.email).one_or_none()
    if exists:
        raise HTTPException(status_code=409, detail="该邮箱已注册")

    user = User(
        email=payload.email,
        nickname=payload.nickname or payload.email.split("@")[0],
        hashed_password=hash_password(payload.password),
    )
    db.add(user)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="该邮箱已注册") from None

    # 顺手建好画像记录，避免后续到处判空
    db.add(HealthPreference(user_id=user.id))
    db.commit()
    db.refresh(user)
    return _token_for(user, db)


@router.post("/login", response_model=TokenResponse, summary="登录")
def login(payload: LoginRequest, db: DbSession) -> TokenResponse:
    user = db.query(User).filter(User.email == payload.email).one_or_none()
    if user is None or not verify_password(payload.password, user.hashed_password):
        raise HTTPException(status_code=401, detail="邮箱或密码不正确")
    if not user.is_active:
        raise HTTPException(status_code=403, detail="账号已被停用")
    return _token_for(user, db)


@router.get("/me", response_model=UserOut, summary="获取当前登录用户")
def me(user: CurrentUser) -> User:
    return user
