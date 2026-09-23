# Tasks

- [x] Task 1: 基础设施与工程初始化
  - [x] SubTask 1.1: 编写 `deploy/docker-compose.yml`（MySQL 8 / Redis 7 / RocketMQ 5 NameServer+Broker+Proxy / ES 8 / Kibana），配置端口、卷、健康检查
  - [x] SubTask 1.2: 编写初始化 SQL（建库 `my_mall`，表：ums_user、ums_address、pms_category、pms_product、pms_sku、oms_order、oms_order_item、sms_seckill_activity、sms_seckill_product）+ 种子数据
  - [x] SubTask 1.3: 编写根目录 `AGENTS.md`（架构说明、技术栈版本、目录约定、启动/构建/测试命令、编码规范、Git 约定）
  - [x] SubTask 1.4: 验证 `docker compose up -d` 后各中间件可连通（注：MySQL 因宿主机 3306 被本机 MySQL 服务占用，映射到 3307；RocketMQ 卷需一次性 chown 3000:3000，已记录在 AGENTS.md）

- [x] Task 2: 后端骨架（Spring Boot 3 + Java 17 + Maven）（注：代码包名为 `com.mall`，非最初约定的 `com.example.mall`）
  - [x] SubTask 2.1: 创建 `backend/` 工程与 pom.xml（web、validation、MyBatis-Plus、mysql-connector、data-redis、Redisson、rocketmq-spring-boot-starter、data-elasticsearch、jjwt、Knife4j、Lombok、test）
  - [x] SubTask 2.2: 实现统一响应体 Result/PageResult、全局异常处理器、BizException、参数校验、跨域配置（WebConfig）
  - [x] SubTask 2.3: 实现 JWT 工具类（JwtUtils）+ 登录拦截器 AuthInterceptor（Redis 会话校验、管理员校验、LoginUser 上下文）
  - [x] SubTask 2.4: 配置 application-dev.yml 连接 docker-compose 中间件（MySQL 走宿主机 3307），Knife4j 文档可用；已有 JwtUtilsTest / ResultTest 单测

- [x] Task 3: 用户模块（注：包名 `com.mall.user`，接口前缀 /api/user、/api/address）
  - [x] SubTask 3.1: 注册（BCrypt 加密、用户名唯一校验，POST /api/user/register）、登录（签发 JWT + Redis 会话 `mall:token:{token}`，TTL 与 JWT 一致，POST /api/user/login）、登出（删除 Redis 会话使 Token 失效，POST /api/user/logout，需登录）。登录响应 LoginVO{token,userId,username,nickname,role}；登录失败不区分「用户不存在/密码错误」防枚举
  - [x] SubTask 3.2: 收货地址 CRUD（GET /api/address/list、POST /api/address、PUT /api/address/{id}、DELETE /api/address/{id}、PUT /api/address/default/{id}）。默认地址唯一由应用层保证（事务内先清旧默认再设新默认）；首个地址自动设为默认；更新/删除/设默认均校验地址归属防越权
  - [x] SubTask 3.3: 用户模块单元测试（UserServiceImplTest 7 个 + AddressServiceImplTest 8 个，Mockito mock Mapper/Redis，覆盖 BCrypt、唯一校验、会话写入/删除、默认地址唯一、越权校验；`mvn test` 全绿 22/22）

