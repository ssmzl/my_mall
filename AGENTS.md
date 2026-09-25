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
- [x] 订单模块：`com.mall.order` —— 下单防重 Token（`mall:order:token:{token}` 绑定用户一次性消费，TTL 10min）、事务内 SKU 条件扣减库存防超卖（`UPDATE ... WHERE stock >= qty`，同 SKU 条目先合并）、订单/订单项快照（orderNo=时间戳+6位随机；addressSnapshot 为 6 字段收货信息 JSON 与前端契约一致）、事务提交后 afterCommit 发送延迟关单消息（Topic `mall-order-close`，延迟级别 `mall.order.close-delay-level` 默认 4=30s 便于演示、生产建议 16=30m）+ 清购物车已购项；模拟支付（条件更新 0→1 幂等，成功累加商品销量）、主动取消/超时关单共用条件更新 0→4（并发只回补一次库存）、订单分页/详情（归属校验防越权）；接口前缀 /api/order 与前端 order.ts 契约一致；OrderServiceImplTest 24 个单测，`mvn test` 全绿 99/99；已全链路 HTTP 冒烟（含 30s 延迟关单与库存回补验证，详见 tasks.md Task 7）
- [x] 秒杀模块：`com.mall.seckill` —— 活动列表 GET /api/seckill/activities（免登录，剩余库存优先读 Redis `mall:seckill:stock:{spId}` 回退 DB）、启动预热 `SeckillWarmUpRunner`（上线未结束活动的秒杀库存加载到 Redis）、秒杀下单 POST /api/seckill/{spId}（简单限流固定窗口 1s 默认 3 次 + Lua 脚本原子「限购校验+库存预扣+已购计数」`SeckillLua`，MQ 发送失败自动回滚预扣）、消费者异步建单（Topic `mall-seckill-order`，DB 条件扣减兜底防超卖、任务状态幂等、秒杀价快照 + 默认地址快照、afterCommit 复用延迟关单）、结果查询 GET /api/seckill/result?taskId=（校验任务归属防越权）；SeckillServiceImplTest 18 个单测 + SeckillIntegrationTest 3 个并发集成测试（20 用户抢 5 件不超卖、单用户限购 1 件、真实 MQ 建单数一致，中间件未启动自动跳过），`mvn test` 全绿 120/120；已 HTTP 冒烟全链路（详见 tasks.md Task 8）
- [x] 后台管理模块：`com.mall.admin` —— 分类维护（POST /admin/category、PUT/DELETE /admin/category/{id}、管理端分类树 GET /admin/category/tree 含禁用分类；层级固定两级、删除前校验无子分类且无商品）；商品/SKU 维护（GET /admin/product/page 含下架、POST /admin/product 必须挂二级分类、PUT /admin/product/{id} 仅更新非空字段、PUT /admin/product/status/{id} 上下架、SKU 增改删 POST /admin/product/{productId}/sku、PUT/DELETE /admin/product/sku/{id}）；商品新增/修改/上下架均在事务内发送 ES upsert 同步（MQ 失败抛异常整体回滚）；SKU 增删改后重算 SKU 最低价回写商品展示价（无 SKU 保持原价）并触发 ES upsert + 删除详情缓存 `mall:product:detail:{id}`（ES 文档不含 SKU 字段，搜索展示价取商品表 price，故需回写后同步；商品修改/上下架同样清详情缓存）；订单管理（GET /admin/order/page 全量订单含订单项、PUT /admin/order/deliver/{orderNo} 条件更新仅 1已支付→2已发货、重复发货幂等）；秒杀活动配置（POST /admin/seckill/activity 默认下线、PUT /admin/seckill/activity/{id} 置上线时预热秒杀库存到 Redis、POST/DELETE /admin/seckill/product 活动上线中同步写/清理 Redis 预热 key、GET /admin/seckill/activity/list 管理端全量活动列表含下线/已结束活动——C 端列表只查上线中活动，后台配置页必须用本接口）；详情缓存删除统一在事务提交后 afterCommit 执行（见 5.2 踩坑记录）；AdminSearchController 已从 search 模块迁入 admin 模块（POST /api/admin/search/product/syncAll 路径不变，Task 5 全量同步逻辑复用）；AdminCategoryServiceImplTest/AdminProductServiceImplTest(14)/AdminOrderServiceImplTest/AdminSeckillServiceImplTest 共 44 个单测，`mvn test` 全绿 164/164；已 HTTP 冒烟全链路含普通用户 403（详见 tasks.md Task 9，SubTask 9.1 此前勾选有误、代码已随本次落地）
- [ ] Task 11 全链路联调（验证前后端契约一致性）

## 8. 辅助文档（按需阅读）

Agent 在执行对应类型的任务前，**必须先阅读**下表中的文档：

| 文档 | 内容 | 何时阅读 |
| --- | --- | --- |
| [docs/dev-guide.md](docs/dev-guide.md) | 环境要求、常用命令（docker compose / mvn / npm）、演示账号 | 启动服务、执行构建/测试命令、需要登录演示账号时 |
| [docs/middleware.md](docs/middleware.md) | 中间件连接信息（MySQL 3307 等）、RocketMQ 踩坑与修复、ES IK 分词器安装与降级策略 | 连接中间件、排障中间件启动失败、开发 search 模块（ES）时 |

新增踩坑记录/约定时优先归入上表文档，避免本文件继续膨胀；本文件只保留高频核心约定与进度。
