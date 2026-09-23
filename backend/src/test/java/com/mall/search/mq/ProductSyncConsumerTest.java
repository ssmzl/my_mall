package com.mall.search.mq;

import com.mall.product.entity.PmsProduct;
import com.mall.product.mapper.ProductMapper;
import com.mall.search.service.ProductIndexService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品同步消息消费者单元测试（按 tag 路由 + 幂等/容错语义）。
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncConsumerTest {

    @Mock
    private ProductIndexService productIndexService;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private ProductSyncConsumer consumer;

    private MessageExt message(String tag, String body) {
        MessageExt msg = new MessageExt();
        msg.setTags(tag);
        msg.setBody(body.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @Test
    void upsertShouldIndexProductFromDb() {
        PmsProduct product = new PmsProduct();
        product.setId(5L);
        when(productMapper.selectById(5L)).thenReturn(product);

        consumer.onMessage(message(ProductSyncProducer.TAG_UPSERT, "5"));

        verify(productIndexService).indexProduct(product);
    }

    @Test
    void upsertMissingProductShouldRemoveIndexDoc() {
        when(productMapper.selectById(9L)).thenReturn(null);

        consumer.onMessage(message(ProductSyncProducer.TAG_UPSERT, "9"));

        verify(productIndexService).removeProduct(9L);
    }

    @Test
    void deleteShouldRemoveIndexDoc() {
        consumer.onMessage(message(ProductSyncProducer.TAG_DELETE, "7"));

        verify(productIndexService).removeProduct(7L);
        verifyNoInteractions(productMapper);
    }

    @Test
    void unknownTagShouldBeIgnored() {
        consumer.onMessage(message("unknown", "5"));

        verifyNoInteractions(productIndexService, productMapper);
    }

    @Test
    void malformedBodyShouldBeDropped() {
        consumer.onMessage(message(ProductSyncProducer.TAG_UPSERT, "not-a-number"));

        verifyNoInteractions(productIndexService, productMapper);
    }

    @Test
    void indexFailureShouldPropagateForMqRetry() {
        PmsProduct product = new PmsProduct();
        product.setId(5L);
        when(productMapper.selectById(5L)).thenReturn(product);
        doThrow(new IllegalStateException("商品索引写入失败")).when(productIndexService).indexProduct(product);

        // 异常上抛由 RocketMQ 自动重试，不允许吞掉
        assertThatCode(() -> consumer.onMessage(message(ProductSyncProducer.TAG_UPSERT, "5")))
                .isInstanceOf(IllegalStateException.class);
    }
}
