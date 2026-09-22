package com.mall.cart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 全选/取消全选参数。
 */
@Data
public class CartSelectAllDTO {

    /** true 全选 / false 全不选 */
    @NotNull(message = "selected不能为空")
    private Boolean selected;
}
