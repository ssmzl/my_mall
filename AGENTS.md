# AGENTS.md — my_mall 全栈商城

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

| 层 | 技术 | 版本 |
| --- | --- | --- |
| 后端 | JDK | 17 |
| 后端 | Spring Boot | 3.5.x |
| 后端 | MyBatis-Plus | 3.5.x |
| 后端 | Spring Security + JWT | 6.x（随 Boot） |
| 前端 | Vue | 3.5 |
| 前端 | TypeScript / Vite | 5.x / 随 frontend |
| 前端 | Element Plus | 2.x |
| 数据 | MySQL | 8.0 |
| 缓存 | Redis | 7 |
| 消息 | RocketMQ | 5.3（apache/rocketmq:5.3.2） |
| 搜索 | Elasticsearch / Kibana | 8.15（8.15.3，两者版本必须一致） |

## 4. 目录结构约定

```
my_mall/
├── AGENTS.md                # 本文件（核心约定与进度）
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
        ├── user/            # 用户、地址（ums_*）—— 待开发
        ├── product/         # 分类、商品、SKU（pms_*）—— 待开发
        ├── search/          # ES 商品搜索 —— 待开发
        ├── cart/            # 购物车（Redis）—— 待开发
        ├── order/           # 订单（oms_*）—— 待开发
        ├── seckill/         # 秒杀（sms_*）—— 待开发
        └── admin/           # 后台管理接口 —— 待开发
```

每个业务包内部分层：`controller` / `service`（接口 + `service/impl` 实现）/ `mapper` / `entity` / `dto`。

## 5. 编码规范

- **Java**：驼峰命名；类 UpperCamelCase，方法/字段 lowerCamelCase；常量 UPPER_SNAKE_CASE。
- **统一返回**：所有 Controller 返回 `Result<T>`（code / message / data），不直接裸返回实体。
- **分层**：Service 一律「接口 + 实现类」（`XxxService` + `XxxServiceImpl`），Controller 不直接依赖 Mapper。
- **异常**：严格抛出业务异常（如 `BizException`），由全局异常处理器转 `Result`；**严禁空 catch 吞异常**。
- **持久层**：默认使用 MyBatis-Plus（`BaseMapper` + `QueryWrapper`/`LambdaQueryWrapper`）；只有复杂多表/报表查询才允许手写 XML。
- **SQL/表**：主键 `BIGINT AUTO_INCREMENT`；库存类字段 `INT UNSIGNED` 非负；金额 `DECIMAL(10,2)`；统一 `create_time` / `update_time`。
- **前端**：Composition API + `<script setup lang="ts">`；接口统一走封装的 axios 实例。

### 5.1 前端组件与样式约定

- **通用组件放 `frontend/src/components/`，页面放 `views/`**：多个页面共用的 UI（导航栏、页脚、表单外壳等）必须抽成组件，**不允许在页面里复制粘贴同一份模板/样式**。
  - `components/layout/`：全局布局组件（`AppHeader.vue` 顶部导航、`AppFooter.vue` 页脚），由 `App.vue` 统一引入。
  - `components/user/`：用户模块共用组件（如 `AuthShell.vue` 登录/注册页外壳）。
  - 后续若新增共用组件（如商品卡片、价格组件），按业务域建子目录存放。
- **跨组件通信**：页面 → 头部这类「非父子」刷新场景，用 `defineExpose` 暴露方法 + 父组件（App.vue）转发事件（如购物车角标 `refreshCartCount`），不引入事件总线。
- **主题色**：品牌色等色板统一定义在 `assets/main.css` 的 `:root` CSS 变量（`--mall-brand` 等），组件内用 `var()` 引用，不要硬编码色值。
- **图标**：Element Plus 图标已在 `main.ts` 全局注册，模板里直接 `<el-icon><Xxx /></el-icon>`；字符串形式传 prop 时写 `:icon="'Xxx'"`。

## 6. Git 约定

- 远程仓库：`git@github.com:ssmzl/my_mall.git`（main 分支）。
- 除非用户明确要求，**默认不执行任何 git 操作**（commit / push / branch / merge 等一律先征得同意）。

## 7. 当前进度

> **进度管理约定（必须遵守）**：每完成一个任务（或子任务），必须同步更新两处，缺一不可：
> 1. `.trae/specs/init-mall-project/tasks.md` —— 勾选对应任务/子任务，并补注实现要点（接口路径、关键设计决策、测试情况）；
> 2. 本文件（`AGENTS.md`）—— 更新第 7 节「当前进度」清单；若实现过程产生了新的约定、踩坑记录或中间件注意事项，约定补进对应章节，中间件踩坑补进 `docs/middleware.md`。
> 两者更新后才算该任务完成。

