#!/usr/bin/env python3
"""把生成出来的原始图片（PNG）转成菜谱配图要求的 JPG，并放到 static/recipes/。

为什么需要这一步
----------------
图片生成工具只输出 PNG，而 `food_image_service.py` 约定的后缀是 `.jpg`。
PNG 对照片来说是**最差的格式**：一张 1536×1024 的食物照 PNG 有 2~3 MB，
转成 JPEG 后只有 150~250 KB。手机在公网上加载这几十张图时，
体积差 10 倍以上，所以必须在入库前转掉。

用法
----
1. 把生成好的图片按这个结构放好（每张图一个目录，目录名 = 图片 key）：

       .tmp-images/
           tomato-egg/      xxx.png      → static/recipes/tomato-egg.jpg
           mapo-tofu/       yyy.png      → static/recipes/mapo-tofu.jpg

   也支持平铺放法（文件名 = key）：

       .tmp-images/tomato-egg.png       → static/recipes/tomato-egg.jpg

2. 运行：

       python tools/import-recipe-images.py

   加 `--check` 只做检查、不写文件。

为什么目录名就是 key
--------------------
key 是 `food_image_service.py` 规则表里的标识符（如 `tomato-egg`）。
用目录名当 key，是为了**避免依赖生成顺序去猜哪张图是哪道菜** ——
顺序一旦错位，就会出现「麻婆豆腐配了番茄炒蛋的图」这种很难发现的问题。

⚠️ 生成图片时必须**一张一张来，不要并行**
----------------------------------------
踩过的坑：并行发起 11 个生成请求（每个都传了各自的 `output_dir`），结果

1. `output_dir` 参数**全部失效**，11 张图全写进了同一个目录；
2. 文件名只带**秒级**时间戳（`..._2026-09-24T08-11-24.png`），
   同一秒内完成的多张图**同名互相覆盖** —— 11 张最后只剩 8 张，白花了 3 张的积分。

**正确做法**：一张一张串行生成，每张生成完立刻确认落盘位置。
本项目这 12 张就是这么补出来的。多花几分钟，但不会浪费积分。

（如果已经并行生成了、图混在一个目录里：图片内容是可以肉眼区分的。
本项目当时把混在一起的图做成带标签的拼图，一眼就认出哪张是哪道菜，
再按时间戳 + 内容反推归位。只补了真正丢失的 3 张。）
"""
from __future__ import annotations

import argparse
import ast
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    print("缺少 Pillow。请先安装：")
    print("  pip install Pillow")
    sys.exit(1)

# 脚本在 tools/ 下，项目根目录是它的上一级
ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIR = ROOT / ".tmp-images"
TARGET_DIR = ROOT / "backend" / "static" / "recipes"

# 支持的输入格式
SOURCE_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp")

# 输出尺寸：长边压到 1280。
# 依据：App 里最大的配图是菜谱详情的通栏图，180dp 高、屏宽约 360dp，
# 3x 屏下约 1080×540 像素。1280 长边留了余量，又不会让文件变大。
MAX_EDGE = 1280

# JPEG 质量。86 是「肉眼基本无损」和「体积可接受」之间的常用平衡点。
JPEG_QUALITY = 86


def find_sources() -> list[tuple[str, Path]]:
    """找出所有待转换的图片，返回 [(key, 文件路径), ...]。"""
    if not SOURCE_DIR.is_dir():
        return []

    found: list[tuple[str, Path]] = []

    for entry in sorted(SOURCE_DIR.iterdir()):
        # 形式一：子目录，目录名 = key
        if entry.is_dir():
            for child in sorted(entry.iterdir()):
                if child.suffix.lower() in SOURCE_SUFFIXES:
                    found.append((entry.name, child))
                    break  # 一个目录只取第一张，多余的是重复生成
            continue

        # 形式二：平铺文件，文件名 = key
        if entry.suffix.lower() in SOURCE_SUFFIXES:
            found.append((entry.stem, entry))

    return found


def convert(key: str, src: Path) -> tuple[int, int, int]:
    """转一张图，返回 (宽, 高, 字节数)。"""
    TARGET_DIR.mkdir(parents=True, exist_ok=True)
    dst = TARGET_DIR / f"{key}.jpg"

    with Image.open(src) as im:
        # 统一转 RGB：PNG 可能带 alpha（RGBA），直接存 JPEG 会报错
        if im.mode not in ("RGB", "L"):
            im = im.convert("RGB")

        # 等比缩放，只缩不放（原图比目标小就保持原样，避免放大变糊）
        width, height = im.size
        longest = max(width, height)
        if longest > MAX_EDGE:
            scale = MAX_EDGE / longest
            im = im.resize(
                (round(width * scale), round(height * scale)),
                Image.LANCZOS,
            )

        im.save(
            dst,
            format="JPEG",
            quality=JPEG_QUALITY,
            optimize=True,
            progressive=True,  # 渐进式 JPEG：网络慢时能先看到模糊轮廓再变清晰
        )
        return im.size[0], im.size[1], dst.stat().st_size


