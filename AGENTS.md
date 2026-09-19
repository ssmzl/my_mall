# AGENTS.md — my_mall 全栈商城

## 1. 项目简介

`my_mall` 是一个**简历级全栈商城项目**：Vue3 + TypeScript 前端 + Spring Boot 单体后端 + MySQL / Redis / RocketMQ / Elasticsearch 中间件全家桶，覆盖电商核心链路（用户、商品、搜索、购物车、订单、秒杀、后台管理），用于学习演示与求职简历。

前端代码位于 `frontend/`（已存在），后端代码位于 `backend/`（按本文件约定逐步建设）。

## 2. 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        浏览器 / 用户                          │
└──────────────────────────┬───────────────────────────────────┘
                           │ HTTP / JSON (统一 Result<T>)
┌──────────────────────────▼───────────────────────────────────┐
│  前端  Vue 3 + TypeScript + Vite + Element Plus + Pinia      │
│        （frontend/，dev 端口默认 5173，反向代理 /api）        │
└──────────────────────────┬───────────────────────────────────┘
                           │ REST API
┌──────────────────────────▼───────────────────────────────────┐
│  后端  Spring Boot 3.5 单体（backend/，端口 8080）            │
│        Spring Security + JWT · MyBatis-Plus · 参数校验        │
│        业务包：user / product / search / cart / order /       │
│               seckill / admin                                 │
└───┬──────────┬──────────────┬────────────────┬──────────────┘
    │          │              │                │
┌───▼───┐  ┌───▼────┐  ┌──────▼───────┐  ┌─────▼─────────┐
│ MySQL │  │ Redis  │  │  RocketMQ    │  │ Elasticsearch │
│  8.0  │  │   7    │  │ NS 9876      │  │ 8.15  9200    │
│ 3307  │  │ 6379   │  │ Broker 10911 │  │ Kibana 5601   │
└───────┘  └────────┘  │ Proxy  8081  │  └───────────────┘
                       └──────────────┘
- MySQL：业务数据（库名 my_mall，utf8mb4）
- Redis：缓存 / 分布式锁 / 秒杀库存预热（appendonly 开启）
- RocketMQ：订单超时关单、秒杀削峰等异步消息（自动建 Topic）
- ES：商品搜索（IK 分词，见第 8 节）
```

所有中间件由 `deploy/docker-compose.yml` 统一编排（项目名 `my-mall`，网络 `my-mall-net`，数据用命名卷持久化）。

## 3. 技术栈与版本

| 层 | 技术 | 版本 |
| --- | --- | --- |
| 后端 | JDK | 17 |
| 后端 | Spring Boot | 3.5.x |
| 后端 | MyBatis-Plus | 3.5.x |
| 后端 | Spring Security + JWT | 6.x（随 Boot） |
| 前端 | Vue | 3.5 |
| 前端 | TypeScript / Vite | 5.x / 随 frontend |
| 前端 | Element Plus | 2.x |
| 数据 | MySQL | 8.0 |
| 缓存 | Redis | 7 |
| 消息 | RocketMQ | 5.3（apache/rocketmq:5.3.2） |
| 搜索 | Elasticsearch / Kibana | 8.15（8.15.3，两者版本必须一致） |

## 4. 目录结构约定

```
my_mall/
├── AGENTS.md                # 本文件
├── deploy/                  # 基础设施
│   ├── docker-compose.yml   # 中间件编排（MySQL/Redis/RocketMQ/ES/Kibana）
│   ├── mysql/init/          # MySQL 首次启动自动执行的 SQL
│   │   ├── 01-schema.sql    #   建库建表
│   │   └── 02-seed.sql      #   种子数据
│   └── rocketmq/
│       └── broker.conf      # Broker 配置（autoCreateTopicEnable=true 等）
├── frontend/                # Vue3 + TS 前端（已存在）
└── backend/                 # Spring Boot 后端（单体，按业务包划分）
    └── src/main/java/com/example/mall/
        ├── common/          # Result<T>、异常、常量、工具类
        ├── config/          # MyBatis-Plus / Redis / Web 等配置
        ├── security/        # Spring Security、JWT 过滤器
        ├── user/            # 用户、地址（ums_*）
        ├── product/         # 分类、商品、SKU（pms_*）
        ├── search/          # ES 商品搜索
        ├── cart/            # 购物车（Redis）
        ├── order/           # 订单（oms_*）
        ├── seckill/         # 秒杀（sms_*）
        └── admin/           # 后台管理接口
