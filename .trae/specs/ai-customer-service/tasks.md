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

- [ ] Task 3: 聊天服务、上下文管理与 SSE 接口
  - [ ] SubTask 3.1: `ChatService`（接口 + `ChatServiceImpl`）核心方法 `streamChat(Long userId, String message, SseEmitter emitter)`：
    - 读取 Redis 上下文 → 拼装 system prompt + 历史 messages + 当前 user message
    - 注入 tools 调用 LLM（stream=true）
    - 若 LLM 返回 finish_reason=tool_calls，暂停 SSE 输出，串行执行工具，将 tool_result 回填 messages，再次请求 LLM 总结并继续流式输出
    - 流式解析 chunk，逐段 `emitter.send(SseEmitter.event().name("message").data(chunk))`
    - 完整回复结束后，更新 Redis 上下文（FIFO 截断至 maxContextRounds），异步写 ai_message
  - [ ] SubTask 3.2: `AiChatController` POST `/api/ai/chat/stream`（需登录），创建 `SseEmitter`，调用 `ChatService#streamChat`，异常时发送 `error` 事件并 `completeWithError`；用户级限流在 Controller 层用 Redis 计数器拦截
  - [ ] SubTask 3.3: 异步持久化 `AiMessageService#saveAsync`（`@Async("aiTaskExecutor")`），写入 ai_conversation（首次消息自动创建会话，title 取用户第一句话前 20 字）与 ai_message；失败仅记录日志

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
