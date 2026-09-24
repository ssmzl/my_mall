package com.mall.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图（与前端 Order 契约对齐，addressSnapshot 为收货地址快照 JSON 字符串，由前端解析）。
 */
@Data
public class OrderVO {

    private Long id;

    private String orderNo;

    private Long userId;

    private BigDecimal totalAmount;

    /** 状态：0待支付 1已支付 2已发货 3已完成 4已取消 */
    private Integer status;

    /** 收货地址快照（JSON 字符串） */
    private String addressSnapshot;

    private LocalDateTime payTime;

    private LocalDateTime createTime;

    private List<OrderItemVO> items;
}
