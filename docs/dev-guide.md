# 开发环境与常用命令

> 本文件由 `AGENTS.md` 第 8 节引用。搭建/启动环境、执行构建与测试命令、使用演示账号时先阅读本文件。

## 环境要求

- Docker Desktop（Windows，Linux 容器模式）
- JDK 17+
- Node.js 20+
- Maven 3.8+（或使用 mvnw）

## 常用命令

```bash
# ---- 基础设施（在仓库根目录执行） ----
docker compose -f deploy/docker-compose.yml up -d      # 启动全部中间件
docker compose -f deploy/docker-compose.yml ps         # 查看状态（healthy）
docker compose -f deploy/docker-compose.yml logs -f mysql   # 看某服务日志
docker compose -f deploy/docker-compose.yml down       # 停止（数据保留在命名卷）
docker compose -f deploy/docker-compose.yml down -v    # 停止并删除数据卷（慎用！会清库）

# ---- 后端（backend/ 目录下） ----
mvn spring-boot:run    # 本地启动（默认 8080）
mvn test               # 跑单测
mvn clean package      # 打包

# ---- 前端（frontend/ 目录下） ----
npm install
npm run dev            # 本地启动（默认 5173）
npm run test:unit      # 单测（vitest）
npm run build          # 生产构建
```

中间件连接信息与已知坑见 [middleware.md](middleware.md)。

## 启动顺序（首次/日常演示）

1. **中间件**：`docker compose -f deploy/docker-compose.yml up -d`，等全部容器 `healthy`（`docker compose -f deploy/docker-compose.yml ps`）。
2. **后端**：`backend/` 下 `mvn spring-boot:run`（端口 8080，context-path `/api`）。启动时自动：幂等创建 ES 索引、预热上线中秒杀活动库存到 Redis。接口文档 http://localhost:8080/api/doc.html 。
3. **前端**：`frontend/` 下 `npm run dev`（端口 5173，`/api` 反向代理到 8080），浏览器打开 http://localhost:5173 。

> 停止顺序无强依赖；`docker compose down` 保留数据卷，`down -v` 会清库（初始化 SQL 只在卷首次创建时执行，清库后重启会重新建表和种子数据）。

## 种子数据说明

初始化 SQL 位于 `deploy/mysql/init/`（仅 MySQL 数据卷**首次创建**时自动执行）：

- `01-schema.sql`：建库 `my_mall` + 9 张业务表（ums_user/ums_address、pms_category/pms_product/pms_sku、oms_order/oms_order_item、sms_seckill_activity/sms_seckill_product）。
- `02-seed.sql`：种子数据 —— 3 个一级分类 + 6 个二级分类、14 个在售商品（含 SKU）、演示账号（见下表）、已结束的示例秒杀活动。
- **秒杀演示注意**：种子秒杀活动已过期，C 端「秒杀」页可能为空。用管理员登录后台「秒杀管理」新建活动（结束时间选未来）并置为上线、添加活动商品后即可抢购；管理端所有接口前缀为 `/api/admin/**`。
- 商品 ID 段 `990xxx`（分类 99x、活动 990xxx 等）为自动化集成测试保留段，联调时出现属测试遗留数据，不影响演示。

## 演示账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `123456` | 管理员（role=1） |
| `user` | `123456` | 普通用户（role=0） |

密码在数据库中以 BCrypt 存储（明文 `123456`，哈希见 `deploy/mysql/init/02-seed.sql` 注释）。

## 一键演示动线（联调已验证）

注册/登录 → 首页/分类 → 搜索「小米」（高亮）→ 商品详情选规格加购 → 购物车结算 → 确认订单提交（防重 Token）→ 我的订单「去支付」→ 订单详情查看快照；不支付等 30 秒可观察延迟消息自动关单与库存回补（`mall.order.close-delay-level` 默认 4=30s，便于演示）。
