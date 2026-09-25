package com.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单 MQ 消息体（生产者发送 → 消费者异步建单）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    /** 任务ID（消费者据此更新任务状态） */
    private String taskId;

    /** 下单用户ID */
    private Long userId;

    /** 秒杀商品ID */
    private Long seckillProductId;
}
