package com.mall.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细视图（与前端 OrderItem 契约对齐）。
 */
@Data
public class OrderItemVO {

    private Long id;

    private Long orderId;

    private Long productId;

    private Long skuId;

    /** 下单时商品名称快照 */
    private String productName;

    /** 下单时SKU规格快照 */
    private String skuSpec;

    /** 下单单价（元） */
    private BigDecimal price;

    private Integer quantity;
}