- [x] 基础设施：`deploy/docker-compose.yml`（MySQL 8 / Redis 7 / RocketMQ 5 / ES 8.15 / Kibana）+ 初始化 SQL + 种子数据，已验证可连通（中间件踩坑详见 `docs/middleware.md`）
- [x] 后端骨架：`backend/`（包名 `com.mall`）—— Result/PageResult、全局异常、JWT 工具 + 登录拦截器、Knife4j、application-dev.yml（MySQL 走 3307）、JwtUtilsTest / ResultTest
- [x] 用户模块：`com.mall.user` —— 注册/登录/登出（BCrypt、JWT + Redis 会话 `mall:token:{token}`）、收货地址 CRUD（默认地址唯一由应用层事务保证、归属校验防越权）；接口前缀 `/api/user`、`/api/address`（详见 tasks.md Task 3）；UserServiceImplTest / AddressServiceImplTest 共 15 个单测
- [x] 前端商城：`frontend/` —— 全部页面完成（登录/注册、首页、商品列表、搜索、详情、购物车、确认订单、我的订单/详情、收货地址、秒杀、后台管理 ×3）；axios 封装（Token/Result 解包/401 跳转）、user store、路由守卫（登录 + 管理员）；用户 store Vitest 6 个单测，`npm run build` / `npm run test:unit` 通过（详见 tasks.md Task 10）
  - **API 契约冻结**：商品/搜索/购物车/订单/秒杀/管理端的接口契约已在前端 `frontend/src/api/types.ts` + `src/api/*.ts` 定义（含秒杀 taskId 轮询、下单防重 Token、搜索高亮 `<em>` 标签等约定）。**Task 4-9 后端各模块实现时必须与该契约对齐**，如需调整契约须同步修改前端。
- [x] 前端美化重构：抽出通用布局组件 `AppHeader.vue` / `AppFooter.vue`（sticky 导航、路由高亮、头像下拉、购物车角标经 defineExpose + App 转发刷新）与用户模块外壳 `AuthShell.vue`（登录/注册共用）；登录/注册/收货地址页美化；主题色收敛到 `main.css` CSS 变量；组件约定见第 5.1 节
- [x] 商品模块：`com.mall.product` —— 分类树（GET /api/product/category/tree）、商品分页（GET /api/product/page，仅上架 + keyword 模糊搜索 + 分类过滤，不加载 detail 大字段；categoryId 会先展开为「自身 + 全部后代分类 ID」再 IN 查询，选一级分类可命中其下所有二级分类商品）、商品详情（GET /api/product/{id} 含 SKU，缓存三防：空值缓存防穿透 / Redisson 互斥锁防击穿 / 随机 TTL 防雪崩，缓存 key `mall:product:detail:{id}`）；新增 MybatisPlusConfig 分页插件（MyBatis-Plus 3.5.9+ 需单独引入 `mybatis-plus-jsqlparser`）；ProductServiceImplTest 13 个单测，`mvn test` 全绿 35/35（详见 tasks.md Task 4）
- [x] 购物车模块：`com.mall.cart` —— Redis Hash 购物车（key `mall:cart:{userId}`，field=skuId，value=条目快照 JSON 固化商品信息），增删改查 + 勾选/全选 + 合计聚合（GET /api/cart、POST /api/cart、PUT /api/cart/quantity|selected|selectedAll、DELETE /api/cart/{skuId}，均需登录）；加购校验 SKU 存在/商品上架/库存充足，skuSpec 由 specJson 解析为 " / " 拼接文案，条目 JSON 损坏自动清理降级；CartServiceImplTest 15 个单测，`mvn test` 全绿 50/50（详见 tasks.md Task 6）
- [x] ES 搜索模块：`com.mall.search` —— 索引 `mall_product`（IK 分词，启动幂等自建 + `deploy/elasticsearch/init-product-index.sh`）、RocketMQ 同步（Topic `mall-product-sync`，upsert/delete，DB 回查式消费，天然幂等）、搜索接口 GET /api/search（多字段高亮 `<em>` + 分类/价格筛选 + 综合/销量/价格排序 + 分页，契约与前端 SearchQuery/SearchProduct 对齐）；SearchSyncIntegrationTest 10 个集成测试（独立 `*_it` 索引/Topic，中间件未启动自动跳过）+ 生产者/消费者单测 11 个，`mvn test` 全绿 75/75（含全量同步 4 个单测）；存量商品全量同步入口已提前实现：管理员接口 POST /api/admin/search/product/syncAll（`ProductIndexService#syncAllProducts`，500/批 bulk，索引空导致搜索全空的修复，Task 9 管理端可直接复用）；注意搜索仅匹配 name/subTitle，不含分类名（详见 tasks.md Task 5）
- [ ] 订单 + 延迟关单、秒杀、后台管理（详细拆解见 `.trae/specs/init-mall-project/tasks.md`；Task 11 全链路联调时验证前后端契约一致性）

## 8. 辅助文档（按需阅读）

Agent 在执行对应类型的任务前，**必须先阅读**下表中的文档：

| 文档 | 内容 | 何时阅读 |
| --- | --- | --- |
| [docs/dev-guide.md](docs/dev-guide.md) | 环境要求、常用命令（docker compose / mvn / npm）、演示账号 | 启动服务、执行构建/测试命令、需要登录演示账号时 |
| [docs/middleware.md](docs/middleware.md) | 中间件连接信息（MySQL 3307 等）、RocketMQ 踩坑与修复、ES IK 分词器安装与降级策略 | 连接中间件、排障中间件启动失败、开发 search 模块（ES）时 |

新增踩坑记录/约定时优先归入上表文档，避免本文件继续膨胀；本文件只保留高频核心约定与进度。
