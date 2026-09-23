package com.mall.search.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品搜索请求参数（与前端 SearchQuery 契约对齐）。
 */
@Data
public class SearchQueryDTO {

    /** 关键词，可空（空时按筛选条件浏览全部上架商品） */
    private String keyword;

    /** 分类过滤（一级/二级分类均可，服务端展开为子树） */
    private Long categoryId;

    private BigDecimal minPrice;

    private BigDecimal maxPrice;

    /** 综合排序 default / 销量 sales / 价格升序 priceAsc / 价格降序 priceDesc，非法值按 default 处理 */
    private String sort;

    private long pageNum = 1;

    private long pageSize = 10;
}
