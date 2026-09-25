"""大模型调用封装。

统一走阿里云百炼（DashScope）的 OpenAI 兼容接口：
    https://dashscope.aliyuncs.com/compatible-mode/v1

未配置 DASHSCOPE_API_KEY 时自动降级为 MOCK 模式，返回内置假数据，
保证「拍照 → 识别 → 确认 → 菜谱 → 采购」整条链路在无密钥时也能跑通。
"""
from __future__ import annotations

import base64
import json
import logging
import re
from typing import Any

from app.core.config import settings

logger = logging.getLogger(__name__)

_JSON_FENCE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL)


class AIUnavailable(RuntimeError):
    """AI 服务不可用（无密钥、超时、上游报错）。"""


def _extract_json(text: str) -> dict[str, Any]:
    """从模型回复里抠出 JSON。容忍 ```json 围栏和前后废话。"""
    if not text or not text.strip():
        raise AIUnavailable("模型返回为空")

    candidate = text.strip()
    fence = _JSON_FENCE.search(candidate)
    if fence:
        candidate = fence.group(1).strip()

    try:
        parsed = json.loads(candidate)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    # 退一步：截取第一个 { 到最后一个 }
    start, end = candidate.find("{"), candidate.rfind("}")
    if start != -1 and end > start:
        try:
            parsed = json.loads(candidate[start : end + 1])
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError as exc:
            raise AIUnavailable(f"模型返回的不是合法 JSON: {exc}") from exc

    raise AIUnavailable("无法从模型返回中解析出 JSON")


def image_to_data_url(raw: bytes, mime: str = "image/jpeg") -> str:
    return f"data:{mime};base64,{base64.b64encode(raw).decode('ascii')}"


class AIClient:
    """薄封装。每次调用都新建 client，避免长连接在容器里失效。"""

    def __init__(self) -> None:
        self._client = None

    @property
    def enabled(self) -> bool:
        return settings.ai_enabled

    def _get_client(self):
        if not self.enabled:
            raise AIUnavailable("未配置 DASHSCOPE_API_KEY，AI 功能处于 MOCK 模式")
        if self._client is None:
            from openai import OpenAI

            self._client = OpenAI(
                api_key=settings.DASHSCOPE_API_KEY,
                base_url=settings.AI_BASE_URL,
                timeout=settings.AI_TIMEOUT_SECONDS,
            )
        return self._client

    def chat_json(
        self,
        *,
        system: str,
        user_text: str,
        image_data_url: str | None = None,
        model: str | None = None,
        temperature: float = 0.3,
    ) -> dict[str, Any]:
        """调用模型并强制返回 dict。失败抛 AIUnavailable。"""
        client = self._get_client()
        chosen_model = model or (settings.VISION_MODEL if image_data_url else settings.TEXT_MODEL)

        if image_data_url:
            content: Any = [
                {"type": "text", "text": user_text},
                {"type": "image_url", "image_url": {"url": image_data_url}},
            ]
        else:
            content = user_text

        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": content},
        ]

        kwargs: dict[str, Any] = {
            "model": chosen_model,
            "messages": messages,
            "temperature": temperature,
        }
        # qwen-vl 系列在兼容模式下不总是支持 response_format，失败时降级重试
        try:
            resp = client.chat.completions.create(
                **kwargs, response_format={"type": "json_object"}
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("json_object 模式失败，改用纯文本模式重试: %s", exc)
            try:
                resp = client.chat.completions.create(**kwargs)
            except Exception as exc2:  # noqa: BLE001
                raise AIUnavailable(f"模型调用失败: {exc2}") from exc2

        text = (resp.choices[0].message.content or "").strip()
        return _extract_json(text)


ai_client = AIClient()
