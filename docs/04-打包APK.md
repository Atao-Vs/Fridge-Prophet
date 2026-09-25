# 04 · 打包签名 APK

这一章解决四件事：**生成签名密钥 → 本地打正式包 → 验证包没问题 → 用 GitHub Actions 自动打**。

前两件是必须做的，第三件是防止你装到手机上才发现问题，第四件是省时间用的。

---

## 0. 先搞清楚：为什么不能直接用 debug 包

你可能想问：`assembleDebug` 出来的 APK 也能装能跑，为什么还要折腾签名？

三个原因，每个都会在比赛现场咬你一口：

| 问题 | debug 包的后果 |
|---|---|
| **签名不同** | debug 包用系统自动生成的调试密钥签名，每台电脑的密钥都不一样。你把 debug 包发给评委，评委装上了；下次你换台电脑重新打包发过去，**覆盖安装会失败**，提示「应用签名不一致，请先卸载」——评委手机里的数据全没了。 |
| **包名带后缀** | 我们配置了 `applicationIdSuffix = ".debug"`，debug 包的真实包名是 `com.fridgeprophet.app.debug`。如果比赛要求提交包名，这个是错的。 |
| **体积大 6 倍** | debug 包 17.8 MB，release 包 2.8 MB。debug 不做代码混淆和资源压缩，还塞了一堆调试信息。 |

所以：**自己调试用 debug 包，交出去的一律用 release 包。**

---

## 1. 生成签名密钥（keystore）

签名密钥是一个 `.jks` 文件，相当于你的「公章」。Android 只认一件事：**同一个包名 + 同一个密钥签名 = 可以覆盖安装**。密钥丢了，你就再也无法给已发布的 App 推送更新。

### 方式一：双击（推荐）

在文件管理器里打开项目根目录，**双击 `make-keystore.bat`**。

弹出的黑窗口里会依次问你别名、组织名、密码，然后自动生成两个文件并做校验：

```
android/release.jks            密钥库本体
android/keystore.properties    密码配置，构建时自动读取
```

这两个文件都已经被 `.gitignore` 忽略，不会被提交。

> 为什么是 `.bat` 而不是 `.sh`：Windows 不能靠双击运行 `.sh` 文件。
> 这个 `.bat` 会自动寻找你机器上的 bash（优先用 WorkBuddy 内置的 PortableGit），
> 再调用 `tools/new-keystore.sh`。

### 方式二：命令行

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet"
bash tools/new-keystore.sh
```

和双击 `.bat` 效果完全一样。

### 方式三：手敲 keytool 命令

如果你想完全掌控每一步：

```bash
"G:/Android/jdk-17.0.20.1+1/bin/keytool.exe" -genkeypair \
  -keystore "G:/workbuddy/任务路径/Fridge-Prophet/android/release.jks" \
  -alias fridgeprophet \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 \
  -keypass 你的密码 \
  -dname "CN=FridgeProphet, OU=Mobile, O=FridgeProphet, L=Beijing, ST=Beijing, C=CN"
