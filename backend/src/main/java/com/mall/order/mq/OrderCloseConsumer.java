package com.mall.order.mq;

import com.mall.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单消费者：收到延迟消息后关闭仍处于待支付状态的订单并回补库存。
 *
 * <p>幂等性由服务层条件更新（仅 0待支付 → 4已取消）保证：用户已支付/已主动取消时
 * 条件更新影响行数为 0，直接确认消费不再回补库存；订单不存在（如下单事务已回滚）
 * 仅记录日志并确认消费，不触发消息重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${mall.order.close-topic:" + OrderCloseProducer.DEFAULT_TOPIC + "}",
        consumerGroup = "${mall.order.close-consumer-group:mall-order-close-consumer}")
public class OrderCloseConsumer implements RocketMQListener<String> {

    private final OrderService orderService;

    @Override
    public void onMessage(String orderNo) {
        log.info("收到超时关单消息: orderNo={}", orderNo);
        orderService.closeTimeoutOrder(orderNo);
    }
}
