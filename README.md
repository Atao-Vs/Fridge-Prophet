# 冰箱先知 Fridge Prophet

> 拍一张冰箱照片，AI 自动知道「有什么、还能吃多久、今天吃什么、缺什么」，
> 并根据不同用户的饮食目标生成个性化菜谱和采购清单。

参赛项目。本仓库包含后端服务与 Android 客户端。

---

## 一、当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 阶段一 | FastAPI 后端（数据模型 / 接口 / AI 接入 / 采购算法） | ✅ **已完成，60 项端到端测试全部通过** |
| 阶段二 | Android 客户端（Kotlin + Compose） | ✅ **已完成，debug / release 均构建通过** |
| 阶段三 | 后端部署到公网 | ⏳ 待开始（等云账号） |
| 阶段四 | 打包签名 APK | 🔄 **签名脚本与 CI 已就绪，待生成密钥** |

> **不知道从哪下手？先看 [`docs/00-行动清单.md`](docs/00-行动清单.md)。**
> 那份文档把全部待办按依赖顺序排好了，并标注了哪一步需要你亲自做。
>
> **想先确认后端是活的？** 跑起服务后浏览器打开 <http://127.0.0.1:8000/docs>，
> 按 [`docs/05-用接口文档跑通全链路.md`](docs/05-用接口文档跑通全链路.md) 点一遍，
> 不用装 Android 就能验证「识别 → 入库 → 菜谱 → 采购」整条链路。

## 二、技术选型

| 层 | 选型 | 说明 |
|---|---|---|
| 客户端 | Kotlin + Jetpack Compose + MVVM | 原生 Android，与策划书一致 |
| 后端 | Python 3.13 + FastAPI + SQLAlchemy 2.0 | 承载全部 AI 编排与业务规则 |
| 数据库 | Supabase 托管 PostgreSQL | 免运维；开发期自动退回 SQLite |
| 图片存储 | Supabase Storage | 冰箱照片，免费 1GB，自带 CDN |
| AI | 通义千问 Qwen-VL（百炼平台） | 视觉识别 + 菜谱生成，国内直连 |
| 部署 | 腾讯云轻量（中国香港节点） | 免备案，国内访问快 |

### 架构

```
┌─────────────────┐
│  Android App    │  Kotlin + Compose
│  (拍照/库存/菜谱) │
└────────┬────────┘
         │  HTTPS + JWT
         ▼
┌─────────────────┐
│  FastAPI 后端    │  ← 规则系统负责约束
│  ├ 视觉识别接口   │     AI 负责理解和生成
│  ├ 菜谱生成接口   │
│  ├ 库存/采购计算  │  ← 纯确定性算法，不用 AI
│  └ 用户画像       │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐ ┌──────────────┐
│Supabase│ │通义千问 Qwen-VL│
│ PG+存储 │ │  视觉 + 语言   │
└────────┘ └──────────────┘
```

## 三、目录结构

```
Fridge-Prophet/
├── README.md                  ← 你在这里
├── start-backend.bat          ← 【双击这个】一键启动后端，不用会命令行
├── .gitignore                 ← 已排除 .env / 签名密钥 / 构建产物
├── .github/workflows/
│   └── android.yml            ← 推代码自动构建，产物在 Actions 页面下载
├── docs/                      ← 每一步的详细操作指南
│   ├── 00-行动清单.md          ← 从这里开始：全部待办按依赖排序
│   ├── 01-账号注册.md          ← Supabase / 百炼 / 腾讯云 怎么开
│   ├── 02-本地运行.md          ← 怎么在本地把后端和 App 都跑起来
│   ├── 03-部署到公网.md        ← 服务器 + Nginx + systemd + HTTPS
│   └── 04-打包APK.md           ← 签名密钥 / release 构建 / GitHub Actions
├── tools/
│   ├── start-backend.sh       ← 后端启动脚本（.bat 实际调用的就是它）
│   └── new-keystore.sh        ← 一键生成签名密钥并写好配置
├── backend/                   ← 后端服务（已完成，60 项测试全过）
│   ├── app/
│   │   ├── main.py
│   │   ├── core/              config.py（全部配置）· security.py（JWT）
│   │   ├── db/                引擎与会话
│   │   ├── models/            ORM：用户 / 库存 / 菜谱 / 采购（11 张表）
│   │   ├── schemas/           Pydantic 出入参
│   │   ├── services/          AI 调用 · 视觉识别 · 菜谱生成 · 采购计算 · 图片存储
│   │   └── api/v1/            auth / users / inventory / vision / recipes / shopping
│   ├── scripts/               check_supabase.py · check_ai.py · smoke_http.py
│   ├── tests/test_e2e.py      60 项端到端测试
│   ├── requirements.txt
│   ├── .env.example
│   └── run.py
└── android/                   ← Android 客户端（Kotlin + Compose）
    ├── keystore.properties.example  签名配置模板（复制成 keystore.properties 用）
    ├── gradle/libs.versions.toml   版本目录（全部版本已逐一验证存在）
    ├── app/build.gradle.kts        构建配置 · 签名配置 · 后端地址注入
    └── app/src/main/java/com/fridgeprophet/app/
        ├── core/            令牌存储 · 统一错误封装 · 跨页刷新总线
        ├── data/
        │   ├── remote/      DTO · Retrofit 接口 · OkHttp 客户端
        │   └── repository/  五个仓库，界面只跟它们打交道
        ├── di/              Hilt 模块
        └── ui/
            ├── components/  复用组件（卡片 / 标签 / 空状态）
            ├── theme/       白绿橙品牌色
            └── screens/     splash · auth · onboarding · main(5 Tab)
                             home · fridge · scan · recipes · shopping · profile
```

