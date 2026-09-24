package com.mall.order.controller;

import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.order.dto.CreateOrderDTO;
import com.mall.order.dto.CreateOrderVO;
import com.mall.order.dto.OrderPageQueryDTO;
import com.mall.order.dto.OrderVO;
import com.mall.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（均需登录，用户ID取自登录上下文）。
 * 契约对齐前端 src/api/order.ts：GET /order/token、POST /order、POST /order/pay/{orderNo}、
 * PUT /order/cancel/{orderNo}、GET /order/page、GET /order/{orderNo}。
 */
@Tag(name = "订单", description = "下单（防重Token）、模拟支付、取消、超时关单、分页/详情")
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "获取下单防重Token", description = "一次性令牌，绑定当前用户，10分钟内有效")
    @GetMapping("/token")
    public Result<String> getToken() {
        return Result.ok(orderService.getOrderToken());
    }

    @Operation(summary = "下单", description = "事务内条件扣减库存防超卖；成功后清购物车已购项并发送延迟关单消息")
    @PostMapping
    public Result<CreateOrderVO> createOrder(@Valid @RequestBody CreateOrderDTO dto) {
        return Result.ok(orderService.createOrder(dto));
    }

    @Operation(summary = "模拟支付回调", description = "仅待支付订单可支付，重复回调幂等返回成功")
    @PostMapping("/pay/{orderNo}")
    public Result<Void> pay(@PathVariable String orderNo) {
        orderService.payOrder(orderNo);
        return Result.ok();
    }

    @Operation(summary = "主动取消订单", description = "仅待支付订单可取消，已取消幂等返回成功；取消后回补库存")
    @PutMapping("/cancel/{orderNo}")
    public Result<Void> cancel(@PathVariable String orderNo) {
        orderService.cancelOrder(orderNo);
        return Result.ok();
    }

    @Operation(summary = "我的订单分页列表", description = "按创建时间降序，支持状态 tab 与订单编号筛选，含订单项")
    @GetMapping("/page")
    public Result<PageResult<OrderVO>> page(OrderPageQueryDTO query) {
        return Result.ok(orderService.pageMyOrders(query));
    }

    @Operation(summary = "订单详情", description = "校验订单归属，防越权")
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@PathVariable String orderNo) {
        return Result.ok(orderService.detail(orderNo));
    }
}
