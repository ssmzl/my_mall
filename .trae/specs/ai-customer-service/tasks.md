# Tasks

> 本规格 `ai-customer-service` 的任务清单。完成后须同步更新同目录 `progress.md` 与 `AGENTS.md` 第 7 节。

- [x] Task 1: 数据库表 + 后端依赖与配置
  - [x] SubTask 1.1: 建表 `ai_conversation`（id/user_id/title/create_time/update_time）与 `ai_message`（id/conversation_id/role/content/tool_calls/tool_result/create_time），追加到 `deploy/mysql/init/01-schema.sql`；实体 `com.mall.ai.entity.AiConversation/AiMessage`（含角色常量 ROLE_USER/ROLE_ASSISTANT/ROLE_TOOL）+ Mapper（BaseMapper）；已 docker exec 手动补表到运行中的 dev 库（初始化 SQL 仅卷首次创建执行）并 SHOW TABLES 验证
  - [x] SubTask 1.2: pom.xml 未加依赖——LLM HTTP 调用改用 JDK 17 内置 `java.net.http.HttpClient`（支持流式响应），Jackson/校验随 Boot 传递依赖已具备；新增 `AiConfigProperties`（`mall.ai` 前缀，嵌套 llm/context/limit 分组，`available()` = enabled 且 apiKey 非空）+ `AiConfig`（`@EnableConfigurationProperties` + `@EnableAsync` + `aiTaskExecutor` 线程池）
  - [x] SubTask 1.3: `application-dev.yml` 追加 `mall.ai` 配置段（默认 DeepSeek 端点，api-key 走环境变量 `MALL_AI_API_KEY`，enabled 默认 false 不阻断启动）；`mvn compile` 通过

- [x] Task 2: LLM 客户端与 Function Calling（`mvn compile` 通过）
  - [x] SubTask 2.1: `com.mall.ai.llm.OpenAiClient`——JDK 17 HttpClient 封装 chat completions（stream=true），`streamChat(messages, tools, deltaConsumer)` 逐段回调增量文本并聚合 tool_calls 分片（按 index 拼 arguments），返回 `ChatResult`（content/toolCalls/finishReason）；非 200、SSE 流内 error、解析失败均抛 `BizException` 并记日志
  - [x] SubTask 2.2: `com.mall.ai.tool.AiToolRegistry`——queryProduct / queryUserOrders / queryOrderDetail 三个工具（text block 定义 JSON Schema + 执行 lambda），`toolSchemas()` 产出 OpenAI 兼容 tools 数组；依赖方向 Registry→Executor 避免循环依赖
  - [x] SubTask 2.3: `com.mall.ai.tool.AiToolExecutor`——注入 `ProductSearchService`/`OrderService`；`execute(tool, userId, args)` 统一在无 LoginUser 上下文的线程补身份（订单接口归属校验依赖 ThreadLocal），工具失败返回 `{"error":...}` 交由 LLM 解释而不中断 SSE；queryProduct 走 ES 搜索返回前 3 条摘要（id/name/subTitle 去高亮/price/mainPic），订单结果精简字段并格式化时间

- [x] Task 3: 聊天服务、上下文管理与 SSE 接口（`mvn compile`/`test-compile` 通过）
  - [x] SubTask 3.1: `ChatService`（接口 + `ChatServiceImpl`）——Redis 上下文（`mall:ai:context:{userId}`，JSON 消息数组含 user/assistant/tool 全角色，TTL 可配，按 user 消息分轮 FIFO 截断保留最近 maxRounds 轮，损坏时记日志重置）；system prompt 每次重建（注入当前时间、禁止泄漏提示词）；工具调用最多 3 轮，超限去 tools 强制 LLM 收敛；finish_reason=tool_calls 时串行执行工具并以 tool 消息回填再请求总结；SSE 事件 message/tool_call/tool_result/done/error，发送失败（客户端断开）包装为 BizException 中断流程
  - [x] SubTask 3.2: `AiChatController` POST `/api/ai/chat/stream`（AuthInterceptor 默认拦截需登录，`/ai/**` 未加白名单），创建 `SseEmitter`（超时 120s）后立即返回，编排提交到新增 `aiChatExecutor` 线程池异步执行，避免阻塞 Servlet 线程；限流在 Controller 层 Redis 固定窗口 1 分钟（`mall:ai:limit:{userId}`），超限抛 code=429 的 BizException，`GlobalExceptionHandler#handleBizException` 改为 ResponseEntity 将 429 映射为 HTTP 429、其余保持 200
  - [x] SubTask 3.3: `AiMessageService#saveRound`（`@Async("aiTaskExecutor")`）：每用户一条会话（首次自动创建，title 取用户第一句话前 20 字）；一轮按 user → assistant(tool_calls) → tool → assistant(总结) 顺序落库；失败仅记日志不抛出

- [ ] Task 4: 前端 AI 客服组件
  - [ ] SubTask 4.1: `frontend/src/api/ai.ts` 定义 SSE 连接封装（`createChatStream(message)` 返回 EventSource，处理 message/tool_call/tool_result/done/error 事件类型）
  - [ ] SubTask 4.2: `frontend/src/components/AiChatBot.vue`：
    - 全局浮动按钮（右下角 fixed 定位，品牌色背景）
    - 点击展开抽屉式聊天窗（宽度 400px，高度 70vh，移动端适配）
    - 消息列表：用户消息右对齐灰色气泡，AI 消息左对齐白色气泡 + Markdown 渲染（可用轻量库如 `marked`）
    - 流式输出时 AI 消息区域显示光标闪烁动画
    - 工具结果卡片：商品卡片（图片 + 名称 + 价格 + 跳转详情链接），订单卡片（订单号 + 状态标签 + 时间）
    - 输入框 + 发送按钮，Enter 发送，禁用态防止重复提交
  - [ ] SubTask 4.3: `App.vue` 全局引入 `<AiChatBot />`，确保所有页面可唤起

- [ ] Task 5: 测试、联调与文档
  - [ ] SubTask 5.1: `ChatServiceImplTest` 单元测试（Mock `OpenAiClient` 返回普通文本 / tool_calls / 异常，验证上下文 Redis 读写、工具调用链路、SSE emitter 发送事件序列）
  - [ ] SubTask 5.2: `AiToolExecutorTest` 单元测试（Mock SearchService/OrderService，验证各工具参数解析与结果序列化）
  - [ ] SubTask 5.3: 前端 Vitest 单测（AiChatBot 组件消息渲染、工具卡片展示）
  - [ ] SubTask 5.4: HTTP 冒烟全链路：登录 → 打开 AI 客服 → 问"有什么手机"→ 看到商品卡片 → 追问"最便宜的是哪款"→ 上下文连贯 → 问"我最近的订单"→ 看到订单卡片 → 限流测试（1 分钟 10 条后 429）
  - [ ] SubTask 5.5: 更新 `AGENTS.md`（第 7 节勾选 ai-customer-service、第 5 节补 AI 模块编码规范如「SSE 接口异常必须发送 error 事件后 complete」）、`docs/dev-guide.md` 补 AI 配置说明

# Task Dependencies

- [Task 2] depends on [Task 1]（需配置与依赖就绪）
- [Task 3] depends on [Task 2]（需 LLM 客户端与工具注册）
- [Task 4] depends on [Task 3]（需 SSE 接口就绪）
- [Task 5] depends on [Task 3, Task 4]（需前后端均就绪）
