#!/usr/bin/env bash
# ============================================================
#  后端一键启动
#
#  用法（在项目根目录执行）：
#      bash tools/start-backend.sh
#
#  这个脚本会自动完成四件事，缺什么补什么：
#      1. 没有虚拟环境就建一个
#      2. 依赖没装或装不全就装
#      3. 没有 .env 就从模板复制一份（零配置即可运行）
#      4. 打印你的局域网 IP，方便填到 App 的 API_BASE_URL
#
#  重复执行是安全的，已经就绪的步骤会自动跳过。
# ============================================================

set -euo pipefail

# ---------- 0. 保证核心命令可用（重要，别删）----------
# bash 被**直接调用**时（例如从 cmd 里跑，或双击 .bat），
# 它不会像 git-bash.exe 那样自动把 /usr/bin 加进 PATH。
# 结果是 dirname / awk / grep / sort / tr / hostname 这些**外部命令**全部
# 报 "command not found"，脚本第一步就挂。
#
# 这个坑特别有迷惑性：pwd、cd、echo 都是 bash 内置命令，照常工作，
# 所以看起来「bash 明明能跑」，只是个别命令找不到。
#
# 这里主动补上 MSYS 的标准目录，让脚本无论被谁调用都能跑通。
for _d in /usr/bin /bin /mingw64/bin; do
    if [[ -d "$_d" ]]; then
        case ":$PATH:" in
            *":$_d:"*) ;;
            *) PATH="$_d:$PATH" ;;
        esac
    fi
done
export PATH
unset _d

# 补完还是找不到，说明这个 bash 装得不完整，早点报错比后面莫名其妙地挂掉好
if ! command -v dirname >/dev/null 2>&1; then
    echo "错误：找不到 dirname，这个 bash 环境不完整。" >&2
    echo "当前 PATH=$PATH" >&2
    echo "请安装 Git for Windows 后重试：https://git-scm.com/download/win" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/backend"

cd "$BACKEND_DIR"

# ---------- 1. 找一个可用的 Python ----------
find_python() {
    local candidates=(
        "C:/Users/Atao/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
        "C:/Users/Atao/AppData/Local/Programs/Python/Python311/python.exe"
    )
    for c in "${candidates[@]}"; do
        [[ -x "$c" ]] && { echo "$c"; return 0; }
    done
    for name in python3 python py; do
        if command -v "$name" >/dev/null 2>&1; then
            command -v "$name"
            return 0
        fi
    done
    return 1
}

PYTHON="$(find_python)" || {
    echo "找不到 Python。请先安装 Python 3.11 或以上版本。" >&2
    exit 1
}

# ---------- 2. 虚拟环境 ----------
# Windows 的 venv 可执行文件在 Scripts/ 下，Linux/macOS 在 bin/ 下
if [[ -d ".venv/Scripts" ]]; then
    VENV_PY=".venv/Scripts/python.exe"
elif [[ -d ".venv/bin" ]]; then
    VENV_PY=".venv/bin/python"
else
    VENV_PY=""
fi

if [[ -z "$VENV_PY" ]]; then
    echo "[1/4] 创建虚拟环境 .venv ..."
    "$PYTHON" -m venv .venv
    if [[ -d ".venv/Scripts" ]]; then
        VENV_PY=".venv/Scripts/python.exe"
    else
        VENV_PY=".venv/bin/python"
    fi
    echo "      完成"
else
    echo "[1/4] 虚拟环境已存在，跳过"
fi

# ---------- 3. 依赖 ----------
# 用「能不能 import fastapi」判断依赖是否装好，比比对版本号简单可靠
if "$VENV_PY" -c "import fastapi, uvicorn, sqlalchemy, pydantic_settings" 2>/dev/null; then
    echo "[2/4] 依赖已就绪，跳过"
else
    echo "[2/4] 安装依赖（首次约需 1-3 分钟）..."
    "$VENV_PY" -m pip install --upgrade pip -q
    "$VENV_PY" -m pip install -r requirements.txt
    echo "      完成"
fi

# ---------- 4. 配置文件 ----------
if [[ -f ".env" ]]; then
    echo "[3/4] .env 已存在，跳过"
else
    echo "[3/4] 从模板创建 .env ..."
    cp .env.example .env
    echo "      完成（DATABASE_URL 与 DASHSCOPE_API_KEY 都留空，将使用 SQLite + MOCK 模式）"
fi

# ---------- 5. 局域网 IP（真机调试要用）----------
echo "[4/4] 你的局域网 IP（真机调试时填到 API_BASE_URL）："

# 这里有两个坑，都不是显而易见的：
#
# 坑一：Windows 的 ipconfig 输出是 **GBK** 编码（中文 Windows 控制台默认），
#       而 MSYS 的 locale 是 UTF-8。GNU awk 在 UTF-8 locale 下读到非法的
#       多字节序列时，会**静默地放弃匹配**——不报错，就是什么都不输出。
#       所以必须加 LC_ALL=C，让它按字节处理，不去尝试解码 UTF-8。
#       （验证方法：ipconfig | grep IPv4 | od -An -tx1，会看到 b5 d8 而不是 e5 9c）
#
# 坑二：MSYS 自带的 hostname 不支持 -I 参数，Windows 上跑会报
#       "unknown option -- I"。所以按操作系统分支，而不是靠命令是否存在来判断。
_ips=""
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        _ips="$(ipconfig 2>/dev/null | tr -d '\r' | LC_ALL=C awk '
            /IPv4/ { ip = $NF }
            /IPv4.*(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)/ { print "      http://" ip ":8000/" }
        ' || true)"
        ;;
    *)
        # Linux / macOS
        _ips="$(hostname -I 2>/dev/null | tr ' ' '\n' \
            | LC_ALL=C grep -E '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' \
            | sed 's|^|      http://|; s|$|:8000/|' || true)"
        ;;
esac

if [[ -n "$_ips" ]]; then
    printf '%s\n' "$_ips" | sort -u
else
    # 检测不到也别静默跳过，给用户一条自己能查的路
    echo "      （没自动检测到。手动查：在 cmd 里运行 ipconfig，"
    echo "        找「IPv4 地址」那一行，形如 192.168.x.x）"
fi
unset _ips

echo
echo "============================================================"
echo " 启动后端..."
echo " 接口文档: http://127.0.0.1:8000/docs"
echo " 健康检查: http://127.0.0.1:8000/health"
echo
echo " 手机真机调试：确保手机与电脑连同一个 WiFi，"
echo " 然后这样打 App 包（把 IP 换成上面列出的那个）："
echo "   cd android"
echo "   \"G:/Android/gradle-8.14.5/bin/gradle.bat\" installDebug \\"
echo "       -PAPI_BASE_URL=http://192.168.x.x:8000/"
echo
echo " 按 Ctrl+C 停止"
echo "============================================================"
echo

exec "$VENV_PY" run.py
