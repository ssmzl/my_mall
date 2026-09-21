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

## 演示账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `123456` | 管理员（role=1） |
| `user` | `123456` | 普通用户（role=0） |

密码在数据库中以 BCrypt 存储（明文 `123456`，哈希见 `deploy/mysql/init/02-seed.sql` 注释）。
