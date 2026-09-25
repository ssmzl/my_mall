package com.mall.admin.dto;

import lombok.Data;

/**
 * 管理端订单分页查询参数（与前端 AdminOrderQuery 契约对齐）。
 */
@Data
public class AdminOrderPageQueryDTO {

    private Integer pageNum = 1;

    private Integer pageSize = 10;

    /** 订单编号，模糊匹配 */
    private String orderNo;

    /** 订单状态：0待支付 1已支付 2已发货 3已完成 4已取消，空表示全部 */
    private Integer status;
}
