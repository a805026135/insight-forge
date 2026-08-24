package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.seckill.core.dto.SalesTickEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 验证销售心跳生产者：扣减成功后发往 sales.tick（与规则引擎消费者 TOPIC 逐字符一致），
 * key = activityId:skuId（同 SKU 保序），payload 含 activityId/skuId/qty。
 */
class SalesTickProducerTest {

    @Test
    void sendPublishesToSalesTickTopicWithCorrectKeyAndJson() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        SalesTickProducer producer = new SalesTickProducer(kafka, new ObjectMapper());

        boolean ok = producer.send(SalesTickEvent.of(1L, 1L, 3));

        assertTrue(ok);
        var captor = forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> rec = captor.getValue();
        assertEquals("sales.tick", rec.topic());   // 必须与规则引擎消费者 TOPIC 逐字符一致
        assertEquals("1:1", rec.key());
        assertTrue(rec.value().contains("\"activityId\":1"));
    }

    @Test
    void synchronousKafkaFailureDoesNotEscapeIntoOrderRequest() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        doThrow(new IllegalStateException("metadata unavailable"))
                .when(kafka).send(any(ProducerRecord.class));
        SalesTickProducer producer = new SalesTickProducer(kafka, new ObjectMapper());

        boolean ok = producer.send(SalesTickEvent.of(1L, 1L, 1));

        assertFalse(ok);
    }
}
