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

- [x] Task 7: 订单模块 + RocketMQ 延迟关单
  - [x] SubTask 7.1: 下单接口：防重 Token（Redis 一次性令牌）、事务内校验并扣减库存、生成订单/订单项、清购物车已购项、发送延迟关单消息 —— GET /api/order/token（UUID 令牌写 `mall:order:token:{token}`=userId，TTL 10min 可配 `mall.order.token-ttl-minutes`）；POST /api/order（@Transactional）：Token 原子消费（GET 校验绑定用户 + DELETE 返回值判定，重放拒绝）→ 地址归属校验 → 同 SKU 条目合并（防拆单绕过库存）→ `SkuMapper#deductStock` 条件扣减（UPDATE ... WHERE stock >= qty，DB 行锁防超卖，影响 0 行即库存不足）→ 生成订单（orderNo=时间戳+6位随机，唯一索引兜底；addressSnapshot 为 6 字段收货信息 JSON，与前端 OrderDetailView 契约一致）与订单项（固化商品名/规格" / "拼接/单价快照）→ 事务提交后 afterCommit 发送延迟关单消息 + 逐个清购物车已购项（事务回滚不发送不清理）
  - [x] SubTask 7.2: 模拟支付回调 POST /api/order/pay/{orderNo}（幂等）—— 条件更新仅 0待支付→1已支付（写 payTime），影响 0 行时按当前状态分流：已支付幂等返回成功（不再累加销量）、其他状态抛「不可支付」；支付成功后按订单项累加 pms_product.sales
  - [x] SubTask 7.3: 主动取消 PUT /api/order/cancel/{orderNo} + 超时关单消费者（回补库存、消费幂等）—— 共用 `tryCancel`：条件更新仅 0待支付→4已取消，成功才回补 SKU 库存（restockStock），保证支付与关单并发时只有一方生效、库存只回补一次；主动取消：越权按订单不存在处理、已取消幂等、已支付抛「不可取消」；消费者 `OrderCloseConsumer`（RocketMQListener，Topic `mall-order-close` / 消费组 `mall-order-close-consumer` 可配）：订单不存在（下单事务回滚残留消息）仅告警确认消费；非待支付状态静默跳过；生产者 `OrderCloseProducer` 下单 afterCommit 发送，消息发送失败仅告警不阻断已提交订单；延迟级别可配 `mall.order.close-delay-level`（默认 4=30s 便于演示，生产建议 16=30m）
  - [x] SubTask 7.4: 订单分页列表/详情接口 —— GET /api/order/page（仅本人订单，status 状态 tab + orderNo 模糊，createTime/id 降序，pageSize 截断 [1,50]，批量查订单项按 orderId 分组组装）；GET /api/order/{orderNo}（归属校验防越权，他人订单按不存在处理）；返回 OrderVO（含 items，addressSnapshot 为原始 JSON 字符串由前端解析）
  - [x] SubTask 7.5: 单元测试 —— OrderServiceImplTest 24 个（Token 写入绑定用户与 TTL、Token 缺失/绑定他人/重放删除失败拒绝、成功下单扣库存+订单/订单项快照断言、同 SKU 合并扣减、afterCommit 触发发送延迟消息+清购物车与事务未提交不触发、库存不足/商品下架/地址越权拒绝且不落单、支付成功累加销量、重复支付幂等不加销量、已取消不可支付、取消回补库存、已取消幂等不重复回补、已支付不可取消、越权取消拒绝、关单关单回补/订单不存在跳过/已支付跳过、分页组装、详情越权拒绝；TableInfoHelper 初始化 OmsOrder/OmsOrderItem，TransactionSynchronizationManager.initSynchronization 支持 afterCommit 验证）；`mvn test` 全绿 99/99（新增 24）。已 HTTP 冒烟：下单→防重重放拒绝→详情→支付→重复支付幂等→已支付取消拒绝→分页→加购下单清购物车→30s 超时关单 status=4→SKU8 库存 500-2(支付)=498 ✓