- [x] Task 4: 商品模块 + 缓存三防
  - [x] SubTask 4.1: 分类树查询、商品分页列表接口（GET /api/product/category/tree 两级树按 sort 升序；GET /api/product/page 仅上架商品，支持 categoryId 过滤 + keyword 模糊匹配名称/副标题，按销量降序；列表不加载 detail 大字段。注意：MyBatis-Plus 3.5.9+ 分页插件拆分到 mybatis-plus-jsqlparser 模块，pom 已补依赖并新增 MybatisPlusConfig 注册 PaginationInnerInterceptor）
  - [x] SubTask 4.2: 商品详情接口：GET /api/product/{id}（含 SKU 列表）。Redis 缓存 key `mall:product:detail:{id}`（StringRedisTemplate + Jackson JSON）；空值缓存防穿透（不存在/已下架写 `__NULL__` 标记，TTL 2 分钟）；Redisson 互斥锁防击穿（tryLock 等待 3s/租期 10s，含双重检查，未抢到锁重读缓存、仍空查库兜底）；随机 TTL 防雪崩（1h + [0,30m)）；缓存 JSON 损坏时删除重建。已冒烟验证：Redis 写入正常、TTL 随机、空值缓存生效
  - [x] SubTask 4.3: 商品缓存逻辑单元测试（ProductServiceImplTest 13 个：缓存命中不查库、空值缓存命中拒绝、未命中加锁重建+随机 TTL 校验、查无商品/已下架写空值缓存、双重检查、未抢到锁重读/查库兜底、锁中断恢复中断标记、分类树、分页映射、分类展开；mock StringRedisTemplate/RedissonClient；单测内 TableInfoHelper.initTableInfo 初始化 PmsProduct/PmsCategory 元数据以支持 lambda 条件）；`mvn test` 全绿 35/35
  - [x] Bugfix: 商品分页按一级分类查询返回空 —— categoryId 由精确 eq 改为先展开为「自身 + 全部后代分类 ID」（BFS 逐层查子分类）再 IN 查询（expandCategoryIds），商品挂在二级分类下时选一级分类也能命中全部商品

- [x] Task 5: ES 商品搜索
  - [x] SubTask 5.1: 定义 ES 商品索引（IK 分词器），提供索引初始化脚本/说明 —— 索引 `mall_product`（name/subTitle: text ik_max_word/ik_smart，mainPic: keyword index:false，price: scaled_float(100)，status 随文档存储查询时过滤）；`ProductIndexInitializer` 启动时幂等建索引（analyze 探测 IK，未装插件降级 standard，ES 不可达仅告警不阻断启动）；手动脚本 `deploy/elasticsearch/init-product-index.sh`；本机 ES 已装 analysis-ik 8.15.3
  - [x] SubTask 5.2: 商品变更经 RocketMQ 同步 ES（生产者发送 + 消费者 upsert/delete，消费幂等）—— Topic `mall-product-sync`（tag: upsert/delete，消息体=productId），`ProductSyncProducer`（syncSend，SEND_OK 校验，失败抛 BizException）+ `ProductSyncConsumer`（@RocketMQMessageListener selectorExpression "upsert || delete"，upsert 以 DB 为准回查后写索引、商品不存在兜底删索引，ES 写失败异常上抛触发 MQ 重试；ES 覆盖写/删天然幂等）。Topic/消费组/索引名均可用 `mall.search.*` 配置覆盖
  - [x] SubTask 5.3: 搜索接口：关键词高亮 + 分类/价格筛选 + 销量/价格/综合排序 + 分页 —— GET /api/search（GET 白名单免登录），bool 查询：must multiMatch(name^2, subTitle) + filter status=1 / categoryId(expandCategoryIds 展开子树，复用商品模块逻辑并提升为 ProductService 接口方法) / price range；排序 default=score,sales,id / sales / priceAsc / priceDesc（均 id 降序兜底保证分页稳定），非法值按 default；高亮 <em> 全字段 numberOfFragments=0，未命中字段回退原值；pageSize 截断 [1,100]
  - [x] SubTask 5.4: 搜索同步与查询集成测试（连接 docker ES）—— `SearchSyncIntegrationTest` 10 个（独立索引/Topic `*_it` 隔离；索引映射与幂等建索引、upsert/删除文档、关键词+高亮+下架过滤、价格区间、销量/价格排序、分页、subTitle 高亮、空关键词浏览、真实 MQ 链路 upsert→消费回查 DB→写 ES 与 delete→删文档，Awaitility 等待异步消费）+ 单测 11 个（生产者 5 + 消费者 6：tag 路由/幂等/脏消息丢弃/失败上抛重试）；集成测试用 `@EnabledIf(MiddlewareConditions#searchEnvAvailable)` 守护，中间件未启动自动跳过不阻塞 mvn test。`mvn test` 全绿 71/71（新增 21），并已 HTTP 冒烟 /api/search
  - [x] 补充：存量商品全量同步入口提前实现（原计划随 Task 9 提供，因索引空导致搜索全空）—— `ProductIndexService#syncAllProducts`（listAllForIndex 查库全量商品含下架、按 500/批 bulk upsert、errors 逐条打印并抛 IllegalStateException）、`ProductService#listAllForIndex`（仅取索引字段排除 detail 大字段）、管理员接口 POST /api/admin/search/product/syncAll（AdminSearchController）；`ProductIndexSyncAllTest` 4 个单测（空数据不 bulk、逐条 upsert id 对齐、501 条拆 500+1 两批、bulk 有错误条目抛异常），`mvn test` 全绿 75/75；实测 admin 登录→syncAll 返回 13→搜「小米」命中 2 条带高亮。注意：搜索仅匹配 name/subTitle，不匹配分类名（种子数据无商品名含「手机」，搜「手机」为空属数据使然非缺陷）

