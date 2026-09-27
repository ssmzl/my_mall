# AGENTS.md — my\_mall 全栈商城

## 1. 项目简介

`my_mall` 是一个**简历级全栈商城项目**：Vue3 + TypeScript 前端 + Spring Boot 单体后端 + MySQL / Redis / RocketMQ / Elasticsearch 中间件全家桶，覆盖电商核心链路（用户、商品、搜索、购物车、订单、秒杀、后台管理），用于学习演示与求职简历。

前端代码位于 `frontend/`（已存在），后端代码位于 `backend/`（按本文件约定逐步建设）。

## 2. 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        浏览器 / 用户                          │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP / JSON (统一 Result<T>)
┌──────────────────────────▼───────────────────────────────────┐
│  前端  Vue 3 + TypeScript + Vite + Element Plus + Pinia      │
│        （frontend/，dev 端口默认 5173，反向代理 /api）        │
└──────────────────────────┬───────────────────────────────────┘
                           │ REST API
┌──────────────────────────▼───────────────────────────────────┐
│  后端  Spring Boot 3.5 单体（backend/，端口 8080）            │
│        Spring Security + JWT · MyBatis-Plus · 参数校验        │
│        业务包：user / product / search / cart / order /       │
│               seckill / admin                                 │
└───┬──────────┬──────────────┬────────────────┬──────────────┘
    │          │              │                │
┌───▼───┐  ┌───▼────┐  ┌──────▼───────┐  ┌─────▼─────────┐
│ MySQL │  │ Redis  │  │  RocketMQ    │  │ Elasticsearch │
│  8.0  │  │   7    │  │ NS 9876      │  │ 8.15  9200    │
│ 3307  │  │ 6379   │  │ Broker 10911 │  │ Kibana 5601   │
└───────┘  └────────┘  │ Proxy  8081  │  └───────────────┘
                       └──────────────┘
- MySQL：业务数据（库名 my_mall，utf8mb4）
- Redis：缓存 / 分布式锁 / 秒杀库存预热（appendonly 开启）
- RocketMQ：订单超时关单、秒杀削峰等异步消息（自动建 Topic）
- ES：商品搜索（IK 分词，见 docs/middleware.md）
```

所有中间件由 `deploy/docker-compose.yml` 统一编排（项目名 `my-mall`，网络 `my-mall-net`，数据用命名卷持久化）。

## 3. 技术栈与版本

| 层  | 技术                     | 版本                         |
| -- | ---------------------- | -------------------------- |
| 后端 | JDK                    | 17                         |
| 后端 | Spring Boot            | 3.5.x                      |
| 后端 | MyBatis-Plus           | 3.5.x                      |
| 后端 | Spring Security + JWT  | 6.x（随 Boot）                |
| 前端 | Vue                    | 3.5                        |
| 前端 | TypeScript / Vite      | 5.x / 随 frontend           |
| 前端 | Element Plus           | 2.x                        |
| 数据 | MySQL                  | 8.0                        |
| 缓存 | Redis                  | 7                          |
| 消息 | RocketMQ               | 5.3（apache/rocketmq:5.3.2） |
| 搜索 | Elasticsearch / Kibana | 8.15（8.15.3，两者版本必须一致）      |

## 4. 目录结构约定

```
my_mall/
├── AGENTS.md                # 本文件（核心约定与进度）
├── .trae/specs/             # 功能规格（每个子目录含 spec.md / tasks.md / checklist.md / progress.md）
│   ├── init-mall-project/   #   初始全栈商城（已交付，进度见 progress.md）
│   └── ai-customer-service/ #   AI 智能客服（规划中）
├── docs/                    # 辅助文档（见第 8 节，Agent 按需阅读）
│   ├── dev-guide.md         #   开发环境、常用命令、演示账号
│   └── middleware.md        #   中间件连接信息与踩坑要点
├── deploy/                  # 基础设施
│   ├── docker-compose.yml   # 中间件编排（MySQL/Redis/RocketMQ/ES/Kibana）
│   ├── mysql/init/          # MySQL 首次启动自动执行的 SQL
│   │   ├── 01-schema.sql    #   建库建表
│   │   └── 02-seed.sql      #   种子数据
│   └── rocketmq/
│       └── broker.conf      # Broker 配置（autoCreateTopicEnable=true 等）
├── frontend/                # Vue3 + TS 前端（已存在）
└── backend/                 # Spring Boot 后端（单体，按业务包划分）
    └── src/main/java/com/mall/
        ├── common/          # Result<T>/PageResult、BizException、全局异常处理、HealthController
        ├── config/          # MyBatis-Plus / Knife4j / Web（跨域）等配置
        ├── security/        # JwtUtils、AuthInterceptor（登录/管理员校验）、LoginUser 上下文
        ├── user/            # 用户、地址（ums_*）—— 已开发
        ├── product/         # 分类、商品、SKU（pms_*）—— 已开发
        ├── search/          # ES 商品搜索 —— 已开发
        ├── cart/            # 购物车（Redis）—— 已开发
        ├── order/           # 订单（oms_*）—— 已开发
        ├── seckill/         # 秒杀（sms_*）—— 已开发
        └── admin/           # 后台管理接口 —— 已开发
