"""业务服务层：AI 调用、规则计算、库存操作。"""
from app.services.ai_client import AIUnavailable, ai_client
from app.services.recipe_service import generate_recipes
from app.services.shopping_service import compute_missing, estimate_price
from app.services.vision_service import recognize_foods

__all__ = [
    "ai_client",
    "AIUnavailable",
    "recognize_foods",
    "generate_recipes",
    "compute_missing",
    "estimate_price",
]
