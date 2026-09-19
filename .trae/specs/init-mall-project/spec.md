# my_mall 简历级全栈商城 Spec

## Why
从零搭建一个可写进简历的全栈商城项目：以 Spring Boot 单体 + Vue3 为载体，落地 Redis / RocketMQ / Elasticsearch 三大中间件在电商核心链路（搜索、下单、秒杀）中的真实用法，形成可运行、可演示、有"吹点"的工程作品。

## What Changes
- 新增 `backend/`：Spring Boot 3.x（Java 17）单体工程，MyBatis-Plus + JWT + Knife4j，按业务包划分模块（非微服务，降低架构复杂度）。
- 新增 `deploy/docker-compose.yml`：MySQL 8、Redis 7、RocketMQ 5（NameServer+Broker+Proxy）、Elasticsearch 8（+Kibana），一键起全部中间件。
- 扩展 `frontend/`（已有 Vue3+TS+Pinia 脚手架）：引入 Element Plus + Axios，实现商城前台页面 + 简易后台管理页。
- 核心功能：用户注册登录（JWT）、商品分类/详情、ES 商品搜索、Redis 购物车、下单（事务+扣库存）、模拟支付、RocketMQ 延迟消息超时关单、秒杀（Lua 预扣库存 + MQ 异步下单）。
- 简历亮点设计：缓存穿透/击穿/雪崩三防、Redisson 分布式锁、接口幂等（防重 Token + 消费幂等）、本地消息表式可靠投递（RocketMQ 事务消息）。
- 新增根目录 `AGENTS.md`：项目架构、技术栈、目录约定、常用命令、编码规范，供人与 AI 协作参考。

## Impact
- Affected specs: 无既有规格，本规格为初始基线。
- Affected code: 新增 `backend/`、`deploy/`、`AGENTS.md`；修改 `frontend/`（新增页面/路由/store/api 封装）。

## ADDED Requirements

### Requirement: 基础设施一键启动
系统 SHALL 提供 docker-compose 编排 MySQL 8 / Redis 7 / RocketMQ 5 / Elasticsearch 8 / Kibana，并提供初始化 SQL（建库建表+种子数据）与 ES 索引初始化说明。

#### Scenario: 启动全部中间件
- **WHEN** 在仓库根目录执行 `docker compose -f deploy/docker-compose.yml up -d`
- **THEN** MySQL(容器内 3306，宿主机映射 3307，因本机 3306 被占用)、Redis(6379)、RocketMQ NameServer(9876)/Broker(10911)/Proxy(8081)、ES(9200) 均可连通，且 MySQL 自动执行初始化脚本

### Requirement: 后端工程骨架
系统 SHALL 提供 Spring Boot 3 单体后端，包含：统一响应体 `Result<T>`、全局异常处理、参数校验、Knife4j 接口文档、JWT 登录拦截器、MyBatis-Plus 代码分层（controller/service/mapper/domain）、配置外置（dev 环境连接 docker-compose 中间件）。

#### Scenario: 启动后端
- **WHEN** 中间件就绪后启动后端
- **THEN** 应用正常启动，`/doc.html` 可访问接口文档，健康检查接口返回成功

### Requirement: 用户模块
系统 SHALL 提供注册（用户名/密码 BCrypt 加密）、登录（签发 JWT + Redis 存会话）、登出、收货地址 CRUD。

#### Scenario: 注册并登录
- **WHEN** 用户注册后携带账号密码登录
- **THEN** 返回 JWT；携带该 Token 访问受保护接口成功；登出后 Token 立即失效

### Requirement: 商品模块
系统 SHALL 提供分类树查询、商品分页列表、商品详情（含 SKU 库存）；商品详情走 Redis 缓存，并具备缓存穿透（布隆过滤器/空值缓存）、击穿（互斥锁重建）、雪崩（随机过期）防护。

#### Scenario: 查询热点商品详情
- **WHEN** 高频请求同一商品详情
- **THEN** 命中 Redis 缓存；缓存失效时仅一个线程回源重建；查询不存在的商品不穿透到数据库

### Requirement: ES 商品搜索
系统 SHALL 将商品数据同步至 ES（应用层发 MQ，消费者写 ES），提供关键词搜索（IK 分词、标题/副标题匹配、高亮）、按分类/价格区间筛选、按销量/价格/综合排序、分页。

#### Scenario: 关键词搜索
- **WHEN** 用户输入关键词搜索
- **THEN** 返回分词匹配且高亮的商品分页结果；后台新增/修改商品后搜索结果最终一致

### Requirement: 购物车
系统 SHALL 基于 Redis Hash 实现购物车：增删改查、勾选/全选、数量修改、金额小计，登录后访问。

#### Scenario: 加购并结算预览
- **WHEN** 用户将 SKU 加入购物车并勾选
- **THEN** 购物车实时返回选中商品列表与合计金额

### Requirement: 订单模块
系统 SHALL 提供：下单（校验商品/库存/地址，数据库事务内扣减 SKU 库存、生成订单与订单项、清购物车已购项）、模拟支付回调、主动取消、订单分页列表/详情；订单超时未支付通过 RocketMQ 延迟消息自动关单并回补库存；下单与关单接口幂等（防重复提交、消费幂等）。

#### Scenario: 下单超时自动关单
- **WHEN** 用户下单后 30 分钟（可配置）未支付
- **THEN** 延迟消息触发自动取消订单并回补库存，重复消费不产生副作用

#### Scenario: 重复提交下单
- **WHEN** 用户持同一防重 Token 重复提交订单
- **THEN** 仅首个请求成功，其余返回幂等提示

### Requirement: 秒杀模块
系统 SHALL 提供秒杀活动与秒杀商品管理、秒杀下单：Redis Lua 脚本原子预扣库存 + 用户限购校验，成功后投递 RocketMQ 异步创建订单，前端轮询结果；接口层限流。

#### Scenario: 秒杀高并发
- **WHEN** 并发请求秒杀同一商品
- **THEN** 库存不超卖（预扣原子性）、单用户限购一份、未扣到者快速失败、扣到者异步生成订单

### Requirement: 后台管理接口
系统 SHALL 提供管理端接口（管理员角色）：分类/商品/SKU 维护、秒杀活动配置、订单列表查询与发货状态流转。

#### Scenario: 管理员上架商品
- **WHEN** 管理员新增商品并上架
- **THEN** 前台商品列表与 ES 搜索可查到该商品

### Requirement: 前端商城页面
系统 SHALL 在现有 Vue3+TS 脚手架基础上实现：首页/商品列表、搜索结果页（高亮+筛选）、商品详情、购物车、确认订单、我的订单、登录/注册、秒杀活动页、简易后台管理页（商品/订单）；Axios 封装（Token 携带、统一错误提示）、Pinia 用户态、路由守卫。

#### Scenario: 完整购物闭环
- **WHEN** 用户登录 → 搜索商品 → 加购 → 下单 → 模拟支付 → 查看订单
- **THEN** 全流程页面可用，订单状态正确流转

### Requirement: 测试与文档
系统 SHALL 为后端核心 Service（用户、商品缓存、下单、超时关单、秒杀预扣）编写单元/集成测试；前端核心 store/组件编写 Vitest 单测；根目录提供 AGENTS.md（架构、命令、规范）与必要的运行说明。

#### Scenario: 执行测试
- **WHEN** 执行 `mvn test`（后端）与 `npm run test:unit`（前端）
- **THEN** 核心用例全部通过

## MODIFIED Requirements
无（初始基线）。

## REMOVED Requirements
无。
