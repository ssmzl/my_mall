package com.mall.admin.dto;

import lombok.Data;

/**
 * 管理端修改分类请求（与前端 adminUpdateCategory 契约对齐，仅更新非空字段）。
 */
@Data
public class AdminCategoryUpdateDTO {

    private String name;

    private Integer sort;

    /** 状态：0禁用 1启用 */
    private Integer status;
}
