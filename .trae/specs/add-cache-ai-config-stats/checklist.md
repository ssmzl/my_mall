# Checklist

- [ ] C 端分类树命中缓存后不再查库（TTL 内重复请求无 SQL）
- [ ] 管理端新增/修改/删除分类后，C 端与管理端分类树缓存均已删除，下次读取拿到最新数据
- [ ] 空分类树写入 60s 短 TTL 占位，防缓存穿透
- [ ] `ai_message` 新增 prompt_tokens / completion_tokens 列（可空），`ai_config` 表已建且本地库已执行 ALTER
- [ ] `OpenAiClient` 流式请求携带 stream_options.include_usage 并解析 usage；LLM 未返回 usage 时对话流程不受影响
- [ ] 一轮对话（含多次工具调用循环）的 token 按轮累计，落到该轮最终 assistant 消息行
- [ ] GET /admin/ai/config 不返回 api-key 明文，仅返回 hasApiKey
- [ ] PUT /admin/ai/config：api-key 留空保留原值；保存后不重启应用配置即时生效（下一次对话用新配置）
- [ ] 应用启动时 ai_config 表有配置行则覆盖 yml 默认，无行时用 yml 默认
- [ ] GET /admin/stats/summary 返回今日/累计销量、销售额、AI 轮数、token；销售口径为 status IN (1,2,3) 且按 pay_time
- [ ] GET /admin/stats/top-products 支持 range=today|7d|30d，返回 Top10 及对比上一同期增幅；上期销量为 0 时增幅为 null
- [ ] range 非法值返回业务异常；所有统计接口返回 Result<T> 且要求管理员权限
- [ ] 前端数据看板展示汇总卡片与 Top10（range 切换、增幅 tag、NEW 显示），未引入新依赖
- [ ] 前端 AI 配置页可查看（密钥脱敏）与保存配置，api-key 留空不覆盖
- [ ] /admin 默认重定向到数据看板，菜单含「数据看板」「AI 配置」入口，接口路径与前端 api 定义逐字对齐
- [ ] 新增/修改代码有对应单测，mvn test 全绿，npm run build 通过
- [ ] tasks.md / progress.md / AGENTS.md 已同步更新
