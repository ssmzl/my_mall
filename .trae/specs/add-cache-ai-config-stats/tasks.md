# Tasks

- [x] Task 1: 数据库变更：`ai_config` 表 + `ai_message` token 列
  - [x] 1.1 修改 `deploy/mysql/init/01-schema.sql`：新增 `ai_config` 单行配置表（id=1 主键，enabled tinyint、base_url、api_key varchar(512)、model、temperature decimal(3,2)、max_rounds int、ttl_minutes int、rate_limit_per_minute int、update_time）；`ai_message` 增加 `prompt_tokens INT NULL`、`completion_tokens INT NULL`
  - [x] 1.2 对本地已有库执行幂等 ALTER（docker exec mysql，`ADD COLUMN IF NOT EXISTS` 语义用先查 information_schema 或容错脚本），并插入 `ai_config` 空表（不预置数据行）
    - 实现要点：`information_schema.COLUMNS` 计数 + PREPARE/EXECUTE 条件 ALTER，脚本经 docker cp 进容器执行；验证两列已加、`ai_config` 建表成功且 0 行
- [x] Task 2: 分类树缓存（与 Task 1 无依赖，可并行）
  - [x] 2.1 C 端 `ProductServiceImpl#categoryTree` 接入缓存：key `mall:cache:category:tree:c`，JSON 序列化，TTL 1h，空结果写 60s 占位
  - [x] 2.2 管理端 `AdminCategoryServiceImpl#tree()` 接入缓存：key `mall:cache:category:tree:admin`，同上策略
  - [x] 2.3 `AdminCategoryServiceImpl` 的 add/update/delete 成功后删除两个缓存 key（当前无事务直接删；方法注释标注「若加事务须 afterCommit 删缓存」约定）
    - 实现要点：新增共用组件 `com.mall.product.cache.CategoryTreeCache`（get/put/evictAll，反序列化失败删脏数据按未命中处理、序列化失败降级不写缓存）；空树缓存值即 `"[]"`（读取反序列化为空列表，天然区分未命中 null），无需额外占位标记；`AdminCategoryServiceImpl#evictTreeCacheAfterChange` 统一驱逐并标注 afterCommit 约定；新增 `CategoryTreeCacheTest` 7 个用例（TTL 策略/空树占位/损坏降级/驱逐双 key），`mvn test` 全绿 189/189
- [ ] Task 3: AI token 采集（依赖 Task 1）
  - [ ] 3.1 `OpenAiClient`：stream 请求体加 `stream_options:{"include_usage":true}`；解析最终 chunk `usage` 节点，`ChatResult` 增加 `promptTokens`/`completionTokens`（可空）
  - [ ] 3.2 `ChatServiceImpl#streamChat`：按轮累计每次 LLM 调用的 usage（多次工具循环取和），传给 `saveRound`
  - [ ] 3.3 `AiMessage` 实体加 token 字段；`AiMessageServiceImpl#saveRound` 将累计值写到该轮最终 assistant 消息行
- [ ] Task 4: 管理端 AI 配置（依赖 Task 1）
  - [ ] 4.1 新增 `ai_config` 实体 + Mapper；启动时加载表配置覆盖 `AiConfigProperties`（表无行则用 yml 默认）
  - [ ] 4.2 `AdminAiConfigController`：`GET /admin/ai/config`（api-key 脱敏返回 hasApiKey）、`PUT /admin/ai/config`（api-key 留空保留原值；参数校验 + 保存写库 + synchronized 原地更新 `AiConfigProperties` 即时生效）
  - [ ] 4.3 Service 接口 + 实现类分层，异常走 `BizException`
- [ ] Task 5: 管理端统计接口（依赖 Task 1）
  - [ ] 5.1 新增 `AdminStatsMapper`（接口 + XML）：汇总聚合（销量/销售额按 oms_order.status IN (1,2,3)、pay_time 过滤；AI 轮数按 ai_message.role='user'；token 求和）与 Top10 聚合（按 range 分组参数：今日/近7天/近30天，含上一同期对比）
  - [ ] 5.2 `AdminStatsService(Impl)` 组装 VO：增幅 =（本期-上期)/上期*100，上期为 0 时增幅 null
  - [ ] 5.3 `AdminStatsController`：`GET /admin/stats/summary`、`GET /admin/stats/top-products?range=today|7d|30d`（range 非法抛 BizException），返回 `Result<T>`
- [ ] Task 6: 前端管理端页面（依赖 Task 4、5）
  - [ ] 6.1 `src/api/admin.ts` + `types.ts`：新增 stats / aiConfig 接口与类型
  - [ ] 6.2 新增 `views/admin/AdminDashboardView.vue`：汇总卡片（今日/累计销量、销售额、AI 轮数、token）+ Top10 表格（el-table，range 切换 Tab，增幅 tag：正绿负红、null 显示 NEW）；纯 Element Plus，不新增依赖
  - [ ] 6.3 新增 `views/admin/AdminAiConfigView.vue`：表单（enabled 开关、base-url、model、temperature、max-rounds、ttl-minutes、rate-limit、api-key 密码框留空不改），保存提示成功
  - [ ] 6.4 路由 + `AdminLayout.vue` 菜单：新增 dashboard / ai-config 页面；`/admin` 默认重定向改为数据看板
- [ ] Task 7: 测试与收尾（依赖全部）
  - [ ] 7.1 后端单测：缓存驱逐逻辑、AI 配置脱敏与「留空保留」逻辑、统计增幅计算（含上期为 0 边界）、token 累计逻辑（Mockito，风格对齐现有测试）
  - [ ] 7.2 `mvn test` 全绿 + 前端 `npm run build` 通过
  - [ ] 7.3 更新本文件勾选、progress.md、AGENTS.md 第 7 节；新约定（缓存 key、统计口径）补入 project 记忆/AGENTS 第 5.2 节

# Task Dependencies
- Task 3 / 4 / 5 依赖 Task 1
- Task 2 无依赖，可与 Task 1 并行
- Task 6 依赖 Task 4、5
- Task 7 依赖全部
