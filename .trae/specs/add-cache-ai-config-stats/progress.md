# add-cache-ai-config-stats 当前进度

> 记录 add-cache-ai-config-stats 规格下各任务的完成状态与实现要点。
> 任务粒度与依赖关系见同目录 [tasks.md](tasks.md)；验收清单见 [checklist.md](checklist.md)；规格见 [spec.md](spec.md)。

## 总览

- 状态：**进行中（Task 1、2 已完成，Task 3-7 待做）**
- 范围：项目收尾三件事
  1. 分类树 Redis 缓存（C 端 `mall:cache:category:tree:c` / 管理端 `mall:cache:category:tree:admin`，TTL 1h，空树 60s 占位防穿透；管理端增删改成功后删除两个 key）
  2. 管理端 AI 配置（新增 `ai_config` 单行配置表，启动加载覆盖 `AiConfigProperties`，`GET/PUT /admin/ai/config` 保存后原地更新单例即时生效，api-key 脱敏、留空保留原值）
  3. 管理端数据看板（`/admin/stats/summary` 汇总 + `/admin/stats/top-products` Top10 销量榜含同比上一周期增幅；AI token 依赖 `OpenAiClient` 开启 `stream_options.include_usage` 采集并落库到 `ai_message` 新列 `prompt_tokens`/`completion_tokens`）

## 任务清单

- [x] Task 1 数据库变更：`ai_config` 表 + `ai_message` token 列（01-schema.sql 更新 + 本地库 ALTER）
  - 已完成（2026-09-27）：`01-schema.sql` 新增 `ai_config` 表与 `ai_message.prompt_tokens`/`completion_tokens` 列；本地库用 information_schema 条件判断 + PREPARE 幂等迁移（docker cp 进容器执行），已验证建表成功、列已加、`ai_config` 0 行
- [x] Task 2 分类树缓存：C 端/管理端读缓存 + 管理端增删改驱逐（无依赖，可与 Task 1 并行）
  - 已完成（2026-09-27）：新增共用组件 `com.mall.product.cache.CategoryTreeCache`（key `mall:cache:category:tree:c`/`...:tree:admin`，TTL 1h，空树写 `"[]"` 60s 占位防穿透；反序列化失败删脏数据按未命中、序列化失败降级不缓存）；`ProductServiceImpl#categoryTree` 与 `AdminCategoryServiceImpl#tree()` 接入；管理端 add/update/delete 成功后 `evictTreeCacheAfterChange()` 驱逐双 key（注释标注加事务须 afterCommit）；新增 `CategoryTreeCacheTest` 7 用例，`mvn test` 全绿 189/189
- [ ] Task 3 AI token 采集：OpenAiClient usage 解析 + ChatService 按轮累计 + saveRound 落库（依赖 Task 1）
- [ ] Task 4 管理端 AI 配置：实体/Mapper + 启动加载 + GET/PUT 接口（脱敏/留空保留/即时生效）（依赖 Task 1）
- [ ] Task 5 管理端统计接口：AdminStatsMapper XML 聚合 + Service 组装 VO + Controller（依赖 Task 1）
- [ ] Task 6 前端：AdminDashboardView.vue 数据看板 + AdminAiConfigView.vue AI 配置页 + api/路由/菜单（依赖 Task 4、5）
- [ ] Task 7 测试与收尾：单测 + `mvn test` 全绿 + `npm run build` 通过 + 文档同步（依赖全部）
