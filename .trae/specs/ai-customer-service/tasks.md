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

- [x] Task 4: 前端 AI 客服组件（`npm run build` 含 vue-tsc 类型检查通过）
  - [x] SubTask 4.1: `frontend/src/api/ai.ts` 定义 SSE 连接封装——因后端接口为 POST，`EventSource` 仅支持 GET 不可行，按 spec.md 备选方案实现 `fetch + ReadableStream` 手工解析 SSE（event:/data: 行 + 空行分帧 + 多行 data 还原），`createChatStream(message, handlers, signal)` 处理 message/tool_call/tool_result/done/error 五类事件；401/429 等未进入流前的 Result JSON 错误统一转 onError；`parseToolResult` 按 JSON 形状归类商品/订单列表/订单详情卡片载荷
  - [x] SubTask 4.2: `frontend/src/components/AiChatBot.vue`：
    - 全局浮动按钮（右下角 fixed 定位，品牌色 `--mall-brand` 背景）
    - 点击展开抽屉式聊天窗（400px × 70vh，移动端 @media 全宽 80vh）
    - 消息列表：用户消息右对齐灰色气泡（纯文本），AI 消息左对齐白色气泡 + Markdown 渲染（`marked`，输出经 `DOMPurify` 消毒防 XSS）
    - 流式输出时 AI 消息区域显示光标闪烁动画；tool_call → tool_result 之间显示「正在查询…」占位
    - 工具结果卡片：商品卡片（图片 + 名称 + 价格 + 跳转 `/product/{id}` 详情链接），订单列表/详情卡片（订单号 + 状态 el-tag + 金额 + 时间）
    - 输入框 + 发送按钮，Enter 发送（Shift+Enter 换行），sending/空输入禁用防重复提交；关闭面板时 AbortController 中断进行中的流
  - [x] SubTask 4.3: `App.vue` 全局引入 `<AiChatBot />`，确保所有页面可唤起；新增依赖 `marked`、`dompurify`

- [x] Task 5: 测试、联调与文档
  - [x] SubTask 5.1: `ChatServiceImplTest`（8 用例）：mock `OpenAiClient`/`AiToolExecutor`/`AiMessageService`/RedisTemplate + 真实 `AiToolRegistry`，覆盖普通文本流式回复与上下文写回、tool_calls 工具链路（tool_call/tool_result/message/done 事件序列 + 第二次请求 messages 含 assistant(tool_calls)/tool 回填）、工具轮数超限去 tools 强制收敛、LLM 异常兜底 error 事件、未启用快速失败、上下文按轮 FIFO 截断、缓存损坏重置、客户端断开中断不持久化；SSE 事件断言通过反射调 `SseEventBuilderImpl#build()` 还原 wire 文本再按 SSE 语法解析（不依赖 Spring 内部 token 布局）；`ChatResult` 无 setter，测试用反射 `appendContent` 构造
  - [x] SubTask 5.2: `AiToolExecutorTest`（11 用例）：execute 统一入口（未知工具抛 BizException、为无上下文线程补 LoginUser 并在结束后清理、已有上下文不覆盖不清理、业务异常转 `{"error":...}` 不外抛）；queryProduct 参数解析（keyword/sort/pageSize=3）+ ES 高亮 `<em>` 清理；queryUserOrders 状态过滤/分页透传（pageSize=5）+ 状态文案映射 + 时间裁剪到分；queryOrderDetail 缺 orderNo 抛错、明细/支付时间序列化、items null 兜底空数组
  - [x] SubTask 5.3: 前端 Vitest `AiChatBot.spec.ts`（6 用例）：面板开合与欢迎语、发送后用户气泡 + createChatStream 参数、流式增量 Markdown 渲染与打字光标收尾、商品卡片（含 `/product/{id}` 跳转链接）、订单卡片 + tool_call 期间「正在查询…」占位、error 事件错误样式展示且可继续发送；`npm run type-check`（vue-tsc）通过。**顺带修复组件 bug**：SSE 回调直接改 push 前的原始对象不触发 Vue 响应式渲染，改为从 `messages.value` 取回代理引用后再增量更新
  - [x] SubTask 5.4: HTTP 冒烟全链路（真实 DeepSeek `deepseek-flash`）：登录 → 「有什么手机」→ queryProduct 工具调用返回商品卡片数据 + Markdown 表格 → 「最便宜的是哪款」上下文连贯（零工具引用上轮）→ 「我最近的订单」订单卡片数据 → 限流验证 HTTP 429（`{"code":429,"message":"发送太频繁…"}`）→ `ai_conversation`/`ai_message` 异步落库正常。**联调修复两处数据问题**：① 种子商品副标题无「手机」关键词且 ik_smart 把「智能手机」切成整词，问「有什么手机」搜不到——更新 `02-seed.sql` 与线上库副标题（「…5G 手机」空格分词）+ 管理端 `POST /admin/search/product/syncAll` 重建索引；② docker exec 传中文会乱码，改用 `CONVERT(UNHEX('..') USING utf8mb4)` 纯 ASCII SQL
  - [x] SubTask 5.5: 文档更新——`AGENTS.md` 第 7 节勾选交付、第 5.2 节新增「AI 模块」编码规范（SSE error 事件兜底/工具失败不外抛/结果精简/api-key 环境变量/前端响应式代理引用）；`docs/dev-guide.md` 新增「AI 智能客服配置」章节（环境变量注入与进程重启注意、模型名以 GET /models 为准、开关/限流、冒烟动线、排障入口）

# Task Dependencies

- [Task 2] depends on [Task 1]（需配置与依赖就绪）
- [Task 3] depends on [Task 2]（需 LLM 客户端与工具注册）
- [Task 4] depends on [Task 3]（需 SSE 接口就绪）
- [Task 5] depends on [Task 3, Task 4]（需前后端均就绪）
