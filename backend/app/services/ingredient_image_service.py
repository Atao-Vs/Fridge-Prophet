"""食材配图：按食材名匹配一张**白底单品图**。

## 为什么单独做一套图，不复用菜谱图

菜谱图是「一盘做好的菜」，食材图是「一样生的食材」，两者用途完全不同：
菜谱图放在菜谱卡片上让人有食欲，食材图放在冰箱列表里帮人**快速认东西**。
把成品菜图塞进冰箱列表会出现「冰箱里有一盘红烧肉」这种荒谬的观感。

## 为什么是白底

冰箱列表一屏十几个条目，每条一张小图。彩色背景的小图混在一起会互相打架，
而且不同的底色会让整列看着参差不齐。统一白底 + 主体居中，
缩到 44dp 时依然能一眼分辨，整列也是齐的。

## 匹配不上怎么办

逐级退化，最后返回 None —— 由客户端显示「食材名首字 + 哈希色块」的占位。
**不要给一个通用的「未知食材」图**：一列里出现五张一样的通用图，
用户会以为列表渲染坏了，比没有图更糟。
"""
from __future__ import annotations

import logging

from app.core.config import settings

logger = logging.getLogger(__name__)

IMAGE_SUBDIR = "ingredients"
IMAGE_SUFFIX = ".jpg"

# ---------------------------------------------------------------- 匹配规则
# 顺序有意义：**从上往下**匹配，命中即停。
#
# ⚠️ 单字关键词必须排在多字关键词**之后**，而且要注意会不会被别的规则抢走。
# 踩过的坑（和菜谱图那边是同一类问题）：
#   「鸡蛋」含「鸡」，如果鸡肉规则在前面，鸡蛋会被配上鸡肉图。
#   所以蛋类规则必须排在所有肉类之前。
#
# 加新规则前先自问一遍：这条的关键词会不会被上面某条先命中？
INGREDIENT_RULES: tuple[tuple[tuple[str, ...], str], ...] = (
    # —— 蛋奶：必须排在肉类之前（「鸡蛋」含「鸡」）——
    (("鸡蛋", "蛋", "蛋液"), "egg"),
    (("牛奶", "纯奶", "鲜奶"), "milk"),
    (("酸奶", "优酪乳"), "yogurt"),
    (("奶酪", "芝士", "黄油", "奶油"), "cheese"),

    # —— 肉类 ——
    (("鸡胸", "鸡腿", "鸡翅", "鸡肉", "整鸡", "鸡"), "chicken"),
    (("五花", "里脊", "排骨", "猪肉", "肉末", "肉丝", "肉片", "猪"), "pork"),
    (("牛腩", "牛肉", "牛排", "牛"), "beef"),
    (("火腿", "培根", "香肠", "腊肉", "午餐肉"), "ham"),
    (("羊肉", "羊排", "羊"), "lamb"),

    # —— 水产 ——
    (("虾", "基围虾", "虾仁"), "shrimp"),
    (("鱼", "鲈鱼", "鲫鱼", "草鱼", "三文鱼", "带鱼"), "fish"),
    (("蟹", "蛤", "贝", "鱿鱼", "海鲜"), "seafood"),

    # —— 蔬菜 ——
    # 「西红柿 / 番茄」放在黄瓜前面：两者常一起出现（凉拌），但西红柿的识别度更高
    (("西红柿", "番茄"), "tomato"),
    (("黄瓜", "青瓜"), "cucumber"),
    (("土豆", "马铃薯", "洋芋"), "potato"),
    (("胡萝卜",), "carrot"),
    (("白萝卜", "萝卜"), "radish"),
    (("白菜", "娃娃菜", "大白菜"), "cabbage"),
    (("菠菜",), "spinach"),
    (("生菜", "莴苣"), "lettuce"),
    (("西兰花", "花菜", "菜花"), "broccoli"),
    (("青椒", "尖椒", "彩椒", "柿子椒"), "green-pepper"),
    (("洋葱",), "onion"),
    (("茄子",), "eggplant"),
    (("蘑菇", "香菇", "金针菇", "杏鲍菇", "口蘑", "木耳", "菌"), "mushroom"),
    (("玉米",), "corn"),
    (("南瓜",), "pumpkin"),
    (("豆角", "四季豆", "豇豆", "豌豆", "青豆"), "green-beans"),
    (("芹菜",), "celery"),

    # —— 豆制品 ——
    (("豆腐", "豆干", "腐竹", "千张", "豆皮"), "tofu"),

    # —— 主食 ——
    (("米饭", "大米", "米", "饭"), "rice"),
    (("面条", "挂面", "拉面", "意面", "面粉", "粉"), "noodles"),
    (("面包", "吐司"), "bread"),

    # —— 水果 ——
    (("苹果",), "apple"),
    (("香蕉",), "banana"),
    (("橙子", "橘子", "柑橘", "柚子"), "orange"),
    (("葡萄", "提子"), "grape"),
    (("草莓",), "strawberry"),
    (("西瓜",), "watermelon"),

    # —— 其他 ——
    (("姜", "蒜", "葱", "香菜", "调味"), "seasoning"),
)


def _exists(key: str) -> bool:
    return (settings.STATIC_DIR / IMAGE_SUBDIR / f"{key}{IMAGE_SUFFIX}").is_file()


def _url(key: str) -> str:
    return f"/static/{IMAGE_SUBDIR}/{key}{IMAGE_SUFFIX}"


def resolve_ingredient_key(name: str) -> str | None:
    """按规则算出这个食材**理论上**该用哪张图，不查磁盘。

    拆出这个函数是为了测试规则表顺序：例如「鸡蛋」必须先命中 egg，
    不能被鸡肉规则里的单字「鸡」抢走。图片还没生成时也能测规则是否正确。
    """
    text = (name or "").strip()
    if not text:
        return None

    for keywords, key in INGREDIENT_RULES:
        if any(kw in text for kw in keywords):
            return key
    return None


def resolve_ingredient_image(name: str) -> str | None:
    """算出这个食材该用哪张图。返回**相对 URL**，找不到返回 None。

    只返回磁盘上真实存在的图：规则表里写了 key 但图还没生成时，
    直接返回 None，而不是返回一个 404 的地址 —— 那会在客户端显示成裂图。
    """
    key = resolve_ingredient_key(name)
    if key is None:
        return None
    if _exists(key):
        return _url(key)
    logger.debug("食材图 %s 尚未生成，跳过 %s", key, name)
    return None


def available_keys() -> list[str]:
    """已经生成好的图有哪些。供 `scripts/check_images.py` 之类的工具核对。"""
    folder = settings.STATIC_DIR / IMAGE_SUBDIR
    if not folder.is_dir():
        return []
    return sorted(p.stem for p in folder.glob(f"*{IMAGE_SUFFIX}"))
