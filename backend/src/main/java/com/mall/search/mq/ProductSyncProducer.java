package com.mall.search.mq;

import com.mall.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 商品索引同步消息生产者。
 *
 * <p>商品新增/修改/上架/下架发送 upsert，删除发送 delete；消息体为商品ID，
 * 消费端以数据库为准回查后写 ES。由商品写操作方（Task 9 管理端）调用。
 */
@Slf4j
@Component
public class ProductSyncProducer {

    /** 同步 Topic 默认值（与消费者注解中的占位符默认值保持一致） */
    public static final String DEFAULT_TOPIC = "mall-product-sync";
    /** 商品新增/修改/上下架：覆盖写入索引 */
    public static final String TAG_UPSERT = "upsert";
    /** 商品删除：移除索引文档 */
    public static final String TAG_DELETE = "delete";

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public ProductSyncProducer(RocketMQTemplate rocketMQTemplate,
                               @Value("${mall.search.sync-topic:" + DEFAULT_TOPIC + "}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    public void sendUpsert(Long productId) {
        send(TAG_UPSERT, productId);
    }

    public void sendDelete(Long productId) {
        send(TAG_DELETE, productId);
    }

    private void send(String tag, Long productId) {
        SendResult result;
        try {
            result = rocketMQTemplate.syncSend(topic + ":" + tag, String.valueOf(productId));
        } catch (Exception e) {
            log.error("商品同步消息发送失败: topic={}, tag={}, productId={}", topic, tag, productId, e);
            throw new BizException("商品搜索同步失败，请稍后重试");
        }
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            log.error("商品同步消息发送状态异常: tag={}, productId={}, status={}", tag, productId, result.getSendStatus());
            throw new BizException("商品搜索同步失败，请稍后重试");
        }
        log.info("商品同步消息发送成功: topic={}, tag={}, productId={}, msgId={}", topic, tag, productId, result.getMsgId());
    }
}
