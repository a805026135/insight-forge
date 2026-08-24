package com.insightforge.seckill.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.mapper.ActivityMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Bounded-staleness cache for activity admission metadata.
 *
 * The authoritative stock remains in Redis Lua. This cache only removes the
 * per-order database read for the slowly-changing LIVE/STOPPED admission flag.
 * A two-second TTL bounds how long a status transition can remain visible while
 * Caffeine's atomic loader collapses a hot-key miss into one database query.
 */
@Service
public class ActivityCacheService {

    private final ActivityMapper mapper;
    private final boolean enabled;
    private final Cache<Long, Activity> cache = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(2))
            .build();

    public ActivityCacheService(
            ActivityMapper mapper,
            @Value("${insightforge.seckill.activity-cache-enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.enabled = enabled;
    }

    public Activity getActivity(Long activityId) {
        if (!enabled) {
            return mapper.selectById(activityId);
        }
        return cache.get(activityId, mapper::selectById);
    }

    public void evict(Long activityId) {
        cache.invalidate(activityId);
    }
}
