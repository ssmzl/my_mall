package com.mall.seckill.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.BizException;
import com.mall.seckill.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单消息生产者：Redis 预扣成功后投递 MQ，由消费者异步建单（削峰）。
 *
 * <p>与关单消息不同：秒杀消息丢失意味着用户抢购成功却永远建不出单（任务停在 QUEUED），
 * 因此发送失败/状态异常时向上抛出异常，由调用方回滚 Redis 预扣并拒绝本次抢购。
 */
@Slf4j
@Component
public class SeckillOrderProducer {

    /** 秒杀下单 Topic 默认值（与消费者注解中的占位符默认值保持一致） */
    public static final String DEFAULT_TOPIC = "mall-seckill-order";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public SeckillOrderProducer(RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper,
                                @Value("${mall.seckill.order-topic:" + DEFAULT_TOPIC + "}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /** 发送秒杀下单消息（Redis 预扣成功后调用），失败抛异常由调用方回滚预扣 */
    public void sendSeckillOrder(String taskId, Long userId, Long seckillProductId) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new SeckillOrderMessage(taskId, userId, seckillProductId));
        } catch (Exception e) {
            log.error("秒杀下单消息序列化失败: taskId={}, userId={}, seckillProductId={}", taskId, userId, seckillProductId, e);
            throw new BizException("系统繁忙，请稍后重试");
        }
        SendResult result;
        try {
            Message<String> message = MessageBuilder.withPayload(payload).build();
            result = rocketMQTemplate.syncSend(topic, message, 3000);
        } catch (Exception e) {
            log.error("秒杀下单消息发送失败: topic={}, taskId={}, userId={}, seckillProductId={}",
                    topic, taskId, userId, seckillProductId, e);
            throw new BizException("系统繁忙，请稍后重试");
        }
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            log.error("秒杀下单消息发送状态异常: topic={}, taskId={}, status={}", topic, taskId, result.getSendStatus());
            throw new BizException("系统繁忙，请稍后重试");
        }
        log.info("秒杀下单消息发送成功: topic={}, taskId={}, userId={}, seckillProductId={}, msgId={}",
                topic, taskId, userId, seckillProductId, result.getMsgId());
    }
}
