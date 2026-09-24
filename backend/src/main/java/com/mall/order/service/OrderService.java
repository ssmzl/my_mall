package com.mall.order.service;

import com.mall.common.PageResult;
import com.mall.order.dto.CreateOrderDTO;
import com.mall.order.dto.CreateOrderVO;
import com.mall.order.dto.OrderPageQueryDTO;
import com.mall.order.dto.OrderVO;

/**
 * 订单服务。
 */
public interface OrderService {

    /** 获取下单防重 Token（一次性，绑定当前用户，TTL 内有效） */
    String getOrderToken();

    /** 下单：事务内条件扣减库存、生成订单/订单项，事务提交后清购物车并发送延迟关单消息 */
    CreateOrderVO createOrder(CreateOrderDTO dto);

    /** 模拟支付回调：仅待支付订单可支付，重复支付幂等返回成功 */
    void payOrder(String orderNo);

    /** 主动取消订单：仅待支付订单可取消，已取消幂等返回成功；取消后回补库存 */
    void cancelOrder(String orderNo);

    /** 超时关单（消费者调用）：关闭仍待支付的订单并回补库存，非待支付状态幂等跳过 */
    void closeTimeoutOrder(String orderNo);

    /** 当前用户订单分页列表（createTime 降序，含订单项） */
    PageResult<OrderVO> pageMyOrders(OrderPageQueryDTO query);

    /** 订单详情（校验归属，防越权） */
    OrderVO detail(String orderNo);
}