- [x] Task 8: 秒杀模块
  - [x] SubTask 8.1: 秒杀活动/商品管理与活动商品预热（库存加载到 Redis）—— 实体/Mapper（SmsSeckillActivity/SmsSeckillProduct + 条件扣减/回补 `deductSeckillStock`/`restockSeckillStock`）；活动列表 GET /api/seckill/activities（GET 白名单免登录，仅上线未结束活动，商品联查 pms_product 取名称/主图/原价，剩余库存优先读 Redis `mall:seckill:stock:{spId}`、key 缺失回退 DB）；预热 `SeckillWarmUpRunner`（ApplicationRunner 启动时把「上线且未结束」活动的秒杀库存 SET 到 Redis，以 DB 为基准整体覆盖，失败仅告警不阻断启动）
  - [x] SubTask 8.2: 秒杀下单：Lua 脚本原子预扣 + 单用户限购校验，成功投递 MQ —— POST /api/seckill/{seckillProductId}（需登录）：简单限流（固定窗口 1s 计数，`mall.seckill.rate-limit-per-second` 默认 3）→ 校验秒杀商品/活动进行中/商品上架 → Lua 脚本（`SeckillLua`，KEYS=库存key+已购key，ARGV=限购数+TTL）原子执行「限购校验+库存预扣+已购+1」，返回 1成功/-1售罄/-2超限购/-3未预热；已购计数 key `mall:seckill:purchased:{spId}:{userId}` TTL=活动结束+1h；成功写 QUEUED 任务（`mall:seckill:task:{taskId}`，TTL 30min 可配）并投递 MQ（Topic `mall-seckill-order` 可配，消息体 JSON{taskId,userId,seckillProductId}）；MQ 发送失败回滚 Redis 预扣（库存+1、已购-1）并删任务、抛异常拒绝本次抢购
  - [x] SubTask 8.3: 秒杀订单消费者异步建单 + 下单结果查询接口（前端轮询）+ 简单限流 —— `SeckillOrderConsumer`（消费组 `mall-seckill-order-consumer` 可配）：任务非 QUEUED（重复投递/已处理）跳过保证消费幂等、任务不存在（残留消息）告警确认；建单 @Transactional：DB 条件扣减秒杀库存（最终防线，0 行=售罄 → FAIL+归还 Redis）→ 生成订单（秒杀价快照、收货地址取用户默认地址可空）+ 订单项（spec 解析复用规则）→ afterCommit 发送延迟关单消息（复用 OrderCloseProducer，秒杀单同样 30s 未支付自动关单）→ 任务置 SUCCESS 回写 orderNo；系统异常由消费者兜底 failTask（任务置 FAIL + 归还 Redis 预扣，不重试防重复建单）；结果查询 GET /api/seckill/result?taskId=（需登录，校验任务归属防越权探测，不存在/过期/他人任务统一返回 FAIL）
  - [x] SubTask 8.4: 秒杀不超卖/限购的并发集成测试 —— `SeckillIntegrationTest` 3 个（真实 MySQL/Redis/RocketMQ，独立 ID 段 990xxx + `*_it` Topic/消费组隔离，`MiddlewareConditions#seckillEnvAvailable` 守护中间件未启动自动跳过）：20 用户并发抢 5 件库存恰好 5 成功 15 失败且 Redis 预扣归零/DB 库存未动（不超卖）、单用户 10 并发仅 1 次成功（限购）、真实 MQ 消费后所有成功任务变 SUCCESS 且订单数与成功数一致、DB 秒杀库存扣减至 0（最终防线）；`SeckillServiceImplTest` 18 个单测（活动列表 Redis 优先/DB 回退、Lua 各返回码分流、限流拒绝、MQ 失败回滚、结果查询越权/过期、建单成功/DB 售罄归还/幂等跳过/failTask 归还）。`mvn test` 全绿 120/120（新增 21）。已 HTTP 冒烟：活动列表含实时库存 → 登录抢购返回 taskId → 轮询 SUCCESS 返回订单号 → 订单页可见秒杀价订单（含默认地址快照与订单项）→ 重复抢购被限购拒绝 → Redis 库存 10→9 → 未登录 POST 返回 401

