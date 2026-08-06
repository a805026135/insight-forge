package com.insightforge.agent.core.planning;

import java.util.List;

/**
 * DSL 自编译结果（Fail-Close 门禁的返回值）。
 * valid=false 时 errors 列出所有不合规项；编排器据此阻断上线（绝不裸奔发布）。
 */
public record DslCompileResult(boolean valid, List<String> errors) {
    public static DslCompileResult ok() {
        return new DslCompileResult(true, List.of());
    }

    public static DslCompileResult fail(List<String> errors) {
        return new DslCompileResult(false, errors);
    }
}
