# 项目收尾：分类树缓存 + 管理端 AI 配置 + 管理端数据看板 Spec

## Why
项目收尾阶段补齐三块能力：① 分类树是 C 端高频读接口，目前每次请求都查库，需要缓存且管理端增删改后必须保证缓存一致性；② AI 配置（enabled/api-key/model/温度/上下文/限流）目前只来自 yml，改配置要重启，需要在管理端可视化配置并即时生效；③ 管理端缺少经营数据视图，需要展示商品销量、销售额、AI 对话轮数、AI token 消耗及 Top10 销量变化。

## What Changes
- 分类树接入 Redis 缓存：C 端与管理端各一个缓存 key，管理端新增/修改/删除分类成功后删除缓存（无事务上下文直接删；若后续加事务须沿用 afterCommit 约定）
- AI 对话 token 采集：`OpenAiClient` 流式请求开启 `stream_options.include_usage` 并解析最终 chunk 的 `usage`；`ChatService` 按轮累计后随 `saveRound` 落库（`ai_message` 新增 `prompt_tokens`/`completion_tokens` 列，仅记在每轮最终 assistant 消息上；LLM 未返回 usage 时记 NULL）
- 新增 `ai_config` 单行配置表：管理端可查看/修改 AI 配置（enabled、base-url、api-key、model、temperature、上下文轮数/TTL、限流），保存后原地更新 `AiConfigProperties` 单例即时生效；api-key 响应脱敏，保存时留空表示保留原值
- 新增管理端统计接口：汇总（当日/累计销量、销售额、AI 对话轮数、AI token 消耗）与 Top10 商品销量榜（今日/近7天/近30天，含对比上一同期的增幅）；聚合 SQL 手写 XML
- 前端新增「数据看板」与「AI 配置」两个管理端页面 + 路由/菜单入口；**BREAKING**：`/admin` 默认重定向从分类管理改为数据看板

## Impact
- Affected specs: init-mall-project（product 分类、admin 分类维护）、ai-customer-service（AI 配置、消息持久化）
- Affected code:
  - 后端：`ProductServiceImpl#categoryTree`、`AdminCategoryServiceImpl`、`OpenAiClient`、`ChatServiceImpl`、`AiMessageService(Impl)`、`AiConfigProperties`/`AiConfig`、新增 `admin` 包下 `AdminStatsController/Service/Mapper`、`AdminAiConfigController/Service`
  - DB：`deploy/mysql/init/01-schema.sql`（`ai_message` 加列、新增 `ai_config` 表）+ 已有库手工 ALTER
  - 前端：`src/api/admin.ts`、`src/api/types.ts`、`src/router/index.ts`、`views/admin/AdminLayout.vue`、新增 `AdminDashboardView.vue`、`AdminAiConfigView.vue`

## ADDED Requirements

### Requirement: 分类树缓存
系统 SHALL 为 C 端分类树与管理端分类树提供 Redis 读缓存：
- key：`mall:cache:category:tree:c`（C 端，仅启用分类）与 `mall:cache:category:tree:admin`（管理端，含禁用分类）
- 值为分类树 JSON（ObjectMapper 序列化），TTL 1 小时；缓存查询结果为空树时写短 TTL（60s）占位值防穿透
- 管理端新增/修改/删除分类成功后 SHALL 删除上述两个 key（当前操作无事务，直接删）

#### Scenario: C 端首次读取回源
- **WHEN** C 端请求分类树且缓存未命中
- **THEN** 查库构建两级树、写缓存并返回；TTL 内再次请求不产生 DB 查询

#### Scenario: 管理端变更后缓存失效
- **WHEN** 管理端新增/修改/删除分类成功
- **THEN** 立即删除 C 端与管理端两个树缓存 key，下次读取回源最新数据

### Requirement: AI 对话 token 采集
系统 SHALL 采集每轮 AI 对话的 LLM token 用量：
- `OpenAiClient` 流式请求体增加 `stream_options: {"include_usage": true}`，解析最终 chunk 的 `usage`（prompt_tokens / completion_tokens），暴露在 `ChatResult` 上；服务端未返回时为 null
- `ChatServiceImpl` 按轮累计（一轮可能多次 LLM 调用），随 `saveRound` 写入该轮最终 assistant 消息的 `prompt_tokens` / `completion_tokens`（INT NULL，默认 NULL）

#### Scenario: 正常记录 token
- **WHEN** 一轮对话完成且 LLM 返回 usage
- **THEN** 该轮所有 LLM 调用的 prompt/completion tokens 累加后持久化到最终 assistant 消息行

#### Scenario: 服务端不返回 usage
- **WHEN** LLM 响应中无 usage 字段
- **THEN** token 列保持 NULL，对话流程不受影响，统计接口按 0 处理

### Requirement: 管理端 AI 配置
系统 SHALL 提供管理端 AI 配置的查看与修改：
- 新表 `ai_config` 单行（id=1）：`enabled`、`base_url`、`api_key`、`model`、`temperature`、`max_rounds`、`ttl_minutes`、`rate_limit_per_minute`、`update_time`
- 应用启动时若表中存在配置行则覆盖 `AiConfigProperties` 对应字段；表无数据时沿用 yml 默认
- 接口：`GET /admin/ai/config`（api-key 脱敏：只返回 `hasApiKey` 布尔，不返回明文）、`PUT /admin/ai/config`（api-key 传空表示保留原值；保存写库后原地更新 `AiConfigProperties` 单例，立即生效，无需重启）

#### Scenario: 查看配置不泄露密钥
- **WHEN** 管理员请求 GET /admin/ai/config
- **THEN** 返回除 api_key 明文外的全部配置及 hasApiKey 标志

#### Scenario: 修改配置即时生效
- **WHEN** 管理员修改 enabled/model 等并保存成功
- **THEN** 不重启应用，下一次 AI 对话即按新配置执行

### Requirement: 管理端数据统计
系统 SHALL 提供管理端统计接口（`/admin/stats/**`，管理员权限）：
- `GET /admin/stats/summary`：返回今日销量、今日销售额、累计销量、累计销售额、今日/累计 AI 对话轮数、今日/累计 AI token 消耗
  - 销售口径：`oms_order.status IN (1,2,3)`（已支付/已发货/已完成），金额 = `SUM(order_item.price * quantity)`，时间按 `pay_time`
  - AI 对话轮数口径：`ai_message.role = 'user'` 的消息数（一条 user 消息 = 一轮）
  - AI token 口径：`ai_message` 中 `prompt_tokens + completion_tokens` 求和（NULL 按 0）
- `GET /admin/stats/top-products?range=today|7d|30d`：按所选时间范围的销量 Top10 商品（productId、商品名快照、销量、销售额），并对比等长上一周期计算增幅百分比；上期销量为 0 时增幅返回 null（前端显示 NEW）
- 聚合查询为多表 GROUP BY，按约定手写 Mapper XML（`AdminStatsMapper`）

#### Scenario: 查看汇总与 Top10
- **WHEN** 管理员打开数据看板
- **THEN** 展示汇总卡片与可切换（今日/近7天/近30天）的 Top10 销量榜及增幅

## MODIFIED Requirements

### Requirement: 管理端路由默认页
`/admin` 默认重定向 SHALL 指向数据看板（原为分类管理）；菜单新增「数据看板」「AI 配置」入口。

**BREAKING**：管理端默认落地页变更。

## REMOVED Requirements
（无）
