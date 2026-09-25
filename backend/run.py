"""本地开发启动脚本。

    python run.py
等价于：
    uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
"""
import os
import sys
from pathlib import Path

import uvicorn

sys.path.insert(0, str(Path(__file__).parent))

if __name__ == "__main__":
    port = int(os.getenv("PORT", "8000"))
    print(f"\n  冰箱先知后端启动中...")
    print(f"  接口文档:  http://127.0.0.1:{port}/docs")
    print(f"  健康检查:  http://127.0.0.1:{port}/health\n")
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=port,
        reload=True,
        reload_dirs=[str(Path(__file__).parent / "app")],
    )
