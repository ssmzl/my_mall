package com.mall.order.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单延迟消息生产者。
 *
 * <p>下单事务提交后发送延迟消息（RocketMQ 延迟级别，默认 30s 便于演示，生产建议 16=30m），
 * 消息体为订单编号；消费端以条件更新（仅待支付可关单）保证幂等。
 */
@Slf4j
@Component
public class OrderCloseProducer {

    /** 延迟关单 Topic 默认值（与消费者注解中的占位符默认值保持一致） */
    public static final String DEFAULT_TOPIC = "mall-order-close";

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;
    private final int delayLevel;

    public OrderCloseProducer(RocketMQTemplate rocketMQTemplate,
                              @Value("${mall.order.close-topic:" + DEFAULT_TOPIC + "}") String topic,
                              @Value("${mall.order.close-delay-level:4}") int delayLevel) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
        this.delayLevel = delayLevel;
    }

    /** 发送延迟关单消息（在下单事务提交后调用），消息体为订单编号 */
    public void sendDelayClose(String orderNo) {
        SendResult result;
        try {
            Message<String> message = MessageBuilder.withPayload(orderNo).build();
            result = rocketMQTemplate.syncSend(topic, message, 3000, delayLevel);
        } catch (Exception e) {
            log.error("延迟关单消息发送失败: topic={}, orderNo={}", topic, orderNo, e);
            // 发送失败仅记录告警，不回滚已提交的订单：订单仍可主动取消/人工处理
            return;
        }
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            log.error("延迟关单消息发送状态异常: topic={}, orderNo={}, status={}", topic, orderNo, result.getSendStatus());
            return;
        }
        log.info("延迟关单消息发送成功: topic={}, orderNo={}, msgId={}, delayLevel={}",
                topic, orderNo, result.getMsgId(), delayLevel);
    }
}
