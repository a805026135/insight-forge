package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrderEventProducerTest {

    @Test
    void requestThreadDoesNotWaitForBrokerAck() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> pendingAck = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(pendingAck);
        OrderEventProducer producer = new OrderEventProducer(kafka, new ObjectMapper(), "async-reservation");

        assertTimeoutPreemptively(Duration.ofMillis(100),
                () -> assertTrue(producer.sendOrderCreated(event())));
        assertTrue(!pendingAck.isDone(), "方法返回时 broker ACK 可以仍未完成");
    }

    @Test
    void asynchronousAckFailureIsObservedWithoutBreakingCaller() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);
        OrderEventProducer producer = new OrderEventProducer(kafka, new ObjectMapper(), "async-reservation");

        assertTrue(producer.sendOrderCreated(event()));
        assertDoesNotThrow(() -> failed.completeExceptionally(new IllegalStateException("broker down")));
    }


    @Test
    void syncAckModeWaitsForBrokerAndCanServeAsAbBaseline() throws Exception {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> pendingAck = new CompletableFuture<>();
        CountDownLatch sendInvoked = new CountDownLatch(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sendInvoked.countDown();
            return pendingAck;
        });
        OrderEventProducer producer = new OrderEventProducer(kafka, new ObjectMapper(), "sync-ack");

        CompletableFuture<Boolean> call = CompletableFuture.supplyAsync(
                () -> producer.sendOrderCreated(event()));
        assertTrue(sendInvoked.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(call.isDone(), "sync-ack 基线必须等待 broker ACK");
        pendingAck.complete(null);
        assertTrue(call.get(1, java.util.concurrent.TimeUnit.SECONDS));
    }
    private OrderCreatedEvent event() {
        return new OrderCreatedEvent("OF-1-1-1001", 1001L, 1L, 1L,
                101L, 1, BigDecimal.TEN, "trace-1");
    }
}
