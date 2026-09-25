"""图片存储：优先 Supabase Storage，未配置时退回本地磁盘。

为什么要有这一层：比赛演示期间如果服务器重装或者换机器，
存在本地磁盘的照片就全没了。Supabase Storage 免费 1GB，且自带 CDN。
"""
from __future__ import annotations

import logging

import httpx

from app.core.config import settings

logger = logging.getLogger(__name__)


def _public_url(path: str) -> str:
    base = settings.SUPABASE_URL.rstrip("/")
    return f"{base}/storage/v1/object/public/{settings.SUPABASE_BUCKET}/{path}"


def upload_image(raw: bytes, path: str, content_type: str = "image/jpeg") -> str | None:
    """上传图片，返回可公开访问的 URL。失败返回 None（不影响识别流程）。"""
    if settings.supabase_storage_enabled:
        url = _upload_to_supabase(raw, path, content_type)
        if url:
            return url
        logger.warning("Supabase 上传失败，改用本地磁盘")

    return _save_local(raw, path)


def _upload_to_supabase(raw: bytes, path: str, content_type: str) -> str | None:
    base = settings.SUPABASE_URL.rstrip("/")
    endpoint = f"{base}/storage/v1/object/{settings.SUPABASE_BUCKET}/{path}"
    headers = {
        "Authorization": f"Bearer {settings.SUPABASE_SERVICE_KEY}",
        "Content-Type": content_type,
        "x-upsert": "true",
    }
    try:
        with httpx.Client(timeout=20.0) as client:
            resp = client.post(endpoint, content=raw, headers=headers)
        if resp.status_code in (200, 201):
            return _public_url(path)
        logger.error("Supabase Storage 返回 %s: %s", resp.status_code, resp.text[:200])
    except httpx.HTTPError as exc:
        logger.error("Supabase Storage 请求异常: %s", exc)
    return None


def _save_local(raw: bytes, path: str) -> str | None:
    try:
        target = settings.UPLOAD_DIR / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        return f"/uploads/{path}"
    except OSError as exc:  # noqa: BLE001
        logger.warning("本地落盘失败（不影响识别）: %s", exc)
        return None


def ensure_bucket() -> bool:
    """启动时调用：检查 bucket 是否存在，不存在就建一个公开 bucket。"""
    if not settings.supabase_storage_enabled:
        return False

    base = settings.SUPABASE_URL.rstrip("/")
    headers = {
        "Authorization": f"Bearer {settings.SUPABASE_SERVICE_KEY}",
        "Content-Type": "application/json",
    }
    try:
        with httpx.Client(timeout=15.0) as client:
            check = client.get(f"{base}/storage/v1/bucket/{settings.SUPABASE_BUCKET}",
                               headers=headers)
            if check.status_code == 200:
                logger.info("Supabase bucket 已存在: %s", settings.SUPABASE_BUCKET)
                return True

            created = client.post(
                f"{base}/storage/v1/bucket",
                headers=headers,
                json={"id": settings.SUPABASE_BUCKET, "name": settings.SUPABASE_BUCKET,
                      "public": True},
            )
        if created.status_code in (200, 201):
            logger.info("已创建 Supabase bucket: %s", settings.SUPABASE_BUCKET)
            return True
        logger.error("创建 bucket 失败 %s: %s", created.status_code, created.text[:200])
    except httpx.HTTPError as exc:
        logger.error("连接 Supabase 失败: %s", exc)
    return False
