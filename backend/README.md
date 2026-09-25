# 冰箱先知 Fridge Prophet · 后端

FastAPI 后端，实现策划书里的完整闭环：

```
拍照 → AI识别食材 → 用户确认 → 写入冰箱库存 → 结合用户画像生成菜谱
     → 计算缺少食材 → 生成采购清单 → 买完写回库存
```

## 快速开始

```bash
cd backend

# 1. 装依赖（首次）
C:/Users/Atao/.workbuddy-ai/binaries/python/envs/fridge/Scripts/pip.exe install -r requirements.txt

# 2. 配置（可跳过，跳过则 AI 走 MOCK 模式）
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY

# 3. 启动
C:/Users/Atao/.workbuddy-ai/binaries/python/envs/fridge/Scripts/python.exe run.py
```

打开 http://127.0.0.1:8000/docs 就能看到全部接口并直接调试。

## 设计要点

**1. 规则负责约束，AI 负责生成**

模型只做「想菜名、写步骤、估营养」。**食材有没有、缺多少，全部由后端拿真实库存重算**
（`app/services/recipe_service.py::_recompute_availability`），不采信模型自报的
`available` 字段。这样才能杜绝「AI 说你有豆腐，其实冰箱里没有」。

**2. 采购计算不用 AI**

「需要 - 现有 = 缺多少」是纯算术，交给模型只会算错。
见 `app/services/shopping_service.py`，含单位归一化（kg→g、升→ml）和同类项合并。

**3. AI 输出必须结构化**

`app/services/ai_client.py` 强制 `response_format=json_object`，
并带三级容错解析（直接解析 → 剥离 ```json 围栏 → 截取首尾大括号）。

**4. 识别结果不直接入库**

`POST /vision/scan` 只返回候选结果，必须再调 `POST /inventory/confirm` 才写入。
这是策划书里反复强调的「用户确认机制」。

**5. 无密钥也能完整演示**

未配置 `DASHSCOPE_API_KEY` 时自动降级为 MOCK 模式，返回内置食材和菜谱，
整条链路照常跑通。比赛现场网络出问题时这是保命机制。

## 目录结构

```
backend/
├── app/
│   ├── main.py                 # 入口，CORS、异常处理、静态文件
│   ├── core/
│   │   ├── config.py           # 全部配置项（环境变量覆盖）
│   │   └── security.py         # bcrypt 哈希 + JWT 签发校验
│   ├── db/session.py           # 引擎、会话、建表
│   ├── models/                 # ORM：用户/库存/菜谱/采购
│   ├── schemas/                # Pydantic 出入参
│   ├── services/
│   │   ├── ai_client.py        # 通义千问调用封装 + JSON 容错解析
│   │   ├── vision_service.py   # AI 1 视觉识别
│   │   ├── recipe_service.py   # AI 2 菜谱生成 + 库存重算
│   │   └── shopping_service.py # AI 4 采购差集计算
│   └── api/
│       ├── deps.py             # 当前用户、DB 会话
│       └── v1/                 # auth / users / inventory / vision / recipes / shopping
├── requirements.txt
├── .env.example
└── run.py
```

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册，直接返回 token |
| POST | `/api/v1/auth/login` | 登录 |
| GET | `/api/v1/auth/me` | 当前用户 |
| GET | `/api/v1/users/profile` | 完整画像（启动时调） |
| PUT | `/api/v1/users/preference` | 保存饮食偏好 |
| PUT | `/api/v1/users/health` | 保存健康管理设置 |
| GET/POST/DELETE | `/api/v1/users/family` | 家庭成员 |
| GET | `/api/v1/inventory` | 全部食材（可按位置/分类筛选） |
| GET | `/api/v1/inventory/expiring` | 即将过期 |
| GET | `/api/v1/inventory/stats` | 首页概览数字 |
| POST | `/api/v1/inventory/confirm` | **确认 AI 识别结果，写入库存** |
| POST | `/api/v1/vision/scan` | **上传照片识别食材** |
| GET | `/api/v1/vision/status` | AI 是否已配置 |
| POST | `/api/v1/recipes/generate` | **生成菜谱（核心）** |
| GET | `/api/v1/recipes/{id}` | 菜谱详情（用当前库存重算缺料） |
| POST | `/api/v1/recipes/feedback` | 收藏/做过/跳过/评分 |
| GET | `/api/v1/recipes/insights/preference` | 行为推导出的口味偏好 |
| POST | `/api/v1/shopping/build` | **生成采购清单** |
| POST | `/api/v1/shopping/{id}/apply` | 买完写回库存 |

## 切到 PostgreSQL

改 `.env` 一行即可，模型层不用动：

```
DATABASE_URL=postgresql+psycopg://fridge:密码@127.0.0.1:5432/fridge_prophet
```
