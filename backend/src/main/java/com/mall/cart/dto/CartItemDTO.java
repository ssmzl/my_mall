package com.mall.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 购物车数量参数：加购（POST /cart）与修改数量（PUT /cart/quantity）共用。
 */
@Data
public class CartItemDTO {

    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 数量，至少为 1 */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为1")
    private Integer quantity;
}
