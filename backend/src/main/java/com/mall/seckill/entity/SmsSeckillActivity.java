package com.mall.seckill.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀活动表 sms_seckill_activity 实体。
 */
@Data
@TableName("sms_seckill_activity")
public class SmsSeckillActivity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 活动名称 */
    private String name;

    /** 活动开始时间 */
    private LocalDateTime startTime;

    /** 活动结束时间 */
    private LocalDateTime endTime;

    /** 状态：0下线 1上线 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