def expected_keys() -> list[str]:
    """从 food_image_service.py 的规则表里读出全部 key。

    用 `ast` 静态解析，**不 import** —— 这个脚本只需要 Pillow，
    可能跑在没装后端依赖（pydantic 等）的 Python 环境里，
    import 会直接失败。而且 import 会连带初始化整个 app 包，代价也没必要。

    直接读源文件而不是抄一份 key 列表：抄一份的话，规则表改了这里不会跟着改，
    最后变成「脚本说齐了、实际缺图」这种最难查的问题。
    """
    src = ROOT / "backend" / "app" / "services" / "food_image_service.py"
    if not src.is_file():
        print(f"[警告] 找不到规则表文件：{src}")
        return []

    tree = ast.parse(src.read_text(encoding="utf-8"))

    keys: set[str] = set()
    rule_names = {"DISH_RULES", "INGREDIENT_RULES"}

    for node in tree.body:
        if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            name, value = node.target.id, node.value
        elif (
            isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
        ):
            name, value = node.targets[0].id, node.value
        else:
            continue

        if name == "DEFAULT_KEY":
            keys.add(ast.literal_eval(value))
        elif name in rule_names:
            # 结构是 ((关键词, 关键词...), "图片key")
            for _keywords, key in ast.literal_eval(value):
                keys.add(key)

    return sorted(keys)


def main() -> int:
    parser = argparse.ArgumentParser(description="把原始图片转成菜谱配图 JPG")
    parser.add_argument(
        "--check", action="store_true", help="只检查，不写文件"
    )
    args = parser.parse_args()

    sources = find_sources()
    if not sources:
        print(f"没有找到待转换的图片。")
        print(f"  请把图片放到：{SOURCE_DIR}")
        print(f"  目录名（或文件名）就是图片 key，例如 tomato-egg")
        return 1

    print(f"源目录：{SOURCE_DIR}")
    print(f"目标目录：{TARGET_DIR}")
    print(f"共 {len(sources)} 张待处理\n")

    if args.check:
        for key, src in sources:
            print(f"  [待转换] {key:<28} ← {src.name}")
        return 0

    total_before = 0
    total_after = 0
    ok_keys: list[str] = []

    print(f"{'key':<28} {'输出尺寸':<14} {'体积':>10}  {'压缩比':>7}")
    print("-" * 68)

    for key, src in sources:
        before = src.stat().st_size
        try:
            width, height, after = convert(key, src)
        except Exception as exc:
            print(f"{key:<28} [失败] {exc}")
            continue

        total_before += before
        total_after += after
        ok_keys.append(key)
        print(
            f"{key:<28} {f'{width}x{height}':<14} "
            f"{after / 1024:>8.0f}KB  {before / after:>6.1f}x"
        )

    print("-" * 68)
    if total_after:
        print(
            f"合计 {len(ok_keys)} 张："
            f"{total_before / 1024 / 1024:.1f}MB → {total_after / 1024 / 1024:.1f}MB"
            f"（省掉 {100 - total_after / total_before * 100:.0f}%）"
        )

    # ---- 完整性检查：规则表要的图还缺哪些 ----
    keys = expected_keys()
    if keys:
        have = set(ok_keys)
        # 已经在库里的旧图也算有
        for p in TARGET_DIR.glob("*.jpg"):
            have.add(p.stem)

        missing = [k for k in keys if k not in have]
        unknown = [k for k in ok_keys if k not in keys]

        print()
        if unknown:
            # 不是错误：可能只是想换掉某张图。但多半是 key 拼错了，所以要提一句。
            print(f"[提示] 这些 key 不在规则表里，不会被任何菜匹配到：{', '.join(unknown)}")

        if missing:
            print(f"[待补] 规则表还需要 {len(missing)} 张：{', '.join(missing)}")
        else:
            print(f"[OK] 规则表需要的 {len(keys)} 张图已全部就位")

    print("\n完成。验证：")
    print("  cd backend && .venv/Scripts/python.exe scripts/check_images.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
