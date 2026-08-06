package com.insightforge.rule.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分段库存服务（大白话：一个 SKU 的库存被切成若干「段」seg_1、seg_2……）。
 *
 * Phase 9 的两条防超卖铁律都在这里落地：
 * ① <b>调拨只建新段 seg_{N+1}</b>（O(1) 追加，绝不重排 / 改写已有段，不影响正在抢购的用户）；
 * ② <b>两阶段预留 pending</b>：调拨先 mark 一笔 pending（库存没真增加），确认才入账，
 *    失败/超时只删 pending——库存回到调拨前，<b>绝不超卖</b>。
 *
 * 用户可抢的「可用库存 = 总库存 − 预占(pending)」，因此 pending 期间的调拨库存用户碰不到，
 * 从根本上杜绝「AI 调拨与用户抢购打架导致超卖」。
 *
 * 说明：这是教学确定性内存模型（单 JVM）；生产把 segments / pending 放 Redis 哈希即可，语义不变。
 */
@Component
public class StockSegmentService {

    private static final class SegmentState {
        final List<Integer> segments = new ArrayList<>();
        int pending = 0;   // 两阶段预留：已调拨但未确认/未回滚的数量
        int total = 0;     // 各段之和（含 pending）
    }

    private final ConcurrentHashMap<String, SegmentState> states = new ConcurrentHashMap<>();

    private static String key(long activityId, long skuId) {
        return activityId + ":" + skuId;
    }

    /**
     * 调拨：只建新段 seg_{N+1}，同时记 pending（两阶段预留第一阶段）。线程安全。
     *
     * @return 新建的段号与调拨量
     */
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

    /**
     * 两阶段预留第二阶段：确认调入，撤销 pending（库存正式对用户可用）。
     */
    public synchronized void confirmPending(long activityId, long skuId, int amount) {
        SegmentState st = states.get(key(activityId, skuId));
        if (st != null) {
            st.pending -= amount;
        }
    }

    /**
     * 补偿：调入取消——移除新段 + 撤销 pending（库存回到调拨前，绝不超卖）。
     */
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

    /**
     * 用户可抢的可用库存 = 总库存 − 预占（pending 期间用户不能抢调拨中的库存）。
     */
    public synchronized int available(long activityId, long skuId) {
        SegmentState st = states.get(key(activityId, skuId));
        return st == null ? 0 : st.total - st.pending;
    }

    public synchronized int segmentCount(long activityId, long skuId) {
        SegmentState st = states.get(key(activityId, skuId));
        return st == null ? 0 : st.segments.size();
    }

    public synchronized int total(long activityId, long skuId) {
        SegmentState st = states.get(key(activityId, skuId));
        return st == null ? 0 : st.total;
    }

    /** 清空（测试隔离用）。 */
    public void reset() {
        states.clear();
    }

    public record AllocResult(int newSegment, int amount) {
    }
}
