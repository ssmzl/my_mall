package com.mall.search.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * ES 商品搜索文档。
 *
 * <p>仅存放搜索/列表展示所需字段，不包含 detail 大字段；
 * status 随文档存储（0下架 1上架），查询时统一过滤，避免下架后丢文档、重新上架需重建索引。
 */
@Data
public class EsProduct {

    private Long id;

    /** 所属二级分类ID */
    private Long categoryId;

    private String name;

    private String subTitle;

    /** 主图URL，仅随文档存储，不参与检索（index: false） */
    private String mainPic;

    /** 展示价格（元） */
    private BigDecimal price;

    /** 销量 */
    private Integer sales;

    /** 0下架 1上架 */
    private Integer status;
}