```

每个业务包内部分层：`controller` / `service`（接口 + `service/impl` 实现）/ `mapper` / `entity` / `dto`。

## 5. 编码规范

- **Java**：驼峰命名；类 UpperCamelCase，方法/字段 lowerCamelCase；常量 UPPER\_SNAKE\_CASE。
- **统一返回**：所有 Controller 返回 `Result<T>`（code / message / data），不直接裸返回实体。
- **分层**：Service 一律「接口 + 实现类」（`XxxService` + `XxxServiceImpl`），Controller 不直接依赖 Mapper。
- **异常**：严格抛出业务异常（如 `BizException`），由全局异常处理器转 `Result`；**严禁空 catch 吞异常**。
- **持久层**：默认使用 MyBatis-Plus（`BaseMapper` + `QueryWrapper`/`LambdaQueryWrapper`）；只有复杂多表/报表查询才允许手写 XML。
- **SQL/表**：主键 `BIGINT AUTO_INCREMENT`；库存类字段 `INT UNSIGNED` 非负；金额 `DECIMAL(10,2)`；统一 `create_time` / `update_time`。
- **前端**：Composition API + `<script setup lang="ts">`；接口统一走封装的 axios 实例。

### 5.1 前端组件与样式约定

- **通用组件放** **`frontend/src/components/`，页面放** **`views/`**：多个页面共用的 UI（导航栏、页脚、表单外壳等）必须抽成组件，**不允许在页面里复制粘贴同一份模板/样式**。
  - `components/layout/`：全局布局组件（`AppHeader.vue` 顶部导航、`AppFooter.vue` 页脚），由 `App.vue` 统一引入。
  - `components/user/`：用户模块共用组件（如 `AuthShell.vue` 登录/注册页外壳）。
  - 后续若新增共用组件（如商品卡片、价格组件），按业务域建子目录存放。
- **跨组件通信**：页面 → 头部这类「非父子」刷新场景，用 `defineExpose` 暴露方法 + 父组件（App.vue）转发事件（如购物车角标 `refreshCartCount`），不引入事件总线。
- **主题色**：品牌色等色板统一定义在 `assets/main.css` 的 `:root` CSS 变量（`--mall-brand` 等），组件内用 `var()` 引用，不要硬编码色值。
- **图标**：Element Plus 图标已在 `main.ts` 全局注册，模板里直接 `<el-icon><Xxx /></el-icon>`；字符串形式传 prop 时写 `:icon="'Xxx'"`。

### 5.2 踩坑记录与一致性约定

> 后端实现 / bug 修复过程中沉淀的通用约定，新写接口或排查同类问题时先对照本节。

- **LocalDateTime 反序列化格式**：后端 DTO 的 `LocalDateTime` 字段必须显式加 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")`，否则 Jackson 默认只接受 ISO 格式（带 `T` 分隔符），与前端 Element Plus 日期选择器发送的空格分隔格式冲突，会报解析错误。
- **缓存删除必须在事务提交后执行**：事务方法内删除 Redis 缓存时，必须用 `TransactionSynchronizationManager.registerSynchronization` 的 `afterCommit` 回调删除（无事务上下文时直接删），否则「删缓存 → 提交」窗口内并发读请求会读到未提交旧数据并回写缓存，造成长期脏数据（参见 `AdminProductServiceImpl#evictDetailCacheAfterCommit`）。同理，事务内发 MQ 消息也应放到 afterCommit。
- **管理端与 C 端列表接口分离**：C 端列表接口只返回在线/有效数据（如秒杀活动只查「已上线且未结束」）。新增数据若默认下线/禁用，C 端接口查不到属正常现象，管理端必须提供独立的全量列表接口（如 `GET /admin/seckill/activity/list` 返回含下线/已结束活动），后台配置页一律调管理端接口，不能复用 C 端接口。
- **前端增删改后刷新受影响的列表**：操作会级联影响其他已展示数据时，前端必须主动刷新对应列表（如管理端 SKU 增删改会重算商品展示价/库存，保存后除刷新 SKU 弹层外还需刷新主商品列表），不能假设后端返回的提示信息够用。
- **接口路径必须与前端契约逐字对齐**：后端 Controller 的 `@RequestMapping` 基路径 + 方法路径拼出来的完整路径，要与前端 `src/api/*.ts` 中定义的完全一致（曾因基路径 `/admin/product` 下写 `/sku/{id}`、前端只拼了 `/admin/sku/{id}` 导致 404）。修改路径时前后端同步改。

