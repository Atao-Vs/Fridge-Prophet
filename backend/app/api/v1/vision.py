"""AI 1 接口：上传冰箱照片 → 返回结构化食材列表（不直接入库）。"""
import logging
import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.api.deps import CurrentUser
from app.core.config import settings
from app.schemas.inventory import ScanResult
from app.services.storage_service import upload_image
from app.services.vision_service import recognize_foods

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/vision", tags=["AI 识别"])

ALLOWED_MIME = {"image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic"}


@router.post("/scan", response_model=ScanResult, summary="扫描冰箱照片，识别食材")
async def scan_fridge(
    user: CurrentUser,
    file: UploadFile = File(..., description="冰箱或食材照片"),
) -> ScanResult:
    mime = (file.content_type or "image/jpeg").lower()
    if mime not in ALLOWED_MIME:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"不支持的图片格式：{mime}，请上传 JPG / PNG / WEBP",
        )

    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="上传的文件是空的")
    if len(raw) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"图片不能超过 {settings.MAX_UPLOAD_MB}MB，请在客户端压缩后再上传",
        )

    scan_id = uuid.uuid4().hex[:16]

    # 落盘留档，便于后续用「用户修正结果」积累训练数据（策划书第二十七节）
    # 优先存 Supabase Storage，未配置则存本地磁盘
    suffix = Path(file.filename or "").suffix or ".jpg"
    image_url = upload_image(raw, f"{user.id}/{scan_id}{suffix}", mime)

    result = recognize_foods(raw, mime, scan_id)
    result.image_url = image_url
    return result


@router.get("/status", summary="查询 AI 与存储配置（客户端可据此显示提示）")
def ai_status(user: CurrentUser) -> dict:
    return {
        "ai_enabled": settings.ai_enabled,
        "vision_model": settings.VISION_MODEL if settings.ai_enabled else "mock",
        "text_model": settings.TEXT_MODEL if settings.ai_enabled else "mock",
        "storage": "supabase" if settings.supabase_storage_enabled else "local",
        "note": (
            "已接入通义千问"
            if settings.ai_enabled
            else "未配置 DASHSCOPE_API_KEY，当前返回演示数据"
        ),
    }