```

参数逐个解释：

| 参数 | 作用 | 为什么这么填 |
|---|---|---|
| `-keyalg RSA -keysize 2048` | 加密算法和长度 | Android 的标准选择。2048 位是安全下限，别用 1024 |
| `-validity 10000` | 有效期（天） | 约 27 年。Google Play 要求密钥有效期至少到 2033 年，10000 天足够 |
| `-storepass` / `-keypass` | 密钥库密码 / 密钥密码 | 填成一样，省得记两套。keytool 现在也推荐一致 |
| `-dname` | 证书里显示的信息 | 自签名证书，内容不影响功能，但**不能留空**，否则 keytool 会交互式追问 |
| `-alias` | 密钥别名 | 一个密钥库里可以有多个密钥，靠别名区分。记住这个名字，配置里要用 |

### ⚠️ 生成完立刻备份

这一步不能省。把 `release.jks` 和 `keystore.properties` **一起**复制到至少两个不同介质：

- 一个 U 盘
- 一个云盘（私有目录，别放公开分享链接）
- 或者密码管理器（Bitwarden / 1Password 支持存文件）

**两个文件必须放在一起**。只有密钥没有密码，等于没有；只有密码没有密钥，也等于没有。

---

## 2. 填 keystore.properties

如果你用了脚本，这步已经自动完成了。手敲命令的话，自己创建 `android/keystore.properties`：

```properties
storeFile=release.jks
storePassword=你的密码
keyAlias=fridgeprophet
keyPassword=你的密码
```

`storeFile` 支持两种写法：

- **相对路径** `release.jks` → 解析为 `android/release.jks`
- **绝对路径** `G:/keys/release.jks` → 密钥放在项目外也可以（更安全，不会误提交）

> 有个容易踩的坑已经帮你处理掉了：Gradle 的 `android { }` 块里 `file()` 是相对 `app/` 目录解析的，
> 而 `keystore.properties` 在 `android/` 下。构建脚本里用的是 `rootProject.file(...)`，
> 所以相对路径按 `android/` 解析，符合直觉。

---

## 3. 指定后端地址

**不要把服务器地址写死在代码里。** 我们的构建脚本支持三种注入方式，优先级从高到低：

```bash
# 1. 命令行 -P 参数（推荐，一次性）
./gradlew assembleRelease -PAPI_BASE_URL=https://api.your-domain.com/

# 2. 环境变量（CI 里用）
export API_BASE_URL=https://api.your-domain.com/

# 3. 写进 android/gradle.properties（本地长期固定用）
API_BASE_URL=https://api.your-domain.com/
```

都没配的话走默认值：

| 构建类型 | 默认地址 | 说明 |
|---|---|---|
| debug | `http://10.0.2.2:8000/` | `10.0.2.2` 是模拟器指向宿主机的固定地址，**真机连不上** |
| release | `https://api.example.com/` | 占位域名，不存在。打包时会打印警告提醒你 |

结尾的 `/` 不用管，脚本会自动补上。Retrofit 强制要求 baseUrl 以 `/` 结尾，少一个斜杠会在运行时直接抛异常。

### 真机调试怎么填

1. 电脑和手机连**同一个 WiFi**
2. 在电脑上查局域网 IP：
   ```bash
   ipconfig | grep -A 5 "无线局域网适配器 WLAN" | grep "IPv4"
   ```
   或者看 `192.168.x.x` / `10.x.x.x` 开头的那个地址
3. 用这个 IP 打包：
   ```bash
   ./gradlew installDebug -PAPI_BASE_URL=http://192.168.1.23:8000/
   ```
4. 后端要监听所有网卡，不能只听 localhost：
   ```bash
   cd backend
   uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
   ```
5. Windows 防火墙会拦 8000 端口。第一次跑如果手机连不上，去「Windows 防火墙 → 允许应用通过防火墙」放行 Python，或者临时关掉防火墙试一下。

---

## 4. 本地打 release 包

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet/android"
export JAVA_HOME="G:\Android\jdk-17.0.20.1+1"

"G:/Android/gradle-8.14.5/bin/gradle.bat" clean assembleRelease \
  -PAPI_BASE_URL=https://api.your-domain.com/ \
  --console=plain
