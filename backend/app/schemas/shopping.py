"""采购清单模型。"""
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field


class ShoppingItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    food_name: str
    quantity: float
    unit: str
    category: str = "其他"
    estimated_price: float | None = None
    checked: bool = False
    applied_to_inventory: bool = False


class ShoppingItemUpdate(BaseModel):
    quantity: float | None = Field(default=None, ge=0)
    unit: str | None = Field(default=None, max_length=16)
    checked: bool | None = None
    estimated_price: float | None = Field(default=None, ge=0)


class ShoppingListOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    title: str
    status: str
    source_recipes: str | None = None
    planned_date: date | None = None
    created_at: datetime
    items: list[ShoppingItemOut] = Field(default_factory=list)
    estimated_total: float = 0


class ShoppingBuildRequest(BaseModel):
    """从菜谱生成采购清单。recipe_ids 为空时表示按未来 N 天膳食计划计算。"""

    recipe_ids: list[int] = Field(default_factory=list)
    days: int = Field(default=1, ge=1, le=14)
    title: str | None = None
    planned_date: date | None = None


class ShoppingApplyRequest(BaseModel):
    """把已勾选的采购项写回冰箱库存。"""

    only_checked: bool = True
    shelf_life_days: int = Field(default=5, ge=1, le=365)
