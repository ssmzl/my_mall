package com.mall.order.dto;

import lombok.Data;

/**
 * 我的订单分页查询参数（与前端 AdminOrderQuery 契约对齐：status 状态 tab + orderNo 可选）。
 */
@Data
public class OrderPageQueryDTO {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 订单状态，空表示全部 */
    private Integer status;

    /** 订单编号，模糊匹配 */
    private String orderNo;
}
