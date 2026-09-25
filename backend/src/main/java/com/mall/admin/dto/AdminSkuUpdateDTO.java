package com.mall.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端修改 SKU 请求（与前端 adminUpdateSku 契约对齐，仅更新非空字段）。
 */
@Data
public class AdminSkuUpdateDTO {

    private String skuCode;

    private String specJson;

    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    @PositiveOrZero(message = "库存不能为负数")
    private Integer stock;
}
