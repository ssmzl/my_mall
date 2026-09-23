#!/usr/bin/env bash
# =============================================================================
# ES 商品搜索索引初始化脚本（SubTask 5.1）
#
# 说明：
# - 后端 ProductIndexInitializer 会在应用启动时自动创建索引（推荐方式），
#   本脚本用于手动初始化 / 运维重建场景。
# - 分词器优先 IK（写入 ik_max_word / 检索 ik_smart）；未安装 analysis-ik
#   插件时自动降级 ES 内置 standard（中文按单字切分，搜索质量下降但不阻塞）。
#   IK 安装方式见 docs/middleware.md。
#
# 用法：
#   bash init-product-index.sh [索引名] [ES地址]
#   默认: bash init-product-index.sh mall_product http://localhost:9200
# =============================================================================
set -euo pipefail

INDEX="${1:-mall_product}"
ES="${2:-http://localhost:9200}"

# 1) 探测 IK 分词器
ANALYZER="standard"; SEARCH_ANALYZER="standard"
if curl -fsS "$ES/_cat/plugins" 2>/dev/null | grep -q "analysis-ik"; then
  ANALYZER="ik_max_word"; SEARCH_ANALYZER="ik_smart"
  echo "[INFO] 检测到 analysis-ik 插件，使用 IK 分词"
else
  echo "[WARN] 未检测到 analysis-ik 插件，降级使用 standard 分词器"
fi

# 2) 已存在则跳过（幂等）
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' "$ES/$INDEX" || true)
if [ "$HTTP_CODE" = "200" ]; then
  echo "[INFO] 索引 $INDEX 已存在，跳过创建"
  exit 0
fi

# 3) 创建索引（单分片零副本，本地开发配置）
curl -fsS -X PUT "$ES/$INDEX" -H 'Content-Type: application/json' -d @- <<EOF
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "id":         { "type": "long" },
      "categoryId": { "type": "long" },
      "name":       { "type": "text", "analyzer": "$ANALYZER", "search_analyzer": "$SEARCH_ANALYZER" },
      "subTitle":   { "type": "text", "analyzer": "$ANALYZER", "search_analyzer": "$SEARCH_ANALYZER" },
      "mainPic":    { "type": "keyword", "index": false },
      "price":      { "type": "scaled_float", "scaling_factor": 100 },
      "sales":      { "type": "integer" },
      "status":     { "type": "integer" }
    }
  }
}
EOF

echo "[INFO] 索引 $INDEX 创建完成"
