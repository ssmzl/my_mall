# 中间件使用要点

> 本文件由 `AGENTS.md` 第 8 节引用。涉及中间件连接、启动排障、插件安装时先阅读本文件。

## 连接信息（宿主机视角）

| 服务 | 地址 | 账号 |
| --- | --- | --- |
| MySQL | `localhost:3307`（宿主机 3306 被本机 MySQL 服务占用），库 `my_mall` | root / root123 |
| Redis | `localhost:6379` | 无密码（本地开发） |
| RocketMQ NameServer | `localhost:9876` | — |
| RocketMQ Proxy（gRPC） | `localhost:8081` | 5.x 客户端优先走 Proxy |
| Elasticsearch | `http://localhost:9200` | 无认证（xpack.security 已关） |
| Kibana | `http://localhost:5601` | — |

所有中间件由 `deploy/docker-compose.yml` 统一编排（项目名 `my-mall`，网络 `my-mall-net`，数据用命名卷持久化）。

## RocketMQ

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

## Elasticsearch IK 分词器

IK 分词器**无法通过 docker-compose 自动安装**，首次需要手动执行（版本必须与 ES 严格一致，当前为 8.15.3）：

```bash
docker exec -it my-mall-elasticsearch bin/elasticsearch-plugin install \
  https://get.infini.cloud/elasticsearch/analysis-ik/8.15.3
docker restart my-mall-elasticsearch
```

也可改用 analysis-ik 对应 ES 版本的 GitHub release zip 离线安装：`bin/elasticsearch-plugin install file:///path/to/analysis-ik-8.15.3.zip`。

**降级策略**：若 IK 不可用（未安装/装失败），建索引时先用 ES 内置 `standard` 分词器顶上，代码层（search 模块）会对分词器做兼容处理，不阻塞开发进度。
