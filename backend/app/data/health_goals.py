"""健康管理目标的字段名 → 中文标签。

## 为什么放后端

两个地方要用同一份：用户自己编辑健康偏好（`/users/health`）时客户端要渲染标签，
广场里别人看你的主页（`/social/users/{id}`）时也要渲染。

如果客户端各写一份，某天加了一个新目标（比如「低碘」），
后端字段加了、App 忘了加标签，广场上就会显示成一个空标签或者原始字段名。
放后端意味着**加一个目标只改一个文件**。

顺序即展示顺序：`dict` 在 Python 3.7+ 保序，客户端直接按返回顺序渲染即可。
"""
from __future__ import annotations

HEALTH_GOAL_LABELS: dict[str, str] = {
    "low_carb": "低碳水",
    "low_sodium": "低盐",
    "low_fat": "低脂",
    "high_protein": "高蛋白",
    "high_fiber": "高纤维",
    "vegetarian": "素食",
    "low_sugar": "控糖",
    "high_calcium": "补钙",
    "high_iron": "补铁",
    "low_purine": "低嘌呤",
    "no_raw_food": "避免生食",
}

# 这些目标都是「一般性营养取向」，不涉及疾病诊断或治疗。
# 广场展示时必须带上这句话，避免用户把它当成医疗建议转述给别人。
HEALTH_DISCLAIMER = (
    "以上仅为一般性营养取向，不构成医疗建议，也不能替代医生或注册营养师的指导。"
)
