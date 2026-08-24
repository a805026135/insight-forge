package com.insightforge.seckill.core.cache;

import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.mapper.ActivityMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityCacheServiceTest {

    @Test
    void enabledCacheCollapsesRepeatedHotKeyReads() {
        ActivityMapper mapper = mock(ActivityMapper.class);
        Activity activity = new Activity();
        activity.setId(7L);
        when(mapper.selectById(7L)).thenReturn(activity);
        ActivityCacheService service = new ActivityCacheService(mapper, true);

        assertSame(activity, service.getActivity(7L));
        assertSame(activity, service.getActivity(7L));

        verify(mapper, times(1)).selectById(7L);
    }

    @Test
    void disabledCacheKeepsComparableDatabaseBaseline() {
        ActivityMapper mapper = mock(ActivityMapper.class);
        Activity activity = new Activity();
        activity.setId(7L);
        when(mapper.selectById(7L)).thenReturn(activity);
        ActivityCacheService service = new ActivityCacheService(mapper, false);

        service.getActivity(7L);
        service.getActivity(7L);

        verify(mapper, times(2)).selectById(7L);
    }

    @Test
    void evictionForcesReloadAfterStatusChange() {
        ActivityMapper mapper = mock(ActivityMapper.class);
        Activity first = new Activity();
        Activity second = new Activity();
        when(mapper.selectById(7L)).thenReturn(first, second);
        ActivityCacheService service = new ActivityCacheService(mapper, true);

        assertSame(first, service.getActivity(7L));
        service.evict(7L);
        assertSame(second, service.getActivity(7L));
    }
}