## 6. Git 约定

- 远程仓库：`git@github.com:ssmzl/my_mall.git`（main 分支）。
- 除非用户明确要求，**默认不执行任何 git 操作**（commit / push / branch / merge 等一律先征得同意）。

## 7. 当前进度

> **进度管理约定（必须遵守）**：每完成一个任务（或子任务），必须同步更新两处，缺一不可：
>
> 1. 对应 spec 目录（`.trae/specs/<spec-name>/`）下的 `tasks.md` —— 勾选对应任务/子任务，并补注实现要点（接口路径、关键设计决策、测试情况）；
> 2. 对应 spec 目录下的 `progress.md` —— 更新进度清单；若实现过程产生了新的约定、踩坑记录或中间件注意事项，约定补进本文件对应章节，中间件踩坑补进 `docs/middleware.md`。
>    两者更新后才算该任务完成。

- [x] **init-mall-project**：初始全栈商城全部完成并交付（基础设施 / 后端骨架 / 用户 / 商品 / ES 搜索 / 购物车 / 订单 / 秒杀 / 后台管理 / 前端 / 联调，`mvn test` 全绿 163/163、`npm run build` 通过）。详细进度清单与实现要点见 [.trae/specs/init-mall-project/progress.md](.trae/specs/init-mall-project/progress.md)
- [ ] **ai-customer-service**：AI 智能客服（LLM + Function Calling + SSE 流式）—— Task 1、2、3 完成（建表 + `mall.ai` 配置 + `OpenAiClient` 流式 LLM 客户端 + `AiToolRegistry`/`AiToolExecutor` Function Calling + `ChatService` 上下文/工具编排 + `/api/ai/chat/stream` SSE 接口 + 限流 + 异步持久化，详见 [.trae/specs/ai-customer-service/progress.md](.trae/specs/ai-customer-service/progress.md)），规格见 [.trae/specs/ai-customer-service/](.trae/specs/ai-customer-service/)

## 8. 辅助文档（按需阅读）

Agent 在执行对应类型的任务前，**必须先阅读**下表中的文档：

| 文档                                       | 内容                                                    | 何时阅读                              |
| ---------------------------------------- | ----------------------------------------------------- | --------------------------------- |
| [docs/dev-guide.md](docs/dev-guide.md)   | 环境要求、常用命令（docker compose / mvn / npm）、演示账号            | 启动服务、执行构建/测试命令、需要登录演示账号时          |
| [docs/middleware.md](docs/middleware.md) | 中间件连接信息（MySQL 3307 等）、RocketMQ 踩坑与修复、ES IK 分词器安装与降级策略 | 连接中间件、排障中间件启动失败、开发 search 模块（ES）时 |

新增踩坑记录/约定时优先归入上表文档，避免本文件继续膨胀；本文件只保留高频核心约定与进度。
