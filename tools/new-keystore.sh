#!/usr/bin/env bash
# ============================================================
#  一键生成 Android 签名密钥库（keystore）
#
#  用法（在项目根目录执行）：
#      bash tools/new-keystore.sh
#
#  执行完会得到两个文件：
#      android/release.jks            —— 密钥库本体，签名用的
#      android/keystore.properties    —— 密码配置，构建时自动读取
#
#  两者都已被 .gitignore 忽略，不会被提交。
# ============================================================

set -euo pipefail

# ---------- 0. 保证核心命令可用（重要，别删）----------
# bash 被**直接调用**时（例如从 cmd 里跑，或双击 .bat），
# 它不会像 git-bash.exe 那样自动把 /usr/bin 加进 PATH。
# 结果是 dirname / grep / cat 这些**外部命令**全部报 "command not found"。
#
# 迷惑之处：pwd、cd、echo 是 bash 内置命令，照常工作，
# 所以看起来「bash 明明能跑」，只是个别命令找不到。
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

if ! command -v dirname >/dev/null 2>&1; then
    echo "错误：找不到 dirname，这个 bash 环境不完整。" >&2
    echo "请安装 Git for Windows 后重试：https://git-scm.com/download/win" >&2
    exit 1
fi

# ---------- 定位项目根目录 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"

KEYSTORE="$ANDROID_DIR/release.jks"
PROPS="$ANDROID_DIR/keystore.properties"

# ---------- 定位 keytool ----------
# 优先用 JAVA_HOME，其次用本项目使用的 JDK，最后退回 PATH 里的 keytool
find_keytool() {
    local candidates=()
    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/keytool.exe" "$JAVA_HOME/bin/keytool")
    candidates+=(
        "G:/Android/jdk-17.0.20.1+1/bin/keytool.exe"
        "G:/Android/jdk-17.0.20.1+1/bin/keytool"
    )
    for c in "${candidates[@]}"; do
        [[ -x "$c" ]] && { echo "$c"; return 0; }
    done
    if command -v keytool >/dev/null 2>&1; then
        command -v keytool
        return 0
    fi
    return 1
}

KEYTOOL="$(find_keytool)" || {
    echo "找不到 keytool。请设置 JAVA_HOME 指向 JDK 17，或把 JDK 的 bin 目录加入 PATH。" >&2
    exit 1
}

# ---------- 路径转换 ----------
# keytool.exe 是原生 Windows 程序，不认 Git Bash 的 MSYS 风格路径。
# 直接传 /g/workbuddy/... 它会报 FileNotFoundException（找不到指定路径）。
# 必须先用 cygpath 转成 G:\workbuddy\... 再交给它。
# （注意：/tmp/... 这类路径连 cygpath 都转不成盘符路径，所以脚本内部的
#   路径判断仍用 MSYS 风格，只在调用 keytool 时转换。）
if command -v cygpath >/dev/null 2>&1; then
    KEYSTORE_FOR_KEYTOOL="$(cygpath -w "$KEYSTORE")"
else
    KEYSTORE_FOR_KEYTOOL="$KEYSTORE"
fi

echo "使用 keytool: $KEYTOOL"
echo "密钥库位置:   $KEYSTORE_FOR_KEYTOOL"

# ---------- 已存在则先确认 ----------
if [[ -f "$KEYSTORE" ]]; then
    echo
    echo "⚠️  $KEYSTORE 已经存在。"
    echo "    覆盖它会生成一个新的签名，导致旧版本 App 无法再通过更新安装。"
    read -r -p "    确定要覆盖吗？输入 yes 继续：" ans
    [[ "$ans" == "yes" ]] || { echo "已取消。"; exit 0; }
fi

# ---------- 收集参数 ----------
read -r -p "密钥别名 (alias) [fridgeprophet]：" ALIAS
ALIAS="${ALIAS:-fridgeprophet}"

read -r -p "证书里显示的组织名 (可随便填) [FridgeProphet]：" ORG
ORG="${ORG:-FridgeProphet}"

echo
echo "设置密码。密钥库密码和密钥密码会设为同一个，省得记两套。"
echo "建议用 16 位以上、包含大小写字母和数字的随机串。"
while true; do
    read -r -s -p "请输入密码：" PASS1; echo
    read -r -s -p "请再输入一次：" PASS2; echo
    if [[ "$PASS1" != "$PASS2" ]]; then
        echo "两次输入不一致，重来。"
        continue
    fi
    if [[ ${#PASS1} -lt 6 ]]; then
        echo "keytool 要求至少 6 位，重来。"
        continue
    fi
    break
done

# ---------- 生成密钥库 ----------
echo
echo "正在生成密钥库..."

"$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE_FOR_KEYTOOL" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$PASS1" \
    -keypass "$PASS1" \
    -dname "CN=$ORG, OU=Mobile, O=$ORG, L=Beijing, ST=Beijing, C=CN"

# ---------- 写 keystore.properties ----------
# storeFile 写相对路径，构建脚本会相对 android/ 目录解析
cat > "$PROPS" <<EOF
# 由 tools/new-keystore.sh 生成 —— 请勿提交进 Git
storeFile=release.jks
storePassword=$PASS1
keyAlias=$ALIAS
keyPassword=$PASS1
EOF

# ---------- 校验 ----------
echo
echo "正在校验..."

# 用 -a 强制按文本处理，关键词只用 ASCII。
# 原因：Windows 中文控制台是 GBK 编码，grep 遇到 UTF-8 中文会判定为二进制，
# 直接回一句 "Binary file matches" 而不显示内容。
"$KEYTOOL" -list -v -keystore "$KEYSTORE_FOR_KEYTOOL" -storepass "$PASS1" 2>/dev/null \
    | grep -aE "Alias|Valid|SHA256|SHA-256|CN=" || true

echo
echo "============================================================"
echo " 完成。生成的文件："
echo "   $KEYSTORE"
echo "   $PROPS"
echo
echo " ⚠️  现在立刻做备份，这一步不能省："
echo "     把 release.jks 和 keystore.properties 一起复制到"
echo "     U 盘 / 云盘 / 密码管理器，至少两份不同介质。"
echo
echo "     原因：Android 只认「同一个密钥签名的包才能覆盖安装」。"
echo "     密钥丢了，你就再也无法给已发布的 App 推送更新，"
echo "     只能换包名重新上架，用户数据全部丢失。"
echo "============================================================"
