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
- **brokerIP1 = 127.0.0.1（2026-09-22 调整）**：原 `host.docker.internal` 依赖宿主机 hosts 解析，但本机 hosts 中的条目指向了一个过期的局域网 IP（192.168.30.245，本机现网段为 192.168.155.x），导致宿主机 Java 客户端拿到的 Broker 地址不可达，发送报 `sendDefaultImpl call timeout`。现改为 `127.0.0.1`（依赖 docker-compose 把 10911/10909 发布到宿主机回环）。代价：**Proxy 容器会经 NS 拿到该地址后连自己而不可用**（项目客户端走 name-server 9876 的 remoting 协议，不经 Proxy，无影响）。若以后修复了 hosts 条目，可改回 `host.docker.internal` 让 Proxy 恢复。
- **hosts 过期条目排障提示**：宿主机 Java 客户端连 RocketMQ 超时但容器内组件正常时，先 `Resolve-DnsName host.docker.internal` 比对 `ipconfig` 实际 IP（hosts 文件在 `C:\Windows\System32\drivers\etc\hosts`，修改需管理员权限）。
- **卷权限（重要）**：容器以 `rocketmq` 用户（uid/gid 3000）运行，命名卷首次创建后属主是 root，Broker 会因 store 目录不可写而启动失败（日志只有 NPE 噪音）。首次 `up -d` 后若 Broker 起不来，执行一次修复：

```bash
docker run --rm --user root \
  -v my-mall_rocketmq-namesrv-logs:/a -v my-mall_rocketmq-broker-logs:/b \
  -v my-mall_rocketmq-broker-store:/c -v my-mall_rocketmq-proxy-logs:/d \
  apache/rocketmq:5.3.2 sh -c "chown -R 3000:3000 /a /b /c /d"
docker compose -f deploy/docker-compose.yml up -d
```

## Elasticsearch

### IK 分词器

IK 分词器**无法通过 docker-compose 自动安装**，版本必须与 ES 严格一致（当前已安装 **analysis-ik 8.15.3**，2026-09-22）：

```bash
docker exec -it my-mall-elasticsearch bin/elasticsearch-plugin install \
  https://get.infini.cloud/elasticsearch/analysis-ik/8.15.3
docker restart my-mall-elasticsearch
```

验证：`curl http://localhost:9200/_cat/plugins` 应看到 `analysis-ik`。

**降级策略**：若 IK 不可用（未安装/装失败），后端 `ProductIndexServiceImpl` 建索引前会先 analyze 探测，不可用时自动降级 ES 内置 `standard`（中文按单字切分，搜索质量下降但不阻塞），无需改代码。

### 商品索引初始化

- 推荐：后端启动时 `ProductIndexInitializer` 幂等自建（不存在才建，写入前亦会自愈）。
- 手动/运维：`bash deploy/elasticsearch/init-product-index.sh [索引名] [ES地址]`（默认 `mall_product`）。
- 重建索引：先 `curl -X DELETE http://localhost:9200/mall_product`，重启后端或执行脚本即可；存量数据需 Task 9 管理端的全量同步入口回灌。