- [x] Task 9: 后台管理接口
  - [x] SubTask 9.1: 管理员分类/商品/SKU 维护接口（变更触发 ES 同步：调用 ProductSyncProducer sendUpsert/sendDelete，需提供存量商品全量同步/索引重建入口）（注：此前勾选有误、代码实际未落地，本次随 Task 9 收尾一并实现）—— 新建 `com.mall.admin` 模块（AdminCategoryService/AdminProductService + impl）：分类增改删（parentId=0 一级/挂一级下为二级、层级固定两级、删除前校验无子分类且无商品）+ 管理端分类树 GET /admin/category/tree（含禁用分类）；商品分页 GET /admin/product/page（含下架、keyword 模糊、不加载 detail）、新增 POST /admin/product（必须挂二级分类）、修改 PUT /admin/product/{id}（仅更新非空字段）、上下架 PUT /admin/product/status/{id}，均 @Transactional 内发送 ES upsert（MQ 失败抛异常整体回滚）；SKU 增改删 POST /admin/product/{productId}/sku、PUT/DELETE /admin/sku/{id}（后修复：ES 文档不含 SKU 字段、搜索展示价取商品表 price，SKU 变更现会重算 SKU 最低价回写商品展示价（无 SKU 保持原价）并在事务内触发 ES upsert + 删除详情缓存 `mall:product:detail:{id}`；商品修改/上下架同样清详情缓存）
  - [x] SubTask 9.2: 管理员订单列表/发货、秒杀活动配置接口 —— GET /admin/order/page（全量订单、orderNo 模糊 + status 过滤、pageSize 截断 [1,50]、含订单项，复用 OrderVO/OrderItemVO）；发货 PUT /admin/order/deliver/{orderNo}（条件更新仅 1已支付→2已发货，与支付/取消并发安全；重复发货幂等，其他状态抛「不可发货」）；秒杀活动 POST /admin/seckill/activity（默认下线，end>start 校验）、PUT /admin/seckill/activity/{id}（仅更新非空字段；置为上线(1)时以 DB 为基准整体覆盖预热秒杀库存到 Redis `mall:seckill:stock:{spId}`，与 SeckillWarmUpRunner 语义一致）；活动商品 POST /admin/seckill/product（校验活动存在/SKU 属于该商品、limitPerUser 缺省 1；活动上线中则同步写 Redis 预热）、DELETE /admin/seckill/product/{id}（删 DB 行并清理 Redis 预热 key）
  - [x] SubTask 9.3（模块调整 + 测试）: AdminSearchController 由 `com.mall.search.controller` 迁至 `com.mall.admin.controller`（接口路径不变 POST /api/admin/search/product/syncAll，依赖 search 模块 ProductIndexService，与其他管理端控制器归属一致）；单元测试 44 个（AdminCategoryServiceImplTest 11 + AdminProductServiceImplTest 14：二级分类校验/ES upsert 触发/SKU 维护/SKU 变更展示价回写与缓存失效；AdminOrderServiceImplTest 7：分页组装/发货条件更新幂等/非法状态拒绝；AdminSeckillServiceImplTest 12：时间校验/上线预热 Redis/活动商品归属/删除清理），`mvn test` 全绿 164/164。已 HTTP 冒烟：管理员登录→分类增改树（含禁用）删→商品分页(13)/上下架→syncAll=13→订单分页(14)/不存在订单发货拒绝→秒杀活动上线→添加活动商品 Redis 预热 stock=3→删除商品 Redis key 清理；普通用户访问 /admin/** 返回 403

- [x] Task 10: 前端商城（基于现有 Vue3+TS 脚手架）（注：页面已完成并可通过构建；商品/购物车/订单/秒杀/管理端的后端接口尚未实现，API 契约已冻结在前端 `frontend/src/api/types.ts` + `src/api/*.ts`，Task 4-9 后端实现时须与该契约对齐）
  - [x] SubTask 10.1: Element Plus 全量注册 + 中文 locale + 图标全局注册；axios 封装（src/api/http.ts：Token 携带、Result 解包、401 统一跳登录、ElMessage 错误提示）；user store（登录态/管理员/restore/登出清理）；路由 16 条 + 登录守卫 + 管理员守卫 + 页面标题；App.vue 顶部导航（搜索框/购物车角标/用户菜单）
  - [x] SubTask 10.2: 登录/注册页（表单校验、redirect 回跳）、首页（分类侧栏+商品网格）、商品列表页（categoryId 联动）、搜索结果页（高亮 v-html 转义后渲染 + 分类/价格/排序筛选）、商品详情页（SKU 规格选择、加购 emit cart-changed、立即购买）
  - [x] SubTask 10.3: 购物车页（勾选/全选/数量/删除、合计、去结算）、确认订单页（立即购买/购物车双模式、地址单选、防重 Token 下单）、我的订单页（状态 tab、支付/取消）、订单详情页（步骤条、地址快照解析）、收货地址页（CRUD + 设默认）
  - [x] SubTask 10.4: 秒杀活动页（倒计时、活动状态推算、抢购 + taskId 轮询结果 QUEUED/SUCCESS/FAIL）
  - [x] SubTask 10.5: 简易后台管理页（AdminLayout 侧边菜单；商品维护含两级分类/上下架/SKU 管理、订单管理含发货、秒杀活动管理含活动商品维护）
  - [x] SubTask 10.6: 用户 store Vitest 单测 6 个（登录/管理员/restore/登出/登出失败清理；`npm run test:unit` 全绿），`npm run build`（vue-tsc 类型检查 + vite 构建）通过
  - [x] SubTask 10.7（美化重构）: 抽出通用组件 `components/layout/AppHeader.vue`（sticky 导航/路由高亮/头像下拉/购物车角标 defineExpose）、`AppFooter.vue`、`components/user/AuthShell.vue`（登录/注册共用外壳）；登录/注册/地址页美化；主题色收敛到 `main.css` `:root` CSS 变量；约定写入 AGENTS.md 第 6.1 节；单测/类型检查/构建全部通过
  - [x] SubTask 10.8（后台补全与修复）: 新增分类管理页 `AdminCategoryView.vue`（树形 el-table 展示两级分类，新增一级/子分类、编辑名称排序、启停开关确认、删除确认——有子分类/有商品由后端拒绝并提示；路由 /admin/categories 与侧边菜单入口，/admin 重定向改为分类管理；`Category` 类型补 `status` 字段，`adminUpdateCategory` 的 name 放宽为可选对齐「仅更新非空字段」契约）；秒杀管理页改调管理端全量活动列表 GET /admin/seckill/activity/list（C 端接口只查上线中活动，新增默认下线的活动会不可见）；商品页 SKU 保存/删除后同步刷新主商品列表（SKU 变更会重算展示价/库存）；`npm run build` 通过

- [x] Task 11: 联调与交付
  - [x] SubTask 11.1: 全链路联调：注册登录 → 搜索 → 加购 → 下单 → 支付 → 超时关单 → 秒杀（HTTP 逐环节验证 + Chrome 浏览器完整闭环实测：登录→搜索「小米」→详情加购→结算→提交订单→去支付→订单详情；防重 Token 重放拒绝、支付幂等、30s 关单库存回补、秒杀 taskId 轮询 SUCCESS+限购拒绝均通过；注意管理端接口带 /api 前缀、种子秒杀活动已过期需后台新建上线）
  - [x] SubTask 11.2: 完善 AGENTS.md 运行说明（环境要求、启动顺序、演示账号、种子数据说明）—— 第 4 节目录树各业务包标记「已开发」；启动顺序/种子数据说明/演示动线补入 `docs/dev-guide.md`（AGENTS 第 8 节引用）
  - [x] SubTask 11.3: `mvn test` 与 `npm run test:unit` 全绿，`npm run build` 与后端打包通过 —— `mvn clean package` BUILD SUCCESS（163/163 全绿，含 ES/MQ/秒杀集成测试，中间件在线未跳过）、`npm run test:unit` 6/6、`npm run build` 通过

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
