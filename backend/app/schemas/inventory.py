"""冰箱库存模型。"""
from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.models.inventory import FRESHNESS_LEVELS, STORAGE_LOCATIONS


class InventoryBase(BaseModel):
    food_name: str = Field(min_length=1, max_length=64)
    category: str = "其他"
    quantity: float = Field(default=0, ge=0)
    unit: str = Field(default="个", max_length=16)
    purchase_date: date | None = None
    expiry_date: date | None = None
    storage_location: str = "冷藏"
    note: str | None = Field(default=None, max_length=255)

    @field_validator("storage_location")
    @classmethod
    def _check_location(cls, v: str) -> str:
        if v not in STORAGE_LOCATIONS:
            raise ValueError(f"storage_location 必须是 {STORAGE_LOCATIONS} 之一")
        return v

    @model_validator(mode="after")
    def _check_date_order(self) -> "InventoryBase":
        if (
            self.purchase_date
            and self.expiry_date
            and self.expiry_date < self.purchase_date
        ):
            raise ValueError("过期日期不能早于购买日期")
        return self


class InventoryCreate(InventoryBase):
    confidence: float | None = Field(default=None, ge=0, le=1)
    source: str = "manual"
    shelf_life_days: int | None = Field(
        default=None,
        ge=0,
        le=3650,
        description="只写了保质期天数、没写具体过期日期时用这个推算；两者都留空则按食材名取默认值",
    )


class InventoryUpdate(BaseModel):
    food_name: str | None = Field(default=None, min_length=1, max_length=64)
    category: str | None = None
    quantity: float | None = Field(default=None, ge=0)
    unit: str | None = Field(default=None, max_length=16)
    purchase_date: date | None = None
    expiry_date: date | None = None
    storage_location: str | None = None
    freshness: str | None = None
    note: str | None = Field(default=None, max_length=255)

    @field_validator("storage_location")
    @classmethod
    def _check_location(cls, v: str | None) -> str | None:
        if v is not None and v not in STORAGE_LOCATIONS:
            raise ValueError(f"storage_location 必须是 {STORAGE_LOCATIONS} 之一")
        return v

    @field_validator("freshness")
    @classmethod
    def _check_freshness(cls, v: str | None) -> str | None:
        if v is not None and v not in FRESHNESS_LEVELS:
            raise ValueError(f"freshness 必须是 {FRESHNESS_LEVELS} 之一")
        return v


class InventoryOut(InventoryBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    freshness: str
    confidence: float | None = None
    source: str = "manual"
    days_left: int | None = None
    created_at: datetime
    updated_at: datetime

    # 食材配图，**相对路径**（如 /static/ingredients/egg.jpg），客户端自己拼域名。
    # 匹配不上时为 None，此时客户端显示「食材名首字 + 哈希色块」的占位 ——
    # 不给通用图，一列里出现五张一样的图比没图更像坏了。
    #
    # ⚠️ 这个字段**不在 models 里**，是 `api/v1/inventory.py::_to_out` 按 food_name
    # 算出来的。所以新增返回 InventoryOut 的接口时，必须走 `_to_out`，
    # 直接 `InventoryOut.model_validate(row)` 会得到 image_url=None（界面上就是一片占位色块）。
    image_url: str | None = None


class RecognizedFood(BaseModel):
    """AI 视觉识别输出的单个食材。

    前 7 个字段由 AI 产出；后 3 个是**用户确认页可覆盖**的字段：
    AI 只能猜出「大概能放几天」，但用户手里的牛奶可能已经买了 3 天，
    所以用户指定的购买日期/保质期优先级高于 AI 的 shelf_life_days。
    """

    name: str = Field(min_length=1, max_length=64)
    quantity: float = Field(default=1, ge=0)
    unit: str = Field(default="个", max_length=16)
    confidence: float = Field(default=0.5, ge=0, le=1)
    category: str = "其他"
    storage_location: str = "冷藏"
    shelf_life_days: int | None = Field(default=None, ge=0, le=3650)

    purchase_date: date | None = Field(
        default=None, description="用户指定的购买日期，留空则按今天算"
    )
    expiry_date: date | None = Field(
        default=None, description="用户指定的过期日期，优先级高于 shelf_life_days"
    )

    @model_validator(mode="after")
    def _check_dates(self) -> "RecognizedFood":
        if (
            self.purchase_date
            and self.expiry_date
            and self.expiry_date < self.purchase_date
        ):
            raise ValueError("过期日期不能早于购买日期")
        return self


class ScanResult(BaseModel):
    """识别结果。注意：不直接写库，需用户确认后调 /inventory/confirm。"""

    scan_id: str
    foods: list[RecognizedFood]
    image_url: str | None = None
    needs_review: bool = False
    model: str = "mock"
    message: str = ""


class ScanConfirmRequest(BaseModel):
    """用户确认页提交的结果（可能已被用户手动修改）。"""

    foods: list[RecognizedFood]
    default_storage_location: str = "冷藏"


class ExpiringItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    food_name: str
    quantity: float
    unit: str
    expiry_date: date | None = None
    days_left: int | None = None
    freshness: str
