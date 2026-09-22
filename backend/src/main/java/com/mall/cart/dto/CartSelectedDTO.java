package com.mall.cart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 勾选/取消勾选单个购物车条目参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartSelectedDTO {

    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** true 勾选 / false 取消勾选 */
    @NotNull(message = "selected不能为空")
    private Boolean selected;
}
