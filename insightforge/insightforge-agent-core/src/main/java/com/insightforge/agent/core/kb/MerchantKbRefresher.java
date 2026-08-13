package com.insightforge.agent.core.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 商家知识库异步刷新器（Phase 19c）：
 * 商品增删改、动作执行后调用 {@link #schedule(long)}，异步重入库该商家的
 * 商品目录/历史决策/活动规则。60 秒去抖，避免连续变更触发多次向量化。
 */
@Component
public class MerchantKbRefresher {

    private static final Logger log = LoggerFactory.getLogger(MerchantKbRefresher.class);

    private static final long DEBOUNCE_MS = 60_000L;

    private final KnowledgeBaseSeeder seeder;
    private final long debounceMs;
    private final ConcurrentHashMap<Long, Long> lastRefreshAt = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> pending = new ConcurrentLinkedQueue<>();

    @Autowired
    public MerchantKbRefresher(KnowledgeBaseSeeder seeder) {
        this(seeder, DEBOUNCE_MS);
    }

    MerchantKbRefresher(KnowledgeBaseSeeder seeder, long debounceMs) {
        this.seeder = seeder;
        this.debounceMs = debounceMs;
    }

    /** 调度一次异步刷新；同一商家 60 秒内重复调用自动去抖。 */
    public void schedule(long merchantId) {
        long now = System.currentTimeMillis();
        lastRefreshAt.compute(merchantId, (mid, prev) -> {
            if (prev != null && now - prev < debounceMs) {
                return prev;
            }
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> refresh(mid));
            pending.add(future);
            future.whenComplete((ignored, error) -> pending.remove(future));
            return now;
        });
    }

    /** Package-private deterministic synchronization point for tests and controlled shutdown. */
    void awaitIdle() {
        for (CompletableFuture<Void> future : pending.toArray(CompletableFuture[]::new)) {
            future.join();
        }
    }

    private void refresh(long merchantId) {
        try {
            Map<String, Integer> result = seeder.reindexMerchant(merchantId);
            log.info("商家#{} 知识库自动刷新完成：{}", merchantId, result);
        } catch (Exception e) {
            log.warn("商家#{} 知识库自动刷新失败：{}", merchantId, e.getMessage());
        }
    }
}
