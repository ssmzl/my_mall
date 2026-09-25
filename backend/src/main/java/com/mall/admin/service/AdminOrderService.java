package com.mall.admin.service;

import com.mall.admin.dto.AdminOrderPageQueryDTO;
import com.mall.common.PageResult;
import com.mall.order.dto.OrderVO;

/**
 * 管理端订单服务。
 */
public interface AdminOrderService {

    /** 全量订单分页（所有用户），orderNo 模糊 + status 过滤，含订单项 */
    PageResult<OrderVO> page(AdminOrderPageQueryDTO query);

    /** 发货：仅已支付订单可发货（已支付 → 已发货，重复发货幂等） */
    void deliver(String orderNo);
}
