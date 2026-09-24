package com.mall.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 下单响应（与前端 CreateOrderVO 契约对齐）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderVO {

    private Long orderId;

    private String orderNo;

    /** 应付金额（元） */
    private BigDecimal payAmount;
}
