package com.insightforge.agent.core.kb;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantKbRefresherTest {

    @Test
    void scheduleDebouncesRepeatedCallsWithinWindow() {
        KnowledgeBaseSeeder seeder = mock(KnowledgeBaseSeeder.class);
        when(seeder.reindexMerchant(anyLong()))
                .thenReturn(Map.of("productChunks", 2, "decisionChunks", 1, "activityChunks", 1));
        MerchantKbRefresher refresher = new MerchantKbRefresher(seeder, 60_000L);

        refresher.schedule(1L);
        refresher.schedule(1L);
        refresher.schedule(1L);

        refresher.awaitIdle();
        verify(seeder).reindexMerchant(1L);
    }

    @Test
    void scheduleAllowsRefreshAfterWindowElapses() {
        KnowledgeBaseSeeder seeder = mock(KnowledgeBaseSeeder.class);
        when(seeder.reindexMerchant(anyLong()))
                .thenReturn(Map.of("productChunks", 2, "decisionChunks", 1, "activityChunks", 1));
        MerchantKbRefresher refresher = new MerchantKbRefresher(seeder, 10L);

        refresher.schedule(2L);
        refresher.awaitIdle();
        try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        refresher.schedule(2L);
        refresher.awaitIdle();
        verify(seeder, org.mockito.Mockito.times(2)).reindexMerchant(2L);
    }
}
