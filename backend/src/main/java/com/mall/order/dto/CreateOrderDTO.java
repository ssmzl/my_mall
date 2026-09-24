package com.mall.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 下单请求（与前端 CreateOrderPayload 契约对齐）。
 */
@Data
public class CreateOrderDTO {

    /** 防重 Token（GET /order/token 获取，一次性） */
    @NotBlank(message = "缺少防重Token")
    private String token;

    /** 收货地址ID */
    @NotNull(message = "请选择收货地址")
    private Long addressId;

    @NotEmpty(message = "下单商品不能为空")
    private List<CreateOrderItemDTO> items;

    @Data
    public static class CreateOrderItemDTO {

        @NotNull(message = "skuId不能为空")
        private Long skuId;

        @NotNull(message = "购买数量不能为空")
        @Min(value = 1, message = "购买数量至少为1")
        private Integer quantity;
    }
}