```

每个业务包内部分层：`controller` / `service`（接口 + `service/impl` 实现）/ `mapper` / `entity` / `dto`。

## 5. 常用命令

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

## 6. 编码规范

- **Java**：驼峰命名；类 UpperCamelCase，方法/字段 lowerCamelCase；常量 UPPER_SNAKE_CASE。
- **统一返回**：所有 Controller 返回 `Result<T>`（code / message / data），不直接裸返回实体。
- **分层**：Service 一律「接口 + 实现类」（`XxxService` + `XxxServiceImpl`），Controller 不直接依赖 Mapper。
- **异常**：严格抛出业务异常（如 `BizException`），由全局异常处理器转 `Result`；**严禁空 catch 吞异常**。
- **持久层**：默认使用 MyBatis-Plus（`BaseMapper` + `QueryWrapper`/`LambdaQueryWrapper`）；只有复杂多表/报表查询才允许手写 XML。
- **SQL/表**：主键 `BIGINT AUTO_INCREMENT`；库存类字段 `INT UNSIGNED` 非负；金额 `DECIMAL(10,2)`；统一 `create_time` / `update_time`。
- **前端**：Composition API + `<script setup lang="ts">`；接口统一走封装的 axios 实例。

## 7. Git 约定

- 除非用户明确要求，**默认不执行任何 git 操作**（commit / push / branch / merge 等一律先征得同意）。

## 8. 中间件使用要点

### 连接信息（宿主机视角）

| 服务 | 地址 | 账号 |
| --- | --- | --- |
| MySQL | `localhost:3307`（宿主机 3306 被本机 MySQL 服务占用），库 `my_mall` | root / root123 |
| Redis | `localhost:6379` | 无密码（本地开发） |
| RocketMQ NameServer | `localhost:9876` | — |
| RocketMQ Proxy（gRPC） | `localhost:8081` | 5.x 客户端优先走 Proxy |
| Elasticsearch | `http://localhost:9200` | 无认证（xpack.security 已关） |
| Kibana | `http://localhost:5601` | — |

### RocketMQ

- Broker 配置在 `deploy/rocketmq/broker.conf`，已开启 `autoCreateTopicEnable = true`。
- `brokerIP1 = host.docker.internal`：保证宿主机 Java 客户端与容器内 Proxy 都能连到 Broker（Windows Docker Desktop 场景）。
- **卷权限（重要）**：容器以 `rocketmq` 用户（uid/gid 3000）运行，命名卷首次创建后属主是 root，Broker 会因 store 目录不可写而启动失败（日志只有 NPE 噪音）。首次 `up -d` 后若 Broker 起不来，执行一次修复：

```bash
docker run --rm --user root \
  -v my-mall_rocketmq-namesrv-logs:/a -v my-mall_rocketmq-broker-logs:/b \
  -v my-mall_rocketmq-broker-store:/c -v my-mall_rocketmq-proxy-logs:/d \
  apache/rocketmq:5.3.2 sh -c "chown -R 3000:3000 /a /b /c /d"
docker compose -f deploy/docker-compose.yml up -d
```

### Elasticsearch IK 分词器

IK 分词器**无法通过 docker-compose 自动安装**，首次需要手动执行（版本必须与 ES 严格一致，当前为 8.15.3）：

```bash
docker exec -it my-mall-elasticsearch bin/elasticsearch-plugin install \
  https://get.infini.cloud/elasticsearch/analysis-ik/8.15.3
docker restart my-mall-elasticsearch
```

也可改用 analysis-ik 对应 ES 版本的 GitHub release zip 离线安装：`bin/elasticsearch-plugin install file:///path/to/analysis-ik-8.15.3.zip`。

**降级策略**：若 IK 不可用（未安装/装失败），建索引时先用 ES 内置 `standard` 分词器顶上，代码层（search 模块）会对分词器做兼容处理，不阻塞开发进度。

## 9. 环境要求

- Docker Desktop（Windows，Linux 容器模式）
- JDK 17+
- Node.js 20+
- Maven 3.8+（或使用 mvnw）

## 10. 演示账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `123456` | 管理员（role=1） |
| `user` | `123456` | 普通用户（role=0） |

密码在数据库中以 BCrypt 存储（明文 `123456`，哈希见 `deploy/mysql/init/02-seed.sql` 注释）。
