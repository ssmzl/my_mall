package com.mall.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品分类表 pms_category 实体。
 */
@Data
@TableName("pms_category")
public class PmsCategory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父分类ID，0表示一级分类 */
    private Long parentId;

    private String name;

    /** 层级：1一级分类 2二级分类 */
    private Integer level;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 状态：0禁用 1启用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 子分类（树形结构，非数据库字段） */
    @TableField(exist = false)
    private List<PmsCategory> children;
}
