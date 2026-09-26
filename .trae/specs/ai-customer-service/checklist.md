# Checklist

- [ ] `deploy/mysql/init/01-schema.sql` 包含 `ai_conversation`、`ai_message` 建表语句；后端 `AiConfigProperties` 可正确绑定 `mall.ai.llm.*` 配置
- [ ] LLM 客户端可正常请求 OpenAI 兼容接口，流式解析 SSE chunk 无丢字；非 2xx 响应抛 `BizException`
- [ ] 3 个 Function Calling 工具（queryProduct / queryUserOrders / queryOrderDetail）注册成功，LLM 返回 `tool_calls` 时后端能正确执行并回填结果
- [ ] SSE 接口 `POST /api/ai/chat/stream` 登录后可访问，未登录返回 401；流式输出正常，异常时发送 `error` 事件并关闭连接
- [ ] 多轮上下文：Redis 缓存最近 10 轮对话，连续追问时 AI 能理解上文；TTL 30 分钟后上下文自动清除
- [ ] 用户级限流：1 分钟 10 条消息，超限返回 429，限流计数基于 Redis
- [ ] 聊天记录异步写入 `ai_message` 表，首次对话自动创建 `ai_conversation` 记录
- [ ] 前端 AI 客服浮动按钮在所有页面可见，点击展开聊天窗；消息支持 Markdown 渲染；工具结果以卡片展示
- [ ] 前端 SSE 连接能正确接收并渲染流式文本、工具卡片、done/error 事件
- [ ] `mvn test` 新增单测全绿（ChatServiceImplTest + AiToolExecutorTest）；`npm run test:unit` 前端单测通过；`npm run build` 通过
- [ ] HTTP 冒烟：完整对话链路（商品查询 → 上下文追问 → 订单查询 → 限流触发）验证通过
- [ ] `AGENTS.md` 与 `docs/dev-guide.md` 已更新 AI 模块相关说明
