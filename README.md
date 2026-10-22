# my_mall

简历级全栈商城项目：覆盖电商核心链路（用户、商品、搜索、购物车、订单、秒杀、后台管理）+ AI 智能客服（LLM Function Calling + SSE 流式）。

- 前端：Vue 3 + TypeScript + Vite + Element Plus + Pinia
- 后端：Spring Boot 3.5 单体（Spring Security + JWT / MyBatis-Plus）
- 中间件：MySQL 8.0 / Redis 7 / RocketMQ 5.3 / Elasticsearch 8.15（Docker Compose 一键编排）

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 用户 | 注册 / 登录（JWT）、收货地址管理 |
| 商品 | 分类树（Redis 缓存）、商品详情、SKU 规格 |
| 搜索 | Elasticsearch + IK 分词，关键词高亮 |
| 购物车 | Redis 存储，登录用户购物车 |
| 订单 | 下单防重 Token、模拟支付、超时延迟消息自动关单与库存回补（RocketMQ） |
| 秒杀 | 库存预热到 Redis + Lua 原子扣减 + MQ 异步落库削峰，四层防超卖 |
| 后台管理 | 分类 / 商品 / SKU / 秒杀活动 / 订单管理、数据看板统计、AI 配置 |
| AI 客服 | DeepSeek LLM + Function Calling 查商品 / 订单，SSE 流式输出，限流 + 异步持久化 |

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│                        浏览器 / 用户                          │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP / JSON (统一 Result<T>)
┌──────────────────────────▼───────────────────────────────────┐
│  前端  Vue 3 + TypeScript + Vite + Element Plus + Pinia      │
│        （frontend/，dev 端口 5173，反向代理 /api）            │
└──────────────────────────┬───────────────────────────────────┘
                           │ REST API
┌──────────────────────────▼───────────────────────────────────┐
│  后端  Spring Boot 3.5 单体（backend/，端口 8080）            │
│        Spring Security + JWT · MyBatis-Plus · 参数校验        │
│        业务包：user / product / search / cart / order /       │
│               seckill / admin / ai                            │
└───┬──────────┬──────────────┬────────────────┬──────────────┘
    │          │              │                │
┌───▼───┐  ┌───▼────┐  ┌──────▼───────┐  ┌─────▼─────────┐
│ MySQL │  │ Redis  │  │  RocketMQ    │  │ Elasticsearch │
│  8.0  │  │   7    │  │ NS 9876      │  │ 8.15  9200    │
│ 3307  │  │ 6379   │  │ Broker 10911 │  │ Kibana 5601   │
└───────┘  └────────┘  │ Proxy  8081  │  └───────────────┘
                       └──────────────┘
```

- **MySQL**：业务数据（库名 `my_mall`，utf8mb4，11 张业务表）
- **Redis**：缓存 / 分布式锁 / 秒杀库存预热 / 购物车 / AI 限流（appendonly 开启）
- **RocketMQ**：订单超时关单（延迟消息）、秒杀削峰异步落库（自动建 Topic）
- **Elasticsearch**：商品搜索（IK 分词），启动时幂等建索引

## 目录结构

```
my_mall/
├── deploy/                  # 基础设施
│   ├── docker-compose.yml   # MySQL / Redis / RocketMQ / ES / Kibana 编排
│   ├── mysql/init/          # 首次启动自动执行：建表 + 种子数据
│   └── rocketmq/broker.conf
├── frontend/                # Vue3 + TS 前端
│   └── src/
│       ├── api/             # 接口契约（axios 封装：Token / Result 解包 / 401 跳登录）
│       ├── components/      # 通用组件（layout / user / ai ...）
│       └── views/           # 页面
├── backend/                 # Spring Boot 单体
│   └── src/main/java/com/mall/
│       ├── common/          # Result<T> / BizException / 全局异常处理
│       ├── config/          # MyBatis-Plus / Knife4j / Web 跨域
│       ├── security/        # JwtUtils / AuthInterceptor / LoginUser 上下文
│       ├── user/ product/ search/ cart/ order/ seckill/ admin/ ai/
│       └──                  # 每个业务包内：controller / service / mapper / entity / dto
├── docs/                    # 开发指南 / 中间件踩坑
└── scripts/                 # 秒杀压测等辅助脚本
```

## 快速开始

### 环境要求

- JDK 17+、Maven 3.8+
- Node.js 20+（建议 22+）
- Docker Desktop（Linux 容器模式）

### 1. 启动中间件

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps   # 等待全部 healthy
```

MySQL 首次启动会自动执行 `deploy/mysql/init/` 下的建库建表与种子数据 SQL。

### 2. 启动后端（端口 8080，context-path `/api`）

```bash
cd backend
mvn spring-boot:run
```

启动时自动创建 ES 索引、预热上线中秒杀活动库存到 Redis。接口文档：<http://localhost:8080/api/doc.html>

### 3. 启动前端（端口 5173，`/api` 代理到 8080）

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 <http://localhost:5173>。

### 演示账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `123456` | 管理员 |
| `user` | `123456` | 普通用户 |

> 秒杀演示：种子秒杀活动已过期，需用管理员登录后台「秒杀管理」新建活动（结束时间选未来）并上线、添加活动商品后即可抢购。

### AI 智能客服（可选）

- 设置环境变量 `MALL_AI_API_KEY`（DeepSeek API Key，`sk-` 开头）后重启后端；
- 开关 `mall.ai.enabled`，未启用时接口返回友好提示不报错；
- 限流：每用户每分钟 10 条（Redis 固定窗口）。

## 构建与测试

```bash
# 后端单测（backend/ 下）
mvn test

# 前端单测（frontend/ 下）
npm run test:unit

# 前端生产构建（含 type-check）
npm run build
```

## 演示动线

注册/登录 → 首页/分类浏览 → 搜索「小米」（关键词高亮）→ 商品详情选规格加购 → 购物车结算 → 提交订单（防重 Token）→ 我的订单「去支付」→ 订单详情查看快照；不支付等 30 秒可观察 RocketMQ 延迟消息自动关单与库存回补。

## 文档

- [docs/dev-guide.md](docs/dev-guide.md)：开发环境、常用命令、演示账号与 AI 客服配置
- [docs/middleware.md](docs/middleware.md)：中间件连接信息与踩坑要点（RocketMQ、ES IK 分词等）
- [AGENTS.md](AGENTS.md)：项目约定与进度
