package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.runtime.CriticVerdict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 独立验证器（设计文档 §4：把 Critic 升级为「程序 → 规则 → 模型/人工」三道校验）。
 *
 * 关键纪律：不让原 Agent 自评「我做对了吗」，由独立 Critic 校验。
 *   - 第一道（确定性程序）：DSL 结构/语法合法——已由 {@link DslCompiler} 完成，这里直接采信其结果。
 *   - 第二道（业务规则）：REPRICE≥成本、REALLOCATE≥0——同样由 DslCompiler 完成（Fail-Close 硬门槛）。
 *   - 第三道（模型/人工）：内容/风险评审。本地桩默认放行；requireHumanReview=true 时模拟「需人工复核」
 *     （生产接模型评审或 HITL 界面，高风险动作如跨活动预算再分配必须人工 approve）。
 *
 * 三道全过才放行发布；任一不过 → CriticVerdict.fail，编排器据此反思重试或正确失败。
 */
@Component
public class IndependentCritic {

    /** 测试/演示开关：模拟第三道「需人工复核」拦截。 */
    @Value("${insightforge.agent.planning.require-human-review:false}")
    private boolean requireHumanReview;

    public CriticVerdict verify(GuardrailPolicy policy, DslCompileResult compile) {
        // 第一道 + 第二道（确定性，能程序判定的绝不先问模型）
        if (!compile.valid()) {
            return CriticVerdict.fail(compile.errors(), 0.98, "HIGH");
        }
        // 第三道（模型/人工）——本地桩：默认放行；高风险动作/低置信度时由上层置 requireHumanReview 触发
        if (requireHumanReview) {
            return CriticVerdict.fail(
                    List.of("需人工复核（高风险动作 / 低置信度，按 HITL 三要素交人）"), 0.6, "MEDIUM");
        }
        return CriticVerdict.pass();
    }
}
