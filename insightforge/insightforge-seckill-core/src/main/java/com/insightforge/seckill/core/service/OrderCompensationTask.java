package com.insightforge.seckill.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.redis.RedisStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单补偿巡检（Phase 14.5：堵住「Redis 已扣减但订单没落库」的最后一公里）。
 *
 * 背景：异步路径先扣 Redis、再经 Kafka 落库。若消费者异常（进程挂/消息坏），
 * Redis 扣了但订单永远不来，且幂等键 TTL（10 分钟）过期后用户重试还会再扣一次。
 *
 * Lua 在扣库存时同步把 orderId 放入按截止时间排序的 ZSET。本任务每轮只拉取到期的固定批次，
 * DB 已有订单则清理索引；缺单则用预留载荷重建。失败项后移重试，避免热循环。
 */
@Component
public class OrderCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(OrderCompensationTask.class);
    private static final int BATCH_SIZE = 100;
    private static final long RETRY_DELAY_MS = 5_000;

    private final ObjectProvider<RedisStockService> stockServiceProvider;
    private final OrdersMapper ordersMapper;
    private final OrderCreationService orderCreationService;
    private final ObjectMapper objectMapper;

    public OrderCompensationTask(ObjectProvider<RedisStockService> stockServiceProvider,
                                 OrdersMapper ordersMapper,
                                 OrderCreationService orderCreationService,
                                 ObjectMapper objectMapper) {
        this.stockServiceProvider = stockServiceProvider;
        this.ordersMapper = ordersMapper;
        this.orderCreationService = orderCreationService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${insightforge.compensation.interval-ms:1000}")
    public void sweep() {
        RedisStockService stockService = stockServiceProvider.getIfAvailable();
        if (stockService == null) {
            return; // local 无 Redis：主路径是同步建单，无需补偿
        }
        long now = System.currentTimeMillis();
        List<String> orderIds;
        try {
            orderIds = stockService.dueReservations(now, BATCH_SIZE);
        } catch (Exception e) {
            log.warn("补偿巡检读取到期预留失败（跳过本轮）", e);
            return;
        }
        if (orderIds.isEmpty()) {
            return;
        }

        int compensated = 0;
        for (String orderId : orderIds) {
            Long existing = ordersMapper.selectCount(new QueryWrapper<Orders>().eq("order_id", orderId));
            if (existing != null && existing > 0) {
                stockService.acknowledgeReservation(orderId);
                continue;
            }
            String payload = stockService.reservationPayload(orderId);
            if (payload == null) {
                stockService.rescheduleReservation(orderId, now + RETRY_DELAY_MS);
                continue;
            }
            try {
                OrderCreatedEvent evt = objectMapper.readValue(payload, OrderCreatedEvent.class);
                SeckillRequest req = new SeckillRequest();
                req.setUserId(evt.getUserId());
                req.setActivityId(evt.getActivityId());
                req.setSkuId(evt.getSkuId());
                req.setQty(evt.getQty());
                if (orderCreationService.createOrderIdempotent(
                        req, evt.getOrderId(), evt.getPrice(), evt.getProductId())) {
                    compensated++;
                    log.warn("补偿补单成功（原异步落库丢失）：orderId={}", orderId);
                }
                stockService.acknowledgeReservation(orderId);
            } catch (Exception e) {
                log.warn("补偿补单失败 orderId={}", orderId, e);
                stockService.rescheduleReservation(orderId, now + RETRY_DELAY_MS);
            }
        }
        if (compensated > 0) {
            log.warn("补偿巡检完成：本轮补单 {} 笔", compensated);
        }
    }
}
