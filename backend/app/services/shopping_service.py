"""AI 4：采购规划。

纯确定性计算，不用模型——因为「需要多少 - 现有多少 = 缺多少」这件事，
让模型算只会算错。模型的价值在菜谱生成，不在这里。
"""
from __future__ import annotations

from collections import defaultdict

from app.models.inventory import FoodInventory
from app.schemas.recipe import RecipeOut
from app.schemas.shopping import ShoppingItemOut

# 单位换算：把常见单位折算到基准单位，便于跨菜谱合并同类项
_UNIT_ALIASES: dict[str, str] = {
    "克": "g", "千克": "kg", "公斤": "kg", "毫升": "ml", "升": "l",
    "颗": "个", "只": "个", "枚": "个", "根": "个", "条": "个",
    "袋": "袋", "瓶": "瓶", "盒": "盒", "把": "把", "块": "块",
}

# 每 100 单位的参考单价（元），仅用于给出「预计花费」的量级
_PRICE_TABLE: dict[str, float] = {
    "鸡蛋": 1.2, "西红柿": 1.0, "番茄": 1.0, "豆腐": 3.0, "鸡胸肉": 2.4,
    "牛奶": 6.0, "西兰花": 1.6, "青椒": 1.2, "牛肉": 8.0, "猪肉": 4.0,
    "大米": 0.8, "小葱": 2.0, "葱": 2.0, "生姜": 2.0, "大蒜": 1.6,
    "胡萝卜": 0.8, "土豆": 0.6, "白菜": 0.5, "菠菜": 1.2, "香菇": 2.4,
}


def normalize_unit(unit: str) -> str:
    u = (unit or "").strip().lower()
    return _UNIT_ALIASES.get(u, u)


def _to_base(quantity: float, unit: str) -> tuple[float, str]:
    """kg → g，l → ml，方便跨菜谱累加。"""
    u = normalize_unit(unit)
    if u == "kg":
        return quantity * 1000, "g"
    if u == "l":
        return quantity * 1000, "ml"
    return quantity, u


def estimate_price(name: str, quantity: float, unit: str) -> float:
    """粗略估价。仅用于给用户一个花费量级，不作为结算依据。"""
    unit_price = _PRICE_TABLE.get(name)
    if unit_price is None:
        for key, price in _PRICE_TABLE.items():
            if key in name or name in key:
                unit_price = price
                break
    if unit_price is None:
        return 0.0
    qty, u = _to_base(quantity, unit)
    if u in ("g", "ml"):
        return round(unit_price * qty / 100, 2)
    return round(unit_price * qty, 2)


def compute_missing(
    recipes: list[RecipeOut],
    inventory: list[FoodInventory],
) -> list[ShoppingItemOut]:
    """需要食材 - 已有食材 = 缺少食材，并自动合并重复食材。"""
    needed: dict[tuple[str, str], float] = defaultdict(float)
    display_unit: dict[tuple[str, str], str] = {}

    for recipe in recipes:
        for ing in recipe.ingredients:
            if ing.optional:
                continue
            qty, unit = _to_base(ing.quantity, ing.unit)
            key = (ing.name.strip(), unit)
            needed[key] += qty
            display_unit.setdefault(key, ing.unit)

    have: dict[tuple[str, str], float] = defaultdict(float)
    # 同时记下「这个食材名在库存里出现过哪些单位」。
    # 用来处理单位对不上的情况：库存记「1 盒豆腐」，菜谱要「300 g」。
    have_units_by_name: dict[str, set[str]] = defaultdict(set)
    for item in inventory:
        qty, unit = _to_base(item.quantity, item.unit)
        have[(item.food_name.strip(), unit)] += qty
        have_units_by_name[item.food_name.strip()].add(unit)

    result: list[ShoppingItemOut] = []
    for (name, unit), need_qty in sorted(needed.items()):
        owned_qty = have.get((name, unit), 0.0)
        gap = round(need_qty - owned_qty, 2)
        if gap <= 0:
            continue

        # 单位对不上时不能直接相减。
        # 「1 盒」和「300 g」之间没有换算关系，硬算会把 1 盒当成 0 g，
        # 于是把冰箱里明明有的豆腐又列进采购清单。
        # 而 recipe_service._recompute_availability 对同一种情况判定的是「有」——
        # 两处必须一致，否则用户会看到自相矛盾的界面：
        #   菜谱详情页：豆腐 ✓ 有
        #   采购清单：  请购买豆腐 300 g
        #
        # 这里的取舍与 recipe_service 保持一致：单位无法换算时按「大概率有」处理。
        # 理由：让用户去买冰箱里已经有的东西，比漏买一样更糟。
        # （owned_qty <= 0 说明没有任何同单位库存；
        #   have_units_by_name 里有这个名字，说明只是单位不同，不是真没有。）
        if owned_qty <= 0 and have_units_by_name.get(name):
            continue

        result.append(
            ShoppingItemOut(
                id=0,
                food_name=name,
                quantity=gap,
                unit=unit,
                estimated_price=estimate_price(name, gap, unit) or None,
            )
        )
    return result


def merge_into(items: list[ShoppingItemOut]) -> list[ShoppingItemOut]:
    """合并同名同单位的采购项（多天计划场景）。"""
    merged: dict[tuple[str, str], ShoppingItemOut] = {}
    for it in items:
        key = (it.food_name, normalize_unit(it.unit))
        if key in merged:
            merged[key].quantity = round(merged[key].quantity + it.quantity, 2)
            merged[key].estimated_price = round(
                (merged[key].estimated_price or 0) + (it.estimated_price or 0), 2
            ) or None
        else:
            merged[key] = it
    return list(merged.values())
