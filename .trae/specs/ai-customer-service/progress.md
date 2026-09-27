# ai-customer-service 当前进度

> 记录 ai-customer-service 规格下各任务的完成状态与实现要点。
> 任务粒度与依赖关系见同目录 [tasks.md](tasks.md)；验收清单见 [checklist.md](checklist.md)。

- [x] Task 1 数据库表 + 后端依赖与配置 —— `ai_conversation`/`ai_message` 两张表（追加至 `deploy/mysql/init/01-schema.sql`，已 docker exec 手动补到运行中的 dev 库）；实体 `AiConversation`/`AiMessage` + BaseMapper；`AiConfigProperties`（`mall.ai` 前缀嵌套 llm/context/limit，`available()` 判启用）+ `AiConfig`（@EnableAsync + aiTaskExecutor）；`application-dev.yml` 追加 `mall.ai` 配置段（默认 DeepSeek 端点，api-key 走环境变量 `MALL_AI_API_KEY`，enabled 默认 false 不阻断启动）；LLM HTTP 调用确定用 JDK 17 内置 `java.net.http.HttpClient`，pom 未加依赖；`mvn compile` 通过
- [x] Task 2 LLM 客户端与 Function Calling —— `com.mall.ai.llm.OpenAiClient`（JDK 17 HttpClient，stream=true 流式解析 SSE chunk：增量文本经 deltaConsumer 回调、tool_calls 分片按 index 聚合、finish_reason 捕获；非 200/流内 error/解析失败抛 BizException）；`com.mall.ai.tool.AiToolRegistry`（queryProduct/queryUserOrders/queryOrderDetail 定义 + 执行 lambda + toolSchemas()）；`com.mall.ai.tool.AiToolExecutor`（execute 统一补 LoginUser 上下文再委托业务服务，失败返回 {"error":...} JSON 由 LLM 向用户解释；结果精简字段控制上下文体积）。依赖方向 Registry→Executor 避免循环注入；`mvn compile` 通过
- [ ] Task 3 聊天服务、上下文管理与 SSE 接口
- [ ] Task 4 前端 AI 客服组件
- [ ] Task 5 测试、联调与文档
