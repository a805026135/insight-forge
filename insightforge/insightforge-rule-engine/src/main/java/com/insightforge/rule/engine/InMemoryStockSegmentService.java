package com.insightforge.rule.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分段库存服务（内存态实现，默认 @Profile("!integration")）。
 *
 * 大白话：一个 SKU 的库存被切成若干「段」seg_1、seg_2……。Phase 9 的两条防超卖铁律都在这里落地：
 * ① <b>调拨只建新段 seg_{N+1}</b>（O(1) 追加，绝不重排 / 改写已有段，不影响正在抢购的用户）；
 * ② <b>两阶段预留 pending</b>：调拨先 mark 一笔 pending（库存没真增加），确认才入账，
 *    失败/超时只删 pending——库存回到调拨前，<b>绝不超卖</b>。
 *
 * 用户可抢的「可用库存 = 总库存 − 预占(pending)」，因此 pending 期间的调拨库存用户碰不到，
 * 从根本上杜绝「AI 调拨与用户抢购打架导致超卖」。
 *
 * 说明：这是教学确定性内存模型（单 JVM）；生产/集成环境由 {@code RedisStockSegmentService} 替换为
 * 同一份 Redis 库存，语义不变。
 */
@Component
@Profile("!integration")
public class InMemoryStockSegmentService implements StockSegmentService {

    private static final class SegmentState {
        final List<Integer> segments = new ArrayList<>();
        int pending = 0;   // 两阶段预留：已调拨但未确认/未回滚的数量
        int total = 0;     // 各段之和（含 pending）
    }

    private final ConcurrentHashMap<String, SegmentState> states = new ConcurrentHashMap<>();

    private static String key(long activityId, long skuId) {
        return activityId + ":" + skuId;
    }

    @Override
    public synchronized AllocResult allocateNewSegment(long activityId, long skuId, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("调拨数量不可为负（违反『库存不可透支』护栏）");
        }
        SegmentState st = states.computeIfAbsent(key(activityId, skuId), k -> new SegmentState());
        int newSeg = st.segments.size() + 1;
        st.segments.add(amount);
        st.total += amount;
        st.pending += amount;
        return new AllocResult(newSeg, amount);
    }

    @Override
    public synchronized void confirmPending(long activityId, long skuId, int amount) {
        SegmentState st = states.get(key(activityId, skuId));
        if (st != null) {
            st.pending -= amount;
        }
    }

    @Override
    public synchronized void releasePending(long activityId, long skuId, int amount) {
        SegmentState st = states.get(key(activityId, skuId));
        if (st != null) {
            if (!st.segments.isEmpty()) {
                st.segments.remove(st.segments.size() - 1);
            }
            st.total -= amount;
            st.pending -= amount;
        }
    }

    @Override
    public synchronized int available(long activityId, long skuId) {
        SegmentState st = states.get(key(activityId, skuId));
        return st == null ? 0 : st.total - st.pending;
    }

    @Override
    public synchronized int segmentCount(long activityId, long skuId) {
        SegmentState st = states.get(key(activityId, skuId));
        return st == null ? 0 : st.segments.size();
    }

    @Override
    public synchronized int total(long activityId, long skuId) {
        SegmentState st = states.get(key(activityId, skuId));
        return st == null ? 0 : st.total;
    }

    @Override
    public void reset() {
        states.clear();
    }
}
