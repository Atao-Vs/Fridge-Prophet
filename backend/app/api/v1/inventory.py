"""冰箱库存：增删改查、临期查询、识别结果确认入库。"""
from datetime import date, timedelta

from fastapi import APIRouter, HTTPException, Query, status

from app.api.deps import CurrentUser, DbSession
from app.models.inventory import FoodInventory
from app.schemas.inventory import (
    ExpiringItem,
    InventoryCreate,
    InventoryOut,
    InventoryUpdate,
    ScanConfirmRequest,
)
from app.services.ingredient_image_service import resolve_ingredient_image

router = APIRouter(prefix="/inventory", tags=["冰箱库存"])

DEFAULT_SHELF_LIFE: dict[str, int] = {
    "鸡蛋": 30, "牛奶": 7, "豆腐": 3, "西红柿": 7, "青椒": 7, "西兰花": 5,
    "鸡胸肉": 30, "牛肉": 30, "猪肉": 30, "叶菜": 3, "蘑菇": 5,
}


def _shelf_life_for(name: str, explicit: int | None) -> int:
    if explicit:
        return explicit
    for key, days in DEFAULT_SHELF_LIFE.items():
        if key in name or name in key:
            return days
    return 7


def _resolve_dates(
    *,
    food_name: str,
    purchase_date: date | None,
    expiry_date: date | None,
    shelf_life_days: int | None,
    today: date,
) -> tuple[date, date]:
    """决定入库时用哪个购买日期和过期日期。

    优先级（越靠前越优先）：

    - 购买日期：用户显式指定 > 今天
    - 过期日期：用户显式指定 > 购买日期 + 用户给的保质期天数
      > 购买日期 + 按食材名查到的默认保质期

    用户手里的牛奶可能已经买了 3 天，所以「用户说的」永远压过「AI 猜的」。
    """
    purchase = purchase_date or today
    if expiry_date is not None:
        return purchase, expiry_date
    days = _shelf_life_for(food_name, shelf_life_days)
    return purchase, purchase + timedelta(days=days)


def _merge_expiry(old: date | None, new: date) -> date:
    """同名食材累加时如何合并过期日期：取**更早**的那个。

    库存表按「名称 + 储存位置」聚合成一行，两批不同保质期的同种食材
    本来就没法分开记。取更早的意味着 App 会**更早**提醒你，
    而漏提醒（把快过期的显示成新鲜）比多提醒严重得多。
    """
    return new if old is None else min(old, new)


def _to_out(item: FoodInventory, today: date) -> InventoryOut:
    """ORM 行 → 返回体。**所有返回食材的地方都必须走这里**。

    配图和剩余天数是**算出来的**，不在表里。放在这里是为了只有一处实现：
    如果哪个接口自己 `model_validate(row)`，那条路径上的食材就会没有图、
    没有剩余天数，而且这种缺失是静默的（界面显示占位色块，不报错）。
    """
    days_left = (item.expiry_date - today).days if item.expiry_date else None
    out = InventoryOut.model_validate(item)
    out.days_left = days_left
    out.image_url = resolve_ingredient_image(item.food_name)
    return out


def _owned_or_404(db: DbSession, user_id: int, item_id: int) -> FoodInventory:
    item = (
        db.query(FoodInventory)
        .filter(FoodInventory.id == item_id, FoodInventory.user_id == user_id)
        .one_or_none()
    )
    if item is None:
        raise HTTPException(status_code=404, detail="库存中找不到该食材")
    return item


@router.get("", response_model=list[InventoryOut], summary="全部食材（可按位置/分类过滤）")
def list_inventory(
    user: CurrentUser,
    db: DbSession,
    storage_location: str | None = Query(default=None, description="冷藏/冷冻/常温"),
    category: str | None = None,
    keyword: str | None = None,
) -> list[InventoryOut]:
    q = db.query(FoodInventory).filter(FoodInventory.user_id == user.id)
    if storage_location:
        q = q.filter(FoodInventory.storage_location == storage_location)
    if category:
        q = q.filter(FoodInventory.category == category)
    if keyword:
        q = q.filter(FoodInventory.food_name.contains(keyword))

    items = q.order_by(FoodInventory.expiry_date.asc().nullslast()).all()
    today = date.today()
    for it in items:
        it.refresh_freshness(today)
    db.commit()
    return [_to_out(it, today) for it in items]


@router.get("/expiring", response_model=list[ExpiringItem], summary="即将过期（默认 3 天内）")
def list_expiring(
    user: CurrentUser,
    db: DbSession,
    within_days: int = Query(default=3, ge=0, le=30),
) -> list[ExpiringItem]:
    today = date.today()
    deadline = today + timedelta(days=within_days)
    items = (
        db.query(FoodInventory)
        .filter(
            FoodInventory.user_id == user.id,
            FoodInventory.expiry_date.isnot(None),
            FoodInventory.expiry_date <= deadline,
        )
        .order_by(FoodInventory.expiry_date.asc())
        .all()
    )
    out = []
    for it in items:
        it.refresh_freshness(today)
        out.append(
            ExpiringItem(
                id=it.id,
                food_name=it.food_name,
                quantity=it.quantity,
                unit=it.unit,
                expiry_date=it.expiry_date,
                days_left=(it.expiry_date - today).days if it.expiry_date else None,
                freshness=it.freshness,
            )
        )
    db.commit()
    return out


