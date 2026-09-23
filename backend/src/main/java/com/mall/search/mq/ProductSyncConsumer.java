package com.mall.search.mq;

import com.mall.product.entity.PmsProduct;
import com.mall.product.mapper.ProductMapper;
import com.mall.search.service.ProductIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 商品索引同步消息消费者：upsert → 以数据库为准回查后覆盖写索引，delete → 删除文档。
 *
 * <p>消费幂等：ES 按文档 ID 覆盖写 / 删除，重复消费无副作用。
 * ES 写入失败时异常上抛，由 RocketMQ 自动重试（默认 16 次后进死信）；
 * 消息体损坏则记录诊断日志后丢弃，避免无限重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${mall.search.sync-topic:" + ProductSyncProducer.DEFAULT_TOPIC + "}",
        consumerGroup = "${mall.search.sync-consumer-group:mall-search-sync-consumer}",
        selectorExpression = "upsert || delete")
public class ProductSyncConsumer implements RocketMQListener<MessageExt> {

    private final ProductIndexService productIndexService;
    private final ProductMapper productMapper;

    @Override
    public void onMessage(MessageExt message) {
        String tag = message.getTags();
        String body = message.getBody() == null ? "" : new String(message.getBody(), StandardCharsets.UTF_8);
        Long productId;
        try {
            productId = Long.parseLong(body.trim());
        } catch (NumberFormatException e) {
            log.error("商品同步消息体非法，丢弃: tag={}, body={}, msgId={}", tag, body, message.getMsgId());
            return;
        }
        switch (tag == null ? "" : tag) {
            case ProductSyncProducer.TAG_UPSERT -> upsert(productId);
            case ProductSyncProducer.TAG_DELETE -> productIndexService.removeProduct(productId);
            default -> log.warn("忽略未识别的商品同步消息: tag={}, productId={}", tag, productId);
        }
    }

    private void upsert(Long productId) {
        PmsProduct product = productMapper.selectById(productId);
        if (product == null) {
            // 商品已被物理删除且删除消息丢失时，兜底清掉索引残留
            log.info("商品不存在（可能已删除），同步为移除索引: productId={}", productId);
            productIndexService.removeProduct(productId);
            return;
        }
        productIndexService.indexProduct(product);
    }
}
