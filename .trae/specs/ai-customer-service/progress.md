# ai-customer-service 当前进度

> 记录 ai-customer-service 规格下各任务的完成状态与实现要点。
> 任务粒度与依赖关系见同目录 [tasks.md](tasks.md)；验收清单见 [checklist.md](checklist.md)。

- [x] Task 1 数据库表 + 后端依赖与配置 —— `ai_conversation`/`ai_message` 两张表（追加至 `deploy/mysql/init/01-schema.sql`，已 docker exec 手动补到运行中的 dev 库）；实体 `AiConversation`/`AiMessage` + BaseMapper；`AiConfigProperties`（`mall.ai` 前缀嵌套 llm/context/limit，`available()` 判启用）+ `AiConfig`（@EnableAsync + aiTaskExecutor）；`application-dev.yml` 追加 `mall.ai` 配置段（默认 DeepSeek 端点，api-key 走环境变量 `MALL_AI_API_KEY`，enabled 默认 false 不阻断启动）；LLM HTTP 调用确定用 JDK 17 内置 `java.net.http.HttpClient`，pom 未加依赖；`mvn compile` 通过
- [x] Task 2 LLM 客户端与 Function Calling —— `com.mall.ai.llm.OpenAiClient`（JDK 17 HttpClient，stream=true 流式解析 SSE chunk：增量文本经 deltaConsumer 回调、tool_calls 分片按 index 聚合、finish_reason 捕获；非 200/流内 error/解析失败抛 BizException）；`com.mall.ai.tool.AiToolRegistry`（queryProduct/queryUserOrders/queryOrderDetail 定义 + 执行 lambda + toolSchemas()）；`com.mall.ai.tool.AiToolExecutor`（execute 统一补 LoginUser 上下文再委托业务服务，失败返回 {"error":...} JSON 由 LLM 向用户解释；结果精简字段控制上下文体积）。依赖方向 Registry→Executor 避免循环注入；`mvn compile` 通过
- [x] Task 3 聊天服务、上下文管理与 SSE 接口 —— `com.mall.ai.service.ChatService` + `ChatServiceImpl`（Redis 上下文 `mall:ai:context:{userId}` 存 JSON 消息数组含 user/assistant/tool 全角色，按 user 消息分轮 FIFO 截断至 maxRounds，TTL 可配；system prompt 每次重建含当前时间与防泄漏约束；工具调用循环最多 3 轮，finish_reason=tool_calls 时串行执行工具、tool 消息回填后二次请求总结，超限去 tools 强制收敛；SSE 事件 message/tool_call/tool_result/done/error，发送失败包装 BizException 中断）；`AiChatController` POST `/api/ai/chat/stream`（需登录，SseEmitter 120s 超时，编排提交 `aiChatExecutor` 异步执行；Controller 层 Redis 固定窗口限流 1 分钟/10 条，超限 BizException(429)，`GlobalExceptionHandler` 以 ResponseEntity 映射 HTTP 429）；`AiMessageService#saveRound`（`@Async("aiTaskExecutor")`，每用户一条会话首次自动创建 title 取前 20 字，按 user → assistant(tool_calls) → tool → assistant 落库，失败仅记日志）；`AiConfig` 拆出 `aiChatExecutor` 聊天处理线程池；`mvn compile`/`test-compile` 通过
- [ ] Task 4 前端 AI 客服组件
- [ ] Task 5 测试、联调与文档
