package com.mall.admin.controller;

import com.mall.admin.dto.AdminOrderPageQueryDTO;
import com.mall.admin.service.AdminOrderService;
import com.mall.common.PageResult;
import com.mall.common.Result;
import com.mall.order.dto.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端订单接口（/admin/** 由登录拦截器要求管理员角色）。
 */
@Tag(name = "管理端-订单", description = "全量订单分页查询、发货")
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    @Operation(summary = "订单分页列表", description = "所有用户的订单，orderNo 模糊 + status 过滤，含订单项")
    @GetMapping("/page")
    public Result<PageResult<OrderVO>> page(AdminOrderPageQueryDTO query) {
        return Result.ok(adminOrderService.page(query));
    }

    @Operation(summary = "发货", description = "仅已支付订单可发货（已支付→已发货，重复发货幂等）")
    @PutMapping("/deliver/{orderNo}")
    public Result<Void> deliver(@PathVariable String orderNo) {
        adminOrderService.deliver(orderNo);
        return Result.ok(null);
    }
}
