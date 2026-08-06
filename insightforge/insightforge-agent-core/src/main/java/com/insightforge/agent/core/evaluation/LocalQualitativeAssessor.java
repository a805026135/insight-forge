package com.insightforge.agent.core.evaluation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 本地确定性定性评估器（大白话：不用真 LLM，按客观数字套模板生成结论）。
 *
 * 为什么不用 LLM 实时生成？① 评估是离线批处理，不需要秒回；② 模板确定性、零成本、
 * 可单测、绝不幻觉——恰恰符合本项目「LLM 不进热路径 / 不编造成功」的纪律。
 * 生产环境把 assess 换成 DeepSeek 调用即可（接口不变）。
 */
@Component
public class LocalQualitativeAssessor implements QualitativeAssessor {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String assess(AbExperiment e) {
        String verdict;
        if ("AI_CONTROL".equals(e.winner())) {
            verdict = "AI 控盘在清货效率与盈利性上均优于人工规则，建议推进灰度全量";
        } else {
            verdict = "人工规则未显著劣化，维持对照、暂缓全量";
        }
        return String.format(
                "双轨评估（客观优先）：实验组 AI 控盘售罄率 %s%%（相对人工 %s）、毛利率 %s%%；%s。",
                e.ai().sellThroughRate().multiply(HUNDRED),
                e.sellThroughLiftPct(),
                e.ai().grossMarginRate().multiply(HUNDRED),
                verdict
        );
    }
}
