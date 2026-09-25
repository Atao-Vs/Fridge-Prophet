"""AI 1：视觉识别。冰箱照片 → 结构化食材列表。"""
from __future__ import annotations

import logging

from app.core.config import settings
from app.schemas.inventory import RecognizedFood, ScanResult
from app.services.ai_client import AIUnavailable, ai_client

logger = logging.getLogger(__name__)

VISION_SYSTEM_PROMPT = """你是一个专业的食材识别助手，服务于中国的家庭厨房场景。
你的任务是识别照片中可见的食材，并估计数量。

严格规则：
1. 只输出 JSON，不要输出任何解释文字、Markdown 代码块或额外说明。
2. 只识别你真正看到的食材。看不清就不输出，绝对不允许编造。
3. 数量必须是基于画面证据的保守估计。无法判断数量时填 1。
4. confidence 表示你的把握程度（0 到 1 之间）：
   - 0.9 以上：非常确定是什么，也能看清大致数量
   - 0.7 到 0.9：能确定种类，但数量是估算
   - 0.5 到 0.7：只能猜出大致类别
   - 低于 0.5：画面模糊或只有局部，请降低 confidence 而不是硬猜
5. 名称用中国大陆日常叫法（如「西红柿」而不是「番茄」以外的生僻说法、「鸡胸肉」「鸡蛋」「豆腐」「牛奶」）。
6. storage_location 只能是「冷藏」「冷冻」「常温」三者之一，根据食材在冰箱中的位置判断。
7. shelf_life_days 是在该储存方式下的建议可食用天数（整数）。

输出 JSON 结构（必须严格遵守）：
{
  "foods": [
    {
      "name": "鸡蛋",
      "quantity": 6,
      "unit": "个",
      "confidence": 0.95,
      "category": "蛋类",
      "storage_location": "冷藏",
      "shelf_life_days": 30
    }
  ]
}

如果照片里没有任何可识别的食材，返回 {"foods": []}。"""

VISION_USER_PROMPT = """请识别这张冰箱或食材照片里所有可见的食材。

要求：
- 逐项列出食材名称、估计数量、单位、识别置信度
- 同时给出建议的储存位置和保质天数
- 不确定的食材降低 confidence，不要编造
- 只返回 JSON"""

# 无 API Key 时的兜底数据，用于跑通全链路
MOCK_FOODS: list[dict] = [
    {"name": "鸡蛋", "quantity": 6, "unit": "个", "confidence": 0.96,
     "category": "蛋类", "storage_location": "冷藏", "shelf_life_days": 30},
    {"name": "西红柿", "quantity": 3, "unit": "个", "confidence": 0.91,
     "category": "蔬菜", "storage_location": "冷藏", "shelf_life_days": 7},
    {"name": "豆腐", "quantity": 1, "unit": "盒", "confidence": 0.88,
     "category": "豆制品", "storage_location": "冷藏", "shelf_life_days": 3},
    {"name": "鸡胸肉", "quantity": 450, "unit": "g", "confidence": 0.84,
     "category": "肉类", "storage_location": "冷冻", "shelf_life_days": 30},
    {"name": "牛奶", "quantity": 1, "unit": "盒", "confidence": 0.93,
     "category": "乳制品", "storage_location": "冷藏", "shelf_life_days": 7},
]

# 置信度低于这个值的结果，前端要强提示用户确认
LOW_CONFIDENCE_THRESHOLD = 0.75


def _mock_scan(scan_id: str, reason: str) -> ScanResult:
    foods = [RecognizedFood(**f) for f in MOCK_FOODS]
    return ScanResult(
        scan_id=scan_id,
        foods=foods,
        needs_review=True,
        model="mock",
        message=f"当前为演示数据（{reason}）。配置 DASHSCOPE_API_KEY 后将使用 {settings.VISION_MODEL} 真实识别。",
    )


def recognize_foods(image_bytes: bytes, mime: str, scan_id: str) -> ScanResult:
    """识别照片中的食材。AI 不可用时降级为 MOCK，不抛异常打断演示。"""
    if not ai_client.enabled:
        return _mock_scan(scan_id, "未配置 AI 密钥")

    from app.services.ai_client import image_to_data_url

    try:
        data = ai_client.chat_json(
            system=VISION_SYSTEM_PROMPT,
            user_text=VISION_USER_PROMPT,
            image_data_url=image_to_data_url(image_bytes, mime),
            model=settings.VISION_MODEL,
            temperature=0.1,
        )
    except AIUnavailable as exc:
        logger.error("视觉识别失败: %s", exc)
        return _mock_scan(scan_id, f"AI 调用失败：{exc}")

    raw_foods = data.get("foods") or []
    foods: list[RecognizedFood] = []
    for item in raw_foods:
        if not isinstance(item, dict):
            continue
        try:
            foods.append(RecognizedFood(**item))
        except Exception:  # noqa: BLE001  单条脏数据不影响整体
            logger.warning("跳过无法解析的识别结果: %s", item)

    needs_review = (not foods) or any(
        f.confidence < LOW_CONFIDENCE_THRESHOLD for f in foods
    )

    return ScanResult(
        scan_id=scan_id,
        foods=foods,
        needs_review=needs_review,
        model=settings.VISION_MODEL,
        message="识别完成，请核对后加入冰箱。" if foods else "没有识别到食材，请换一张更清晰的照片。",
    )
