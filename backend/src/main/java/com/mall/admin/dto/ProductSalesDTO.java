package com.mall.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品销量/销售额聚合行（Mapper XML 聚合查询结果，用于 Top10 与汇总）。
 */
@Data
public class ProductSalesDTO {

    /** 商品ID */
    private Long productId;

    /** 商品名称（下单时快照，取最近一条） */
    private String productName;

    /** 销量（件数求和） */
    private Long sales;

    /** 销售额（SUM(price * quantity)） */
    private BigDecimal amount;
}
