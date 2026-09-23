package com.mall.search.service;

import com.mall.product.entity.PmsProduct;

/**
 * ES 商品索引维护：索引初始化、文档 upsert / delete。
 */
public interface ProductIndexService {

    /** 索引名 */
    String getIndexName();

    /**
     * 确保商品索引存在（不存在则创建，幂等）。
     * 分词器优先 IK（ik_max_word / ik_smart），未安装时降级 ES 内置 standard。
     */
    void ensureIndex();

    /** 按文档 ID 覆盖写入（upsert），天然幂等 */
    void indexProduct(PmsProduct product);

    /** 删除文档，索引中不存在时静默成功 */
    void removeProduct(Long productId);

    /**
     * 存量商品全量同步 / 索引重建：查库全量商品（含下架）分批 bulk 写入 ES（upsert 幂等）。
     *
     * @return 成功写入的文档数
     * @throws IllegalStateException ES 写入失败或存在失败条目时抛出
     */
    int syncAllProducts();
}
