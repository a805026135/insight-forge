package com.insightforge.agent.core.evaluation;

/**
 * 定性补充评估器（大白话：客观数字之外，再补一句「人话总结」）。
 *
 * 双轨评估的设计原则——**客观硬指标优先，LLM 定性补充**。客观数字（毛利率/售罄率）
 * 是硬证据，定性只是帮人快速读懂结论。做成接口是为了把「LLM 调用」隔离在最后一环，
 * 且永不进秒杀热路径：生产可换成 DeepSeek 生成更细腻的复盘文案，本地用确定性桩。
 */
public interface QualitativeAssessor {

    /** 根据 A/B 结果产出一段定性结论（中文人话）。 */
    String assess(AbExperiment experiment);
}
