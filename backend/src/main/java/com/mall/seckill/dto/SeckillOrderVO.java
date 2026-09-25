package com.mall.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单响应（异步，前端凭 taskId 轮询结果，与前端 SeckillOrderVO 契约一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderVO implements Serializable {

    private String taskId;
}