```

跑完产物在这里：

```
android/app/build/outputs/apk/release/app-release.apk
```

首次构建大约 4 分钟。构建日志里要确认这几个关键任务**都出现了且没失败**：

```
> Task :app:kspReleaseKotlin              ← Hilt / 注解处理
> Task :app:compileReleaseKotlin          ← Kotlin 编译
> Task :app:hiltJavaCompileRelease        ← Hilt 代码生成
> Task :app:minifyReleaseWithR8           ← 代码混淆，最容易出问题的一步
> Task :app:packageRelease                ← 打包
BUILD SUCCESSFUL
```

> **为什么特别关注 `minifyReleaseWithR8`**：R8 会删掉「看起来没被调用」的代码。
> 但 Hilt 的依赖注入、kotlinx.serialization 的 JSON 解析、Retrofit 的接口代理
> 全靠反射和生成的代码工作，很容易被误删——表现为**编译通过，一打开 App 就崩**。
> `proguard-rules.pro` 里已经针对这三个库写了保留规则，本项目已验证通过。
> 但如果你后面新增了用反射的库，记得回来补规则。

---

## 5. 验证包没问题（重要，别跳过）

打完包先做三项检查，别急着装。

### 5.1 确认签名正确

```bash
# apksigner 是 Java 程序，和 Gradle 一样需要 JAVA_HOME。
# 不设的话会报 "JAVA_HOME is not set and no 'java' command could be found"，
# 这个报错看着像签名坏了，其实只是环境变量没设。
export JAVA_HOME="G:/Android/jdk-17.0.20.1+1"

"G:/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs \
  "G:/workbuddy/任务路径/Fridge-Prophet/android/app/build/outputs/apk/release/app-release.apk"
```

看到 `CN=FridgeProphet` 之类的信息就说明用的是你的密钥。如果显示 `CN=Android Debug`，说明 `keystore.properties` 没被读到，检查文件位置和格式。

### 5.2 确认地址注入正确

```bash
"G:/Android/Sdk/build-tools/36.0.0/aapt2.exe" dump strings \
  "G:/workbuddy/任务路径/Fridge-Prophet/android/app/build/outputs/apk/release/app-release.apk" \
  | grep -i "http"
```

能搜到你填的域名就对了。

### 5.3 装到真机上完整走一遍

```bash
"G:/Android/Sdk/platform-tools/adb.exe" install -r \
  "G:/workbuddy/任务路径/Fridge-Prophet/android/app/build/outputs/apk/release/app-release.apk"
```

**release 包一定要在真机上完整跑一遍九步链路**（注册 → 问卷 → 扫描 → 识别确认 → 生成菜谱 → 详情 → 采购 → 写回库存）。
R8 混淆造成的问题只在 release 包出现，debug 包测不出来。这一步是给比赛现场买的保险。

---

## 6. 用 GitHub Actions 自动打包

本地打包的缺点：占电脑 4 分钟、依赖你本机环境、没法一键给别人。

配好 CI 之后，推代码就自动出包，在网页上下载即可。

### 6.1 先确认仓库已初始化

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet"
git status
```

如果提示 `not a git repository`：

```bash
git init
git add .
git commit -m "初始化：冰箱先知"
```

**提交前务必检查一遍**：

```bash
git status --short | grep -iE "\.env|keystore|\.jks"
```

这条命令**必须没有任何输出**。如果有输出，说明密钥文件要被提交了，立刻停下来检查 `.gitignore`。

### 6.2 推到 GitHub

```bash
git remote add origin https://github.com/你的用户名/fridge-prophet.git
git branch -M main
git push -u origin main
```

### 6.3 配置仓库 Secrets

密钥不能明文放仓库里，要用 GitHub 的 Secrets 加密存储。

**路径**：仓库页面 → `Settings` → 左侧 `Secrets and variables` → `Actions` → `New repository secret`

需要配 4 个：

| Secret 名称 | 值 | 怎么得到 |
|---|---|---|
| `KEYSTORE_BASE64` | 密钥库的 base64 编码 | 见下方命令 |
| `KEYSTORE_PASSWORD` | 密钥库密码 | 你自己设的 |
| `KEY_ALIAS` | 密钥别名 | 例如 `fridgeprophet` |
| `KEY_PASSWORD` | 密钥密码 | 你自己设的 |