## 四、后端已完成的能力

**AI 模块（策划书第十四节）**

| 模块 | 实现位置 | 说明 |
|---|---|---|
| AI 1 视觉识别 | `services/vision_service.py` | 照片 → 食材名称/数量/单位/置信度 |
| AI 2 菜谱生成 | `services/recipe_service.py` | 库存 + 画像 → 结构化菜谱 JSON |
| AI 3 用户画像 | `api/v1/recipes.py` | 记录点击/收藏/烹饪/跳过，推导口味偏好 |
| AI 4 采购规划 | `services/shopping_service.py` | 需要 − 现有 = 缺少，自动合并同类项 |

**三个关键设计决策**

1. **规则负责约束，AI 负责生成。** 模型只写菜名、步骤、营养估算；
   「食材有没有、缺多少」由后端拿真实库存重算（`_recompute_availability`），
   不采信模型自报的 `available` 字段。这样才不会出现「AI 说你有豆腐，其实冰箱里没有」。

2. **采购计算不用 AI。** 「需要 − 现有 = 缺多少」是纯算术，交给模型只会算错。
   含单位归一化（kg→g、升→ml）和跨菜谱同类项合并。

3. **识别结果不直接入库。** `POST /vision/scan` 只返回候选，
   必须再调 `POST /inventory/confirm` 才写入。这是策划书强调的用户确认机制。

**无密钥也能完整演示。** 未配置 `DASHSCOPE_API_KEY` 时自动降级为 MOCK 模式，
返回内置食材与菜谱，整条链路照常跑通。比赛现场网络出问题时这是保命机制。

## 五、路线图

### 现在 → 你要做的第一件事

**先跑本地验证，不需要任何账号。**

**最简单的做法：在文件管理器里找到项目根目录，双击 `start-backend.bat`。**

> 所谓「项目根目录」就是包含 `README.md`、`backend`、`android`、`tools` 这几个东西的文件夹，
> 也就是本文件所在的位置。不需要打开任何终端。

双击后会弹出一个黑窗口，脚本自动完成：建虚拟环境 → 装依赖 → 复制配置 →
打印你的局域网 IP → 启动服务。首次运行需要 1-3 分钟装依赖，之后几秒就好。

窗口里出现这两行就成功了：

```
  接口文档: http://127.0.0.1:8000/docs
  健康检查: http://127.0.0.1:8000/health
```

浏览器打开 <http://127.0.0.1:8000/docs> 就能看到全部接口。
**这个窗口不要关**，关掉服务就停了。按 `Ctrl+C` 可以停止。

<details>
<summary>如果你习惯用命令行（点击展开）</summary>

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet"    # 进入项目根目录
bash tools/start-backend.sh
```

`start-backend.bat` 做的事和这条命令完全一样，只是帮你把「打开终端、cd 到目录」这两步省掉了。

手动分步执行：

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet/backend"

# 建虚拟环境 —— 这步不能省
C:/Users/Atao/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe -m venv .venv

# 装依赖
.venv/Scripts/python.exe -m pip install -r requirements.txt

# 复制配置
cp .env.example .env

# 启动
.venv/Scripts/python.exe run.py
```

