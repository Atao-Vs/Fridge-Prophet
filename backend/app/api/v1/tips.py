"""食品安全小贴士。

内容取向是「科学辟谣 + 权威机构确认的真实要点」，不是「民间食物相克大全」——
后者绝大多数已被证伪，在一个食品安全类应用里传播伪科学是负价值。
详见 app/data/food_tips.py 顶部的说明。

**这个路由不需要登录。** 理由：内容是静态的公共知识，不含任何用户数据；
不鉴权还能让客户端在登录页也展示一条贴士。
"""
from __future__ import annotations

import random

from fastapi import APIRouter, HTTPException, Query

from app.data.food_tips import CATEGORIES, FOOD_TIPS, VERDICTS

router = APIRouter(prefix="/tips", tags=["食品安全贴士"])

# 按 id 建索引，详情查询 O(1)
_BY_ID = {t["id"]: t for t in FOOD_TIPS}


def _summary(tip: dict) -> dict:
    """列表用的精简版：不带 detail，减少传输量。"""
    return {
        "id": tip["id"],
        "title": tip["title"],
        "category": tip["category"],
        "verdict": tip["verdict"],
        "summary": tip["summary"],
    }


@router.get("", summary="全部贴士（可按分类筛选，不含详情正文）")
def list_tips(
    category: str | None = Query(default=None, description="按分类筛选"),
    verdict: str | None = Query(default=None, description="按结论筛选：谣言/部分属实/属实/注意"),
    limit: int | None = Query(default=None, ge=1, le=100),
) -> dict:
    items = FOOD_TIPS
    if category:
        items = [t for t in items if t["category"] == category]
    if verdict:
        items = [t for t in items if t["verdict"] == verdict]
    if limit:
        items = items[:limit]

    return {
        "total": len(FOOD_TIPS),
        "count": len(items),
        "categories": list(CATEGORIES),
        "verdicts": list(VERDICTS),
        "items": [_summary(t) for t in items],
    }


@router.get("/random", summary="随机取几条（首页标语位用）")
def random_tips(
    count: int = Query(default=1, ge=1, le=10),
    exclude: str | None = Query(
        default=None, description="不要返回这条（传上一条的 id），保证「换一条」是真的换了"
    ),
) -> dict:
    """首页那个标语位每次进来换一条，所以单独给了这个接口。

    ## 为什么要 `exclude`

    纯 `random.sample` 有 1/N 的概率抽到和上一条完全一样的内容。
    N 是几十条，也就是用户每切几次界面就会撞见一次「没变」，
    读起来就像功能坏了 —— 而这恰恰是这个需求要解决的问题。

    客户端把当前显示的贴士 id 传上来，服务端从**剩下的**里抽，
    这样「每次切换都换一条」才是确定的，而不是概率性的。

    exclude 不合法（id 不存在）时忽略它，不报错：
    它只是个优化提示，不该让整个请求失败。
    """
    pool = [t for t in FOOD_TIPS if t["id"] != exclude] if exclude else FOOD_TIPS
    if not pool:
        # 只有一条贴士时上面会把池子清空，退回全集，保证永远有东西返回
        pool = FOOD_TIPS
    picked = random.sample(pool, k=min(count, len(pool)))
    return {"count": len(picked), "items": [_summary(t) for t in picked]}


@router.get("/{tip_id}", summary="单条贴士详情（含原因与原理）")
def get_tip(tip_id: str) -> dict:
    tip = _BY_ID.get(tip_id)
    if tip is None:
        raise HTTPException(status_code=404, detail="找不到这条贴士")
    return tip
