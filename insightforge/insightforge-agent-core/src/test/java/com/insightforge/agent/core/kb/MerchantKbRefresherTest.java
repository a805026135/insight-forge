package com.insightforge.agent.core.kb;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
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

        verify(seeder, timeout(5000).times(1)).reindexMerchant(1L);
    }

    @Test
    void scheduleAllowsRefreshAfterWindowElapses() {
        KnowledgeBaseSeeder seeder = mock(KnowledgeBaseSeeder.class);
        when(seeder.reindexMerchant(anyLong()))
                .thenReturn(Map.of("productChunks", 2, "decisionChunks", 1, "activityChunks", 1));
        MerchantKbRefresher refresher = new MerchantKbRefresher(seeder, 10L);

        refresher.schedule(2L);
        verify(seeder, timeout(5000).times(1)).reindexMerchant(2L);
        assertDoesNotThrow(() -> Thread.sleep(30));
        refresher.schedule(2L);
        verify(seeder, timeout(5000).times(2)).reindexMerchant(2L);
    }
}
