# AI 智能客服 Spec

## Why

在现有 my_mall 全栈商城基础上，增加 AI 智能客服模块。用户可通过自然语言与 AI 对话，实时查询商品信息、个人订单状态、物流（模拟），并获得商品推荐。该模块作为简历级亮点，展示 LLM 集成、Function Calling（工具调用）、SSE 流式输出、多轮上下文记忆等现代 AI 工程实践。

## What Changes

- 后端新增 `com.mall.ai` 模块：
  - LLM HTTP 客户端（OpenAI 兼容接口，配置化切换 baseUrl / apiKey / model）。
  - Function Calling 工具注册与执行：查询商品（复用 ES 搜索/商品 Service）、查询用户订单列表、查询订单详情。
  - 聊天编排服务（上下文管理 → LLM 请求 → 若触发工具则执行并回填 → 二次总结 → 流式输出）。
  - SSE 流式接口 `POST /api/ai/chat/stream`（需登录）。
  - 聊天记录异步持久化（`ai_conversation` / `ai_message`）。
- 前端新增 `frontend/src/components/AiChatBot.vue`：
  - 全局浮动按钮 + 抽屉式聊天窗口。
  - SSE EventSource 连接、流式打字效果、Markdown 渲染、工具结果卡片展示。
- 数据库新增 `ai_conversation`、`ai_message` 两张表（见 tasks.md Task 1.1）。

## Impact

- Affected specs: `init-mall-project`（复用其用户/订单/商品/搜索 Service 作为 Function Calling 数据源）。
- Affected code:
  - 新增 `backend/src/main/java/com/mall/ai/`（client / service / controller / entity / mapper / dto / config）。
  - 新增 `frontend/src/components/AiChatBot.vue` + `src/api/ai.ts`。
  - 修改 `deploy/mysql/init/01-schema.sql` 新增建表语句、`02-seed.sql` 可选补充演示数据。
  - 修改 `backend/pom.xml` 新增 `okhttp` 依赖（或复用已有 `spring-web` RestTemplate）。

## ADDED Requirements

### Requirement: LLM 基础接入与配置
系统 SHALL 提供配置化的 LLM HTTP 客户端，支持通过 `application-dev.yml` 切换 baseUrl、apiKey、model、temperature 等参数，不硬编码任何第三方密钥。

#### Scenario: 切换 LLM 提供商
- **WHEN** 修改 `application-dev.yml` 中 `mall.ai.llm.*` 配置项
- **THEN** 重启后 AI 客服自动使用新的 LLM 端点，无需改代码

### Requirement: Function Calling 工具注册与执行
系统 SHALL 实现 3 个工具函数，供 LLM 在对话中自主调用：
- `queryProduct`：按关键词查询商品（复用现有 `SearchService`，返回前 N 条 name/price/mainPic）。
- `queryUserOrders`：查询当前登录用户的最近 N 笔订单（复用 `OrderService`，返回 orderNo/status/createTime）。
- `queryOrderDetail`：按订单号查询订单详情（复用 `OrderService`，返回地址快照/订单项/状态/支付时间）。

工具定义采用 OpenAI 兼容的 JSON Schema 格式，由聊天服务在每次请求时注入 `tools` 字段。LLM 返回 `tool_calls` 时，后端串行执行工具、将结果回填 `tool` 角色消息，再次请求 LLM 生成自然语言总结。

#### Scenario: 用户问"我最近的订单到哪了"
- **WHEN** 用户发送该问题
- **THEN** LLM 识别意图并调用 `queryUserOrders` → 后端执行并返回结果 → LLM 总结为自然语言回复

### Requirement: SSE 流式聊天接口
系统 SHALL 提供 `POST /api/ai/chat/stream`（需登录），前端通过 `EventSource` 或 `fetch + ReadableStream` 接收 SSE 事件流。SSE 事件类型：
- `message`：AI 生成的文本片段（逐字/逐词流式推送）。
- `tool_call`：工具调用开始（可选，前端可显示"正在查询..."占位）。
- `tool_result`：工具执行结果（前端以卡片形式展示商品/订单摘要）。
- `done`：流结束。
- `error`：异常终止。

#### Scenario: 正常对话流
- **WHEN** 用户发送消息
- **THEN** 前端实时看到 AI 逐字输出回复；若触发工具调用，先看到"正在查询订单..."，再看到结果卡片和总结文本

### Requirement: 多轮上下文与历史记忆
系统 SHALL 基于 Redis 缓存最近 10 轮对话上下文（用户 + AI + tool 角色消息），key `mall:ai:context:{userId}`，TTL 30 分钟（可配）。每次请求将缓存上下文拼入 LLM 的 `messages` 字段。上下文超长时按 FIFO 截断，保留最近 N 轮。

异步将消息写入 `ai_message` 表（`@Async` 线程池，失败仅记录日志不阻塞聊天）。

#### Scenario: 连续追问
- **WHEN** 用户先问"有什么手机"，再追问"最便宜的是哪款"
- **THEN** 第二轮无需重复"手机"关键词，AI 能基于上下文理解意图

### Requirement: 前端 AI 客服组件
系统 SHALL 在前端新增全局浮动 AI 客服入口（右下角气泡按钮），点击展开抽屉式聊天窗口：
- 发送消息输入框（支持 Enter 发送）。
- 消息列表：用户消息右对齐，AI 消息左对齐 + Markdown 渲染。
- 流式输出时显示打字光标动画。
- 工具结果以卡片形式展示（商品卡片含图片/名称/价格，订单卡片含订单号/状态）。
- 组件注册在 `App.vue` 全局挂载，所有页面均可唤起。

### Requirement: 限流与安全
系统 SHALL 对用户级 AI 聊天做限流：固定窗口 1 分钟默认 10 条消息（Redis 计数 `mall:ai:limit:{userId}`），超限返回 429。禁止 AI 回复中包含系统提示词（system prompt）泄漏。

## MODIFIED Requirements

无（仅新增模块，不修改现有业务接口行为）。

## REMOVED Requirements

无。