- [x] Task 6: 购物车（Redis）
  - [x] SubTask 6.1: 购物车增删改查、勾选/全选、合计金额（Redis Hash 存储）。接口前缀 /api/cart：GET /cart、POST /cart（加购，已在购物车则累加数量）、PUT /cart/quantity、PUT /cart/selected、PUT /cart/selectedAll、DELETE /cart/{skuId}（幂等），均需登录。存储结构 key `mall:cart:{userId}`、field=skuId、value=条目快照 JSON（固化商品名/规格/主图/加购时单价/数量/勾选/加购时间，展示不反查商品库）；加购校验 SKU 存在 + 商品上架 + 库存充足（已有数量+新增 ≤ stock，改数量同理）；skuSpec 由 specJson 解析为值 " / " 拼接（与前端详情页约定一致），无规格/损坏回退 skuCode；查询按 addTime 升序，聚合 totalCount（quantity 之和，供角标）/selectedCount/selectedAmount；条目 JSON 损坏时记录诊断日志、删除脏数据按不存在处理（购物车无 TTL，脏数据不会自愈）
  - [x] SubTask 6.2: 购物车单元测试（CartServiceImplTest 15 个：新增快照固化商品信息与默认勾选、已加购累加数量保留勾选/加购时间、超库存/规格不存在/商品下架拒绝且不写入、改数量/勾选变更、全选 putAll、幂等删除、聚合合计与 addTime 排序、空购物车、脏数据跳过并删除；mock StringRedisTemplate 的 HashOperations 与 SkuMapper/ProductMapper）；`mvn test` 全绿 50/50

- [ ] Task 7: 订单模块 + RocketMQ 延迟关单
  - [ ] SubTask 7.1: 下单接口：防重 Token（Redis 一次性令牌）、事务内校验并扣减库存、生成订单/订单项、清购物车已购项、发送延迟关单消息
  - [ ] SubTask 7.2: 模拟支付回调（更新订单状态，幂等）
  - [ ] SubTask 7.3: 主动取消 + 超时关单消费者（回补库存、消费幂等）
  - [ ] SubTask 7.4: 订单分页列表/详情接口
  - [ ] SubTask 7.5: 下单事务、超时关单、幂等性的单元/集成测试

- [ ] Task 8: 秒杀模块
  - [ ] SubTask 8.1: 秒杀活动/商品管理与活动商品预热（库存加载到 Redis）
  - [ ] SubTask 8.2: 秒杀下单：Lua 脚本原子预扣 + 单用户限购校验，成功投递 MQ
  - [ ] SubTask 8.3: 秒杀订单消费者异步建单 + 下单结果查询接口（前端轮询）+ 简单限流
  - [ ] SubTask 8.4: 秒杀不超卖/限购的并发集成测试