</details>

> **别直接敲 `python run.py`。** 系统 Python 里没有这些依赖，
> 会报 `ModuleNotFoundError: No module named 'uvicorn'`。
> 必须用虚拟环境里的解释器 `.venv/Scripts/python.exe`。

`DASHSCOPE_API_KEY` 留空会自动进 MOCK 模式，`DATABASE_URL` 留空自动用 SQLite。
**零配置就能把整条链路走通** —— 这是刻意的设计，让你在花一分钱之前先确认产品是通的。

**然后并行开三个账号**（详细步骤见 [`docs/01-账号注册.md`](docs/01-账号注册.md)）：

- [ ] **Supabase** — 托管数据库 + 图片存储
- [ ] **阿里云百炼** — 通义千问 API Key
- [ ] **腾讯云** — 轻量服务器（买**中国香港**节点，免备案）

> 完整排序、时间估算、以及哪一步会卡住哪一步，见 [`docs/00-行动清单.md`](docs/00-行动清单.md)。

### 阶段二：Android 客户端 —— ✅ 完成

```
assembleDebug    BUILD SUCCESSFUL in 1m 30s   →  app-debug.apk    17.8 MB
assembleRelease  BUILD SUCCESSFUL in 4m 11s   →  app-release.apk   2.8 MB
```

release 包体积只有 debug 的 **16%**，因为开了 R8 代码混淆 + 资源压缩。
这一步提前验证掉了一个高风险项：R8 很容易把 Hilt 的依赖注入、
kotlinx.serialization 的 JSON 解析这类「靠反射工作」的代码误删，
典型症状是**编译通过、一打开 App 就崩**。`proguard-rules.pro` 里的保留规则已验证有效。

已落地：

1. ✅ 工具链装到 `G:/Android/`：JDK 17 + Gradle 8.14.5 + SDK platform-36 / build-tools 36.0.0
2. ✅ Kotlin + Compose + Hilt + Retrofit + CameraX 工程骨架，含 Gradle Wrapper
3. ✅ 5 个 Tab 全部接上真实接口：首页 / 冰箱 / 菜谱 / 采购 / 我的
4. ✅ CameraX 拍照 → 上传 → 识别结果确认页（可改名、改数量、手动补录）
5. ✅ 启动页自动判断去向 + 登录注册 + 5 步引导问卷
6. ✅ 菜谱详情页：食材齐全度标记、烹饪步骤、营养估算（带免责声明）、收藏 / 做过 / 跳过
7. ✅ 采购清单：勾选、乐观更新、一键写回冰箱

#### 为什么依赖版本看起来这么「旧」

四个版本被三条约束连锁锁死，**动一个就要重验整条链**：

| 约束 | 结论 |
|---|---|
| KSP 的 Kotlin 版本上限 | Kotlin 卡在 **2.2.21**（KSP 只发到 `2.2.21-2.0.5`） |
| AGP 9 默认开启的 `android.newDsl` 与标准 Kotlin 插件冲突 | AGP 卡在 **8.13.2**（8.x 最后一代） |
| AGP 8.13.2 的 compileSdk 上限是 36 | 所有 AndroidX 库必须用 compileSdk 36 时代的版本 |

由此推出的两个具体取舍：

- **Hilt 停在 2.58**。Dagger 2.59 给 Hilt Gradle 插件加了 AGP 9 支持，并明确声明
  「AGP 9 is now a requirement」；2.59.1 更把最低 AGP 硬设为 9.0.0。
  而升 AGP 9 会连锁要求 Gradle 9.1+ 和更高 Kotlin —— 正好撞上上面两条约束。
- **hilt-navigation-compose 停在 1.3.0**。1.4.0 传递依赖
  `lifecycle-viewmodel-compose:2.11.0`，而 2.11.0 要求 compileSdk 37 + AGP 9.1.0。
  1.3.0 依赖的是 2.9.1，正好落在 compileSdk 36 的能力范围内。

还有一个 Windows 特有的坑：项目路径 `G:\workbuddy\任务路径\...` 含中文，
AGP 默认拒绝在非 ASCII 路径下构建，已在 `android/gradle.properties` 里用
`android.overridePathCheck=true` 放行。

