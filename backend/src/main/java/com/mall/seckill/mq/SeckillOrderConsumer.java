package com.mall.seckill.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.seckill.dto.SeckillOrderMessage;
import com.mall.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单消费者：收到预扣成功的消息后异步建单（事务内 DB 条件扣减秒杀库存 + 生成订单）。
 *
 * <p>幂等性由任务状态保证：建单前校验任务仍为 QUEUED，重复投递/已处理的消息直接跳过；
 * 建单系统异常时将任务置为 FAIL 并归还 Redis 预扣（DB 事务已回滚），不再抛出异常触发重试，
 * 避免重试建出重复订单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${mall.seckill.order-topic:" + SeckillOrderProducer.DEFAULT_TOPIC + "}",
        consumerGroup = "${mall.seckill.order-consumer-group:mall-seckill-order-consumer}")
public class SeckillOrderConsumer implements RocketMQListener<String> {

    private final SeckillService seckillService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        SeckillOrderMessage message;
        try {
            message = objectMapper.readValue(payload, SeckillOrderMessage.class);
        } catch (Exception e) {
            // 脏消息（无法解析）无法定位任务，确认消费避免无限重试
            log.warn("秒杀下单消息格式非法，跳过: payload={}", payload, e);
            return;
        }
        log.info("收到秒杀下单消息: taskId={}, userId={}, seckillProductId={}",
                message.getTaskId(), message.getUserId(), message.getSeckillProductId());
        try {
            seckillService.createSeckillOrder(message);
        } catch (Exception e) {
            // 系统异常（DB 不可用等）：任务置 FAIL + 归还 Redis 预扣，确认消费不重试
            log.error("秒杀异步建单失败: taskId={}, userId={}, seckillProductId={}",
                    message.getTaskId(), message.getUserId(), message.getSeckillProductId(), e);
            seckillService.failTask(message, "系统繁忙，抢购失败，请稍后重试");
        }
    }
}
