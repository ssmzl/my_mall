package com.mall.search.mq;

import com.mall.common.BizException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品同步消息生产者单元测试（mock RocketMQTemplate）。
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncProducerTest {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    private ProductSyncProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ProductSyncProducer(rocketMQTemplate, ProductSyncProducer.DEFAULT_TOPIC);
    }

    @Test
    void sendUpsertShouldSendToTopicWithTag() {
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenReturn(okResult());
        producer.sendUpsert(5L);
        verify(rocketMQTemplate).syncSend("mall-product-sync:upsert", "5");
    }

    @Test
    void sendDeleteShouldSendToTopicWithTag() {
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenReturn(okResult());
        producer.sendDelete(6L);
        verify(rocketMQTemplate).syncSend("mall-product-sync:delete", "6");
    }

    private SendResult okResult() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        return result;
    }

    @Test
    void sendFailureShouldThrowBizException() {
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenThrow(new RuntimeException("mq down"));
        assertThatThrownBy(() -> producer.sendUpsert(5L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品搜索同步失败");
    }

    @Test
    void sendNonOkStatusShouldThrowBizException() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenReturn(result);
        assertThatThrownBy(() -> producer.sendDelete(6L))
                .isInstanceOf(BizException.class);
    }

    @Test
    void sendOkStatusShouldPass() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), anyString())).thenReturn(result);
        assertThatCode(() -> producer.sendUpsert(1L)).doesNotThrowAnyException();
    }
}