> 想升级这套版本时，请从 `android/gradle/libs.versions.toml` 顶部的注释开始读——
> 那里记了每条约束的原因。

### 阶段三：部署到公网 —— ⏳ 等云账号

按 [`docs/03-部署到公网.md`](docs/03-部署到公网.md) 走：

1. 买腾讯云轻量**中国香港**节点（内地节点要 ICP 备案，7-20 个工作日，比赛等不起）
2. 服务器装 Python 3.13 + Nginx，代码上传后建虚拟环境装依赖
3. systemd 守护进程，配开机自启 + 崩溃自动重启
4. Nginx 反向代理 + certbot 上 HTTPS

> **两个已预判的坑**：
> ① Supabase 免费版闲置 7 天会暂停项目，演示前最致命 —— `docs/03` 里给了保活 cron，部署完就配。
> ② Android 从 API 28 起默认禁止明文 HTTP，release 包**必须**走 HTTPS，否则请求全被系统拦掉。

### 阶段四：打包签名 APK —— 🔄 签名与 CI 已就绪

```bash
# 1. 生成密钥（一次性）
bash tools/new-keystore.sh

# 2. 打正式包
cd android
export JAVA_HOME="G:\Android\jdk-17.0.20.1+1"
"G:/Android/gradle-8.14.5/bin/gradle.bat" assembleRelease \
  -PAPI_BASE_URL=https://你的正式域名/
```

已完成的部分：

- ✅ release 构建链路验证通过（含 R8 混淆）
- ✅ `tools/new-keystore.sh` 一键生成密钥并写好 `keystore.properties`
- ✅ 后端地址改为**可注入**（`-PAPI_BASE_URL` / 环境变量 / `gradle.properties`），
  不再硬编码在代码里 —— 换服务器不用改代码，CI 也能注入
- ✅ GitHub Actions 流水线（`.github/workflows/android.yml`），推代码自动出包
- ✅ 打 release 包时若未注入地址会打印警告，避免打出连不上后端的包

完整步骤见 [`docs/04-打包APK.md`](docs/04-打包APK.md)。

## 六、快速验证（现在就能跑）

**后端：**

```bash
# 一条命令：建环境 + 装依赖 + 复制配置 + 打印局域网 IP + 启动
bash tools/start-backend.sh
```

启动后打开这个地址：http://127.0.0.1:8000/docs —— 这是 FastAPI 自动生成的交互式接口文档，
所有接口都能直接在网页上点着调试，不用装 Postman。

**两套测试，测的不是一回事：**

```bash
cd backend

# ① 进程内测试（60 项）：直接调应用，不起网络栈，快
.venv/Scripts/python.exe tests/test_e2e.py

# ② HTTP 冒烟测试（37 项）：打真实运行中的服务，走网络栈
.venv/Scripts/python.exe scripts/smoke_http.py

# 部署后验线上（只有这个能发现 Nginx 配错、证书没生效）
.venv/Scripts/python.exe scripts/smoke_http.py --base-url https://你的域名
```

**Android：**

```bash
cd "G:/workbuddy/任务路径/Fridge-Prophet/android"

export JAVA_HOME="G:\Android\jdk-17.0.20.1+1"

# 打 debug 包。默认后端地址是 10.0.2.2:8000（模拟器专用），
# 真机调试要换成电脑的局域网 IP：
"G:/Android/gradle-8.14.5/bin/gradle.bat" installDebug \
  -PAPI_BASE_URL=http://192.168.1.23:8000/

# 产物
ls app/build/outputs/apk/debug/app-debug.apk
```

后端地址支持三种注入方式，优先级从高到低：

| 方式 | 写法 | 适用场景 |
|---|---|---|
| 命令行参数 | `-PAPI_BASE_URL=https://...` | 临时切换，推荐 |
| 环境变量 | `export API_BASE_URL=https://...` | CI 流水线 |
| 配置文件 | 写进 `android/gradle.properties` | 本地长期固定 |

都不配则用默认值：debug 是 `http://10.0.2.2:8000/`，release 是占位域名 `https://api.example.com/`。
结尾的 `/` 会自动补上，不用自己加。

> 为什么不做成硬编码：换服务器地址是部署阶段的日常操作，写死在代码里会导致
> 地址被提交进 Git、CI 无法注入、别人 clone 下来指向你的服务器。
