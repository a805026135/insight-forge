package com.insightforge.api.dto;

import java.util.List;

/**
 * 商家可读的对账摘要：不暴露 MySQL/Redis 内部实现细节，只表达业务结论。
 */
public record ReconcileSummary(
        boolean passed,
        String activityStatus,
        List<String> messages
) {
}
