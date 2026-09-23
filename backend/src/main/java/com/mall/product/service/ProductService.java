package com.mall.product.service;

import com.mall.common.PageResult;
import com.mall.product.dto.ProductDetailVO;
import com.mall.product.entity.PmsCategory;
import com.mall.product.entity.PmsProduct;

import java.util.List;

/**
 * 商品服务：分类树、商品分页列表、商品详情（缓存三防）。
 */
public interface ProductService {

    /** 查询启用的分类，构建两级分类树 */
    List<PmsCategory> categoryTree();

    /** 商品分页列表（仅上架商品），keyword 模糊匹配名称/副标题 */
    PageResult<PmsProduct> page(long pageNum, long pageSize, Long categoryId, String keyword);

    /**
     * 商品详情（含 SKU 列表），带缓存三防：
     * 空值缓存防穿透 + Redisson 互斥锁防击穿 + 随机过期防雪崩。
     *
     * @throws com.mall.common.BizException 商品不存在或已下架
     */
    ProductDetailVO getDetail(Long id);

    /**
     * 将分类展开为「自身 + 全部后代分类」ID 集合（BFS）。
     * 商品挂在二级分类下，一级/二级分类过滤均可用该方法统一展开。
     * 供商品列表与 ES 搜索的分类过滤复用。
     */
    List<Long> expandCategoryIds(Long categoryId);

    /**
     * 查询全量商品（含下架，按 id 升序），供 ES 存量全量同步 / 索引重建使用。
     * 仅返回索引所需字段，不含 detail 大字段。
     */
    List<PmsProduct> listAllForIndex();
}