- [ ] Task 9: 后台管理接口
  - [x] SubTask 9.1: 管理员分类/商品/SKU 维护接口（变更触发 ES 同步：调用 ProductSyncProducer sendUpsert/sendDelete，需提供存量商品全量同步/索引重建入口）
  - [ ] SubTask 9.2: 管理员订单列表/发货、秒杀活动配置接口

- [x] Task 10: 前端商城（基于现有 Vue3+TS 脚手架）（注：页面已完成并可通过构建；商品/购物车/订单/秒杀/管理端的后端接口尚未实现，API 契约已冻结在前端 `frontend/src/api/types.ts` + `src/api/*.ts`，Task 4-9 后端实现时须与该契约对齐）
  - [x] SubTask 10.1: Element Plus 全量注册 + 中文 locale + 图标全局注册；axios 封装（src/api/http.ts：Token 携带、Result 解包、401 统一跳登录、ElMessage 错误提示）；user store（登录态/管理员/restore/登出清理）；路由 16 条 + 登录守卫 + 管理员守卫 + 页面标题；App.vue 顶部导航（搜索框/购物车角标/用户菜单）
  - [x] SubTask 10.2: 登录/注册页（表单校验、redirect 回跳）、首页（分类侧栏+商品网格）、商品列表页（categoryId 联动）、搜索结果页（高亮 v-html 转义后渲染 + 分类/价格/排序筛选）、商品详情页（SKU 规格选择、加购 emit cart-changed、立即购买）
  - [x] SubTask 10.3: 购物车页（勾选/全选/数量/删除、合计、去结算）、确认订单页（立即购买/购物车双模式、地址单选、防重 Token 下单）、我的订单页（状态 tab、支付/取消）、订单详情页（步骤条、地址快照解析）、收货地址页（CRUD + 设默认）
  - [x] SubTask 10.4: 秒杀活动页（倒计时、活动状态推算、抢购 + taskId 轮询结果 QUEUED/SUCCESS/FAIL）
  - [x] SubTask 10.5: 简易后台管理页（AdminLayout 侧边菜单；商品维护含两级分类/上下架/SKU 管理、订单管理含发货、秒杀活动管理含活动商品维护）
  - [x] SubTask 10.6: 用户 store Vitest 单测 6 个（登录/管理员/restore/登出/登出失败清理；`npm run test:unit` 全绿），`npm run build`（vue-tsc 类型检查 + vite 构建）通过
  - [x] SubTask 10.7（美化重构）: 抽出通用组件 `components/layout/AppHeader.vue`（sticky 导航/路由高亮/头像下拉/购物车角标 defineExpose）、`AppFooter.vue`、`components/user/AuthShell.vue`（登录/注册共用外壳）；登录/注册/地址页美化；主题色收敛到 `main.css` `:root` CSS 变量；约定写入 AGENTS.md 第 6.1 节；单测/类型检查/构建全部通过

- [ ] Task 11: 联调与交付
  - [ ] SubTask 11.1: 全链路联调：注册登录 → 搜索 → 加购 → 下单 → 支付 → 超时关单 → 秒杀
  - [ ] SubTask 11.2: 完善 AGENTS.md 运行说明（环境要求、启动顺序、演示账号、种子数据说明）
  - [ ] SubTask 11.3: `mvn test` 与 `npm run test:unit` 全绿，`npm run build` 与后端打包通过

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 2]
- [Task 4] depends on [Task 3]（依赖登录拦截器联调）
- [Task 5] depends on [Task 2]（与 Task 3/4 可并行，仅需骨架）
- [Task 6] depends on [Task 3]
- [Task 7] depends on [Task 3, Task 6]
- [Task 8] depends on [Task 7]
- [Task 9] depends on [Task 4, Task 5, Task 7]
- [Task 10] depends on [Task 3]（需登录接口），页面随各后端模块就绪并行推进
- [Task 11] depends on [Task 1-10]
