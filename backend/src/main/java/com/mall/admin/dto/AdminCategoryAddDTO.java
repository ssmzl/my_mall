package com.mall.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端新增分类请求（与前端 adminAddCategory 契约对齐）。
 */
@Data
public class AdminCategoryAddDTO {

    /** 父分类ID，0 表示一级分类 */
    @NotNull(message = "父分类ID不能为空")
    private Long parentId;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称长度不能超过50")
    private String name;

    /** 排序值，越小越靠前，默认 0 */
    private Integer sort;
}