生成 `KEYSTORE_BASE64` 的值：

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet/android"
base64 -w 0 release.jks
```

> `-w 0` 是关键，表示不换行。Git Bash 自带的 `base64` 可能不支持 `-w`，
> 那就用：`base64 release.jks | tr -d '\n'`
>
> 复制输出时要**完整**，这是一个几千字符的长字符串。末尾不要多复制换行符。

### 6.4 触发构建

配好之后有三种触发方式：

- **推代码**：`git push` 到 main，且改动了 `android/` 目录
- **手动**：仓库页面 → `Actions` → 左侧 `Android 构建` → 右侧 `Run workflow`
  - 手动触发时可以填 `api_base_url`，指定这次打包用哪个后端地址
- **发 PR**：验证能不能编译过

构建完成后，在 `Actions` 里点进那次运行，页面底部 `Artifacts` 区域可以下载：

```
app-debug      →  app-debug.apk
app-release    →  app-release.apk   ← 这个是要交的
```

### 6.5 没配密钥会怎样

CI 里做了降级处理：如果 `KEYSTORE_BASE64` 没配，会自动退化成 debug 签名并打印提示，**构建仍然成功**。

这样设计是为了让 fork 你仓库的人不用密钥也能编译。但**你交比赛的包必须是配好密钥打出来的**，否则会出现前面说的「签名不一致无法覆盖安装」问题。

---

## 7. 常见问题

**`Keystore file not found for signing config 'release'`**

`keystore.properties` 里的 `storeFile` 路径不对。确认文件真的存在于你写的路径下。相对路径是按 `android/` 解析的。

**`Failed to read key xxx from store: Keystore was tampered with, or password was incorrect`**

密码错了。注意 `storePassword` 和 `keyPassword` 是两个不同的字段，虽然我们建议填成一样。另外检查有没有不小心带上了首尾空格。

**构建成功，但装到手机上打开就闪退**

九成是 R8 把反射用的类删了。排查步骤：

1. 先用 debug 包装一遍，如果 debug 正常、release 闪退，基本可以确认是混淆问题
2. 抓崩溃日志：
   ```bash
   "G:/Android/Sdk/platform-tools/adb.exe" logcat -d | grep -iE "FATAL|AndroidRuntime" | tail -50
   ```
3. 把日志里报的类名加进 `android/app/proguard-rules.pro`，例如：
   ```
   -keep class com.你新增的库.** { *; }
   ```
4. 重新打 release 包验证

**`API_BASE_URL` 打印出来还是占位域名**

检查你用的是 `-PAPI_BASE_URL=...` 还是环境变量。用 `-P` 时注意等号两边不能有空格，值里的 `/` 结尾可有可无。

**想让 debug 包和 release 包同时装在手机上**

已经配好了。debug 包的真实包名是 `com.fridgeprophet.app.debug`，和 release 包不冲突，可以共存。

---

## 8. 这一章做完，你应该有

- [ ] `android/release.jks` 密钥库文件
- [ ] `android/keystore.properties` 密码配置
- [ ] 密钥已备份到至少两个不同介质
- [ ] `app-release.apk` 本地构建成功，体积约 3.1 MB
- [ ] `apksigner verify` 显示的是你自己的证书信息
- [ ] release 包在真机上完整跑通九步链路
- [ ] 代码已推到 GitHub，且 `git status` 里没有密钥文件
- [ ] GitHub 仓库配好 4 个 Secrets
- [ ] Actions 里能成功跑出构建，Artifacts 里能下载 APK

### 现在这个包的签名状态

本章写作时仓库里还没有 `keystore.properties`，所以当前的 `app-release.apk` 是
**用 debug 密钥签的**。用 `apksigner verify` 看会显示：

```
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
```

你跑完 `bash tools/new-keystore.sh` 重新打包后，这里应该变成：

```
Signer #1 certificate DN: CN=FridgeProphet, OU=Mobile, O=FridgeProphet, L=Beijing, ST=Beijing, C=CN
```

**这是一个很好的验收锚点**——如果打完包这里还是 `Android Debug`，
说明 `keystore.properties` 没被正确读取，包不能用。

---

返回 [00 · 行动清单](./00-行动清单.md) 继续下一项。