@router.get("/stats", summary="库存概览（首页顶部数字）")
def inventory_stats(user: CurrentUser, db: DbSession) -> dict:
    today = date.today()
    items = db.query(FoodInventory).filter(FoodInventory.user_id == user.id).all()
    for it in items:
        it.refresh_freshness(today)
    db.commit()

    by_location: dict[str, int] = {}
    for it in items:
        by_location[it.storage_location] = by_location.get(it.storage_location, 0) + 1

    return {
        "total_kinds": len(items),
        "by_location": by_location,
        "expiring_soon": sum(
            1 for it in items if it.expiry_date and (it.expiry_date - today).days <= 3
        ),
        "expired": sum(
            1 for it in items if it.expiry_date and (it.expiry_date - today).days < 0
        ),
        "freshness_breakdown": {
            level: sum(1 for it in items if it.freshness == level)
            for level in ("新鲜", "正常", "尽快食用", "已过期")
        },
    }


@router.post("", response_model=InventoryOut, status_code=status.HTTP_201_CREATED,
             summary="手动添加食材")
def create_item(payload: InventoryCreate, user: CurrentUser, db: DbSession) -> InventoryOut:
    today = date.today()
    data = payload.model_dump()
    # shelf_life_days 只是「用来推算过期日期」的输入提示，不是数据库字段
    shelf_life_days = data.pop("shelf_life_days", None)

    purchase, expiry = _resolve_dates(
        food_name=data["food_name"],
        purchase_date=data.get("purchase_date"),
        expiry_date=data.get("expiry_date"),
        shelf_life_days=shelf_life_days,
        today=today,
    )
    data["purchase_date"] = purchase
    data["expiry_date"] = expiry

    item = FoodInventory(user_id=user.id, **data)
    item.refresh_freshness(today)
    db.add(item)
    db.commit()
    db.refresh(item)
    return _to_out(item, today)


@router.patch("/{item_id}", response_model=InventoryOut, summary="修改食材")
def update_item(
    item_id: int, payload: InventoryUpdate, user: CurrentUser, db: DbSession
) -> InventoryOut:
    item = _owned_or_404(db, user.id, item_id)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(item, field, value)
    if payload.freshness is None:
        item.refresh_freshness()
    db.commit()
    db.refresh(item)
    return _to_out(item, date.today())


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT, summary="删除食材")
def delete_item(item_id: int, user: CurrentUser, db: DbSession) -> None:
    item = _owned_or_404(db, user.id, item_id)
    db.delete(item)
    db.commit()


@router.post("/confirm", response_model=list[InventoryOut], status_code=status.HTTP_201_CREATED,
             summary="确认 AI 识别结果并写入冰箱（同名食材自动累加）")
def confirm_scan(
    payload: ScanConfirmRequest, user: CurrentUser, db: DbSession
) -> list[InventoryOut]:
    """策划书强调的「用户确认后才写入库存」这一步就在这里落地。"""
    if not payload.foods:
        raise HTTPException(status_code=400, detail="没有可加入的食材")

    today = date.today()
    saved: list[FoodInventory] = []

    for food in payload.foods:
        location = food.storage_location or payload.default_storage_location
        purchase, expiry = _resolve_dates(
            food_name=food.name,
            purchase_date=food.purchase_date,
            expiry_date=food.expiry_date,
            shelf_life_days=food.shelf_life_days,
            today=today,
        )
        existing = (
            db.query(FoodInventory)
            .filter(
                FoodInventory.user_id == user.id,
                FoodInventory.food_name == food.name,
                FoodInventory.storage_location == location,
            )
            .one_or_none()
        )

        if existing:
            existing.quantity += food.quantity
            existing.confidence = food.confidence
            existing.source = "ai_scan"
            # 两批同种食材保质期不同，取更早的那个（见 _merge_expiry 注释）
            existing.expiry_date = _merge_expiry(existing.expiry_date, expiry)
            existing.refresh_freshness(today)
            saved.append(existing)
        else:
            item = FoodInventory(
                user_id=user.id,
                food_name=food.name,
                category=food.category,
                quantity=food.quantity,
                unit=food.unit,
                storage_location=location,
                purchase_date=purchase,
                expiry_date=expiry,
                confidence=food.confidence,
                source="ai_scan",
            )
            item.refresh_freshness(today)
            db.add(item)
            saved.append(item)

    db.commit()
    for it in saved:
        db.refresh(it)
    return [_to_out(it, today) for it in saved]


@router.delete("", status_code=status.HTTP_204_NO_CONTENT, summary="清空冰箱")
def clear_inventory(user: CurrentUser, db: DbSession) -> None:
    db.query(FoodInventory).filter(FoodInventory.user_id == user.id).delete()
    db.commit()
