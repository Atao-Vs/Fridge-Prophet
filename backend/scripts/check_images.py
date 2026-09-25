"""菜谱配图自检：看看图片库里有什么、还缺什么、匹配规则是否正常。

用法：
    cd backend
    .venv/Scripts/python.exe scripts/check_images.py

它做三件事：
    1. 列出 static/recipes/ 里已有的图片
    2. 列出规则表会用到、但还没生成的 key
    3. 拿几个真实菜名试一遍匹配，确认能匹配到图（而不是全部落到 default）
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import settings  # noqa: E402
from app.services.food_image_service import (  # noqa: E402
    DEFAULT_KEY,
    IMAGE_SUBDIR,
    available_images,
    expected_keys,
    resolve_image_key,
    resolve_image_url,
)

# 拿几个真实菜名 + 用户冰箱里常见的菜来试匹配
SAMPLES: list[tuple[str, list[str]]] = [
    ("番茄鸡蛋豆腐", ["西红柿", "鸡蛋", "豆腐"]),
    ("香煎鸡胸配西兰花", ["鸡胸肉", "西兰花"]),
    ("青椒炒鸡蛋", ["青椒", "鸡蛋"]),
    ("番茄炒蛋", ["西红柿", "鸡蛋"]),
    ("麻婆豆腐", ["豆腐", "肉末"]),
    ("红烧肉", ["五花肉"]),
    ("清蒸鲈鱼", ["鲈鱼", "姜"]),
    ("蒜蓉西兰花", ["西兰花", "大蒜"]),
    ("酸辣土豆丝", ["土豆", "青椒"]),
    ("蛋炒饭", ["米饭", "鸡蛋"]),
    ("冬瓜排骨汤", ["冬瓜", "排骨"]),
    ("完全不认识的菜名XYZ", ["不明食材"]),
]


def main() -> int:
    directory = settings.STATIC_DIR / IMAGE_SUBDIR
    print("=" * 60)
    print(f"图片目录: {directory}")
    print("=" * 60)

    have = available_images()
    need = expected_keys()
    missing = [k for k in need if k not in have]
    extra = [k for k in have if k not in need]

    print(f"\n已就位 {len(have)} 张：")
    print("  " + ("、".join(have) if have else "（一张都没有）"))

    print(f"\n规则表需要 {len(need)} 张，其中缺 {len(missing)} 张：")
    print("  " + ("、".join(missing) if missing else "（一张不缺）"))
    if extra:
        print(f"\n规则表用不到、但目录里有的（可能是多余的）：{'、'.join(extra)}")

    print("\n" + "-" * 60)
    print("匹配实测：")
    print("-" * 60)
    fallback_count = 0
    for name, ings in SAMPLES:
        key = resolve_image_key(name, ings)
        url = resolve_image_url(name, ings)
        if key == DEFAULT_KEY:
            fallback_count += 1
        if key is None:
            print(f"  [--]  {name:16s} → 无图（图片库是空的）")
        else:
            print(f"  [OK]  {name:16s} → {key:24s} {url}")

    print("\n" + "=" * 60)
    if not have:
        print("结论：图片库为空，配图功能处于「降级但可用」状态。")
        print("      接口仍会正常返回 image_url=None，App 显示占位样式，不影响任何链路。")
        print(f"      把图片命名为 <key>.jpg 放进 {directory} 即可生效，无需改代码。")
    elif fallback_count >= len(SAMPLES) - 1:
        print("结论：图片太少，几乎所有菜都落到了 default，建议再多生成几张。")
    else:
        print(f"结论：匹配正常，{len(SAMPLES) - fallback_count}/{len(SAMPLES)} 个样本命中了专属图。")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
