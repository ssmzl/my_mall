# add-cache-ai-config-stats 当前进度

> 记录 add-cache-ai-config-stats 规格下各任务的完成状态与实现要点。
> 任务粒度与依赖关系见同目录 [tasks.md](tasks.md)；验收清单见 [checklist.md](checklist.md)；规格见 [spec.md](spec.md)。

## 总览

- 状态：**进行中（Task 1-5 已完成，Task 6、7 待做）**
- 范围：项目收尾三件事
  1. 分类树 Redis 缓存（C 端 `mall:cache:category:tree:c` / 管理端 `mall:cache:category:tree:admin`，TTL 1h，空树 60s 占位防穿透；管理端增删改成功后删除两个 key）
  2. 管理端 AI 配置（新增 `ai_config` 单行配置表，启动加载覆盖 `AiConfigProperties`，`GET/PUT /admin/ai/config` 保存后原地更新单例即时生效，api-key 脱敏、留空保留原值）
  3. 管理端数据看板（`/admin/stats/summary` 汇总 + `/admin/stats/top-products` Top10 销量榜含同比上一周期增幅；AI token 依赖 `OpenAiClient` 开启 `stream_options.include_usage` 采集并落库到 `ai_message` 新列 `prompt_tokens`/`completion_tokens`）

## 任务清单

- [x] Task 1 数据库变更：`ai_config` 表 + `ai_message` token 列（01-schema.sql 更新 + 本地库 ALTER）
  - 已完成（2026-09-27）：`01-schema.sql` 新增 `ai_config` 表与 `ai_message.prompt_tokens`/`completion_tokens` 列；本地库用 information_schema 条件判断 + PREPARE 幂等迁移（docker cp 进容器执行），已验证建表成功、列已加、`ai_config` 0 行
- [x] Task 2 分类树缓存：C 端/管理端读缓存 + 管理端增删改驱逐（无依赖，可与 Task 1 并行）
  - 已完成（2026-09-27）：新增共用组件 `com.mall.product.cache.CategoryTreeCache`（key `mall:cache:category:tree:c`/`...:tree:admin`，TTL 1h，空树写 `"[]"` 60s 占位防穿透；反序列化失败删脏数据按未命中、序列化失败降级不缓存）；`ProductServiceImpl#categoryTree` 与 `AdminCategoryServiceImpl#tree()` 接入；管理端 add/update/delete 成功后 `evictTreeCacheAfterChange()` 驱逐双 key（注释标注加事务须 afterCommit）；新增 `CategoryTreeCacheTest` 7 用例，`mvn test` 全绿 189/189
- [x] Task 3 AI token 采集：OpenAiClient usage 解析 + ChatService 按轮累计 + saveRound 落库（依赖 Task 1）
  - 已完成（2026-09-27）：`OpenAiClient` 请求体加 `stream_options: {"include_usage": true}`，`parseSseLine` 在 choices 缺失提前 return 前解析 `usage` 节点（usage chunk 的 choices 为空数组），`ChatResult` 新增可空 `promptTokens`/`completionTokens`；`ChatServiceImpl#streamChat` 循环内用 `accumulateTokens` 按轮累计（多次工具调用取和，全 null 保持 null），随 `saveRound` 传给持久化；`AiMessage` 实体加 `promptTokens`/`completionTokens`，`AiMessageServiceImpl#saveRound` 仅写入该轮最终 assistant 消息行；同步修正 `ChatServiceImplTest` 中 saveRound 旧签名调用
- [x] Task 4 管理端 AI 配置：实体/Mapper + 启动加载 + GET/PUT 接口（脱敏/留空保留/即时生效）（依赖 Task 1）
  - 已完成（2026-09-27）：新增 `com.mall.ai.entity.AiConfigEntity`（命名避开已有 `AiConfig` 配置类）+ `AiConfigMapper`；`AiConfigInitializer`（ApplicationRunner）启动时加载 `ai_config` id=1 行覆盖 `AiConfigProperties`，无行沿用 yml；共用映射规则抽为 `AiConfigApplier.apply`（api-key 空白不覆盖）；`AdminAiConfigService(Impl)`：`GET /admin/ai/config` 返回 VO 仅含 `hasApiKey` 不回传明文，`PUT /admin/ai/config` api-key 留空保留原值（表行优先、其次 yml 值），upsert 后 synchronized 方法内原地 apply 到 props 单例即时生效；DTO 用 jakarta validation 校验（temperature 0-2、各数值 ≥1）
- [x] Task 5 管理端统计接口：AdminStatsMapper XML 聚合 + Service 组装 VO + Controller（依赖 Task 1）
  - 已完成（2026-09-27）：`AdminStatsMapper`（接口 + `resources/mapper/AdminStatsMapper.xml`）4 个聚合查询：订单销量/销售额（`oms_order.status IN (1,2,3)`，`pay_time` 左闭右开区间，start/end 为 null 即累计口径）、AI 轮数（`ai_message.role='user'`）、token 求和（NULL 按 0）、Top10（GROUP BY product_id，商品名取最近下单快照子查询，避免 GROUP_CONCAT 逗号截断）；`AdminStatsService(Impl)` 组装 `AdminStatsSummaryVO` / `AdminTopProductVO`，增幅 =（本期-上期)/上期*100 保留 1 位小数，上期为 0 返回 null（NEW）；时间窗口按自然日对齐（7d = 含今天共 7 个自然日，同期前移等长周期）；`AdminStatsController`：`GET /admin/stats/summary`、`GET /admin/stats/top-products?range=today|7d|30d`，range 非法抛 `BizException`
  - 验证：`mvn test` 全绿 189/189（0 失败，13 跳过为需中间件的集成测试）
- [ ] Task 6 前端：AdminDashboardView.vue 数据看板 + AdminAiConfigView.vue AI 配置页 + api/路由/菜单（依赖 Task 4、5）
- [ ] Task 7 测试与收尾：单测 + `mvn test` 全绿 + `npm run build` 通过 + 文档同步（依赖全部）
