package com.mall.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mall.product.entity.PmsProduct;
import com.mall.search.entity.EsProduct;
import com.mall.search.service.ProductIndexService;

import java.io.IOException;

/**
 * ES 商品索引维护实现（基于 ElasticsearchClient DSL）。
 *
 * <p>索引设计（mall_product）：
 * <ul>
 *   <li>name / subTitle：text，分词优先 IK（写入 ik_max_word、检索 ik_smart），未安装插件降级 standard；</li>
 *   <li>mainPic：keyword 且 index:false，仅随文档存储不参与检索；</li>
 *   <li>price：scaled_float(scalingFactor=100)，对应数据库 DECIMAL(10,2)，避免浮点误差；</li>
 *   <li>status 随文档存储，查询时 filter 过滤，下架不删文档。</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductIndexServiceImpl implements ProductIndexService {

    private static final String IK_INDEX_ANALYZER = "ik_max_word";
    private static final String IK_SEARCH_ANALYZER = "ik_smart";
    private static final String FALLBACK_ANALYZER = "standard";
    private static final String ALREADY_EXISTS = "resource_already_exists_exception";

    private final ElasticsearchClient elasticsearchClient;
    private final String indexName;

    public ProductIndexServiceImpl(ElasticsearchClient elasticsearchClient,
                                   @Value("${mall.search.product-index:mall_product}") String indexName) {
        this.elasticsearchClient = elasticsearchClient;
        this.indexName = indexName;
    }

    @Override
    public String getIndexName() {
        return indexName;
    }

    @Override
    public synchronized void ensureIndex() {
        boolean exists;
        try {
            exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        } catch (IOException e) {
            throw new IllegalStateException("查询 ES 索引是否存在失败: index=" + indexName, e);
        }
        if (exists) {
            return;
        }
        boolean ikAvailable = probeIk();
        String indexAnalyzer = ikAvailable ? IK_INDEX_ANALYZER : FALLBACK_ANALYZER;
        String searchAnalyzer = ikAvailable ? IK_SEARCH_ANALYZER : FALLBACK_ANALYZER;
        log.info("初始化 ES 商品索引: index={}, 分词器 index={} / search={}", indexName, indexAnalyzer, searchAnalyzer);
        try {
            elasticsearchClient.indices().create(c -> c.index(indexName)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0"))
                    .mappings(m -> m
                            .properties("id", p -> p.long_(l -> l))
                            .properties("categoryId", p -> p.long_(l -> l))
                            .properties("name", p -> p.text(t -> t
                                    .analyzer(indexAnalyzer).searchAnalyzer(searchAnalyzer)))
                            .properties("subTitle", p -> p.text(t -> t
                                    .analyzer(indexAnalyzer).searchAnalyzer(searchAnalyzer)))
                            .properties("mainPic", p -> p.keyword(k -> k.index(false)))
                            .properties("price", p -> p.scaledFloat(sf -> sf.scalingFactor(100.0)))
                            .properties("sales", p -> p.integer(i -> i))
                            .properties("status", p -> p.integer(i -> i))));
        } catch (IOException e) {
            throw new IllegalStateException("创建 ES 商品索引失败: index=" + indexName, e);
        } catch (ElasticsearchException e) {
            // 多实例并发建索引时后者会收到 409，按幂等处理
            if (!ALREADY_EXISTS.equals(e.error().type())) {
                throw e;
            }
            log.info("ES 商品索引已被其他实例创建，跳过: index={}", indexName);
        }
    }

    /** 探测 IK 分词器是否可用：analyze 请求失败即视为未安装，降级 standard */
    private boolean probeIk() {
        try {
            elasticsearchClient.indices().analyze(a -> a.analyzer(IK_INDEX_ANALYZER).text("商城商品搜索测试"));
            return true;
        } catch (Exception e) {
            log.warn("IK 分词器不可用，索引分词降级为 standard: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void indexProduct(PmsProduct product) {
        // 先确保索引存在：应用启动时 ES 不可用导致的「索引缺失」可自愈
        ensureIndex();
        EsProduct doc = toDoc(product);
        try {
            IndexResponse response = elasticsearchClient.index(i -> i
                    .index(indexName)
                    .id(String.valueOf(doc.getId()))
                    .document(doc));
            log.info("商品索引写入成功: index={}, productId={}, result={}", indexName, doc.getId(), response.result());
        } catch (IOException e) {
            log.error("商品索引写入失败: index={}, productId={}", indexName, doc.getId(), e);
            throw new IllegalStateException("商品索引写入失败: productId=" + product.getId(), e);
        }
    }

    @Override
    public void removeProduct(Long productId) {
        try {
            var response = elasticsearchClient.delete(d -> d.index(indexName).id(String.valueOf(productId)));
            log.info("商品索引删除完成: index={}, productId={}, result={}", indexName, productId, response.result());
        } catch (ElasticsearchException e) {
            // 索引尚不存在视为已删除（如启动前 ES 未就绪），保持幂等语义
            if ("index_not_found_exception".equals(e.error().type())) {
                log.info("商品索引不存在，删除视为完成: index={}, productId={}", indexName, productId);
                return;
            }
            throw e;
        } catch (IOException e) {
            log.error("商品索引删除失败: index={}, productId={}", indexName, productId, e);
            throw new IllegalStateException("商品索引删除失败: productId=" + productId, e);
        }
    }

    private EsProduct toDoc(PmsProduct product) {
        EsProduct doc = new EsProduct();
        doc.setId(product.getId());
        doc.setCategoryId(product.getCategoryId());
        doc.setName(product.getName());
        doc.setSubTitle(product.getSubTitle());
        doc.setMainPic(product.getMainPic());
        doc.setPrice(product.getPrice());
        doc.setSales(product.getSales());
        doc.setStatus(product.getStatus());
        return doc;
    }
}
