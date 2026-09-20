package com.mall.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品表 pms_product 实体。
 */
@Data
@TableName("pms_product")
public class PmsProduct implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属二级分类ID */
    private Long categoryId;

    private String name;

    private String subTitle;

    private String mainPic;

    /** 商品详情（富文本/HTML），列表查询不加载 */
    private String detail;

    /** 展示价格（元） */
    private BigDecimal price;

    /** 总库存（非负） */
    private Integer stock;

    /** 销量 */
    private Integer sales;

    /** 状态：0下架 1上架 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
