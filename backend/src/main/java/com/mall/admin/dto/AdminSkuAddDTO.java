package com.mall.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端新增 SKU 请求（与前端 adminAddSku 契约对齐）。
 */
@Data
public class AdminSkuAddDTO {

    @NotBlank(message = "SKU编码不能为空")
    private String skuCode;

    /** 规格JSON，如 {"颜色":"黑色","存储":"256GB"}，可选 */
    private String specJson;

    /** SKU价格（元） */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    /** SKU库存（非负） */
    @NotNull(message = "库存不能为空")
    @PositiveOrZero(message = "库存不能为负数")
    private Integer stock;
}
