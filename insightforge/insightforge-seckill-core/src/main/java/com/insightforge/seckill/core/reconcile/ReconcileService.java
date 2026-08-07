package com.insightforge.seckill.core.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.redis.RedisStockService;
import com.insightforge.seckill.core.state.OrderStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对账服务（Phase 6）——系统「账对不对」的唯一裁判。
 *
 * 两类对账：
 *  - {@link #reconcileStockConsistency(long)} ：上线前「MySQL↔Redis 库存一致性」。
 *    遍历活动的每个 SKU，比较 DB 剩余库存与 Redis 剩余库存。Redis 不可用（local profile）
 *    时跳过 Redis 比对，降级为「DB 内部合法性」校验（stock∈[0,total] 且 sold=total-stock）。
 *  - {@link #reconcileFinalStock(long)} ：结算「最终库存 = 初始 - 成功下单量」。
 *    成功下单量 = 该 SKU 下 status != CANCEL 的订单的 <b>SUM(qty)</b>（订单台账，独立事实源）。
 *    拿到成功下单量后做<b>两类交叉核对</b>：① 与 Redis 权威库存比（LIVE 期间 Redis 是权威，
 *    能发现 Redis↔DB 漂移）；② 与 DB 剩余库存比。两者都通过才算账平。
 *    ——修正 REVIEW §3.2：原实现用「成功单数（计数）」比 DB stock，而 DB 里 stock=total-sold
 *    与 sold 同源更新，恒成立（恒等式），且完全不读 Redis，发现不了双写漂移；计数也不支持 qty>1。
 *
 * 为什么对账要做成独立 Service、而不是直接塞进扣减逻辑？
 *   对账是「离线 / 管理面」操作（上线前闸门、活动结束结算、定时巡检），不是用户抢购热路径。
 *   它与高并发扣减解耦，才能在不影响吞吐量的情况下反复、可观测地跑（也方便 Phase 7+ 的 Agent 复盘）。
 */
@Service
public class ReconcileService {

    private final ActivitySkuMapper skuMapper;
    private final OrdersMapper ordersMapper;
    /** Redis 库存服务：用 ObjectProvider 拿，local profile 下为 null（不存在该 Bean）。 */
    private final ObjectProvider<RedisStockService> redisStockService;

    public ReconcileService(ActivitySkuMapper skuMapper,
                            OrdersMapper ordersMapper,
                            ObjectProvider<RedisStockService> redisStockService) {
        this.skuMapper = skuMapper;
        this.ordersMapper = ordersMapper;
        this.redisStockService = redisStockService;
    }

    /**
     * 维度一：MySQL ↔ Redis 库存一致性（上线前对账 / 定时巡检）。
     *
     * @return 每个 SKU 一条明细；Redis 不可用时整体 passed 仍可能为 true（降级为 DB 内部校验）。
     */
    public ReconcileReport reconcileStockConsistency(long activityId) {
        ReconcileReport report = new ReconcileReport();
        List<ActivitySku> skus = skuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("activity_id", activityId));

        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs == null) {
            // local profile：没有 Redis，跳过双写比对，只校验 DB 内部库存自洽。
            report.add(new ReconcileItem("mysql-redis",
                    true, "Redis 不可用（local profile）：跳过 MySQL↔Redis 比对，降级校验 DB 内部库存"));
            for (ActivitySku sku : skus) {
                boolean dbOk = sku.getStock() != null && sku.getStock() >= 0
                        && sku.getTotalStock() != null && sku.getStock() <= sku.getTotalStock()
                        && sku.getSold() != null
                        && (sku.getTotalStock() - sku.getStock()) == sku.getSold();
                report.add(new ReconcileItem("db-internal:" + sku.getId(), dbOk,
                        String.format("total=%d, stock=%d, sold=%d",
                                sku.getTotalStock(), sku.getStock(), sku.getSold())));
            }
            return report;
        }

        // 非 local：真刀真枪比 MySQL 与 Redis 的剩余库存。
        // 注意 Redis 未预热时 getStock 返回 -1，会判为不一致 → 阻断上线（宁可错杀不放过）。
        for (ActivitySku sku : skus) {
            int redisStock = rs.getStock(activityId, sku.getId());
            boolean ok = redisStock == sku.getStock();
            report.add(new ReconcileItem("mysql-redis:" + sku.getId(), ok,
                    String.format("db_stock=%d, redis_stock=%d", sku.getStock(), redisStock)));
        }
        return report;
    }

    /**
     * 维度二：结算对账（活动结束 SETTLING 时）。
     * 以「订单台账 SUM(qty)（status != CANCEL）」为独立事实源，交叉核对：
     *   ① Redis 权威剩余库存（LIVE 期间 Redis 是唯一真实库存底座，Lua 直写；能发现 Redis↔DB 漂移）
     *   ② DB 剩余库存
     * 任一不一致则该 SKU 对账失败。
     *
     * 为什么不直接用 DB 的 sold 字段？DB 里 stock 与 sold 每次扣减/回补<b>同事务</b>更新，
     * 拿 sold 反推 stock 是「用自己证明自己」的恒等式（REVIEW §3.2）。订单台账由 Kafka 消费落库，
     * 与 Redis 扣减是<b>两条独立链路</b>，用台账 SUM(qty) 反推期望库存再去比对 Redis/DB，
     * 才能真正暴露双写漂移或丢失。
     */
    public ReconcileReport reconcileFinalStock(long activityId) {
        ReconcileReport report = new ReconcileReport();
        List<ActivitySku> skus = skuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("activity_id", activityId));
        RedisStockService rs = redisStockService.getIfAvailable();

        for (ActivitySku sku : skus) {
            // 独立事实源：订单台账里「未被取消」的订单的购买量之和（支持 qty>1）
            List<Orders> orders = ordersMapper.selectList(
                    new QueryWrapper<Orders>()
                            .eq("activity_id", activityId)
                            .eq("sku_id", sku.getSkuId())
                            .ne("status", OrderStatus.CANCEL.name()));
            int soldFromLedger = orders.stream().mapToInt(o -> o.getQty() == null ? 0 : o.getQty()).sum();

            int expectedStock = sku.getTotalStock() - soldFromLedger;

            // ① Redis 权威库存交叉核对（能发现 Redis↔DB 漂移）
            if (rs != null) {
                int redisStock = rs.getStock(activityId, sku.getId());
                boolean redisOk = redisStock == expectedStock;
                report.add(new ReconcileItem("final-redis:" + sku.getId(), redisOk,
                        String.format("total=%d, soldFromLedger=%d, expectedStock=%d, redisStock=%d",
                                sku.getTotalStock(), soldFromLedger, expectedStock, redisStock)));
            }

            // ② DB 剩余库存核对（Redis 不可用时这是唯一闸门；覆盖 DB 内部自洽）
            boolean dbOk = sku.getStock() == expectedStock;
            report.add(new ReconcileItem("final-db:" + sku.getId(), dbOk,
                    String.format("total=%d, soldFromLedger=%d, expectedStock=%d, dbStock=%d",
                            sku.getTotalStock(), soldFromLedger, expectedStock, sku.getStock())));
        }
        return report;
    }
}
