package com.insightforge.rule.engine;

/**
 * DSL→Drools 编译失败异常（Fail-Close 门禁：编译不过，DSL 一律不得上线）。
 *
 * 对应评估方案 §5 的「DSL Fail-Close 门禁（L3 硬门槛，发布前 100% 编译）」——
 * 与 Phase 8 的 {@code DslCompiler}（结构+业务校验）形成双重闸门：
 * DSL 先过 Phase 8 的白名单/护栏校验，再过本类的 DRL 编译校验，任一不过都阻断上线。
 */
public class DslCompileException extends RuntimeException {

    public DslCompileException(String message) {
        super(message);
    }
}
