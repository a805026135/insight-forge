package com.insightforge.seckill.core.service;

import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import com.insightforge.seckill.core.cache.SkuCacheService;
import com.insightforge.seckill.core.kafka.OrderEventProducer;
import com.insightforge.seckill.core.redis.DeductResult;
import com.insightforge.seckill.core.redis.RedisIdempotentService;
import com.insightforge.seckill.core.redis.RedisStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 秒杀主流程（Phase 4：Kafka 异步削峰 + 幂等）。
 *
 * 相比 Phase 3 的核心变化——「建单落库」从**同步**变成**异步**：
 * - 扣减成功 → 往 Kafka 发一条 order.created 事件 → **立刻返回用户**（接口 ~毫秒级，P99&lt;10ms）。
 * - 后方的 {@code OrderCreatedConsumer} 异步把「订单 + DB 库存」落库。海量并发先堆在 Kafka，
 *   由消费者匀速消化（削峰），用户的请求路径上不再有 DB 写。
 * - 幂等闸门（Redis SETNX，仅非 local）挡住「同一用户重复抢」造成的重复扣减；
 *   数据库 order_id 唯一约束做最终兜底，保证 exactly-once（无论 Kafka 重投多少次，只建一张单）。
 *
 * 两条路径（自动选择，零代码切换）：
 * - 非 local（Redis + Kafka 就绪）：Redis Lua 原子扣减 → SETNX 幂等 → 发 Kafka → 快速返回；消费者异步落库。
 * - local（零依赖冒烟）：无 Redis/Kafka，走 DB 条件更新 + order_id 唯一约束幂等，同步落库（教学/冒烟用）。
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;
    /** SKU 多级缓存（Phase 5）：高频读走 L1 Caffeine + L2 Redis，避免每次打 DB。 */
    private final SkuCacheService skuCacheService;
    /** Redis 库存服务：用 ObjectProvider 拿，local profile 下为 null（不存在该 Bean）。 */
    private final ObjectProvider<RedisStockService> redisStockService;
    /** 幂等闸门（Redis SETNX）：仅非 local 有 Redis 时存在，local 下为 null。 */
    private final ObjectProvider<RedisIdempotentService> idempotentService;
    /** Kafka 生产者：仅非 local 存在，local 下为 null。 */
    private final ObjectProvider<OrderEventProducer> orderEventProducer;

    public SeckillService(ActivitySkuMapper activitySkuMapper,
                          OrdersMapper ordersMapper,
                          SkuCacheService skuCacheService,
                          ObjectProvider<RedisStockService> redisStockService,
                          ObjectProvider<RedisIdempotentService> idempotentService,
                          ObjectProvider<OrderEventProducer> orderEventProducer) {
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.skuCacheService = skuCacheService;
        this.redisStockService = redisStockService;
        this.idempotentService = idempotentService;
        this.orderEventProducer = orderEventProducer;
    }

    /**
     * 抢购主入口（Phase 4，整体 @Transactional 以包住同步兜底路径的 DB 操作）。
     *
     * 三种结局：
     * 1) 异步快路径（非 local + Redis 扣成功）：发 Kafka → 立刻返回订单号。**接口极快**。
     * 2) 同步兜底（local，或 Redis 异常回退）：DB 条件更新 → 同步建单（order_id 唯一约束幂等）。
     * 3) 售罄：Redis/DB 都告知库存不足 → 返回 409。
     *
     * 注意：异步路径不碰 DB，同步路径的 DB 操作收在 {@code syncCreateOrder} 一个事务里，
     * 避免把「网络调用 / Kafka 发送」裹进长事务占连接。
     */
    @Transactional
    public ApiResult<String> seckill(SeckillRequest req) {
        ActivitySku sku = skuCacheService.getSku(req.getSkuId());
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }

        // 确定性订单号：同一 (activityId, skuId, userId) 永远算出同一个值，
        // 这是全链路幂等的「锚点」——重复请求拿到同一个订单号，DB 唯一约束据此去重。
        String orderId = buildOrderId(req);

        // —— 主路径：Redis Lua 原子扣减（秒杀高并发的真正入口）——
        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs != null) {
            // 幂等闸门（仅非 local 有 Redis）：先占坑，防止「同一用户重复抢」被当成两次独立扣减。
            RedisIdempotentService idem = idempotentService.getIfAvailable();
            if (idem != null && !idem.tryAcquire(orderId)) {
                // 这个 orderId 已经被占过坑（之前抢过），直接返回既有订单号 —— 幂等。
                log.info("命中幂等闸门（Redis SETNX），重复请求被拦：orderId={}", orderId);
                return ApiResult.ok(orderId);
            }

            DeductResult res = rs.deduct(sku.getActivityId(), sku.getId(), req.getQty());
            if (res == DeductResult.SUCCESS) {
                // 扣成功 → 发 Kafka 异步建单，立刻返回（削峰 + 极速响应，P99<10ms）
                OrderEventProducer producer = orderEventProducer.getIfAvailable();
                if (producer != null && producer.sendOrderCreated(OrderCreatedEvent.from(req, orderId))) {
                    return ApiResult.ok(orderId);
                }
                // 极端兜底：Kafka 不可用（理论上 !local 必有），退回同步建单，保证不丢单
                log.warn("Kafka 发送失败，降级同步建单：orderId={}", orderId);
                return syncCreateOrder(req, sku, orderId);
            }
            if (res == DeductResult.SOLD_OUT) {
                // 没扣成：释放幂等槽（本次并未真正下单），避免误占位阻塞用户后续重试
                if (idem != null) idem.release(orderId);
                return ApiResult.fail(409, "已售罄");
            }
            // NOT_PREHEATED / ERROR：保守回退 DB 兜底（同样先释放幂等槽）
            if (idem != null) idem.release(orderId);
        }

        // —— 兜底路径：Phase 2 的 DB 原子条件更新（local 唯一路径；Redis 异常时也走这）——
        return syncCreateOrder(req, sku, orderId);
    }

    /**
     * 同步建单 + 扣 DB 库存（local 主路径 & 异步发送失败时的兜底）。
     * 由 seckill() 的 @Transactional 提供事务：插订单 + 扣库存要么都成要么都回滚。
     *
     * 幂等设计（无 Redis 时靠 DB 唯一约束）：
     *   先插订单——order_id 唯一约束会拦下「重复用户」（DuplicateKeyException），
     *   此时直接返回既有订单号、**绝不**走到扣库存，从而不会多扣。
     *   仅在「插单成功」后才扣库存；若扣库存时发现已售罄（affected=0），撤销刚插的订单。
     */
    private ApiResult<String> syncCreateOrder(SeckillRequest req, ActivitySku sku, String orderId) {
        Orders order = new Orders();
        order.setOrderId(orderId);
        order.setUserId(req.getUserId());
        order.setActivityId(req.getActivityId());
        order.setSkuId(req.getSkuId());
        order.setQty(req.getQty());
        order.setStatus("CREATED");
        order.setCreatedAt(LocalDateTime.now());
        try {
            ordersMapper.insert(order); // 幂等闸门：重复用户在这里被唯一约束挡住
        } catch (DuplicateKeyException e) {
            // 同一 (用户,活动,SKU) 已经下过单 → 直接返回既有订单号（幂等）
            log.info("命中幂等去重（DB 唯一约束），订单已存在：orderId={}", orderId);
            return ApiResult.ok(orderId);
        }

        // 原子扣减 DB 库存（条件更新，绝不为负）
        int affected = activitySkuMapper.decrementStock(sku.getId(), req.getQty());
        if (affected == 0) {
            // 售罄：刚插入的订单撤销（本事务内回滚该行），返回售罄
            ordersMapper.deleteById(order.getId());
            return ApiResult.fail(409, "已售罄");
        }
        // 库存真的变了：失效缓存，下次读取会回源到最新值（保证缓存与 DB 最终一致）
        skuCacheService.evict(sku.getId());
        return ApiResult.ok(orderId);
    }

    /**
     * 用 (activityId, skuId, userId) 确定性推导订单号。
     * 不依赖随机，保证「同一次抢购」永远得到同一个订单号 —— 全链路幂等的基础。
     * 形如 "OF-1-1-1001"，可读且绝不会因数字位数不同而碰撞。
     */
    private String buildOrderId(SeckillRequest req) {
        return "OF-" + req.getActivityId() + "-" + req.getSkuId() + "-" + req.getUserId();
    }

    /**
     * 库存预热接口：把 DB 里的剩余库存刷进 Redis（秒杀开始前必须调用一次）。
     * 没启用 Redis 时返回提示，避免静默失败。
     */
    public ApiResult<Void> preheat(Long skuId) {
        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs == null) {
            return ApiResult.fail(409, "未启用 Redis 主路径（请用 --spring.profiles.active=redis 启动）");
        }
        // 先失效缓存，再读最新 SKU（防预热推了旧库存）；getSku 内部会回源 DB 拿最新值
        skuCacheService.evict(skuId);
        ActivitySku sku = skuCacheService.getSku(skuId);
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        rs.preheat(sku.getActivityId(), sku.getId(), sku.getStock());
        return ApiResult.ok(null);
    }

    /** 查询某 SKU 的库存情况，便于手动验证与压测后核对。 */
    public ApiResult<StockInfo> stockInfo(Long skuId) {
        ActivitySku sku = skuCacheService.getSku(skuId);
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        StockInfo info = new StockInfo();
        info.setSkuId(skuId);
        info.setTotalStock(sku.getTotalStock());
        info.setStock(sku.getStock());
        info.setSold(sku.getSold());
        return ApiResult.ok(info);
    }
}
